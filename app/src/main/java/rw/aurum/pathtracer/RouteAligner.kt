package rw.aurum.pathtracer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Works out where a stretch really lies from how the follower walks it. A photo puts the stretch
 * in place, but ARCore only roughly knows which way a photo faces, and a few degrees off swings
 * the far end of a corridor meters sideways. People walk along the path, so the turn and shift
 * that best lay their walk onto the recorded path correct it, and keep correcting slow drift.
 *
 * Positions go in in the frame the photo gave (the anchor's); [fit] maps them into the stretch's
 * frame. Photos seen on the way pin the stretch where they are.
 */
class RouteAligner(private val path: Path) {
    var fit = YawPose.Identity
        private set

    /** Meters walked since [reset]. */
    var walked = 0f
        private set

    private class Sample(val p: TrailPoint, val s: Float, val walked: Float)
    private class Landmark(val seen: TrailPoint, val expected: TrailPoint, val walked: Float)

    private val samples = ArrayDeque<Sample>()
    private val landmarks = mutableMapOf<Int, Landmark>()
    private var last: TrailPoint? = null

    fun reset() {
        fit = YawPose.Identity
        walked = 0f
        samples.clear()
        landmarks.clear()
        last = null
    }

    /** Photo [id] was seen at [seen] in the anchor's frame; the route has it at [expected]. */
    fun landmark(id: Int, seen: TrailPoint, expected: TrailPoint) {
        landmarks[id] = Landmark(seen, expected, walked)
        if (walked >= MIN_WALK) refit()
    }

    /**
     * The follower stands at [p] in the anchor's frame, [s] meters along the path as far as the
     * navigator knows. Returns true when [fit] changed.
     */
    fun walk(p: TrailPoint, s: Float): Boolean {
        val prev = last
        if (prev != null && p.distanceTo(prev) < SAMPLE_METERS) return false
        if (prev != null) walked += p.distanceTo(prev)
        last = p
        samples += Sample(p, s, walked)
        if (samples.size > MAX_SAMPLES) samples.removeFirst()
        if (walked < MIN_WALK) return false
        refit()
        return true
    }

    /**
     * Gauss-Newton on (turn, x shift, z shift). Each walked point should lie on the path, but only
     * across it: along a corridor, any spot fits. Older points and photos count for less, as
     * drift since then makes them less exact. The turn is held near the photo's, and each refit
     * near the last, so a wrong turn can't drag the route along.
     */
    private fun refit() {
        val start = fit
        var th = start.yaw.toDouble()
        var tx = start.x.toDouble()
        var tz = start.z.toDouble()
        val h = DoubleArray(9)
        val g = DoubleArray(3)
        fun add(jt: Double, jx: Double, jz: Double, r: Double, w: Double) {
            val j = doubleArrayOf(jt, jx, jz)
            for (a in 0..2) {
                g[a] += w * j[a] * r
                for (b in 0..2) h[a * 3 + b] += w * j[a] * j[b]
            }
        }
        repeat(ITERATIONS) {
            h.fill(0.0)
            g.fill(0.0)
            val c = cos(th)
            val sn = sin(th)
            for (sample in samples) {
                val q = sample.p
                val mx = c * q.x + sn * q.z + tx
                val mz = -sn * q.x + c * q.z + tz
                val dx = -sn * q.x + c * q.z
                val dz = -c * q.x - sn * q.z
                val near = path.nearest(TrailPoint(mx.toFloat(), q.y, mz.toFloat()), sample.s - WINDOW, sample.s + WINDOW)
                val ex = mx - near.nearest.x
                val ez = mz - near.nearest.z
                val dist = hypot(ex, ez)
                if (dist > CUTOFF) continue
                val sigma = SIGMA_WALK + DRIFT * (walked - sample.walked)
                val w = (if (dist < HUBER) 1.0 else HUBER / dist) / (sigma * sigma)
                // The path's direction over a couple of meters; recorded points are too noisy for
                // a single segment's.
                val a = path.pointAt(near.s - CHORD)
                val b = path.pointAt(near.s + CHORD)
                val nx = -(b.z - a.z).toDouble()
                val nz = (b.x - a.x).toDouble()
                val nl = hypot(nx, nz)
                if (nl > CHORD) {
                    add((nx * dx + nz * dz) / nl, nx / nl, nz / nl, (nx * ex + nz * ez) / nl, w)
                } else {
                    // A sharp turn or the end of the path: the point itself is where to be.
                    add(dx, 1.0, 0.0, ex, w)
                    add(dz, 0.0, 1.0, ez, w)
                }
            }
            for (l in landmarks.values) {
                val q = l.seen
                val sigma = SIGMA_PHOTO + DRIFT * (walked - l.walked)
                val w = 1.0 / (sigma * sigma)
                add(-sn * q.x + c * q.z, 1.0, 0.0, c * q.x + sn * q.z + tx - l.expected.x, w)
                add(-c * q.x - sn * q.z, 0.0, 1.0, -sn * q.x + c * q.z + tz - l.expected.z, w)
            }
            add(1.0, 0.0, 0.0, th, 1.0 / (TURN_PRIOR * TURN_PRIOR))
            add(1.0, 0.0, 0.0, th - start.yaw, 1.0 / (STEP_TURN * STEP_TURN))
            add(0.0, 1.0, 0.0, tx - start.x, 1.0 / (STEP_SHIFT * STEP_SHIFT))
            add(0.0, 0.0, 1.0, tz - start.z, 1.0 / (STEP_SHIFT * STEP_SHIFT))
            val d = solve3(h, g) ?: return
            th -= d[0]
            tx -= d[1]
            tz -= d[2]
        }
        fit = YawPose(tx.toFloat(), 0f, tz.toFloat(), th.coerceIn(-MAX_TURN, MAX_TURN).toFloat())
    }

    private companion object {
        const val SAMPLE_METERS = 0.5f
        const val MAX_SAMPLES = 200
        /** Walk this far before trusting the walk over the photo. */
        const val MIN_WALK = 3f
        const val ITERATIONS = 4
        /** Look this far either side of where the navigator put a point. */
        const val WINDOW = 8f
        /** Points farther than this from the path are off it, not evidence of where it is. */
        const val CUTOFF = 3.0
        const val HUBER = 0.5
        const val CHORD = 1f
        /** How far from the recorded line people walk. */
        const val SIGMA_WALK = 0.35
        const val SIGMA_PHOTO = 0.1
        /** Drift per meter walked. */
        const val DRIFT = 0.02
        val TURN_PRIOR = Math.toRadians(20.0)
        val STEP_TURN = Math.toRadians(4.0)
        const val STEP_SHIFT = 0.5
        val MAX_TURN = Math.toRadians(30.0)
    }
}

/** Solves the 3×3 system [h]·x = [g] (row-major), or null if it is singular. */
private fun solve3(h: DoubleArray, g: DoubleArray): DoubleArray? {
    fun det(m: DoubleArray) =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    val d = det(h)
    if (abs(d) < 1e-12) return null
    return DoubleArray(3) { col ->
        val m = h.copyOf()
        for (row in 0..2) m[row * 3 + col] = g[row]
        det(m) / d
    }
}
