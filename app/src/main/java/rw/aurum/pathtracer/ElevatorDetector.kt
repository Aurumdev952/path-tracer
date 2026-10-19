package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Notices elevator rides from barometric altitude. An elevator climbs fast while you stand still;
 * stairs and escalators climb slowly while you move forward, so they don't count.
 *
 * Feed it [altitude] samples from the pressure sensor, [step]s from the step detector and, while
 * ARCore tracks, [position] samples. Times are in milliseconds on one clock.
 */
class ElevatorDetector(private val c: Config = Config()) {

    data class Config(
        /** Pressure readings are noisy; altitude is smoothed over about this long. */
        val smoothingS: Float = 0.8f,
        /** Vertical speed is measured over this window. */
        val slopeWindowS: Float = 2f,
        /** A ride starts when climbing or falling at least this fast... */
        val startSpeed: Float = 0.5f,
        /** ...and the height changed at least this much over [lookbackS]... */
        val startRise: Float = 1.6f,
        val lookbackS: Float = 4f,
        /** ...while standing: walking less than this far and taking at most [maxSteps] steps. */
        val maxWalk: Float = 1.5f,
        val maxSteps: Int = 2,
        /**
         * Slow lifts count from [slowStartSpeed] and [slowStartRise], but only when ARCore tracked
         * at least [minTracked] of the lookback, so the walk check can still turn escalators away.
         */
        val slowStartSpeed: Float = 0.35f,
        val slowStartRise: Float = 1.2f,
        val minTracked: Float = 0.6f,
        /** Step reports come in late: steps taken during a ride cancel it until this long after it was noticed. */
        val confirmS: Float = 2f,
        /**
         * A door or the air handling can shift the pressure by a meter's worth or more at once. A car
         * leaving from rest gains at most about 0.75 m in its first second, and far less in [jumpS].
         */
        val jumpRise: Float = 1.2f,
        val jumpS: Float = 0.4f,
        /**
         * A height change that took less time than this, leaving out its first and last quarter
         * meter, was a pressure step. One floor at 1.25 m/s² takes about 2 s by that measure.
         */
        val minMotionS: Float = 1.5f,
        /** A ride ends after the height holds still (below this speed) for [stopHoldS]. */
        val stopSpeed: Float = 0.15f,
        val stopHoldS: Float = 2.5f,
        /** Shorter rides were a false alarm. */
        val minRide: Float = 2.5f,
    )

    sealed interface Event {
        /** [startedAt] is when the ride began, a little before it was noticed. */
        data class RideStarted(val startedAt: Long, val up: Boolean) : Event
        /** The elevator stopped. [rise] in meters since the ride began, negative going down. */
        data class RideEnded(val rise: Float, val endedAt: Long) : Event
        /** It wasn't a ride after all. */
        data object RideCancelled : Event
    }

    private class Sample(val t: Long, val v: Float)
    private class Spot(val t: Long, val x: Float, val z: Float)

    private val history = ArrayDeque<Sample>()
    private val raw = ArrayDeque<Sample>()
    private val spots = ArrayDeque<Spot>()
    private val steps = ArrayDeque<Long>()
    private var smoothed = Float.NaN
    private var lastT = 0L
    private var lastJump = Long.MIN_VALUE
    private var noticedAt = 0L
    private var startedAt = 0L
    private var startAlt = 0f
    private var stillSince = -1L

    /** Whether [step]s are coming: the phone has a step detector and may use it. Unknown steps can't veto a ride. */
    var stepsKnown = false

    var riding = false
        private set

    /** Meters climbed since the ride began; negative going down. 0 when not riding. */
    var rise = 0f
        private set

    /** The latest smoothed altitude, for display. NaN before the first sample. */
    val altitude: Float get() = smoothed

    fun position(t: Long, x: Float, z: Float) {
        spots.addLast(Spot(t, x, z))
        while (spots.isNotEmpty() && t - spots.first().t > KEEP_MS) spots.removeFirst()
    }

    /** A step taken at [t]. Reports may arrive a second or two late. */
    fun step(t: Long) {
        steps.addLast(t)
        while (steps.isNotEmpty() && t - steps.first() > KEEP_MS) steps.removeFirst()
    }

    fun altitude(t: Long, meters: Float): Event? {
        smoothed = if (smoothed.isNaN()) {
            meters
        } else {
            val alpha = 1f - exp(-(t - lastT).coerceAtLeast(0) / 1000f / c.smoothingS)
            smoothed + alpha * (meters - smoothed)
        }
        lastT = t
        history.addLast(Sample(t, smoothed))
        while (history.isNotEmpty() && t - history.first().t > KEEP_MS) history.removeFirst()
        raw.addLast(Sample(t, meters))
        while (raw.isNotEmpty() && t - raw.first().t > KEEP_MS) raw.removeFirst()
        if (jumped(t)) lastJump = t
        if (t - history.first().t < c.slopeWindowS * 1000) return null

        val speed = slope(t)
        return if (!riding) maybeStart(t, speed) else maybeEnd(t, speed)
    }

    private fun maybeStart(t: Long, speed: Float): Event? {
        val from = t - (c.lookbackS * 1000).toLong()
        val tracked = trackedShare(from, t) >= c.minTracked
        if (abs(speed) < if (tracked) c.slowStartSpeed else c.startSpeed) return null
        val then = at(from) ?: return null
        if (abs(smoothed - then) < if (tracked) c.slowStartRise else c.startRise) return null
        if (walked(from, t) > c.maxWalk) return null
        if (stepsKnown && stepsBetween(from, t) > c.maxSteps) return null
        if (lastJump >= from) return null
        val start = rideStart(t)
        riding = true
        noticedAt = t
        startedAt = start.t
        startAlt = start.v
        rise = smoothed - startAlt
        stillSince = -1L
        return Event.RideStarted(start.t, up = speed > 0)
    }

    private fun maybeEnd(t: Long, speed: Float): Event? {
        val confirming = t - noticedAt <= c.confirmS * 1000
        if (confirming && (lastJump > noticedAt || stepsKnown && stepsBetween(startedAt, t) > c.maxSteps)) return cancel()
        // Smoothing makes a steady climb lag by speed x smoothing time; add that back while moving.
        rise = smoothed + speed * c.smoothingS - startAlt
        if (abs(speed) >= c.stopSpeed) {
            stillSince = -1L
            return null
        }
        if (stillSince < 0) stillSince = t
        if (t - stillSince < c.stopHoldS * 1000) return null
        val total = smoothed - startAlt
        // Stillness shows up a slope window and a smoothing time after the car actually stopped.
        val stoppedAt = stillSince - ((c.slopeWindowS / 2 + c.smoothingS) * 1000).toLong()
        val motion = motionTime(startedAt, t)
        if (abs(total) < c.minRide || motion != null && motion < c.minMotionS * 1000) return cancel()
        riding = false
        rise = 0f
        return Event.RideEnded(total, stoppedAt)
    }

    private fun cancel(): Event {
        riding = false
        rise = 0f
        return Event.RideCancelled
    }

    /** Going back from now, the last moment the height was still: where the ride began. */
    private fun rideStart(t: Long): Sample {
        var i = history.lastIndex
        while (i > 0) {
            val s = history[i]
            val second = at(s.t - 1000) ?: break
            if (abs(s.v - second) < STILL_PER_SECOND && t - s.t > c.slopeWindowS * 1000) return s
            i--
        }
        return history[i]
    }

    /** Least-squares vertical speed over the last window, in meters per second. */
    private fun slope(t: Long): Float {
        val from = t - (c.slopeWindowS * 1000).toLong()
        var n = 0
        var st = 0.0
        var sv = 0.0
        var stt = 0.0
        var stv = 0.0
        for (s in history) {
            if (s.t < from) continue
            val x = (s.t - from) / 1000.0
            n++
            st += x; sv += s.v; stt += x * x; stv += x * s.v
        }
        val d = n * stt - st * st
        return if (n < 3 || d <= 1e-9) 0f else ((n * stv - st * sv) / d).toFloat()
    }

    /** Smoothed altitude at time [t], or null if the history doesn't reach back that far. */
    private fun at(t: Long): Float? {
        if (history.isEmpty() || history.first().t > t) return null
        return history.lastOrNull { it.t <= t }?.v
    }

    /** Whether the raw height leapt by [Config.jumpRise] within [Config.jumpS] after holding still. */
    private fun jumped(t: Long): Boolean {
        val jump = (c.jumpS * 1000).toLong()
        val before = rawMean(t - jump - 1000, t - jump - 500) ?: return false
        val still = rawMean(t - jump - 500, t - jump) ?: return false
        val now = rawMean(t - 200, t) ?: return false
        return abs(still - before) < STILL_PER_SECOND && abs(now - still) > c.jumpRise
    }

    /** Mean raw altitude after [from] up to [to], or null without samples. */
    private fun rawMean(from: Long, to: Long): Float? {
        var n = 0
        var sum = 0f
        for (i in raw.indices.reversed()) {
            val s = raw[i]
            if (s.t <= from) break
            if (s.t > to) continue
            n++
            sum += s.v
        }
        return if (n == 0) null else sum / n
    }

    /**
     * How long the raw height took to get from its level before [from] to its level at [to],
     * leaving out the first and last [MOTION_BAND_M] that noise blurs. Null when the history
     * doesn't reach back that far, which only happens on long rides.
     */
    private fun motionTime(from: Long, to: Long): Long? {
        if (raw.first().t > from - 1000) return null
        val before = rawMean(from - 1000, from) ?: return null
        val after = rawMean(to - 1000, to) ?: return null
        val sign = if (after > before) 1f else -1f
        var began = -1L
        var ended = -1L
        val recent = ArrayDeque<Sample>()
        var sum = 0f
        for (s in raw) {
            if (s.t <= from) continue
            recent.addLast(s)
            sum += s.v
            while (s.t - recent.first().t >= MOTION_MEAN_MS) sum -= recent.removeFirst().v
            val level = sum / recent.size
            if (began < 0 && (level - before) * sign > MOTION_BAND_M) began = s.t
            if ((after - level) * sign > MOTION_BAND_M) ended = s.t
        }
        return if (began < 0) 0 else ended - began
    }

    /** How far ARCore saw the phone move sideways. A leap is ARCore correcting itself, not walking. */
    private fun walked(from: Long, to: Long): Float {
        var total = 0f
        var prev: Spot? = null
        for (s in spots) {
            if (s.t < from || s.t > to) continue
            prev?.let { p -> hypot(s.x - p.x, s.z - p.z).takeIf { it <= MAX_HOP_M }?.let { total += it } }
            prev = s
        }
        return total
    }

    /** The share of the time from [from] to [to] that ARCore was tracking. */
    private fun trackedShare(from: Long, to: Long): Float {
        var covered = 0L
        var prev: Long? = null
        for (s in spots) {
            if (s.t < from || s.t > to) continue
            prev?.let { p -> (s.t - p).takeIf { it <= MAX_GAP_MS }?.let { covered += it } }
            prev = s.t
        }
        return covered.toFloat() / (to - from)
    }

    private fun stepsBetween(from: Long, to: Long): Int = steps.count { it in from..to }

    private companion object {
        const val KEEP_MS = 30_000L
        const val MOTION_MEAN_MS = 300L
        const val MOTION_BAND_M = 0.25f
        const val STILL_PER_SECOND = 0.2f
        /** A longer gap between ARCore poses means tracking was lost in between. */
        const val MAX_GAP_MS = 500L
        const val MAX_HOP_M = 1f
    }
}

/** Typical height of one floor, for guessing floors from meters. Real buildings vary. */
const val FLOOR_HEIGHT_M = 3.2f

/** "G", "Ground", "L", "Lobby" or "0" is 0; "B2" is -2; "15" is 15. Null if it isn't a floor number. */
fun floorNumber(label: String): Int? {
    val s = label.trim().uppercase()
    return when {
        s.isEmpty() -> null
        s in setOf("G", "GF", "GROUND", "L", "LOBBY", "RDC") -> 0
        s.startsWith("B") -> s.drop(1).toIntOrNull()?.let { -it }
        else -> s.toIntOrNull()
    }
}

fun floorLabel(n: Int): String = when {
    n == 0 -> "G"
    n < 0 -> "B${-n}"
    else -> n.toString()
}

/** The floor reached after rising [rise] meters from floor [from], or null if [from] isn't a floor number. */
fun guessFloor(from: String, rise: Float): String? =
    floorNumber(from)?.let { floorLabel(it + (rise / FLOOR_HEIGHT_M).roundToInt()) }

/** "about 10 floors down", for when the floor can't be named. */
fun rideSummary(rise: Float): String {
    val n = floorsIn(rise).coerceAtLeast(1)
    return "about ${if (n == 1) "1 floor" else "$n floors"} ${if (rise < 0) "down" else "up"}"
}

/** Whole floors in [rise] meters, for messages like "up 14 floors". */
fun floorsIn(rise: Float): Int = (abs(rise) / FLOOR_HEIGHT_M).roundToInt()

/** Where an elevator ride left the follower, compared with the recorded ride. */
sealed interface RideCheck {
    data object Arrived : RideCheck
    data class NotYet(val floors: Int) : RideCheck
    data class TooFar(val floors: Int) : RideCheck
    /** The recording didn't measure the ride, so there's nothing to compare. */
    data object Unknown : RideCheck
}

/** Compares [ridden] meters with the [expected] rise of the recorded ride. */
fun checkRide(expected: Float?, ridden: Float): RideCheck {
    if (expected == null) return RideCheck.Unknown
    val left = expected - ridden
    return when {
        abs(left) <= FLOOR_HEIGHT_M * 0.5f -> RideCheck.Arrived
        (left > 0) == (expected > 0) -> RideCheck.NotYet(floorsIn(left).coerceAtLeast(1))
        else -> RideCheck.TooFar(floorsIn(left).coerceAtLeast(1))
    }
}

/** What to tell the follower when the elevator stops. */
fun rideMessage(check: RideCheck, floor: String): String {
    val target = floor.takeIf { it.isNotBlank() }
    return when (check) {
        RideCheck.Arrived -> (if (target != null) "This is floor $target. " else "This is your floor. ") +
            "Step out and point your camera at the sign in the photo"
        is RideCheck.NotYet -> "Not yet. Stay in for about ${floors(check.floors)}" + (target?.let { ", to floor $it" } ?: "")
        is RideCheck.TooFar -> "You went about ${floors(check.floors)} too far" + (target?.let { ". Go back to floor $it" } ?: "")
        RideCheck.Unknown -> (if (target != null) "If this is floor $target, step out" else "When you reach your floor, step out") +
            " and point your camera at the sign in the photo"
    }
}

private fun floors(n: Int) = if (n == 1) "1 floor" else "$n floors"

