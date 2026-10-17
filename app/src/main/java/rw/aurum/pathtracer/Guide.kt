package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A floor polyline with distances along it ("s", in meters from its first point). */
class Path(val points: List<TrailPoint>) {
    private val cum = FloatArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + points[i].distanceTo(points[i - 1])
    }
    val length: Float get() = if (points.isEmpty()) 0f else cum.last()

    /** Distance along the path of point [index]. */
    fun distanceAt(index: Int): Float = cum[index.coerceIn(0, points.lastIndex)]

    fun pointAt(s: Float): TrailPoint {
        if (points.size < 2) return points.firstOrNull() ?: TrailPoint(0f, 0f, 0f)
        val i = segmentAt(s)
        val span = (cum[i + 1] - cum[i]).coerceAtLeast(1e-6f)
        val t = ((s - cum[i]) / span).coerceIn(0f, 1f)
        val a = points[i]
        val b = points[i + 1]
        return TrailPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }

    /** The part of the path between [from] and [to], with the distance of each point. */
    fun slice(from: Float, to: Float): List<Pair<TrailPoint, Float>> {
        if (points.size < 2) return emptyList()
        val a = from.coerceIn(0f, length)
        val b = to.coerceIn(a, length)
        val out = mutableListOf(pointAt(a) to a)
        for (i in points.indices) if (cum[i] > a && cum[i] < b) out += points[i] to cum[i]
        out += pointAt(b) to b
        return out
    }

    data class Fix(val s: Float, val offset: Float, val nearest: TrailPoint)

    /**
     * Finds where [p] is along the path. It looks around [near] first (a little behind, more
     * ahead) so a corridor walked twice doesn't make progress jump, and falls back on the whole
     * path when [p] is far from that window.
     */
    fun locate(p: TrailPoint, near: Float): Fix {
        if (points.size < 2) return Fix(0f, points.firstOrNull()?.distanceTo(p) ?: 0f, points.firstOrNull() ?: p)
        val local = nearest(p, near - LOCATE_BEHIND, near + LOCATE_AHEAD)
        if (local.offset <= LOCATE_REACH) return local
        val global = nearest(p, 0f, length)
        return if (global.offset < local.offset - 0.5f) global else local
    }

    private fun nearest(p: TrailPoint, from: Float, to: Float): Fix {
        var best = Fix(0f, Float.MAX_VALUE, points.first())
        for (i in 0 until points.lastIndex) {
            if (cum[i + 1] < from || cum[i] > to) continue
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len2 = dx * dx + dz * dz
            val t = if (len2 < 1e-8f) 0f else (((p.x - a.x) * dx + (p.z - a.z) * dz) / len2).coerceIn(0f, 1f)
            val q = TrailPoint(a.x + dx * t, a.y + (b.y - a.y) * t, a.z + dz * t)
            val d = q.distanceTo(p)
            if (d < best.offset) best = Fix(cum[i] + (cum[i + 1] - cum[i]) * t, d, q)
        }
        return best
    }

    private fun segmentAt(s: Float): Int {
        var lo = 0
        var hi = points.lastIndex - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    private companion object {
        const val LOCATE_BEHIND = 4f
        const val LOCATE_AHEAD = 20f
        const val LOCATE_REACH = 3f
    }
}

/** A photo inside a stretch: [leg] of the route, whose frame sits at [pose] in the stretch frame. */
data class StretchImage(val leg: Int, val pose: YawPose)

/**
 * Legs joined by checkpoints share one continuous frame, so they are followed as one stretch.
 * A new stretch begins at the entrance and after every elevator ride.
 */
class Stretch(
    val entry: LegEntry,
    val images: List<StretchImage>,
    /** Point index in [path] where each image's leg begins, matching [images]. */
    val legStarts: List<Int>,
    val path: Path,
    /** Stairs and escalators, with [Step.at] as an index into [path]. */
    val steps: List<Step>,
) {
    val firstLeg: Int get() = images.first().leg
}

fun Route.stretches(): List<Stretch> {
    val out = mutableListOf<Stretch>()
    var images = mutableListOf<StretchImage>()
    var starts = mutableListOf<Int>()
    var points = mutableListOf<TrailPoint>()
    var steps = mutableListOf<Step>()
    var entry: LegEntry = LegEntry.Start
    var pose = YawPose.Identity
    fun close() {
        if (images.isNotEmpty()) out += Stretch(entry, images, starts, Path(points), steps)
    }
    legs.forEachIndexed { i, leg ->
        val checkpoint = leg.entry as? LegEntry.Checkpoint
        if (checkpoint != null && images.isNotEmpty()) {
            pose = pose.compose(checkpoint.fromPrevious)
        } else {
            close()
            images = mutableListOf(); starts = mutableListOf(); points = mutableListOf(); steps = mutableListOf()
            entry = if (checkpoint != null) LegEntry.Start else leg.entry
            pose = YawPose.Identity
        }
        images += StretchImage(i, pose)
        starts += points.size
        leg.steps.forEach { steps += it.copy(at = points.size + it.at) }
        points += leg.points.map(pose::apply)
    }
    close()
    return out
}

/** Heading change in radians from direction [a] to [b] on the floor. Positive turns left. */
fun signedAngle(ax: Float, az: Float, bx: Float, bz: Float): Float =
    atan2(az * bx - ax * bz, ax * bx + az * bz)

/** Something the follower has to do at distance [s] along a stretch. */
sealed interface Maneuver {
    val s: Float

    /** [angle] in radians, positive to the left. [direction] is the way out, on the floor. */
    data class Turn(override val s: Float, val angle: Float, val at: TrailPoint, val dirX: Float, val dirZ: Float) : Maneuver
    data class Floor(override val s: Float, val step: Step) : Maneuver
    data class End(override val s: Float) : Maneuver
}

/** Turns worth announcing: sharp enough, and not a wobble around an obstacle. */
fun detectTurns(path: Path): List<Maneuver.Turn> {
    if (path.points.size < 3) return emptyList()
    fun dir(from: Float, to: Float): Pair<Float, Float>? {
        val a = path.pointAt(from)
        val b = path.pointAt(to)
        val len = hypot(b.x - a.x, b.z - a.z)
        return if (len < 0.2f) null else (b.x - a.x) / len to (b.z - a.z) / len
    }
    fun turn(sIn: Float, sOut: Float): Maneuver.Turn? {
        val din = dir(sIn - TURN_ARM, sIn) ?: return null
        val dout = dir(sOut, sOut + TURN_ARM) ?: return null
        val angle = signedAngle(din.first, din.second, dout.first, dout.second)
        if (abs(angle) < MIN_TURN) return null
        val s = (sIn + sOut) / 2
        return Maneuver.Turn(s, angle, path.pointAt(s), dout.first, dout.second)
    }

    val corners = simplify(path.points, SIMPLIFY_TOLERANCE)
        .map { path.distanceAt(it) }
        .filter { it > TURN_ARM / 2 && it < path.length - 0.5f }
    // Group corners that are close together and judge each group as one turn.
    val turns = mutableListOf<Maneuver.Turn>()
    var i = 0
    while (i < corners.size) {
        var j = i
        while (j + 1 < corners.size && corners[j + 1] - corners[j] < MERGE_DISTANCE) j++
        turn(corners[i], corners[j])?.let { turns += it }
        i = j + 1
    }
    return turns
}

/** Douglas-Peucker on the floor plane. Returns the indices of the interior corners kept. */
private fun simplify(points: List<TrailPoint>, tolerance: Float): List<Int> {
    val keep = BooleanArray(points.size)
    fun visit(first: Int, last: Int) {
        if (last - first < 2) return
        val a = points[first]
        val b = points[last]
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = hypot(dx, dz).coerceAtLeast(1e-6f)
        var worst = -1
        var worstDist = tolerance
        for (k in first + 1 until last) {
            val p = points[k]
            val d = abs((p.x - a.x) * dz - (p.z - a.z) * dx) / len
            if (d > worstDist) {
                worst = k
                worstDist = d
            }
        }
        if (worst < 0) return
        keep[worst] = true
        visit(first, worst)
        visit(worst, last)
    }
    visit(0, points.lastIndex)
    return points.indices.filter { keep[it] }
}

/** What to show the follower right now. */
data class Instruction(val kind: Kind, val title: String, val detail: String, val turn: Float? = null) {
    enum class Kind { Straight, Turn, Floor, Arrive, OffRoute }
}

fun Maneuver.instruction(distance: Float, next: Stretch?): Instruction {
    val inN = if (distance < NOW_DISTANCE) "now" else "in ${meters(distance)}"
    return when (this) {
        is Maneuver.Turn -> Instruction(Instruction.Kind.Turn, turnName(angle), inN, angle)
        is Maneuver.Floor -> Instruction(
            Instruction.Kind.Floor,
            "Take the ${step.change.label}",
            listOfNotNull(step.floor.takeIf { it.isNotBlank() }?.let { "to floor $it" }, inN).joinToString(" · "),
        )
        is Maneuver.End -> when (val entry = next?.entry) {
            null -> Instruction(Instruction.Kind.Arrive, if (distance < NOW_DISTANCE) "You've arrived" else "Destination ahead", inN)
            is LegEntry.Elevator -> Instruction(
                Instruction.Kind.Floor,
                "Take the elevator",
                listOfNotNull(entry.floor.takeIf { it.isNotBlank() }?.let { "to floor $it" }, inN).joinToString(" · "),
            )
            else -> Instruction(Instruction.Kind.Straight, "Keep going", inN)
        }
    }
}

/** The instruction as a sentence to read out, e.g. "Turn left in 4 meters". */
fun Instruction.spoken(): String {
    val words = detail.replace(" · ", ", ").replace(Regex("""(\d+(\.\d+)?) m\b"""), "$1 meters")
    return if (words.isEmpty()) title else "$title $words"
}

fun turnName(angle: Float): String {
    val deg = abs(Math.toDegrees(angle.toDouble()))
    val side = if (angle > 0) "left" else "right"
    return when {
        deg < 60 -> "Bear $side"
        deg < 150 -> "Turn $side"
        else -> "Turn around"
    }
}

fun meters(d: Float): String = if (d < 10f) "${(d * 2).roundToInt() / 2f} m".replace(".0 m", " m") else "${d.roundToInt()} m"

/** How the follower is doing on the current stretch. */
data class Guidance(
    val progress: Float,
    val offRoute: Boolean,
    val instruction: Instruction,
    /** Where the on-screen arrow should point, in the stretch frame. */
    val target: TrailPoint,
    /** Meters left to the destination along the whole route. */
    val remaining: Float,
    val floor: String,
    /** Index of the next maneuver, to notice when a new one comes up. */
    val maneuverKey: Int,
    val maneuverDistance: Float,
)

/**
 * Follows a route stretch by stretch. It knows nothing about ARCore: it is told which stretch the
 * follower is on and where they stand in that stretch's frame.
 */
class Navigator(val route: Route) {
    val stretches = route.stretches()
    private val maneuvers = stretches.map { stretch ->
        (detectTurns(stretch.path) +
            stretch.steps.map { Maneuver.Floor(stretch.path.distanceAt(it.at), it) } +
            Maneuver.End(stretch.path.length)).sortedBy { it.s }
    }
    private val lengths = stretches.map { it.path.length }

    var stretch = 0
        private set
    private var progress = 0f

    /** The follower was just seen at the start of [leg]'s photo. */
    fun lockOn(leg: Int) {
        val index = stretchOf(leg)
        val image = stretches[index].images.indexOfFirst { it.leg == leg }
        if (index != stretch) progress = stretches[index].path.distanceAt(stretches[index].legStarts[image])
        stretch = index
    }

    fun stretchOf(leg: Int): Int = stretches.indexOfFirst { s -> s.images.any { it.leg == leg } }

    /** Moves on to the next stretch, e.g. when the follower says they've left the elevator. */
    fun skip() {
        if (stretch < stretches.lastIndex) {
            stretch++
            progress = 0f
        }
    }

    val isLastStretch: Boolean get() = stretch == stretches.lastIndex

    fun update(position: TrailPoint): Guidance {
        val current = stretches[stretch]
        val path = current.path
        val fix = path.locate(position, progress)
        progress = fix.s
        val offRoute = fix.offset > OFF_ROUTE
        val list = maneuvers[stretch]
        val key = list.indexOfFirst { it.s > progress - PASSED }.let { if (it < 0) list.lastIndex else it }
        val next = list[key]
        val distance = (next.s - progress).coerceAtLeast(0f)
        val instruction = when {
            offRoute -> Instruction(Instruction.Kind.OffRoute, "Back to the path", "${meters(fix.offset)} away")
            distance > STRAIGHT_UNTIL -> Instruction(Instruction.Kind.Straight, "Go straight", "for ${meters(distance)}")
            else -> next.instruction(distance, stretches.getOrNull(stretch + 1))
        }
        val floor = current.steps.lastOrNull { path.distanceAt(it.at) <= progress && it.floor.isNotBlank() }?.floor
            ?: (current.entry as? LegEntry.Elevator)?.floor.orEmpty()
        return Guidance(
            progress = progress,
            offRoute = offRoute,
            instruction = instruction,
            target = if (offRoute) fix.nearest else path.pointAt(progress + ARROW_LEAD),
            remaining = (path.length - progress) + lengths.drop(stretch + 1).sum(),
            floor = floor,
            maneuverKey = key,
            maneuverDistance = distance,
        )
    }

    /** True once the follower has reached the end of the current stretch. */
    fun atStretchEnd(g: Guidance): Boolean =
        !g.offRoute && stretches[stretch].path.length - g.progress < END_RADIUS

    fun maneuversAhead(from: Float, to: Float): List<Maneuver> = maneuvers[stretch].filter { it.s in from..to }

    companion object {
        const val OFF_ROUTE = 2.5f
        const val END_RADIUS = 1.5f
        const val ARROW_LEAD = 2f
        private const val PASSED = 0.5f
        private const val STRAIGHT_UNTIL = 15f
    }
}

private const val TURN_ARM = 1.5f
private const val MERGE_DISTANCE = 2f
private const val SIMPLIFY_TOLERANCE = 0.35f
private val MIN_TURN = Math.toRadians(35.0).toFloat()
private const val NOW_DISTANCE = 1.5f
