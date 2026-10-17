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

    /** After an elevator ride. Tracking breaks in a moving lift, so the leg starts from a new photo. */
    data class Elevator(val floor: String) : LegEntry

    /**
     * A photo taken mid-walk to correct drift. [fromPrevious] is this leg's frame inside the
     * previous leg's frame, so a follower can carry on even if they never see the photo.
     */
    data class Checkpoint(val fromPrevious: YawPose) : LegEntry
}

/** Stairs or an escalator, starting at point [at] of the leg. Tracking keeps going through it. */
data class Step(val at: Int, val change: FloorChange, val floor: String)

/** A camera pose: position and rotation quaternion (x, y, z, w), in ARCore camera axes. */
data class CameraPose(val tx: Float, val ty: Float, val tz: Float, val qx: Float, val qy: Float, val qz: Float, val qw: Float)

/**
 * A full camera frame saved for server localization: a friend's camera image is matched against it.
 * [image] and [depth] are files next to route.json. Intrinsics are in pixels of [image], which is
 * in the camera sensor's orientation. [pose] is the camera in the leg's frame.
 */
data class CameraView(
    val image: String,
    /** ARCore DEPTH16 (little-endian millimeters), covering the same view as [image], or null. */
    val depth: String?,
    val depthWidth: Int,
    val depthHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val pose: CameraPose,
)

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
    /**
     * False when the leg starts from the scene rather than a flat sign: [photo] is only shown to
     * the follower, and the leg can be found only through [views] and a localization server.
     */
    val sign: Boolean = true,
    val views: List<CameraView> = emptyList(),
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

    /** Files next to route.json that this route uses. */
    val files: List<String>
        get() = legs.flatMap { leg -> listOf(leg.photo) + leg.views.flatMap { listOfNotNull(it.image, it.depth) } }.distinct()

    fun toJson(): JSONObject = JSONObject()
        // Older apps can't follow scene-only legs, so only those routes need the newer format.
        .put("format", if (legs.all { it.sign }) 1 else FORMAT)
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })

    companion object {
        const val FORMAT = 2

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
        .put("sign", sign)
        .put("views", JSONArray().apply { views.forEach { put(it.toJson()) } })
}

private fun CameraView.toJson(): JSONObject = JSONObject()
    .put("image", image)
    .put("depth", depth ?: "")
    .put("depthWidth", depthWidth)
    .put("depthHeight", depthHeight)
    .put("intrinsics", JSONArray().put(fx.toDouble()).put(fy.toDouble()).put(cx.toDouble()).put(cy.toDouble()))
    .put(
        "pose",
        JSONArray().apply { listOf(pose.tx, pose.ty, pose.tz, pose.qx, pose.qy, pose.qz, pose.qw).forEach { put(it.toDouble()) } },
    )

private fun viewFromJson(json: JSONObject): CameraView {
    val k = json.getJSONArray("intrinsics")
    val p = json.getJSONArray("pose")
    fun f(a: JSONArray, i: Int) = a.getDouble(i).toFloat()
    return CameraView(
        image = json.getString("image"),
        depth = json.optString("depth").ifEmpty { null },
        depthWidth = json.optInt("depthWidth"),
        depthHeight = json.optInt("depthHeight"),
        fx = f(k, 0), fy = f(k, 1), cx = f(k, 2), cy = f(k, 3),
        pose = CameraPose(f(p, 0), f(p, 1), f(p, 2), f(p, 3), f(p, 4), f(p, 5), f(p, 6)),
    )
}

private fun legFromJson(json: JSONObject): Leg {
    val flat = json.getJSONArray("points")
    val entry = json.getJSONObject("entry")
    val steps = json.optJSONArray("steps") ?: JSONArray()
    return Leg(
        photo = json.getString("photo"),
        photoWidth = json.optDouble("photoWidth", 0.0).toFloat(),
        entry = when (entry.getString("type")) {
            "elevator" -> LegEntry.Elevator(entry.optString("floor"))
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
        sign = json.optBoolean("sign", true),
        views = json.optJSONArray("views")?.let { views -> (0 until views.length()).map { viewFromJson(views.getJSONObject(it)) } }
            ?: emptyList(),
    )
}
