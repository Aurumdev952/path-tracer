package rw.aurum.pathtracer

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import kotlin.math.hypot

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
class RouteRecorder(
    private val store: RouteStore,
    /** Save full camera views at the start of each leg, for the localization server. */
    private val captureViews: Boolean,
) : ArRenderer() {

    private val _state = MutableStateFlow(RecordUi())
    val state: StateFlow<RecordUi> = _state

    private class LegDraft(
        val index: Int,
        val photo: String,
        val width: Float,
        val entry: LegEntry,
        val anchor: Anchor,
        val sign: Boolean,
    ) {
        val points = mutableListOf<TrailPoint>()
        val steps = mutableListOf<Step>()
        val views = mutableListOf<CameraView>()
        val viewPoses = mutableListOf<Pose>()
        val startedAt = SystemClock.elapsedRealtime()
        var lastViewAt = 0L
        fun toLeg() = Leg(photo, width, entry, points.toList(), steps.toList(), sign, views.toList())
    }

    private val trailRenderer = TrailRenderer()
    private val floorTracker = FloorTracker()
    private var database: AugmentedImageDatabase? = null

    private var stage: RecordStage = RecordStage.Capture(PhotoFor.Start)
    private val legs = mutableListOf<Leg>()
    /** The leg being walked. During a checkpoint photo the previous leg keeps recording. */
    private var current: LegDraft? = null
    private var captureRequested = false
    private var sceneRequested = false
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

    /** No usable sign here: start the leg from where the phone is, to be found by the server later. */
    fun useScene() = post { if (stage is RecordStage.Capture && captureViews) sceneRequested = true }

    fun retake() = post {
        val s = stage
        if (s is RecordStage.Locking) {
            debug?.event("retake", "after_ms" to SystemClock.elapsedRealtime() - lockStartedAt, "seen_frames" to lockFrames)
            stage = RecordStage.Capture(s.reason)
            hint = null
        }
    }

    /** Elevators end the leg (tracking breaks inside); stairs and escalators are marked and walked through. */
    fun changeFloor(change: FloorChange, floor: String) = post {
        val leg = current ?: return@post
        if (stage != RecordStage.Walking) return@post
        this.floor = floor
        debug?.event("floor_change", "change" to change.name.lowercase(), "floor" to floor, "leg" to legs.size, "points" to leg.points.size)
        if (change == FloorChange.Elevator) {
            legs += leg.toLeg()
            leg.anchor.detach()
            current = null
            stage = RecordStage.Capture(PhotoFor.Elevator(floor))
        } else {
            leg.steps += Step(leg.points.size.coerceAtLeast(1) - 1, change, floor)
        }
    }

    fun addCheckpoint() = post {
        if (stage == RecordStage.Walking) {
            debug?.event("checkpoint")
            stage = RecordStage.Capture(PhotoFor.Checkpoint)
        }
    }

    /** Cancels a checkpoint photo and carries on walking. */
    fun cancelCheckpoint() = post {
        val s = stage
        val reason = (s as? RecordStage.Capture)?.reason ?: (s as? RecordStage.Locking)?.reason
        if (reason == PhotoFor.Checkpoint) {
            debug?.event("checkpoint_cancel")
            stage = RecordStage.Walking
            hint = null
        }
    }

    fun finish(name: String) = post {
        if (stage is RecordStage.Saved) return@post
        if (walkedMeters() < MIN_ROUTE_METERS) {
            notice("Walk a few steps first")
            debug?.event("finish_too_short", "meters" to walkedMeters())
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
        debug?.event("saved", "route" to route.id, "legs" to kept.size, "meters" to route.lengthMeters, "floors" to route.floors.joinToString(","))
        stage = RecordStage.Saved(route.id)
    }

    override fun onCreateGl() = trailRenderer.create()

    override fun onFrame(session: Session, frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        var frameHint = trackingHint(camera)

        if (tracking && captureRequested) capture(session, frame)
        if (tracking && sceneRequested) startFromScene(session, frame)
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
            if (captureViews) collectView(frame, leg)
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
            debug?.event("photo_rejected", "leg" to index, "size" to "${bitmap.width}x${bitmap.height}", "measured_m" to measured)
            return
        }
        debug?.event("photo", "leg" to index, "size" to "${bitmap.width}x${bitmap.height}", "measured_m" to measured, "for" to s.reason.javaClass.simpleName.lowercase())
        debug?.show("measured", measured)
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
            if (!s.slow && SystemClock.elapsedRealtime() - lockStartedAt > SLOW_LOCK_MS) {
                stage = s.copy(slow = true)
                debug?.event("lock_slow", "seen_frames" to lockFrames)
            }
            return null
        }
        if (lockFrames == 0) debug?.event("photo_seen", "after_ms" to SystemClock.elapsedRealtime() - lockStartedAt)
        if (++lockFrames < (if (pendingMeasured) LOCK_FRAMES_MEASURED else LOCK_FRAMES)) return null

        val entry = startLeg(
            session, levelled(image.centerPose), s.reason, pendingPhoto!!,
            image.extentX.takeIf { it > 0f && it.isFinite() } ?: 0f, sign = true,
        ) ?: return null
        debug?.event(
            "locked",
            "leg" to legs.size,
            "after_ms" to SystemClock.elapsedRealtime() - lockStartedAt,
            "measured" to pendingMeasured,
            "width_m" to image.extentX,
            "entry" to entry.javaClass.simpleName.lowercase(),
            "offset" to (entry as? LegEntry.Checkpoint)?.fromPrevious?.let { "%.2f,%.2f,%.2f,%.1fdeg".format(java.util.Locale.US, it.x, it.y, it.z, Math.toDegrees(it.yaw.toDouble())) },
        )
        stage = RecordStage.Walking
        hint = null
        return null
    }

    /** Starts a leg whose frame is [pose], ending the previous one if this is a checkpoint. */
    private fun startLeg(session: Session, pose: Pose, reason: PhotoFor, photo: String, width: Float, sign: Boolean): LegEntry? {
        val previous = current
        val entry = when (reason) {
            PhotoFor.Start -> LegEntry.Start
            is PhotoFor.Elevator -> LegEntry.Elevator(reason.floor)
            PhotoFor.Checkpoint -> {
                if (previous == null) return null
                LegEntry.Checkpoint(previous.anchor.pose.inverse().compose(pose).toYawPose())
            }
        }
        val anchor = session.createAnchor(pose)
        val index = legs.size + if (previous != null) 1 else 0
        if (previous != null) {
            legs += previous.toLeg()
            previous.anchor.detach()
        }
        current = LegDraft(index, photo, width, entry, anchor, sign)
        return entry
    }

    private fun startFromScene(session: Session, frame: Frame) {
        val s = stage as? RecordStage.Capture ?: return
        // A photo for the follower to recognise the spot; it isn't used for tracking.
        val bitmap = SignPhoto.capture(frame, width, height) ?: return
        sceneRequested = false
        val index = legs.size + if (current != null) 1 else 0
        val photo = "leg$index.jpg"
        saveJpeg(bitmap, File(store.draft, photo))
        // The screen's axes, levelled: the frame's heading is where the phone faces.
        val entry = startLeg(session, levelled(frame.camera.displayOrientedPose), s.reason, photo, 0f, sign = false) ?: return
        debug?.event("scene_start", "leg" to index, "entry" to entry.javaClass.simpleName.lowercase())
        stage = RecordStage.Walking
        hint = null
    }

    /**
     * Saves up to a few camera views in the first seconds of a leg, each from a clearly different
     * spot or angle, so a follower's camera has more to match against.
     */
    private fun collectView(frame: Frame, leg: LegDraft) {
        val now = SystemClock.elapsedRealtime()
        if (leg.views.size >= MAX_VIEWS || now - leg.startedAt > VIEW_WINDOW_MS || now - leg.lastViewAt < VIEW_GAP_MS) return
        val pose = frame.camera.pose
        val distinct = leg.viewPoses.all { seen ->
            val moved = hypot(hypot(pose.tx() - seen.tx(), pose.ty() - seen.ty()), pose.tz() - seen.tz())
            moved > VIEW_MIN_MOVE || angleBetween(pose, seen) > VIEW_MIN_TURN
        }
        if (!distinct) return
        val grabbed = CameraViews.grab(frame, frame.camera, withDepth = true) ?: return
        leg.lastViewAt = now
        val view = CameraViews.save(grabbed, leg.anchor.pose, store.draft, "leg${leg.index}_v${leg.views.size}")
        leg.views += view
        leg.viewPoses += pose
        debug?.event("view", "leg" to leg.index, "n" to leg.views.size, "depth" to (grabbed.depth != null), "jpeg_kb" to grabbed.jpeg.size / 1024)
    }

    /** Angle between two camera's viewing directions, in radians. */
    private fun angleBetween(a: Pose, b: Pose): Float {
        val za = a.zAxis
        val zb = b.zAxis
        val dot = (za[0] * zb[0] + za[1] * zb[1] + za[2] * zb[2]).coerceIn(-1f, 1f)
        return kotlin.math.acos(dot)
    }

    private fun publish(frameHint: String?) {
        debug?.let { d ->
            d.show("stage", stage.javaClass.simpleName.lowercase())
            d.show("legs", legs.size + (if (current != null) 1 else 0))
            d.show("points", current?.points?.size ?: 0)
            d.show("meters", walkedMeters())
            if (stage is RecordStage.Locking) d.show("lock_frames", lockFrames)
        }
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
        const val MAX_VIEWS = 3
        const val VIEW_WINDOW_MS = 5000L
        const val VIEW_GAP_MS = 400L
        const val VIEW_MIN_MOVE = 0.15f
        const val VIEW_MIN_TURN = 0.14f
        const val SLOW_LOCK_MS = 8000L
        const val MIN_ROUTE_METERS = 1f
        const val NOTICE_MS = 5000L
    }
}
