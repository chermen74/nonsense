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

enum class Mode { BALL, DIAL, BUMPERS, PAINT }

/**
 * The app opens on its own name and a list of what it can do, rather than
 * dropping you into whichever toy you left running. Everything you can reach
 * is on that one screen, so nothing has to be discovered by accident.
 */
enum class Screen { TITLE, PLAY }

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
)

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
    val NAMES = listOf(
        "graphite", "bone", "oxblood", "rust", "ochre", "moss", "teal", "slate", "plum",
    )
    private val BASES = intArrayOf(
        0x3a3a3c, 0xc9c0ab, 0x702929, 0x9c5b3c, 0xb08940,
        0x5c6e4a, 0x3f6b68, 0x465a78, 0x5c3f5e,
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
        const val MIN_BUMPER = 0.018f
        const val MAX_BUMPER = 0.30f

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

        fun defaultTable(): MutableList<Bumper> = mutableListOf(
            Bumper(0.25f, 0.30f, 0.055f, Shape.CIRCLE, 0f),
            Bumper(0.75f, 0.30f, 0.055f, Shape.CIRCLE, 0f),
            Bumper(0.50f, 0.50f, 0.068f, Shape.HEXAGON, 0f),
            Bumper(0.25f, 0.72f, 0.055f, Shape.BAR, 0f),
            Bumper(0.75f, 0.72f, 0.055f, Shape.BAR, 0f),
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
    var canvasIndex = 0        // sheer
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

    fun bumperPoints(b: Bumper): Array<FloatArray>? {
        val m = minOf(w, h)
        return Outlines.points(b.shape, b.nx * w, b.ny * h, b.size * m, b.rot)
    }

    fun bumperCenter(b: Bumper): FloatArray = floatArrayOf(b.nx * w, b.ny * h)
    fun bumperRadius(b: Bumper): Float = b.size * minOf(w, h)

    fun pointInBumper(px: Float, py: Float, b: Bumper): Boolean {
        val pts = bumperPoints(b)
            ?: return hypot(px - b.nx * w, py - b.ny * h) <= bumperRadius(b)
        return Geom.pointInPoly(px, py, pts)
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
        bounceCount++
        lastImpact = hypot(vx, vy)
        lastImpactWall = fromWall
    }

    fun step(dt: Float) {
        justCameToRest = false
        if (w <= 0f || h <= 0f) return
        if (screen == Screen.TITLE) return
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

    /** Reflect the ball off one bumper — any convex shape against any other. */
    fun bounce(b: Bumper) {
        val r = ballR()
        val bp = ballPoints()
        val gp = bumperPoints(b)
        val c = bumperCenter(b)
        val br = bumperRadius(b)

        val hit: Hit? = when {
            bp == null && gp == null -> Geom.circleVsCircle(bx, by, r, c[0], c[1], br)
            bp == null -> Geom.circleVsPoly(bx, by, r, gp!!)
            gp == null -> Geom.circleVsPoly(c[0], c[1], br, bp)
                ?.let { Hit(-it.nx, -it.ny, it.depth) }        // bumper against ball
            else -> Geom.satPolyPoly(bp, gp)
        }
        if (hit == null) return

        bx += hit.nx * hit.depth
        by += hit.ny * hit.depth

        val dot = vx * hit.nx + vy * hit.ny
        if (dot >= 0f) return
        impartSpin(hit.nx, hit.ny, false)
        vx = (vx - 2f * dot * hit.nx) * KICK
        vy = (vy - 2f * dot * hit.ny) * KICK
        val sp = hypot(vx, vy)
        if (sp > MAX_SPEED) { vx *= MAX_SPEED / sp; vy *= MAX_SPEED / sp }
    }

    // ---- the bottom strip -------------------------------------------------

    fun modeH(): Float = viewH * 0.062f
    fun stripH(): Float = viewH * 0.075f
    fun chromeH(): Float = modeH() + stripH()
    fun modeRowTop(): Float = h
    fun stripTop(): Float = h + modeH()

    /**
     * The mode row. Android has no keyboard and a hidden double tap is not a
     * feature anyone can find, so the four toys are on screen, along with the
     * palette and whichever toggle the current mode has.
     */
    fun modeLabels(): List<String> {
        val labels = mutableListOf("menu", "ball", "dial", "bumpers", "paint", "ink")
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

    fun tapMode(label: String) {
        when (label) {
            "menu" -> { screen = Screen.TITLE; drawerOpen = false; editing = false; dragging = false }
            "ball" -> { mode = Mode.BALL; editing = false }
            "dial" -> { mode = Mode.DIAL; editing = false }
            "bumpers" -> { mode = Mode.BUMPERS; editing = false }
            "paint" -> { mode = Mode.PAINT; editing = false }
            "ink" -> drawerOpen = !drawerOpen
            "edit" -> { editing = !editing; selected = -1 }
            "catch" -> mustCatch = !mustCatch
        }
    }

    fun inStrip(y: Float): Boolean = y >= stripTop() && y <= stripTop() + stripH()

    // ---- the opening screen ----------------------------------------------

    data class MenuItem(val key: String, val label: String, val blurb: String)

    /**
     * The front door. Every toy is named here with a line saying what it is,
     * because none of them announce themselves once you are inside — a field
     * with a ball in it looks the same whether or not it will let you paint.
     */
    fun menuItems(): List<MenuItem> = listOf(
        MenuItem("ball", "ball", "throw it, let it ring off the walls"),
        MenuItem("dial", "dial", "a knurled wheel that clicks as it spins"),
        MenuItem("bumpers", "bumpers", "build a table and bounce through it"),
        MenuItem("paint", "paint", "an open field and a ball that leaves ink"),
        MenuItem("ink", "ink & canvas", "colour, translucency, what it sits on"),
    )

    /** Where the wordmark's baseline sits. */
    fun titleBaseline(): Float = viewH * 0.26f

    fun menuRowH(): Float = minOf(viewH * 0.082f, w * 0.16f)

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
        when (key) {
            "ball" -> { mode = Mode.BALL; screen = Screen.PLAY }
            "dial" -> { mode = Mode.DIAL; screen = Screen.PLAY }
            "bumpers" -> { mode = Mode.BUMPERS; screen = Screen.PLAY }
            "paint" -> { mode = Mode.PAINT; screen = Screen.PLAY }
            "ink" -> { screen = Screen.PLAY; drawerOpen = true }
            else -> return false
        }
        editing = false
        return true
    }

    data class Zone(val kind: String, val x0: Float, val x1: Float, val count: Int)

    fun stripZones(): List<Zone> = listOf(
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
                "color" -> if (i == inkFamily) drawerOpen = true else inkFamily = i
                "size" -> sizeIndex = i
                "shape" -> { shape = Shape.entries[i]; spin = 0f }
            }
            return true
        }
        return false
    }

    // ---- the palette drawer ----------------------------------------------

    var drawerOpen = false

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
            inkFamily = ((px - b.gx) / b.cell).toInt().coerceIn(0, Palette.NAMES.size - 1)
            inkTone = ((py - b.gy) / b.cell).toInt().coerceIn(0, Palette.TONE_MIX.size - 1)
            return "ink"
        }
        for (kind in drawerRows) {
            for (c in drawerChips(drawerRowY(b, kind), drawerRowCount(kind), b)) {
                if (px < c.x || px > c.x + c.w || py < c.y || py > c.y + c.h) continue
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

    val toolbarLabels = listOf("add", "shape", "turn", "−", "+", "del", "reset", "done")

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

    fun doToolbar(label: String) {
        val b = table.getOrNull(selected)
        when (label) {
            "add" -> { table.add(Bumper(0.5f, 0.4f, 0.06f, Shape.CIRCLE, 0f)); selected = table.size - 1 }
            "shape" -> if (b != null) {
                b.shape = Shape.entries[(Shape.entries.indexOf(b.shape) + 1) % Shape.entries.size]
            }
            "turn" -> if (b != null) b.rot += (Math.PI / 12.0).toFloat()
            "−" -> if (b != null) b.size = Geom.clamp(b.size * 0.88f, MIN_BUMPER, MAX_BUMPER)
            "+" -> if (b != null) b.size = Geom.clamp(b.size * 1.14f, MIN_BUMPER, MAX_BUMPER)
            "del" -> if (b != null) { table.removeAt(selected); selected = -1 }
            "reset" -> { table = defaultTable(); selected = -1 }
            "done" -> { editing = false; selected = -1 }
        }
    }

    /** Handles on the selected bumper: resize, and rotate. */
    fun handles(b: Bumper): Array<FloatArray> {
        val c = bumperCenter(b)
        val r = bumperRadius(b)
        val co = cos(b.rot)
        val si = sin(b.rot)
        val reach = r + minOf(w, h) * 0.06f
        return arrayOf(
            floatArrayOf(c[0] + r * co, c[1] + r * si),
            floatArrayOf(c[0] - reach * si, c[1] + reach * co),
        )
    }

    fun cycleMode() {
        editing = false
        mode = Mode.entries[(Mode.entries.indexOf(mode) + 1) % Mode.entries.size]
    }
}
