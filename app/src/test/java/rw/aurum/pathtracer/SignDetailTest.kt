package rw.aurum.pathtracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SignDetailTest {
    private val side = SignDetail.SIDE

    private fun image(seed: Int = 1, fill: Int = 180, noise: Int = 6, draw: (ByteArray, Random) -> Unit = { _, _ -> }): ByteArray {
        val rng = Random(seed)
        val g = ByteArray(side * side) { (fill + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte() }
        draw(g, rng)
        return g
    }

    private fun rect(g: ByteArray, x: Int, y: Int, w: Int, h: Int, v: Int) {
        for (j in y until minOf(y + h, side)) for (i in x until minOf(x + w, side)) g[j * side + i] = v.toByte()
    }

    /** Scattered dark blobs of different sizes, like words and a logo on a light sign. */
    private fun signLike(g: ByteArray, rng: Random) {
        repeat(120) { rect(g, rng.nextInt(side - 12), rng.nextInt(side - 12), 3 + rng.nextInt(10), 3 + rng.nextInt(10), rng.nextInt(10, 90)) }
    }

    @Test fun aPlainWallIsWeak() {
        val r = SignDetail.analyze(image(), side)
        assertEquals(SignDetail.Level.Weak, r.level)
        assertTrue("found ${r.count}", r.count < 5)
        assertFalse(r.dark)
    }

    @Test fun aSignWithTextIsGood() {
        val r = SignDetail.analyze(image(draw = ::signLike), side)
        assertEquals("count ${r.count}, cells ${r.cells}", SignDetail.Level.Good, r.level)
        assertEquals(16, r.cells)
    }

    @Test fun detailInOneCornerOnlyIsNotGood() {
        val r = SignDetail.analyze(image { g, rng -> repeat(60) { rect(g, rng.nextInt(40), rng.nextInt(40), 3 + rng.nextInt(6), 3 + rng.nextInt(6), 30) } }, side)
        assertTrue("cells ${r.cells}", r.cells <= 2)
        assertTrue(r.level != SignDetail.Level.Good)
    }

    @Test fun findsTheFourCornersOfASquare() {
        val r = SignDetail.analyze(image(noise = 0) { g, _ -> rect(g, 60, 60, 70, 70, 20) }, side)
        assertEquals(4, r.count)
        val xs = r.corners.filterIndexed { i, _ -> i % 2 == 0 }.map { (it * side).toInt() }.sorted()
        assertTrue("corners at $xs", xs.first() in 58..62 && xs.last() in 127..131)
    }

    @Test fun aDarkBoxIsDark() {
        assertTrue(SignDetail.analyze(image(fill = 15), side).dark)
    }

    @Test fun statusSaysWhatToFixFirst() {
        val good = SignDetail.Level.Good
        assertNull(captureStatus(null, dark = false, measurable = null))
        assertEquals(SignStatus("Ready. Tap the button", true), captureStatus(good, dark = false, measurable = true))
        assertFalse(captureStatus(good, dark = false, measurable = false)!!.good)
        assertTrue(captureStatus(good, dark = true, measurable = true)!!.text.startsWith("Too dark"))
        assertTrue(captureStatus(SignDetail.Level.Weak, dark = false, measurable = true)!!.text.startsWith("Too plain"))
        // Measuring is the one that stops a photo, so it's named before plainness.
        assertTrue(captureStatus(SignDetail.Level.Weak, dark = false, measurable = false)!!.text.startsWith("Can't tell how far"))
        assertNull(captureStatus(null, dark = false, measurable = true))
        // 16 cm of wall, as in the field: findable only from right in front of it.
        assertTrue(captureStatus(good, dark = false, measurable = true, span = 0.16f)!!.text.startsWith("Step back"))
        assertTrue(captureStatus(good, dark = false, measurable = true, span = 0.8f)!!.good)
    }

    @Test fun lockStatusFollowsWhatARCoreSees() {
        assertEquals("Looking for the sign…", lockStatus(ImageSeen.None, 1000).text)
        assertTrue(lockStatus(ImageSeen.None, 6000).text.startsWith("Not found yet"))
        assertTrue(lockStatus(ImageSeen.Spotted, 6000).good)
        assertEquals("Got it. Hold steady", lockStatus(ImageSeen.Tracking, 0).text)
    }
}
