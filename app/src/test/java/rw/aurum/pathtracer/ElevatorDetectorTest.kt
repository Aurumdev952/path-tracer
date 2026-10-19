package rw.aurum.pathtracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.random.Random

class ElevatorDetectorTest {

    /** A height (and optional walking) over time, sampled 10 times a second with sensor noise. */
    private class Trace(seed: Int = 1) {
        val rng = Random(seed)
        val samples = mutableListOf<Triple<Long, Float, Pair<Float, Float>?>>()
        var t = 0L
        var height = 0f
        var x = 0f

        /** Moves [dh] meters up and [dx] forward over [seconds], at a steady pace. */
        fun go(seconds: Float, dh: Float = 0f, dx: Float = 0f, tracked: Boolean = true) {
            val n = (seconds * 10).toInt()
            repeat(n) {
                t += 100
                height += dh / n
                x += dx / n
                val noise = (rng.nextFloat() - 0.5f) * 0.4f
                samples += Triple(t, height + noise, if (tracked) x to 0f else null)
            }
        }

        /** An elevator: speeds up, cruises at [speed], slows down, covering [dh] meters. */
        fun ride(dh: Float, speed: Float = 2.5f) {
            val ramp = 2f
            val cruise = (kotlin.math.abs(dh) - speed * ramp) / speed
            val sign = if (dh > 0) 1 else -1
            // Average speed while speeding up or slowing down is half the top speed.
            go(ramp, sign * speed * ramp / 2)
            go(cruise, sign * speed * cruise)
            go(ramp, sign * speed * ramp / 2)
        }

        fun run(d: ElevatorDetector): List<Pair<Long, ElevatorDetector.Event>> = samples.mapNotNull { (t, h, p) ->
            p?.let { d.position(t, it.first, it.second) }
            d.altitude(t, h)?.let { t to it }
        }
    }

    @Test fun findsAnElevatorRideUpFromGroundToFifteen() {
        val trace = Trace()
        trace.go(10f)
        val rideBegins = trace.t
        trace.ride(48f)
        val rideStops = trace.t
        trace.go(10f)

        val events = trace.run(ElevatorDetector())

        assertEquals(2, events.size)
        val (noticedAt, started) = events[0]
        started as ElevatorDetector.Event.RideStarted
        assertTrue(started.up)
        assertTrue("noticed ${noticedAt - rideBegins} ms in", noticedAt - rideBegins < 4000)
        assertTrue("start off by ${started.startedAt - rideBegins} ms", kotlin.math.abs(started.startedAt - rideBegins) < 1500)
        val ended = events[1].second as ElevatorDetector.Event.RideEnded
        assertEquals(48f, ended.rise, 1.5f)
        assertTrue("stop off by ${ended.endedAt - rideStops} ms", kotlin.math.abs(ended.endedAt - rideStops) < 2000)
        assertEquals("15", guessFloor("G", ended.rise))
    }

    @Test fun findsARideDown() {
        val trace = Trace(2)
        trace.go(8f)
        trace.ride(-19f)
        trace.go(8f)
        val ended = trace.run(ElevatorDetector()).last().second as ElevatorDetector.Event.RideEnded
        assertEquals(-19f, ended.rise, 1.5f)
        assertEquals("B4", guessFloor("2", ended.rise))
    }

    @Test fun aSlowElevatorStillCounts() {
        val trace = Trace(3)
        trace.go(8f)
        trace.ride(9.6f, speed = 0.8f)
        trace.go(8f)
        val ended = trace.run(ElevatorDetector()).last().second as ElevatorDetector.Event.RideEnded
        assertEquals(9.6f, ended.rise, 1.2f)
    }

    @Test fun anElevatorThatStopsOnTheWayGivesTwoRides() {
        val trace = Trace(4)
        trace.go(8f)
        trace.ride(16f)
        trace.go(8f) // doors open at floor 5, nobody gets out
        trace.ride(32f)
        trace.go(8f)
        val ends = trace.run(ElevatorDetector()).map { it.second }.filterIsInstance<ElevatorDetector.Event.RideEnded>()
        assertEquals(2, ends.size)
        assertEquals(48f, ends.sumOf { it.rise.toDouble() }.toFloat(), 2f)
    }

    @Test fun stairsAreNotAnElevator() {
        val trace = Trace(5)
        trace.go(5f)
        // Three flights at a brisk pace: up 3.2 m and 5 m along each, with a landing between.
        repeat(3) {
            trace.go(7f, dh = 3.2f, dx = 5f)
            trace.go(2f, dx = 1.5f)
        }
        trace.go(5f)
        assertTrue(trace.run(ElevatorDetector()).isEmpty())
    }

    @Test fun anEscalatorIsNotAnElevator() {
        val trace = Trace(6)
        trace.go(5f)
        trace.go(18f, dh = 4.5f, dx = 7.8f)
        trace.go(5f)
        assertTrue(trace.run(ElevatorDetector()).isEmpty())
    }

    @Test fun aDoorGustIsNotARide() {
        val trace = Trace(7)
        trace.go(8f)
        trace.go(0.5f, dh = 1f)
        trace.go(1f)
        trace.go(0.5f, dh = -1f)
        trace.go(8f)
        val events = trace.run(ElevatorDetector()).map { it.second }
        assertTrue(events.none { it is ElevatorDetector.Event.RideEnded })
    }

    @Test fun weatherDriftIsNotARide() {
        val trace = Trace(8)
        trace.go(600f, dh = 3f)
        assertTrue(trace.run(ElevatorDetector()).isEmpty())
    }

    @Test fun reportsTheRiseWhileRiding() {
        val trace = Trace(9)
        trace.go(8f)
        trace.go(2f, dh = 2.5f)
        trace.go(4f, dh = 10f)
        val d = ElevatorDetector()
        trace.run(d)
        assertTrue(d.riding)
        assertEquals(12.5f, d.rise, 1.5f)
    }

    @Test fun tellsTheFollowerWhetherThisIsTheirFloor() {
        // Recorded: G to 15, 48 m.
        assertEquals(RideCheck.Arrived, checkRide(48f, 47f))
        assertEquals(RideCheck.NotYet(9), checkRide(48f, 19f))
        assertEquals(RideCheck.TooFar(2), checkRide(48f, 54.5f))
        assertEquals(RideCheck.NotYet(3), checkRide(-12f, -2f))
        assertEquals(RideCheck.Unknown, checkRide(null, 30f))
        assertEquals("Not yet. Stay in for about 9 floors, to floor 15", rideMessage(RideCheck.NotYet(9), "15"))
        assertEquals("This is floor 15. Step out and point your camera at the sign in the photo", rideMessage(RideCheck.Arrived, "15"))
        assertEquals("You went about 1 floor too far. Go back to floor 15", rideMessage(RideCheck.TooFar(1), "15"))
    }

    @Test fun doesNotInventAFloorWhenTheStartFloorIsUnknown() {
        assertNull(guessFloor("", -33.3f))
        assertEquals("2", guessFloor("12", -33.3f))
        assertEquals("about 10 floors down", rideSummary(-33.3f))
        assertEquals("about 1 floor up", rideSummary(3.4f))
    }

    @Test fun guessesFloors() {
        assertEquals(0, floorNumber("Ground"))
        assertEquals(-2, floorNumber("b2"))
        assertEquals(15, floorNumber(" 15 "))
        assertNull(floorNumber("Radiology"))
        assertEquals("16", guessFloor("12", 12.8f))
        assertEquals("G", guessFloor("4", -12.8f))
        assertEquals(4, floorsIn(-13f))
        assertEquals("G", floorLabel(min(0, 0)))
    }
}
