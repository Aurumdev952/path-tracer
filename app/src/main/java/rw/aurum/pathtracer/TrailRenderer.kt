package rw.aurum.pathtracer

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Draws the walked path as a glowing floor ribbon with chevrons flowing from the start toward
 * the newest point, plus a pulsing beacon on the first point. Geometry is in anchor space.
 */
class TrailRenderer {
    private var ribbonProgram = 0
    private var beaconProgram = 0
    private var ribbon: FloatBuffer = allocate(1024)
    private val beacon = floatBuffer(
        floatArrayOf(
            -BEACON, 0f, -BEACON, -1f, -1f,
            BEACON, 0f, -BEACON, 1f, -1f,
            -BEACON, 0f, BEACON, -1f, 1f,
            BEACON, 0f, BEACON, 1f, 1f,
        )
    )
    private val mvp = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val beaconModel = FloatArray(16)

    fun create() {
        ribbonProgram = linkProgram(
            """
            uniform mat4 u_Mvp;
            attribute vec3 a_Pos;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); v_Uv = a_Uv; }
            """,
            """
            precision mediump float;
            uniform float u_Time;
            uniform float u_Length;
            varying vec2 v_Uv; // x: meters along the path, y: -1..1 across it
            void main() {
                float across = abs(v_Uv.y);
                float edge = 1.0 - smoothstep(0.55, 1.0, across);
                float core = exp(-across * 4.0);
                float c = fract(v_Uv.x * 2.2 - across * 0.45 - u_Time * 0.9);
                float chevron = smoothstep(0.0, 0.06, c) * (1.0 - smoothstep(0.16, 0.28, c));
                float t = clamp(v_Uv.x / max(u_Length, 0.01), 0.0, 1.0);
                vec3 color = mix(vec3(0.10, 0.85, 1.0), vec3(1.0, 0.30, 0.80), t);
                float sweep = mod(u_Time * 1.6, u_Length + 3.0);
                float wave = exp(-(v_Uv.x - sweep) * (v_Uv.x - sweep) * 3.0);
                float alpha = edge * (0.28 + 0.5 * core + 0.55 * chevron + 0.45 * wave);
                gl_FragColor = vec4(color * (0.8 + 0.6 * chevron + 0.8 * wave) * alpha, alpha);
            }
            """,
        )
        beaconProgram = linkProgram(
            """
            uniform mat4 u_Mvp;
            attribute vec3 a_Pos;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); v_Uv = a_Uv; }
            """,
            """
            precision mediump float;
            uniform float u_Time;
            varying vec2 v_Uv;
            void main() {
                float r = length(v_Uv);
                float centre = 1.0 - smoothstep(0.10, 0.14, r);
                float ring = fract(u_Time * 0.6);
                float pulse = exp(-pow((r - ring) * 14.0, 2.0)) * (1.0 - ring);
                float halo = (1.0 - smoothstep(0.0, 1.0, r)) * 0.18;
                float alpha = clamp(centre + pulse + halo, 0.0, 1.0) * (1.0 - smoothstep(0.92, 1.0, r));
                gl_FragColor = vec4(vec3(0.20, 1.0, 0.65) * alpha, alpha);
            }
            """,
        )
    }

    /**
     * [head] is the live floor position under the camera; it extends the ribbon smoothly between
     * recorded points.
     */
    fun draw(
        points: List<TrailPoint>, head: TrailPoint?, model: FloatArray,
        view: FloatArray, projection: FloatArray, timeSec: Float,
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val start = points.firstOrNull() ?: head
        if (start != null) {
            Matrix.translateM(beaconModel, 0, model, 0, start.x, start.y + 0.004f, start.z)
            setMvp(beaconModel, view, projection)
            drawQuads(beaconProgram, beacon, 4, timeSec, 0f)
        }

        setMvp(model, view, projection)

        val path = if (head != null && points.isNotEmpty() && head.distanceTo(points.last()) > 0.02f) points + head else points
        if (path.size >= 2) {
            val length = buildRibbon(path)
            drawQuads(ribbonProgram, ribbon, path.size * 2, timeSec, length)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setMvp(model: FloatArray, view: FloatArray, projection: FloatArray) {
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
    }

    private fun drawQuads(program: Int, data: FloatBuffer, vertexCount: Int, time: Float, length: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_Mvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_Time"), time)
        GLES20.glGetUniformLocation(program, "u_Length").takeIf { it >= 0 }?.let { GLES20.glUniform1f(it, length) }
        val aPos = GLES20.glGetAttribLocation(program, "a_Pos")
        val aUv = GLES20.glGetAttribLocation(program, "a_Uv")
        data.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, data)
        data.position(3)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE, data)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    /** Fills [ribbon] with a triangle strip two vertices wide and returns the path length. */
    private fun buildRibbon(path: List<TrailPoint>): Float {
        val floats = path.size * 2 * FLOATS_PER_VERTEX
        if (ribbon.capacity() < floats) ribbon = allocate(floats * 2)
        ribbon.clear()
        var dist = 0f
        for (i in path.indices) {
            if (i > 0) dist += path[i].distanceTo(path[i - 1])
            val prev = path[maxOf(i - 1, 0)]
            val next = path[minOf(i + 1, path.lastIndex)]
            var tx = next.x - prev.x
            var tz = next.z - prev.z
            val len = sqrt(tx * tx + tz * tz).coerceAtLeast(1e-5f)
            tx /= len; tz /= len
            val nx = -tz * HALF_WIDTH
            val nz = tx * HALF_WIDTH
            val p = path[i]
            ribbon.put(p.x + nx).put(p.y + 0.01f).put(p.z + nz).put(dist).put(-1f)
            ribbon.put(p.x - nx).put(p.y + 0.01f).put(p.z - nz).put(dist).put(1f)
        }
        return dist
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 5
        const val STRIDE = FLOATS_PER_VERTEX * 4
        const val HALF_WIDTH = 0.11f
        const val BEACON = 0.45f

        fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
