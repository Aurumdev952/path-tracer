package rw.aurum.pathtracer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class GuideTest {

    /** Points every 12 cm along straight lines through [corners] (x, z pairs). */
    private fun walk(vararg corners: Pair<Float, Float>): List<TrailPoint> {
        val out = mutableListOf(TrailPoint(corners[0].first, 0f, corners[0].second))
        for (i in 1 until corners.size) {
            val (ax, az) = corners[i - 1]
            val (bx, bz) = corners[i]
            val n = (kotlin.math.hypot(bx - ax, bz - az) / 0.12f).toInt().coerceAtLeast(1)
            for (k in 1..n) out += TrailPoint(ax + (bx - ax) * k / n, 0f, az + (bz - az) * k / n)
        }
        return out
    }

    private fun leg(points: List<TrailPoint>, entry: LegEntry = LegEntry.Start, steps: List<Step> = emptyList()) =
        Leg("leg.jpg", 0.3f, entry, points, steps)

    private fun route(vararg legs: Leg) = Route("id", "Room 512", 0L, legs.toList())

    @Test fun yawPoseInverseUndoesIt() {
        val pose = YawPose(1.5f, 0.2f, -3f, 0.7f)
        val p = TrailPoint(0.4f, 1f, 2f)
        val back = pose.inverse().apply(pose.apply(p))
        assertEquals(p.x, back.x, 1e-4f)
        assertEquals(p.y, back.y, 1e-4f)
        assertEquals(p.z, back.z, 1e-4f)
        val id = pose.compose(pose.inverse())
        assertEquals(0f, id.x, 1e-4f)
        assertEquals(0f, id.z, 1e-4f)
        assertEquals(0f, id.yaw, 1e-4f)
    }

    @Test fun yawPoseComposeMatchesApplyingTwice() {
        val a = YawPose(1f, 0f, 2f, 0.5f)
        val b = YawPose(-0.5f, 0.3f, 1f, -1.2f)
        val p = TrailPoint(0.3f, 0f, -0.8f)
        val once = a.compose(b).apply(p)
        val twice = a.apply(b.apply(p))
        assertEquals(twice.x, once.x, 1e-4f)
        assertEquals(twice.y, once.y, 1e-4f)
        assertEquals(twice.z, once.z, 1e-4f)
    }

    @Test fun rightTurnIsNegative() {
        // Facing forward (-z), then facing +x is a right turn.
        assertTrue(signedAngle(0f, -1f, 1f, 0f) < 0)
        assertTrue(signedAngle(0f, -1f, -1f, 0f) > 0)
    }

    @Test fun findsOneRightTurnOnAnLShapedWalk() {
        val path = Path(walk(0f to 0f, 0f to -6f, 6f to -6f))
        val turns = detectTurns(path)
        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals(-90.0, Math.toDegrees(turn.angle.toDouble()), 8.0)
        assertEquals(6f, turn.s, 0.5f)
        assertEquals("Turn right", turnName(turn.angle))
    }

    @Test fun ignoresSmallWobbles() {
        val points = (0..80).map { i -> TrailPoint(0.15f * sin(i / 6f), 0f, -i * 0.12f) }
        assertTrue(detectTurns(Path(points)).isEmpty())
    }

    @Test fun aSideStepAroundAnObstacleIsNotATurn() {
        val path = Path(walk(0f to 0f, 0f to -4f, 0.6f to -4.6f, 0.6f to -5.6f, 0f to -6.2f, 0f to -10f))
        assertTrue(detectTurns(path).isEmpty())
    }

    @Test fun locateStaysOnTheRightPassOfACorridorWalkedTwice() {
        // Down a corridor and back the same way.
        val path = Path(walk(0f to 0f, 0f to -8f, 0.3f to -8f, 0.3f to 0f))
        val out = path.locate(TrailPoint(0.15f, 0f, -2f), near = 2f)
        assertEquals(2f, out.s, 0.3f)
        val back = path.locate(TrailPoint(0.15f, 0f, -2f), near = 13f)
        assertEquals(14.3f, back.s, 0.3f)
    }

    @Test fun guidesThroughAnLShapedRoute() {
        val nav = Navigator(route(leg(walk(0f to 0f, 0f to -10f, 6f to -10f))))
        val start = nav.update(TrailPoint(0f, 0f, 0f))
        assertEquals("Turn right", start.instruction.title)
        assertEquals("in 10 m", start.instruction.detail)
        assertEquals(16f, start.remaining, 0.2f)

        val atTurn = nav.update(TrailPoint(0f, 0f, -9.5f))
        assertEquals("now", atTurn.instruction.detail)

        val after = nav.update(TrailPoint(3f, 0f, -10f))
        assertEquals("Destination ahead", after.instruction.title)
        assertFalse(nav.atStretchEnd(after))

        val end = nav.update(TrailPoint(5.8f, 0f, -10f))
        assertEquals("You've arrived", end.instruction.title)
        assertTrue(nav.atStretchEnd(end))
    }

    @Test fun saysGoStraightWhenTheNextTurnIsFar() {
        val nav = Navigator(route(leg(walk(0f to 0f, 0f to -30f, 5f to -30f))))
        val g = nav.update(TrailPoint(0f, 0f, 0f))
        assertEquals("Go straight", g.instruction.title)
        assertEquals("for 30 m", g.instruction.detail)
    }

    @Test fun warnsWhenOffRouteAndPointsBackToThePath() {
        val nav = Navigator(route(leg(walk(0f to 0f, 0f to -10f))))
        nav.update(TrailPoint(0f, 0f, -4f))
        val g = nav.update(TrailPoint(4f, 0f, -5f))
        assertTrue(g.offRoute)
        assertEquals("Back to the path", g.instruction.title)
        assertEquals(0f, g.target.x, 0.05f)
        assertEquals(-5f, g.target.z, 0.2f)
    }

    @Test fun checkpointLegsJoinIntoOneStretchAndElevatorsSplit() {
        // Leg 2's photo is 5 m ahead of leg 1's, turned a quarter turn to the left.
        val offset = YawPose(0f, 0f, -5f, (PI / 2).toFloat())
        val r = route(
            leg(walk(0f to 0f, 0f to -5f)),
            leg(walk(0f to 0f, 0f to -3f), LegEntry.Checkpoint(offset)),
            leg(walk(0f to 0f, 0f to -4f), LegEntry.Elevator("5")),
        )
        val stretches = r.stretches()
        assertEquals(2, stretches.size)
        assertEquals(listOf(0, 1), stretches[0].images.map { it.leg })
        assertEquals(LegEntry.Elevator("5"), stretches[1].entry)
        // Walking "forward" in leg 2's frame is walking -x in leg 1's frame after a left quarter turn.
        val last = stretches[0].path.points.last()
        assertEquals(-3f, last.x, 0.01f)
        assertEquals(-5f, last.z, 0.01f)
        // That bend is announced as a left turn.
        val turn = detectTurns(stretches[0].path).single()
        assertEquals("Turn left", turnName(turn.angle))
    }

    @Test fun announcesTheElevatorAndFloors() {
        val r = route(
            leg(walk(0f to 0f, 0f to -6f)),
            leg(walk(0f to 0f, 0f to -6f), LegEntry.Elevator("5"), listOf(Step(10, FloorChange.Stairs, "6"))),
        )
        val nav = Navigator(r)
        val g = nav.update(TrailPoint(0f, 0f, -2f))
        assertEquals("Take the elevator", g.instruction.title)
        assertEquals("to floor 5 · in 4 m", g.instruction.detail)
        assertEquals(10f, g.remaining, 0.2f)

        nav.lockOn(1)
        assertEquals(1, nav.stretch)
        val up = nav.update(TrailPoint(0f, 0f, -0.2f))
        assertEquals("Take the stairs", up.instruction.title)
        assertEquals("5", up.floor)
        val upstairs = nav.update(TrailPoint(0f, 0f, -3f))
        assertEquals("6", upstairs.floor)
    }

    @Test fun routeSurvivesJson() {
        val r = route(
            leg(walk(0f to 0f, 0f to -1f)),
            leg(walk(0f to 0f, 1f to 0f), LegEntry.Checkpoint(YawPose(1f, 0.1f, 2f, 0.3f))),
            leg(walk(0f to 0f, 0f to 1f), LegEntry.Elevator("B1"), listOf(Step(3, FloorChange.Escalator, "G"))),
        )
        val back = Route.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r.name, back.name)
        assertEquals(r.legs.map { it.entry }, back.legs.map { it.entry })
        assertEquals(r.legs[2].steps, back.legs[2].steps)
        assertEquals(r.legs[1].points.size, back.legs[1].points.size)
        assertTrue(abs(r.lengthMeters - back.lengthMeters) < 1e-3f)
        assertEquals(listOf("B1", "G"), back.floors)
    }

    @Test fun speaksInstructionsAsSentences() {
        assertEquals("Turn left in 4.5 meters", Instruction(Instruction.Kind.Turn, "Turn left", "in 4.5 m").spoken())
        assertEquals("Turn right now", Instruction(Instruction.Kind.Turn, "Turn right", "now").spoken())
        assertEquals(
            "Take the elevator to floor 5, in 12 meters",
            Instruction(Instruction.Kind.Floor, "Take the elevator", "to floor 5 · in 12 m").spoken(),
        )
    }

    @Test fun sceneLegsNeedTheNewFormatAndOldRoutesStayCompatible() {
        val signs = route(leg(walk(0f to 0f, 0f to -1f)))
        assertEquals(1, signs.toJson().getInt("format"))
        val scene = route(leg(walk(0f to 0f, 0f to -1f)).copy(sign = false))
        assertEquals(2, scene.toJson().getInt("format"))
        assertFalse(Route.fromJson(JSONObject(scene.toJson().toString())).legs[0].sign)
        // Routes saved before views existed still load, as sign legs with no views.
        val old = JSONObject(signs.toJson().toString())
        old.getJSONArray("legs").getJSONObject(0).apply { remove("sign"); remove("views") }
        val back = Route.fromJson(old).legs[0]
        assertTrue(back.sign)
        assertTrue(back.views.isEmpty())
    }

    @Test fun formatsDistances() {
        assertEquals("4 m", meters(4.1f))
        assertEquals("4.5 m", meters(4.4f))
        assertEquals("12 m", meters(12.4f))
    }
}
