package rw.aurum.pathtracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HomographyTest {
    // A camera at the origin looking down -z, 1000 px focal length, 1920 x 1080 image.
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private fun project(p: FloatArray) = projectToImage(identity, 1000f, 1000f, 960f, 540f, p)!!
    private val up = floatArrayOf(0f, 1f, 0f)
    private val eye = floatArrayOf(0f, 0f, 0f)

    /** A 1 m sign 2 m away on a wall turned [degrees] about the vertical. */
    private fun signCorners(degrees: Double): FloatArray {
        val a = Math.toRadians(degrees)
        val normal = floatArrayOf(sin(a).toFloat(), 0f, cos(a).toFloat())
        return squareOnPlane(floatArrayOf(0f, 0f, -2f), normal, 1f, up, up, eye)!!
    }

    private fun corner(c: FloatArray, i: Int) = floatArrayOf(c[3 * i], c[3 * i + 1], c[3 * i + 2])

    @Test fun squareMapsToItsCorners() {
        val quad = floatArrayOf(10f, 20f, 300f, 40f, 280f, 260f, 30f, 220f)
        val h = Homography.squareTo(quad)!!
        val out = FloatArray(2)
        listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).forEachIndexed { i, (u, v) ->
            h.map(u, v, out)
            assertEquals(quad[2 * i], out[0], 1e-3f)
            assertEquals(quad[2 * i + 1], out[1], 1e-3f)
        }
    }

    @Test fun undoesTheViewingAngle() {
        for (degrees in listOf(0.0, 25.0, 40.0, 60.0)) {
            val c = signCorners(degrees)
            val quad = FloatArray(8)
            for (i in 0 until 4) project(corner(c, i)).copyInto(quad, 2 * i)
            val h = Homography.squareTo(quad)!!
            // Any point on the sign, e.g. a quarter across and three quarters down, lands where
            // the camera really sees it: perspective, not just a stretch.
            val onSign = FloatArray(3) { k -> c[k] + 0.25f * (c[3 + k] - c[k]) + 0.75f * (c[9 + k] - c[k]) }
            val out = FloatArray(2)
            h.map(0.25f, 0.75f, out)
            val seen = project(onSign)
            assertEquals("at $degrees", seen[0], out[0], 0.05f)
            assertEquals("at $degrees", seen[1], out[1], 0.05f)
        }
    }

    @Test fun theSquareIsUprightAndNotMirrored() {
        val c = signCorners(40.0)
        val tl = project(corner(c, 0))
        val tr = project(corner(c, 1))
        val bl = project(corner(c, 3))
        assertTrue("top-left is left of top-right", tl[0] < tr[0])
        assertTrue("top is above bottom on screen", tl[1] < bl[1])
        assertEquals("the top edge is level", c[1], c[4], 1e-5f)
        // A normal pointing away from the viewer gives the same square.
        val away = squareOnPlane(floatArrayOf(0f, 0f, -2f), floatArrayOf(-sin(0.7f), 0f, -cos(0.7f)), 1f, up, up, eye)!!
        val toward = squareOnPlane(floatArrayOf(0f, 0f, -2f), floatArrayOf(sin(0.7f), 0f, cos(0.7f)), 1f, up, up, eye)!!
        for (k in 0 until 12) assertEquals(toward[k], away[k], 1e-5f)
    }

    @Test fun aSignOnTheFloorFollowsTheScreen() {
        val screenUp = floatArrayOf(0f, 0f, -1f)
        val c = squareOnPlane(floatArrayOf(0f, -1.4f, -1f), up, 0.5f, up, screenUp, eye)!!
        // Top corners are the far ones, as on screen.
        assertTrue(c[2] < c[11])
        assertEquals(0.5f, c[3] - c[0], 1e-5f)
    }

    @Test fun pointsBehindTheCameraDontProject() {
        assertNull(projectToImage(identity, 1000f, 1000f, 960f, 540f, floatArrayOf(0f, 0f, 1f)))
    }

    @Test fun averagesASteadyLockAndRefusesAShakyOne() {
        val steady = List(6) { YawPose(1f + it * 0.002f, 0f, 2f, 0.5f + (it - 3) * 0.003f) }
        val mean = assertNotNullAndGet(steadyPose(steady, 0.02f, Math.toRadians(1.0).toFloat()))
        assertEquals(1.005f, mean.x, 1e-4f)
        assertEquals(0.4985f, mean.yaw, 1e-3f)
        val shaky = steady + YawPose(1f, 0f, 2f, 0.6f)
        assertNull(steadyPose(shaky, 0.02f, Math.toRadians(1.0).toFloat()))
        // Headings either side of +-180 degrees average across the seam, not to 0.
        val seam = listOf(YawPose(0f, 0f, 0f, PI.toFloat() - 0.005f), YawPose(0f, 0f, 0f, -PI.toFloat() + 0.005f))
        assertEquals(PI, kotlin.math.abs(assertNotNullAndGet(steadyPose(seam, 0.02f, 0.02f)).yaw).toDouble(), 1e-3)
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
