package rw.aurum.pathtracer

import android.content.Context
import com.google.ar.core.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Which self-hosted localization server(s) to ask. Experimental; set from debug mode. */
enum class VpsMethod(val label: String) {
    Off("Off"),
    LightGlue("LightGlue"),
    Mast3r("MASt3R"),
    Both("Both"),
}

data class VpsSettings(
    val method: VpsMethod = VpsMethod.Off,
    val lightGlueUrl: String = DEFAULT_LIGHTGLUE,
    val mast3rUrl: String = DEFAULT_MAST3R,
) {
    val enabled: Boolean get() = method != VpsMethod.Off

    /** The servers to ask, by name. */
    val servers: List<Pair<String, String>>
        get() = when (method) {
            VpsMethod.Off -> emptyList()
            VpsMethod.LightGlue -> listOf("lightglue" to lightGlueUrl)
            VpsMethod.Mast3r -> listOf("mast3r" to mast3rUrl)
            VpsMethod.Both -> listOf("lightglue" to lightGlueUrl, "mast3r" to mast3rUrl)
        }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("vps_method", method.name)
            .putString("vps_lightglue", lightGlueUrl)
            .putString("vps_mast3r", mast3rUrl)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"
        // With `adb reverse tcp:8001 tcp:8001` (and 8002) these reach the laptop over USB.
        const val DEFAULT_LIGHTGLUE = "http://127.0.0.1:8001"
        const val DEFAULT_MAST3R = "http://127.0.0.1:8002"

        fun load(context: Context): VpsSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VpsSettings(
                method = runCatching { VpsMethod.valueOf(p.getString("vps_method", null)!!) }.getOrDefault(VpsMethod.Off),
                lightGlueUrl = p.getString("vps_lightglue", null) ?: DEFAULT_LIGHTGLUE,
                mast3rUrl = p.getString("vps_mast3r", null) ?: DEFAULT_MAST3R,
            )
        }
    }
}

/** A reference view as sent to the server. */
class RefView(val jpeg: ByteArray, val depth: ByteArray?, val view: CameraView)

/** The server's answer: where the query camera was inside reference [ref]'s camera frame. */
data class VpsFix(
    val server: String,
    val ref: Int,
    val pose: Pose,
    val inliers: Int,
    val reprojectionPx: Float,
    val depth: String,
    val serverMs: Float,
)

/** Talks to the localization servers in the background. */
class VpsClient {
    private val pool = Executors.newFixedThreadPool(2)

    /** Calls [done] on a background thread with a fix, or null and why not. */
    fun localize(server: String, url: String, query: CameraFrame, refs: List<RefView>, done: (VpsFix?, String?, Long) -> Unit) {
        pool.execute {
            val start = System.currentTimeMillis()
            val result = runCatching { request(server, url, query, refs) }
            val ms = System.currentTimeMillis() - start
            result.onSuccess { (fix, why) -> done(fix, why, ms) }.onFailure { done(null, it.javaClass.simpleName + ": " + it.message, ms) }
        }
    }

    fun shutdown() = pool.shutdownNow()

    private fun request(server: String, url: String, query: CameraFrame, refs: List<RefView>): Pair<VpsFix?, String?> {
        val meta = JSONObject()
            .put("query", intrinsics(query.fx, query.fy, query.cx, query.cy))
            .put(
                "refs",
                JSONArray().apply {
                    refs.forEach { r ->
                        val o = intrinsics(r.view.fx, r.view.fy, r.view.cx, r.view.cy)
                        if (r.depth != null) o.put("depth_width", r.view.depthWidth).put("depth_height", r.view.depthHeight)
                        put(o)
                    }
                },
            )
        val boundary = "pt" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            fun part(name: String, data: ByteArray, file: Boolean) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"")
                if (file) out.writeBytes("; filename=\"$name\"\r\nContent-Type: application/octet-stream")
                out.writeBytes("\r\n\r\n")
                out.write(data)
                out.writeBytes("\r\n")
            }
            part("meta", meta.toString().toByteArray(), file = false)
            part("query", query.jpeg, file = true)
            refs.forEachIndexed { i, r ->
                part("ref$i", r.jpeg, file = true)
                r.depth?.let { part("ref${i}_depth", it, file = true) }
            }
            out.writeBytes("--$boundary--\r\n")
        }
        val conn = URL(url.trimEnd('/') + "/localize").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 15000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { body.writeTo(it) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code != 200) return null to "HTTP $code ${text.take(120)}"
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) return null to "no pose"
            val best = json.getJSONObject("best")
            val pose = best.getJSONObject("pose")
            val t = pose.getJSONArray("t")
            val q = pose.getJSONArray("q")
            fun f(a: JSONArray, i: Int) = a.getDouble(i).toFloat()
            return VpsFix(
                server = server,
                ref = best.getInt("ref"),
                pose = Pose(floatArrayOf(f(t, 0), f(t, 1), f(t, 2)), floatArrayOf(f(q, 0), f(q, 1), f(q, 2), f(q, 3))),
                inliers = best.optInt("inliers"),
                reprojectionPx = best.optDouble("reprojection_px").toFloat(),
                depth = best.optString("depth"),
                serverMs = json.optDouble("ms").toFloat(),
            ) to null
        } finally {
            conn.disconnect()
        }
    }

    private fun intrinsics(fx: Float, fy: Float, cx: Float, cy: Float) =
        JSONObject().put("fx", fx.toDouble()).put("fy", fy.toDouble()).put("cx", cx.toDouble()).put("cy", cy.toDouble())

    companion object {
        /** A one-line health check for the settings screen. Blocks; call off the main thread. */
        fun health(url: String): String = runCatching {
            val conn = URL(url.trimEnd('/') + "/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            try {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                when {
                    json.optBoolean("ready") -> "${json.optString("matcher")} ready on ${json.optString("gpu").ifEmpty { json.optString("device") }}"
                    !json.isNull("error") -> "error: ${json.optString("error")}"
                    else -> "${json.optString("matcher")} is loading…"
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { "can't reach it (${it.javaClass.simpleName})" }
    }
}
