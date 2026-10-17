package rw.aurum.pathtracer

import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot

sealed interface FollowStage {
    /** Waiting to see the photo that starts [stretch]: the entrance, or a sign after the elevator. */
    data class Searching(val stretch: Int) : FollowStage
    data object Guiding : FollowStage
    data object Arrived : FollowStage
}

data class FollowUi(
    val stage: FollowStage = FollowStage.Searching(0),
    val hint: String? = null,
    val instruction: Instruction? = null,
    val remaining: Float = 0f,
    val floor: String = "",
    val offRoute: Boolean = false,
    /** Which way to turn to see the path, in radians (positive left), or null when it's in view. */
    val arrow: Float? = null,
    /** Goes up by one each time the phone should buzz (a turn or floor change coming up). */
    val buzz: Int = 0,
    /** Near the end of a stretch that leads to an elevator, offer to move on by hand. */
    val canSkip: Boolean = false,
)

/**
 * Guides a follower along a saved route. Photos in the route are ARCore Augmented Images named
 * `leg<index>`; seeing one fixes where the route is in the room.
 */
class RouteFollower(route: Route) : ArRenderer() {

    private val _state = MutableStateFlow(FollowUi())
    val state: StateFlow<FollowUi> = _state

    private val navigator = Navigator(route)
    private val guideRenderer = GuideRenderer()
    private val floorTracker = FloorTracker()

    private var stage: FollowStage = FollowStage.Searching(0)
    private var anchor: Anchor? = null
    private var lastSnap: Pose? = null
    private var lastSnapFrame = 0L
    private var frameCount = 0L
    private var buzz = 0
    private var buzzedFor = -1
    private var buzzedStretch = -1

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    /** The follower says they're out of the elevator already: wait for the next photo. */
    fun skip() = post {
        if (stage == FollowStage.Guiding && !navigator.isLastStretch) {
            navigator.skip()
            stage = FollowStage.Searching(navigator.stretch)
            dropAnchor()
        }
    }

    override fun onCreateGl() = guideRenderer.create()

    override fun onFrame(session: Session, frame: Frame) {
        frameCount++
        val camera = frame.camera
        var hint = trackingHint(camera)
        var guidance: Guidance? = null
        var arrow: Float? = null

        if (camera.trackingState == TrackingState.TRACKING && stage != FollowStage.Arrived) {
            snapToPhotos(session, frame)
            val anchor = anchor
            if (stage == FollowStage.Guiding && anchor != null) {
                if (anchor.trackingState == TrackingState.TRACKING) {
                    val g = guide(session, camera, anchor)
                    guidance = g
                    arrow = arrowTo(camera, anchor, g)
                } else {
                    hint = hint ?: "Hold on, finding the path again"
                }
            }
        }
        publish(hint, guidance, arrow)
    }

    /** Re-anchors the route whenever one of its photos is in clear view. */
    private fun snapToPhotos(session: Session, frame: Frame) {
        val minStretch = when (val s = stage) {
            is FollowStage.Searching -> s.stretch
            else -> navigator.stretch
        }
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (image.trackingState != TrackingState.TRACKING ||
                image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING
            ) continue
            val leg = image.name.removePrefix("leg").toIntOrNull() ?: continue
            val stretchIndex = navigator.stretchOf(leg)
            if (stretchIndex < minStretch) continue
            val inStretch = navigator.stretches[stretchIndex].images.first { it.leg == leg }.pose
            val world = levelled(image.centerPose).compose(inStretch.inverse().toPose())

            val moving = stretchIndex != navigator.stretch || stage != FollowStage.Guiding
            if (!moving && !movedEnough(world)) continue
            navigator.lockOn(leg)
            stage = FollowStage.Guiding
            dropAnchor()
            anchor = session.createAnchor(world)
            lastSnap = world
            lastSnapFrame = frameCount
            return
        }
    }

    private fun movedEnough(world: Pose): Boolean {
        val last = lastSnap ?: return true
        if (frameCount - lastSnapFrame < SNAP_EVERY_FRAMES) return false
        val d = hypot(hypot(world.tx() - last.tx(), world.ty() - last.ty()), world.tz() - last.tz())
        val turn = abs(world.toYawPose().yaw - last.toYawPose().yaw)
        return d > SNAP_METERS || turn > SNAP_RADIANS
    }

    private fun dropAnchor() {
        anchor?.detach()
        anchor = null
        lastSnap = null
    }

    private fun guide(session: Session, camera: Camera, anchor: Anchor): Guidance {
        val cam = camera.pose
        val local = anchor.pose.inverse().transformPoint(floatArrayOf(cam.tx(), floorTracker.floorY(session, camera), cam.tz()))
        val g = navigator.update(TrailPoint(local[0], local[1], local[2]))

        // Buzz once when each turn, floor change or end comes within reach.
        if (g.maneuverDistance < BUZZ_DISTANCE && !g.offRoute &&
            (g.maneuverKey != buzzedFor || navigator.stretch != buzzedStretch)
        ) {
            buzz++
            buzzedFor = g.maneuverKey
            buzzedStretch = navigator.stretch
        }

        val stretch = navigator.stretches[navigator.stretch]
        val path = stretch.path
        val from = g.progress - BEHIND
        val to = g.progress + LOOKAHEAD
        val endsHere = path.length <= to + 0.5f
        val turn = navigator.maneuversAhead(from + BEHIND, to).filterIsInstance<Maneuver.Turn>().firstOrNull()
        val marker = if (endsHere) {
            path.pointAt(path.length) to (if (navigator.isLastStretch) GuideRenderer.Marker.Destination else GuideRenderer.Marker.Elevator)
        } else {
            null
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        anchor.pose.toMatrix(model, 0)
        val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
        guideRenderer.draw(path.slice(from, to), if (endsHere) to + 10f else to, turn, marker, model, view, projection, seconds)

        if (navigator.atStretchEnd(g)) {
            if (navigator.isLastStretch) {
                stage = FollowStage.Arrived
            } else {
                navigator.skip()
                stage = FollowStage.Searching(navigator.stretch)
                dropAnchor()
            }
        }
        return g
    }

    /** The turn needed to face the path, or null when the path is roughly ahead. */
    private fun arrowTo(camera: Camera, anchor: Anchor, g: Guidance): Float? {
        val target = anchor.pose.transformPoint(floatArrayOf(g.target.x, g.target.y, g.target.z))
        val cam = camera.pose
        val z = cam.zAxis
        var fx = -z[0]
        var fz = -z[2]
        if (hypot(fx, fz) < 0.3f) {
            // Phone pointing at the floor: use the top of the screen as "ahead".
            val up = camera.displayOrientedPose.yAxis
            fx = up[0]
            fz = up[2]
        }
        val dx = target[0] - cam.tx()
        val dz = target[2] - cam.tz()
        if (hypot(dx, dz) < 0.5f) return null
        val bearing = signedAngle(fx, fz, dx, dz)
        return bearing.takeIf { abs(it) > ARROW_ANGLE }
    }

    private fun publish(hint: String?, g: Guidance?, arrow: Float?) {
        val stage = stage
        val previous = _state.value
        val next = FollowUi(
            stage = stage,
            hint = hint,
            instruction = g?.instruction ?: previous.instruction.takeIf { stage == FollowStage.Guiding },
            remaining = g?.remaining ?: previous.remaining,
            floor = g?.floor ?: previous.floor,
            offRoute = g?.offRoute ?: false,
            arrow = arrow,
            buzz = buzz,
            canSkip = stage == FollowStage.Guiding && !navigator.isLastStretch &&
                g != null && navigator.stretches[navigator.stretch].path.length - g.progress < SKIP_WITHIN,
        )
        if (next != previous) _state.value = next
    }

    private companion object {
        const val LOOKAHEAD = 7f
        const val BEHIND = 0.6f
        const val BUZZ_DISTANCE = 3f
        const val SKIP_WITHIN = 10f
        const val SNAP_EVERY_FRAMES = 15
        const val SNAP_METERS = 0.04f
        const val SNAP_RADIANS = 0.03f
        val ARROW_ANGLE = Math.toRadians(35.0).toFloat()
    }
}
