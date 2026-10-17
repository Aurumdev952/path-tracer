package rw.aurum.pathtracer

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Draws the guidance for a follower in the stretch frame: only the next few meters of the route
 * as a ribbon of arrows flowing forward, a big arrow on the floor at the next turn, and a marker
 * where the stretch ends (destination or elevator).
 */
class GuideRenderer {
    private var ribbonProgram = 0
    private var arrowProgram = 0
    private var markerProgram = 0
    private var ribbon: FloatBuffer = allocate(1024)
    private val quad: FloatBuffer = allocate(4 * FLOATS_PER_VERTEX)
    private val mvp = FloatArray(16)
    private val viewModel = FloatArray(16)

    fun create() {
        val vertex = """
            uniform mat4 u_Mvp;
            attribute vec3 a_Pos;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); v_Uv = a_Uv; }
            """
        ribbonProgram = linkProgram(
            vertex,
            """
            precision mediump float;
            uniform float u_Time;
            uniform float u_From;
            uniform float u_To;
            varying vec2 v_Uv; // x: meters along the route, y: -1..1 across it
            void main() {
                float across = abs(v_Uv.y);
                float edge = 1.0 - smoothstep(0.7, 1.0, across);
                // Chevrons pointing and flowing forward.
                float c = fract((v_Uv.x + across * 0.32) * 1.6 - u_Time * 1.4);
                float chevron = smoothstep(0.0, 0.07, c) * (1.0 - smoothstep(0.24, 0.36, c));
                float fade = smoothstep(u_From, u_From + 0.8, v_Uv.x) * (1.0 - smoothstep(u_To - 3.0, u_To, v_Uv.x));
                float rim = smoothstep(0.75, 0.9, across) * (1.0 - smoothstep(0.92, 1.0, across));
                float alpha = fade * (edge * (0.18 + 0.8 * chevron) + 0.5 * rim);
                vec3 color = mix(vec3(0.18, 0.90, 0.78), vec3(1.0), chevron * 0.35);
                gl_FragColor = vec4(color * alpha, alpha);
            }
            """,
        )
        arrowProgram = linkProgram(
            vertex,
            """
            precision mediump float;
            uniform float u_Time;
            uniform float u_Alpha;
            varying vec2 v_Uv; // -1..1; +y points the way to go
            void main() {
                vec2 p = v_Uv;
                p.y -= 0.12 * sin(u_Time * 4.0);
                float shaft = step(abs(p.x), 0.2) * step(-0.85, p.y) * step(p.y, 0.15);
                float head = step(0.05, p.y) * step(p.y, 0.95) * step(abs(p.x), (0.95 - p.y) * 0.85);
                float shape = max(shaft, head);
                float glow = exp(-dot(v_Uv, v_Uv) * 2.0) * 0.25;
                float alpha = clamp(shape * 0.95 + glow, 0.0, 1.0) * u_Alpha;
                gl_FragColor = vec4(mix(vec3(0.18, 0.90, 0.78), vec3(1.0), shape * 0.6) * alpha, alpha);
            }
            """,
        )
        markerProgram = linkProgram(
            vertex,
            """
            precision mediump float;
            uniform float u_Time;
            uniform vec3 u_Color;
            varying vec2 v_Uv;
            void main() {
                float r = length(v_Uv);
                float centre = 1.0 - smoothstep(0.16, 0.2, r);
                float ring = fract(u_Time * 0.7);
                float pulse = exp(-pow((r - ring) * 12.0, 2.0)) * (1.0 - ring);
                float outline = exp(-pow((r - 0.85) * 25.0, 2.0)) * 0.7;
                float alpha = clamp(centre + pulse + outline, 0.0, 1.0) * (1.0 - smoothstep(0.95, 1.0, r));
                gl_FragColor = vec4(u_Color * alpha, alpha);
            }
            """,
        )
    }

    enum class Marker(val r: Float, val g: Float, val b: Float) {
        Destination(1.0f, 0.78f, 0.25f),
        Elevator(0.70f, 0.45f, 1.0f),
    }

    /**
     * [slice] is the stretch of route to draw with each point's distance along it; it fades out
     * toward [fadeTo]. [turn] is the next turn in view, [marker] the end of the stretch if in view.
     */
    fun draw(
        slice: List<Pair<TrailPoint, Float>>, fadeTo: Float, turn: Maneuver.Turn?, marker: Pair<TrailPoint, Marker>?,
        model: FloatArray, view: FloatArray, projection: FloatArray, timeSec: Float,
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        if (slice.size >= 2) {
            buildRibbon(slice)
            GLES20.glUseProgram(ribbonProgram)
            uniform(ribbonProgram, "u_From") { GLES20.glUniform1f(it, slice.first().second) }
            uniform(ribbonProgram, "u_To") { GLES20.glUniform1f(it, fadeTo) }
            drawStrip(ribbonProgram, ribbon, slice.size * 2, timeSec)
        }
        if (marker != null) {
            val (p, kind) = marker
            fillQuad(p, 0f, -1f, MARKER_SIZE, 0.015f)
            GLES20.glUseProgram(markerProgram)
            uniform(markerProgram, "u_Color") { GLES20.glUniform3f(it, kind.r, kind.g, kind.b) }
            drawStrip(markerProgram, quad, 4, timeSec)
        }
        if (turn != null) {
            fillQuad(turn.at, turn.dirX, turn.dirZ, ARROW_SIZE, 0.03f)
            GLES20.glUseProgram(arrowProgram)
            uniform(arrowProgram, "u_Alpha") { GLES20.glUniform1f(it, 1f) }
            drawStrip(arrowProgram, quad, 4, timeSec)
        }

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private inline fun uniform(program: Int, name: String, set: (Int) -> Unit) {
        GLES20.glGetUniformLocation(program, name).takeIf { it >= 0 }?.let(set)
    }

    private fun drawStrip(program: Int, data: FloatBuffer, vertexCount: Int, time: Float) {
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_Mvp"), 1, false, mvp, 0)
        uniform(program, "u_Time") { GLES20.glUniform1f(it, time) }
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

    /** A floor square centered on [c], its +v side facing direction ([fx], [fz]). */
    private fun fillQuad(c: TrailPoint, fx: Float, fz: Float, half: Float, lift: Float) {
        val len = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-5f)
        val ux = fx / len * half
        val uz = fz / len * half
        // Right of the forward direction, seen from above.
        val rx = -uz
        val rz = ux
        val y = c.y + lift
        quad.clear()
        quad.put(c.x - rx - ux).put(y).put(c.z - rz - uz).put(-1f).put(-1f)
        quad.put(c.x + rx - ux).put(y).put(c.z + rz - uz).put(1f).put(-1f)
        quad.put(c.x - rx + ux).put(y).put(c.z - rz + uz).put(-1f).put(1f)
        quad.put(c.x + rx + ux).put(y).put(c.z + rz + uz).put(1f).put(1f)
    }

    private fun buildRibbon(path: List<Pair<TrailPoint, Float>>) {
        val floats = path.size * 2 * FLOATS_PER_VERTEX
        if (ribbon.capacity() < floats) ribbon = allocate(floats * 2)
        ribbon.clear()
        for (i in path.indices) {
            val prev = path[maxOf(i - 1, 0)].first
            val next = path[minOf(i + 1, path.lastIndex)].first
            var tx = next.x - prev.x
            var tz = next.z - prev.z
            val len = sqrt(tx * tx + tz * tz).coerceAtLeast(1e-5f)
            tx /= len; tz /= len
            val nx = -tz * HALF_WIDTH
            val nz = tx * HALF_WIDTH
            val (p, s) = path[i]
            ribbon.put(p.x + nx).put(p.y + 0.02f).put(p.z + nz).put(s).put(-1f)
            ribbon.put(p.x - nx).put(p.y + 0.02f).put(p.z - nz).put(s).put(1f)
        }
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 5
        const val STRIDE = FLOATS_PER_VERTEX * 4
        const val HALF_WIDTH = 0.28f
        const val ARROW_SIZE = 0.55f
        const val MARKER_SIZE = 0.6f

        fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
