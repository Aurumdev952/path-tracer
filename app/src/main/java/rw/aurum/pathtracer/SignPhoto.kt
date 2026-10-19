package rw.aurum.pathtracer

import android.graphics.Bitmap
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Takes a photo of whatever is inside the on-screen framing box, straight from ARCore's camera image. */
object SignPhoto {
    /** The framing box is a square this fraction of the screen width, centered on the screen. */
    const val BOX = 0.72f
    private const val MAX_SIDE = 1024
    private const val EDGE = 0.05f
    private const val MIN_WIDTH = 0.05f
    private const val MAX_WIDTH = 4f

    /** The real width the framing box covers, and how it was found ("edges" or "center"). */
    data class Size(val width: Float, val how: String)

    /**
     * Measures the real width of what the framing box covers, in meters. First from the surface
     * at the box's left edge, right edge and center (detected walls and floors, or depth), which
     * must agree on one flat surface. Failing that, from the distance to the center alone, assuming
     * the sign faces the camera. Null with the reason in [lastFailure] when neither works.
     */
    fun measure(frame: Frame, viewWidth: Int, viewHeight: Int): Size? {
        val side = viewWidth * BOX
        val left = (viewWidth - side) / 2
        val y = viewHeight / 2f
        fun surface(x: Float, anyPoint: Boolean = false): HitResult? = frame.hitTest(x, y).firstOrNull { hit ->
            when (val t = hit.trackable) {
                is Plane -> t.isPoseInPolygon(hit.hitPose)
                is DepthPoint -> true
                is Point -> anyPoint
                else -> false
            }
        }
        val a = surface(left + side * EDGE)?.hitPose
        val b = surface(left + side * (1 - EDGE))?.hitPose
        val c = surface(viewWidth / 2f)
        if (a != null && b != null && c != null) {
            val across = dist(a, b)
            // The center must sit near the middle of the two edges, or the box spans a corner or a gap.
            val mid = Pose.makeTranslation((a.tx() + b.tx()) / 2, (a.ty() + b.ty()) / 2, (a.tz() + b.tz()) / 2)
            val width = across / (1 - 2 * EDGE)
            if (dist(c.hitPose, mid) <= across * 0.15f && width in MIN_WIDTH..MAX_WIDTH) return Size(width, "edges")
        }
        val center = c ?: surface(viewWidth / 2f, anyPoint = true)
        if (center == null) {
            lastFailure = "no surface at the center (edges ${if (a == null) "-" else "ok"}/${if (b == null) "-" else "ok"})"
            return null
        }
        val camera = frame.camera
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)

        // With the surface's direction known, intersect the box's two edge rays with the sign's
        // plane: right at any angle. Otherwise assume the sign faces the camera.
        val normal = surfaceNormal(center)
        if (normal != null) {
            val toWorld = FloatArray(16).also { inv ->
                val viewProjection = FloatArray(16)
                Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
                Matrix.invertM(inv, 0, viewProjection, 0)
            }
            val (oa, da) = ray(toWorld, -BOX)
            val (ob, db) = ray(toWorld, BOX)
            val width = spanOnPlane(oa, da, ob, db, center.hitPose.translation, normal)
            if (width != null && width in MIN_WIDTH..MAX_WIDTH) return Size(width, "plane")
        }
        // Depth along the camera's view direction, and the view's horizontal focal length from the
        // projection matrix: the box spans BOX of the view, so 2 * BOX * depth / projection[0] meters.
        val cam = camera.pose
        val hit = center.hitPose
        val forward = cam.zAxis.map { -it }
        val depth = (hit.tx() - cam.tx()) * forward[0] + (hit.ty() - cam.ty()) * forward[1] + (hit.tz() - cam.tz()) * forward[2]
        val width = 2 * BOX * depth / projection[0]
        if (width !in MIN_WIDTH..MAX_WIDTH) {
            lastFailure = "estimate out of range: %.2f m at %.2f m".format(java.util.Locale.US, width, depth)
            return null
        }
        return Size(width, "center")
    }

    /** The surface's normal at a hit, when ARCore knows it: walls, floors, depth, some feature points. */
    private fun surfaceNormal(hit: HitResult): FloatArray? = when (val t = hit.trackable) {
        is Plane, is DepthPoint -> hit.hitPose.yAxis
        is Point -> hit.hitPose.yAxis.takeIf { t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL }
        else -> null
    }

    /** The world ray through the view's middle row at normalized x [ndcX]: (origin, direction). */
    private fun ray(toWorld: FloatArray, ndcX: Float): Pair<FloatArray, FloatArray> {
        fun unproject(z: Float): FloatArray {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, toWorld, 0, floatArrayOf(ndcX, 0f, z, 1f), 0)
            return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        val near = unproject(-1f)
        val far = unproject(1f)
        return near to floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
    }

    /** Why the last [measure] returned null, for the debug log. */
    var lastFailure: String = ""
        private set

    private fun dist(p: Pose, q: Pose) = hypot(hypot(p.tx() - q.tx(), p.ty() - q.ty()), p.tz() - q.tz())

    /** Returns null when no camera image is ready yet; try again on the next frame. */
    fun capture(frame: Frame, viewWidth: Int, viewHeight: Int): Bitmap? {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return null
        }
        image.use {
            val side = viewWidth * BOX
            val left = (viewWidth - side) / 2
            val top = (viewHeight - side) / 2
            // The box's top-left, top-right and bottom-left corners, in camera image pixels. The
            // camera image is usually rotated against the screen; sampling along these edges
            // gives an upright photo whatever the rotation.
            val corners = FloatArray(6)
            frame.transformCoordinates2d(
                Coordinates2d.VIEW, floatArrayOf(left, top, left + side, top, left, top + side),
                Coordinates2d.IMAGE_PIXELS, corners,
            )
            val ox = corners[0]
            val oy = corners[1]
            val ux = corners[2] - ox
            val uy = corners[3] - oy
            val vx = corners[4] - ox
            val vy = corners[5] - oy
            val scale = minOf(1f, MAX_SIDE / max(hypot(ux, uy), hypot(vx, vy)))
            val w = (hypot(ux, uy) * scale).roundToInt().coerceAtLeast(1)
            val h = (hypot(vx, vy) * scale).roundToInt().coerceAtLeast(1)

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uvRow = uPlane.rowStride
            val uvPix = uPlane.pixelStride
            val maxX = image.width - 1
            val maxY = image.height - 1

            val pixels = IntArray(w * h)
            for (j in 0 until h) {
                val fy = (j + 0.5f) / h
                for (i in 0 until w) {
                    val fx = (i + 0.5f) / w
                    val ix = (ox + ux * fx + vx * fy).toInt().coerceIn(0, maxX)
                    val iy = (oy + uy * fx + vy * fy).toInt().coerceIn(0, maxY)
                    val lum = yBuf.get(iy * yRow + ix * yPix).toInt() and 0xff
                    val uvIndex = (iy / 2) * uvRow + (ix / 2) * uvPix
                    val cb = (uBuf.get(uvIndex).toInt() and 0xff) - 128
                    val cr = (vBuf.get(uvIndex).toInt() and 0xff) - 128
                    val r = (lum + 1.402f * cr).toInt().coerceIn(0, 255)
                    val g = (lum - 0.344f * cb - 0.714f * cr).toInt().coerceIn(0, 255)
                    val b = (lum + 1.772f * cb).toInt().coerceIn(0, 255)
                    pixels[j * w + i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
