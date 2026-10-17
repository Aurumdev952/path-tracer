package rw.aurum.pathtracer

import android.graphics.Bitmap
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Takes a photo of whatever is inside the on-screen framing box, straight from ARCore's camera image. */
object SignPhoto {
    /** The framing box is a square this fraction of the screen width, centered on the screen. */
    const val BOX = 0.72f
    private const val MAX_SIDE = 1024

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
