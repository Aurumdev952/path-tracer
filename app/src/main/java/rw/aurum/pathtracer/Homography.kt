package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Maps the unit square onto a four-sided shape: (0,0) to the first corner, (1,0) to the second,
 * (1,1) to the third and (0,1) to the fourth. A flat square seen at an angle is exactly such a
 * shape, so sampling through this mapping undoes the angle. Null if the corners are degenerate.
 */
class Homography private constructor(private val m: FloatArray) {

    /** Writes the point the unit-square point ([u], [v]) maps to into [out]. */
    fun map(u: Float, v: Float, out: FloatArray) {
        val w = m[6] * u + m[7] * v + 1f
        out[0] = (m[0] * u + m[1] * v + m[2]) / w
        out[1] = (m[3] * u + m[4] * v + m[5]) / w
    }

    companion object {
        /** [quad] holds x, y for the four corners in order (eight values). */
        fun squareTo(quad: FloatArray): Homography? {
            val (x0, y0, x1, y1) = quad
            val x2 = quad[4]; val y2 = quad[5]; val x3 = quad[6]; val y3 = quad[7]
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < 1e-9f) return null
            val g = (dx3 * dy2 - dx2 * dy3) / den
            val h = (dx1 * dy3 - dx3 * dy1) / den
            return Homography(
                floatArrayOf(
                    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                    y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                    g, h,
                )
            )
        }
    }
}

/**
 * The corners of a square of [side] meters lying on the plane through [center] with normal
 * [normal], kept upright: its top edge is level when [up] (world up) isn't along the normal,
 * otherwise it follows [fallbackUp] (the screen's up). Corners in order top-left, top-right,
 * bottom-right, bottom-left as seen from [eye], 12 values.
 */
fun squareOnPlane(center: FloatArray, normal: FloatArray, side: Float, up: FloatArray, fallbackUp: FloatArray, eye: FloatArray): FloatArray? {
    var n = normalize(normal) ?: return null
    // Face the viewer, or the square comes out mirrored.
    if (dot(n, sub(eye, center)) < 0f) n = scale(n, -1f)
    val right = normalize(cross(up, n))?.takeIf { abs(dot(normalize(up)!!, n)) < 0.9f }
        ?: normalize(cross(fallbackUp, n))
        ?: return null
    val top = cross(n, right)
    val h = side / 2
    fun corner(r: Float, t: Float) = add(center, add(scale(right, r * h), scale(top, t * h)))
    return corner(-1f, 1f) + corner(1f, 1f) + corner(1f, -1f) + corner(-1f, -1f)
}

/**
 * Where the world point [p] lands in the camera image, in pixels, given the world-to-camera
 * matrix [toCamera] (column-major, camera looking down -z with +y up) and the image's focal
 * lengths and principal point. Null when the point is behind the camera.
 */
fun projectToImage(toCamera: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float, p: FloatArray): FloatArray? {
    val x = toCamera[0] * p[0] + toCamera[4] * p[1] + toCamera[8] * p[2] + toCamera[12]
    val y = toCamera[1] * p[0] + toCamera[5] * p[1] + toCamera[9] * p[2] + toCamera[13]
    val z = toCamera[2] * p[0] + toCamera[6] * p[1] + toCamera[10] * p[2] + toCamera[14]
    if (z > -1e-3f) return null
    return floatArrayOf(cx + fx * x / -z, cy - fy * y / -z)
}

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
private fun normalize(a: FloatArray): FloatArray? {
    val l = sqrt(dot(a, a))
    return if (l < 1e-6f) null else scale(a, 1f / l)
}
