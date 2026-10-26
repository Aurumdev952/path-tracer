package rw.aurum.pathtracer

import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Camera
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps only an image's heading, so the anchor frame stays upright whether the image lies on the
 * floor or hangs on a wall.
 */
fun levelled(pose: Pose): Pose {
    val x = pose.xAxis
    val yaw = atan2(-x[2], x[0])
    return Pose(pose.translation, floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2)))
}

fun YawPose.toPose() = Pose(floatArrayOf(x, y, z), floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2)))

/** Reads an upright pose; any tilt is dropped. */
fun Pose.toYawPose() = YawPose(tx(), ty(), tz(), 2 * atan2(qy(), qw()))

/** A sign's upright pose, and whether its heading came from the wall it hangs on. */
class SignPose(val pose: Pose, val onWall: Boolean)

/**
 * The upright pose of [image]: its heading from the wall behind it when ARCore has found that
 * wall (see [wallYaw]), otherwise from the image itself.
 */
fun signPose(session: Session, image: AugmentedImage): SignPose {
    val center = image.centerPose
    val c = center.translation
    val wall = session.getAllTrackables(Plane::class.java)
        .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.VERTICAL }
        .mapNotNull { plane ->
            val n = plane.centerPose.yAxis
            val p = plane.centerPose.translation
            val gap = (c[0] - p[0]) * n[0] + (c[1] - p[1]) * n[1] + (c[2] - p[2]) * n[2]
            val onPlane = Pose.makeTranslation(c[0] - gap * n[0], c[1] - gap * n[1], c[2] - gap * n[2])
            plane.takeIf { abs(gap) < WALL_GAP && it.isPoseInPolygon(onPlane) }?.let { it to abs(gap) }
        }
        .minByOrNull { it.second }?.first
    val yaw = wall?.let { wallYaw(center.xAxis, center.yAxis, it.centerPose.yAxis) }
        ?: return SignPose(levelled(center), false)
    return SignPose(Pose(c, floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2))), true)
}

/** How far in front of or behind a wall a sign on it can be, in meters. */
private const val WALL_GAP = 0.1f

/**
 * Finds the floor under the phone. It uses the lowest detected floor plane right below the
 * camera, so tables nearby are ignored and stairs are followed. With no plane in sight it falls
 * back on the last measured eye height.
 */
class FloorTracker {
    private var eyeHeight = TYPICAL_EYE_HEIGHT

    fun floorY(session: Session, camera: Camera): Float {
        val cam = camera.pose
        val eye = cam.ty()
        val floor = session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    eye - it.centerPose.ty() in MIN_EYE_HEIGHT..MAX_EYE_HEIGHT &&
                    it.isPoseInPolygon(Pose.makeTranslation(cam.tx(), it.centerPose.ty(), cam.tz()))
            }
            .minOfOrNull { it.centerPose.ty() }
        if (floor != null) eyeHeight = eye - floor
        return floor ?: (eye - eyeHeight)
    }

    private companion object {
        const val MIN_EYE_HEIGHT = 0.7f
        const val MAX_EYE_HEIGHT = 2.2f
        const val TYPICAL_EYE_HEIGHT = 1.4f
    }
}

/**
 * Keeps a route frame going when ARCore stops tracking its anchor while the camera still tracks,
 * which happens when walking into a new area: without this, nothing more gets recorded or drawn.
 * It remembers where the anchor last was, and after a second without it, while the camera still
 * tracks, puts a new anchor there. The camera's own tracking carries any walking or turning done
 * meanwhile; placing the anchor relative to the camera instead would drag the route along.
 */
class AnchorKeeper(private val onEvent: (String, Array<Pair<String, Any?>>) -> Unit = { _, _ -> }) {
    private var lastPose: Pose? = null
    private var lostSince = 0L
    private var lastState: TrackingState? = null

    fun reset() {
        lastPose = null
        lostSince = 0L
        lastState = null
    }

    /** Returns a replacement anchor when [anchor] has been lost long enough, else null. */
    fun check(session: Session, camera: Camera, anchor: Anchor, now: Long): Anchor? {
        val state = anchor.trackingState
        if (state != lastState) {
            onEvent("anchor", arrayOf("state" to state.name.lowercase()))
            lastState = state
        }
        if (camera.trackingState != TrackingState.TRACKING) return null
        if (state == TrackingState.TRACKING) {
            lastPose = anchor.pose
            lostSince = 0L
            return null
        }
        val pose = lastPose ?: return null
        if (lostSince == 0L) lostSince = now
        if (now - lostSince < REANCHOR_AFTER_MS) return null
        onEvent("reanchored", arrayOf("lost_ms" to now - lostSince, "was" to state.name.lowercase()))
        lostSince = 0L
        lastState = null
        return session.createAnchor(pose)
    }

    private companion object {
        const val REANCHOR_AFTER_MS = 1000L
    }
}
