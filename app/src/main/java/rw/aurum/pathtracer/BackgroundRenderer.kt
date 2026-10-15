package rw.aurum.pathtracer

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws the ARCore camera image full screen. */
class BackgroundRenderer {
    var textureId = -1
        private set

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvs = floatBuffer(FloatArray(8))

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = linkProgram(
            """
            attribute vec4 a_Pos;
            attribute vec2 a_Tex;
            varying vec2 v_Tex;
            void main() { gl_Position = a_Pos; v_Tex = a_Tex; }
            """,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_Tex;
            uniform samplerExternalOES u_Tex;
            void main() { gl_FragColor = texture2D(u_Tex, v_Tex); }
            """,
        )
        aPos = GLES20.glGetAttribLocation(program, "a_Pos")
        aTex = GLES20.glGetAttribLocation(program, "a_Tex")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, uvs,
            )
        }
        if (frame.timestamp == 0L) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad.rewind())
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uvs.rewind())
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

fun floatBuffer(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data)
        position(0)
    }

fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
    fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src.trimIndent())
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
    GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
    GLES20.glLinkProgram(program)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
    check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
    return program
}
