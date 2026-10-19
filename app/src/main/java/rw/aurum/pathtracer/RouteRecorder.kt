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
    /**
     * In a moving elevator. [rise] in meters so far, negative going down. [auto] when the
     * barometer noticed the ride, so the user can say it wasn't one.
     */
    data class Riding(val rise: Float, val auto: Boolean) : RecordStage
    data class Saved(val routeId: String) : RecordStage
}

/** "Which floor are you on?" with a [guess] (empty if unknown) and how far the ride went. */
data class FloorQuestion(val guess: String, val ride: String)

data class RecordUi(
    val stage: RecordStage = RecordStage.Capture(PhotoFor.Start),
    val hint: String? = null,
    val meters: Float = 0f,
    /** Photos taken so far, including the one being locked on. */
    val photos: Int = 0,
    val photo: File? = null,
    val floor: String = "",
    /** After an elevator ride the barometer noticed: ask which floor this is. */
    val askFloor: FloorQuestion? = null,
)

/**
 * Records a route as legs, each measured from a photographed sign. All state lives on the GL
 * thread; the UI talks to it through the public methods, which queue events.
 */
class RouteRecorder(private val store: RouteStore) : ArRenderer() {

    private val _state = MutableStateFlow(RecordUi())
    val state: StateFlow<RecordUi> = _state

    private class LegDraft(val photo: String, val width: Float, val entry: LegEntry, var anchor: Anchor) {
        val points = mutableListOf<TrailPoint>()
        /** When each point was recorded, to drop the ones taken inside an elevator. */
        val times = mutableListOf<Long>()
        val steps = mutableListOf<Step>()
        fun toLeg() = Leg(photo, width, entry, points.toList(), steps.toList())

        /** Drops points recorded at or after [t], keeping at least the first. */
        fun trimFrom(t: Long) {
            val keep = times.indexOfFirst { it >= t }.let { if (it < 0) points.size else it.coerceAtLeast(1) }
            while (points.size > keep) {
                points.removeAt(points.lastIndex)
                times.removeAt(times.lastIndex)
            }
            steps.removeAll { it.at >= points.size }
        }
    }

    private val trailRenderer = TrailRenderer()
    private val floorTracker = FloorTracker()
    private var database: AugmentedImageDatabase? = null

    private var stage: RecordStage = RecordStage.Capture(PhotoFor.Start)
    private val legs = mutableListOf<Leg>()
    /** The leg being walked. During a checkpoint photo the previous leg keeps recording. */
    private var current: LegDraft? = null
    private var captureRequested = false
    private var measureTries = 0
    private var pendingImage: String? = null
    private var pendingPhoto: String? = null
    private var attempts = 0
    private var lockFrames = 0
    private var lockStartedAt = 0L
    private var hint: String? = null
    private var hintUntil = 0L
    private var floor = ""

    private val elevator = ElevatorDetector()
    private val keeper = AnchorKeeper { name, fields -> debug?.event(name, *fields) }
    /** The leg that was being walked when an elevator ride was noticed, until the ride is confirmed. */
    private var ridingFrom: LegDraft? = null
    private var rideStartedAt = 0L
    /** Meters ridden since the last leg ended, over one or more stops of the elevator. */
    private var elevatorRise = 0f
    private var askFloor: FloorQuestion? = null
    /** The floor before the elevator ride, to count floors from. */
    private var rideBaseFloor = ""
    /** The floor the user typed with Floor change → Elevator, which beats any guess. */
    private var manualFloor: String? = null

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    fun takePhoto() = post { if (stage is RecordStage.Capture) captureRequested = true }

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
        if (change == FloorChange.Elevator) {
            rideBaseFloor = this.floor
            manualFloor = floor.takeIf { it.isNotBlank() }
        }
        this.floor = floor
        debug?.event("floor_change", "change" to change.name.lowercase(), "floor" to floor, "leg" to legs.size, "points" to leg.points.size)
        if (change == FloorChange.Elevator) {
            legs += leg.toLeg()
            leg.anchor.detach()
            current = null
            elevatorRise = 0f
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

    /** The user confirmed or corrected the floor the elevator stopped at. */
    fun confirmFloor(label: String) = post {
        askFloor = null
        floor = label.trim()
        val s = stage
        if (s is RecordStage.Capture && s.reason is PhotoFor.Elevator) stage = RecordStage.Capture(PhotoFor.Elevator(floor))
        debug?.event("floor_confirmed", "floor" to floor)
    }

    /** It wasn't an elevator: carry on with the leg as if nothing happened. */
    fun notAnElevator() = post {
        if ((stage as? RecordStage.Riding)?.auto == true) resumeAfterFalseRide("user")
    }

    fun finish(name: String) = post {
        if (stage is RecordStage.Saved) return@post
        ridingFrom?.let { endRiddenLeg(it) }
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

        if (tracking) elevator.position(SystemClock.elapsedRealtime(), camera.pose.tx(), camera.pose.tz())
        takeAltitudes { t, m -> elevator.altitude(t, m)?.let(::onElevator) }
        if (stage is RecordStage.Riding) stage = (stage as RecordStage.Riding).copy(rise = elevatorRise + elevator.rise)
        debug?.show("baro_m", elevator.altitude)

        if (tracking && captureRequested) capture(session, frame)
        val s = stage
        if (tracking && s is RecordStage.Locking) frameHint = frameHint ?: lockOn(session, s)

        val leg = current
        if (leg != null) {
            keeper.check(session, camera, leg.anchor, SystemClock.elapsedRealtime())?.let { replacement ->
                leg.anchor.detach()
                leg.anchor = replacement
            }
        }
        if (tracking && leg != null && leg.anchor.trackingState == TrackingState.TRACKING) {
            val cam = camera.pose
            val local = leg.anchor.pose.inverse()
                .transformPoint(floatArrayOf(cam.tx(), floorTracker.floorY(session, camera), cam.tz()))
            val head = TrailPoint(local[0], local[1], local[2])
            val last = leg.points.lastOrNull()
            if (last == null || head.distanceTo(last) >= STEP_METERS) {
                leg.points += head
                leg.times += SystemClock.elapsedRealtime()
            }

            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
            leg.anchor.pose.toMatrix(model, 0)
            val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
            trailRenderer.draw(leg.points, head, model, view, projection, seconds)
        }
        publish(frameHint)
    }

    private fun onElevator(e: ElevatorDetector.Event) {
        when (e) {
            is ElevatorDetector.Event.RideStarted -> {
                debug?.event("ride_start", "up" to e.up, "stage" to stage.javaClass.simpleName.lowercase(), "lag_ms" to SystemClock.elapsedRealtime() - e.startedAt)
                val s = stage
                when {
                    // Walking, or framing a checkpoint photo: the leg ends where the ride began.
                    current != null && (s == RecordStage.Walking || (s as? RecordStage.Capture)?.reason == PhotoFor.Checkpoint) -> {
                        ridingFrom = current
                        rideStartedAt = e.startedAt
                        current = null
                        elevatorRise = 0f
                        rideBaseFloor = floor
                        manualFloor = null
                        stage = RecordStage.Riding(0f, auto = true)
                    }
                    // Already between legs because of an elevator (tapped, or an earlier stop of this ride).
                    s is RecordStage.Capture && s.reason is PhotoFor.Elevator -> stage = RecordStage.Riding(elevatorRise, auto = false)
                }
                if (stage is RecordStage.Riding) askFloor = null
            }
            is ElevatorDetector.Event.RideEnded -> {
                if (stage !is RecordStage.Riding) return
                elevatorRise += e.rise
                ridingFrom?.let { endRiddenLeg(it) }
                // Keep a floor the user typed; otherwise guess one from the height and ask.
                val typed = manualFloor
                val guess = typed ?: guessFloor(rideBaseFloor, elevatorRise)
                debug?.event("ride_end", "rise_m" to e.rise, "total_m" to elevatorRise, "floors" to floorsIn(elevatorRise), "guess" to guess)
                if (typed == null) askFloor = FloorQuestion(guess.orEmpty(), rideSummary(elevatorRise))
                floor = guess.orEmpty()
                stage = RecordStage.Capture(PhotoFor.Elevator(guess.orEmpty()))
            }
            ElevatorDetector.Event.RideCancelled -> {
                debug?.event("ride_cancel")
                if ((stage as? RecordStage.Riding)?.auto == true && ridingFrom != null) {
                    resumeAfterFalseRide("detector")
                } else if (stage is RecordStage.Riding) {
                    stage = RecordStage.Capture(PhotoFor.Elevator(floor))
                }
            }
        }
    }

    /** The elevator ride is real: the leg it interrupted ends where the ride began. */
    private fun endRiddenLeg(leg: LegDraft) {
        leg.trimFrom(rideStartedAt - TRIM_BEFORE_RIDE_MS)
        legs += leg.toLeg()
        leg.anchor.detach()
        ridingFrom = null
    }

    private fun resumeAfterFalseRide(why: String) {
        debug?.event("ride_false", "by" to why)
        keeper.reset()
        current = ridingFrom
        ridingFrom = null
        stage = RecordStage.Walking
    }

    /**
     * Takes the sign photo once its real size is known. ARCore never locked on to a photo of
     * unknown size in field tests, so without a size there is no photo: measuring is retried for
     * about a second, then the user is asked to step closer.
     */
    private fun capture(session: Session, frame: Frame) {
        val s = stage as? RecordStage.Capture ?: return
        val size = SignPhoto.measure(frame, width, height)
        if (size == null) {
            if (++measureTries < MEASURE_TRIES) return
            captureRequested = false
            measureTries = 0
            notice("Couldn't tell how far away the sign is. Step a little closer, hold still and try again")
            debug?.event("photo_unmeasured", "why" to SignPhoto.lastFailure)
            return
        }
        val bitmap = SignPhoto.capture(frame, width, height) ?: return
        captureRequested = false
        val measureFrames = measureTries
        measureTries = 0
        val db = database ?: AugmentedImageDatabase(session).also { database = it }
        val index = legs.size + if (current != null) 1 else 0
        val imageName = "leg${index}_${attempts++}"
        val measured = size.width
        try {
            db.addImage(imageName, bitmap, measured)
        } catch (e: ImageInsufficientQualityException) {
            notice("Not enough detail in that photo. Try a sign with text or a picture")
            debug?.event("photo_rejected", "leg" to index, "size" to "${bitmap.width}x${bitmap.height}", "measured_m" to measured)
            return
        }
        debug?.event(
            "photo", "leg" to index, "size" to "${bitmap.width}x${bitmap.height}", "measured_m" to measured,
            "how" to size.how, "after_frames" to measureFrames, "for" to s.reason.javaClass.simpleName.lowercase(),
        )
        debug?.show("measured", measured)
        session.configure(session.config.apply { augmentedImageDatabase = db })
        val photo = "leg$index.jpg"
        saveJpeg(bitmap, File(store.draft, photo))
        pendingImage = imageName
        pendingPhoto = photo
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
        if (++lockFrames < LOCK_FRAMES) return null

        val pose = levelled(image.centerPose)
        val anchor = session.createAnchor(pose)
        val previous = current
        val entry = when (val reason = s.reason) {
            PhotoFor.Start -> LegEntry.Start
            is PhotoFor.Elevator -> LegEntry.Elevator(reason.floor, elevatorRise.takeIf { it != 0f })
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
        keeper.reset()
        elevatorRise = 0f
        manualFloor = null
        askFloor = null
        debug?.event(
            "locked",
            "leg" to legs.size,
            "after_ms" to SystemClock.elapsedRealtime() - lockStartedAt,
            "width_m" to image.extentX,
            "entry" to entry.javaClass.simpleName.lowercase(),
            "offset" to (entry as? LegEntry.Checkpoint)?.fromPrevious?.let { "%.2f,%.2f,%.2f,%.1fdeg".format(java.util.Locale.US, it.x, it.y, it.z, Math.toDegrees(it.yaw.toDouble())) },
        )
        stage = RecordStage.Walking
        hint = null
        return null
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
            askFloor = askFloor,
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
        const val LOCK_FRAMES = 6
        /** About a second of frames to find the sign's size before giving up. */
        const val MEASURE_TRIES = 30
        /** Points from the last moments before a ride are usually already inside the elevator. */
        const val TRIM_BEFORE_RIDE_MS = 1500L
        const val SLOW_LOCK_MS = 8000L
        const val MIN_ROUTE_METERS = 1f
        const val NOTICE_MS = 5000L
    }
}
