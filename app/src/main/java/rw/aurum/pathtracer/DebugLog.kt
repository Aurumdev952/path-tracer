package rw.aurum.pathtracer

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug mode: one log file per AR session, with a line per event such as
 * `12.345 snap leg=2 drift=0.31`, plus live values for an on-screen panel. Logs stay on the
 * phone unless shared. Safe to call from any thread.
 */
class DebugLog(dir: File, mode: String) {
    private val start = SystemClock.elapsedRealtime()
    val file = File(dir.apply { mkdirs() }, "${STAMP.format(Date())}-$mode.log")
    private val writer = file.bufferedWriter()
    private val values = LinkedHashMap<String, String>()
    private val _live = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The latest value of everything passed to [show], in first-shown order. */
    val live: StateFlow<List<Pair<String, String>>> = _live
    private var lastPublish = 0L

    /** Seconds since the session started, for comparing events. */
    val seconds: Float get() = (SystemClock.elapsedRealtime() - start) / 1000f

    @Synchronized
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val line = buildString {
            append("%.3f ".format(Locale.US, seconds)).append(name)
            fields.forEach { (k, v) -> append(' ').append(k).append('=').append(format(v)) }
        }
        writer.write(line)
        writer.newLine()
    }

    /** Sets a live value for the panel. Updates reach the screen a few times a second. */
    @Synchronized
    fun show(key: String, value: Any?) {
        values[key] = format(value)
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublish >= PUBLISH_MS) {
            lastPublish = now
            _live.value = values.toList()
        }
    }

    @Synchronized
    fun flush() = writer.flush()

    @Synchronized
    fun close() {
        event("end")
        writer.close()
    }

    private fun format(v: Any?): String = when (v) {
        null -> "-"
        is Float -> "%.3f".format(Locale.US, v)
        is Double -> "%.3f".format(Locale.US, v)
        is String -> if (v.any { it.isWhitespace() || it == '=' }) "\"${v.replace("\"", "'")}\"" else v
        else -> v.toString()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "debug"
        private const val KEEP = 40
        private const val PUBLISH_MS = 250L
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        fun dir(context: Context) = File(context.filesDir, "logs")

        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        }

        fun logs(context: Context): List<File> =
            (dir(context).listFiles() ?: emptyArray()).filter { it.name.endsWith(".log") }.sortedByDescending { it.name }

        /** Starts a new session log, keeping only the most recent ones. */
        fun open(context: Context, mode: String): DebugLog {
            logs(context).drop(KEEP - 1).forEach { it.delete() }
            return DebugLog(dir(context), mode)
        }

        fun clear(context: Context) {
            dir(context).deleteRecursively()
        }

        /** Zips every log into [out], for sharing. */
        fun zipAll(context: Context, out: File) {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                logs(context).forEach { log ->
                    zip.putNextEntry(ZipEntry(log.name))
                    log.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
