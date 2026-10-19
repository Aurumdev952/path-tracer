package rw.aurum.pathtracer

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.AugmentedImage
import kotlin.math.max
import kotlin.math.min

/** Draws a frame around a tracked sign photo, so the user sees which sign ARCore matched. */
class SignOutlineRenderer {
    private var program = 0
    private val vertices = floatBuffer(FloatArray(4 * 6 * 3))
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    fun create() {
        program = linkProgram(
            """
            uniform mat4 u_Mvp;
            attribute vec3 a_Pos;
            void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); }
            """,
            """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = vec4(u_Color.rgb * u_Color.a, u_Color.a); }
            """,
        )
    }

    /** [rgb] from 0 to 1; [alpha] fades the frame out. */
    fun draw(image: AugmentedImage, rgb: FloatArray, alpha: Float, view: FloatArray, projection: FloatArray) {
        // The photo lies in the image's x-z plane. The frame sits just outside it, so it doesn't
        // hide the sign, and just in front of it, so the wall doesn't hide the frame.
        val hx = image.extentX / 2
        val hz = image.extentZ / 2
        val w = max(0.008f, 0.05f * min(image.extentX, image.extentZ))
        val y = 0.003f
        vertices.position(0)
        bar(-hx - w, -hz - w, hx + w, -hz, y)
        bar(-hx - w, hz, hx + w, hz + w, y)
        bar(-hx - w, -hz, -hx, hz, y)
        bar(hx, -hz, hx + w, hz, y)
        vertices.position(0)

        image.centerPose.toMatrix(model, 0)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_Mvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "u_Color"), rgb[0], rgb[1], rgb[2], alpha)
        val aPos = GLES20.glGetAttribLocation(program, "a_Pos")
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 4 * 6)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Two triangles covering x0..x1, z0..z1 at height [y]. */
    private fun bar(x0: Float, z0: Float, x1: Float, z1: Float, y: Float) {
        vertices.put(floatArrayOf(x0, y, z0, x1, y, z0, x0, y, z1, x1, y, z0, x1, y, z1, x0, y, z1))
    }

    companion object {
        val Green = floatArrayOf(0.18f, 0.9f, 0.78f)
        val Yellow = floatArrayOf(1f, 0.78f, 0.34f)
    }
}
