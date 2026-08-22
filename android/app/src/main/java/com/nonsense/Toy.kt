package com.nonsense

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The whole simulation, with no Android in it.
 *
 * Keeping this free of android.* means it can be driven by plain JVM unit
 * tests — the geometry, the collision and the feel constants are the parts
 * most likely to be wrong, and on a device they are the parts hardest to
 * inspect. NonsenseView owns input and drawing; everything here is state and
 * arithmetic.
 *
 * Ported from desktop/renderer.html, which is the reference implementation.
 */

enum class Mode { BALL, DIAL, BUMPERS, BOLT, GLASS, PAINT }

/**
 * The app opens on its own name and a list of what it can do, rather than
 * dropping you into whichever toy you left running. Everything you can reach
 * is on that one screen, so nothing has to be discovered by accident.
 */
enum class Screen { TITLE, PLAY, PAYWALL }

/**
 * Free forever, or bought once.
 *
 * The split is meant to leave a real toy in the free tier rather than a demo:
 * the ball, the dial and the bumper table all play, in three colours, on two
 * grounds. What is bought is the studio — painting, arranging your own table,
 * and the whole palette.
 *
 * Nothing here talks to a store. [Toy] only ever reads [Toy.tier]; who sets it
 * is the platform's business, which is what lets the same gate serve Play, an
 * App Store, or a licence file.
 */
enum class Tier { FREE, FULL }

enum class Shape { CIRCLE, TRIANGLE, SQUARE, PENTAGON, HEXAGON, BAR }

/** A push that frees one shape from another: unit normal plus overlap. */
data class Hit(val nx: Float, val ny: Float, val depth: Float)

/**
 * A bumper, stored normalised — nx/ny are fractions of the field and size is a
 * fraction of its short edge — so a table built on a phone still looks right
 * on a tablet, and survives a rotation.
 */
data class Bumper(
    var nx: Float,
    var ny: Float,
    var size: Float,
    var shape: Shape,
    var rot: Float,
    /**
     * Its ink, from the same fifty-six as everything else — or
     * [Toy.FOLLOW_INK], which is the default, meaning it wears whatever ink
     * the rest of the app is holding. The tone is its own either way, so a
     * table that follows the ink is still four shades rather than one flat
     * colour.
     */
    var family: Int = Toy.FOLLOW_INK,
    var tone: Int = 2,
    /**
     * Pulled and stretched: the two axes scale apart from each other, so a
     * hexagon can be squashed into a lozenge and an O into an oval. [size] is
     * still the base, and these multiply it.
     */
    var sx: Float = 1f,
    var sy: Float = 1f,
    /**
     * A letter, or empty for one of the six outlines. A letter is not convex,
     * so it is not one polygon — it is a handful of boxes, and the ball is
     * tested against each of them. That is why bumpers have parts now.
     */
    var glyph: String = "",
)

/**
 * A struck bolt: a head travelling in a straight line, and the zigzag it has
 * left behind it.
 *
 * The zigzag is laid down as the head travels rather than generated at draw
 * time, for one reason — a bolt redrawn from fresh randomness every frame
 * shimmers like television static instead of hanging in the air. Each node is
 * displaced once, from a seed carried in the bolt, and never moves again.
 */
class Bolt(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rng: Int,
    /** The ink it was struck in. An etching keeps the colour it arrived in. */
    val argb: Int = 0,
    /** 0 is the bolt your finger threw; a fork is 1, its own fork is 2. */
    val gen: Int = 0,
) {
    val nodes = mutableListOf(floatArrayOf(x, y))
    /** 1 while it is travelling, then it counts down the glow after the hit. */
    var life = 1f
    /** Set the moment it reaches a wall: from here it is only cooling. */
    var struck = false
    var sinceNode = 0f
    /**
     * Which way the next kink throws. It has to be carried on the bolt: it
     * was read off the node count, which stops alternating the moment the
     * rolling window is full and the count stops changing. A constant side is
     * a steady curve, and a long bolt drew a smooth arc instead of a zigzag.
     */
    var side = 1f
}

/**
 * One fracture line. A crack is the same idea as a bolt's zigzag — nodes laid
 * once and never moved — because glass and lightning break the same way: a
 * line that jags as it runs and forks off what it passes.
 */
class Crack(val nodes: List<FloatArray>, val ring: Boolean, val depth: Int)

/** One press. The cracks it made, in the ink it was pressed in. */
class Break(val x: Float, val y: Float, val argb: Int, val cracks: List<Crack>)

/**
 * A bolt that has arrived. The path stays on the scene until it is cleared.
 * It keeps its generation because the bolt your finger threw is drawn a shade
 * heavier than the forks that came off it.
 */
class Etched(val nodes: List<FloatArray>, val argb: Int, val gen: Int)

/**
 * Block letters, five wide and seven tall, one bit per cell. Two hex digits
 * per row, seven rows per letter, A to Z in order — one literal, so the three
 * ports can be checked against each other by comparing a single string.
 *
 * A letter is drawn and collided as the boxes its set cells make, which is
 * what lets a concave shape work at all in a world of convex polygons: each
 * box is convex, and a letter is just several of them.
 */
object Letters {
    const val GRID_W = 5
    const val GRID_H = 7
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    const val FONT =
        "0e11111f1111111e11111e11111e0e11101010110e1e11111111111e1f10101e10101f1f10101e1010100e11101711110e1111111f1111111f04040404041f0702020202120c111214181412111010101010101f111b1515111111111915131111110e11111111110e1e11111e1010100e11111115120d1e11111e1412110f10100e01011e1f0404040404041111111111110e11111111110a0411111115151b1111110a040a111111110a040404041f01020408101f"

    private fun row(letter: Char, r: Int): Int {
        val i = ALPHABET.indexOf(letter)
        if (i < 0) return 0
        val at = (i * GRID_H + r) * 2
        return FONT.substring(at, at + 2).toInt(16)
    }

    fun on(letter: Char, col: Int, r: Int): Boolean =
        (row(letter, r) shr (GRID_W - 1 - col)) and 1 == 1

    /**
     * The boxes a letter is made of, in unit space: x and y from -1 to 1.
     * Horizontal runs, then merged downward where a run repeats — a stem
     * drawn as seven stacked boxes shows seams, and one box does not.
     */
    fun boxes(letter: Char): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val taken = Array(GRID_H) { BooleanArray(GRID_W) }
        for (r in 0 until GRID_H) {
            var c = 0
            while (c < GRID_W) {
                if (!on(letter, c, r) || taken[r][c]) { c++; continue }
                var end = c
                while (end + 1 < GRID_W && on(letter, end + 1, r) && !taken[r][end + 1]) end++
                // How far down this exact run repeats.
                var last = r
                while (last + 1 < GRID_H &&
                    (c..end).all { on(letter, it, last + 1) && !taken[last + 1][it] } &&
                    (c == 0 || !on(letter, c - 1, last + 1)) &&
                    (end == GRID_W - 1 || !on(letter, end + 1, last + 1))
                ) {
                    last++
                }
                for (rr in r..last) for (cc in c..end) taken[rr][cc] = true
                val x0 = c.toFloat() / GRID_W * 2f - 1f
                val x1 = (end + 1).toFloat() / GRID_W * 2f - 1f
                val y0 = r.toFloat() / GRID_H * 2f - 1f
                val y1 = (last + 1).toFloat() / GRID_H * 2f - 1f
                out.add(floatArrayOf(x0, y0, x1, y1))
                c = end + 1
            }
        }
        return out
    }

    /**
     * The letter's edge, as closed loops in unit space — the outside wound one
     * way and the hole in an A or an O the other, so a fill leaves the counter
     * open.
     *
     * The boxes are what a letter is hit as; this is what it is drawn as.
     * Drawing the boxes leaves a seam on every edge two of them share, and a
     * letter built of visible bricks does not read as a letter. This has no
     * internal edges at all: only the cell edges with nothing on the far side.
     */
    fun outline(letter: Char): List<Array<FloatArray>> {
        val edges = mutableListOf<IntArray>()
        for (r in 0 until GRID_H) for (c in 0 until GRID_W) {
            if (!on(letter, c, r)) continue
            if (r == 0 || !on(letter, c, r - 1)) edges.add(intArrayOf(c, r, c + 1, r))
            if (c == GRID_W - 1 || !on(letter, c + 1, r)) edges.add(intArrayOf(c + 1, r, c + 1, r + 1))
            if (r == GRID_H - 1 || !on(letter, c, r + 1)) edges.add(intArrayOf(c + 1, r + 1, c, r + 1))
            if (c == 0 || !on(letter, c - 1, r)) edges.add(intArrayOf(c, r + 1, c, r))
        }
        val used = BooleanArray(edges.size)
        val loops = mutableListOf<Array<FloatArray>>()
        for (start in edges.indices) {
            if (used[start]) continue
            val pts = mutableListOf<FloatArray>()
            var cur = start
            while (true) {
                used[cur] = true
                pts.add(unit(edges[cur][0], edges[cur][1]))
                val ex = edges[cur][2]
                val ey = edges[cur][3]
                if (ex == edges[start][0] && ey == edges[start][1]) break
                // The next edge out of this corner. Two diagonal cells meet at
                // a point and offer two; either closes a loop, and both get
                // walked before this is done.
                val next = edges.indices.firstOrNull { !used[it] && edges[it][0] == ex && edges[it][1] == ey }
                    ?: break
                cur = next
            }
            if (pts.size >= 3) loops.add(pts.toTypedArray())
        }
        return loops
    }

    private fun unit(c: Int, r: Int): FloatArray =
        floatArrayOf(c.toFloat() / GRID_W * 2f - 1f, r.toFloat() / GRID_H * 2f - 1f)
}

object Outlines {
    private fun ngon(n: Int): Array<FloatArray> = Array(n) { i ->
        val a = (-Math.PI / 2.0 + i * 2.0 * Math.PI / n).toFloat()
        floatArrayOf(cos(a), sin(a))
    }

    /** Convex outlines on a unit circumradius, so a size means one thing. */
    val UNIT: Map<Shape, Array<FloatArray>?> = mapOf(
        Shape.CIRCLE to null,
        Shape.TRIANGLE to ngon(3),
        Shape.SQUARE to arrayOf(
            floatArrayOf(-0.707f, -0.707f), floatArrayOf(0.707f, -0.707f),
            floatArrayOf(0.707f, 0.707f), floatArrayOf(-0.707f, 0.707f),
        ),
        Shape.PENTAGON to ngon(5),
        Shape.HEXAGON to ngon(6),
        Shape.BAR to arrayOf(
            floatArrayOf(-1.35f, -0.5f), floatArrayOf(1.35f, -0.5f),
            floatArrayOf(1.35f, 0.5f), floatArrayOf(-1.35f, 0.5f),
        ),
    )

    /**
     * A circle pulled out of round is an ellipse, and an ellipse is not a
     * circle to bounce off. Sixteen sides at a unit circumradius is close
     * enough that the seam between what is drawn and what is hit never shows,
     * and every side of it is still convex.
     */
    val ELLIPSE: Array<FloatArray> = ngon(16)

    /**
     * How much ground a shape actually covers, as a fraction of its
     * circumradius — its inradius. A bar is long but narrow and should not ink
     * a stripe as wide as it is long.
     */
    val COVER: Map<Shape, Float> = mapOf(
        Shape.CIRCLE to 1f, Shape.TRIANGLE to 0.5f, Shape.SQUARE to 0.707f,
        Shape.PENTAGON to 0.809f, Shape.HEXAGON to 0.866f, Shape.BAR to 0.5f,
    )

    /** World-space outline, or null for a circle. */
    fun points(shape: Shape, cx: Float, cy: Float, r: Float, rot: Float): Array<FloatArray>? {
        val u = UNIT[shape] ?: return null
        val c = cos(rot)
        val s = sin(rot)
        return Array(u.size) { i ->
            val px = u[i][0] * r
            val py = u[i][1] * r
            floatArrayOf(cx + px * c - py * s, cy + px * s + py * c)
        }
    }
}

object Geom {
    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    fun closestOnSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): FloatArray {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return floatArrayOf(ax, ay)
        val t = clamp(((px - ax) * dx + (py - ay) * dy) / len2, 0f, 1f)
        return floatArrayOf(ax + t * dx, ay + t * dy)
    }

    fun pointInPoly(px: Float, py: Float, pts: Array<FloatArray>): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i][0]; val yi = pts[i][1]
            val xj = pts[j][0]; val yj = pts[j][1]
            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi + 1e-12f) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun circleVsCircle(ax: Float, ay: Float, ar: Float, bx: Float, by: Float, br: Float): Hit? {
        val dx = ax - bx
        val dy = ay - by
        val d = hypot(dx, dy)
        if (d >= ar + br || d <= 0.001f) return null
        return Hit(dx / d, dy / d, ar + br - d)
    }

    /** Pushes the circle out of the polygon. */
    fun circleVsPoly(cx: Float, cy: Float, r: Float, pts: Array<FloatArray>): Hit? {
        var qx = 0f; var qy = 0f
        var best = Float.MAX_VALUE
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val p = closestOnSegment(cx, cy, a[0], a[1], b[0], b[1])
            val d = hypot(cx - p[0], cy - p[1])
            if (d < best) { best = d; qx = p[0]; qy = p[1] }
        }
        val inside = pointInPoly(cx, cy, pts)
        if (!inside && best >= r) return null
        if (best < 1e-6f) return null
        val s = if (inside) -1f else 1f
        return Hit(s * (cx - qx) / best, s * (cy - qy) / best, if (inside) r + best else r - best)
    }

    private fun project(pts: Array<FloatArray>, nx: Float, ny: Float): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (p in pts) {
            val d = p[0] * nx + p[1] * ny
            if (d < lo) lo = d
            if (d > hi) hi = d
        }
        return floatArrayOf(lo, hi)
    }

    private fun centroid(pts: Array<FloatArray>): FloatArray {
        var x = 0f; var y = 0f
        for (p in pts) { x += p[0]; y += p[1] }
        return floatArrayOf(x / pts.size, y / pts.size)
    }

    /** Separating axis test: the shallowest push that frees A from B. */
    fun satPolyPoly(a: Array<FloatArray>, b: Array<FloatArray>): Hit? {
        var depth = Float.MAX_VALUE
        var nx = 0f
        var ny = 0f
        for (pts in arrayOf(a, b)) {
            for (i in pts.indices) {
                val p0 = pts[i]
                val p1 = pts[(i + 1) % pts.size]
                var ex = -(p1[1] - p0[1])
                var ey = p1[0] - p0[0]
                val len = hypot(ex, ey)
                if (len < 1e-9f) continue
                ex /= len; ey /= len
                val pa = project(a, ex, ey)
                val pb = project(b, ex, ey)
                if (pa[1] < pb[0] || pb[1] < pa[0]) return null      // a gap
                val o = minOf(pa[1] - pb[0], pb[1] - pa[0])
                if (o < depth) { depth = o; nx = ex; ny = ey }
            }
        }
        val ca = centroid(a)
        val cb = centroid(b)
        if ((ca[0] - cb[0]) * nx + (ca[1] - cb[1]) * ny < 0f) { nx = -nx; ny = -ny }
        return Hit(nx, ny, depth)
    }
}

/**
 * Nine families, four tones each. The middle tone of every family is the hand
 * picked colour and the rest are mixed toward white or black from it, so a
 * family holds its hue and nothing turns garish.
 */
object Palette {
    /**
     * Fourteen families. The first nine are the originals and keep their
     * places, because a saved ink is an index — appending is the only change
     * that does not quietly repaint somebody's bumper table.
     */
    val NAMES = listOf(
        "graphite", "bone", "oxblood", "rust", "ochre", "moss", "teal", "slate", "plum",
        "cobalt", "rose", "fern", "umber", "ink",
    )
    private val BASES = intArrayOf(
        0x3a3a3c, 0xc9c0ab, 0x702929, 0x9c5b3c, 0xb08940,
        0x5c6e4a, 0x3f6b68, 0x465a78, 0x5c3f5e,
        0x2f4a86, 0xa9596b, 0x3f8248, 0x6d4c33, 0x1d2530,
    )
    val TONE_MIX = floatArrayOf(0.58f, 0.28f, 0f, -0.38f)

    private fun mixChannel(v: Int, target: Int, amount: Float): Int =
        (v + (target - v) * amount).toInt().coerceIn(0, 255)

    /** [family][tone] as opaque ARGB. */
    val COLORS: Array<IntArray> = Array(BASES.size) { f ->
        val base = BASES[f]
        val r = (base shr 16) and 0xff
        val g = (base shr 8) and 0xff
        val b = base and 0xff
        IntArray(TONE_MIX.size) { t ->
            val m = TONE_MIX[t]
            val target = if (m >= 0f) 255 else 0
            val amt = abs(m)
            val rr = mixChannel(r, target, amt)
            val gg = mixChannel(g, target, amt)
            val bb = mixChannel(b, target, amt)
            (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    val ALPHAS = floatArrayOf(0.15f, 0.3f, 0.5f, 0.75f, 1f)
    val SCRIMS = floatArrayOf(0f, 0.06f, 0.12f, 0.18f, 0.25f, 0.34f)

    /**
     * What the toy sits on. The first is no canvas at all — the window stays
     * translucent and your home screen shows through, which is the whole
     * reason the Android build exists. The rest are solid grounds, for a
     * phone that cannot float an app over its home screen, or for when you
     * simply want a colour to draw on.
     */
    val CANVAS_NAMES = listOf("sheer", "paper", "linen", "sage", "slate", "ink", "black")
    val CANVAS_COLORS = intArrayOf(
        0,                              // sheer: never painted
        0xfff4f1ea.toInt(),             // paper
        0xffe2d9c6.toInt(),             // linen
        0xffb9c0ab.toInt(),             // sage
        0xff59636d.toInt(),             // slate
        0xff23262b.toInt(),             // ink
        0xff0b0c0e.toInt(),             // black
    )

    val HAPTIC_NAMES = listOf("off", "soft", "firm")
    val HAPTIC_SCALES = floatArrayOf(0f, 0.55f, 1f)
}

class Toy {

    companion object {
        /** Eight steps, a bead through to a grapefruit. */
        val SIZES = floatArrayOf(0.12f, 0.2f, 0.3f, 0.5f, 0.7f, 1.0f, 1.45f, 2.1f)
        const val DEFAULT_SIZE = 5                 // 1.0
        const val KICK = 1.06f
        const val MAX_SPEED = 6000f
        /** How far the two axes can be pulled apart from each other. */
        /**
         * How head-on a hit on a round surface has to be before it comes off
         * it. Below this, the ball keeps the speed it had along the surface
         * and only loses what it had into it — so it follows the curve round
         * instead of ricocheting off it, and a ball sent past a round bumper
         * at a shallow angle wraps rather than kicks.
         *
         * Measured as |v·n| over |v|: zero is a graze, one is dead-on. At 0.55
         * a hit inside about 33 degrees of the surface follows it, and
         * anything squarer bounces the way it always did.
         */
        /**
         * A bumper's family when it has not been given one of its own: it
         * wears whatever the ink is. Everything else in the app — the ball,
         * the paint, the lightning, the glass — is drawn in the current ink
         * already, and a table that stayed its own five colours whatever you
         * picked was the one thing standing outside the palette.
         *
         * A bumper you have deliberately coloured keeps that colour. This is
         * the default, not the rule.
         */
        const val FOLLOW_INK = -1

        const val CURVE_BITE = 0.55f

        const val MIN_STRETCH = 0.25f
        const val MAX_STRETCH = 4f

        const val MIN_BUMPER = 0.018f
        const val MAX_BUMPER = 0.30f

        /** Graphite, bone and oxblood — a warm, a cool and a neutral. */
        const val FREE_FAMILIES = 3

        /** Sheer and paper: the one that is the point, and one to draw on. */
        const val FREE_CANVASES = 2

        /**
         * The ground the app opens on. Slate rather than sheer: the see-through
         * window is what the Android build is for, but it is not what most of
         * the toys look best on, and a ground you can see is a better first
         * impression than one you cannot. It is free whatever tier you are on,
         * because a default nobody can use is not a default.
         */
        const val DEFAULT_CANVAS = 4

        /**
         * Fast enough to be a real spin, slow enough that the ribs stay ribs.
         * At 18 ribs this is 40 rib-passes a second, comfortably under the
         * 60Hz the screen redraws at — push past that and the knurl stops
         * turning and starts strobing backwards.
         */
        const val MAX_DIAL_OMEGA = 14f
        const val DIAL_RIBS = 18

        /** How far back a flick is measured, in seconds. */
        const val DIAL_WINDOW = 0.12f

        /** How solid a bumper is, so a painting still shows faintly under it. */
        const val BUMPER_ALPHA = 0.72f

        // ---- lightning ---------------------------------------------------

        /** Below this a flick is a nudge, not a strike. */
        const val BOLT_MIN_SPEED = 420f

        /** A bolt leaves faster than your finger did. */
        const val BOLT_SPEED = 2.0f
        const val BOLT_MAX_SPEED = 9000f

        /**
         * A bolt ends at the wall, not at a stopwatch. This is the failsafe
         * for one that somehow never gets there — a branch thrown along the
         * edge, a field resized under it — so it is generous.
         */
        const val BOLT_LIFE = 1.6f

        /** How long a struck bolt stays hot before it is only an etching. */
        const val BOLT_GLOW = 0.42f

        /** Live bolts, branches included. One flick can make several. */
        const val MAX_BOLTS = 40

        /** Chance a kink throws a fork, and how far off the fork leaves. */
        const val BOLT_BRANCH = 0.19f
        const val BOLT_BRANCH_SPREAD = 0.62f

        /** A fork is slower than what threw it, and can fork twice more. */
        const val BOLT_BRANCH_SPEED = 0.7f
        const val BOLT_MAX_GEN = 3

        /**
         * A strike fans out rather than leaving as one line. This is not
         * decoration: a single line thrown from near an edge met a wall in a
         * tenth of the screen and died there, so lightning only ever looked
         * like lightning when it was thrown from the middle. A fan means some
         * arm always has room to run, wherever your finger was.
         */
        const val BOLT_ARC = 1.15f

        /**
         * How many arms. The harder you flick the more of them there are,
         * which is also where the extra knocks come from: every arm that
         * reaches a wall is its own impact, so a hard throw is felt as a
         * volley rather than a single tap.
         */
        const val BOLT_ARMS_MIN = 3
        const val BOLT_ARMS_MAX = 9

        /** Flick speed at which the fan is at its widest count. */
        const val BOLT_ARMS_FULL = 5200f

        /**
         * How far a strike leans away from a wall it is thrown at. Without
         * this the fan had to be almost a half-disc to give an edge flick
         * anywhere to go, and a half-disc reads as a starburst rather than as
         * lightning. Leaning keeps the strike pointed where you threw it and
         * still gives it room to run.
         */
        const val BOLT_LEAN = 0.8f
        const val BOLT_LEAN_REACH = 0.42f

        /** Etchings kept. A fan lands many more paths than one line did. */
        const val MAX_ETCHED = 220

        // ---- glass -------------------------------------------------------

        /**
         * Radial cracks per press. Real glass throws a handful of long
         * fractures from the point of impact and a few rings around it; this
         * is the handful.
         */
        const val GLASS_RADIALS_MIN = 7
        const val GLASS_RADIALS_MAX = 13

        /** Rings around the impact, at growing radii. */
        const val GLASS_RINGS_MIN = 2
        const val GLASS_RINGS_MAX = 4

        /** Node spacing along a crack, as a fraction of the short edge. */
        const val GLASS_STEP = 0.05f

        /**
         * How far a crack wanders, as a fraction of that spacing. Lower than
         * lightning's: a fracture runs nearly straight, and the difference
         * between nearly and exactly is the whole look of it.
         */
        const val GLASS_JAG = 0.34f

        /** How far out the first ring sits, and how much further each one. */
        const val GLASS_RING_FIRST = 0.1f
        const val GLASS_RING_STEP = 0.13f

        /** Points around a ring. Enough to read as a loop, not as a circle. */
        const val GLASS_RING_POINTS = 15

        /** Presses kept before the oldest pane is swept up. */
        const val MAX_BREAKS = 14

        val PAYWALL_LABELS = listOf("subscribe", "restore", "not now")

        /**
         * What the button says before the store has answered. The real price
         * is localised and comes from Play or StoreKit; this is only what
         * stands in on a cold start or with no network.
         */
        const val PRICE_FALLBACK = "$1.99"

        /** The row under the buttons that opens the keypad. */
        const val CODE_PROMPT = "have a code?"

        /**
         * The unlock code, stored as a hash rather than as itself, so reading
         * this file does not hand it over. That is obfuscation and not
         * security: four digits is ten thousand guesses, the gate is a client
         * -side boolean either way, and the source is public. It exists so a
         * code can be handed to a tester or a friend without handing over the
         * app, which is all it is for.
         */
        const val CODE_HASH = 553159795
        const val CODE_LENGTH = 4

        /** FNV-1a over a salted string. Identical in all three ports. */
        fun codeHash(entry: String): Int {
            var h = -2128831035
            for (c in "nonsense/$entry") {
                h = h xor c.code
                h *= 16777619
            }
            return h
        }

        /** How cool an etching sits under a live strike. */
        const val ETCH_ALPHA = 0.62f

        /**
         * A live strike is white-hot; a cooled etching keeps its hue, because
         * on this toy the colour is the point and a near-white core washes it
         * out. These are how far each is mixed toward white.
         */
        const val BOLT_CORE_HOT = 0.9f
        const val BOLT_CORE_COOL = 0.26f

        /**
         * A fork is drawn lighter than what threw it, so the spread reads as
         * a spread. Three generations in, an arm is a quarter of the weight
         * of the trunk, which is what "thins as it fragments" means here.
         */
        fun boltWeight(gen: Int): Float = 1f - 0.25f * gen

        /** The shortest signed way round from a to b. */
        fun angleDelta(a: Float, b: Float): Float {
            var d = (b - a) % (2f * PI_F)
            if (d > PI_F) d -= 2f * PI_F
            if (d < -PI_F) d += 2f * PI_F
            return d
        }

        const val PI_F = 3.1415927f

        /** How many arms a flick of this speed throws. */
        fun boltArms(flick: Float): Int {
            val t = Geom.clamp(
                (flick - BOLT_MIN_SPEED) / maxOf(BOLT_ARMS_FULL - BOLT_MIN_SPEED, 1f), 0f, 1f)
            return BOLT_ARMS_MIN + ((BOLT_ARMS_MAX - BOLT_ARMS_MIN) * t).toInt()
        }

        /** Spacing of the zigzag's kinks, as a fraction of the short edge. */
        const val BOLT_NODE = 0.045f

        /** How far each kink throws sideways, as a fraction of that spacing. */
        const val BOLT_JAG = 0.9f

        /**
         * A bolt now runs from your finger to the wall and stops, so its
         * whole path is the drawing and nothing rolls off the back of it.
         * This is only a ceiling: a screen diagonal at this spacing is about
         * thirty kinks, and the cap is loose enough that a bolt thrown along
         * the long edge of a tall phone still arrives intact.
         */
        const val BOLT_MAX_NODES = 96

        /**
         * A plain linear congruential step. Deterministic and identical in
         * Kotlin, Swift and JavaScript, which matters because the zigzag is
         * part of the simulation rather than part of the drawing.
         */
        fun nextRand(s: Int): Int = s * 1664525 + 1013904223

        /** -1 to 1 from a seed. */
        fun rand01(s: Int): Float = ((s ushr 9) and 0xffff) / 65535f
        fun randUnit(s: Int): Float = rand01(s) * 2f - 1f

        /**
         * The table out of the box, in the ink you are holding rather than in
         * five colours of its own. The tones are spread so the pieces still
         * read as separate things: one family, four shades of it.
         */
        fun defaultTable(): MutableList<Bumper> = mutableListOf(
            Bumper(0.25f, 0.30f, 0.055f, Shape.CIRCLE, 0f, tone = 1),
            Bumper(0.75f, 0.30f, 0.055f, Shape.CIRCLE, 0f, tone = 3),
            Bumper(0.50f, 0.50f, 0.068f, Shape.HEXAGON, 0f, tone = 2),
            Bumper(0.25f, 0.72f, 0.055f, Shape.BAR, 0f, tone = 0),
            Bumper(0.75f, 0.72f, 0.055f, Shape.BAR, 0f, tone = 3),
        )
    }

    /** The play field. */
    var w = 0f
    var h = 0f

    /** The whole view, and the system navigation bar at the foot of it. */
    var viewH = 0f
    var insetBottom = 0f

    var mode = Mode.BALL
    var screen = Screen.TITLE

    var tier = Tier.FREE
    fun full(): Boolean = tier == Tier.FULL

    /** What the paywall goes back to when it is dismissed. */
    private var paywallFrom = Screen.TITLE

    /** Set by the platform once it knows what the unlock costs. */
    var priceText: String? = null

    // ---- what costs money ------------------------------------------------

    /**
     * The ball is the free toy. Everything else — the dial, the table,
     * lightning, paint — is the unlock.
     */
    fun modeLocked(m: Mode): Boolean = !full() && m != Mode.BALL
    fun editLocked(): Boolean = !full()
    fun familyLocked(i: Int): Boolean = !full() && i >= FREE_FAMILIES
    fun canvasLocked(i: Int): Boolean =
        !full() && i >= FREE_CANVASES && i != DEFAULT_CANVAS

    /** Anything locked sends you here rather than doing nothing at all. */
    fun showPaywall() {
        if (screen == Screen.PAYWALL) return
        paywallFrom = screen
        screen = Screen.PAYWALL
    }

    fun dismissPaywall() {
        screen = paywallFrom
    }

    /** Applied the moment a purchase lands, so the UI can just redraw. */
    fun unlock() {
        tier = Tier.FULL
        if (screen == Screen.PAYWALL) dismissPaywall()
    }

    /**
     * Pulls anything paid back into reach of the free tier. A refund is the
     * case that matters: without this, someone who was FULL keeps the black
     * canvas and the plum ink they no longer own, because those are just saved
     * indices.
     *
     * The factory bumper colours are left alone on purpose. They ship with the
     * app rather than being chosen from the palette, and a free tier whose
     * table is five identical grey shapes is a worse advertisement for the
     * paid one than a handsome table you cannot yet rearrange.
     */
    fun clampToTier() {
        if (full()) return
        if (inkFamily >= FREE_FAMILIES) inkFamily = 0
        if (canvasLocked(canvasIndex)) canvasIndex = DEFAULT_CANVAS
        if (modeLocked(mode)) mode = Mode.BALL
        if (editing) { editing = false; selected = -1 }
        closeDrawer()
    }

    // ---- ball -------------------------------------------------------------
    var bx = 0f
    var by = 0f
    var vx = 0f
    var vy = 0f
    var baseR = 0f
    var sizeIndex = DEFAULT_SIZE
    var shape = Shape.CIRCLE
    var spin = 0f
    var omega = 0f
    var dragging = false
    var mustCatch = false
    private var grabDX = 0f
    private var grabDY = 0f
    private var placed = false

    val friction = 0.55f
    val restitution = 0.82f
    val spinFriction = 0.45f

    // ---- dial -------------------------------------------------------------
    var dialAngle = 0f
    var dialOmega = 0f
    var dialR = 0f
    var dialGrab = false

    /**
     * A knurled wheel coasts. The old 0.35 shed two thirds of its speed every
     * second and was still before you had let go of it, which is why it never
     * looked like it was spinning at all.
     */
    val dialFriction = 0.78f

    val dialRibs = DIAL_RIBS

    /** Counts ribs passing the index mark, so the view can click for each. */
    var dialDetent = 0
    private var dialDetentIndex = 0
    private var dialLastAngle = 0f

    /** Recent drag samples as (turned, seconds), newest last. */
    private val dialSamples = ArrayDeque<FloatArray>()

    // ---- lightning --------------------------------------------------------
    val bolts = mutableListOf<Bolt>()

    /** Every bolt that has arrived, in the order it landed. */
    val etched = mutableListOf<Etched>()
    private var boltSeed = 0x5eed

    // ---- bumpers ----------------------------------------------------------
    var table: MutableList<Bumper> = defaultTable()
    var editing = false
    var selected = -1

    // ---- ink --------------------------------------------------------------
    var inkFamily = 0
    var inkTone = 2
    // The point of the app is that it is sheer. Defaults that read as opaque
    // hide that, so the ball starts see-through and the tint starts light.
    var inkAlphaIndex = 3      // 0.75
    var scrimIndex = 1         // 6%
    var canvasIndex = DEFAULT_CANVAS
    var paintOnBumpers = true

    /**
     * How hard the phone is allowed to answer. Off, soft, firm. This is a
     * setting rather than a constant because a vibration that reads as a
     * satisfying knock on one actuator is a wasp in a jar on another.
     */
    var hapticIndex = 2

    /** Set when a grab found nothing, so the view can mark the spot. */
    var missX = 0f
    var missY = 0f
    var missAt = 0L

    /** True on the frame the ball stops, so the view can settle its stroke. */
    var justCameToRest = false

    /** Bumped on every reflection; the view fires a haptic when it changes. */
    var bounceCount = 0
    var lastImpact = 0f

    /** True when the last impact was a wall rather than a bumper. */
    var lastImpactWall = false

    /**
     * How hard the last impact was, 0 to 1. Below the floor it is not worth
     * feeling — a ball settling against an edge should not buzz — and above
     * the ceiling it is as firm as it gets.
     */
    fun impactStrength(): Float {
        val floor = 200f
        val ceiling = 2600f
        if (lastImpact < floor) return 0f
        return ((lastImpact - floor) / (ceiling - floor)).coerceIn(0f, 1f)
    }

    fun inkColor(): Int = Palette.COLORS[inkFamily][inkTone]

    /** True while the window is left see-through and nothing is painted under. */
    fun sheer(): Boolean = canvasIndex == 0

    /** The ground colour, opaque. Meaningless while [sheer]. */
    fun canvasColor(): Int = Palette.CANVAS_COLORS[canvasIndex]

    fun inkAlpha(): Float = Palette.ALPHAS[inkAlphaIndex]
    fun scrim(): Float = Palette.SCRIMS[scrimIndex]
    fun hapticScale(): Float = Palette.HAPTIC_SCALES[hapticIndex]

    fun ballR(): Float = baseR * SIZES[sizeIndex]
    fun inkWidth(): Float = ballR() * 2f * (Outlines.COVER[shape] ?: 1f)
    fun ballPoints(): Array<FloatArray>? = Outlines.points(shape, bx, by, ballR(), spin)

    /** Paint is live in paint mode, and on the bumper table when switched on. */
    fun painting(): Boolean {
        if (editing) return false
        return mode == Mode.PAINT || (mode == Mode.BUMPERS && paintOnBumpers)
    }

    fun catching(): Boolean = mode == Mode.BALL && mustCatch && !editing

    /**
     * [newViewH] is the whole view; [newInsetBottom] is the system navigation
     * bar. The play field is what is left after the nav bar and the control
     * rows, so nothing the ball does — and nothing you have to tap — ever
     * lands under the gesture pill.
     */
    fun resize(newW: Float, newViewH: Float, newInsetBottom: Float = 0f) {
        if (newW <= 0f || newViewH <= 0f) return
        w = newW
        viewH = newViewH
        insetBottom = maxOf(0f, newInsetBottom)
        h = maxOf(1f, viewH - insetBottom - chromeH())
        // Derived sizes follow the field. Computing them once meant a view that
        // was measured at zero produced a radius of zero — an invisible ball.
        baseR = minOf(w, h) * 0.05f
        dialR = minOf(w, h) * 0.22f
        if (!placed) { bx = w / 2f; by = h / 2f; placed = true }
    }

    /**
     * The one outline a bumper has, or null for a circle. Letters have none —
     * they are several boxes — so this returns null for them too and callers
     * that care use [bumperParts].
     */
    fun bumperPoints(b: Bumper): Array<FloatArray>? {
        if (b.glyph.isNotEmpty()) return null
        val m = minOf(w, h)
        val pts = Outlines.UNIT[b.shape] ?: return null
        return stretched(pts, b, m)
    }

    /** A unit outline, stretched by the bumper's two axes, then turned. */
    private fun stretched(unit: Array<FloatArray>, b: Bumper, m: Float): Array<FloatArray> {
        val cx = b.nx * w
        val cy = b.ny * h
        val r = b.size * m
        val c = cos(b.rot)
        val si = sin(b.rot)
        return Array(unit.size) { i ->
            val x = unit[i][0] * r * b.sx
            val y = unit[i][1] * r * b.sy
            floatArrayOf(cx + x * c - y * si, cy + x * si + y * c)
        }
    }

    /**
     * Every convex piece of a bumper. One for an outline, none for a circle,
     * and a handful for a letter — a letter is not convex, and the only way
     * to bounce off one honestly is to bounce off its parts.
     */
    fun bumperParts(b: Bumper): List<Array<FloatArray>> {
        val m = minOf(w, h)
        if (b.glyph.isEmpty()) {
            bumperPoints(b)?.let { return listOf(it) }
            // A round bumper stays an exact circle until it is pulled; only
            // then is it worth trading that for sixteen flat sides.
            if (b.sx != 1f || b.sy != 1f) return listOf(stretched(Outlines.ELLIPSE, b, m))
            return emptyList()
        }
        return Letters.boxes(b.glyph[0]).map { box ->
            stretched(
                arrayOf(
                    floatArrayOf(box[0], box[1]), floatArrayOf(box[2], box[1]),
                    floatArrayOf(box[2], box[3]), floatArrayOf(box[0], box[3]),
                ),
                b, m,
            )
        }
    }

    /**
     * What a bumper is drawn as, rather than what it is hit as: one loop for
     * an outline, and for a letter its edge — not its boxes, which would show
     * a seam wherever two of them meet.
     */
    fun bumperLoops(b: Bumper): List<Array<FloatArray>> {
        if (b.glyph.isEmpty()) return bumperParts(b)
        val m = minOf(w, h)
        return Letters.outline(b.glyph[0]).map { stretched(it, b, m) }
    }

    fun bumperColor(b: Bumper): Int =
        Palette.COLORS[if (b.family == FOLLOW_INK) inkFamily else b.family][b.tone]

    fun bumperCenter(b: Bumper): FloatArray = floatArrayOf(b.nx * w, b.ny * h)
    fun bumperRadius(b: Bumper): Float = b.size * minOf(w, h)

    fun encodeTable(): String = table.joinToString(";") {
        "${it.nx},${it.ny},${it.size},${it.shape.name},${it.rot},${it.family},${it.tone}," +
            "${it.sx},${it.sy},${it.glyph}"
    }

    /**
     * Rows written before bumpers had a colour of their own have five fields
     * rather than seven. They still load, in graphite — a saved table is
     * someone's arrangement and is not worth throwing away over a new field.
     */
    fun decodeTable(raw: String): MutableList<Bumper> = raw.split(";").mapNotNull { row ->
        val f = row.split(",")
        if (f.size != 5 && f.size != 7 && f.size != 10) return@mapNotNull null
        runCatching {
            Bumper(
                Geom.clamp(f[0].toFloat(), 0f, 1f),
                Geom.clamp(f[1].toFloat(), 0f, 1f),
                Geom.clamp(f[2].toFloat(), MIN_BUMPER, MAX_BUMPER),
                Shape.valueOf(f[3]),
                f[4].toFloat(),
                // -1 is a bumper that follows the ink, and it has to survive
                // the round trip: a table saved following the ink and loaded
                // clamped to graphite would leave the palette again.
                if (f.size >= 7) f[5].toInt().coerceIn(FOLLOW_INK, Palette.NAMES.size - 1)
                else FOLLOW_INK,
                if (f.size >= 7) f[6].toInt().coerceIn(0, Palette.TONE_MIX.size - 1) else 2,
                if (f.size == 10) Geom.clamp(f[7].toFloat(), MIN_STRETCH, MAX_STRETCH) else 1f,
                if (f.size == 10) Geom.clamp(f[8].toFloat(), MIN_STRETCH, MAX_STRETCH) else 1f,
                if (f.size == 10 && f[9].length == 1 && Letters.ALPHABET.contains(f[9])) f[9] else "",
            )
        }.getOrNull()
    }.toMutableList()

    fun pointInBumper(px: Float, py: Float, b: Bumper): Boolean {
        val parts = bumperParts(b)
        if (parts.isEmpty()) {
            // A circle, and a stretched circle is an ellipse.
            val m = minOf(w, h)
            val dx = (px - b.nx * w)
            val dy = (py - b.ny * h)
            val c = cos(-b.rot)
            val si = sin(-b.rot)
            val lx = (dx * c - dy * si) / maxOf(b.size * m * b.sx, 1e-4f)
            val ly = (dx * si + dy * c) / maxOf(b.size * m * b.sy, 1e-4f)
            return lx * lx + ly * ly <= 1f
        }
        // A letter is grabbed anywhere on it, and a fat target is kinder than
        // asking someone to land on the crossbar of an H.
        if (b.glyph.isNotEmpty() &&
            hypot(px - b.nx * w, py - b.ny * h) <= bumperRadius(b) * maxOf(b.sx, b.sy)
        ) {
            return true
        }
        return parts.any { Geom.pointInPoly(px, py, it) }
    }

    // ---- catching ---------------------------------------------------------

    /**
     * Small balls get slack so a bead is not a pixel-hunt; a big one you simply
     * have to hit. Measured against the real outline, so a bar is caught along
     * the bar and missed across its narrow side.
     */
    fun catchSlack(): Float = maxOf(0f, minOf(w, h) * 0.04f - ballR() * 0.5f)

    fun withinCatch(px: Float, py: Float): Boolean {
        val slack = catchSlack()
        val pts = ballPoints() ?: return hypot(px - bx, py - by) <= ballR() + slack
        if (Geom.pointInPoly(px, py, pts)) return true
        var best = Float.MAX_VALUE
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val p = Geom.closestOnSegment(px, py, a[0], a[1], b[0], b[1])
            val d = hypot(px - p[0], py - p[1])
            if (d < best) best = d
        }
        return best <= slack
    }

    // ---- input ------------------------------------------------------------

    /** Returns false when the grab missed a ball that had to be caught. */
    fun grab(px: Float, py: Float, nowMillis: Long): Boolean {
        if (catching() && !withinCatch(px, py)) {
            missX = px; missY = py; missAt = nowMillis
            return false
        }
        // Caught, so it stays where it was caught: snapping it to the finger
        // would undo the catch.
        val caught = catching()
        grabDX = if (caught) bx - px else 0f
        grabDY = if (caught) by - py else 0f
        dragging = true
        if (!caught) { bx = px; by = py }
        vx = 0f; vy = 0f
        omega = 0f
        return true
    }

    fun drag(px: Float, py: Float) {
        if (!dragging) return
        bx = px + grabDX
        by = py + grabDY
    }

    fun release(flingX: Float, flingY: Float) {
        if (!dragging) return
        dragging = false
        vx = flingX
        vy = flingY
        omega = Geom.clamp(omega + vx / maxOf(ballR(), 1f) * 0.25f, -30f, 30f)
    }

    fun grabDial(px: Float, py: Float) {
        dialGrab = true
        dialOmega = 0f
        dialLastAngle = angleTo(px, py)
        dialSamples.clear()
    }

    /** How fast the wheel has been turning over the last [DIAL_WINDOW]. */
    private fun windowOmega(): Float {
        var turned = 0f
        var seconds = 0f
        for (s in dialSamples) { turned += s[0]; seconds += s[1] }
        return if (seconds <= 0f) 0f else turned / seconds
    }

    /**
     * [dt] is the time since the previous drag sample, in seconds.
     *
     * The speed is taken over a short window rather than from the last sample
     * alone. A finger nearly always stalls for a frame or two before it lifts,
     * so reading the final sample meant a hard flick released at almost no
     * speed — the wheel stopped the instant you let go of it, every time.
     */
    fun dragDial(px: Float, py: Float, dt: Float) {
        if (!dialGrab) return
        val a = angleTo(px, py)
        var d = a - dialLastAngle
        while (d > Math.PI) d -= (2.0 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
        dialLastAngle = a
        dialAngle += d

        dialSamples.addLast(floatArrayOf(d, maxOf(dt, 0.001f)))
        var held = 0f
        for (sample in dialSamples) held += sample[1]
        while (held > DIAL_WINDOW && dialSamples.size > 1) {
            held -= dialSamples.first()[1]
            dialSamples.removeFirst()
        }
        dialOmega = Geom.clamp(windowOmega(), -MAX_DIAL_OMEGA, MAX_DIAL_OMEGA)
        updateDetents()
    }

    /** Letting go of a moving wheel throws it, rather than handing it back. */
    fun releaseDial() {
        dialGrab = false
        dialOmega = Geom.clamp(windowOmega() * 1.4f, -MAX_DIAL_OMEGA, MAX_DIAL_OMEGA)
        dialSamples.clear()
    }

    /**
     * Every rib that crosses the index mark bumps [dialDetent] by one, whether
     * the wheel is being turned or coasting. The view clicks on the change,
     * which is what makes a drawn circle feel like a knurled wheel.
     */
    private fun updateDetents() {
        val step = (2.0 * Math.PI / dialRibs).toFloat()
        val idx = floor(dialAngle / step).toInt()
        if (idx != dialDetentIndex) {
            dialDetent += abs(idx - dialDetentIndex)
            dialDetentIndex = idx
        }
    }

    /** 0 at rest, 1 at the cap — the view lightens its click as it speeds up. */
    fun dialSpeedFraction(): Float = (abs(dialOmega) / MAX_DIAL_OMEGA).coerceIn(0f, 1f)

    fun angleTo(px: Float, py: Float): Float = atan2(py - h / 2f, px - w / 2f)

    // ---- physics ----------------------------------------------------------

    /**
     * Every reflection passes through here, so it is also where impacts are
     * counted — the view watches [bounceCount] to fire a haptic tap, which is
     * the one thing the desktop build cannot do.
     */
    private fun impartSpin(nx: Float, ny: Float, fromWall: Boolean) {
        val r = maxOf(ballR(), 1f)
        val t = vx * -ny + vy * nx            // velocity along the surface
        omega = Geom.clamp(omega + t / r * 0.5f, -30f, 30f)
        registerImpact(hypot(vx, vy), fromWall)
    }

    /**
     * The one place an impact is recorded, whatever hit what. The view watches
     * [bounceCount] and knows nothing about balls or bolts, so lightning
     * striking a wall feels like a ball striking a wall without the view
     * needing a line of new code.
     */
    private fun registerImpact(speed: Float, fromWall: Boolean) {
        bounceCount++
        lastImpact = speed
        lastImpactWall = fromWall
    }

    // ---- lightning --------------------------------------------------------

    /** Returns false when the flick was too slow to be a strike. */
    fun fireBolt(px: Float, py: Float, flingX: Float, flingY: Float): Boolean {
        val flick = hypot(flingX, flingY)
        if (flick < BOLT_MIN_SPEED) return false
        var bvx = flingX * BOLT_SPEED
        var bvy = flingY * BOLT_SPEED
        val sp = hypot(bvx, bvy)
        if (sp > BOLT_MAX_SPEED) { bvx *= BOLT_MAX_SPEED / sp; bvy *= BOLT_MAX_SPEED / sp }
        // The ink is read at the strike, not at the drawing, so an etching
        // keeps the colour it was thrown in however you change the palette
        // afterwards.
        val argb = inkColor()
        val arms = boltArms(flick)
        val aim = boltAim(px, py, bvx, bvy)
        val speed = hypot(bvx, bvy)
        for (i in 0 until arms) {
            // The first arm goes where you threw it; the rest fan out either
            // side, alternating, so the spread stays centred on the throw.
            val step = if (i == 0) 0f else ((i + 1) / 2) / ((arms - 1) / 2f + 0.001f)
            val side = if (i % 2 == 0) -1f else 1f
            val turn = side * Geom.clamp(step, 0f, 1f) * BOLT_ARC
            boltSeed = nextRand(boltSeed)
            // A little speed apart, so the arms do not all arrive at once and
            // the knocks land as a volley rather than a single thud.
            val f = 0.72f + 0.28f * rand01(boltSeed)
            val a = aim + turn
            bolts.add(
                Bolt(px, py, cos(a) * speed * f, sin(a) * speed * f, boltSeed, argb, 0),
            )
        }
        while (bolts.size > MAX_BOLTS) bolts.removeAt(0)
        return true
    }

    /** The throw direction, leaned away from whichever wall is closest. */
    fun boltAim(px: Float, py: Float, vx: Float, vy: Float): Float {
        val short = minOf(w, h)
        val near = minOf(px, w - px, py, h - py)
        val lean = Geom.clamp(1f - near / maxOf(short * BOLT_LEAN_REACH, 1f), 0f, 1f) * BOLT_LEAN
        val a0 = atan2(vy, vx)
        val inward = atan2(h / 2f - py, w / 2f - px)
        return a0 + angleDelta(a0, inward) * lean
    }

    /** A fork, leaving at an angle to whatever threw it. */
    private fun branch(b: Bolt) {
        if (b.gen >= BOLT_MAX_GEN || bolts.size >= MAX_BOLTS) return
        b.rng = nextRand(b.rng)
        // Off to the side the last kink threw, and never by a little: a
        // uniform turn puts most forks within a few degrees of their parent,
        // which draws parallel streaks rather than a tree.
        val turn = b.side * (0.45f + 0.55f * abs(randUnit(b.rng))) * BOLT_BRANCH_SPREAD
        val c = cos(turn)
        val si = sin(turn)
        val f = BOLT_BRANCH_SPEED
        val fork = Bolt(
            b.x, b.y,
            (b.vx * c - b.vy * si) * f,
            (b.vx * si + b.vy * c) * f,
            nextRand(b.rng), b.argb, b.gen + 1,
        )
        bolts.add(fork)
    }

    /** Everything that has arrived, wiped. Two-finger tap, or C, or CLEAR. */
    fun clearEtched() { etched.clear() }

    // ---- glass -----------------------------------------------------------

    /** Every pane broken so far, in the order it was pressed. */
    val breaks = mutableListOf<Break>()

    private var glassSeed = 0x91a5

    fun clearGlass() { breaks.clear() }

    /**
     * One crack, walked outward from [x0],[y0] along [angle] until it leaves
     * the field. Jagged the way a bolt is jagged — alternating side, random
     * magnitude — because a fracture that wanders randomly is a scribble and
     * one that does not wander at all is a ruler line.
     */
    private fun crackOut(x0: Float, y0: Float, angle: Float, depth: Int): Crack {
        val step = maxOf(minOf(w, h) * GLASS_STEP, 1f)
        val nodes = mutableListOf(floatArrayOf(x0, y0))
        var x = x0
        var y = y0
        var side = 1f
        val dx = cos(angle)
        val dy = sin(angle)
        // The field's own diagonal is the most a straight run can need.
        val limit = (hypot(w, h) / step).toInt() + 2
        for (i in 0 until limit) {
            x += dx * step
            y += dy * step
            if (x < 0f || x > w || y < 0f || y > h) {
                // Stop on the frame rather than beyond it: a crack ends at
                // the edge of the pane, which is where the pane ends.
                nodes.add(floatArrayOf(Geom.clamp(x, 0f, w), Geom.clamp(y, 0f, h)))
                break
            }
            glassSeed = nextRand(glassSeed)
            side = -side
            val jag = side * (0.4f + 0.6f * abs(randUnit(glassSeed))) * step * GLASS_JAG
            nodes.add(
                floatArrayOf(
                    Geom.clamp(x + -dy * jag, 0f, w),
                    Geom.clamp(y + dx * jag, 0f, h),
                ),
            )
        }
        return Crack(nodes, ring = false, depth = depth)
    }

    /** A ring around the impact, closed, and clipped to the pane. */
    private fun crackRing(x0: Float, y0: Float, r: Float, depth: Int): Crack {
        val nodes = mutableListOf<FloatArray>()
        for (i in 0..GLASS_RING_POINTS) {
            val a = i.toFloat() / GLASS_RING_POINTS * 2f * PI_F
            glassSeed = nextRand(glassSeed)
            val rr = r * (0.86f + 0.28f * rand01(glassSeed))
            nodes.add(
                floatArrayOf(
                    Geom.clamp(x0 + cos(a) * rr, 0f, w),
                    Geom.clamp(y0 + sin(a) * rr, 0f, h),
                ),
            )
        }
        return Crack(nodes, ring = true, depth = depth)
    }

    /**
     * Break the pane at a point. Returns false only if the field has no size
     * yet, so a press before the first layout does not make a break at the
     * origin that nobody asked for.
     */
    fun breakGlass(px: Float, py: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        val short = minOf(w, h)
        glassSeed = nextRand(glassSeed)
        val radials = GLASS_RADIALS_MIN +
            ((GLASS_RADIALS_MAX - GLASS_RADIALS_MIN) * rand01(glassSeed)).toInt()
        glassSeed = nextRand(glassSeed)
        val rings = GLASS_RINGS_MIN +
            ((GLASS_RINGS_MAX - GLASS_RINGS_MIN) * rand01(glassSeed)).toInt()

        val cracks = mutableListOf<Crack>()
        val start = randUnit(glassSeed) * PI_F
        for (i in 0 until radials) {
            glassSeed = nextRand(glassSeed)
            // Evenly spaced and then nudged: perfectly even spokes read as a
            // wheel rather than as a break.
            val a = start + i.toFloat() / radials * 2f * PI_F +
                randUnit(glassSeed) * (PI_F / radials) * 0.55f
            cracks.add(crackOut(px, py, a, i % 3))
        }
        for (k in 0 until rings) {
            cracks.add(crackRing(px, py, short * (GLASS_RING_FIRST + GLASS_RING_STEP * k), k))
        }
        breaks.add(Break(px, py, inkColor(), cracks))
        while (breaks.size > MAX_BREAKS) breaks.removeAt(0)
        // The pane going is a hard, flat knock, and the view already knows how
        // to feel one of those.
        registerImpact(2600f, true)
        return true
    }

    private fun etch(b: Bolt) {
        if (b.nodes.size < 2) return
        etched.add(Etched(b.nodes.toList(), b.argb, b.gen))
        while (etched.size > MAX_ETCHED) etched.removeAt(0)
    }

    /** A kink in the zigzag: at a wall it is exact, in flight it wanders. */
    private fun addNode(b: Bolt, node: Float, exact: Boolean) {
        while (b.nodes.size >= BOLT_MAX_NODES) b.nodes.removeAt(0)
        if (exact) {
            b.nodes.add(floatArrayOf(b.x, b.y))
        } else {
            b.rng = nextRand(b.rng)
            // Alternating, not random. A displacement with a random sign is a
            // random walk: it drifts, and it draws a wobbling rope. Lightning
            // throws to one side and then the other, and only the size of the
            // throw varies.
            b.side = -b.side
            val jag = b.side * (0.45f + 0.55f * abs(randUnit(b.rng))) * node * BOLT_JAG
            val sp = maxOf(hypot(b.vx, b.vy), 1f)
            // Clamped, because the head reflecting off a wall is not the whole
            // story: a kink displaced sideways near an edge lands outside the
            // field, and the field stops where the controls begin.
            b.nodes.add(
                floatArrayOf(
                    Geom.clamp(b.x + -b.vy / sp * jag, 0f, w),
                    Geom.clamp(b.y + b.vx / sp * jag, 0f, h),
                ),
            )
        }
        b.sinceNode = 0f
    }

    fun stepBolts(dt: Float) {
        val node = maxOf(minOf(w, h) * BOLT_NODE, 1f)
        // Iterated by index because a fork is appended to this same list as it
        // is walked, and a fork thrown this frame should start travelling the
        // next one rather than in the middle of its parent's step.
        val live = bolts.size
        for (bi in 0 until live) {
            val b = bolts[bi]
            if (b.struck) { b.life -= dt / BOLT_GLOW; continue }
            val speed = hypot(b.vx, b.vy)
            // Substepped for the same reason the ball is: at nine thousand
            // pixels a second a bolt would cross the field between frames.
            val steps = ceil(speed * dt / maxOf(node * 0.5f, 1f)).toInt().coerceIn(1, 48)
            val hStep = dt / steps
            for (i in 0 until steps) {
                if (b.struck) break
                b.x += b.vx * hStep
                b.y += b.vy * hStep
                b.sinceNode += hypot(b.vx, b.vy) * hStep

                // The wall is the end of the journey, not a cushion. A bolt
                // that bounced was a ball with a zigzag drawn on it; this one
                // arrives, knocks, and stays where it landed.
                val atWall = b.x <= 0f || b.x >= w || b.y <= 0f || b.y >= h
                if (atWall) {
                    b.x = Geom.clamp(b.x, 0f, w)
                    b.y = Geom.clamp(b.y, 0f, h)
                    b.struck = true
                    registerImpact(speed, true)
                    addNode(b, node, exact = true)
                    etch(b)
                } else if (b.sinceNode >= node) {
                    addNode(b, node, exact = false)
                    b.rng = nextRand(b.rng)
                    if (rand01(b.rng) < BOLT_BRANCH) branch(b)
                }
            }
            if (!b.struck) {
                b.life -= dt / BOLT_LIFE
                // Out of road without ever reaching a wall: it still counts as
                // arrived, or a stray fork would simply blink out.
                if (b.life <= 0f) etch(b)
            }
        }
        bolts.removeAll { it.life <= 0f }
    }

    /** How brightly a bolt still burns. Falls away late rather than evenly. */
    fun boltAlpha(b: Bolt): Float = Geom.clamp(b.life, 0f, 1f).pow(0.55f)

    fun step(dt: Float) {
        justCameToRest = false
        if (w <= 0f || h <= 0f) return
        if (screen == Screen.TITLE) return
        if (mode == Mode.BOLT) {
            stepBolts(dt)
            return
        }
        if (mode == Mode.DIAL) {
            if (!dialGrab) {
                dialOmega *= dialFriction.pow(dt)
                if (abs(dialOmega) < 0.05f) dialOmega = 0f
                dialAngle += dialOmega * dt
                updateDetents()
            }
            return
        }

        val frozen = editing && mode == Mode.BUMPERS
        if (!dragging && !frozen) {
            val wasMoving = vx != 0f || vy != 0f
            val decay = friction.pow(dt)
            vx *= decay
            vy *= decay
            if (hypot(vx, vy) < 4f) { vx = 0f; vy = 0f }

            // Substep, so a quick flick cannot jump a wall or skip a bumper
            // between frames, and contacts are caught shallow — a deep one
            // picks its escape direction badly.
            val reach = hypot(vx, vy) * dt
            val steps = ceil(reach / maxOf(ballR() * 0.25f, 1f)).toInt().coerceIn(1, 32)
            val hStep = dt / steps
            for (i in 0 until steps) {
                spin += omega * hStep          // turn first, then resolve
                bx += vx * hStep
                by += vy * hStep
                walls()
                if (mode == Mode.BUMPERS) for (b in table) bounce(b)
            }
            if (wasMoving && vx == 0f && vy == 0f) justCameToRest = true
        } else {
            spin += omega * dt
        }

        if (dragging) omega *= 0.02f.pow(dt)
        omega *= spinFriction.pow(dt)
        if (abs(omega) < 0.02f) omega = 0f
    }

    /**
     * Walls, against the ball's real outline. Only reverse when the ball is
     * heading into the wall: a corner that rotates back into contact would
     * otherwise flip the velocity a second time and pin the ball to the edge.
     */
    fun walls() {
        val r = ballR()
        val pts = ballPoints()
        var lo0: Float; var hi0: Float; var lo1: Float; var hi1: Float
        if (pts == null) {
            lo0 = bx - r; hi0 = bx + r; lo1 = by - r; hi1 = by + r
        } else {
            lo0 = Float.MAX_VALUE; hi0 = -Float.MAX_VALUE
            lo1 = Float.MAX_VALUE; hi1 = -Float.MAX_VALUE
            for (p in pts) {
                if (p[0] < lo0) lo0 = p[0]
                if (p[0] > hi0) hi0 = p[0]
                if (p[1] < lo1) lo1 = p[1]
                if (p[1] > hi1) hi1 = p[1]
            }
        }
        if (lo0 < 0f) { bx -= lo0; if (vx < 0f) { vx = -vx * restitution; impartSpin(1f, 0f, true) } }
        if (hi0 > w) { bx -= hi0 - w; if (vx > 0f) { vx = -vx * restitution; impartSpin(-1f, 0f, true) } }
        if (lo1 < 0f) { by -= lo1; if (vy < 0f) { vy = -vy * restitution; impartSpin(0f, 1f, true) } }
        if (hi1 > h) { by -= hi1 - h; if (vy > 0f) { vy = -vy * restitution; impartSpin(0f, -1f, true) } }
    }

    /**
     * Reflect the ball off one bumper — any convex shape against any other,
     * and a letter against any of them, because a letter is several convex
     * shapes and the deepest contact is the one that counts.
     */
    fun bounce(b: Bumper) {
        val r = ballR()
        val bp = ballPoints()
        val parts = bumperParts(b)
        val c = bumperCenter(b)
        val br = bumperRadius(b)

        var hit: Hit? = null
        if (parts.isEmpty()) {
            hit = if (bp == null) Geom.circleVsCircle(bx, by, r, c[0], c[1], br)
            else Geom.circleVsPoly(c[0], c[1], br, bp)?.let { Hit(-it.nx, -it.ny, it.depth) }
        } else {
            for (gp in parts) {
                val one = if (bp == null) Geom.circleVsPoly(bx, by, r, gp)
                else Geom.satPolyPoly(bp, gp)
                if (one != null && (hit == null || one.depth > hit!!.depth)) hit = one
            }
        }
        if (hit == null) return

        val n = hit!!
        bx += n.nx * n.depth
        by += n.ny * n.depth

        val dot = vx * n.nx + vy * n.ny
        if (dot >= 0f) return
        impartSpin(n.nx, n.ny, false)

        // A flat side is a flat side: it reflects. A round one is followed
        // when it is barely touched, and reflects when it is hit squarely.
        val curve = if (isRound(b)) grip(dot) else 1f
        val keep = 1f + (KICK - 1f) * curve       // along the surface
        val give = KICK * curve                   // into it
        val nx2 = dot * n.nx
        val ny2 = dot * n.ny
        vx = (vx - nx2) * keep - nx2 * give
        vy = (vy - ny2) * keep - ny2 * give
        val sp = hypot(vx, vy)
        if (sp > MAX_SPEED) { vx *= MAX_SPEED / sp; vy *= MAX_SPEED / sp }
    }

    /** A round bumper, and a round one pulled out of round. */
    fun isRound(b: Bumper): Boolean = b.glyph.isEmpty() && b.shape == Shape.CIRCLE

    /**
     * How much of a bounce a hit gets, from none at a graze to all of it once
     * it is square enough. Eased at both ends, because a hard edge between
     * following the curve and coming off it is a thing you can feel.
     */
    fun grip(dot: Float): Float {
        val sp = hypot(vx, vy)
        if (sp < 1e-4f) return 1f
        val t = Geom.clamp(abs(dot) / sp / CURVE_BITE, 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- the bottom strip -------------------------------------------------

    fun modeH(): Float = viewH * 0.062f
    fun stripH(): Float = viewH * 0.075f
    fun chromeH(): Float = modeH() + stripH()
    fun modeRowTop(): Float = h
    fun stripTop(): Float = h + modeH()

    /**
     * The mode row. Android has no keyboard and a hidden double tap is not a
     * feature anyone can find, so the five toys are on screen, along with the
     * palette and whichever toggle the current mode has.
     */
    fun modeLabels(): List<String> {
        val labels =
            mutableListOf("menu", "ball", "dial", "bumpers", "bolt", "glass", "paint", "ink")
        if (mode == Mode.BUMPERS) labels.add("edit")
        if (mode == Mode.BALL) labels.add("catch")
        return labels
    }

    fun modeCells(): List<Chip> {
        val labels = modeLabels()
        val cw = w / labels.size
        return labels.indices.map { Chip(it, cw * it, modeRowTop(), cw, modeH()) }
    }

    fun modeHit(x: Float, y: Float): String? {
        if (y < modeRowTop() || y > modeRowTop() + modeH()) return null
        val labels = modeLabels()
        return labels[((x / w) * labels.size).toInt().coerceIn(0, labels.size - 1)]
    }

    /** The mode a row or a menu key names, or null if it names something else. */
    fun modeNamed(key: String): Mode? = when (key) {
        "ball" -> Mode.BALL
        "dial" -> Mode.DIAL
        "bumpers" -> Mode.BUMPERS
        "bolt" -> Mode.BOLT
        "glass" -> Mode.GLASS
        "paint" -> Mode.PAINT
        else -> null
    }

    /** One gate for every way into a toy, so none of them can forget it. */
    private fun openMode(m: Mode) {
        if (modeLocked(m)) showPaywall() else { mode = m; editing = false }
    }

    fun tapMode(label: String) {
        modeNamed(label)?.let { openMode(it); return }
        when (label) {
            "menu" -> { screen = Screen.TITLE; drawerOpen = false; editing = false; dragging = false }
            "ink" -> if (drawerOpen) closeDrawer() else drawerOpen = true
            "edit" -> if (editLocked()) showPaywall()
            else { editing = !editing; selected = -1 }
            "catch" -> mustCatch = !mustCatch
        }
    }

    // ---- the opening screen ----------------------------------------------

    data class MenuItem(val key: String, val label: String, val blurb: String)

    /**
     * The front door. Every toy is named here with a line saying what it is,
     * because none of them announce themselves once you are inside — a field
     * with a ball in it looks the same whether or not it will let you paint.
     */
    fun menuItems(): List<MenuItem> {
        val items = mutableListOf(
            MenuItem("ball", "ball", "throw it and let it ring"),
            MenuItem("dial", "dial", "a knurled wheel that clicks"),
            MenuItem("bumpers", "bumpers", "a table to bounce through"),
            MenuItem("bolt", "lightning", "a strike that stays etched"),
            MenuItem("glass", "glass", "press it and it breaks"),
            MenuItem("paint", "paint", "a ball that leaves ink"),
            MenuItem("ink", "ink & canvas", "colour, sheerness, ground"),
        )
        // Named on the front door rather than sprung on you behind a control:
        // you can see what it costs before you go looking for it.
        if (!full()) items.add(MenuItem("unlock", "unlock everything", "everything but the ball"))
        return items
    }

    /** Rows the free tier can look at but not use. */
    fun menuLocked(key: String): Boolean = modeNamed(key)?.let { modeLocked(it) } ?: false

    /** Where the wordmark's baseline sits. */
    fun titleBaseline(): Float = viewH * 0.26f

    /**
     * Rows shrink to fit rather than running off the bottom. Adding lightning
     * made a seventh row, and at a fixed height seven of them overflowed a
     * 1080x1920 screen by forty pixels — the sort of thing that is invisible
     * until someone with a smaller phone cannot reach the last entry.
     */
    fun menuRowH(): Float {
        val n = menuItems().size
        val top = titleBaseline() + viewH * 0.075f
        val room = maxOf(viewH - insetBottom - top - viewH * 0.02f, 1f)
        return minOf(viewH * 0.082f, w * 0.16f, room / (n + (n - 1) * 0.18f))
    }

    fun menuRows(): List<Chip> {
        val items = menuItems()
        val rh = menuRowH()
        val gap = rh * 0.18f
        val x = w * 0.11f
        val cw = w * 0.78f
        val top = titleBaseline() + viewH * 0.075f
        return items.indices.map { i -> Chip(i, x, top + (rh + gap) * i, cw, rh) }
    }

    fun menuHit(px: Float, py: Float): String? {
        val items = menuItems()
        for (c in menuRows())
            if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h)
                return items[c.i].key
        return null
    }

    /** Returns true if the tap opened something. */
    fun tapMenu(key: String): Boolean {
        val m = modeNamed(key)
        if (m != null) {
            if (modeLocked(m)) showPaywall() else { mode = m; screen = Screen.PLAY }
            editing = false
            return true
        }
        when (key) {
            "ink" -> { screen = Screen.PLAY; drawerOpen = true }
            "unlock" -> showPaywall()
            else -> return false
        }
        editing = false
        return true
    }

    // ---- the paywall -----------------------------------------------------

    fun paywallLines(): List<String> = listOf(
        "the dial, the bumper table and lightning",
        "paint — the ball leaves ink wherever it goes",
        "arrange the table, and colour every bumper",
        "all fifty-six inks and all seven canvases",
        "no account, no ads, nothing collected",
    )

    /**
     * The terms, on the paywall itself. Not decoration: Apple's guideline
     * 3.1.2 wants the length of the subscription, what it costs per period,
     * and that it renews, on the screen that sells it — and rejects the ones
     * that leave it to the store sheet.
     */
    fun subscriptionTerms(): List<String> = listOf(
        "${priceText ?: PRICE_FALLBACK} per month, renews until cancelled",
        "cancel any time in your store account",
    )

    /** subscribe · restore · not now, stacked. */
    val paywallLabels: List<String> get() = PAYWALL_LABELS

    // ---- the code --------------------------------------------------------

    /** True while the keypad is up instead of the buttons. */
    var codeOpen = false

    /** What has been typed so far. Never longer than the code itself. */
    var codeEntry = ""

    /** Set for a moment when a wrong code is entered, so the view can say so. */
    var codeWrong = false

    fun openCode() { codeOpen = true; codeEntry = ""; codeWrong = false }
    fun closeCode() { codeOpen = false; codeEntry = ""; codeWrong = false }

    /**
     * A digit, or "del". Returns true if that was the digit that unlocked it.
     * Checked on the last digit rather than on a button, because a keypad
     * with an enter key on it is one more thing to explain.
     */
    fun typeCode(key: String): Boolean {
        codeWrong = false
        if (key == "del") {
            if (codeEntry.isNotEmpty()) codeEntry = codeEntry.dropLast(1)
            return false
        }
        if (codeEntry.length >= CODE_LENGTH) return false
        codeEntry += key
        if (codeEntry.length < CODE_LENGTH) return false
        if (codeHash(codeEntry) == CODE_HASH) {
            closeCode()
            unlock()
            return true
        }
        codeWrong = true
        codeEntry = ""
        return false
    }

    /** 1-9, then a blank, 0, and delete: a phone keypad, minus the letters. */
    fun keypadKeys(): List<String> =
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")

    fun keypadCells(): List<Chip> {
        val cw = minOf(w * 0.74f, minOf(w, viewH) * 0.9f)
        val gap = cw * 0.04f
        val bw = (cw - gap * 2f) / 3f
        val bh = minOf(bw * 0.62f, viewH * 0.082f)
        val x = (w - cw) / 2f
        val top = viewH - insetBottom - bh * 0.5f - 4f * bh - 3f * gap
        return keypadKeys().indices.map { i ->
            Chip(i, x + (bw + gap) * (i % 3), top + (bh + gap) * (i / 3), bw, bh)
        }
    }

    fun keypadHit(px: Float, py: Float): String? {
        val keys = keypadKeys()
        for (c in keypadCells())
            if (keys[c.i].isNotEmpty() &&
                px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h
            ) {
                return keys[c.i]
            }
        return null
    }

    /** Where the "have a code?" row sits, under the buttons. */
    fun codePromptCell(): Chip {
        val b = paywallButtons().first()
        val h = minOf(viewH * 0.05f, b.h * 0.7f)
        return Chip(0, b.x, b.y - h - h * 0.35f, b.w, h)
    }

    fun paywallButtons(): List<Chip> {
        val bh = minOf(viewH * 0.072f, w * 0.145f)
        val gap = bh * 0.22f
        val cw = minOf(w * 0.74f, minOf(w, viewH) * 0.9f)
        val x = (w - cw) / 2f
        val floorY = viewH - insetBottom - bh * 0.5f
        val top = floorY - paywallLabels.size * bh - (paywallLabels.size - 1) * gap
        return paywallLabels.indices.map { i -> Chip(i, x, top + (bh + gap) * i, cw, bh) }
    }

    fun paywallHit(px: Float, py: Float): String? {
        for (c in paywallButtons())
            if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h)
                return paywallLabels[c.i]
        return null
    }

    /** What the unlock button reads, once the store has said. */
    fun unlockLabel(): String = "subscribe  ·  ${priceText ?: PRICE_FALLBACK}/mo"

    fun inStrip(y: Float): Boolean = y >= stripTop() && y <= stripTop() + stripH()

    data class Zone(val kind: String, val x0: Float, val x1: Float, val count: Int)

    /**
     * Lightning and glass have no ball in them, so the sizes and the shapes
     * are controls for nothing there — but the colour is the whole point of
     * both, since a strike etches and a break exposes an edge in whatever ink
     * it was made with. They get the full width for the palette instead.
     */
    fun stripZones(): List<Zone> =
        if (mode == Mode.BOLT || mode == Mode.GLASS)
            listOf(Zone("color", 0f, w, Palette.NAMES.size))
        else listOf(
            Zone("color", 0f, w * 0.46f, Palette.NAMES.size),
            Zone("size", w * 0.46f, w * 0.73f, SIZES.size),
            Zone("shape", w * 0.73f, w, Shape.entries.size),
        )

    /** Returns true if the tap was consumed. */
    fun stripTap(x: Float): Boolean {
        for (z in stripZones()) {
            if (x < z.x0 || x > z.x1) continue
            val i = (((x - z.x0) / (z.x1 - z.x0)) * z.count).toInt().coerceIn(0, z.count - 1)
            when (z.kind) {
                "color" -> when {
                    familyLocked(i) -> showPaywall()
                    i == inkFamily -> drawerOpen = true
                    else -> inkFamily = i
                }
                "size" -> sizeIndex = i
                "shape" -> { shape = Shape.entries[i]; spin = 0f }
            }
            return true
        }
        return false
    }

    // ---- the palette drawer ----------------------------------------------

    var drawerOpen = false

    /**
     * Whose colour the grid is setting. Editing a bumper and tapping its
     * swatch points the whole palette at that bumper; everything else in the
     * drawer stays global, because translucency and canvas are not per-bumper
     * things.
     */
    enum class Target { INK, BUMPER }
    var drawerTarget = Target.INK

    fun closeDrawer() {
        drawerOpen = false
        drawerTarget = Target.INK
    }

    /**
     * What the drawer says it is painting, and which cell it lights. A bumper
     * that follows the ink lights the ink's own cell at the bumper's tone, so
     * the grid always shows the colour you are actually looking at on screen.
     */
    fun drawerHeading(): String {
        val t = targetBumper()
        return when {
            t == null -> "INK  ·  ${Palette.NAMES[inkFamily]}"
            t.family == FOLLOW_INK -> "BUMPER  ·  INK"
            else -> "BUMPER  ·  ${Palette.NAMES[t.family]}"
        }
    }

    fun drawerFamily(): Int {
        val t = targetBumper() ?: return inkFamily
        return if (t.family == FOLLOW_INK) inkFamily else t.family
    }

    fun drawerTone(): Int = targetBumper()?.tone ?: inkTone

    /** The bumper the drawer is currently painting, if any. */
    fun targetBumper(): Bumper? =
        if (drawerTarget == Target.BUMPER) table.getOrNull(selected) else null

    data class Box(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cell: Float, val gx: Float, val gy: Float, val gridW: Float, val gridH: Float,
        val ay: Float, val ky: Float, val sy: Float, val hy: Float, val rowH: Float,
    )

    /** Rows below the colour grid, in the order they are drawn. */
    val drawerRows = listOf("alpha", "canvas", "scrim", "haptic")

    fun drawerRowCount(kind: String): Int = when (kind) {
        "alpha" -> Palette.ALPHAS.size
        "canvas" -> Palette.CANVAS_NAMES.size
        "scrim" -> Palette.SCRIMS.size
        else -> Palette.HAPTIC_NAMES.size
    }

    fun drawerBox(): Box {
        val cols = Palette.NAMES.size
        val rows = Palette.TONE_MIX.size
        val bw = minOf(w * 0.94f, minOf(w, h) * 1.1f)
        val pad = minOf(w, h) * 0.035f
        val label = pad * 0.75f
        val rowH = minOf(h * 0.055f, pad * 1.6f)
        val gap = pad * 0.5f
        val cell = minOf((bw - pad * 2f) / cols, minOf(w, h) * 0.085f)
        val gridW = cell * cols
        val gridH = cell * rows
        val bh = pad + label + gridH + drawerRows.size * (gap + label + rowH) + pad
        val x = (w - bw) / 2f
        val y = Geom.clamp(h - bh - pad * 0.5f, 0f, viewH)
        val gx = x + (bw - gridW) / 2f
        val gy = y + pad + label
        val ay = gy + gridH + gap + label
        val ky = ay + rowH + gap + label
        val sy = ky + rowH + gap + label
        val hy = sy + rowH + gap + label
        return Box(x, y, bw, bh, cell, gx, gy, gridW, gridH, ay, ky, sy, hy, rowH)
    }

    fun drawerRowY(b: Box, kind: String): Float = when (kind) {
        "alpha" -> b.ay
        "canvas" -> b.ky
        "scrim" -> b.sy
        else -> b.hy
    }

    data class Chip(val i: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    fun drawerChips(y: Float, n: Int, b: Box): List<Chip> {
        val step = b.gridW / n
        val cw = minOf(step - 6f, minOf(w, h) * 0.16f)
        return (0 until n).map { i -> Chip(i, b.gx + step * i + (step - cw) / 2f, y, cw, b.rowH) }
    }

    fun drawerHit(px: Float, py: Float): String {
        val b = drawerBox()
        if (px < b.x || px > b.x + b.w || py < b.y || py > b.y + b.h) return "outside"
        if (px >= b.gx && px <= b.gx + b.gridW && py >= b.gy && py <= b.gy + b.gridH) {
            val family = ((px - b.gx) / b.cell).toInt().coerceIn(0, Palette.NAMES.size - 1)
            val tone = ((py - b.gy) / b.cell).toInt().coerceIn(0, Palette.TONE_MIX.size - 1)
            if (familyLocked(family)) { showPaywall(); return "locked" }
            targetBumper()?.let {
                // Tapping the colour it is already wearing hands it back to
                // the ink — the same repeat-tap the strip uses to open this
                // drawer, and the only way back to following without a button
                // there is nowhere to put.
                if (it.family == family && it.tone == tone) {
                    it.family = FOLLOW_INK
                    return "follow"
                }
                it.family = family
                it.tone = tone
                return "bumper"
            }
            inkFamily = family
            inkTone = tone
            return "ink"
        }
        for (kind in drawerRows) {
            for (c in drawerChips(drawerRowY(b, kind), drawerRowCount(kind), b)) {
                if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
                if (kind == "canvas" && canvasLocked(c.i)) { showPaywall(); return "locked" }
                when (kind) {
                    "alpha" -> inkAlphaIndex = c.i
                    "canvas" -> canvasIndex = c.i
                    "scrim" -> scrimIndex = c.i
                    else -> hapticIndex = c.i
                }
                return kind
            }
        }
        return "panel"
    }

    // ---- the edit toolbar -------------------------------------------------
    // Fixed cells rather than text-measured ones, so the layout is arithmetic
    // and can be tested without a Paint.

    val toolbarLabels = listOf("add", "shape", "turn", "−", "+", "ink", "del", "reset", "done")

    fun toolbarButtons(): List<Chip> {
        val n = toolbarLabels.size
        val pad = minOf(w, h) * 0.02f
        val bw = (w - pad * 2f) / n
        val bh = minOf(h * 0.06f, minOf(w, h) * 0.11f)
        return (0 until n).map { i -> Chip(i, pad + bw * i, pad, bw, bh) }
    }

    fun toolbarHit(px: Float, py: Float): String? {
        for (c in toolbarButtons())
            if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h)
                return toolbarLabels[c.i]
        return null
    }

    /**
     * The next thing along from whatever this bumper is: the six outlines,
     * then A to Z, then back to the first outline.
     */
    fun nextGlyph(b: Bumper): String {
        if (b.glyph.isEmpty()) {
            val i = Shape.entries.indexOf(b.shape) + 1
            if (i < Shape.entries.size) { b.shape = Shape.entries[i]; return "" }
            return Letters.ALPHABET.substring(0, 1)
        }
        val i = Letters.ALPHABET.indexOf(b.glyph[0]) + 1
        if (i >= Letters.ALPHABET.length) { b.shape = Shape.entries[0]; return "" }
        return Letters.ALPHABET.substring(i, i + 1)
    }

    fun doToolbar(label: String) {
        val b = table.getOrNull(selected)
        when (label) {
            "add" -> { table.add(Bumper(0.5f, 0.4f, 0.06f, Shape.CIRCLE, 0f)); selected = table.size - 1 }
            // Six outlines, then the alphabet, then round again. One button
            // rather than two, because a second one would need a name and
            // there is nowhere on a phone to put it.
            "shape" -> if (b != null) b.glyph = nextGlyph(b)
            "turn" -> if (b != null) b.rot += (Math.PI / 12.0).toFloat()
            // Cycling fourteen families one tap at a time is no way to pick a
            // colour when the whole palette already exists.
            "ink" -> if (b != null) { drawerOpen = true; drawerTarget = Target.BUMPER }
            "−" -> if (b != null) b.size = Geom.clamp(b.size * 0.88f, MIN_BUMPER, MAX_BUMPER)
            "+" -> if (b != null) b.size = Geom.clamp(b.size * 1.14f, MIN_BUMPER, MAX_BUMPER)
            // The drawer may be pointed at the bumper that is going away.
            "del" -> if (b != null) { table.removeAt(selected); selected = -1; closeDrawer() }
            "reset" -> { table = defaultTable(); selected = -1; closeDrawer() }
            "done" -> { editing = false; selected = -1; closeDrawer() }
        }
    }

    /** Handles on the selected bumper: resize, and rotate. */
    /**
     * Two handles, in the bumper's own frame so they follow it round: the
     * first pulls, the second turns. Pulling moves both axes at once — drag
     * out along the bumper's width and it widens, drag down and it heightens
     * — so stretching needs no third handle and no modifier key, neither of
     * which a phone has anywhere to put.
     */
    fun handles(b: Bumper): Array<FloatArray> {
        val c = bumperCenter(b)
        val r = bumperRadius(b)
        val co = cos(b.rot)
        val si = sin(b.rot)
        val hx = r * b.sx
        val hy = r * b.sy
        val reach = maxOf(hx, hy) + minOf(w, h) * 0.06f
        return arrayOf(
            floatArrayOf(c[0] + hx * co - hy * si, c[1] + hx * si + hy * co),
            floatArrayOf(c[0] - reach * si, c[1] + reach * co),
        )
    }

    /**
     * Pull the selected bumper to a point: the drag is taken into the
     * bumper's own frame, so the axis you pull along is the axis that grows
     * however far round the thing has been turned.
     */
    fun pullTo(b: Bumper, px: Float, py: Float) {
        val c = bumperCenter(b)
        val r = maxOf(b.size * minOf(w, h), 1e-4f)
        val co = cos(-b.rot)
        val si = sin(-b.rot)
        val dx = px - c[0]
        val dy = py - c[1]
        b.sx = Geom.clamp(abs(dx * co - dy * si) / r, MIN_STRETCH, MAX_STRETCH)
        b.sy = Geom.clamp(abs(dx * si + dy * co) / r, MIN_STRETCH, MAX_STRETCH)
    }

    /**
     * Cycles past anything locked. Landing on a paywall because you
     * double-tapped mid-fidget would be an ambush; the free tier simply has
     * one toy in its rotation, so the gesture leaves it on the ball.
     */
    fun cycleMode() {
        editing = false
        var next = mode
        repeat(Mode.entries.size) {
            next = Mode.entries[(Mode.entries.indexOf(next) + 1) % Mode.entries.size]
            if (!modeLocked(next)) { mode = next; return }
        }
    }
}
