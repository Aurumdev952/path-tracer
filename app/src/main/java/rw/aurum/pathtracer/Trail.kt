package rw.aurum.pathtracer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/** A point on the floor, in the start anchor's frame. y is the floor height there. */
data class TrailPoint(val x: Float, val y: Float, val z: Float) {
    /** Distance walked across the floor, ignoring height. */
    fun distanceTo(o: TrailPoint) = hypot(x - o.x, z - o.z)
}

/** How the start point is found again on a later visit. */
enum class Memory {
    /** A printed START HERE image. Works offline with no account. */
    Marker,
    /** An ARCore Cloud Anchor. Needs internet and a Google Cloud project. */
    Cloud,
}

data class Trail(val points: List<TrailPoint>, val memory: Memory, val cloudAnchorId: String? = null) {
    val lengthMeters: Float get() = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
}

class TrailStore(private val file: File) {

    fun load(): Trail? = runCatching {
        val json = JSONObject(file.readText())
        val flat = json.getJSONArray("points")
        Trail(
            points = (0 until flat.length() / 3).map {
                TrailPoint(
                    flat.getDouble(it * 3).toFloat(),
                    flat.getDouble(it * 3 + 1).toFloat(),
                    flat.getDouble(it * 3 + 2).toFloat(),
                )
            },
            memory = Memory.valueOf(json.getString("memory")),
            cloudAnchorId = json.optString("cloudAnchorId").ifEmpty { null },
        )
    }.getOrNull()

    fun save(trail: Trail) {
        val flat = JSONArray()
        trail.points.forEach { flat.put(it.x.toDouble()).put(it.y.toDouble()).put(it.z.toDouble()) }
        val json = JSONObject()
            .put("points", flat)
            .put("memory", trail.memory.name)
            .put("cloudAnchorId", trail.cloudAnchorId ?: "")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}
