package rw.aurum.pathtracer

import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the app is in relating the trail to the physical room. */
sealed interface Room {
    /** New trail: waiting for the floor (cloud) or the marker to drop the start point. */
    data object FindingStart : Room
    /** Saved trail: waiting to recognise the start point again. */
    data object Relocalizing : Room
    /** Walking; the start point is being saved to the cloud in the background. */
    data object Anchoring : Room
    /** The start point can be found again: this trail will reappear on the next visit. */
    data object Remembered : Room
    /** Walking works, but the room could not be saved to the cloud. */
    data class CloudFailed(val reason: String) : Room
    /** The saved room could not be found again. */
    data class NotFound(val reason: String) : Room
}

data class UiState(
    val room: Room = Room.FindingStart,
    val memory: Memory = Memory.Marker,
    val hint: String? = null,
    val meters: Float = 0f,
    val recording: Boolean = true,
)

/**
 * The free-walk trail mode. All AR and trail state lives on the GL thread; the UI and ARCore
 * callbacks talk to it only through [post], and it reports back through [state].
 */
class PathTracer(private val store: TrailStore) : ArRenderer() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Latest trail, readable from any thread for saving on pause. */
    @Volatile var trail: Trail = Trail(emptyList(), Memory.Marker)
        private set

    private val trailRenderer = TrailRenderer()

    private var started = false
    private var room: Room = Room.FindingStart
    private var recording = true
    private var anchor: Anchor? = null
    private var anchorPlacedAt = 0L
    private var hosting: HostCloudAnchorFuture? = null
    private var resolving: ResolveCloudAnchorFuture? = null
    private var unsavedPoints = 0

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    fun toggleRecording() = post { recording = !recording }

    fun retrySaveRoom() = post { if (room is Room.CloudFailed) room = Room.Anchoring }

    fun useMemory(memory: Memory) = post {
        if (room == Room.FindingStart) trail = trail.copy(memory = memory)
    }

    fun newTrail() = post {
        cancelCloudTasks()
        anchor?.detach()
        anchor = null
        trail = Trail(emptyList(), trail.memory)
        store.clear()
        room = Room.FindingStart
        recording = true
    }

    fun persist() {
        if (trail.points.isNotEmpty()) store.save(trail)
    }

    override fun onCreateGl() = trailRenderer.create()

    override fun onFrame(session: Session, frame: Frame) {
        if (!started) start(session)

        val camera = frame.camera
        var hint = trackingHint(camera)
        if (camera.trackingState == TrackingState.TRACKING) {
            findStart(session, frame, camera)
            val anchor = anchor
            if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                val cam = camera.pose
                val local = anchor.pose.inverse().transformPoint(floatArrayOf(cam.tx(), floorY(session, camera), cam.tz()))
                val head = TrailPoint(local[0], local[1], local[2])
                record(head)
                hint = hint ?: saveRoomIfReady(session, camera, anchor)
                draw(camera, anchor, head)
            }
        }
        publish(hint)
    }

    private fun start(session: Session) {
        started = true
        val saved = store.load()
        if (saved == null || (saved.memory == Memory.Cloud && saved.cloudAnchorId == null)) {
            store.clear()
            return
        }
        trail = saved
        room = Room.Relocalizing
        val cloudId = saved.cloudAnchorId ?: return
        resolving = session.resolveCloudAnchorAsync(cloudId) { resolved, state ->
            post { onResolved(resolved, state) }
        }
    }

    /** Drops the start anchor (new trail) or re-finds it from the marker (saved trail). */
    private fun findStart(session: Session, frame: Frame, camera: Camera) {
        val lookingForMarker = trail.memory == Memory.Marker &&
            (room == Room.FindingStart || room == Room.Relocalizing)
        if (lookingForMarker) {
            val marker = session.getAllTrackables(AugmentedImage::class.java).firstOrNull {
                it.trackingState == TrackingState.TRACKING &&
                    it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
            } ?: return
            anchor = session.createAnchor(levelled(marker.centerPose))
            room = Room.Remembered
            return
        }
        if (room != Room.FindingStart) return
        val floorHit = frame.hitTest(width / 2f, height / 2f).firstOrNull { hit ->
            val plane = hit.trackable as? Plane ?: return@firstOrNull false
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.isPoseInPolygon(hit.hitPose) &&
                hit.hitPose.ty() < camera.pose.ty() - MIN_EYE_HEIGHT
        } ?: return
        anchor = session.createAnchor(Pose.makeTranslation(floorHit.hitPose.translation))
        anchorPlacedAt = SystemClock.elapsedRealtime()
        room = Room.Anchoring
    }

    /** World height of the floor: the lowest detected floor plane, or a typical eye height below the camera. */
    private fun floorY(session: Session, camera: Camera): Float {
        val eye = camera.pose.ty()
        return session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    it.centerPose.ty() < eye - MIN_EYE_HEIGHT
            }
            .minOfOrNull { it.centerPose.ty() } ?: (eye - TYPICAL_EYE_HEIGHT)
    }

    private fun record(head: TrailPoint) {
        if (!recording) return
        val last = trail.points.lastOrNull()
        if (last != null && head.distanceTo(last) < STEP_METERS) return
        trail = trail.copy(points = trail.points + head)
        if (++unsavedPoints >= SAVE_EVERY) {
            store.save(trail)
            unsavedPoints = 0
        }
    }

    /** Hosts the start anchor once ARCore has seen enough of its surroundings. Returns a hint. */
    private fun saveRoomIfReady(session: Session, camera: Camera, anchor: Anchor): String? {
        if (room != Room.Anchoring) return null
        if (hosting != null) return "Saving this room…"
        if (SystemClock.elapsedRealtime() - anchorPlacedAt < HOST_DELAY_MS) return null
        val quality = session.estimateFeatureMapQualityForHosting(camera.pose)
        if (quality == Session.FeatureMapQuality.INSUFFICIENT) {
            return "Look around the start point so this room can be remembered"
        }
        hosting = session.hostCloudAnchorAsync(anchor, CLOUD_TTL_DAYS) { id, state ->
            post { onHosted(id, state) }
        }
        return "Saving this room…"
    }

    private fun onHosted(cloudId: String?, state: CloudAnchorState) {
        hosting = null
        if (room != Room.Anchoring) return
        if (state == CloudAnchorState.SUCCESS && cloudId != null) {
            trail = trail.copy(cloudAnchorId = cloudId)
            store.save(trail)
            room = Room.Remembered
        } else {
            room = Room.CloudFailed(describe(state))
        }
    }

    private fun onResolved(resolved: Anchor?, state: CloudAnchorState) {
        resolving = null
        if (room != Room.Relocalizing) {
            resolved?.detach()
            return
        }
        if (state == CloudAnchorState.SUCCESS && resolved != null) {
            anchor = resolved
            room = Room.Remembered
        } else {
            room = Room.NotFound(describe(state))
        }
    }

    private fun cancelCloudTasks() {
        hosting?.cancel()
        resolving?.cancel()
        hosting = null
        resolving = null
    }

    private fun draw(camera: Camera, anchor: Anchor, head: TrailPoint) {
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        anchor.pose.toMatrix(model, 0)
        val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
        trailRenderer.draw(trail.points, head.takeIf { recording }, model, view, projection, seconds)
    }

    private fun publish(hint: String?) {
        val next = UiState(
            room = room,
            memory = trail.memory,
            hint = hint,
            meters = (trail.lengthMeters * 10).toInt() / 10f,
            recording = recording,
        )
        if (next != _state.value) _state.value = next
    }

    private fun describe(state: CloudAnchorState): String = when (state) {
        CloudAnchorState.ERROR_NOT_AUTHORIZED -> "ARCore cloud isn't set up for this app (see README)"
        CloudAnchorState.ERROR_RESOURCE_EXHAUSTED -> "Cloud quota exhausted"
        CloudAnchorState.ERROR_HOSTING_DATASET_PROCESSING_FAILED -> "Not enough visual detail. Look around and retry"
        CloudAnchorState.ERROR_CLOUD_ID_NOT_FOUND -> "The saved room has expired"
        CloudAnchorState.ERROR_HOSTING_SERVICE_UNAVAILABLE -> "Cloud service unreachable. Check your connection"
        else -> state.name.lowercase().replace('_', ' ')
    }

    private companion object {
        const val STEP_METERS = 0.12f
        const val MIN_EYE_HEIGHT = 0.7f
        const val TYPICAL_EYE_HEIGHT = 1.4f
        const val SAVE_EVERY = 15
        const val HOST_DELAY_MS = 1500L
        const val CLOUD_TTL_DAYS = 365
    }
}
