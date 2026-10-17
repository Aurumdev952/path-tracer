package rw.aurum.pathtracer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Routes on the phone: one folder per route holding `route.json` and the leg photos. A shared
 * route is the same folder zipped into a `.pathroute` file.
 */
class RouteStore(private val root: File) {

    /** Where a route being recorded keeps its photos until it is saved. */
    val draft: File get() = File(root, DRAFT).apply { mkdirs() }

    fun list(): List<Route> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name != DRAFT }
        .mapNotNull { load(it.name) }
        .sortedByDescending { it.createdAt }

    fun load(id: String): Route? = runCatching {
        Route.fromJson(JSONObject(File(dir(id), ROUTE_FILE).readText()))
    }.getOrNull()

    fun photo(route: Route, leg: Leg): File = File(dir(route.id), leg.photo)

    fun loadPhoto(route: Route, leg: Leg): Bitmap? = BitmapFactory.decodeFile(photo(route, leg).path)

    fun clearDraft() {
        File(root, DRAFT).deleteRecursively()
    }

    /** Saves a recorded route, moving its photos out of [draft]. */
    fun saveDraft(route: Route) {
        val target = dir(route.id)
        target.deleteRecursively()
        draft.renameTo(target)
        writeJson(target, route)
    }

    fun rename(route: Route, name: String) = writeJson(dir(route.id), route.copy(name = name))

    fun delete(route: Route) {
        dir(route.id).deleteRecursively()
    }

    /** Zips [route] into [out] for sharing. */
    fun export(route: Route, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ROUTE_FILE))
            zip.write(route.toJson().toString().toByteArray())
            zip.closeEntry()
            route.legs.map { it.photo }.distinct().forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                File(dir(route.id), name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a shared `.pathroute` file. A route that is already on the phone is replaced, so
     * receiving an updated copy just works. Throws if the file isn't a valid route.
     */
    fun import(input: InputStream): Route {
        val incoming = File(root, ".incoming").apply { deleteRecursively(); mkdirs() }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    // Only flat, known file names: nothing can be written outside the folder.
                    if (entry.isDirectory || !SAFE_NAME.matches(name)) continue
                    File(incoming, name).outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total < MAX_IMPORT_BYTES) { "File is too big to be a route" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val json = File(incoming, ROUTE_FILE)
            check(json.exists()) { "This file isn't a Path Tracer route" }
            val route = Route.fromJson(JSONObject(json.readText()))
            check(SAFE_ID.matches(route.id)) { "This route file is damaged" }
            check(route.legs.isNotEmpty()) { "This route is empty" }
            route.legs.forEach {
                check(SAFE_NAME.matches(it.photo) && File(incoming, it.photo).exists()) { "This route is missing a photo" }
            }
            val target = dir(route.id)
            target.deleteRecursively()
            check(incoming.renameTo(target)) { "Couldn't save the route" }
            return route
        } finally {
            incoming.deleteRecursively()
        }
    }

    private fun dir(id: String) = File(root, id)

    private fun writeJson(dir: File, route: Route) {
        dir.mkdirs()
        val tmp = File(dir, "$ROUTE_FILE.tmp")
        tmp.writeText(route.toJson().toString())
        tmp.renameTo(File(dir, ROUTE_FILE))
    }

    companion object {
        const val EXTENSION = "pathroute"
        private const val ROUTE_FILE = "route.json"
        private const val DRAFT = ".draft"
        private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]+\\.(json|jpg)")
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
