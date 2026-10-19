package rw.aurum.pathtracer

import android.graphics.Bitmap
import android.media.Image
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

    /**
     * The real width the framing box covers, how it was found ("edges", "plane" or "center"), and,
     * when the sign's surface is known, a square of that width lying on it (corners top-left,
     * top-right, bottom-right, bottom-left).
     */
    class Size(val width: Float, val how: String, val square: FloatArray? = null)

    /** A sign photo and the real width it shows. [view] is "straight" when the angle was undone. */
    class Shot(val bitmap: Bitmap, val width: Float, val view: String)

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
            if (dist(c.hitPose, mid) <= across * 0.15f && width in MIN_WIDTH..MAX_WIDTH) {
                val normal = surfaceNormal(c)
                return Size(width, "edges", normal?.let { square(frame, mid.translation, it, width) })
            }
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
            if (width != null && width in MIN_WIDTH..MAX_WIDTH) {
                return Size(width, "plane", square(frame, center.hitPose.translation, normal, width))
            }
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

    private fun square(frame: Frame, center: FloatArray, normal: FloatArray, width: Float): FloatArray? {
        val camera = frame.camera
        return squareOnPlane(center, normal, width, floatArrayOf(0f, 1f, 0f), camera.displayOrientedPose.yAxis, camera.pose.translation)
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

    private operator fun FloatArray.component6() = this[5]

    private fun dist(p: Pose, q: Pose) = hypot(hypot(p.tx() - q.tx(), p.ty() - q.ty()), p.tz() - q.tz())

    /**
     * The framing box in the camera image: its top-left corner and the vectors along its top and
     * left edges, in image pixels. The camera image is usually rotated against the screen;
     * sampling along these edges gives an upright picture whatever the rotation.
     */
    private fun boxInImage(frame: Frame, viewWidth: Int, viewHeight: Int): FloatArray {
        val side = viewWidth * BOX
        val left = (viewWidth - side) / 2
        val top = (viewHeight - side) / 2
        val corners = FloatArray(6)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW, floatArrayOf(left, top, left + side, top, left, top + side),
            Coordinates2d.IMAGE_PIXELS, corners,
        )
        return floatArrayOf(corners[0], corners[1], corners[2] - corners[0], corners[3] - corners[1], corners[4] - corners[0], corners[5] - corners[1])
    }

    /** The box in grey levels, [side] pixels square, for [SignDetail]. Null when no camera image is ready. */
    fun grayBox(frame: Frame, viewWidth: Int, viewHeight: Int, side: Int): ByteArray? {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return null
        }
        image.use {
            val (ox, oy, ux, uy, vx, vy) = boxInImage(frame, viewWidth, viewHeight)
            val plane = image.planes[0]
            val buf = plane.buffer
            val row = plane.rowStride
            val pix = plane.pixelStride
            val maxX = image.width - 2
            val maxY = image.height - 2
            val out = ByteArray(side * side)
            for (j in 0 until side) {
                val fy = (j + 0.5f) / side
                for (i in 0 until side) {
                    val fx = (i + 0.5f) / side
                    val ix = (ox + ux * fx + vx * fy).toInt().coerceIn(0, maxX)
                    val iy = (oy + uy * fx + vy * fy).toInt().coerceIn(0, maxY)
                    // Average a 2x2 patch: the box is shrunk several times, and single pixels alias.
                    val at = iy * row + ix * pix
                    val sum = (buf.get(at).toInt() and 0xff) + (buf.get(at + pix).toInt() and 0xff) +
                        (buf.get(at + row).toInt() and 0xff) + (buf.get(at + row + pix).toInt() and 0xff)
                    out[j * side + i] = (sum / 4).toByte()
                }
            }
            return out
        }
    }

    /**
     * Takes the photo for [size]. When the sign's surface is known the photo is taken straight on,
     * as if standing right in front of the sign, so ARCore matches it from any side. Returns null
     * when no camera image is ready yet; try again on the next frame.
     */
    fun capture(frame: Frame, viewWidth: Int, viewHeight: Int, size: Size): Shot? {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return null
        }
        image.use {
            size.square?.let { square -> straightOn(frame, image, size, square)?.let { return it } }
            val (ox, oy, ux, uy, vx, vy) = boxInImage(frame, viewWidth, viewHeight)
            val scale = minOf(1f, MAX_SIDE / max(hypot(ux, uy), hypot(vx, vy)))
            val w = (hypot(ux, uy) * scale).roundToInt().coerceAtLeast(1)
            val h = (hypot(vx, vy) * scale).roundToInt().coerceAtLeast(1)
            val bitmap = sample(image, w, h) { fx, fy, out ->
                out[0] = ox + ux * fx + vx * fy
                out[1] = oy + uy * fx + vy * fy
            }
            return Shot(bitmap, size.width, "box")
        }
    }

    /** Why the last photo couldn't be taken straight on, for the debug log. */
    var lastStraightFailure: String = ""
        private set

    private fun straightOn(frame: Frame, image: Image, size: Size, square: FloatArray): Shot? {
        val camera = frame.camera
        val intrinsics = camera.imageIntrinsics
        val (fx, fy) = intrinsics.focalLength
        val (cx, cy) = intrinsics.principalPoint
        val dims = intrinsics.imageDimensions
        // Intrinsics are for the camera config's image size; scale them if the image differs.
        val sx = image.width.toFloat() / dims[0]
        val sy = image.height.toFloat() / dims[1]
        val toCamera = FloatArray(16)
        camera.pose.inverse().toMatrix(toCamera, 0)
        fun project(p: FloatArray) = projectToImage(toCamera, fx * sx, fy * sy, cx * sx, cy * sy, p)

        // The same points through the screen's view and projection and ARCore's own screen-to-image
        // mapping. The two ways must agree, or the photo could come out turned or mirrored.
        val viewProjection = FloatArray(16).also { vp ->
            val view = FloatArray(16)
            val projection = FloatArray(16)
            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        }
        fun viaScreen(p: FloatArray): FloatArray {
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
            val normalized = floatArrayOf((clip[0] / clip[3] + 1) / 2, (1 - clip[1] / clip[3]) / 2)
            val px = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, normalized, Coordinates2d.IMAGE_PIXELS, px)
            return px
        }
        val worst = (0 until 4).maxOf { i ->
            val corner = FloatArray(3) { k -> square[3 * i + k] }
            val a = project(corner) ?: return null.also { lastStraightFailure = "sign behind the camera" }
            val b = viaScreen(corner)
            hypot(a[0] - b[0], a[1] - b[1])
        }
        if (worst > image.width * PROJECTION_TOLERANCE) {
            lastStraightFailure = "projections disagree by ${worst.roundToInt()} px"
            return null
        }

        // Shrink the square about its middle until all of it is in the camera image.
        val middle = FloatArray(3) { k -> (square[k] + square[3 + k] + square[6 + k] + square[9 + k]) / 4 }
        var shrink = 1f
        repeat(SHRINK_STEPS) {
            val quad = FloatArray(8)
            val inside = (0 until 4).all { i ->
                val corner = FloatArray(3) { k -> middle[k] + (square[3 * i + k] - middle[k]) * shrink }
                val px = project(corner) ?: return@all false
                px.copyInto(quad, 2 * i)
                px[0] in 0f..(image.width - 1f) && px[1] in 0f..(image.height - 1f)
            }
            if (inside) {
                val homography = Homography.squareTo(quad) ?: return null
                val edge = (0 until 4).maxOf { i -> hypot(quad[(2 * i + 2) % 8] - quad[2 * i], quad[(2 * i + 3) % 8] - quad[2 * i + 1]) }
                val n = edge.roundToInt().coerceIn(MIN_STRAIGHT_SIDE, MAX_SIDE)
                return Shot(sample(image, n, n, homography::map), size.width * shrink, "straight")
            }
            shrink *= 0.9f
        }
        lastStraightFailure = "sign doesn't fit in the camera image"
        return null
    }

    /** Builds a [w] x [h] picture; [at] gives the camera image pixel for each point across it (0 to 1). */
    private inline fun sample(image: Image, w: Int, h: Int, at: (Float, Float, FloatArray) -> Unit): Bitmap {
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
        val point = FloatArray(2)

        val pixels = IntArray(w * h)
        for (j in 0 until h) {
            val fy = (j + 0.5f) / h
            for (i in 0 until w) {
                at((i + 0.5f) / w, fy, point)
                val ix = point[0].toInt().coerceIn(0, maxX)
                val iy = point[1].toInt().coerceIn(0, maxY)
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

    private const val PROJECTION_TOLERANCE = 0.02f
    private const val SHRINK_STEPS = 6
    private const val MIN_STRAIGHT_SIDE = 300
}
