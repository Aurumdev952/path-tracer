package rw.aurum.pathtracer

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class RouteAlignerTest {

    /** Points every 12 cm along straight lines through [corners] (x, z pairs), like a recording. */
    private fun walk(vararg corners: Pair<Float, Float>): List<TrailPoint> {
        val out = mutableListOf(TrailPoint(corners[0].first, 0f, corners[0].second))
        for (i in 1 until corners.size) {
            val (ax, az) = corners[i - 1]
            val (bx, bz) = corners[i]
            val n = (hypot(bx - ax, bz - az) / 0.12f).toInt().coerceAtLeast(1)
            for (k in 1..n) out += TrailPoint(ax + (bx - ax) * k / n, 0f, az + (bz - az) * k / n)
        }
        return out
    }

    /** 25 m ahead from the photo at the origin, then 20 m to the right. */
    private val route = Path(walk(0f to -1f, 0f to -26f, 20f to -26f))

    /**
     * Walks [truth] (points in the stretch frame) as seen through an anchor that is off by
     * [error], feeding the aligner like the follower does. Returns how far the follower's spot on
     * the route ended up from where they really were, at the end.
     */
    private fun follow(
        truth: List<TrailPoint>,
        error: (Int) -> YawPose,
        aligner: RouteAligner = RouteAligner(route),
        each: (Int, RouteAligner) -> Unit = { _, _ -> },
    ): Float {
        aligner.landmark(0, TrailPoint(0f, 0f, 0f), TrailPoint(0f, 0f, 0f))
        var s = 0f
        var miss = 0f
        truth.forEachIndexed { i, p ->
            val q = error(i).inverse().apply(p)
            s = route.locate(aligner.fit.apply(q), s).s
            aligner.walk(q, s)
            each(i, aligner)
            val seen = aligner.fit.apply(q)
            miss = hypot(seen.x - p.x, seen.z - p.z)
        }
        return miss
    }

    private fun turned(deg: Double) = YawPose(0f, 0f, 0f, Math.toRadians(deg).toFloat())

    private fun degrees(a: RouteAligner) = Math.toDegrees(a.fit.yaw.toDouble())

    @Test fun aPhotoFacingTenDegreesOffIsCorrectedWithinTenMeters() {
        // The field failure: a route turned 12 degrees about its photo.
        val aligner = RouteAligner(route)
        val miss = follow(walk(0f to -1f, 0f to -12f), { turned(12.0) }, aligner)
        assertTrue("turn ${degrees(aligner)}", abs(degrees(aligner) - 12) < 2)
        assertTrue("miss $miss", miss < 0.3f)
    }

    @Test fun aGoodPhotoIsLeftAlone() {
        val aligner = RouteAligner(route)
        val miss = follow(walk(0f to -1f, 0f to -26f, 20f to -26f), { YawPose.Identity }, aligner)
        assertTrue("turn ${degrees(aligner)}", abs(degrees(aligner)) < 1)
        assertTrue("miss $miss", miss < 0.2f)
    }

    @Test fun walkingOffToOneSideOfTheCorridorStillFindsTheTurn() {
        // The follower weaves up to 40 cm either side of where the recorder walked.
        val truth = walk(0f to -1f, 0f to -26f, 20f to -26f).mapIndexed { i, p ->
            val wobble = 0.4f * sin(i * 0.05f)
            if (p.z > -25.9f) TrailPoint(p.x + wobble, 0f, p.z) else TrailPoint(p.x, 0f, p.z + wobble)
        }
        val aligner = RouteAligner(route)
        val miss = follow(truth, { turned(-10.0) }, aligner)
        assertTrue("turn ${degrees(aligner)}", abs(degrees(aligner) + 10) < 2.5)
        assertTrue("miss $miss", miss < 0.6f)
    }

    @Test fun slowSidewaysDriftIsFollowed() {
        // Down the first corridor, ARCore's frame slides sideways 4 cm per meter after 10 m:
        // 0.6 m by the corner. (Drift along a corridor can't show in the walk; turns and photos fix that.)
        val truth = walk(0f to -1f, 0f to -25f)
        val miss = follow(truth, { i -> YawPose(0.04f * 0.12f * (i - 80).coerceAtLeast(0), 0f, 0f, 0f) })
        assertTrue("miss $miss", miss < 0.25f)
    }

    @Test fun aCheckpointPhotoPinsTheRoute() {
        // Halfway, ARCore's frame jumps 1 m sideways; then a photo at the corner is seen.
        val truth = walk(0f to -1f, 0f to -26f, 20f to -26f)
        val jump = YawPose(1f, 0f, 0f, 0f)
        val corner = truth.indexOfFirst { it.z <= -25.9f }
        val miss = follow(truth, { i -> if (i > corner / 2) jump else YawPose.Identity }) { i, a ->
            if (i == corner) a.landmark(1, jump.inverse().apply(TrailPoint(-1f, 0f, -26f)), TrailPoint(-1f, 0f, -26f))
        }
        assertTrue("miss $miss", miss < 0.3f)
    }

    @Test fun aWrongTurnDoesNotBendTheRoute() {
        // The follower turns left at 15 m, where the route goes straight on.
        val aligner = RouteAligner(route)
        follow(walk(0f to -1f, 0f to -15f, -10f to -15f), { YawPose.Identity }, aligner)
        assertTrue("turn ${degrees(aligner)}", abs(degrees(aligner)) < 3)
        assertTrue("shift ${aligner.fit}", hypot(aligner.fit.x, aligner.fit.z) < 0.8f)
    }

    @Test fun standingStillChangesNothing() {
        val aligner = RouteAligner(route)
        aligner.landmark(0, TrailPoint(0f, 0f, 0f), TrailPoint(0f, 0f, 0f))
        repeat(200) { aligner.walk(TrailPoint(0.05f * sin(it * 1f), 0f, -1f), 0f) }
        assertTrue(aligner.fit == YawPose.Identity)
    }
}
