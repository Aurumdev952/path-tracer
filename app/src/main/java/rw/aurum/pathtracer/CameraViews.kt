package rw.aurum.pathtracer

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.io.File

/** A camera frame ready to send to the localization server, with where the camera was. */
class CameraFrame(
    val jpeg: ByteArray,
    val depth: ByteArray?,
    val depthWidth: Int,
    val depthHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    /** The camera in ARCore world space at this frame. */
    val pose: Pose,
)

/** Grabs full camera frames (in sensor orientation, like the intrinsics) for server localization. */
object CameraViews {

    /** Null when the camera image isn't ready yet; try again on the next frame. */
    fun grab(frame: Frame, camera: Camera, withDepth: Boolean, quality: Int = 85): CameraFrame? {
        val jpeg = try {
            frame.acquireCameraImage().use { toJpeg(it, quality) }
        } catch (e: NotYetAvailableException) {
            return null
        }
        var depth: ByteArray? = null
        var dw = 0
        var dh = 0
        if (withDepth) {
            try {
                frame.acquireDepthImage16Bits().use { image ->
                    dw = image.width
                    dh = image.height
                    depth = tightRows(image.planes[0], dw * 2, dh)
                }
            } catch (e: NotYetAvailableException) {
                // Saved without depth; the server can use its own estimate.
            } catch (e: IllegalStateException) {
                // Depth is off for this session.
            }
        }
        val k = camera.imageIntrinsics
        val focal = k.focalLength
        val principal = k.principalPoint
        return CameraFrame(jpeg, depth, dw, dh, focal[0], focal[1], principal[0], principal[1], camera.pose)
    }

    /** Saves [frame] next to a route being recorded, as seen from [legFrame] (the leg's anchor pose). */
    fun save(frame: CameraFrame, legFrame: Pose, dir: File, name: String): CameraView {
        File(dir, "$name.jpg").writeBytes(frame.jpeg)
        frame.depth?.let { File(dir, "$name.depth").writeBytes(it) }
        val p = legFrame.inverse().compose(frame.pose)
        return CameraView(
            image = "$name.jpg",
            depth = frame.depth?.let { "$name.depth" },
            depthWidth = frame.depthWidth,
            depthHeight = frame.depthHeight,
            fx = frame.fx, fy = frame.fy, cx = frame.cx, cy = frame.cy,
            pose = p.toCameraPose(),
        )
    }

    private fun toJpeg(image: Image, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        var o = 0
        for (row in 0 until h) {
            if (y.pixelStride == 1) {
                y.buffer.position(row * y.rowStride)
                y.buffer.get(nv21, o, w)
                o += w
            } else {
                for (col in 0 until w) nv21[o++] = y.buffer.get(row * y.rowStride + col * y.pixelStride)
            }
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                nv21[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun tightRows(plane: Image.Plane, rowBytes: Int, rows: Int): ByteArray {
        val out = ByteArray(rowBytes * rows)
        val buffer = plane.buffer
        for (r in 0 until rows) {
            buffer.position(r * plane.rowStride)
            buffer.get(out, r * rowBytes, rowBytes)
        }
        return out
    }
}

fun Pose.toCameraPose() = CameraPose(tx(), ty(), tz(), qx(), qy(), qz(), qw())

fun CameraPose.toPose() = Pose(floatArrayOf(tx, ty, tz), floatArrayOf(qx, qy, qz, qw))
