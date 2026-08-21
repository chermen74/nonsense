package com.nonsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The Android port of the desktop toy, checked the same way the browser build
 * is: by driving the simulation rather than by reading it. Toy.kt has no
 * android.* in it precisely so this can run on a plain JVM in CI.
 */
class ToyTest {

    private val dt = 1f / 60f

    private fun toy(w: Float = 1080f, hh: Float = 1920f, inset: Float = 48f): Toy =
        Toy().apply { resize(w, hh, inset) }

    /** Steps until [done] or the time runs out; returns whether it happened. */
    private fun run(t: Toy, seconds: Float, done: () -> Boolean): Boolean {
        val frames = (seconds / dt).toInt()
        repeat(frames) {
            t.step(dt)
            if (done()) return true
        }
        return false
    }

    // ---- palette ---------------------------------------------------------

    @Test fun `palette is nine families of four tones, all distinct`() {
        val flat = Palette.COLORS.flatMap { it.toList() }
        assertEquals(36, flat.size)
        assertEquals(36, flat.toSet().size)
    }

    @Test fun `every family runs light to dark`() {
        fun lum(c: Int): Float {
            val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
        for (tones in Palette.COLORS) {
            assertTrue(lum(tones[0]) > lum(tones[1]))
            assertTrue(lum(tones[1]) > lum(tones[2]))
            assertTrue(lum(tones[2]) > lum(tones[3]))
        }
    }

    @Test fun `the original colours are still in the palette`() {
        val flat = Palette.COLORS.flatMap { it.toList() }.map { it and 0xffffff }
        for (c in listOf(0x3a3a3c, 0x702929, 0x465a78, 0x5c6e4a, 0xb08940)) {
            assertTrue("missing ${c.toString(16)}", flat.contains(c))
        }
    }

    // ---- sizing recovers -------------------------------------------------

    @Test fun `radii follow the field instead of being fixed at construction`() {
        val t = Toy()
        assertEquals(0f, t.ballR(), 0.001f)      // nothing measured yet
        t.resize(1080f, 1920f, 48f)
        assertTrue(t.ballR() > 0f)
        assertTrue(t.dialR > 0f)
        val small = t.ballR()
        t.resize(2000f, 3000f, 48f)
        assertTrue("radius must track a bigger field", t.ballR() > small)
    }

    @Test fun `a zero-sized measure is ignored rather than zeroing the ball`() {
        val t = toy()
        val r = t.ballR()
        t.resize(0f, 0f, 0f)
        assertEquals(r, t.ballR(), 0.001f)
    }

    // ---- walls -----------------------------------------------------------

    @Test fun `walls contain every shape at every size`() {
        for (shape in Shape.entries) {
            for (si in intArrayOf(0, Toy.SIZES.size - 1)) {
                val t = toy()
                t.mode = Mode.BALL
                t.shape = shape
                t.sizeIndex = si
                t.spin = 0.7f; t.omega = 6f
                t.bx = 500f; t.by = 900f
                t.vx = 2600f; t.vy = 1900f
                var worst = 0f
                repeat(240) {
                    t.step(dt)
                    val pts = t.ballPoints()
                    val r = t.ballR()
                    val xs: List<Float>; val ys: List<Float>
                    if (pts == null) {
                        xs = listOf(t.bx - r, t.bx + r); ys = listOf(t.by - r, t.by + r)
                    } else {
                        xs = pts.map { it[0] }; ys = pts.map { it[1] }
                    }
                    val out = maxOf(
                        -(xs.min()), xs.max() - t.w,
                        -(ys.min()), ys.max() - t.h,
                    )
                    if (out > worst) worst = out
                }
                assertTrue("$shape size $si escaped by $worst", worst < 2f)
            }
        }
    }

    @Test fun `a ball resting against a wall is not pinned there`() {
        val t = toy()
        t.shape = Shape.SQUARE
        t.bx = t.ballR(); t.by = 900f
        t.vx = 600f; t.vy = 0f          // moving away from the left wall
        t.omega = 8f
        run(t, 0.5f) { false }
        assertTrue("should have travelled away from the wall", t.bx > t.ballR() * 3f)
    }

    // ---- bumpers ---------------------------------------------------------

    @Test fun `every ball shape is deflected by every bumper shape`() {
        for (ballShape in Shape.entries) {
            for (bumpShape in Shape.entries) {
                val t = toy()
                t.mode = Mode.BUMPERS
                t.paintOnBumpers = false
                t.table = mutableListOf(Bumper(0.5f, 0.55f, 0.12f, bumpShape, 0.2f))
                t.shape = ballShape
                t.sizeIndex = Toy.DEFAULT_SIZE
                t.bx = t.w / 2f; t.by = t.h * 0.12f
                t.vx = 0f; t.vy = 1400f
                val deflected = run(t, 3f) {
                    abs(atan2(t.vx, t.vy)) > 0.26f || t.vy < 0f
                }
                assertTrue("$ballShape not deflected by $bumpShape", deflected)
            }
        }
    }

    @Test fun `a fast flick does not tunnel through a bumper`() {
        for (shape in Shape.entries) {
            val t = toy()
            t.mode = Mode.BUMPERS
            t.paintOnBumpers = false
            t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.10f, Shape.SQUARE, 0f))
            t.shape = shape
            t.sizeIndex = 1                       // small, so it could slip past
            t.bx = t.w / 2f; t.by = t.h * 0.1f
            t.vx = 0f; t.vy = 5200f
            val hit = run(t, 2f) { t.vy < 0f || abs(t.vx) > 40f }
            assertTrue("$shape tunnelled straight through", hit)
        }
    }

    @Test fun `no shape gets stuck inside a bumper`() {
        for (shape in Shape.entries) {
            val t = toy()
            t.mode = Mode.BUMPERS
            t.paintOnBumpers = false
            t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.14f, Shape.PENTAGON, 0.3f))
            t.shape = shape
            t.sizeIndex = 4
            t.bx = t.w / 2f; t.by = t.h / 2f       // starting overlapped
            t.vx = 1800f; t.vy = 900f
            run(t, 1.5f) { false }
            val b = t.table[0]
            val bp = t.ballPoints()
            val gp = t.bumperPoints(b)!!
            val overlap = if (bp == null)
                Geom.circleVsPoly(t.bx, t.by, t.ballR(), gp)?.depth ?: 0f
            else Geom.satPolyPoly(bp, gp)?.depth ?: 0f
            assertTrue("$shape still overlapping by $overlap", overlap < 2f)
        }
    }

    @Test fun `a non-round ball picks up spin from an impact`() {
        val t = toy()
        t.mode = Mode.BALL
        t.shape = Shape.SQUARE
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 1500f; t.vy = 400f
        run(t, 1.2f) { abs(t.omega) > 0.1f }
        assertTrue("a square should tumble, omega=${t.omega}", abs(t.omega) > 0.1f)
    }

    // ---- what the haptics are driven from --------------------------------

    /** Reports whether the very next impact was a wall, or null if none came. */
    private fun firstImpact(t: Toy, seconds: Float): Boolean? {
        val start = t.bounceCount
        repeat((seconds / dt).toInt()) {
            t.step(dt)
            if (t.bounceCount != start) return t.lastImpactWall
        }
        return null
    }

    @Test fun `a wall hit is reported as a wall`() {
        val t = toy()
        t.mode = Mode.BALL
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 2200f; t.vy = 0f
        assertEquals(true, firstImpact(t, 3f))
        assertTrue("a wall hit should be worth feeling", t.impactStrength() > 0f)
    }

    @Test fun `a bumper hit is reported as not a wall`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.paintOnBumpers = false
        t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.12f, Shape.CIRCLE, 0f))
        t.bx = t.w / 2f; t.by = t.h * 0.15f
        t.vx = 0f; t.vy = 1600f
        assertEquals(false, firstImpact(t, 3f))
    }

    @Test fun `impact strength ignores a nudge and saturates on a hard hit`() {
        val t = toy()
        t.lastImpact = 0f
        assertEquals(0f, t.impactStrength(), 0.001f)
        t.lastImpact = 150f
        assertEquals("a ball settling on an edge must not buzz", 0f, t.impactStrength(), 0.001f)
        t.lastImpact = 2600f
        assertEquals(1f, t.impactStrength(), 0.001f)
        t.lastImpact = 9000f
        assertEquals("and it cannot exceed full", 1f, t.impactStrength(), 0.001f)
    }

    @Test fun `impact strength rises with the speed of the hit`() {
        val t = toy()
        t.lastImpact = 600f
        val soft = t.impactStrength()
        t.lastImpact = 1500f
        val firm = t.impactStrength()
        assertTrue("soft=$soft firm=$firm", firm > soft)
        assertTrue(soft > 0f && firm < 1f)
    }

    @Test fun `a ball coming to rest stops producing impacts to feel`() {
        val t = toy()
        t.mode = Mode.BALL
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 2500f; t.vy = 1200f
        assertTrue("should have settled", run(t, 12f) { t.vx == 0f && t.vy == 0f })
        // lastImpact describes the last bounce that happened, not the current
        // speed, so it stays warm after the ball stops. That is harmless: the
        // view only reads it when bounceCount moves. The property that matters
        // is that nothing new fires once the ball is still.
        val settled = t.bounceCount
        run(t, 3f) { false }
        assertEquals("a still ball must not keep buzzing", settled, t.bounceCount)
    }

    // ---- catching --------------------------------------------------------

    @Test fun `catching is off by default and a far press still summons the ball`() {
        val t = toy()
        assertFalse(t.mustCatch)
        assertTrue(t.grab(50f, 50f, 0L))
        assertEquals(50f, t.bx, 0.01f)
        assertEquals(50f, t.by, 0.01f)
    }

    @Test fun `with catching on a miss does not grab and does not stop the ball`() {
        val t = toy()
        t.mustCatch = true
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 300f; t.vy = 0f
        assertFalse(t.grab(20f, 20f, 123L))
        assertFalse(t.dragging)
        assertEquals(300f, t.vx, 0.01f)
        assertEquals(123L, t.missAt)
    }

    @Test fun `a catch holds the ball where it was caught`() {
        val t = toy()
        t.mustCatch = true
        t.bx = 500f; t.by = 900f
        t.vx = 400f; t.vy = 200f
        val offset = t.ballR() * 0.6f
        assertTrue(t.grab(500f + offset, 900f, 0L))
        assertEquals("must not snap to the finger", 500f, t.bx, 1f)
        assertEquals(900f, t.by, 1f)
        assertEquals(0f, t.vx, 0.01f)
        t.drag(500f + offset + 150f, 960f)
        assertEquals(650f, t.bx, 1f)
        assertEquals(960f, t.by, 1f)
    }

    @Test fun `catch slack helps a bead and not a grapefruit`() {
        val t = toy()
        t.sizeIndex = 0
        val beadSlack = t.catchSlack()
        t.sizeIndex = Toy.SIZES.size - 1
        val bigSlack = t.catchSlack()
        assertTrue("a bead should be forgiving, got $beadSlack", beadSlack > 8f)
        assertEquals("a big ball should not be", 0f, bigSlack, 0.001f)
    }

    @Test fun `a bar is caught along its length and missed across its narrow side`() {
        val t = toy()
        t.mustCatch = true
        t.shape = Shape.BAR
        t.sizeIndex = 6
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.spin = 0f
        val reach = t.ballR() * 1.1f
        assertTrue("along the bar", t.withinCatch(t.bx + reach, t.by))
        assertFalse("across the bar", t.withinCatch(t.bx, t.by + reach))
    }

    @Test fun `catching only applies to ball mode`() {
        val t = toy()
        t.mustCatch = true
        for (m in listOf(Mode.BUMPERS, Mode.PAINT)) {
            t.mode = m
            assertFalse(t.catching())
            assertTrue("$m should still summon the ball", t.grab(30f, 30f, 0L))
            t.dragging = false
        }
    }

    // ---- paint -----------------------------------------------------------

    @Test fun `paint is live on the bumper table only when switched on`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.paintOnBumpers = true
        assertTrue(t.painting())
        t.paintOnBumpers = false
        assertFalse(t.painting())
        t.mode = Mode.PAINT
        assertTrue(t.painting())
        t.mode = Mode.BUMPERS
        t.paintOnBumpers = true
        t.editing = true
        assertFalse("editing must suppress the strip", t.painting())
    }

    @Test fun `ink width follows how wide the shape actually is`() {
        val t = toy()
        t.sizeIndex = Toy.DEFAULT_SIZE
        t.shape = Shape.CIRCLE
        val round = t.inkWidth()
        t.shape = Shape.BAR
        assertTrue("a bar must not ink as wide as it is long", t.inkWidth() < round * 0.6f)
    }

    // ---- the strip and the drawer ---------------------------------------

    @Test fun `the strip has colour, size and shape zones that cover the width`() {
        val t = toy()
        val zones = t.stripZones()
        assertEquals(listOf("color", "size", "shape"), zones.map { it.kind })
        assertEquals(0f, zones.first().x0, 0.001f)
        assertEquals(t.w, zones.last().x1, 0.001f)
    }

    @Test fun `tapping the strip sets colour, size and shape`() {
        val t = toy()
        val z = t.stripZones()
        val sizeZone = z[1]
        val step = (sizeZone.x1 - sizeZone.x0) / sizeZone.count
        assertTrue(t.stripTap(sizeZone.x0 + step * 5.5f))
        assertEquals(5, t.sizeIndex)
        val shapeZone = z[2]
        val sstep = (shapeZone.x1 - shapeZone.x0) / shapeZone.count
        assertTrue(t.stripTap(shapeZone.x0 + sstep * 2.5f))
        assertEquals(Shape.entries[2], t.shape)
    }

    @Test fun `tapping the family you are already on opens the drawer`() {
        val t = toy()
        t.inkFamily = 0
        val colorZone = t.stripZones()[0]
        val step = (colorZone.x1 - colorZone.x0) / colorZone.count
        t.stripTap(colorZone.x0 + step * 3.5f)
        assertEquals(3, t.inkFamily)
        assertFalse(t.drawerOpen)
        t.stripTap(colorZone.x0 + step * 3.5f)      // again, same family
        assertTrue(t.drawerOpen)
    }

    @Test fun `the drawer fits on screen in portrait and landscape`() {
        for ((w, h) in listOf(1080f to 1920f, 1920f to 1080f, 720f to 1280f)) {
            val t = toy(w, h)
            val b = t.drawerBox()
            assertTrue("x $b", b.x >= 0f && b.x + b.w <= w + 0.5f)
            // above the control rows, so the row under it stays tappable
            assertTrue("y $b", b.y >= 0f && b.y + b.h <= t.h + 0.5f)
        }
    }

    @Test fun `the drawer sets ink, translucency and tint, and closes on an outside tap`() {
        val t = toy()
        t.drawerOpen = true
        val b = t.drawerBox()
        assertEquals("ink", t.drawerHit(b.gx + b.cell * 6.5f, b.gy + b.cell * 3.5f))
        assertEquals(6, t.inkFamily)
        assertEquals(3, t.inkTone)
        val a = t.drawerChips(b.ay, Palette.ALPHAS.size, b)[1]
        assertEquals("alpha", t.drawerHit(a.x + a.w / 2f, a.y + a.h / 2f))
        assertEquals(1, t.inkAlphaIndex)
        val s = t.drawerChips(b.sy, Palette.SCRIMS.size, b)[4]
        assertEquals("scrim", t.drawerHit(s.x + s.w / 2f, s.y + s.h / 2f))
        assertEquals(4, t.scrimIndex)
        assertEquals("outside", t.drawerHit(b.x - 20f, b.y - 20f))
    }

    // ---- nothing lives under the navigation bar -------------------------

    @Test fun `the play field stops above the nav bar and the controls`() {
        val t = toy(1080f, 1920f, inset = 132f)
        assertTrue("field must not reach the view foot", t.h < 1920f - 132f)
        assertEquals(1920f - 132f - t.chromeH(), t.h, 0.5f)
    }

    @Test fun `both control rows sit above the navigation bar`() {
        val t = toy(1080f, 1920f, inset = 132f)
        val foot = 1920f - 132f
        for (c in t.modeCells()) {
            assertTrue("mode cell below the nav bar: $c", c.y + c.h <= foot + 0.5f)
            assertTrue("mode cell above the field", c.y >= t.h - 0.5f)
        }
        assertTrue("strip below the nav bar", t.stripTop() + t.stripH() <= foot + 0.5f)
        assertTrue("strip below the mode row", t.stripTop() >= t.modeRowTop())
    }

    @Test fun `the ball cannot come to rest under the controls`() {
        val t = toy(1080f, 1920f, inset = 132f)
        t.mode = Mode.BALL
        t.bx = 500f; t.by = 100f
        t.vx = 0f; t.vy = 4000f
        run(t, 4f) { false }
        val r = t.ballR()
        assertTrue("ball at ${t.by} strayed past the field foot ${t.h}", t.by + r <= t.h + 1f)
    }

    @Test fun `a zero inset still leaves room for the controls`() {
        val t = toy(1080f, 1920f, inset = 0f)
        assertEquals(1920f - t.chromeH(), t.h, 0.5f)
        assertTrue(t.stripTop() + t.stripH() <= 1920f + 0.5f)
    }

    // ---- the mode row ----------------------------------------------------

    @Test fun `every toy is reachable from the mode row`() {
        val t = toy()
        val cells = t.modeCells()
        assertTrue(t.modeLabels().containsAll(listOf("ball", "dial", "bumpers", "paint", "ink")))
        for ((i, label) in t.modeLabels().withIndex()) {
            val c = cells[i]
            assertEquals(label, t.modeHit(c.x + c.w / 2f, c.y + c.h / 2f))
        }
        t.tapMode("paint")
        assertEquals(Mode.PAINT, t.mode)
        t.tapMode("bumpers")
        assertEquals(Mode.BUMPERS, t.mode)
        t.tapMode("dial")
        assertEquals(Mode.DIAL, t.mode)
        t.tapMode("ball")
        assertEquals(Mode.BALL, t.mode)
    }

    @Test fun `the mode row offers the toggle that belongs to the mode`() {
        val t = toy()
        t.mode = Mode.BALL
        assertTrue(t.modeLabels().contains("catch"))
        assertFalse(t.modeLabels().contains("edit"))
        t.tapMode("catch")
        assertTrue(t.mustCatch)

        t.mode = Mode.BUMPERS
        assertTrue(t.modeLabels().contains("edit"))
        assertFalse(t.modeLabels().contains("catch"))
        t.tapMode("edit")
        assertTrue(t.editing)

        t.mode = Mode.PAINT
        assertFalse(t.modeLabels().contains("edit"))
        assertFalse(t.modeLabels().contains("catch"))
    }

    @Test fun `the ink button opens and closes the palette`() {
        val t = toy()
        assertFalse(t.drawerOpen)
        t.tapMode("ink")
        assertTrue(t.drawerOpen)
        t.tapMode("ink")
        assertFalse(t.drawerOpen)
    }

    @Test fun `the mode row does not swallow taps meant for the field`() {
        val t = toy()
        assertEquals(null, t.modeHit(t.w / 2f, t.h / 2f))
        assertEquals(null, t.modeHit(t.w / 2f, 10f))
    }

    @Test fun `the strip is only the strip`() {
        val t = toy()
        assertFalse("field taps are not strip taps", t.inStrip(t.h / 2f))
        assertFalse("the mode row is not the strip", t.inStrip(t.modeRowTop() + t.modeH() / 2f))
        assertTrue(t.inStrip(t.stripTop() + t.stripH() / 2f))
    }

    // ---- it should look sheer without being told -------------------------

    @Test fun `the defaults are visibly see-through`() {
        val t = Toy()
        assertTrue("the ball should start translucent", t.inkAlpha() < 1f)
        assertTrue("the tint should start light", t.scrim() < 0.12f)
        assertTrue("but still be a tint", t.scrim() > 0f)
    }

    // ---- editing the table ----------------------------------------------

    @Test fun `the toolbar adds, reshapes, resizes and deletes`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        val n = t.table.size
        t.doToolbar("add")
        assertEquals(n + 1, t.table.size)
        assertEquals(t.table.size - 1, t.selected)
        val shapeBefore = t.table[t.selected].shape
        t.doToolbar("shape")
        assertTrue(t.table[t.selected].shape != shapeBefore)
        val sizeBefore = t.table[t.selected].size
        t.doToolbar("+")
        assertTrue(t.table[t.selected].size > sizeBefore)
        t.doToolbar("−")
        t.doToolbar("−")
        assertTrue(t.table[t.selected].size < sizeBefore)
        val rotBefore = t.table[t.selected].rot
        t.doToolbar("turn")
        assertTrue(t.table[t.selected].rot != rotBefore)
        t.doToolbar("del")
        assertEquals(n, t.table.size)
        assertEquals(-1, t.selected)
        t.doToolbar("done")
        assertFalse(t.editing)
    }

    @Test fun `bumper size is clamped at both ends`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.selected = 0
        repeat(60) { t.doToolbar("+") }
        assertTrue(t.table[0].size <= Toy.MAX_BUMPER + 1e-4f)
        repeat(120) { t.doToolbar("−") }
        assertTrue(t.table[0].size >= Toy.MIN_BUMPER - 1e-4f)
    }

    @Test fun `the toolbar buttons sit inside the field and do not overlap`() {
        val t = toy()
        val btns = t.toolbarButtons()
        assertEquals(t.toolbarLabels.size, btns.size)
        for (b in btns) {
            assertTrue(b.x >= 0f && b.x + b.w <= t.w + 0.5f)
            assertTrue(b.y >= 0f && b.y + b.h <= t.h)
        }
        for (i in 0 until btns.size - 1) {
            assertTrue(btns[i].x + btns[i].w <= btns[i + 1].x + 0.5f)
        }
        assertEquals("add", t.toolbarHit(btns[0].x + 2f, btns[0].y + 2f))
        assertEquals(null, t.toolbarHit(t.w / 2f, t.h - 10f))
    }

    @Test fun `a bumper table survives being described normalised`() {
        // the same table on two different fields keeps its proportions
        val a = toy(1080f, 1920f)
        val b = toy(1440f, 2560f)
        val bumper = Bumper(0.25f, 0.30f, 0.055f, Shape.CIRCLE, 0f)
        val ca = a.bumperCenter(bumper)
        val cb = b.bumperCenter(bumper)
        assertEquals(ca[0] / a.w, cb[0] / b.w, 0.0001f)
        assertEquals(ca[1] / a.h, cb[1] / b.h, 0.0001f)
        assertEquals(a.bumperRadius(bumper) / minOf(a.w, a.h),
                     b.bumperRadius(bumper) / minOf(b.w, b.h), 0.0001f)
    }

    // ---- editing freezes the ball ---------------------------------------

    @Test fun `editing the table freezes the ball`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.bx = 400f; t.by = 400f
        t.vx = 900f; t.vy = 900f
        run(t, 0.5f) { false }
        assertEquals(400f, t.bx, 0.01f)
        assertEquals(400f, t.by, 0.01f)
    }

    // ---- coming to rest --------------------------------------------------

    @Test fun `the ball reports the frame it comes to rest`() {
        val t = toy()
        t.mode = Mode.BALL
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 120f; t.vy = 0f
        // 0.55/s friction takes its time: 120px/s needs ~5.4s to fall under
        // the 4px/s cutoff, so give it headroom rather than a tight window
        val rested = run(t, 10f) { t.justCameToRest }
        assertTrue("should report coming to rest", rested)
        assertEquals(0f, t.vx, 0.001f)
    }
}
