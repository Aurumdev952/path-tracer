package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Notices elevator rides from barometric altitude. An elevator climbs fast while you stand still;
 * stairs and escalators climb slowly while you move forward, so they don't count.
 *
 * Feed it [altitude] samples from the pressure sensor and, while ARCore tracks, [position]
 * samples. Times are in milliseconds on one clock.
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
        /** ...while walking less than this far. */
        val maxWalk: Float = 1.5f,
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
    private val spots = ArrayDeque<Spot>()
    private var smoothed = Float.NaN
    private var lastT = 0L
    private var startAlt = 0f
    private var stillSince = -1L

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
        if (t - history.first().t < c.slopeWindowS * 1000) return null

        val speed = slope(t)
        return if (!riding) maybeStart(t, speed) else maybeEnd(t, speed)
    }

    private fun maybeStart(t: Long, speed: Float): Event? {
        if (abs(speed) < c.startSpeed) return null
        val then = at(t - (c.lookbackS * 1000).toLong()) ?: return null
        if (abs(smoothed - then) < c.startRise) return null
        if (walked(t - (c.lookbackS * 1000).toLong(), t) > c.maxWalk) return null
        val start = rideStart(t)
        riding = true
        startAlt = start.v
        rise = smoothed - startAlt
        stillSince = -1L
        return Event.RideStarted(start.t, up = speed > 0)
    }

    private fun maybeEnd(t: Long, speed: Float): Event? {
        // Smoothing makes a steady climb lag by speed x smoothing time; add that back while moving.
        rise = smoothed + speed * c.smoothingS - startAlt
        if (abs(speed) >= c.stopSpeed) {
            stillSince = -1L
            return null
        }
        if (stillSince < 0) stillSince = t
        if (t - stillSince < c.stopHoldS * 1000) return null
        riding = false
        val total = smoothed - startAlt
        rise = 0f
        // Stillness shows up a slope window and a smoothing time after the car actually stopped.
        val stoppedAt = stillSince - ((c.slopeWindowS / 2 + c.smoothingS) * 1000).toLong()
        return if (abs(total) >= c.minRide) Event.RideEnded(total, stoppedAt) else Event.RideCancelled
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

    private fun walked(from: Long, to: Long): Float {
        var total = 0f
        var prev: Spot? = null
        for (s in spots) {
            if (s.t < from || s.t > to) continue
            prev?.let { total += hypot(s.x - it.x, s.z - it.z) }
            prev = s
        }
        return total
    }

    private companion object {
        const val KEEP_MS = 30_000L
        const val STILL_PER_SECOND = 0.2f
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

/** The floor reached after rising [rise] meters from [from] (assumed ground floor if unknown). */
fun guessFloor(from: String, rise: Float): String =
    floorLabel((floorNumber(from) ?: 0) + (rise / FLOOR_HEIGHT_M).roundToInt())

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

fun rideSummary(rise: Float): String = TODO()
