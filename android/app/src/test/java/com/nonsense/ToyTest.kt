package com.nonsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test fun `palette is fourteen families of four tones, all distinct`() {
        assertEquals(14, Palette.NAMES.size)
        assertEquals(Palette.NAMES.size, Palette.COLORS.size)
        val flat = Palette.COLORS.flatMap { it.toList() }
        assertEquals(56, flat.size)
        assertEquals("two inks came out the same colour", 56, flat.toSet().size)
    }

    @Test fun `the first nine families keep their places`() {
        // A saved ink is an index. Appending is the only change that does not
        // quietly repaint a bumper table somebody built.
        val original = listOf(
            "graphite", "bone", "oxblood", "rust", "ochre", "moss", "teal", "slate", "plum",
        )
        assertEquals(original, Palette.NAMES.take(original.size))
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
        // shape opens the sheet; picking off it is what changes the bumper
        val shapeBefore = t.table[t.selected].shape
        t.doToolbar("shape")
        assertTrue(t.glyphOpen)
        val other = t.glyphCells().first { t.glyphShapeAt(it.i) != shapeBefore }
        t.glyphHit(other.x + other.w / 2f, other.y + other.h / 2f)
        assertTrue(t.table[t.selected].shape != shapeBefore ||
            t.table[t.selected].glyph.isNotEmpty())
        t.closeGlyphs()
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

    @Test fun `slate is the default, and sheer the only see-through ground`() {
        val t = toy()
        // Slate rather than sheer: the see-through window is what the Android
        // build is for, but it is not what most of the toys look best on.
        assertEquals(Toy.DEFAULT_CANVAS, t.canvasIndex)
        assertFalse("the app should not open see-through", t.sheer())
        t.canvasIndex = 0
        assertTrue("but sheer is still there", t.sheer())
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
                    "haptic" -> t.hapticIndex
                    else -> t.voiceIndex
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
            assertTrue("last row spills at $size", b.vy + b.rowH <= b.y + b.h)
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

    // ---- bumpers are on the same palette as everything else --------------

    @Test fun `the factory table has its own colour and keeps it`() {
        val t = toy()
        assertEquals(5, t.table.size)
        for (b in t.table) {
            assertTrue("a bumper owns its colour", b.family in Palette.NAMES.indices)
            assertTrue("and a free one at that", !t.familyLocked(b.family))
            assertTrue(b.tone in Palette.TONE_MIX.indices)
            assertEquals(0xff, (t.bumperColor(b) ushr 24) and 0xff)
        }
        // one family, but not one flat colour: the tones are spread so the
        // pieces still read as separate things
        assertTrue("a table in one shade is a wall",
            t.table.map { t.bumperColor(it) }.toSet().size >= 3)

        // and the table stays put when the ink moves
        t.inkFamily = 0
        val before = t.table.map { t.bumperColor(it) }
        t.inkFamily = 6
        assertEquals("the paint is not the table's business",
            before, t.table.map { t.bumperColor(it) })
    }

    @Test fun `a bumper given a colour keeps it`() {
        val t = toy()
        t.editing = true
        t.selected = 0
        t.mode = Mode.BUMPERS
        t.doToolbar("ink")
        assertTrue(t.drawerOpen)

        // pick oxblood out of the grid for this one bumper
        val box = t.drawerBox()
        val cell = box.cell
        val px = box.gx + cell * 2 + cell / 2
        val py = box.gy + cell * 1 + cell / 2
        assertEquals("bumper", t.drawerHit(px, py))
        assertEquals(2, t.table[0].family)
        assertEquals(1, t.table[0].tone)

        // it no longer follows: the ink moves, it does not
        t.inkFamily = 9
        assertEquals(Palette.COLORS[2][1], t.bumperColor(t.table[0]))
        assertEquals("and so do the ones nobody touched",
            Palette.COLORS[t.table[1].family][t.table[1].tone], t.bumperColor(t.table[1]))

        // and painting in something else does not drag the table with it
        t.inkFamily = 5
        assertEquals("a bumper does not follow the paint",
            Palette.COLORS[2][1], t.bumperColor(t.table[0]))
    }

    @Test fun `every toy paints out of the same pot`() {
        val t = toy()
        t.inkFamily = 5
        t.inkTone = 1
        val ink = t.inkColor()
        // the ball and the paint are the ink itself
        assertEquals(ink, t.inkColor())
        // lightning keeps the ink it was thrown in
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 1800f, -900f)
        assertEquals("a bolt is thrown in the ink", ink, t.bolts.first().argb)
        // glass breaks in it too
        t.mode = Mode.GLASS
        assertTrue(t.breakGlass(400f, 500f))
        assertEquals("and glass breaks in it", ink, t.breaks.last().argb)
        // The table is the exception, and deliberately: it is set from the
        // same palette but holds what it is given, so painting in a new
        // colour does not repaint the bumpers you arranged.
        for (b in t.table) {
            assertEquals(Palette.COLORS[b.family][b.tone], t.bumperColor(b))
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
            assertEquals("a row from before colours existed takes the ink it looked like",
                t.inkFamily, b.family)
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
        // shape opens the whole set rather than stepping one along, for the
        // same reason ink opens the palette
        t.doToolbar("shape")
        assertTrue(t.glyphOpen)
        val cell = t.glyphCells()[Shape.entries.size]      // the first letter
        assertEquals("pick", t.glyphHit(cell.x + cell.w / 2f, cell.y + cell.h / 2f))
        assertEquals("A", b.glyph)
        assertEquals(shape, b.shape)                       // the glyph is what shows
        t.closeGlyphs()

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

    @Test fun `the ball is the free toy, and every other one is the unlock`() {
        val t = free()
        assertFalse("the ball is the free one", t.modeLocked(Mode.BALL))
        for (m in listOf(Mode.DIAL, Mode.BUMPERS, Mode.BOLT, Mode.PAINT)) {
            assertTrue("$m should be paid", t.modeLocked(m))
        }
        // The free ball is the whole ball, not a sample of it: every size,
        // every shape, catching, and three of the nine ink families.
        assertEquals(8, Toy.SIZES.size)
        assertEquals(6, Shape.entries.size)
        assertFalse(t.familyLocked(0))
        // and it still plays
        t.mode = Mode.BALL
        t.bx = t.w / 2f; t.by = t.h * 0.1f
        t.vx = 0f; t.vy = 1600f
        assertTrue("the free tier must actually play", run(t, 3f) { t.vy < 0f || abs(t.vx) > 40f })
    }

    @Test fun `every way into a locked toy opens the shop instead`() {
        // There are three doors into a toy — the front door, the mode row and
        // the cycle gesture — and a gate on two of them is a hole.
        for (key in listOf("dial", "bumpers", "bolt", "paint")) {
            val menu = free()
            menu.screen = Screen.TITLE
            assertTrue(menu.tapMenu(key))
            assertEquals("$key from the front door", Screen.PAYWALL, menu.screen)
            assertEquals(Mode.BALL, menu.mode)

            val row = free()
            row.tapMode(key)
            assertEquals("$key from the row", Screen.PAYWALL, row.screen)
            assertEquals(Mode.BALL, row.mode)
            assertTrue("$key should wear a padlock", row.menuLocked(key))
        }
        // The gesture never ambushes you with a shop: it just stays put.
        val t = free()
        repeat(7) { t.cycleMode() }
        assertEquals(Mode.BALL, t.mode)
        assertEquals(Screen.PLAY, t.screen)
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

    @Test fun `the free tier keeps three colours and three grounds`() {
        val t = free()
        assertEquals(3, (0 until Palette.NAMES.size).count { !t.familyLocked(it) })
        // Sheer, paper, and the slate the app opens on: a default nobody can
        // use is not a default.
        assertEquals(3, (0 until Palette.CANVAS_NAMES.size).count { !t.canvasLocked(it) })
        assertFalse("the ground it opens on", t.canvasLocked(Toy.DEFAULT_CANVAS))
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
            assertEquals("and must not have taken the ground", Toy.DEFAULT_CANVAS, it.canvasIndex)
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

    @Test fun `the code unlocks, and a wrong one does not`() {
        val t = free()
        t.showPaywall()
        assertFalse("the keypad is not up until it is asked for", t.codeOpen)
        t.openCode()
        assertTrue(t.codeOpen)

        // wrong, and it says so rather than silently doing nothing
        for (c in "0000") t.typeCode(c.toString())
        assertTrue("a wrong code should be visible as wrong", t.codeWrong)
        assertEquals("and it clears itself to try again", "", t.codeEntry)
        assertEquals(Tier.FREE, t.tier)
        assertTrue("still on the keypad", t.codeOpen)

        // right
        for (c in "1836") t.typeCode(c.toString())
        assertEquals(Tier.FULL, t.tier)
        assertFalse("and the keypad puts itself away", t.codeOpen)
        assertEquals(Screen.PLAY, t.screen)
        for (m in Mode.entries) assertFalse("$m", t.modeLocked(m))
    }

    @Test fun `the code is only ever stored as a hash`() {
        // Obfuscation rather than security, but reading the source should not
        // hand the code over.
        val src = Toy.CODE_HASH.toString()
        assertNotEquals("1836", src)
        assertEquals(Toy.CODE_HASH, Toy.codeHash("1836"))
        assertNotEquals(Toy.CODE_HASH, Toy.codeHash("1837"))
        assertNotEquals(Toy.CODE_HASH, Toy.codeHash(""))
        assertEquals("four digits", 4, Toy.CODE_LENGTH)
    }

    @Test fun `the keypad has a key under every digit, and fits the screen`() {
        val t = free()
        t.showPaywall()
        t.openCode()
        val keys = t.keypadKeys()
        val cells = t.keypadCells()
        assertEquals(keys.size, cells.size)
        for (c in cells) {
            if (keys[c.i].isEmpty()) continue
            assertEquals(keys[c.i], t.keypadHit(c.x + c.w / 2f, c.y + c.h / 2f))
        }
        assertNull("the blank is not a button",
                   t.keypadHit(cells[9].x + cells[9].w / 2f, cells[9].y + cells[9].h / 2f))
        val last = cells.last()
        assertTrue("the keypad runs off the bottom", last.y + last.h <= t.viewH)
        assertTrue("or off the top", cells.first().y > t.viewH * 0.3f)
    }

    @Test fun `backspace takes a digit off rather than starting over`() {
        val t = free()
        t.showPaywall()
        t.openCode()
        t.typeCode("1"); t.typeCode("8"); t.typeCode("9")
        assertEquals("189", t.codeEntry)
        t.typeCode("del")
        assertEquals("18", t.codeEntry)
        t.typeCode("3"); t.typeCode("6")
        assertEquals(Tier.FULL, t.tier)
    }

    @Test fun `the paywall says what it costs and that it renews`() {
        // Apple rejects a subscription paywall that leaves the terms to the
        // store sheet, and it is the honest thing to do anyway.
        val t = free()
        val terms = t.subscriptionTerms().joinToString(" ").lowercase()
        assertTrue("no price on the screen that sells it", terms.contains("1.99"))
        assertTrue("does not say how often", terms.contains("month"))
        assertTrue("does not say it renews", terms.contains("renew"))
        assertTrue("does not say how to stop", terms.contains("cancel"))

        t.priceText = "£1.79"
        assertTrue("the store's own price should win when it arrives",
                   t.subscriptionTerms().first().contains("£1.79"))
        assertTrue(t.unlockLabel().contains("£1.79"))
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
        // the table they built survives, and because it follows the ink it
        // cannot be left showing a colour they no longer own
        assertEquals(5, t.table.size)
        for (b in t.table) {
            assertTrue("a refunded table must not wear a paid colour",
                !t.familyLocked(b.family))
        }
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

    @Test fun `the subscribe button says the price, before and after the store`() {
        val t = free()
        // A button that says nothing while the store connects is a button
        // nobody presses, so it quotes the list price until it is corrected.
        assertTrue("should stand in with the list price",
                   t.unlockLabel().contains(Toy.PRICE_FALLBACK))
        assertTrue("and say how often", t.unlockLabel().contains("/mo"))
        t.priceText = "\u00a32.99"
        assertTrue(t.unlockLabel().contains("\u00a32.99"))
        assertEquals(5, t.paywallLines().size)
        assertFalse("the promises must not still say it is bought once",
                    t.paywallLines().any { it.contains("bought once") })
    }

    // ---- lightning -------------------------------------------------------

    private fun struck(t: Toy = toy()): Toy {
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 1800f, -900f)
        return t
    }

    /** Runs until every bolt has arrived, then hands back what it etched. */
    private fun arrived(t: Toy): List<Etched> {
        run(t, 4f) { t.bolts.isEmpty() && t.etched.isNotEmpty() }
        return t.etched
    }

    /**
     * The longest of the arms the finger threw, as opposed to the forks that
     * came off them. A strike is a fan now, so "the root" is several paths and
     * the interesting one is whichever had room to run.
     */
    private fun root(t: Toy): Etched =
        t.etched.filter { it.gen == 0 }.maxByOrNull { it.nodes.size }!!

    /** The etching that began where this bolt did, matched on its first kink. */
    private fun etchingOf(t: Toy, start: FloatArray): Etched =
        t.etched.first {
            it.nodes.size > 1 &&
                abs(it.nodes[1][0] - start[0]) < 1e-4f && abs(it.nodes[1][1] - start[1]) < 1e-4f
        }

    @Test fun `a flick strikes and a nudge does not`() {
        val t = toy()
        t.mode = Mode.BOLT
        assertFalse("a slow drag is not a strike", t.fireBolt(100f, 100f, 60f, 40f))
        assertEquals(0, t.bolts.size)
        assertTrue(t.fireBolt(100f, 100f, 1500f, 0f))
        // A strike is a fan, not a line: several arms leave at once.
        assertEquals(Toy.boltArms(1500f), t.bolts.size)
        assertTrue("even the gentlest strike should fan", t.bolts.size >= Toy.BOLT_ARMS_MIN)
    }

    @Test fun `the harder you flick, the more arms and the more knocks`() {
        // Every arm that reaches a wall is its own impact, so a hard throw is
        // felt as a volley rather than a single tap.
        assertEquals(Toy.BOLT_ARMS_MIN, Toy.boltArms(Toy.BOLT_MIN_SPEED))
        assertEquals(Toy.BOLT_ARMS_MAX, Toy.boltArms(Toy.BOLT_ARMS_FULL * 2f))
        assertTrue(Toy.boltArms(2500f) > Toy.boltArms(700f))

        fun knocks(flick: Float): Int {
            val t = toy()
            t.mode = Mode.BOLT
            val before = t.bounceCount
            t.fireBolt(t.w / 2f, t.h / 2f, flick, -flick * 0.4f)
            run(t, 4f) { t.bolts.isEmpty() }
            return t.bounceCount - before
        }
        val soft = knocks(700f)
        val hard = knocks(5200f)
        assertTrue("a hard flick should be felt more: $soft then $hard", hard > soft)
    }

    @Test fun `a strike thrown at a wall leans away from it`() {
        // Thrown outward from a corner, a single line met the wall in a tenth
        // of the screen and died there, so lightning only ever looked like
        // lightning from the middle of the field.
        val t = toy()
        t.mode = Mode.BOLT
        val px = t.w * 0.1f
        val py = t.h * 0.12f
        t.fireBolt(px, py, -1600f, -1600f)      // hard into the top-left corner
        run(t, 4f) { t.bolts.isEmpty() }
        var far = 0f
        for (e in t.etched) for (n in e.nodes) far = maxOf(far, hypot(n[0] - px, n[1] - py))
        val diag = hypot(t.w, t.h)
        assertTrue("a corner strike went nowhere: ${far / diag}", far > diag * 0.35f)

        // and a throw from the middle is not bent away from where it was aimed
        val mid = toy()
        mid.mode = Mode.BOLT
        val aim = mid.boltAim(mid.w / 2f, mid.h / 2f, 1000f, 0f)
        assertEquals("nothing to lean away from here", 0f, aim, 1e-3f)
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
            // and nothing it leaves behind, either
            for (e in t.etched) for (n in e.nodes) {
                val o = maxOf(-n[0], n[0] - t.w, -n[1], n[1] - t.h)
                if (o > worst) worst = o
            }
        }
        assertTrue("escaped by $worst", worst < 2f)
    }

    @Test fun `bolts burn out, and there is a limit on how many can burn at once`() {
        val t = struck()
        assertTrue(t.bolts.isNotEmpty())
        assertTrue("should fade", run(t, 4f) { t.bolts.isEmpty() })

        val many = toy()
        many.mode = Mode.BOLT
        repeat(Toy.MAX_BOLTS * 3) { many.fireBolt(500f, 500f, 2000f, 500f) }
        assertEquals(Toy.MAX_BOLTS, many.bolts.size)
    }

    @Test fun `the zigzag is laid down once and never moves again`() {
        // A bolt redrawn from fresh randomness every frame is television
        // static, so the shape has to be part of the simulation. Two identical
        // strikes must etch identical scenes, fork for fork.
        val a = arrived(struck())
        val b = arrived(struck())
        assertEquals("the same strike must etch the same scene", a.size, b.size)
        for (e in a.indices) {
            val na = a[e].nodes
            val nb = b[e].nodes
            assertEquals("etching $e", na.size, nb.size)
            for (i in na.indices) {
                assertEquals(na[i][0], nb[i][0], 1e-4f)
                assertEquals(na[i][1], nb[i][1], 1e-4f)
            }
        }

        // And once a kink is down, later frames must not move it: what is
        // etched at the end has to still contain what was laid mid-flight.
        val t = struck()
        run(t, 0.06f) { false }
        val snapshot = t.bolts[0].nodes.map { it.copyOf() }
        assertTrue("nothing was laid down to check: ${snapshot.size}", snapshot.size > 3)
        arrived(t)
        // A strike is a fan, so find the etching this particular arm became,
        // by the first kink it laid down rather than by its place in the list.
        val mine = etchingOf(t, snapshot[1])
        for (i in snapshot.indices) {
            assertEquals(snapshot[i][0], mine.nodes[i][0], 1e-4f)
            assertEquals(snapshot[i][1], mine.nodes[i][1], 1e-4f)
        }
    }

    @Test fun `the zigzag actually zigzags`() {
        val t = struck()
        arrived(t)
        val nodes = root(t).nodes
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
        t.fireBolt(200f, 40f, 0f, 2500f)    // straight down a very tall field
        arrived(t)
        val nodes = root(t).nodes
        assertEquals("the window should be full", Toy.BOLT_MAX_NODES, nodes.size)
        // Travel is straight down, so a node's x is its throw. The last one
        // is where it met the wall — an exact position rather than a throw —
        // so it is not part of the alternation.
        var sign = 0
        var alternations = 0
        for (i in 0 until nodes.size - 1) {
            val s = if (nodes[i][0] > 200f) 1 else -1
            if (sign != 0 && s != sign) alternations++
            sign = s
        }
        assertEquals("every kink should throw the other way",
                     nodes.size - 2, alternations)
    }

    @Test fun `a bolt cannot outlive its own memory`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 4000f, 3000f)
        run(t, 3f) { false }
        for (b in t.bolts) assertTrue(b.nodes.size <= Toy.BOLT_MAX_NODES)
        for (e in t.etched) assertTrue(e.nodes.size <= Toy.BOLT_MAX_NODES)
    }

    @Test fun `a strike spreads on its way`() {
        // One flick, several paths: a bolt that only ever drew one line is a
        // wire. Forks come off it, and a fork can fork once itself.
        val t = struck()
        val most = mutableListOf<Int>()
        run(t, 4f) { most.add(t.bolts.size); t.bolts.isEmpty() }
        assertTrue("one flick should have thrown forks", most.max() > 1)
        assertTrue("and every one of them lands", t.etched.size > 1)
        // A fork is thrown off the path rather than laid along it.
        val root = root(t)
        val fork = t.etched.first { it !== root }
        assertTrue("a fork should leave at an angle", fork.nodes.size >= 2)
    }

    @Test fun `a bolt stops at the wall it reaches, and stays there`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 3000f, 0f)     // straight at the right wall
        assertTrue("should arrive", run(t, 2f) { t.etched.isNotEmpty() })
        val end = root(t).nodes.last()
        assertTrue("it should end on a wall, not in the middle: ${end[0]}",
                   end[0] >= t.w - 1f)
        // and nothing is still flying once the glow is spent
        assertTrue("the strike should stop, not pinball", run(t, 3f) { t.bolts.isEmpty() })
        assertTrue("but the scene keeps it", t.etched.isNotEmpty())
    }

    @Test fun `an etching keeps the ink it was thrown in`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.inkFamily = 2
        val first = t.inkColor()
        t.fireBolt(t.w / 2f, t.h / 2f, 3000f, 0f)
        assertTrue(run(t, 2f) { t.bolts.isEmpty() })
        t.inkFamily = 6
        val second = t.inkColor()
        assertTrue("a different ink to prove anything with", first != second)
        t.fireBolt(t.w / 2f, t.h / 2f, -3000f, 0f)
        assertTrue(run(t, 2f) { t.bolts.isEmpty() })

        val inks = t.etched.map { it.argb }.toSet()
        assertTrue("both inks should be on the scene", inks.contains(first))
        assertTrue("and the later strike must not repaint the earlier one",
                   inks.contains(second))
    }

    @Test fun `the scene can be wiped, and cannot grow without limit`() {
        val t = toy()
        t.mode = Mode.BOLT
        repeat(60) {
            t.fireBolt(t.w / 2f, t.h / 2f, 2600f, 1300f)
            run(t, 1f) { t.bolts.isEmpty() }
        }
        assertTrue("something should have been etched", t.etched.isNotEmpty())
        assertTrue("and it is capped: ${t.etched.size}", t.etched.size <= Toy.MAX_ETCHED)
        t.clearEtched()
        assertTrue("a wipe should leave a blank scene", t.etched.isEmpty())
    }

    @Test fun `lightning offers the palette and nothing else`() {
        val t = toy()
        t.mode = Mode.BOLT
        val z = t.stripZones()
        assertEquals("one zone, and it is the ink", 1, z.size)
        assertEquals("color", z[0].kind)
        assertEquals("across the whole width", t.w, z[0].x1, 0.01f)
        // and tapping it still picks a colour
        val step = t.w / z[0].count
        t.stripTap(step * 4.5f)
        assertEquals(4, t.inkFamily)

        t.mode = Mode.PAINT
        assertEquals("paint still gets sizes and shapes too", 3, t.stripZones().size)
    }

    // ---- glass -----------------------------------------------------------

    @Test fun `a press breaks the pane where it was pressed`() {
        val t = toy()
        t.mode = Mode.GLASS
        assertTrue(t.breaks.isEmpty())
        assertTrue(t.breakGlass(t.w * 0.4f, t.h * 0.3f))
        assertEquals(1, t.breaks.size)
        val b = t.breaks[0]
        assertEquals(t.w * 0.4f, b.x, 0.01f)
        assertEquals(t.h * 0.3f, b.y, 0.01f)
        // radials out of the impact, and rings around it
        val radials = b.cracks.count { !it.ring }
        val rings = b.cracks.count { it.ring }
        assertTrue("too few fractures: $radials", radials >= Toy.GLASS_RADIALS_MIN)
        assertTrue(radials <= Toy.GLASS_RADIALS_MAX)
        assertTrue("no rings", rings >= Toy.GLASS_RINGS_MIN)
        assertTrue(rings <= Toy.GLASS_RINGS_MAX)
        // every radial starts at the impact
        for (c in b.cracks.filter { !it.ring }) {
            assertEquals(b.x, c.nodes.first()[0], 0.01f)
            assertEquals(b.y, c.nodes.first()[1], 0.01f)
            assertTrue("a crack that goes nowhere", c.nodes.size > 2)
        }
    }

    @Test fun `no crack leaves the pane`() {
        val t = toy()
        t.mode = Mode.GLASS
        // corners and edges, where a straight run would overshoot furthest
        for (p in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0f, 0.02f to 0.5f)) {
            t.breakGlass(t.w * p.first, t.h * p.second)
        }
        var worst = 0f
        for (b in t.breaks) for (c in b.cracks) for (n in c.nodes) {
            worst = maxOf(worst, -n[0], n[0] - t.w, -n[1], n[1] - t.h)
        }
        assertTrue("a crack ran off the pane by $worst", worst < 0.01f)
    }

    @Test fun `a break keeps the ink it was made in, and is felt`() {
        val t = toy()
        t.mode = Mode.GLASS
        t.inkFamily = 3
        val first = t.inkColor()
        val before = t.bounceCount
        t.breakGlass(t.w / 2f, t.h / 2f)
        assertEquals("the pane going should be felt", before + 1, t.bounceCount)
        assertTrue("and felt as a flat knock", t.lastImpactWall)
        assertTrue(t.impactStrength() > 0.5f)

        t.inkFamily = 9
        val second = t.inkColor()
        t.breakGlass(t.w / 3f, t.h / 3f)
        assertEquals(first, t.breaks[0].argb)
        assertEquals("a later press must not recolour an earlier break",
                     second, t.breaks[1].argb)
    }

    @Test fun `the pane can be swept up, and cannot grow without limit`() {
        val t = toy()
        t.mode = Mode.GLASS
        repeat(Toy.MAX_BREAKS * 3) { t.breakGlass(t.w * 0.5f, t.h * 0.5f) }
        assertEquals(Toy.MAX_BREAKS, t.breaks.size)
        t.clearGlass()
        assertTrue(t.breaks.isEmpty())
    }

    @Test fun `glass is named on the front door and offers the palette`() {
        val t = toy()
        assertTrue(t.menuItems().map { it.key }.contains("glass"))
        assertEquals(Mode.GLASS, t.modeNamed("glass"))
        t.screen = Screen.TITLE
        assertTrue(t.tapMenu("glass"))
        assertEquals(Mode.GLASS, t.mode)
        assertTrue(t.modeLabels().contains("glass"))

        val z = t.stripZones()
        assertEquals("one zone, and it is the ink", 1, z.size)
        assertEquals("color", z[0].kind)

        // and it is behind the subscription, like every toy but the ball
        val f = free()
        assertTrue(f.modeLocked(Mode.GLASS))
        assertTrue(f.menuLocked("glass"))
    }

    @Test fun `the front door still fits with a seventh toy on it`() {
        // Seven rows and the unlock makes eight; at a fixed height they ran
        // off the bottom of a phone.
        val t = free()
        t.screen = Screen.TITLE
        val rows = t.menuRows()
        assertEquals(8, rows.size)
        for (c in rows) assertTrue("row ${c.i} runs off the bottom", c.y + c.h <= t.viewH)
    }

    @Test fun `lightning is named on the front door, and opens once bought`() {
        val t = free()
        assertTrue("named whether or not you have paid",
                   t.menuItems().map { it.key }.contains("bolt"))
        assertTrue("and it wears a padlock until you do", t.menuLocked("bolt"))

        val p = paid()
        p.screen = Screen.TITLE
        assertTrue(p.tapMenu("bolt"))
        assertEquals(Mode.BOLT, p.mode)
        assertEquals(Screen.PLAY, p.screen)
        // and reachable from the row as well
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

    // ---- letters and digits, pulled and stretched ------------------------

    @Test fun `every glyph is a set of strokes that stay on the lattice`() {
        assertEquals("A to Z, then 0 to 9", Letters.ALPHABET + Letters.DIGITS, Letters.GLYPHS)
        assertEquals("one glyph per entry", Letters.GLYPHS.length, Letters.LINES.split("/").size)
        for (ch in Letters.GLYPHS) {
            val strokes = Letters.strokes(ch)
            assertTrue("$ch has no strokes", strokes.isNotEmpty())
            var segments = 0
            for (line in strokes) {
                assertTrue("$ch has a stroke of one point", line.size >= 2)
                segments += line.size - 1
                for (pt in line) {
                    // Nothing outside the box the bumper occupies, or it would
                    // draw and collide beyond the shape it claims to be.
                    assertTrue("$ch runs off the lattice: ${pt[0]},${pt[1]}",
                        pt[0] >= -1.001f && pt[0] <= 1.001f &&
                            pt[1] >= -1.001f && pt[1] <= 1.001f)
                    // And every point lands on the lattice rather than between
                    // its holes, which is what keeps the set looking like one
                    // hand wrote it.
                    val col = (pt[0] + 1f) / 2f * (Letters.GRID_W - 1)
                    val row = (pt[1] + 1f) / 2f * (Letters.GRID_H - 1)
                    assertEquals("$ch is off-lattice", Math.round(col).toFloat(), col, 0.001f)
                    assertEquals("$ch is off-lattice", Math.round(row).toFloat(), row, 0.001f)
                }
            }
            assertTrue("$ch is a single dash", segments >= 1)
        }
    }

    @Test fun `a stroke becomes convex bars that cover it`() {
        val half = 0.15f
        for (ch in Letters.GLYPHS) {
            val strokes = Letters.strokes(ch)
            val bars = Letters.bars(strokes, half)
            assertEquals("one bar per segment",
                strokes.sumOf { it.size - 1 }, bars.size)
            for (bar in bars) {
                assertEquals("a bar is a quad", 4, bar.size)
                // Convex, and wound consistently: the collision in this toy
                // knows nothing but convex polygons.
                var sign = 0
                for (i in 0 until 4) {
                    val a = bar[i]
                    val b = bar[(i + 1) % 4]
                    val c = bar[(i + 2) % 4]
                    val cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
                    val s = if (cross > 0f) 1 else -1
                    if (sign == 0) sign = s
                    assertEquals("$ch has a bent bar", sign, s)
                }
            }
            // Every point on the skeleton is inside some bar: that is what
            // makes the thing you hit the thing you see.
            for (line in strokes) {
                for (i in 0 until line.size - 1) {
                    val mx = (line[i][0] + line[i + 1][0]) / 2f
                    val my = (line[i][1] + line[i + 1][1]) / 2f
                    assertTrue("$ch has a gap at the middle of a stroke",
                        bars.any { Geom.pointInPoly(mx, my, it) })
                }
            }
        }
    }

    @Test fun `a letter is drawn with a pen, and hit with the same pen`() {
        val t = toy()
        t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.2f, Shape.CIRCLE, 0f, glyph = "T"))
        val b = t.table[0]
        val drawn = t.bumperStrokes(b)
        assertEquals(Letters.strokes('T').size, drawn.size)
        // What is drawn and what is hit are built from one thing, so they
        // cannot drift: the bars are the strokes, widened.
        assertEquals(drawn.sumOf { it.size - 1 }, t.bumperParts(b).size)
        assertTrue("a letter has no filled loop any more", t.bumperLoops(b).isEmpty())
        assertEquals(Letters.STROKE * 0.5f * t.bumperRadius(b), t.penHalf(b), 0.001f)
    }

    @Test fun `a letter bumper is hit on its strokes and missed through its gaps`() {
        val t = toy()
        val b = Bumper(0.5f, 0.5f, 0.2f, Shape.CIRCLE, 0f, glyph = "H")
        val cx = 0.5f * t.w
        val cy = 0.5f * t.h
        val r = b.size * minOf(t.w, t.h)
        // The left stem of an H is solid; the space beside it, level with the
        // top, is not. A letter that is grabbed as a blob is not a letter.
        assertTrue("the stem", t.pointInBumper(cx - r * 0.8f, cy - r * 0.6f, b))
        assertFalse("the gap above the crossbar",
            Geom.pointInPoly(cx, cy - r * 0.6f, t.bumperParts(b).first()) &&
                t.bumperParts(b).none { Geom.pointInPoly(cx, cy - r * 0.6f, it) })
        assertTrue("the crossbar", t.bumperParts(b).any { Geom.pointInPoly(cx, cy, it) })
        assertFalse("the gap above it",
            t.bumperParts(b).any { Geom.pointInPoly(cx, cy - r * 0.6f, it) })
    }

    @Test fun `a letter bounces the ball off the part it actually hit`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.table.clear()
        t.table.add(Bumper(0.5f, 0.5f, 0.22f, Shape.CIRCLE, 0f, glyph = "I"))
        // Straight down the middle at the crossbar of an I, from above.
        t.bx = 0.5f * t.w
        t.by = 0.5f * t.h - 0.22f * minOf(t.w, t.h) * 1.6f
        t.vx = 0f; t.vy = 900f
        assertTrue("never reached it", run(t, 2f) { t.vy < 0f })
        assertTrue("it should come back up, not through", t.vy < 0f)
    }

    @Test fun `pulling stretches the axis you pulled along, whichever way it is turned`() {
        val t = toy()
        val b = Bumper(0.5f, 0.5f, 0.08f, Shape.SQUARE, 0f)
        t.table.clear(); t.table.add(b); t.selected = 0
        val r = b.size * minOf(t.w, t.h)
        t.pullTo(b, 0.5f * t.w + r * 2f, 0.5f * t.h)
        assertEquals("pulled sideways, it got wider", 2f, b.sx, 0.01f)
        assertEquals("and no taller", Toy.MIN_STRETCH, b.sy, 0.01f)

        // Turned a quarter turn, the same drag pulls the other axis.
        b.rot = (Math.PI / 2.0).toFloat()
        b.sx = 1f; b.sy = 1f
        t.pullTo(b, 0.5f * t.w + r * 2f, 0.5f * t.h)
        assertEquals(Toy.MIN_STRETCH, b.sx, 0.01f)
        assertEquals(2f, b.sy, 0.01f)

        // and it cannot be pulled past what a shape survives
        t.pullTo(b, 0.5f * t.w + r * 99f, 0.5f * t.h + r * 99f)
        assertEquals(Toy.MAX_STRETCH, b.sx, 0.01f)
        assertEquals(Toy.MAX_STRETCH, b.sy, 0.01f)
    }

    @Test fun `the pull handle sits on the corner of the stretched shape`() {
        val t = toy()
        val b = Bumper(0.5f, 0.5f, 0.08f, Shape.SQUARE, 0f, sx = 2f, sy = 0.5f)
        val hs = t.handles(b)
        val r = b.size * minOf(t.w, t.h)
        assertEquals(0.5f * t.w + r * 2f, hs[0][0], 0.5f)
        assertEquals(0.5f * t.h + r * 0.5f, hs[0][1], 0.5f)
        // and dragging it there is what put it there
        val c = Bumper(0.5f, 0.5f, 0.08f, Shape.SQUARE, 0f)
        t.pullTo(c, hs[0][0], hs[0][1])
        assertEquals(2f, c.sx, 0.01f)
        assertEquals(0.5f, c.sy, 0.01f)
    }

    @Test fun `a pulled circle is hit as the ellipse it is drawn as`() {
        val t = toy()
        val round = Bumper(0.5f, 0.5f, 0.08f, Shape.CIRCLE, 0f)
        assertTrue("a round one stays an exact circle", t.bumperParts(round).isEmpty())
        val pulled = Bumper(0.5f, 0.5f, 0.08f, Shape.CIRCLE, 0f, sx = 3f, sy = 0.4f)
        val parts = t.bumperParts(pulled)
        assertEquals("one convex piece", 1, parts.size)
        assertEquals(16, parts[0].size)
        val r = pulled.size * minOf(t.w, t.h)
        // Long one way and short the other, in the right directions.
        assertTrue(t.pointInBumper(0.5f * t.w + r * 2.5f, 0.5f * t.h, pulled))
        assertFalse(t.pointInBumper(0.5f * t.w, 0.5f * t.h + r * 0.8f, pulled))
    }

    @Test fun `the sheet offers every outline, letter and digit, one tap each`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.06f, Shape.entries.first(), 0f))
        t.selected = 0
        val b = t.table[0]

        assertEquals(Shape.entries.size + 26 + 10, t.glyphCount())
        // Six outlines, then A to Z, then 0 to 9 — and nothing twice.
        val seen = (0 until t.glyphCount()).map { t.glyphShapeAt(it) to t.glyphTextAt(it) }
        assertEquals(t.glyphCount(), seen.toSet().size)
        assertEquals("", t.glyphTextAt(0))
        assertEquals("A", t.glyphTextAt(Shape.entries.size))
        assertEquals("0", t.glyphTextAt(Shape.entries.size + 26))
        assertEquals("9", t.glyphTextAt(t.glyphCount() - 1))

        // One tap reaches any of them, and the sheet knows what it is wearing.
        b.shape = Shape.HEXAGON
        for (c in t.glyphCells()) {
            assertEquals("pick", t.glyphHit(c.x + c.w / 2f, c.y + c.h / 2f))
            assertEquals(t.glyphTextAt(c.i), b.glyph)
            assertEquals(c.i, t.glyphIndexOf(b))
        }
        // A glyph sits over the outline rather than replacing it.
        val hex = t.glyphCells()[Shape.entries.indexOf(Shape.HEXAGON)]
        t.glyphHit(hex.x + hex.w / 2f, hex.y + hex.h / 2f)
        val q = t.glyphCells()[Shape.entries.size + Letters.GLYPHS.indexOf('Q')]
        t.glyphHit(q.x + q.w / 2f, q.y + q.h / 2f)
        assertEquals("Q", b.glyph)
        assertEquals(Shape.HEXAGON, b.shape)
    }

    @Test fun `the edit toolbar clears the status bar`() {
        val t = toy()
        // A Pixel: a tall screen, a status bar at the top, a gesture pill
        // at the bottom.
        t.resize(412f, 915f, 48f, 132f)
        t.mode = Mode.BUMPERS
        t.editing = true
        for (c in t.toolbarButtons()) {
            assertTrue("button ${c.i} is under the system bar", c.y >= t.insetTop)
        }
        // And a tap where the system icons are must not reach it: that was the
        // bug — drawn under the clock, and the system took the touch first.
        assertEquals(null, t.toolbarHit(t.w / 2f, t.insetTop / 2f))
        // With no inset it sits where it always did.
        val flat = toy()
        flat.resize(412f, 915f)
        assertEquals(minOf(flat.w, flat.h) * 0.02f, flat.toolbarButtons()[0].y, 0.001f)
    }

    @Test fun `the sheet fits on the screen it is drawn on`() {
        for (size in listOf(320f to 568f, 360f to 640f, 390f to 844f, 412f to 915f)) {
            val t = toy()
            t.resize(size.first, size.second)
            t.mode = Mode.BUMPERS
            t.editing = true
            t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.06f, Shape.entries.first(), 0f))
            t.selected = 0
            val s = t.glyphSheet()
            val what = "${size.first}x${size.second}"
            assertTrue("$what: off the top", s.y >= 0f)
            assertTrue("$what: off the bottom", s.y + s.h <= t.viewH + 0.5f)
            assertTrue("$what: off the side", s.x >= 0f && s.x + s.w <= t.w + 0.5f)
            for (c in t.glyphCells()) {
                assertTrue("$what: cell ${c.i} escapes the sheet",
                    c.x >= s.x - 0.5f && c.x + c.w <= s.x + s.w + 0.5f &&
                        c.y >= s.y - 0.5f && c.y + c.h <= s.y + s.h + 0.5f)
            }
        }
    }

    @Test fun `the shape button opens the sheet and the ink drawer closes it`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.editing = true
        t.table = mutableListOf(Bumper(0.5f, 0.5f, 0.06f, Shape.entries.first(), 0f))
        t.selected = 0
        t.doToolbar("shape")
        assertTrue(t.glyphOpen)
        t.doToolbar("shape")
        assertTrue("a second tap puts it away", !t.glyphOpen)
        t.doToolbar("shape")
        t.doToolbar("ink")
        assertTrue("two sheets at once is one too many", !t.glyphOpen)
        assertTrue(t.drawerOpen)
        t.doToolbar("done")
        assertTrue(!t.glyphOpen && !t.drawerOpen)
    }

    @Test fun `a digit bumper collides on its own strokes`() {
        for (ch in Letters.DIGITS) {
            val t = toy()
            t.table = mutableListOf(
                Bumper(0.5f, 0.5f, 0.2f, Shape.CIRCLE, 0f, glyph = ch.toString()),
            )
            val b = t.table[0]
            val strokes = t.bumperStrokes(b)
            assertTrue("$ch has no strokes", strokes.isNotEmpty())
            assertEquals("$ch is hit as something other than what it is drawn as",
                strokes.sumOf { it.size - 1 }, t.bumperParts(b).size)
        }
    }

    @Test fun `a table saved before letters existed still loads`() {
        val t = toy()
        val old5 = "0.25,0.3,0.055,CIRCLE,0"
        val old7 = "0.25,0.3,0.055,CIRCLE,0,3,2"
        val now = "0.25,0.3,0.055,CIRCLE,0,3,2,2.5,0.5,K"
        val rows = t.decodeTable("$old5;$old7;$now")
        assertEquals(3, rows.size)
        for (r in rows.take(2)) {
            assertEquals("nothing saved before this was stretched", 1f, r.sx, 0f)
            assertEquals(1f, r.sy, 0f)
            assertEquals("", r.glyph)
        }
        assertEquals(2.5f, rows[2].sx, 0f)
        assertEquals(0.5f, rows[2].sy, 0f)
        assertEquals("K", rows[2].glyph)
        // and what it writes it can read back
        t.table.clear(); t.table.addAll(rows)
        val again = t.decodeTable(t.encodeTable())
        assertEquals(3, again.size)
        assertEquals("K", again[2].glyph)
        assertEquals(2.5f, again[2].sx, 0f)
        // junk in those fields does not take the row down with it
        val bad = t.decodeTable("0.25,0.3,0.055,CIRCLE,0,3,2,99,-4,%")
        assertEquals(1, bad.size)
        assertEquals(Toy.MAX_STRETCH, bad[0].sx, 0f)
        assertEquals(Toy.MIN_STRETCH, bad[0].sy, 0f)
        assertEquals("", bad[0].glyph)
    }

    // ---- curving off a round shape ---------------------------------------

    @Test fun `a graze off a round bumper follows the curve, and a square hit comes off it`() {
        // Straight at the middle of a round bumper: it comes back the way it
        // came, with the kick it always had.
        val square = toy()
        square.mode = Mode.BUMPERS
        square.table.clear()
        square.table.add(Bumper(0.5f, 0.5f, 0.12f, Shape.CIRCLE, 0f))
        square.bx = 0.5f * square.w
        square.by = 0.5f * square.h - 0.3f * square.h
        square.vx = 0f; square.vy = 900f
        var before = square.vy
        var frames = 0
        while (square.vy > 0f && frames++ < 120) { before = square.vy; square.step(dt) }
        assertTrue("never got there", square.vy < 0f)
        assertTrue("a hit down the middle comes back as fast as it went in: " +
            "$before -> ${square.vy}", -square.vy > before * 0.9f)

        // Past the same bumper, barely touching it: it keeps going, bent.
        val graze = toy()
        graze.mode = Mode.BUMPERS
        graze.table.clear()
        graze.table.add(Bumper(0.5f, 0.5f, 0.12f, Shape.CIRCLE, 0f))
        val r = 0.12f * minOf(graze.w, graze.h)
        graze.bx = 0.5f * graze.w - 0.3f * graze.h
        graze.by = 0.5f * graze.h - (r + graze.ballR()) * 0.86f
        graze.vx = 1200f; graze.vy = 0f
        var speed0 = 0f
        var n = 0
        while (graze.vy == 0f && n++ < 120) { speed0 = hypot(graze.vx, graze.vy); graze.step(dt) }
        assertTrue("never touched it", graze.vy != 0f)
        assertTrue("a graze bends it rather than sending it back", graze.vx > 0f)
        val speed1 = hypot(graze.vx, graze.vy)
        // Not to the last decimal: a graze is not perfectly tangential, so a
        // little of the normal component still comes off with the kick.
        assertTrue("and it keeps roughly the speed it arrived with: $speed0 -> $speed1",
            speed1 > speed0 * 0.95f && speed1 < speed0 * 1.07f)
        assertTrue("bent, though: vy=${graze.vy}", graze.vy < -20f)
    }

    @Test fun `a flat side still reflects, however lightly it is clipped`() {
        val t = toy()
        t.mode = Mode.BUMPERS
        t.table.clear()
        t.table.add(Bumper(0.5f, 0.5f, 0.12f, Shape.SQUARE, 0f))
        val r = 0.12f * minOf(t.w, t.h) * 0.707f
        t.bx = 0.5f * t.w - 0.3f * t.h
        t.by = 0.5f * t.h - (r + t.ballR()) * 0.9f
        t.vx = 1200f; t.vy = 0f
        assertTrue("never touched it", run(t, 2f) { t.vy != 0f })
        // Off a flat top the ball leaves upward — it is a reflection, not a
        // slide along the face.
        assertTrue("a flat face reflects: vy=${t.vy}", t.vy < -100f)
    }

    @Test fun `grip is all or nothing at the ends and eased in between`() {
        val t = toy()
        t.vx = 1000f; t.vy = 0f
        assertEquals("a pure graze does not bounce", 0f, t.grip(0f), 1e-4f)
        assertEquals("dead-on bounces in full", 1f, t.grip(-1000f), 1e-4f)
        assertEquals("and at the threshold too", 1f,
            t.grip(-1000f * Toy.CURVE_BITE), 1e-4f)
        val half = t.grip(-1000f * Toy.CURVE_BITE / 2f)
        assertEquals("halfway is halfway", 0.5f, half, 0.01f)
        // and a ball that is not moving is not grazing anything
        t.vx = 0f; t.vy = 0f
        assertEquals(1f, t.grip(0f), 1e-4f)
    }

    @Test fun `a pulled circle curves too, and a letter does not`() {
        val t = toy()
        assertTrue(t.isRound(Bumper(0.5f, 0.5f, 0.1f, Shape.CIRCLE, 0f)))
        assertTrue("an ellipse is a curve",
            t.isRound(Bumper(0.5f, 0.5f, 0.1f, Shape.CIRCLE, 0f, sx = 3f, sy = 0.5f)))
        assertFalse("a letter is flat sides all the way round",
            t.isRound(Bumper(0.5f, 0.5f, 0.1f, Shape.CIRCLE, 0f, glyph = "O")))
        assertFalse(t.isRound(Bumper(0.5f, 0.5f, 0.1f, Shape.HEXAGON, 0f)))
    }


    // ---- sound ------------------------------------------------------------
    //
    // Nobody can listen to these on the machine they are written on, so the
    // waveform has to answer instead: does it decay, does it stay inside the
    // rails, and is the pitch the one that was asked for.

    /** The strongest frequency in a buffer, by testing the ones we expect. */
    private fun peakHz(buf: FloatArray, n: Int, rate: Int, candidates: List<Float>): Float {
        var best = 0f
        var bestPower = -1.0
        for (f in candidates) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * f * i / rate
                re += buf[i] * Math.cos(a)
                im += buf[i] * Math.sin(a)
            }
            val power = re * re + im * im
            if (power > bestPower) { bestPower = power; best = f }
        }
        return best
    }

    @Test fun `a note is the pitch it was asked for`() {
        val rate = 22050
        val buf = FloatArray(rate * 3)
        // Three degrees of the scale, an octave apart at the ends.
        for (step in listOf(0, 5, 10)) {
            val n = Synth.render(Note(Voices.ORGAN, step, 1f), rate, buf)
            val want = Voices.hz(step)
            val heard = peakHz(buf, minOf(n, rate / 4), rate,
                listOf(want / 2f, want * 0.94f, want, want * 1.06f, want * 2f))
            assertEquals("step $step", want, heard, 0.01f)
        }
        // A degree is a degree of the pentatonic, and an octave is five of them
        assertEquals(2f, Voices.hz(5) / Voices.hz(0), 0.001f)
        assertEquals(4f, Voices.hz(10) / Voices.hz(0), 0.001f)
        // and it wraps rather than running off the top of the keyboard
        assertEquals(Voices.hz(0), Voices.hz(Voices.SCALE.size * Voices.OCTAVES), 0.001f)
    }

    @Test fun `every voice starts, decays and stays inside the rails`() {
        val rate = 22050
        val buf = FloatArray(rate * 4)
        for (voice in 1 until Palette.VOICE_NAMES.size) {
            val n = Synth.render(Note(voice, 7, 1f), rate, buf)
            assertTrue("${Palette.VOICE_NAMES[voice]} is silent", n > rate / 20)

            var loudest = 0f
            for (i in 0 until n) loudest = maxOf(loudest, abs(buf[i]))
            assertTrue("${Palette.VOICE_NAMES[voice]} never gets going", loudest > 0.05f)
            assertTrue("${Palette.VOICE_NAMES[voice]} clips at $loudest", loudest <= 1f)

            // It has to be quieter at the end than at the start, or it is a
            // drone rather than a hit.
            fun rms(from: Int, to: Int): Float {
                var sum = 0.0
                for (i in from until to) sum += buf[i].toDouble() * buf[i]
                return Math.sqrt(sum / maxOf(to - from, 1)).toFloat()
            }
            val head = rms(rate / 200, n / 8)
            val tail = rms(n - n / 8, n)
            assertTrue("${Palette.VOICE_NAMES[voice]} does not decay: $head -> $tail",
                tail < head * 0.5f)

            // and it starts from silence rather than from a click
            assertTrue("${Palette.VOICE_NAMES[voice]} clicks", abs(buf[0]) < 0.02f)
        }
    }

    /**
     * The same note, rendered by the browser build, sample for sample. These
     * numbers were read out of headless Chromium running `web/index.html` and
     * pasted here: if a port drifts, one of the two stops matching them, and
     * the toy stops sounding like the same instrument on the two platforms.
     */
    @Test fun `the browser and the phone render the same note`() {
        val rate = 22050
        val buf = FloatArray(rate * 3)
        val n = Synth.render(Note(Voices.KEYS, 7, 1f, 0f, 1f, 12345), rate, buf)
        assertEquals(12733, n)
        val head = floatArrayOf(
            0f, 0.000654f, 0.002594f, 0.005058f,
            0.007924f, 0.010733f, 0.013808f, 0.016713f,
        )
        for (i in head.indices) assertEquals("sample $i", head[i], buf[i], 5e-6f)
        assertEquals(-0.055697f, buf[1000], 5e-6f)
        assertEquals(220f, Voices.hz(0), 0.001f)
        assertEquals(440f, Voices.hz(5), 0.001f)
        assertEquals(880f, Voices.hz(10), 0.001f)
        val lengths = (1..5).map { Synth.samples(Note(it, 7, 1f), rate) }
        assertEquals(listOf(9724, 12733, 4630, 31255, 16206), lengths)
    }

    @Test fun `a bell rings longer than a drum, and a drum is mostly noise`() {
        val rate = 22050
        assertTrue(Synth.samples(Note(Voices.BELL, 0, 1f), rate) >
                   Synth.samples(Note(Voices.DRUM, 0, 1f), rate) * 4)
        assertTrue(Voices.grit(Voices.DRUM) > Voices.grit(Voices.ORGAN) * 10)
        // holding a note longer makes a longer buffer
        assertTrue(Synth.samples(Note(Voices.ORGAN, 0, 1f, hold = 2f), rate) >
                   Synth.samples(Note(Voices.ORGAN, 0, 1f), rate))
    }

    @Test fun `it speaks out of the box, and off means off`() {
        val t = toy()
        assertEquals("a sound nobody can find is a sound nobody has",
            Voices.KEYS, t.voiceIndex)
        // and it can be silenced
        t.voiceIndex = Voices.OFF
        t.mode = Mode.BUMPERS
        t.bx = t.w / 2f; t.by = 100f; t.vx = 0f; t.vy = 2400f
        run(t, 3f) { t.bounceCount > 2 }
        assertTrue("it bounced", t.bounceCount > 0)
        assertTrue("but said nothing", t.takeNotes().isEmpty())

        val rate = 22050
        val buf = FloatArray(rate)
        assertEquals(0, Synth.render(Note(Voices.OFF, 4, 1f), rate, buf))
    }

    @Test fun `a bounce is heard, and harder means louder`() {
        val soft = toy().apply { voiceIndex = Voices.KEYS; mode = Mode.BUMPERS }
        soft.bx = soft.w / 2f; soft.by = soft.h / 2f; soft.vx = 0f; soft.vy = 700f
        run(soft, 3f) { soft.notes.isNotEmpty() }
        val quiet = soft.takeNotes()
        assertTrue("a soft bounce should still be heard", quiet.isNotEmpty())

        val hard = toy().apply { voiceIndex = Voices.KEYS; mode = Mode.BUMPERS }
        hard.bx = hard.w / 2f; hard.by = hard.h / 2f; hard.vx = 0f; hard.vy = 5000f
        run(hard, 3f) { hard.notes.isNotEmpty() }
        val loud = hard.takeNotes()
        assertTrue(loud.isNotEmpty())
        assertTrue("a harder hit should be louder: ${quiet[0].gain} vs ${loud[0].gain}",
            loud[0].gain > quiet[0].gain)
        // and taking them empties the queue
        assertTrue(hard.takeNotes().isEmpty())
    }

    @Test fun `a bigger bumper sounds lower, and glass and lightning have their own voices`() {
        val t = toy()
        t.voiceIndex = Voices.BELL
        val small = Bumper(0.5f, 0.5f, Toy.MIN_BUMPER, Shape.CIRCLE, 0f)
        val big = Bumper(0.5f, 0.5f, Toy.MAX_BUMPER, Shape.CIRCLE, 0f)
        assertTrue("a bigger thing should sound lower",
            Voices.hz(t.bumperStep(big)) < Voices.hz(t.bumperStep(small)))

        // up the screen is up the scale
        assertTrue(t.stepAt(t.w / 2f, t.h * 0.1f) > t.stepAt(t.w / 2f, t.h * 0.9f))

        // glass is grittier than a plain bounce, and lightning rings longer
        t.mode = Mode.GLASS
        assertTrue(t.breakGlass(t.w / 2f, t.h / 2f))
        val glass = t.takeNotes()
        assertEquals(1, glass.size)
        assertTrue("glass should be mostly shatter", glass[0].grit > 0.4f)

        t.mode = Mode.BOLT
        t.fireBolt(t.w / 2f, t.h / 2f, 1800f, -900f)
        val thunder = t.takeNotes()
        assertTrue(thunder.isNotEmpty())
        assertTrue("thunder should hang about", thunder[0].hold > 1f)
        assertTrue("and be low", thunder[0].step < 6)
    }

    @Test fun `no amount of noise piles up`() {
        val t = toy()
        t.voiceIndex = Voices.DRUM
        t.mode = Mode.GLASS
        repeat(40) { i -> t.breakGlass(t.w * 0.2f + i * 3f, t.h * 0.3f + i * 5f) }
        assertTrue("the queue must not grow without bound", t.notes.size <= t.MAX_NOTES)
    }

    // ---- how hard a hit feels ---------------------------------------------

    @Test fun `the harder it lands the more beats you feel`() {
        val t = toy()
        // A flick sets the speed, the speed sets the impact, and the impact
        // sets the number of beats — so this walks up through speeds.
        var last = 0
        var top = 0
        for (speed in listOf(150f, 400f, 900f, 1500f, 2200f, 3000f, 6000f)) {
            t.lastImpact = speed
            t.lastImpactWall = false
            val n = t.impactBumps()
            assertTrue("beats should never go down: $speed gave $n after $last", n >= last)
            last = n
            top = maxOf(top, n)
        }
        assertTrue("a hard hit should be more than one beat", top > 1)
        assertEquals("and never more than the ceiling", Toy.BUMPS_MAX, top)

        // Below the floor a hit is not worth feeling at all.
        t.lastImpact = 0f
        assertEquals(0, t.impactBumps())
        assertEquals(0f, t.bumpLevel(0), 1e-6f)
    }

    @Test fun `a wall is felt as less than a bumper at the same speed`() {
        val t = toy()
        t.lastImpact = 6000f
        t.lastImpactWall = false
        val bumper = t.impactBumps()
        t.lastImpactWall = true
        val wall = t.impactBumps()
        assertTrue("a flat thing to hit should be shorter: $wall vs $bumper", wall < bumper)
        assertTrue(wall >= 1)
    }

    @Test fun `a burst falls away rather than repeating flat`() {
        val t = toy()
        t.lastImpact = 6000f
        t.lastImpactWall = false
        val n = t.impactBumps()
        assertTrue(n >= 3)
        var prev = Float.MAX_VALUE
        for (i in 0 until n) {
            val level = t.bumpLevel(i)
            assertTrue("beat $i is out of range: $level", level > 0f && level <= 1f)
            assertTrue("beat $i should not be louder than the one before", level < prev)
            prev = level
        }
        // The first beat is the impact itself, and the last keeps the falloff.
        assertEquals(t.impactStrength(), t.bumpLevel(0), 1e-6f)
        assertEquals(t.impactStrength() * Toy.BUMP_FALLOFF, t.bumpLevel(n - 1), 1e-6f)
        // and nothing outside the burst
        assertEquals(0f, t.bumpLevel(n), 1e-6f)
        assertEquals(0f, t.bumpLevel(-1), 1e-6f)
    }

    @Test fun `a soft landing is still a single tap`() {
        val t = toy()
        t.lastImpact = 260f            // just over the floor
        t.lastImpactWall = false
        assertEquals("a tap is a tap", 1, t.impactBumps())
        assertEquals(t.impactStrength(), t.bumpLevel(0), 1e-6f)
    }

    // ---- a strike, not a scribble -----------------------------------------

    /** Every etched path, and how far each one runs. */
    private fun inkLeft(t: Toy): List<Float> = t.etched.map { e ->
        var d = 0f
        for (i in 1 until e.nodes.size) {
            d += hypot(e.nodes[i][0] - e.nodes[i - 1][0], e.nodes[i][1] - e.nodes[i - 1][1])
        }
        d
    }

    @Test fun `one flick does not bury the screen`() {
        // This is the bug the fan introduced and a photograph caught: every
        // fork inherited "travel until you hit a wall", so one flick left
        // forty full-length streaks. Fourteen screens of ink is a scribble.
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(t.w * 0.24f, t.h * 0.74f, 1250f, -900f)
        run(t, 4f) { t.bolts.isEmpty() && t.etched.isNotEmpty() }
        val ink = inkLeft(t)
        val diagonal = hypot(t.w, t.h)
        val screens = ink.sum() / diagonal
        assertTrue("nothing was drawn at all", ink.isNotEmpty())
        assertTrue("one flick left $screens screens of ink", screens < 9f)
        assertTrue("one flick left ${ink.size} separate paths", ink.size < 30)
    }

    @Test fun `the harder the flick the more it leaves behind`() {
        fun ink(vx: Float, vy: Float): Float {
            val t = toy()
            t.mode = Mode.BOLT
            t.fireBolt(t.w * 0.24f, t.h * 0.74f, vx, vy)
            run(t, 4f) { t.bolts.isEmpty() && t.etched.isNotEmpty() }
            return inkLeft(t).sum()
        }
        val soft = ink(700f, -500f)
        val hard = ink(2600f, -1900f)
        // Before the reach budget these came out the same, which is the tell
        // that flick strength was not reading at all.
        assertTrue("a hard flick should leave more than a soft one: $soft vs $hard",
            hard > soft * 1.25f)
    }

    @Test fun `a fork is a spark, and its own forks are shorter still`() {
        val t = toy()
        // The stroke your finger threw runs until it hits something.
        assertEquals(Float.MAX_VALUE, t.boltReach(0), 0f)
        val first = t.boltReach(1)
        val second = t.boltReach(2)
        val third = t.boltReach(3)
        assertTrue("a fork should not cross the field",
            first < minOf(t.w, t.h) * 0.75f)
        assertEquals("and each generation gets half the last",
            first * Toy.BOLT_REACH_FALL, second, 0.01f)
        assertEquals(second * Toy.BOLT_REACH_FALL, third, 0.01f)

        // Branching thins out as it goes, or three arms become forty paths.
        assertEquals(Toy.BOLT_BRANCH, t.boltBranchChance(0), 1e-6f)
        for (gen in 1..Toy.BOLT_MAX_GEN) {
            assertTrue("generation $gen should fork less than the one before",
                t.boltBranchChance(gen) < t.boltBranchChance(gen - 1))
        }
    }

    @Test fun `the deeper it goes the shorter the path it leaves`() {
        val t = toy()
        t.mode = Mode.BOLT
        t.fireBolt(t.w * 0.24f, t.h * 0.74f, 1800f, -1300f)
        run(t, 4f) { t.bolts.isEmpty() && t.etched.isNotEmpty() }
        val byGen = t.etched.groupBy { it.gen }
        val longest = byGen.mapValues { (_, list) ->
            list.maxOf { e ->
                var d = 0f
                for (i in 1 until e.nodes.size) {
                    d += hypot(e.nodes[i][0] - e.nodes[i - 1][0],
                               e.nodes[i][1] - e.nodes[i - 1][1])
                }
                d
            }
        }
        // Whatever generations turned up, each one's longest path is shorter
        // than the last: that is "thins as it spreads", measured.
        val gens = longest.keys.sorted()
        for (i in 1 until gens.size) {
            assertTrue("generation ${gens[i]} runs further than ${gens[i - 1]}",
                longest[gens[i]]!! < longest[gens[i - 1]]!!)
        }
    }
}
