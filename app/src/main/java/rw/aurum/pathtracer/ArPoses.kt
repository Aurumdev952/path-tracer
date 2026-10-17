package rw.aurum.pathtracer

import com.google.ar.core.Camera
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
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
