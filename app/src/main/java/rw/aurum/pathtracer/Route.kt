package rw.aurum.pathtracer

import org.json.JSONArray
import org.json.JSONObject

/** How a follower gets from one floor to another. */
enum class FloorChange(val label: String) {
    Elevator("elevator"),
    Stairs("stairs"),
    Escalator("escalator"),
}

/** How a leg begins. */
sealed interface LegEntry {
    /** The first leg, at the entrance. */
    data object Start : LegEntry

    /**
     * After an elevator ride. Tracking breaks in a moving lift, so the leg starts from a new photo.
     * [rise] is how far the ride went in meters (negative going down), if the barometer measured it.
     */
    data class Elevator(val floor: String, val rise: Float? = null) : LegEntry

    /**
     * A photo taken mid-walk to correct drift. [fromPrevious] is this leg's frame inside the
     * previous leg's frame, so a follower can carry on even if they never see the photo.
     */
    data class Checkpoint(val fromPrevious: YawPose) : LegEntry
}

/** Stairs or an escalator, starting at point [at] of the leg. Tracking keeps going through it. */
data class Step(val at: Int, val change: FloorChange, val floor: String)

/**
 * One stretch of walking measured from a single photo. [points] are on the floor, in the frame of
 * the photographed image (levelled, so y is up).
 */
data class Leg(
    val photo: String,
    /** Physical width of the photographed image in meters, or 0 if unknown. */
    val photoWidth: Float,
    val entry: LegEntry,
    val points: List<TrailPoint>,
    val steps: List<Step> = emptyList(),
) {
    val lengthMeters: Float get() = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
}

data class Route(
    val id: String,
    val name: String,
    val createdAt: Long,
    val legs: List<Leg>,
) {
    val lengthMeters: Float get() = legs.sumOf { it.lengthMeters.toDouble() }.toFloat()

    /** Floor labels in the order they are reached. */
    val floors: List<String>
        get() = legs.flatMap { leg ->
            listOfNotNull((leg.entry as? LegEntry.Elevator)?.floor) + leg.steps.map { it.floor }
        }.filter { it.isNotBlank() }

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })

    companion object {
        const val FORMAT = 1

        fun fromJson(json: JSONObject): Route {
            require(json.optInt("format", FORMAT) <= FORMAT) { "This route was made by a newer version of the app" }
            val legs = json.getJSONArray("legs")
            return Route(
                id = json.getString("id"),
                name = json.getString("name"),
                createdAt = json.optLong("createdAt"),
                legs = (0 until legs.length()).map { legFromJson(legs.getJSONObject(it)) },
            )
        }
    }
}

private fun Leg.toJson(): JSONObject {
    val flat = JSONArray()
    points.forEach { flat.put(it.x.toDouble()).put(it.y.toDouble()).put(it.z.toDouble()) }
    val entry = when (val e = entry) {
        LegEntry.Start -> JSONObject().put("type", "start")
        is LegEntry.Elevator -> JSONObject().put("type", "elevator").put("floor", e.floor)
            .apply { e.rise?.let { put("rise", it.toDouble()) } }
        is LegEntry.Checkpoint -> JSONObject().put("type", "checkpoint").put(
            "fromPrevious",
            JSONArray().put(e.fromPrevious.x.toDouble()).put(e.fromPrevious.y.toDouble())
                .put(e.fromPrevious.z.toDouble()).put(e.fromPrevious.yaw.toDouble()),
        )
    }
    val steps = JSONArray()
    this.steps.forEach { steps.put(JSONObject().put("at", it.at).put("change", it.change.name).put("floor", it.floor)) }
    return JSONObject()
        .put("photo", photo)
        .put("photoWidth", photoWidth.toDouble())
        .put("entry", entry)
        .put("points", flat)
        .put("steps", steps)
}

private fun legFromJson(json: JSONObject): Leg {
    val flat = json.getJSONArray("points")
    val entry = json.getJSONObject("entry")
    val steps = json.optJSONArray("steps") ?: JSONArray()
    return Leg(
        photo = json.getString("photo"),
        photoWidth = json.optDouble("photoWidth", 0.0).toFloat(),
        entry = when (entry.getString("type")) {
            "elevator" -> LegEntry.Elevator(
                entry.optString("floor"),
                entry.optDouble("rise").takeIf { !it.isNaN() }?.toFloat(),
            )
            "checkpoint" -> entry.getJSONArray("fromPrevious").let {
                LegEntry.Checkpoint(
                    YawPose(
                        it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                        it.getDouble(2).toFloat(), it.getDouble(3).toFloat(),
                    )
                )
            }
            else -> LegEntry.Start
        },
        points = (0 until flat.length() / 3).map {
            TrailPoint(
                flat.getDouble(it * 3).toFloat(),
                flat.getDouble(it * 3 + 1).toFloat(),
                flat.getDouble(it * 3 + 2).toFloat(),
            )
        },
        steps = (0 until steps.length()).map {
            val step = steps.getJSONObject(it)
            Step(step.getInt("at"), FloorChange.valueOf(step.getString("change")), step.optString("floor"))
        },
    )
}
