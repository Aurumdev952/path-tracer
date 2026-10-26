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

/**
 * Where two rays (origin [oa] or [ob], direction [da] or [db]) cross the plane through [p] with
 * normal [n], and how far apart those points are. Null if either ray runs nearly along the plane
 * (more than about 75 degrees off its normal) or meets it behind its origin.
 */
fun spanOnPlane(oa: FloatArray, da: FloatArray, ob: FloatArray, db: FloatArray, p: FloatArray, n: FloatArray): Float? {
    fun dot(u: FloatArray, v: FloatArray) = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
    fun len(u: FloatArray) = kotlin.math.sqrt(dot(u, u))
    fun hit(o: FloatArray, d: FloatArray): FloatArray? {
        val along = dot(d, n)
        if (kotlin.math.abs(along) < GRAZING * len(d) * len(n)) return null
        val t = (dot(p, n) - dot(o, n)) / along
        if (t <= 0f) return null
        return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
    }
    val a = hit(oa, da) ?: return null
    val b = hit(ob, db) ?: return null
    return len(floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2]))
}

/** cos(75 degrees): rays closer to the plane than this give unreliable widths. */
private const val GRAZING = 0.26f

/** The turn from heading [from] to heading [to], in radians between -pi and pi. */
fun turnBetween(from: Float, to: Float): Float = kotlin.math.atan2(sin(to - from), cos(to - from))

/**
 * The average of [poses] when they agree: every position within [maxShift] meters of the average
 * and every heading within [maxTurn] radians of it. Null when they don't, or there are none.
 */
fun steadyPose(poses: List<YawPose>, maxShift: Float, maxTurn: Float): YawPose? {
    if (poses.isEmpty()) return null
    val x = poses.map { it.x }.average().toFloat()
    val y = poses.map { it.y }.average().toFloat()
    val z = poses.map { it.z }.average().toFloat()
    val yaw = kotlin.math.atan2(poses.sumOf { sin(it.yaw).toDouble() }, poses.sumOf { cos(it.yaw).toDouble() }).toFloat()
    val mean = YawPose(x, y, z, yaw)
    val steady = poses.all { p ->
        val turn = kotlin.math.atan2(sin(p.yaw - yaw), cos(p.yaw - yaw))
        kotlin.math.abs(turn) <= maxTurn &&
            kotlin.math.sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y) + (p.z - z) * (p.z - z)) <= maxShift
    }
    return mean.takeIf { steady }
}
