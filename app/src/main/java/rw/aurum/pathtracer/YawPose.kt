package rw.aurum.pathtracer

import kotlin.math.cos
import kotlin.math.sin

/**
 * An upright pose: a position plus a turn of [yaw] radians about the vertical axis. It uses the
 * same convention as an ARCore Pose with rotation (0, sin(yaw/2), 0, cos(yaw/2)).
 */
data class YawPose(val x: Float, val y: Float, val z: Float, val yaw: Float) {

    /** Maps a point from this pose's frame into the parent frame. */
    fun apply(p: TrailPoint): TrailPoint {
        val c = cos(yaw)
        val s = sin(yaw)
        return TrailPoint(c * p.x + s * p.z + x, p.y + y, -s * p.x + c * p.z + z)
    }

    /** This pose followed by [child], which is given in this pose's frame. */
    fun compose(child: YawPose): YawPose {
        val origin = apply(TrailPoint(child.x, child.y, child.z))
        return YawPose(origin.x, origin.y, origin.z, yaw + child.yaw)
    }

    fun inverse(): YawPose {
        val c = cos(yaw)
        val s = sin(yaw)
        // Rotate -translation by -yaw.
        return YawPose(-(c * x - s * z), -y, -(s * x + c * z), -yaw)
    }

    companion object {
        val Identity = YawPose(0f, 0f, 0f, 0f)
    }
}
