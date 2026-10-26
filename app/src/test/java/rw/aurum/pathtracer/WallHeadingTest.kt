package rw.aurum.pathtracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class WallHeadingTest {
    private fun rad(deg: Double) = Math.toRadians(deg).toFloat()

    /** A level direction at [yaw], as [levelled] reads an image's x axis. */
    private fun along(yaw: Float) = floatArrayOf(cos(yaw), 0f, -sin(yaw))

    /** Out of a wall whose length runs along [yaw]. */
    private fun outOf(yaw: Float) = floatArrayOf(-sin(yaw), 0f, -cos(yaw))

    @Test fun aSignSeenAFewDegreesOffTakesTheWallsHeading() {
        // ARCore sees the sign turned 7 degrees and tipped back a little; the wall runs at 30.
        val wall = rad(30.0)
        val sign = rad(37.0)
        val tipped = outOf(sign).let { floatArrayOf(it[0] * 0.98f, 0.2f, it[2] * 0.98f) }
        assertEquals(wall, wallYaw(along(sign), tipped, outOf(wall))!!, 1e-4f)
    }

    @Test fun theWallsNormalCanPointEitherWay() {
        val wall = rad(-120.0)
        val back = outOf(wall).map { -it }.toFloatArray()
        assertEquals(wall, wallYaw(along(rad(-117.0)), outOf(rad(-117.0)), back)!!, 1e-4f)
    }

    @Test fun anUpsideDownSignKeepsItsOwnDirection() {
        // Its width runs the other way along the wall: the heading must too.
        val wall = rad(10.0)
        val flipped = wall + Math.PI.toFloat()
        val got = wallYaw(along(flipped), outOf(wall).map { -it }.toFloatArray(), outOf(wall))!!
        assertEquals(0f, turnBetween(flipped, got), 1e-4f)
    }

    @Test fun signsOffTheWallOrOnTheFloorAreLeftAlone() {
        val wall = rad(0.0)
        assertNull(wallYaw(along(rad(25.0)), outOf(rad(25.0)), outOf(wall)))
        assertNull(wallYaw(along(wall), floatArrayOf(0f, 1f, 0f), outOf(wall)))
    }
}
