package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The heading of a sign on a wall, taken from the wall. ARCore finds a whole wall's direction
 * from many points, but a sign's from a small picture, which comes out several degrees off
 * depending on where it's seen from.
 *
 * [imageX] runs along the sign's width and [imageNormal] out of it; [wallNormal] is out of the
 * wall. Returns the yaw [levelled] would give a sign lying flat on that wall, or null when the
 * sign isn't on a wall or doesn't sit flat on this one.
 */
fun wallYaw(imageX: FloatArray, imageNormal: FloatArray, wallNormal: FloatArray): Float? {
    if (abs(imageNormal[1]) > MAX_TILT || abs(wallNormal[1]) > MAX_TILT) return null
    val nx = wallNormal[0]
    val nz = wallNormal[2]
    val n = hypot(nx, nz)
    val agree = abs(imageNormal[0] * nx + imageNormal[2] * nz) / (n * hypot(imageNormal[0], imageNormal[2]))
    if (agree < MIN_AGREE) return null
    // Level and along the wall, pointing the same way as the sign's width.
    var x = nz / n
    var z = -nx / n
    if (x * imageX[0] + z * imageX[2] < 0f) {
        x = -x
        z = -z
    }
    return atan2(-z, x)
}

/** Normals more than about 30 degrees off level aren't a wall's. */
private const val MAX_TILT = 0.5f

/** cos(15 degrees): a sign turned further from the wall than this isn't on it. */
private const val MIN_AGREE = 0.966f
