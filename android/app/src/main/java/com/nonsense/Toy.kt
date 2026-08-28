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
     * Its ink, from the same fifty-six as everything else, and its own.
     *
     * Bumpers used to follow whatever ink the rest of the app was holding, so
     * picking a paint colour repainted the table with it. Being able to set a
     * bumper from the whole palette is the point; being unable to stop it
     * moving when you paint was not.
     */
    var family: Int = 0,
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
     * How much further this one may travel before it gives out, in pixels. The
     * bolt your finger threw has no limit — it runs until it hits something.
     * A fork does: it peels off, goes a little way, and dies. Without this
     * every fork ran to a wall too, and one flick left forty full-length
     * streaks, which is a scribble rather than a strike.
     */
    var reach = Float.MAX_VALUE
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
 * Block glyphs, five wide and seven tall, one bit per cell. Two hex digits
 * per row, seven rows per glyph, A to Z and then 0 to 9 in order — one
 * literal, so the three ports can be checked against each other by comparing
 * a single string.
 *
 * A letter is drawn and collided as the boxes its set cells make, which is
 * what lets a concave shape work at all in a world of convex polygons: each
 * box is convex, and a letter is just several of them.
 */
object Letters {
    /** The lattice a glyph is drawn on: five across, seven down. */
    const val GRID_W = 5
    const val GRID_H = 7

    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"

    /**
     * Everything a bumper can be cut into, in the order the font stores it.
     * Letters first because they were here first, and a saved table naming
     * one has to keep meaning it.
     */
    const val GLYPHS = ALPHABET + DIGITS

    /**
     * The glyphs, as strokes rather than as a bitmap.
     *
     * They were a five-by-seven grid of filled cells, which drew a letter as
     * a stack of blocks — legible, and nothing like a letter. This is the
     * skeleton instead: the line a pen would take. A point is one base-36
     * digit, `row * 5 + col` on the lattice above; a full stop starts a new
     * stroke and a slash starts the next glyph. One literal, so the three
     * ports can be compared by eye and by test.
     */
    const val LINES =
        "u2y.ln/u039if.iotxu/9315pvxt/u039txu/40uy.fi/40u.fi/9315pvxtjh/0u.4y.fj/13.2w.vx/3swvp/0u.4fy/0uy/u0h4y/u0y4/139txvp51/u039eif/139txvp51.my/u039eif.hy/9315agiotxvp/04.2w/0pvxt4/0w4/0vhx4/0y.4u/0h4.hw/04uy/139txvp51.9p/62w.vx/5139euy/039ig.iotxvp/x3ko/40adjtxvp/9315pvxtoif/04v/ga5139eigkpvxtoi/pvxt9315agj"

    /** The pen, as a fraction of the bumper's radius. */
    const val STROKE = 0.30f

    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Decoded once each: this runs for every letter on screen, every frame. */
    private val cache = HashMap<Char, List<Array<FloatArray>>>()

    /**
     * A glyph's strokes, in unit space: x and y from -1 to 1. Each stroke is
     * a polyline, and a glyph is one or more of them — the crossbar of an A
     * is its own stroke, and so is the second bowl of a B.
     */
    fun strokes(letter: Char): List<Array<FloatArray>> {
        cache[letter]?.let { return it }
        val i = GLYPHS.indexOf(letter)
        if (i < 0) return emptyList()
        val made = LINES.split("/")[i].split(".").map { line ->
            Array(line.length) { k ->
                val n = BASE36.indexOf(line[k])
                floatArrayOf(
                    (n % GRID_W).toFloat() / (GRID_W - 1) * 2f - 1f,
                    (n / GRID_W).toFloat() / (GRID_H - 1) * 2f - 1f,
                )
            }
        }
        cache[letter] = made
        return made
    }

    /**
     * A stroked line, as something the ball can hit: one convex quad per
     * segment, widened to the pen and run past each end by half of it so the
     * corners of a Z are filled in rather than notched.
     *
     * Convex is the whole point — the collision in this toy knows nothing
     * else, which is why a letter used to be a heap of rectangles. A pen
     * stroke decomposes the same way and looks like handwriting instead.
     */
    fun bars(lines: List<Array<FloatArray>>, half: Float): List<Array<FloatArray>> {
        val out = mutableListOf<Array<FloatArray>>()
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val b = line[i + 1]
                var dx = b[0] - a[0]
                var dy = b[1] - a[1]
                val len = kotlin.math.hypot(dx, dy)
                if (len < 1e-5f) continue
                dx /= len
                dy /= len
                val nx = -dy * half
                val ny = dx * half
                val ex = dx * half
                val ey = dy * half
                out.add(
                    arrayOf(
                        floatArrayOf(a[0] - ex + nx, a[1] - ey + ny),
                        floatArrayOf(b[0] + ex + nx, b[1] + ey + ny),
                        floatArrayOf(b[0] + ex - nx, b[1] + ey - ny),
                        floatArrayOf(a[0] - ex - nx, a[1] - ey - ny),
                    ),
                )
            }
        }
        return out
    }
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

    /**
     * What the toy sounds like. Off first, because a fidget toy that makes
     * noise the moment it is opened is a fidget toy you put down.
     */
    val VOICE_NAMES = listOf("off", "organ", "keys", "drum", "bell", "pluck")
}

/**
 * The sound side of the toy: what a hit sounds like, and the arithmetic that
 * turns that into samples.
 *
 * The core decides *what* is played and renders it; the platform only pushes
 * the buffer at a speaker. Doing the synthesis here rather than three times
 * over is what keeps the phone, the page and the desktop sounding like the
 * same instrument, and it means the waveform can be tested rather than
 * listened to.
 */
object Voices {
    const val OFF = 0
    const val ORGAN = 1
    const val KEYS = 2
    const val DRUM = 3
    const val BELL = 4
    const val PLUCK = 5

    /**
     * A minor pentatonic, in semitones. Any two notes of it played together
     * are consonant, which is the whole reason a toy that picks its pitches
     * from what the ball happens to hit does not sound like a wrong-number
     * tone. Five degrees over three octaves is range enough to tell a hit at
     * the top of the screen from one at the bottom.
     */
    val SCALE = intArrayOf(0, 3, 5, 7, 10)
    /**
     * Four, counting upward from the root — which is one more than there was.
     * It tops out where three from A3 did, so nothing that used to be
     * reachable is gone; what is new is underneath.
     */
    const val OCTAVES = 4

    /**
     * A2, and the root the scale is built on.
     *
     * It was A3, 220Hz, with three octaves counting up — so every note the
     * toy could play lived between 220Hz and about 1760Hz. Physical mass is
     * heard at 60–160Hz, and there was nothing down there at all: the single
     * largest reason a hit had no weight to it.
     */
    const val ROOT_HZ = 110f

    /** Semitones up from [ROOT_HZ] for the nth degree of the scale. */
    fun semitone(step: Int): Int {
        val n = ((step % (SCALE.size * OCTAVES)) + SCALE.size * OCTAVES) % (SCALE.size * OCTAVES)
        return SCALE[n % SCALE.size] + 12 * (n / SCALE.size)
    }

    fun hz(step: Int): Float =
        ROOT_HZ * Math.pow(2.0, semitone(step) / 12.0).toFloat()

    /**
     * The partials each voice is built from: multiples of the fundamental,
     * and how loud each one is. An organ is the odd harmonics of a pipe, keys
     * are a struck string's first two, a bell is deliberately inharmonic —
     * 2.76 and 5.40 are the tuning of a real one and are why it rings rather
     * than hums — and a pluck is a sawtooth thinned to five terms.
     */
    fun partials(voice: Int): Array<FloatArray> = when (voice) {
        // The half-multiple is a sub an octave below the fundamental: a pipe
        // and a struck string both have real energy down there, and it is the
        // cheapest body there is. The upper partials come down as it goes in,
        // so the balance moves downward rather than the note simply getting
        // louder — add the sub and leave the top alone and you get something
        // both boomy and tinny.
        ORGAN -> arrayOf(
            floatArrayOf(0.5f, 0.30f), floatArrayOf(1f, 0.55f), floatArrayOf(2f, 0.20f),
            floatArrayOf(3f, 0.09f), floatArrayOf(4f, 0.03f),
        )
        KEYS -> arrayOf(
            floatArrayOf(0.5f, 0.22f), floatArrayOf(1f, 0.70f),
            floatArrayOf(2f, 0.16f), floatArrayOf(5f, 0.03f),
        )
        DRUM -> arrayOf(floatArrayOf(1f, 0.9f))
        BELL -> arrayOf(
            floatArrayOf(1f, 0.5f), floatArrayOf(2.76f, 0.3f),
            floatArrayOf(5.40f, 0.16f), floatArrayOf(8.93f, 0.06f),
        )
        else -> arrayOf(
            floatArrayOf(1f, 0.5f), floatArrayOf(2f, 0.25f), floatArrayOf(3f, 0.16f),
            floatArrayOf(4f, 0.12f), floatArrayOf(5f, 0.1f),
        )
    }

    /** How long a note of this voice takes to die away, in seconds. */
    fun decay(voice: Int): Float = when (voice) {
        ORGAN -> 0.42f
        KEYS -> 0.55f
        DRUM -> 0.20f
        BELL -> 1.35f
        else -> 0.70f
    }

    /**
     * How much of a note is noise rather than pitch. A drum is mostly its
     * skin, glass is mostly its own shattering, and an organ is none.
     */
    fun grit(voice: Int): Float = when (voice) {
        DRUM -> 0.55f
        PLUCK -> 0.12f
        else -> 0.02f
    }

    /**
     * A drum has no pitch to speak of but it does have a thump: the head drops
     * this far in the first instants, which is what a struck skin does and
     * what stops five drum hits sounding like five beeps.
     */
    const val DRUM_DROP = 0.55f
    const val DRUM_DROP_TIME = 0.035f

    /** The attack, in seconds. Short enough to be a hit, long enough not to click. */
    const val ATTACK = 0.004f

    /** Nothing is ever louder than this, so a chord of them cannot clip. */
    /**
     * How much faster a high partial dies than the fundamental.
     *
     * Zero is the old behaviour — one envelope over every partial, which
     * holds a note's brightness constant for its whole tail and is the single
     * largest cause of "tinny". One is very dark very fast. At 0.65 the
     * fourth partial's tail is about 40% of the fundamental's, so a note is
     * bright at the strike and warm by a tenth of a second.
     *
     * Worth auditioning between 0.4 and 0.9.
     */
    const val PARTIAL_DECAY = 0.65f

    const val HEADROOM = 0.28f
}

/**
 * One sound, decided by the toy and played by the platform. [step] is a degree
 * of the pentatonic rather than a frequency, so a note is a musical choice and
 * the arithmetic that turns it into hertz lives in one place.
 */
data class Note(
    val voice: Int,
    val step: Int,
    val gain: Float,
    /** Extra noise on top of the voice's own: glass is mostly this. */
    val grit: Float = 0f,
    /** Multiplies the voice's decay: a glancing tap rings shorter. */
    val hold: Float = 1f,
    /** Seeds the noise, so the same hit sounds the same on every platform. */
    val seed: Int = 1,
)

/**
 * A note, as samples. Additive: a few partials summed under one envelope,
 * plus as much noise as the voice calls for.
 *
 * This is the whole synthesiser. It is here, in the platform-free half, for
 * the same reason the physics is: so all three builds sound identical, and so
 * "does a bell ring longer than a drum" is a question a test can answer.
 */
object Synth {
    /** Long enough for the longest voice, and not a sample longer. */
    fun samples(note: Note, rate: Int): Int =
        (rate * Voices.decay(note.voice) * note.hold * 1.05f).toInt().coerceIn(1, rate * 3)

    /**
     * Fills [out] with the note. Returns how many samples were written; the
     * rest of the buffer is left alone, so a player can hand the same scratch
     * array to every note it plays.
     */
    fun render(note: Note, rate: Int, out: FloatArray): Int {
        val n = minOf(samples(note, rate), out.size)
        if (note.voice == Voices.OFF || n <= 0) return 0
        val f0 = Voices.hz(note.step)
        val parts = Voices.partials(note.voice)
        val decay = Voices.decay(note.voice) * note.hold
        val grit = (Voices.grit(note.voice) + note.grit).coerceIn(0f, 1f)
        val gain = note.gain.coerceIn(0f, 1f) * Voices.HEADROOM
        var seed = note.seed
        val twoPi = 2.0 * Math.PI

        for (i in 0 until n) {
            val t = i.toFloat() / rate
            // A short attack so a hit is a hit and not a click. The tail is
            // per-partial now rather than one envelope over the lot.
            val env = if (t < Voices.ATTACK) t / Voices.ATTACK else 1f

            // The drum's head drops in the first instants.
            val bend = if (note.voice == Voices.DRUM)
                1f - Voices.DRUM_DROP * (1f - Math.exp((-t / Voices.DRUM_DROP_TIME).toDouble()).toFloat())
            else 1f

            var v = 0f
            for (p in parts) {
                // Higher partials die first, which is what a struck thing
                // does: bright at the strike, warm a tenth of a second later.
                // One envelope across all of them held the brightness
                // constant for the whole tail, and that is what "tinny" is.
                val d = decay / Math.pow(maxOf(p[0], 0.5f).toDouble(),
                    Voices.PARTIAL_DECAY.toDouble()).toFloat()
                v += p[1] * Math.sin(twoPi * f0 * p[0] * bend * t).toFloat() *
                    Math.exp(-4.0 * t / d).toFloat()
            }
            v *= (1f - grit)
            if (grit > 0f) {
                seed = Toy.nextRand(seed)
                // The noise keeps the plain tail: it has no partials to
                // shed, and giving it the fundamental's decay is what makes
                // a drum still sound like one.
                v += grit * Toy.randUnit(seed) * Math.exp(-4.0 * t / decay).toFloat()
            }
            out[i] = (v * env * gain).coerceIn(-1f, 1f)
        }
        return n
    }
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
        /**
         * What a bumper's family used to be able to mean: "whatever the ink
         * is". Nothing writes it any more, and it is kept only so a table
         * saved before bumpers owned their colour still loads.
         */
        const val WAS_FOLLOWING = -1

        /**
         * Beats in a knock, at the hardest. A single click at full strength is
         * only louder than a soft one — what a hard hit actually feels like is
         * several things arriving at once, so the harder it lands the more of
         * them there are.
         */
        const val BUMPS_MAX = 4

        /**
         * Milliseconds between the beats of one knock. Close enough to read as
         * a single event with texture rather than as taps you could count;
         * further apart and a hard hit turns into a stutter.
         */
        const val BUMP_GAP_MS = 17

        /**
         * How much of the first beat the last one keeps. The burst falls away
         * rather than repeating flat, which is what a thing settling does.
         */
        const val BUMP_FALLOFF = 0.45f

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
         * The ground the app opens on. Paper, not sheer and no longer slate.
         *
         * The see-through window is what the Android build is for, but it is
         * not what most of the toys look best on, and a ground you can see is
         * a better first impression than one you cannot. Slate was the first
         * answer to that and it was the wrong one: it turned the warm palette
         * grey, which is the first thing the design pass called out. Paper
         * lets the fifty-six inks read as the colours they are. Free on
         * either tier, because a default nobody can use is not a default.
         */
        const val DEFAULT_CANVAS = 1

        /**
         * Slate: the ground the app used to open on, and free because of it.
         * It stays free so that changing the default takes nothing away from
         * anyone who had already chosen it.
         */
        const val WAS_DEFAULT_CANVAS = 4

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
         * How far a first fork gets, as a fraction of the short edge, and how
         * much of that its own forks keep. This is what "fragment and thin as
         * it spreads" actually needs: the main stroke reaches the wall, and
         * everything that leaves it is a spark rather than a second stroke.
         */
        const val BOLT_REACH = 0.5f
        const val BOLT_REACH_FALL = 0.5f

        /**
         * How much of the branching chance each generation keeps. Flat, the
         * forks multiplied — three arms became forty paths, because every fork
         * forked as eagerly as the stroke that threw it.
         */
        const val BOLT_BRANCH_FALL = 0.45f

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
        /**
         * Graphite with an oxblood middle and a bone bar. All three are free
         * families, so the table a free tier opens on is the table it keeps —
         * and it is an arrangement rather than five identical grey shapes.
         */
        fun defaultTable(): MutableList<Bumper> = mutableListOf(
            Bumper(0.25f, 0.30f, 0.055f, Shape.CIRCLE, 0f, family = 0, tone = 1),
            Bumper(0.75f, 0.30f, 0.055f, Shape.CIRCLE, 0f, family = 0, tone = 3),
            Bumper(0.50f, 0.50f, 0.068f, Shape.HEXAGON, 0f, family = 2, tone = 2),
            Bumper(0.25f, 0.72f, 0.055f, Shape.BAR, 0f, family = 0, tone = 0),
            Bumper(0.75f, 0.72f, 0.055f, Shape.BAR, 0f, family = 1, tone = 2),
        )
    }

    /** The play field. */
    var w = 0f
    var h = 0f

    /** The whole view, and the system navigation bar at the foot of it. */
    var viewH = 0f
    var insetBottom = 0f

    /**
     * The status bar, the clock, the camera cutout. The app draws edge to
     * edge, so anything pinned to the top of the screen lands underneath all
     * of that unless it is told not to — which is what happened to the edit
     * toolbar: drawn eight pixels down, straight under the system icons, and
     * untappable because the system takes the touch first.
     */
    var insetTop = 0f

    var mode = Mode.BALL
    /**
     * Which build this is, printed small under the menu.
     *
     * The toy updates by someone downloading it again, and until this was
     * here there was no way to answer "did it update?" except by hunting for
     * a feature and hoping. The platform fills it in — the version name on a
     * phone, the page's own date in a browser — and an empty one prints
     * nothing rather than a placeholder.
     */
    var build: String = ""

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
    /**
     * Grounds anyone can use: the first two by index, plus slate — which was
     * free for as long as it was the ground the app opened on, and is not
     * taken back now that paper is. Moving the default should improve what a
     * free tier sees, not shrink what it may choose.
     */
    fun canvasLocked(i: Int): Boolean =
        !full() && i >= FREE_CANVASES && i != DEFAULT_CANVAS && i != WAS_DEFAULT_CANVAS

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
     * Bumpers are pulled back too. They used to follow the ink and so could
     * not hold a colour of their own, let alone a paid one; now that they can,
     * a table built while subscribed would otherwise keep showing plum to
     * somebody who has stopped paying for it.
     */
    fun clampToTier() {
        if (full()) return
        if (inkFamily >= FREE_FAMILIES) inkFamily = 0
        for (b in table) if (b.family >= FREE_FAMILIES) b.family = 0
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
    /**
     * No tint out of the box. A screen tint is there for the sheer window,
     * where the toy floats over whatever is behind it; on a solid ground it
     * only ever greys the paper down, which is half of why the app read grey
     * and generic. Anyone who wants it can find it in the drawer.
     */
    var scrimIndex = 0
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

    /**
     * How many beats the last impact should be felt as. One for anything you
     * could call a tap; a burst for a hard landing, which is what "the harder
     * you flick, the more you feel" comes down to — a flick sets the speed,
     * the speed sets the impact, and the impact sets this.
     *
     * A wall gets one beat fewer than a bumper at the same speed: it is a flat
     * thing to hit, and a bumper throws the ball back.
     */
    fun impactBumps(): Int {
        val hit = impactStrength()
        if (hit <= 0f) return 0
        val most = if (lastImpactWall) BUMPS_MAX - 1 else BUMPS_MAX
        return (1 + (hit * most).toInt()).coerceIn(1, most)
    }

    /**
     * How hard the [i]th beat of a knock is, 0 to 1. The first is the impact
     * itself; the rest fall away towards [BUMP_FALLOFF] of it.
     */
    fun bumpLevel(i: Int): Float {
        val n = impactBumps()
        if (n <= 0 || i < 0 || i >= n) return 0f
        if (n == 1) return impactStrength()
        val t = i.toFloat() / (n - 1)
        return impactStrength() * (1f - (1f - BUMP_FALLOFF) * t)
    }

    fun inkColor(): Int = Palette.COLORS[inkFamily][inkTone]

    /** True while the window is left see-through and nothing is painted under. */
    fun sheer(): Boolean = canvasIndex == 0

    /** The ground colour, opaque. Meaningless while [sheer]. */
    fun canvasColor(): Int = Palette.CANVAS_COLORS[canvasIndex]

    fun inkAlpha(): Float = Palette.ALPHAS[inkAlphaIndex]
    fun scrim(): Float = Palette.SCRIMS[scrimIndex]
    fun hapticScale(): Float = Palette.HAPTIC_SCALES[hapticIndex]

    // ---- sound ------------------------------------------------------------

    /**
     * Which voice the toy speaks in. On, out of the box.
     *
     * This started off silent, on the reasoning that a fidget toy making noise
     * the moment you open it is one you put down. That was the wrong call for
     * a feature somebody asked for: a sound you have to go to the bottom of a
     * drawer to switch on is a sound most people never learn exists. Keys is
     * the least tiring of the five to have going while you fidget, and the row
     * that turns it off is in the same place it always was.
     */
    var voiceIndex = Voices.KEYS

    /**
     * Notes the toy has decided to play, waiting for the platform to come and
     * take them. The view drains this every frame; anything it does not take
     * within a few frames is dropped rather than queued up, because a sound
     * that arrives late is worse than one that never arrives.
     */
    val notes = mutableListOf<Note>()

    /** Nothing may pile up more than this: a hail of glass is not a siren. */
    val MAX_NOTES = 8

    var noteSeed = 0x7c9d

    private fun say(step: Int, gain: Float, grit: Float = 0f, hold: Float = 1f) {
        if (voiceIndex == Voices.OFF || gain <= 0.02f) return
        if (notes.size >= MAX_NOTES) return
        noteSeed = nextRand(noteSeed)
        notes.add(Note(voiceIndex, step, gain, grit, hold, noteSeed))
    }

    /** Hands the waiting notes over and forgets them. */
    fun takeNotes(): List<Note> {
        if (notes.isEmpty()) return emptyList()
        val out = notes.toList()
        notes.clear()
        return out
    }

    /**
     * Where on the field a hit was, as a degree of the scale: up the screen is
     * up the scale, which is the mapping anyone expects without being told.
     */
    fun stepAt(px: Float, py: Float): Int {
        val range = Voices.SCALE.size * Voices.OCTAVES
        val up = 1f - Geom.clamp(py / maxOf(h, 1f), 0f, 1f)
        val across = Geom.clamp(px / maxOf(w, 1f), 0f, 1f)
        // Mostly height, with a little sideways so two hits at the same level
        // are not always the same note.
        return ((up * (range - 1)) + (across - 0.5f) * 1.5f).toInt().coerceIn(0, range - 1)
    }

    /**
     * A bumper's note: the bigger it is the lower it sounds, which is what a
     * bigger thing does, and its tone nudges it within that so two bumpers of
     * a size are not in unison.
     */
    fun bumperStep(b: Bumper): Int {
        val range = Voices.SCALE.size * Voices.OCTAVES
        val big = Geom.clamp((b.size - MIN_BUMPER) / (MAX_BUMPER - MIN_BUMPER), 0f, 1f)
        return (((1f - big) * (range - 3)) + b.tone).toInt().coerceIn(0, range - 1)
    }

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
    fun resize(
        newW: Float,
        newViewH: Float,
        newInsetBottom: Float = 0f,
        newInsetTop: Float = 0f,
    ) {
        if (newW <= 0f || newViewH <= 0f) return
        w = newW
        viewH = newViewH
        insetBottom = maxOf(0f, newInsetBottom)
        insetTop = maxOf(0f, newInsetTop)
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
        // The pen is built in world space rather than unit space so that what
        // the ball hits is exactly what was drawn, at any stretch.
        return Letters.bars(bumperStrokes(b), penHalf(b))
    }

    /**
     * What a letter is drawn as: its strokes, in world space. The skeleton
     * stretches and turns with the bumper; the pen does not, because a pen
     * pulled sideways would thin to nothing on a long bar.
     */
    fun bumperStrokes(b: Bumper): List<Array<FloatArray>> {
        if (b.glyph.isEmpty()) return emptyList()
        val m = minOf(w, h)
        return Letters.strokes(b.glyph[0]).map { stretched(it, b, m) }
    }

    /** Half the pen's width, in pixels. */
    fun penHalf(b: Bumper): Float = Letters.STROKE * 0.5f * bumperRadius(b)

    /**
     * What a bumper is drawn as, rather than what it is hit as. Outlines are
     * one closed loop; a letter has no loop at all any more — it is strokes,
     * and [bumperStrokes] is what draws it.
     */
    fun bumperLoops(b: Bumper): List<Array<FloatArray>> {
        if (b.glyph.isEmpty()) return bumperParts(b)
        return emptyList()
    }

    fun bumperColor(b: Bumper): Int =
        Palette.COLORS[b.family][b.tone]

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
                if (f.size >= 7) {
                    // A row that followed the ink is frozen at whatever the
                    // ink is now, so a saved table looks on this launch the
                    // way it looked on the last one.
                    val saved = f[5].toInt().coerceIn(WAS_FOLLOWING, Palette.NAMES.size - 1)
                    if (saved == WAS_FOLLOWING) inkFamily else saved
                } else inkFamily,
                if (f.size >= 7) f[6].toInt().coerceIn(0, Palette.TONE_MIX.size - 1) else 2,
                if (f.size == 10) Geom.clamp(f[7].toFloat(), MIN_STRETCH, MAX_STRETCH) else 1f,
                if (f.size == 10) Geom.clamp(f[8].toFloat(), MIN_STRETCH, MAX_STRETCH) else 1f,
                if (f.size == 10 && f[9].length == 1 && Letters.GLYPHS.contains(f[9])) f[9] else "",
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
    private fun registerImpact(speed: Float, fromWall: Boolean, speak: Boolean = true) {
        bounceCount++
        lastImpact = speed
        lastImpactWall = fromWall
        // A wall is a duller, shorter thing to hit than a bumper, and a hit
        // you can barely feel is one you should barely hear.
        val hit = impactStrength()
        if (speak && hit > 0f) {
            val step = if (fromWall || nextStep < 0) stepAt(bx, by) else nextStep
            say(step, 0.25f + 0.75f * hit, hold = if (fromWall) 0.55f else 1f)
        }
        nextStep = -1
    }

    /**
     * The note the next impact should use, set by whatever is about to be hit
     * and cleared as soon as it is. A bumper knows its own note; a wall does
     * not, and takes the one under the ball.
     */
    private var nextStep = -1

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
        // The throw is the thunder: low, long and mostly noise, and the harder
        // you threw it the further down it goes. The arms landing are heard
        // separately, as the knocks they already were.
        val hard = Geom.clamp(flick / BOLT_ARMS_FULL, 0f, 1f)
        say(((1f - hard) * 3f).toInt() + 1, 0.55f + 0.45f * hard, grit = 0.5f, hold = 1.6f)
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

    /** How far a fork of this generation may travel before it gives out. */
    fun boltReach(gen: Int): Float {
        if (gen <= 0) return Float.MAX_VALUE
        var r = minOf(w, h) * BOLT_REACH
        for (i in 1 until gen) r *= BOLT_REACH_FALL
        return r
    }

    /** How likely a kink of this generation is to throw a fork. */
    fun boltBranchChance(gen: Int): Float {
        var c = BOLT_BRANCH
        for (i in 0 until gen) c *= BOLT_BRANCH_FALL
        return c
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
        fork.reach = boltReach(fork.gen)
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
        // Glass is mostly its own shattering: a high note with a great deal of
        // grit on it, and the more it cracked the brighter it goes.
        say(stepAt(px, py) + radials % 4, 0.9f, grit = 0.55f, hold = 0.8f)
        while (breaks.size > MAX_BREAKS) breaks.removeAt(0)
        // The pane going is a hard, flat knock, and the view already knows how
        // to feel one of those. It is not heard as one: the shatter above is
        // the sound of it, and both at once is just mud.
        registerImpact(2600f, true, speak = false)
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
                val travelled = hypot(b.vx, b.vy) * hStep
                b.sinceNode += travelled
                b.reach -= travelled

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
                } else if (b.reach <= 0f) {
                    // Out of road. A fork does not reach a wall and does not
                    // need to: it stops where it ran out, which is what the
                    // end of a spark looks like.
                    addNode(b, node, exact = true)
                    b.struck = true
                    etch(b)
                } else if (b.sinceNode >= node) {
                    addNode(b, node, exact = false)
                    b.rng = nextRand(b.rng)
                    if (rand01(b.rng) < boltBranchChance(b.gen)) branch(b)
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
        // The bumper about to be struck names the note; the impact plays it.
        nextStep = bumperStep(b)
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

    /**
     * What the dock takes out of the play field. It is measured rather than
     * guessed so the two cannot drift: the field ends where the panel starts,
     * with a little air between them.
     *
     * The dock is shorter than the three rows it replaced, so the canvas is
     * bigger than it was — which was the point of collapsing them.
     */
    fun chromeH(): Float = viewH - insetBottom - dockBox().y + du(8f)
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
            "menu" -> { screen = Screen.TITLE; drawerOpen = false; glyphOpen = false; editing = false; dragging = false }
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

    /**
     * The design's numbers are points on a 390x844 phone. Everything laid out
     * from the handoff is written in them and scaled by whichever dimension is
     * tighter, so a small screen gets the same design smaller rather than the
     * same design cropped.
     */
    fun du(v: Float): Float = v * minOf(w / 390f, viewH / 844f)

    /** Where the wordmark's baseline sits: 78 of top padding, then its size. */
    fun titleBaseline(): Float = insetTop + du(78f) + du(30f)

    /** The 34x2 oxblood rule under the wordmark. */
    fun titleRuleY(): Float = titleBaseline() + du(20f)

    fun taglineBaseline(): Float = titleRuleY() + du(16f) + du(13f)

    fun menuTop(): Float = taglineBaseline() + du(44f)

    /**
     * Sixty is the design's row. Seven of them plus the masthead fit a phone;
     * on anything shorter they shrink rather than running off the bottom,
     * which is invisible until someone cannot reach the last entry.
     */
    fun menuRowH(): Float {
        val n = menuItems().size
        val room = maxOf(viewH - insetBottom - menuTop() - du(40f), 1f)
        return minOf(du(60f), room / n)
    }

    /**
     * Flush rows, no gap. The rule between them is the separator now: seven
     * grey cards gave every item the same heavy weight and ate the gutter,
     * and a gap here would read as cards again.
     */
    fun menuRows(): List<Chip> {
        val rh = menuRowH()
        val x = du(30f)
        val cw = w - x * 2f
        return menuItems().indices.map { i -> Chip(i, x, menuTop() + rh * i, cw, rh) }
    }

    /** The three columns of a row: numeral, name and blurb, glyph. */
    fun menuNumX(c: Chip): Float = c.x + du(26f) / 2f
    fun menuTextX(c: Chip): Float = c.x + du(26f) + du(14f)
    fun menuGlyphX(c: Chip): Float = c.x + c.w - du(26f) / 2f
    fun menuGlyphR(): Float = du(9f)

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

    // ---- the dock ---------------------------------------------------------

    /**
     * One floating panel holding three tiers, where there used to be three
     * loose rows of identical chips.
     *
     * The old row put the six toys, the palette, the menu and whichever
     * toggle the mode had all at the same size in the same grey, so nothing
     * told the eye what mattered — and the options for the five toys you were
     * not holding sat there taking up a row of their own. Tier one is the
     * tool you are choosing, tier two is what that tool can do, tier three is
     * the ink. The set in tier two is a pure function of the mode, which is
     * what removes the third row rather than shrinking it.
     */
    data class Dock(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val pad: Float, val gap: Float,
        val tileY: Float, val tileH: Float,
        val optY: Float, val optH: Float,
        val ruleY: Float, val inkY: Float, val inkH: Float,
    )

    /** The six toys, in the order the design puts them. */
    fun dockTools(): List<Mode> = Mode.entries

    /**
     * What the held tool can do. Everything that was reachable before is
     * still here — the design's lists plus this app's own catch toggle and
     * the ball's shape, which it would otherwise have quietly dropped.
     */
    fun dockOptions(): List<String> = when (mode) {
        Mode.BALL -> listOf("shape", "size −", "size +", "catch", "palette")
        Mode.DIAL -> listOf("size −", "size +", "palette")
        Mode.BUMPERS -> listOf("edit", "clear", "palette")
        Mode.BOLT -> listOf("clear", "palette")
        Mode.GLASS -> listOf("clear", "palette")
        Mode.PAINT -> listOf("paint here", "size −", "size +", "clear", "palette")
    }

    /** What a chip reads. "shape" says what it is as well as what it does. */
    fun dockOptionLabel(key: String): String =
        if (key == "shape") "shape · ${shape.name.lowercase()}" else key

    fun dockBox(): Dock {
        val pad = du(12f)
        val gap = du(11f)
        val tileH = du(52f)
        val optH = du(44f)
        val inkH = du(34f)
        val hh = pad + tileH + gap + optH + gap + du(1f) + du(10f) + inkH + du(10f)
        val x = du(14f)
        val bw = w - x * 2f
        val y = viewH - insetBottom - du(14f) - hh
        val tileY = y + pad
        val optY = tileY + tileH + gap
        val ruleY = optY + optH + gap
        return Dock(
            x = x, y = y, w = bw, h = hh, pad = pad, gap = gap,
            tileY = tileY, tileH = tileH, optY = optY, optH = optH,
            ruleY = ruleY, inkY = ruleY + du(10f), inkH = inkH,
        )
    }

    /** Tier one: six equal columns. */
    fun dockTiles(): List<Chip> {
        val d = dockBox()
        val n = dockTools().size
        val gap = du(5f)
        val inner = d.w - d.pad * 2f
        val cw = (inner - gap * (n - 1)) / n
        return (0 until n).map { i ->
            Chip(i, d.x + d.pad + (cw + gap) * i, d.tileY, cw, d.tileH)
        }
    }

    /** The menu, pinned to the right of tier two at a fixed size. */
    fun dockMenuChip(): Chip {
        val d = dockBox()
        val mw = du(44f)
        val ch = du(38f)
        return Chip(
            -1, d.x + d.w - d.pad - mw, d.optY + (d.optH - ch) / 2f, mw, ch,
        )
    }

    /**
     * Tier two: the held tool's options, sharing what the menu leaves.
     *
     * Shared by the length of what each says rather than equally. The design
     * has them equal, which works at four chips; this app has five on the
     * ball — it keeps a catch toggle the design did not know about — and at
     * five equal chips "shape · circle" had to shrink its type to fit while
     * "size +" sat in white space. Weighting by label keeps every chip at the
     * same size of type, which is the thing worth protecting.
     */
    fun dockOptionChips(): List<Chip> {
        val d = dockBox()
        val opts = dockOptions()
        val gap = du(6f)
        val menu = dockMenuChip()
        val inner = menu.x - gap - (d.x + d.pad)
        val room = inner - gap * (opts.size - 1)
        val weights = opts.map { maxOf(dockOptionLabel(it).length, 4).toFloat() }
        val total = weights.sum()
        val ch = du(38f)
        val y = d.optY + (d.optH - ch) / 2f
        var x = d.x + d.pad
        return opts.indices.map { i ->
            val cw = room * weights[i] / total
            val c = Chip(i, x, y, cw, ch)
            x += cw + gap
            c
        }
    }

    /**
     * Tier three: the inks, flush, as one continuous ribbon. Sixteen points
     * tall was un-hittable one-handed; this is thirty-four.
     */
    fun dockInkCells(): List<Chip> {
        val d = dockBox()
        val n = Palette.NAMES.size
        val cw = (d.w - d.pad * 2f) / n
        return (0 until n).map { i -> Chip(i, d.x + d.pad + cw * i, d.inkY, cw, d.inkH) }
    }

    fun inDock(px: Float, py: Float): Boolean {
        val d = dockBox()
        return px >= d.x && px <= d.x + d.w && py >= d.y && py <= d.y + d.h
    }

    /**
     * What a tap on the dock means, or null for the panel itself. The view
     * acts on it: some of these — wiping a trail, say — need a bitmap the
     * simulation has never heard of.
     */
    fun dockHit(px: Float, py: Float): String? {
        if (!inDock(px, py)) return null
        val tools = dockTools()
        for (c in dockTiles()) {
            if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
            return "tool:${tools[c.i].name.lowercase()}"
        }
        val menu = dockMenuChip()
        if (px >= menu.x && px <= menu.x + menu.w && py >= menu.y && py <= menu.y + menu.h) {
            return "menu"
        }
        val opts = dockOptions()
        for (c in dockOptionChips()) {
            if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
            return "opt:${opts[c.i]}"
        }
        for (c in dockInkCells()) {
            if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
            return "ink:${c.i}"
        }
        return "panel"
    }

    /**
     * Everything a dock tap does that the simulation owns. Returns what it
     * did, so the view can wipe a trail or knock the phone when it needs to.
     */
    fun tapDock(what: String): String {
        when {
            what.startsWith("tool:") -> {
                modeNamed(what.removePrefix("tool:"))?.let { openMode(it) }
                return "tool"
            }
            what == "menu" -> {
                screen = Screen.TITLE
                drawerOpen = false; glyphOpen = false; editing = false; dragging = false
                return "menu"
            }
            what.startsWith("ink:") -> {
                val i = what.removePrefix("ink:").toInt()
                when {
                    familyLocked(i) -> { showPaywall(); return "locked" }
                    // The repeat tap opens the whole palette, which is how the
                    // strip has always worked and the only way in without a
                    // button there is nowhere to put.
                    i == inkFamily -> { drawerOpen = true; return "drawer" }
                    else -> { inkFamily = i; return "ink" }
                }
            }
            what.startsWith("opt:") -> return tapDockOption(what.removePrefix("opt:"))
        }
        return "panel"
    }

    private fun tapDockOption(key: String): String = when (key) {
        "palette" -> { if (drawerOpen) closeDrawer() else drawerOpen = true; "drawer" }
        "edit" -> {
            if (editLocked()) { showPaywall(); "locked" }
            else { editing = !editing; selected = -1; "edit" }
        }
        "catch" -> { mustCatch = !mustCatch; "toggle" }
        "paint here" -> { paintOnBumpers = !paintOnBumpers; "toggle" }
        "size −" -> { sizeIndex = maxOf(0, sizeIndex - 1); "size" }
        "size +" -> { sizeIndex = minOf(SIZES.size - 1, sizeIndex + 1); "size" }
        "shape" -> {
            shape = Shape.entries[(Shape.entries.indexOf(shape) + 1) % Shape.entries.size]
            "shape"
        }
        // The view owns the trail bitmap, so it does the wiping.
        "clear" -> "clear"
        else -> "panel"
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
        return if (t == null) "INK  ·  ${Palette.NAMES[inkFamily]}"
        else "BUMPER  ·  ${Palette.NAMES[t.family]}"
    }

    fun drawerFamily(): Int = targetBumper()?.family ?: inkFamily

    fun drawerTone(): Int = targetBumper()?.tone ?: inkTone

    /** The bumper the drawer is currently painting, if any. */
    fun targetBumper(): Bumper? =
        if (drawerTarget == Target.BUMPER) table.getOrNull(selected) else null

    data class Box(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cell: Float, val gx: Float, val gy: Float, val gridW: Float, val gridH: Float,
        val ay: Float, val ky: Float, val sy: Float, val hy: Float, val vy: Float,
        val rowH: Float,
        /** The grab handle, and the INK line under it. */
        val handleY: Float, val headerY: Float,
        val labelDrop: Float,
    )

    /** Rows below the colour grid, in the order they are drawn. */
    val drawerRows = listOf("alpha", "canvas", "scrim", "haptic", "sound")

    fun drawerRowCount(kind: String): Int = when (kind) {
        "alpha" -> Palette.ALPHAS.size
        "canvas" -> Palette.CANVAS_NAMES.size
        "scrim" -> Palette.SCRIMS.size
        "sound" -> Palette.VOICE_NAMES.size
        else -> Palette.HAPTIC_NAMES.size
    }

    fun drawerRowLabel(kind: String): String = when (kind) {
        "alpha" -> "TRANSLUCENCY"
        "canvas" -> "CANVAS"
        "scrim" -> "SCREEN TINT"
        "haptic" -> "HAPTICS"
        else -> "SOUND"
    }

    /**
     * A proper sheet rather than a floating slab: bottom-anchored, full
     * width, rounded at the top corners only, with a grab handle and a rule
     * above every group.
     *
     * The chips are thirty-eight points tall. They were about twenty-six,
     * which is under the forty-four a thumb wants and was the other half of
     * why this was hard to use one-handed.
     */
    fun drawerBox(): Box = drawerAt(du(1f)).let { first ->
        // The design is drawn for a phone held upright. Turned on its side
        // there is not the height for it, so the sheet shrinks to fit rather
        // than running off the bottom — the same answer the menu list gives.
        // Two passes settle it; the third is insurance.
        var b = first
        for (i in 0 until 3) {
            if (b.y >= 0f && b.y + b.h <= viewH + 0.5f) break
            b = drawerAt(du(1f) * (viewH / maxOf(b.h, 1f)) * 0.99f)
        }
        b
    }

    /**
     * The sheet at a given design unit. Everything below is the handoff's
     * numbers; the unit is what makes them fit the screen they are on.
     */
    private fun drawerAt(u: Float): Box {
        val cols = Palette.NAMES.size
        val rows = Palette.TONE_MIX.size
        val side = 20f * u
        val gridGap = 4f * u
        // Square cells, capped: across a wide screen a full-width grid of
        // them would be taller than the sheet on its own, and four rows of
        // enormous swatches is not what the design is asking for.
        val cell = minOf((w - side * 2f + gridGap) / cols, 34f * u)
        val gridW = cell * cols - gridGap
        val gridH = cell * rows - gridGap
        val rowH = 38f * u
        val labelDrop = 14f * u + 9.5f * u
        val handleY = 16f * u
        val headerY = handleY + 4f * u + 12f * u + 10f * u
        val gy0 = headerY + 12f * u
        // ay..vy stay what they always were: where a row of chips starts.
        // The rule and the label above each one are found by walking back up
        // from it, so a view that only knows about chips still draws right.
        var below = gy0 + gridH + 22f * u
        val ys = mutableListOf<Float>()
        for (i in drawerRows.indices) {
            ys.add(below + labelDrop + 8f * u)
            below = ys[i] + rowH + 18f * u
        }
        val hh = ys.last() + rowH + 40f * u + insetBottom
        val y = Geom.clamp(viewH - hh, 0f, viewH)
        return Box(
            x = 0f, y = y, w = w, h = hh, cell = cell,
            gx = (w - gridW) / 2f, gy = y + gy0, gridW = gridW, gridH = gridH,
            ay = y + ys[0], ky = y + ys[1], sy = y + ys[2], hy = y + ys[3], vy = y + ys[4],
            rowH = rowH, handleY = y + handleY, headerY = y + headerY, labelDrop = labelDrop,
        )
    }

    fun drawerRowY(b: Box, kind: String): Float = when (kind) {
        "alpha" -> b.ay
        "canvas" -> b.ky
        "scrim" -> b.sy
        "haptic" -> b.hy
        else -> b.vy
    }

    data class Chip(val i: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * A row of chips, sharing the sheet's width with a small gap. They fill
     * it rather than sitting in the middle of their share: a 38-point target
     * you can hit is worth more than the air around it.
     */
    fun drawerChips(y: Float, n: Int, b: Box): List<Chip> {
        val gap = b.rowH * (5f / 38f)
        val cw = (b.gridW - gap * (n - 1)) / n
        return (0 until n).map { i -> Chip(i, b.gx + (cw + gap) * i, y, cw, b.rowH) }
    }

    fun drawerHit(px: Float, py: Float): String {
        val b = drawerBox()
        if (px < b.x || px > b.x + b.w || py < b.y || py > b.y + b.h) return "outside"
        if (px >= b.gx && px <= b.gx + b.gridW && py >= b.gy && py <= b.gy + b.gridH) {
            val family = ((px - b.gx) / b.cell).toInt().coerceIn(0, Palette.NAMES.size - 1)
            val tone = ((py - b.gy) / b.cell).toInt().coerceIn(0, Palette.TONE_MIX.size - 1)
            if (familyLocked(family)) { showPaywall(); return "locked" }
            targetBumper()?.let {
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
                    "haptic" -> hapticIndex = c.i
                    else -> voiceIndex = c.i
                }
                return kind
            }
        }
        return "panel"
    }

    // ---- the glyph sheet --------------------------------------------------

    /**
     * Everything a bumper can be cut into, laid out at once.
     *
     * This used to be one button that stepped forward: six outlines, then A,
     * then B, and so on. Thirty-two taps to get back where you started, and —
     * worse — nothing on screen ever said the letters were there at all, so
     * for most people they were not. The palette had exactly this problem and
     * was fixed by showing the whole thing; this is the same fix, and it is
     * what makes the digits worth adding rather than ten more taps to bury.
     */
    var glyphOpen = false

    val glyphCols = 7

    /** Six outlines, then A to Z, then 0 to 9. */
    fun glyphCount(): Int = Shape.entries.size + Letters.GLYPHS.length

    fun glyphShapeAt(i: Int): Shape =
        if (i < Shape.entries.size) Shape.entries[i] else Shape.CIRCLE

    fun glyphTextAt(i: Int): String {
        if (i < Shape.entries.size) return ""
        val at = i - Shape.entries.size
        return Letters.GLYPHS.substring(at, at + 1)
    }

    /** Which cell a bumper is wearing, so the sheet can mark it. */
    fun glyphIndexOf(b: Bumper): Int =
        if (b.glyph.isEmpty()) Shape.entries.indexOf(b.shape)
        else Shape.entries.size + Letters.GLYPHS.indexOf(b.glyph[0])

    data class Sheet(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cell: Float, val gx: Float, val gy: Float,
    )

    fun glyphSheet(): Sheet {
        val rows = (glyphCount() + glyphCols - 1) / glyphCols
        val pad = minOf(w, h) * 0.035f
        val label = pad * 0.75f
        val bw = minOf(w * 0.94f, minOf(w, h) * 1.1f)
        val cell = minOf((bw - pad * 2f) / glyphCols, minOf(w, h) * 0.115f)
        val bh = pad + label + cell * rows + pad
        val x = (w - bw) / 2f
        val y = Geom.clamp(h - bh - pad * 0.5f, 0f, viewH)
        return Sheet(x, y, bw, bh, cell, x + (bw - cell * glyphCols) / 2f, y + pad + label)
    }

    fun glyphCells(): List<Chip> {
        val s = glyphSheet()
        return (0 until glyphCount()).map { i ->
            Chip(
                i,
                s.gx + s.cell * (i % glyphCols),
                s.gy + s.cell * (i / glyphCols),
                s.cell, s.cell,
            )
        }
    }

    /**
     * A cell drawn as the bumper it would make, so the sheet is a preview and
     * not a legend: the same outline code, the same ink, the same everything
     * but where it sits.
     */
    fun glyphSample(i: Int, cx: Float, cy: Float, r: Float): Bumper {
        val sel = table.getOrNull(selected)
        return Bumper(
            nx = cx / w, ny = cy / h, size = r / minOf(w, h),
            shape = glyphShapeAt(i), rot = 0f,
            family = sel?.family ?: 0, tone = sel?.tone ?: 2,
            glyph = glyphTextAt(i),
        )
    }

    fun closeGlyphs() { glyphOpen = false }

    fun glyphHit(px: Float, py: Float): String {
        val s = glyphSheet()
        if (px < s.x || px > s.x + s.w || py < s.y || py > s.y + s.h) return "outside"
        val b = table.getOrNull(selected) ?: return "panel"
        for (c in glyphCells()) {
            if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
            // A glyph covers whatever outline is under it, so leave that
            // alone: pick B over a hexagon, then pick the hexagon back.
            if (c.i < Shape.entries.size) { b.shape = Shape.entries[c.i]; b.glyph = "" }
            else b.glyph = glyphTextAt(c.i)
            return "pick"
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
        return (0 until n).map { i -> Chip(i, pad + bw * i, toolbarTop(), bw, bh) }
    }

    /** Clear of the status bar, whatever the phone puts up there. */
    fun toolbarTop(): Float = insetTop + minOf(w, h) * 0.02f

    fun toolbarHit(px: Float, py: Float): String? {
        for (c in toolbarButtons())
            if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h)
                return toolbarLabels[c.i]
        return null
    }

    fun doToolbar(label: String) {
        val b = table.getOrNull(selected)
        when (label) {
            "add" -> { table.add(Bumper(0.5f, 0.4f, 0.06f, Shape.CIRCLE, 0f)); selected = table.size - 1 }
            // The whole set at once. It was a step-forward button and the
            // letters may as well not have existed; see the glyph sheet.
            "shape" -> if (b != null) { glyphOpen = !glyphOpen; if (glyphOpen) closeDrawer() }
            "turn" -> if (b != null) b.rot += (Math.PI / 12.0).toFloat()
            // Cycling fourteen families one tap at a time is no way to pick a
            // colour when the whole palette already exists.
            "ink" -> if (b != null) { drawerOpen = true; drawerTarget = Target.BUMPER; closeGlyphs() }
            "−" -> if (b != null) b.size = Geom.clamp(b.size * 0.88f, MIN_BUMPER, MAX_BUMPER)
            "+" -> if (b != null) b.size = Geom.clamp(b.size * 1.14f, MIN_BUMPER, MAX_BUMPER)
            // The drawer may be pointed at the bumper that is going away.
            "del" -> if (b != null) { table.removeAt(selected); selected = -1; closeDrawer(); closeGlyphs() }
            "reset" -> { table = defaultTable(); selected = -1; closeDrawer(); closeGlyphs() }
            "done" -> { editing = false; selected = -1; closeDrawer(); closeGlyphs() }
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
