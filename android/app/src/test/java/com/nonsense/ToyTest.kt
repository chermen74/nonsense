package com.nonsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The Android port of the desktop toy, checked the same way the browser build
 * is: by driving the simulation rather than by reading it. Toy.kt has no
 * android.* in it precisely so this can run on a plain JVM in CI.
 */
class ToyTest {

    private val dt = 1f / 60f

    /**
     * A toy past the front door — it opens on its title screen and nothing
     * moves while that is up — and with everything unlocked. These tests are
     * about the simulation, not the shop; what the free tier can reach is
     * tested on its own, further down, by asking for it explicitly.
     */
    private fun toy(w: Float = 1080f, hh: Float = 1920f, inset: Float = 48f): Toy =
        Toy().apply { resize(w, hh, inset); screen = Screen.PLAY; tier = Tier.FULL }

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

    // ---- the opening screen ----------------------------------------------

    /** Turns the wheel through [turns] revolutions over [seconds]. */
    private fun sweepDial(t: Toy, turns: Float, seconds: Float, samples: Int = 20,
                          stallFrames: Int = 0) {
        val r = minOf(t.w, t.h) * 0.3f
        val cx = t.w / 2f
        val cy = t.h / 2f
        t.grabDial(cx + r, cy)                       // angle 0
        val total = (turns * 2.0 * Math.PI).toFloat()
        for (i in 1..samples) {
            val a = total * i / samples
            t.dragDial(cx + r * cos(a), cy + r * sin(a), seconds / samples)
        }
        // A finger nearly always rests for a frame or two before it lifts.
        repeat(stallFrames) {
            t.dragDial(cx + r * cos(total), cy + r * sin(total), dt)
        }
    }

    @Test fun `the app opens on its own name`() {
        assertEquals(Screen.TITLE, Toy().screen)
    }

    @Test fun `nothing moves while the title is up`() {
        val t = toy()
        t.bx = 300f; t.by = 400f; t.vx = 2000f; t.vy = 900f
        t.screen = Screen.TITLE
        run(t, 1f) { false }
        assertEquals(300f, t.bx, 0.001f)
        assertEquals(400f, t.by, 0.001f)
    }

    @Test fun `every menu row is on screen, clear of the navigation bar`() {
        for (size in listOf(Triple(1080f, 1920f, 48f), Triple(1440f, 3120f, 120f),
                            Triple(720f, 1280f, 0f))) {
            val t = Toy().apply { resize(size.first, size.second, size.third) }
            val rows = t.menuRows()
            assertEquals(t.menuItems().size, rows.size)
            val floorY = t.viewH - t.insetBottom
            for (c in rows) {
                assertTrue("row ${c.i} above the title at $size", c.y > t.titleBaseline())
                assertTrue("row ${c.i} under the nav bar at $size", c.y + c.h <= floorY)
                assertTrue("row ${c.i} off the side at $size", c.x > 0f && c.x + c.w < t.w)
            }
            for (i in 1 until rows.size) {
                assertTrue("rows $i overlap at $size", rows[i].y > rows[i - 1].y + rows[i - 1].h)
            }
        }
    }

    @Test fun `each menu row answers to its own middle`() {
        val t = toy()
        val items = t.menuItems()
        for (c in t.menuRows()) {
            assertEquals(items[c.i].key, t.menuHit(c.x + c.w / 2f, c.y + c.h / 2f))
        }
        assertEquals(null, t.menuHit(t.w / 2f, t.titleBaseline() - 10f))
    }

    @Test fun `the menu opens what it names`() {
        for ((key, mode) in listOf("ball" to Mode.BALL, "dial" to Mode.DIAL,
                                   "bumpers" to Mode.BUMPERS, "paint" to Mode.PAINT)) {
            // unlocked: this is about routing, not about who may go where
            val t = Toy().apply { resize(1080f, 1920f, 48f); tier = Tier.FULL }
            assertTrue(t.tapMenu(key))
            assertEquals(mode, t.mode)
            assertEquals(Screen.PLAY, t.screen)
            assertFalse(t.drawerOpen)
        }
        val t = Toy().apply { resize(1080f, 1920f, 48f); tier = Tier.FULL }
        assertTrue(t.tapMenu("ink"))
        assertEquals(Screen.PLAY, t.screen)
        assertTrue("ink & canvas should open the palette", t.drawerOpen)
    }

    @Test fun `there is a drawn way back to the menu from every mode`() {
        for (mode in Mode.entries) {
            val t = toy()
            t.mode = mode
            val labels = t.modeLabels()
            assertTrue("no menu in $mode", labels.contains("menu"))
            val cell = t.modeCells()[labels.indexOf("menu")]
            assertEquals("menu", t.modeHit(cell.x + cell.w / 2f, cell.y + cell.h / 2f))
            t.drawerOpen = true
            t.tapMode("menu")
            assertEquals(Screen.TITLE, t.screen)
            assertFalse("going back should close the palette", t.drawerOpen)
        }
    }

    // ---- the dial --------------------------------------------------------

    @Test fun `a flick leaves the wheel turning for seconds`() {
        val t = toy()
        t.mode = Mode.DIAL
        sweepDial(t, turns = 0.5f, seconds = 0.4f)
        t.releaseDial()
        val thrown = abs(t.dialOmega)
        assertTrue("a flick should spin it: $thrown", thrown > 4f)
        run(t, 3f) { false }
        assertTrue("still turning after 3s: ${t.dialOmega}", abs(t.dialOmega) > 1.5f)
        // It does stop — but a knurled wheel is supposed to run down slowly,
        // and from a hard flick this one takes something over twenty seconds.
        assertTrue("should eventually stop", run(t, 45f) { t.dialOmega == 0f })
    }

    @Test fun `a finger that stalls before it lifts still throws the wheel`() {
        val t = toy()
        t.mode = Mode.DIAL
        sweepDial(t, turns = 0.5f, seconds = 0.4f, stallFrames = 2)
        t.releaseDial()
        // Reading only the final sample would hand back a wheel at rest,
        // which is what made the old dial feel dead.
        assertTrue("stalled flick died: ${t.dialOmega}", abs(t.dialOmega) > 2f)
    }

    @Test fun `the wheel cannot spin fast enough to strobe`() {
        val t = toy()
        t.mode = Mode.DIAL
        sweepDial(t, turns = 6f, seconds = 0.2f, samples = 60)
        t.releaseDial()
        assertTrue(abs(t.dialOmega) <= Toy.MAX_DIAL_OMEGA + 0.001f)
        // Ribs must pass the eye slower than the screen redraws, or the knurl
        // stops turning and starts crawling backwards.
        val ribsPerSecond = Toy.MAX_DIAL_OMEGA / (2.0 * Math.PI) * Toy.DIAL_RIBS
        assertTrue("$ribsPerSecond rib passes a second", ribsPerSecond < 55.0)
    }

    @Test fun `one turn is one click per rib`() {
        val t = toy()
        t.mode = Mode.DIAL
        val before = t.dialDetent
        sweepDial(t, turns = 1f, seconds = 2f, samples = 120)
        // Where a turn starts relative to a rib decides whether the last one
        // lands inside the turn or just past it, so one either way is right.
        val clicks = t.dialDetent - before
        assertTrue("$clicks clicks for ${t.dialRibs} ribs", abs(clicks - t.dialRibs) <= 1)
    }

    @Test fun `a coasting wheel keeps clicking, and a still one does not`() {
        val t = toy()
        t.mode = Mode.DIAL
        sweepDial(t, turns = 0.4f, seconds = 0.35f)
        t.releaseDial()
        val atRelease = t.dialDetent
        run(t, 1f) { false }
        assertTrue("coasting should click", t.dialDetent > atRelease)
        assertTrue("should settle", run(t, 45f) { t.dialOmega == 0f })
        val settled = t.dialDetent
        run(t, 2f) { false }
        assertEquals("a stopped wheel must be silent", settled, t.dialDetent)
    }

    @Test fun `spinning either way clicks the same`() {
        val forward = toy().apply { mode = Mode.DIAL }
        val back = toy().apply { mode = Mode.DIAL }
        sweepDial(forward, turns = 1f, seconds = 2f, samples = 120)
        sweepDial(back, turns = -1f, seconds = 2f, samples = 120)
        assertTrue(
            "forward ${forward.dialDetent} vs back ${back.dialDetent}",
            abs(forward.dialDetent - back.dialDetent) <= 1,
        )
        assertTrue(forward.dialOmega > 0f)
        assertTrue(back.dialOmega < 0f)
    }

    // ---- canvases --------------------------------------------------------

    @Test fun `sheer is the default and the only see-through ground`() {
        val t = toy()
        assertEquals(0, t.canvasIndex)
        assertTrue(t.sheer())
        for (i in 1 until Palette.CANVAS_NAMES.size) {
            t.canvasIndex = i
            assertFalse("canvas $i should be solid", t.sheer())
            assertEquals("canvas $i must be opaque", 0xff, (t.canvasColor() ushr 24) and 0xff)
        }
    }

    @Test fun `there is a canvas named for every colour and they differ`() {
        assertEquals(Palette.CANVAS_NAMES.size, Palette.CANVAS_COLORS.size)
        val solid = Palette.CANVAS_COLORS.drop(1)
        assertEquals(solid.size, solid.toSet().size)
        fun lum(c: Int) = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) +
            0.114f * (c and 0xff)
        // Light through to dark, so there is something to draw on either way.
        assertTrue(solid.any { lum(it) > 200f })
        assertTrue(solid.any { lum(it) < 40f })
    }

    // ---- the palette drawer ----------------------------------------------

    @Test fun `every drawer row can be reached and sets what it names`() {
        val t = toy()
        val b = t.drawerBox()
        for (kind in t.drawerRows) {
            val y = t.drawerRowY(b, kind)
            val n = t.drawerRowCount(kind)
            for (chip in t.drawerChips(y, n, b)) {
                val hit = t.drawerHit(chip.x + chip.w / 2f, chip.y + chip.h / 2f)
                assertEquals("row $kind chip ${chip.i}", kind, hit)
                val got = when (kind) {
                    "alpha" -> t.inkAlphaIndex
                    "canvas" -> t.canvasIndex
                    "scrim" -> t.scrimIndex
                    else -> t.hapticIndex
                }
                assertEquals("row $kind chip ${chip.i}", chip.i, got)
            }
        }
    }

    @Test fun `the drawer still fits on screen with its new rows`() {
        for (size in listOf(Triple(1080f, 1920f, 48f), Triple(1440f, 3120f, 120f),
                            Triple(720f, 1280f, 0f))) {
            val t = Toy().apply { resize(size.first, size.second, size.third) }
            val b = t.drawerBox()
            assertTrue("off the top at $size", b.y >= 0f)
            assertTrue("under the nav bar at $size", b.y + b.h <= t.viewH - t.insetBottom)
            assertTrue("off the side at $size", b.x >= 0f && b.x + b.w <= t.w)
            // and the last row is inside the panel it is drawn in
            assertTrue("last row spills at $size", b.hy + b.rowH <= b.y + b.h)
        }
    }

    // ---- haptics ---------------------------------------------------------

    @Test fun `haptics are on by default and can be silenced`() {
        val t = toy()
        assertTrue("should arrive switched on", t.hapticScale() > 0f)
        t.hapticIndex = 0
        assertEquals("off must mean nothing at all", 0f, t.hapticScale(), 0f)
        assertEquals(Palette.HAPTIC_NAMES.size, Palette.HAPTIC_SCALES.size)
    }

    @Test fun `a wall knock is worth feeling`() {
        val t = toy()
        t.mode = Mode.BALL
        t.bx = t.w / 2f; t.by = t.h / 2f
        t.vx = 3000f; t.vy = 0f
        val before = t.bounceCount
        assertTrue("should reach the wall", run(t, 1f) { t.bounceCount > before })
        assertTrue("a hard hit should be near full strength", t.impactStrength() > 0.6f)
        assertTrue("and it should know it was a wall", t.lastImpactWall)
    }

    // ---- bumpers have their own ink --------------------------------------

    @Test fun `the factory table arrives in five different colours`() {
        val t = toy()
        val inks = t.table.map { t.bumperColor(it) }
        assertEquals(5, inks.size)
        assertEquals("each one should be its own colour", 5, inks.toSet().size)
        for (b in t.table) {
            assertTrue(b.family in Palette.COLORS.indices)
            assertTrue(b.tone in Palette.TONE_MIX.indices)
            assertEquals(0xff, (t.bumperColor(b) ushr 24) and 0xff)
        }
    }

    @Test fun `a table survives being written out and read back`() {
        val t = toy()
        t.table[0].family = 8
        t.table[0].tone = 3
        t.table[2].shape = Shape.TRIANGLE
        t.table[2].rot = 0.75f
        val back = t.decodeTable(t.encodeTable())
        assertEquals(t.table.size, back.size)
        for (i in t.table.indices) {
            assertEquals(t.table[i].family, back[i].family)
            assertEquals(t.table[i].tone, back[i].tone)
            assertEquals(t.table[i].shape, back[i].shape)
            assertEquals(t.table[i].nx, back[i].nx, 1e-6f)
            assertEquals(t.table[i].rot, back[i].rot, 1e-6f)
        }
    }

    @Test fun `tables saved before bumpers had colour still load`() {
        val t = toy()
        // five fields, the old format: no family, no tone
        val old = "0.25,0.3,0.055,CIRCLE,0.0;0.75,0.72,0.06,BAR,1.2"
        val back = t.decodeTable(old)
        assertEquals("an arrangement is not worth discarding over a new field", 2, back.size)
        assertEquals(Shape.CIRCLE, back[0].shape)
        assertEquals(Shape.BAR, back[1].shape)
        for (b in back) {
            assertEquals(0, b.family)
            assertEquals(2, b.tone)
        }
        assertEquals(0, t.decodeTable("nonsense,not,a,row").size)
    }

    // ---- editing the table, all four verbs -------------------------------

    @Test fun `the toolbar can add, delete, reshape and recolour`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        val before = t.table.size

        t.doToolbar("add")
        assertEquals(before + 1, t.table.size)
        assertEquals("the new one should be selected", t.table.size - 1, t.selected)

        val b = t.table[t.selected]
        val shape = b.shape
        t.doToolbar("shape")
        assertTrue("shape should change", b.shape != shape)

        // colour opens the whole palette rather than cycling nine families
        t.doToolbar("ink")
        assertTrue(t.drawerOpen)
        assertEquals(Toy.Target.BUMPER, t.drawerTarget)
        assertEquals(b, t.targetBumper())

        val box = t.drawerBox()
        val family = 6
        val tone = 1
        val hit = t.drawerHit(
            box.gx + box.cell * (family + 0.5f),
            box.gy + box.cell * (tone + 0.5f),
        )
        assertEquals("bumper", hit)
        assertEquals(family, b.family)
        assertEquals(tone, b.tone)
        assertEquals("and the ink itself must not have moved", 0, t.inkFamily)
        assertEquals(2, t.inkTone)

        t.closeDrawer()
        t.doToolbar("del")
        assertEquals(before, t.table.size)
        assertEquals(-1, t.selected)
        assertFalse("the drawer cannot stay pointed at a deleted bumper", t.drawerOpen)
    }

    @Test fun `the palette only follows a bumper while one is selected`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.selected = -1
        t.doToolbar("ink")
        assertFalse("nothing selected, nothing to colour", t.drawerOpen)

        // and with the drawer opened the ordinary way it still sets the ink
        t.drawerOpen = true
        val box = t.drawerBox()
        assertEquals("ink", t.drawerHit(box.gx + box.cell * 3.5f, box.gy + box.cell * 0.5f))
        assertEquals(3, t.inkFamily)
        assertEquals(0, t.inkTone)
    }

    @Test fun `the rest of the drawer stays global while a bumper is targeted`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.selected = 0
        t.doToolbar("ink")
        val box = t.drawerBox()
        for (kind in t.drawerRows) {
            val chip = t.drawerChips(t.drawerRowY(box, kind), t.drawerRowCount(kind), box)[1]
            assertEquals(kind, t.drawerHit(chip.x + chip.w / 2f, chip.y + chip.h / 2f))
        }
        assertEquals(1, t.inkAlphaIndex)
        assertEquals(1, t.canvasIndex)
        assertEquals(1, t.scrimIndex)
        assertEquals(1, t.hapticIndex)
    }

    @Test fun `every toolbar button is reachable at its own middle`() {
        val t = toy()
        for (c in t.toolbarButtons()) {
            assertEquals(
                t.toolbarLabels[c.i],
                t.toolbarHit(c.x + c.w / 2f, c.y + c.h / 2f),
            )
        }
        for (verb in listOf("add", "del", "shape", "ink")) {
            assertTrue("no way to $verb", t.toolbarLabels.contains(verb))
        }
    }

    @Test fun `a recoloured bumper still bounces the ball`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.paintOnBumpers = false
        t.table = mutableListOf(Bumper(0.5f, 0.55f, 0.12f, Shape.CIRCLE, 0f, family = 4, tone = 0))
        t.bx = t.w / 2f; t.by = t.h * 0.12f
        t.vx = 0f; t.vy = 1400f
        assertTrue("colour is paint, not physics", run(t, 3f) { t.vy < 0f })
    }

    // ---- what is free and what is bought ---------------------------------

    private fun free(): Toy = toy().apply { tier = Tier.FREE }
    private fun paid(): Toy = toy().apply { tier = Tier.FULL }

    @Test fun `the free tier is a toy, not a demo`() {
        val t = free()
        // four of the five toys play, in full
        for (m in listOf(Mode.BALL, Mode.DIAL, Mode.BUMPERS, Mode.BOLT)) {
            assertFalse("$m should be free", t.modeLocked(m))
        }
        assertTrue("paint is the paid one", t.modeLocked(Mode.PAINT))
        // every ball shape and size, and the whole bumper table, still play
        assertEquals(8, Toy.SIZES.size)
        assertEquals(6, Shape.entries.size)
        assertEquals(5, t.table.size)
        // and it still bounces
        t.mode = Mode.BUMPERS
        t.paintOnBumpers = false
        t.bx = t.w / 2f; t.by = t.h * 0.1f
        t.vx = 0f; t.vy = 1600f
        assertTrue("the free tier must actually play", run(t, 3f) { t.vy < 0f || abs(t.vx) > 40f })
    }

    @Test fun `buying unlocks exactly what the paywall promised`() {
        val t = free()
        assertTrue(t.modeLocked(Mode.PAINT))
        assertTrue(t.editLocked())
        assertTrue(t.familyLocked(Palette.NAMES.size - 1))
        assertTrue(t.canvasLocked(Palette.CANVAS_NAMES.size - 1))

        t.unlock()
        assertEquals(Tier.FULL, t.tier)
        assertFalse(t.modeLocked(Mode.PAINT))
        assertFalse(t.editLocked())
        for (i in Palette.NAMES.indices) assertFalse("family $i", t.familyLocked(i))
        for (i in Palette.CANVAS_NAMES.indices) assertFalse("canvas $i", t.canvasLocked(i))
    }

    @Test fun `the free tier keeps three colours and two grounds`() {
        val t = free()
        assertEquals(3, (0 until Palette.NAMES.size).count { !t.familyLocked(it) })
        assertEquals(2, (0 until Palette.CANVAS_NAMES.size).count { !t.canvasLocked(it) })
        // and the ones it keeps are the defaults, so nothing starts locked
        assertFalse(t.familyLocked(t.inkFamily))
        assertFalse(t.canvasLocked(t.canvasIndex))
        assertFalse(t.modeLocked(t.mode))
    }

    @Test fun `every locked control opens the paywall rather than doing nothing`() {
        // the menu
        free().let {
            it.tapMenu("paint")
            assertEquals(Screen.PAYWALL, it.screen)
        }
        // the mode row
        free().let {
            it.screen = Screen.PLAY
            it.tapMode("paint")
            assertEquals(Screen.PAYWALL, it.screen)
            assertTrue("and must not have switched anyway", it.mode != Mode.PAINT)
        }
        free().let {
            it.screen = Screen.PLAY
            it.mode = Mode.BUMPERS
            it.tapMode("edit")
            assertEquals(Screen.PAYWALL, it.screen)
            assertFalse(it.editing)
        }
        // the strip
        free().let {
            it.screen = Screen.PLAY
            val z = it.stripZones()[0]
            val step = (z.x1 - z.x0) / z.count
            it.stripTap(z.x0 + step * (Palette.NAMES.size - 0.5f))
            assertEquals(Screen.PAYWALL, it.screen)
            assertEquals("and must not have taken the colour", 0, it.inkFamily)
        }
        // the drawer grid, and the canvas row
        free().let {
            it.screen = Screen.PLAY
            it.drawerOpen = true
            val b = it.drawerBox()
            assertEquals("locked", it.drawerHit(b.gx + b.cell * 8.5f, b.gy + b.cell * 0.5f))
            assertEquals(0, it.inkFamily)
            assertEquals(Screen.PAYWALL, it.screen)
        }
        free().let {
            it.screen = Screen.PLAY
            it.drawerOpen = true
            val b = it.drawerBox()
            val chip = it.drawerChips(it.drawerRowY(b, "canvas"), Palette.CANVAS_NAMES.size, b).last()
            assertEquals("locked", it.drawerHit(chip.x + chip.w / 2f, chip.y + chip.h / 2f))
            assertEquals(0, it.canvasIndex)
            assertEquals(Screen.PAYWALL, it.screen)
        }
    }

    @Test fun `nothing locked is reachable by accident`() {
        // Double tapping mid-fidget should not ambush you with a shop.
        val t = free()
        t.screen = Screen.PLAY
        t.mode = Mode.BUMPERS
        repeat(12) {
            t.cycleMode()
            assertFalse("cycled into ${t.mode}", t.modeLocked(t.mode))
            assertEquals("and must not have opened the shop", Screen.PLAY, t.screen)
        }
    }

    @Test fun `the paywall goes back where it came from`() {
        val t = free()
        t.screen = Screen.PLAY
        t.showPaywall()
        assertEquals(Screen.PAYWALL, t.screen)
        t.showPaywall()                    // a second lock while it is already up
        t.dismissPaywall()
        assertEquals("should return to play, not to the title", Screen.PLAY, t.screen)

        val m = free()
        m.screen = Screen.TITLE
        m.tapMenu("unlock")
        assertEquals(Screen.PAYWALL, m.screen)
        m.dismissPaywall()
        assertEquals(Screen.TITLE, m.screen)
    }

    @Test fun `buying from the paywall puts you back in the toy`() {
        val t = free()
        t.screen = Screen.PLAY
        t.tapMode("paint")
        assertEquals(Screen.PAYWALL, t.screen)
        t.unlock()
        assertEquals(Screen.PLAY, t.screen)
    }

    @Test fun `a refund takes back what it paid for`() {
        val t = paid()
        t.mode = Mode.PAINT
        t.inkFamily = Palette.NAMES.size - 1
        t.canvasIndex = Palette.CANVAS_NAMES.size - 1
        t.editing = true
        t.selected = 1

        t.tier = Tier.FREE
        t.clampToTier()
        assertFalse("paint is not theirs any more", t.modeLocked(t.mode) )
        assertEquals(Mode.BALL, t.mode)
        assertFalse(t.familyLocked(t.inkFamily))
        assertFalse(t.canvasLocked(t.canvasIndex))
        assertFalse(t.editing)
        // but the table they built keeps its shipped colours
        assertEquals(5, t.table.size)
        assertTrue(t.table.map { it.family }.toSet().size > 1)
    }

    @Test fun `the front door names the price instead of hiding it`() {
        val t = free()
        val keys = t.menuItems().map { it.key }
        assertTrue("the free tier should be told what it is missing", keys.contains("unlock"))
        assertTrue(t.menuLocked("paint"))
        for (c in t.menuRows()) {
            assertEquals(t.menuItems()[c.i].key, t.menuHit(c.x + c.w / 2f, c.y + c.h / 2f))
        }
        val paidKeys = paid().menuItems().map { it.key }
        assertFalse("and not nagged once they have paid", paidKeys.contains("unlock"))
        assertFalse(paid().menuLocked("paint"))
    }

    @Test fun `the paywall fits on screen and every button answers`() {
        for (size in listOf(Triple(1080f, 1920f, 48f), Triple(1440f, 3120f, 120f),
                            Triple(720f, 1280f, 0f))) {
            val t = Toy().apply { resize(size.first, size.second, size.third) }
            val buttons = t.paywallButtons()
            assertEquals(3, buttons.size)
            val floorY = t.viewH - t.insetBottom
            for (c in buttons) {
                assertTrue("under the nav bar at $size", c.y + c.h <= floorY)
                assertTrue("off the side at $size", c.x >= 0f && c.x + c.w <= t.w)
                assertEquals(t.paywallLabels[c.i], t.paywallHit(c.x + c.w / 2f, c.y + c.h / 2f))
            }
            for (i in 1 until buttons.size) {
                assertTrue("buttons overlap at $size", buttons[i].y >= buttons[i - 1].y + buttons[i - 1].h)
            }
            assertTrue("must clear the promises above it at $size", buttons[0].y > t.viewH * 0.55f)
        }
    }

    @Test fun `the unlock button says the price once the store has one`() {
        val t = free()
        assertEquals("unlock", t.unlockLabel())
        t.priceText = "\u00a32.99"
        assertTrue(t.unlockLabel().contains("\u00a32.99"))
        assertEquals(5, t.paywallLines().size)
    }

    // ---- lightning -------------------------------------------------------

    private fun struck(t: Toy = toy()): Toy {
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 1800f, -900f)
        return t
    }

    @Test fun `a flick strikes and a nudge does not`() {
        val t = toy()
        t.mode = Mode.BOLT
        assertFalse("a slow drag is not a strike", t.fireBolt(100f, 100f, 60f, 40f))
        assertEquals(0, t.bolts.size)
        assertTrue(t.fireBolt(100f, 100f, 1500f, 0f))
        assertEquals(1, t.bolts.size)
    }

    @Test fun `a bolt leaves faster than the finger, but not without limit`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(100f, 100f, 1000f, 0f)
        assertTrue("should outrun the flick", t.bolts[0].vx > 1000f)

        val fast = toy()
        fast.mode = Mode.BOLT
        fast.fireBolt(100f, 100f, 40000f, 30000f)
        assertTrue(
            "capped, or it crosses the field between frames",
            hypot(fast.bolts[0].vx, fast.bolts[0].vy) <= Toy.BOLT_MAX_SPEED + 1f,
        )
    }

    @Test fun `a bolt hitting a wall registers an impact to feel`() {
        val t = toy()
        t.mode = Mode.BOLT
        val before = t.bounceCount
        t.fireBolt(t.w / 2f, t.h / 2f, 3000f, 0f)     // straight at the right wall
        assertTrue("should reach the wall", run(t, 1f) { t.bounceCount > before })
        assertTrue("and it must read as a wall", t.lastImpactWall)
        assertTrue("hard enough to be worth feeling", t.impactStrength() > 0.5f)
    }

    @Test fun `no bolt ever leaves the field`() {
        val t = toy()
        t.mode = Mode.BOLT
        for (a in 0 until 12) {
            val ang = a * Math.PI.toFloat() / 6f
            t.fireBolt(t.w / 2f, t.h / 2f, cos(ang) * 6000f, sin(ang) * 6000f)
        }
        var worst = 0f
        repeat(140) {
            t.step(dt)
            for (b in t.bolts) {
                val out = maxOf(-b.x, b.x - t.w, -b.y, b.y - t.h)
                if (out > worst) worst = out
                for (n in b.nodes) {
                    val o = maxOf(-n[0], n[0] - t.w, -n[1], n[1] - t.h)
                    if (o > worst) worst = o
                }
            }
        }
        assertTrue("escaped by $worst", worst < 2f)
    }

    @Test fun `bolts burn out, and there is a limit on how many can burn at once`() {
        val t = struck()
        assertEquals(1, t.bolts.size)
        assertTrue("should fade", run(t, 4f) { t.bolts.isEmpty() })

        val many = toy()
        many.mode = Mode.BOLT
        repeat(Toy.MAX_BOLTS * 3) { many.fireBolt(500f, 500f, 2000f, 500f) }
        assertEquals(Toy.MAX_BOLTS, many.bolts.size)
    }

    @Test fun `the zigzag is laid down once and never moves again`() {
        // A bolt redrawn from fresh randomness every frame is television
        // static, so the shape has to be part of the simulation.
        val a = struck()
        val b = struck()
        run(a, 0.4f) { false }
        run(b, 0.4f) { false }
        assertEquals(a.bolts.size, b.bolts.size)
        val na = a.bolts[0].nodes
        val nb = b.bolts[0].nodes
        assertEquals("the same strike must draw the same bolt", na.size, nb.size)
        for (i in na.indices) {
            assertEquals(na[i][0], nb[i][0], 1e-4f)
            assertEquals(na[i][1], nb[i][1], 1e-4f)
        }

        // And once a node is down, later frames must not move it. The window
        // rolls, so the kinks that survive have slid toward the front of the
        // list: line the two up on the newest node they share rather than on
        // index, which is what a rolling window quietly breaks.
        val snapshot = na.map { it.copyOf() }
        run(a, 0.3f) { false }
        val now = a.bolts[0].nodes
        val last = snapshot.last()
        val j = now.indexOfFirst { abs(it[0] - last[0]) < 1e-4f && abs(it[1] - last[1]) < 1e-4f }
        assertTrue("the newest kink at the snapshot should still be on the bolt", j >= 0)
        var k = 0
        while (k <= j && k < snapshot.size) {
            assertEquals(snapshot[snapshot.size - 1 - k][0], now[j - k][0], 1e-4f)
            assertEquals(snapshot[snapshot.size - 1 - k][1], now[j - k][1], 1e-4f)
            k++
        }
        assertTrue("too little overlap to prove anything", k > 4)
    }

    @Test fun `the zigzag actually zigzags`() {
        val t = struck()
        run(t, 0.3f) { false }
        val nodes = t.bolts[0].nodes
        assertTrue("too few kinks to be lightning: ${nodes.size}", nodes.size > 6)
        // Straight travel with no jag would leave every node on one line.
        var offLine = 0
        for (i in 2 until nodes.size) {
            val (ax, ay) = nodes[i - 2][0] to nodes[i - 2][1]
            val (bx, by) = nodes[i - 1][0] to nodes[i - 1][1]
            val (cx, cy) = nodes[i][0] to nodes[i][1]
            val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(cross) > 1f) offLine++
        }
        assertTrue("a straight line is not lightning", offLine > (nodes.size - 2) / 2)
    }

    @Test fun `the throw keeps alternating after the memory fills`() {
        // The side used to be read off the node count, which stops changing
        // the moment the rolling window is full. From there every kink threw
        // the same way, and the zigzag straightened into a smooth arc — a bug
        // no other test could see, because every node was still off-line.
        val t = Toy().apply { resize(400f, 4000f, 0f); screen = Screen.PLAY; tier = Tier.FULL }
        t.mode = Mode.BOLT
        t.fireBolt(200f, 40f, 0f, 500f)     // straight down: no wall to reach
        run(t, 1f) { false }
        assertTrue("the bolt should still be alive", t.bolts.isNotEmpty())
        val nodes = t.bolts[0].nodes
        assertEquals("the window should be full", Toy.BOLT_MAX_NODES, nodes.size)
        // Travel is straight down, so a node's x is its throw.
        var sign = 0
        var alternations = 0
        for (n in nodes) {
            val s = if (n[0] > 200f) 1 else -1
            if (sign != 0 && s != sign) alternations++
            sign = s
        }
        assertEquals("every kink should throw the other way",
                     nodes.size - 1, alternations)
    }

    @Test fun `a bolt cannot outlive its own memory`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 4000f, 3000f)
        run(t, 3f) { false }
        for (b in t.bolts) assertTrue(b.nodes.size <= Toy.BOLT_MAX_NODES)
    }

    @Test fun `lightning is free, and named on the front door`() {
        val t = free()
        assertFalse("the free tier should get the new toy", t.modeLocked(Mode.BOLT))
        assertTrue(t.menuItems().map { it.key }.contains("bolt"))
        t.screen = Screen.TITLE
        assertTrue(t.tapMenu("bolt"))
        assertEquals(Mode.BOLT, t.mode)
        assertEquals(Screen.PLAY, t.screen)
        // and reachable from the row as well
        val p = toy()
        p.mode = Mode.BALL
        assertTrue(p.modeLabels().contains("bolt"))
        p.tapMode("bolt")
        assertEquals(Mode.BOLT, p.mode)
    }

    @Test fun `nothing else moves while the lightning does`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.bx = 300f; t.by = 400f; t.vx = 1500f; t.vy = 800f
        struck(t)
        run(t, 0.5f) { false }
        assertEquals("the ball is not in this toy", 300f, t.bx, 0.001f)
        assertEquals(400f, t.by, 0.001f)
    }
}
