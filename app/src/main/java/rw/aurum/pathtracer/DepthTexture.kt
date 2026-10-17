package rw.aurum.pathtracer

import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ARCore's depth image as a GL texture, so shaders can hide what is behind real walls and
 * furniture. Each texel holds a distance in millimeters, low byte in luminance, high in alpha.
 */
class DepthTexture {
    var textureId = 0
        private set
    /** True once a depth image has been uploaded. */
    var ready = false
        private set
    /** Maps a fragment's normalized device coordinates to depth texture coordinates (column-major mat3). */
    val uvTransform = FloatArray(9)

    private var tight: ByteBuffer? = null
    private val ndcBasis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    private val basisUv = FloatArray(6)

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Packed bytes must not be blended between texels.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        ready = false
    }

    fun update(frame: Frame) {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: IllegalStateException) {
            // Depth is off for this session.
            return
        }
        image.use {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val src = plane.buffer
            // GLES 2 can't skip row padding, so copy into tight rows when there is any.
            val data = if (plane.rowStride == w * 2) {
                src
            } else {
                val out = tight?.takeIf { it.capacity() >= w * h * 2 }
                    ?: ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder()).also { tight = it }
                out.clear()
                for (row in 0 until h) {
                    src.limit(row * plane.rowStride + w * 2)
                    src.position(row * plane.rowStride)
                    out.put(src)
                }
                src.clear()
                out.flip()
                out
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0,
                GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, data,
            )
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcBasis,
            Coordinates2d.TEXTURE_NORMALIZED, basisUv,
        )
        val ox = basisUv[0]
        val oy = basisUv[1]
        uvTransform[0] = basisUv[2] - ox; uvTransform[1] = basisUv[3] - oy; uvTransform[2] = 0f
        uvTransform[3] = basisUv[4] - ox; uvTransform[4] = basisUv[5] - oy; uvTransform[5] = 0f
        uvTransform[6] = ox; uvTransform[7] = oy; uvTransform[8] = 1f
        ready = true
    }

    companion object {
        /** GLSL that returns how visible the current fragment is (0 hidden, 1 shown). */
        const val GLSL = """
            uniform sampler2D u_Depth;
            uniform mat3 u_DepthUv;
            uniform float u_UseDepth;
            varying vec4 v_Clip;
            varying float v_ViewZ;
            float visibility() {
                if (u_UseDepth < 0.5) return 1.0;
                vec2 uv = (u_DepthUv * vec3(v_Clip.xy / v_Clip.w, 1.0)).xy;
                vec4 t = texture2D(u_Depth, uv);
                // Meters: (low byte + 256 * high byte) / 1000.
                float real = t.r * 0.255 + t.a * 65.28;
                if (real <= 0.0) return 1.0;
                // Hide only when something is clearly in front, so the floor itself never hides the path.
                return smoothstep(-0.45, -0.15, real - v_ViewZ);
            }
            """
    }
}
