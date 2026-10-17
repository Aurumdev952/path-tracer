package rw.aurum.pathtracer

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** Why the recorder is asking for a photo. */
sealed interface PhotoFor {
    data object Start : PhotoFor
    data class Elevator(val floor: String) : PhotoFor
    data object Checkpoint : PhotoFor
}

sealed interface RecordStage {
    /** Waiting for the user to frame a sign and press the shutter. */
    data class Capture(val reason: PhotoFor) : RecordStage
    /** Photo taken; waiting for ARCore to find it in the room and measure it. */
    data class Locking(val reason: PhotoFor, val slow: Boolean) : RecordStage
    data object Walking : RecordStage
    data class Saved(val routeId: String) : RecordStage
}

data class RecordUi(
    val stage: RecordStage = RecordStage.Capture(PhotoFor.Start),
    val hint: String? = null,
    val meters: Float = 0f,
    /** Photos taken so far, including the one being locked on. */
    val photos: Int = 0,
    val photo: File? = null,
    val floor: String = "",
)

/**
 * Records a route as legs, each measured from a photographed sign. All state lives on the GL
 * thread; the UI talks to it through the public methods, which queue events.
 */
class RouteRecorder(private val store: RouteStore) : ArRenderer() {

    private val _state = MutableStateFlow(RecordUi())
    val state: StateFlow<RecordUi> = _state

    private class LegDraft(val photo: String, val width: Float, val entry: LegEntry, val anchor: Anchor) {
        val points = mutableListOf<TrailPoint>()
        val steps = mutableListOf<Step>()
        fun toLeg() = Leg(photo, width, entry, points.toList(), steps.toList())
    }

    private val trailRenderer = TrailRenderer()
    private val floorTracker = FloorTracker()
    private var database: AugmentedImageDatabase? = null

    private var stage: RecordStage = RecordStage.Capture(PhotoFor.Start)
    private val legs = mutableListOf<Leg>()
    /** The leg being walked. During a checkpoint photo the previous leg keeps recording. */
    private var current: LegDraft? = null
    private var captureRequested = false
    private var pendingImage: String? = null
    private var pendingPhoto: String? = null
    /** The pending photo's size was measured, so ARCore needs no extra looks to place it. */
    private var pendingMeasured = false
    private var attempts = 0
    private var lockFrames = 0
    private var lockStartedAt = 0L
    private var hint: String? = null
    private var hintUntil = 0L
    private var floor = ""

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    fun takePhoto() = post { if (stage is RecordStage.Capture) captureRequested = true }

    fun retake() = post {
        val s = stage
        if (s is RecordStage.Locking) {
            stage = RecordStage.Capture(s.reason)
            hint = null
        }
    }

    /** Elevators end the leg (tracking breaks inside); stairs and escalators are marked and walked through. */
    fun changeFloor(change: FloorChange, floor: String) = post {
        val leg = current ?: return@post
        if (stage != RecordStage.Walking) return@post
        this.floor = floor
        if (change == FloorChange.Elevator) {
            legs += leg.toLeg()
            leg.anchor.detach()
            current = null
            stage = RecordStage.Capture(PhotoFor.Elevator(floor))
        } else {
            leg.steps += Step(leg.points.size.coerceAtLeast(1) - 1, change, floor)
        }
    }

    fun addCheckpoint() = post { if (stage == RecordStage.Walking) stage = RecordStage.Capture(PhotoFor.Checkpoint) }

    /** Cancels a checkpoint photo and carries on walking. */
    fun cancelCheckpoint() = post {
        val s = stage
        val reason = (s as? RecordStage.Capture)?.reason ?: (s as? RecordStage.Locking)?.reason
        if (reason == PhotoFor.Checkpoint) {
            stage = RecordStage.Walking
            hint = null
        }
    }

    fun finish(name: String) = post {
        if (stage is RecordStage.Saved) return@post
        if (walkedMeters() < MIN_ROUTE_METERS) {
            notice("Walk a few steps first")
            return@post
        }
        current?.let { leg ->
            legs += leg.toLeg()
            leg.anchor.detach()
            current = null
        }
        // A photo locked on just before Arrived may have no points yet; it adds nothing to follow.
        val kept = legs.filter { it.points.isNotEmpty() }
        val route = Route(UUID.randomUUID().toString(), name.trim().ifEmpty { "My route" }, System.currentTimeMillis(), kept)
        store.saveDraft(route)
        stage = RecordStage.Saved(route.id)
    }

    override fun onCreateGl() = trailRenderer.create()

    override fun onFrame(session: Session, frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        var frameHint = trackingHint(camera)

        if (tracking && captureRequested) capture(session, frame)
        val s = stage
        if (tracking && s is RecordStage.Locking) frameHint = frameHint ?: lockOn(session, s)

        val leg = current
        if (tracking && leg != null && leg.anchor.trackingState == TrackingState.TRACKING) {
            val cam = camera.pose
            val local = leg.anchor.pose.inverse()
                .transformPoint(floatArrayOf(cam.tx(), floorTracker.floorY(session, camera), cam.tz()))
            val head = TrailPoint(local[0], local[1], local[2])
            val last = leg.points.lastOrNull()
            if (last == null || head.distanceTo(last) >= STEP_METERS) leg.points += head

            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
            leg.anchor.pose.toMatrix(model, 0)
            val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
            trailRenderer.draw(leg.points, head, model, view, projection, seconds)
        }
        publish(frameHint)
    }

    private fun capture(session: Session, frame: Frame) {
        val s = stage as? RecordStage.Capture ?: return
        val bitmap = SignPhoto.capture(frame, width, height) ?: return
        captureRequested = false
        val db = database ?: AugmentedImageDatabase(session).also { database = it }
        val index = legs.size + if (current != null) 1 else 0
        val imageName = "leg${index}_${attempts++}"
        val measured = SignPhoto.measureWidth(frame, width, height)
        try {
            if (measured != null) db.addImage(imageName, bitmap, measured) else db.addImage(imageName, bitmap)
        } catch (e: ImageInsufficientQualityException) {
            notice("Not enough detail in that photo. Try a sign with text or a picture")
            return
        }
        session.configure(session.config.apply { augmentedImageDatabase = db })
        val photo = "leg$index.jpg"
        saveJpeg(bitmap, File(store.draft, photo))
        pendingImage = imageName
        pendingPhoto = photo
        pendingMeasured = measured != null
        lockFrames = 0
        lockStartedAt = SystemClock.elapsedRealtime()
        hint = null
        stage = RecordStage.Locking(s.reason, slow = false)
    }

    /** Waits until ARCore tracks the new photo steadily, then starts a leg from it. */
    private fun lockOn(session: Session, s: RecordStage.Locking): String? {
        val image = session.getAllTrackables(AugmentedImage::class.java).firstOrNull {
            it.name == pendingImage && it.trackingState == TrackingState.TRACKING &&
                it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
        }
        if (image == null) {
            if (!s.slow && SystemClock.elapsedRealtime() - lockStartedAt > SLOW_LOCK_MS) stage = s.copy(slow = true)
            return null
        }
        if (++lockFrames < (if (pendingMeasured) LOCK_FRAMES_MEASURED else LOCK_FRAMES)) return null

        val pose = levelled(image.centerPose)
        val anchor = session.createAnchor(pose)
        val previous = current
        val entry = when (val reason = s.reason) {
            PhotoFor.Start -> LegEntry.Start
            is PhotoFor.Elevator -> LegEntry.Elevator(reason.floor)
            PhotoFor.Checkpoint -> {
                if (previous == null) return null
                LegEntry.Checkpoint(previous.anchor.pose.inverse().compose(pose).toYawPose())
            }
        }
        if (previous != null) {
            legs += previous.toLeg()
            previous.anchor.detach()
        }
        current = LegDraft(pendingPhoto!!, image.extentX.takeIf { it > 0f && it.isFinite() } ?: 0f, entry, anchor)
        stage = RecordStage.Walking
        hint = null
        return null
    }

    private fun publish(frameHint: String?) {
        val photos = legs.size + (if (current != null) 1 else 0) + (if (stage is RecordStage.Locking) 1 else 0)
        val next = RecordUi(
            stage = stage,
            hint = frameHint ?: hint?.takeIf { SystemClock.elapsedRealtime() < hintUntil },
            meters = (walkedMeters() * 10).toInt() / 10f,
            photos = photos,
            photo = pendingPhoto?.let { File(store.draft, it) }.takeIf { stage is RecordStage.Locking },
            floor = floor,
        )
        if (next != _state.value) _state.value = next
    }

    /** Shows [text] for a few seconds. */
    private fun notice(text: String) {
        hint = text
        hintUntil = SystemClock.elapsedRealtime() + NOTICE_MS
    }

    private fun walkedMeters(): Float = legs.sumOf { it.lengthMeters.toDouble() }.toFloat() +
        (current?.points?.zipWithNext { a, b -> a.distanceTo(b) }?.sum() ?: 0f)

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private companion object {
        const val STEP_METERS = 0.12f
        const val LOCK_FRAMES = 20
        const val LOCK_FRAMES_MEASURED = 6
        const val SLOW_LOCK_MS = 8000L
        const val MIN_ROUTE_METERS = 1f
        const val NOTICE_MS = 5000L
    }
}
