package rw.aurum.pathtracer

import kotlin.math.abs

/**
 * How much detail ARCore has to grab onto inside the framing box: corners (spots where the
 * brightness changes in two directions, like the ends of letters) and how evenly they cover the
 * box. A plain wall has almost none; a sign with text or pictures has many.
 */
object SignDetail {
    enum class Level { Weak, Ok, Good }

    /** [corners] holds x, y pairs from 0 to 1 across the box, strongest first. */
    class Result(val corners: FloatArray, val count: Int, val cells: Int, val brightness: Float) {
        val level: Level
            get() = when {
                count >= GOOD_CORNERS && cells >= GOOD_CELLS -> Level.Good
                count >= OK_CORNERS && cells >= OK_CELLS -> Level.Ok
                else -> Level.Weak
            }
        val dark: Boolean get() = brightness < DARK
    }

    /** Finds corners in a [side] x [side] grey image (one byte per pixel, row by row). */
    fun analyze(gray: ByteArray, side: Int): Result {
        val score = IntArray(side * side)
        var sum = 0L
        for (i in gray.indices) sum += gray[i].toInt() and 0xff
        for (y in RADIUS until side - RADIUS) {
            for (x in RADIUS until side - RADIUS) score[y * side + x] = cornerScore(gray, side, x, y)
        }

        // Keep only the strongest corner in each 3x3 neighbourhood.
        val found = mutableListOf<Int>()
        for (y in RADIUS until side - RADIUS) {
            for (x in RADIUS until side - RADIUS) {
                val s = score[y * side + x]
                if (s == 0) continue
                var best = true
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val other = score[(y + dy) * side + x + dx]
                    // Ties go to the earlier pixel so a flat run of equal scores keeps one corner.
                    if (other > s || (other == s && (dy < 0 || (dy == 0 && dx < 0)))) {
                        best = false
                        break@loop
                    }
                }
                if (best) found += y * side + x
            }
        }

        val perCell = IntArray(GRID * GRID)
        for (p in found) perCell[(p / side) * GRID / side * GRID + (p % side) * GRID / side]++
        val shown = found.sortedByDescending { score[it] }.take(MAX_SHOWN)
        val corners = FloatArray(shown.size * 2)
        shown.forEachIndexed { i, p ->
            corners[2 * i] = (p % side + 0.5f) / side
            corners[2 * i + 1] = (p / side + 0.5f) / side
        }
        return Result(corners, found.size, perCell.count { it >= 2 }, sum.toFloat() / gray.size)
    }

    /**
     * FAST-9: a pixel is a corner when 9 neighbours in a row on the circle around it are all
     * clearly brighter, or all clearly darker. The score is how much they differ, or 0.
     */
    private fun cornerScore(gray: ByteArray, side: Int, x: Int, y: Int): Int {
        val c = gray[y * side + x].toInt() and 0xff
        fun at(k: Int) = gray[(y + CIRCLE_Y[k]) * side + x + CIRCLE_X[k]].toInt() and 0xff

        // Any 9 in a row include at least 2 of the 4 compass points: a cheap early exit.
        var brighter = 0
        var darker = 0
        for (k in 0 until 16 step 4) {
            val v = at(k)
            if (v > c + THRESHOLD) brighter++ else if (v < c - THRESHOLD) darker++
        }
        if (brighter < 2 && darker < 2) return 0

        val ring = IntArray(16) { at(it) }
        if (!hasArc(ring) { it > c + THRESHOLD } && !hasArc(ring) { it < c - THRESHOLD }) return 0
        var s = 0
        for (v in ring) {
            val d = abs(v - c) - THRESHOLD
            if (d > 0) s += d
        }
        return s
    }

    private inline fun hasArc(ring: IntArray, test: (Int) -> Boolean): Boolean {
        var run = 0
        for (k in 0 until 16 + ARC - 1) {
            if (test(ring[k % 16])) {
                if (++run >= ARC) return true
            } else {
                run = 0
            }
        }
        return false
    }

    private const val RADIUS = 3
    private const val ARC = 9
    private const val THRESHOLD = 20
    private const val GRID = 4
    private const val MAX_SHOWN = 150
    private val CIRCLE_X = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val CIRCLE_Y = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)

    // First guesses at a 192 px box; the debug log records them next to ARCore's verdict on each
    // photo so they can be tuned from real signs.
    private const val GOOD_CORNERS = 60
    private const val GOOD_CELLS = 10
    private const val OK_CORNERS = 25
    private const val OK_CELLS = 6
    private const val DARK = 40f

    /** The box is sampled at this many pixels a side. */
    const val SIDE = 192
}
