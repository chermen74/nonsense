package com.nonsense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Input and pixels. All the thinking lives in [Toy], which has no Android in
 * it so it can be unit-tested on a plain JVM.
 *
 * Gestures, since a phone has no keyboard:
 *   double tap            cycle the four toys
 *   long press (bumpers)  edit the table
 *   long press (ball)     require catching before you can throw
 *   two-finger tap        clear the painting
 *   tap the current ink   open the palette
 *
 * The strip along the bottom stays visible in every mode but the dial. The
 * desktop build hides it because a keyboard can reach colour, size and shape
 * without it; here it is the only way in.
 */
class NonsenseView(context: Context) : View(context), Choreographer.FrameCallback {

    private val toy = Toy()
    private val choreographer = Choreographer.getInstance()
    private var lastFrameNanos = 0L

    private val prefs = context.getSharedPreferences("nonsense", Context.MODE_PRIVATE)

    // ---- paint layers -----------------------------------------------------
    // Two of them. Translucent ink has to be composited once per stroke:
    // stroking segment by segment at low alpha makes every round cap overlap
    // the last and re-darken it, so a 15% trail comes out solid and beaded.
    private var trail: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var stroke: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private var strokeLive = false
    private var lastTrailX = 0f
    private var lastTrailY = 0f
    private var trailStarted = false

    // ---- paints -----------------------------------------------------------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(90, 0, 0, 0)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(112, 41, 41)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val layerPaint = Paint()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    // ---- haptics ----------------------------------------------------------
    private val haptics = Haptics(context)

    /** [strength] runs 0 to 1. A wall is a flat knock; a bumper kicks back. */
    private fun bump(strength: Float, wall: Boolean) {
        if (strength <= 0f) return
        haptics.knock(strength * toy.hapticScale(), sharp = wall)
    }

    /** A rib passing the index mark. */
    private fun detent() {
        // Fast spins are lighter and thinner: eighteen ribs at full speed is
        // forty clicks a second, and forty firm ones is a buzz, not a knurl.
        haptics.tick((0.95f - 0.55f * toy.dialSpeedFraction()) * toy.hapticScale())
    }

    /** Any control that answers a finger. */
    private fun tick() = haptics.tick(0.55f * toy.hapticScale())

    // ---- gesture bookkeeping ---------------------------------------------
    private var velocityTracker: VelocityTracker? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private var editDrag: String? = null
    private var grabDX = 0f
    private var grabDY = 0f
    private var lastBounce = 0
    private var lastDetent = 0
    private var lastDetentMs = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private val longPress = Runnable {
        longPressFired = true
        when (toy.mode) {
            Mode.BUMPERS -> { toy.editing = !toy.editing; toy.selected = -1 }
            Mode.BALL -> toy.mustCatch = !toy.mustCatch
            else -> return@Runnable
        }
        toy.dragging = false
        save()
        haptics.knock(0.8f * toy.hapticScale(), sharp = false)
    }

    init {
        load()
    }

    // ---- lifecycle --------------------------------------------------------

    private var insetBottom = 0f

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars = WindowInsetsCompat.toWindowInsetsCompat(insets)
            .getInsets(WindowInsetsCompat.Type.systemBars())
        insetBottom = bars.bottom.toFloat()
        if (width > 0 && height > 0) toy.resize(width.toFloat(), height.toFloat(), insetBottom)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        toy.resize(w.toFloat(), h.toFloat(), insetBottom)
        if (w > 0 && h > 0 && (trail?.width != w || trail?.height != h)) {
            trail = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trail!!)
            stroke = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            strokeCanvas = Canvas(stroke!!)
            strokeLive = false
            trailStarted = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        removeCallbacks(longPress)
        save()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0)
                .toFloat().coerceIn(0f, 0.05f)
            toy.step(dt)
            if (toy.painting()) layTrail()
            if (toy.justCameToRest) settleStroke()
            if (toy.bounceCount != lastBounce) {
                bump(toy.impactStrength(), toy.lastImpactWall)
                lastBounce = toy.bounceCount
            }
            if (toy.dialDetent != lastDetent) {
                // The actuator cannot keep up with a fast wheel and trying
                // just smears every click into one long hum, so drop the ones
                // that arrive too close together and keep the rhythm.
                val ms = frameTimeNanos / 1_000_000L
                if (ms - lastDetentMs >= 26L) { detent(); lastDetentMs = ms }
                lastDetent = toy.dialDetent
            }
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        choreographer.postFrameCallback(this)
    }

    // ---- the painting -----------------------------------------------------

    private fun layTrail() {
        if (!trailStarted) {
            lastTrailX = toy.bx; lastTrailY = toy.by
            trailStarted = true
            return
        }
        if (toy.bx == lastTrailX && toy.by == lastTrailY) return
        inkPaint.color = toy.inkColor()
        inkPaint.alpha = 255                       // full into the scratch layer
        inkPaint.strokeWidth = toy.inkWidth()
        strokeCanvas?.drawLine(lastTrailX, lastTrailY, toy.bx, toy.by, inkPaint)
        lastTrailX = toy.bx; lastTrailY = toy.by
        strokeLive = true
    }

    /** Fold the live stroke onto the trail at its translucency. */
    private fun settleStroke() {
        if (!strokeLive) return
        val s = stroke ?: return
        layerPaint.alpha = (toy.inkAlpha() * 255f).toInt().coerceIn(0, 255)
        trailCanvas?.drawBitmap(s, 0f, 0f, layerPaint)
        s.eraseColor(Color.TRANSPARENT)
        strokeLive = false
    }

    private fun clearTrail() {
        trail?.eraseColor(Color.TRANSPARENT)
        stroke?.eraseColor(Color.TRANSPARENT)
        strokeLive = false
        trailStarted = false
    }

    // ---- input ------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // two-finger tap wipes the painting
        if (toy.painting() &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.pointerCount == 2
        ) {
            removeCallbacks(longPress)
            clearTrail()
            toy.dragging = false
            tick()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp(event)
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        val x = event.x
        val y = event.y
        downX = x; downY = y
        longPressFired = false

        if (toy.screen == Screen.TITLE) {
            toy.menuHit(x, y)?.let { key ->
                toy.tapMenu(key)
                trailStarted = false
                tick()
                save()
            }
            return
        }

        if (toy.drawerOpen) {
            val before = toy.inkFamily to toy.inkTone
            val alphaBefore = toy.inkAlphaIndex
            when (toy.drawerHit(x, y)) {
                "outside" -> toy.drawerOpen = false
                "ink", "alpha" -> {
                    // the stroke must settle before the ink under it changes
                    if (before != (toy.inkFamily to toy.inkTone) || alphaBefore != toy.inkAlphaIndex)
                        settleStroke()
                    tick()
                }
                "scrim", "canvas" -> tick()
                "haptic" -> haptics.knock(0.85f * toy.hapticScale(), sharp = false)
            }
            save()
            return
        }

        if (toy.editing && toy.mode == Mode.BUMPERS) {
            toy.toolbarHit(x, y)?.let {
                toy.doToolbar(it)
                tick()
                save()
                return
            }
            toy.table.getOrNull(toy.selected)?.let { b ->
                val hs = toy.handles(b)
                val reach = minOf(toy.w, toy.h) * 0.05f
                if (hypot(x - hs[0][0], y - hs[0][1]) < reach) { editDrag = "resize"; return }
                if (hypot(x - hs[1][0], y - hs[1][1]) < reach) { editDrag = "rotate"; return }
            }
            for (i in toy.table.indices.reversed()) {
                if (toy.pointInBumper(x, y, toy.table[i])) {
                    toy.selected = i
                    editDrag = "move"
                    grabDX = x - toy.table[i].nx * toy.w
                    grabDY = y - toy.table[i].ny * toy.h
                    return
                }
            }
            toy.selected = -1
            return
        }

        toy.modeHit(x, y)?.let {
            val wasPainting = toy.painting()
            toy.tapMode(it)
            if (wasPainting && !toy.painting()) settleStroke()
            trailStarted = false
            tick()
            save()
            return
        }

        if (stripVisible() && toy.inStrip(y)) {
            val familyBefore = toy.inkFamily
            if (toy.stripTap(x)) {
                if (familyBefore != toy.inkFamily) settleStroke()
                tick()
                save()
                return
            }
        }

        if (isDoubleTap(event)) {
            removeCallbacks(longPress)
            toy.cycleMode()
            trailStarted = false
            tick()
            save()
            return
        }

        postDelayed(longPress, longPressMs)

        if (toy.mode == Mode.DIAL) {
            toy.grabDial(x, y)
            dialLastTime = event.eventTime
        } else {
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
            if (!toy.grab(x, y, System.currentTimeMillis())) {
                // reached for a ball that had to be caught, and it wasn't there
                tick()
                return
            }
            if (toy.painting()) {
                lastTrailX = toy.bx; lastTrailY = toy.by; trailStarted = true
            }
        }
    }

    private var dialLastTime = 0L

    private fun onMove(event: MotionEvent) {
        if (toy.screen == Screen.TITLE) return
        val x = event.x
        val y = event.y
        if (!longPressFired && hypot(x - downX, y - downY) > touchSlop) removeCallbacks(longPress)

        if (toy.drawerOpen) return

        if (toy.editing && toy.mode == Mode.BUMPERS) {
            val b = toy.table.getOrNull(toy.selected) ?: return
            when (editDrag) {
                "move" -> {
                    b.nx = Geom.clamp((x - grabDX) / toy.w, 0f, 1f)
                    b.ny = Geom.clamp((y - grabDY) / toy.h, 0f, 1f)
                }
                "resize" -> {
                    val d = hypot(x - b.nx * toy.w, y - b.ny * toy.h)
                    b.size = Geom.clamp(d / minOf(toy.w, toy.h), Toy.MIN_BUMPER, Toy.MAX_BUMPER)
                }
                "rotate" -> {
                    b.rot = kotlin.math.atan2(y - b.ny * toy.h, x - b.nx * toy.w) -
                        (Math.PI / 2.0).toFloat()
                }
            }
            return
        }

        if (toy.mode == Mode.DIAL) {
            val dtms = (event.eventTime - dialLastTime).coerceAtLeast(1L)
            toy.dragDial(x, y, dtms / 1000f)
            dialLastTime = event.eventTime
        } else if (toy.dragging) {
            velocityTracker?.addMovement(event)
            toy.drag(x, y)
            if (toy.painting()) layTrail()
        }
    }

    private fun onUp(event: MotionEvent) {
        removeCallbacks(longPress)
        if (toy.screen == Screen.TITLE) return
        if (toy.editing && toy.mode == Mode.BUMPERS) {
            if (editDrag != null) save()
            editDrag = null
            return
        }
        if (toy.mode == Mode.DIAL) {
            toy.releaseDial()
            return
        }
        if (toy.dragging) {
            var fx = 0f
            var fy = 0f
            velocityTracker?.let {
                it.addMovement(event)
                it.computeCurrentVelocity(1000)
                fx = it.xVelocity
                fy = it.yVelocity
                it.recycle()
            }
            velocityTracker = null
            toy.release(fx, fy)
        }
    }

    private fun isDoubleTap(event: MotionEvent): Boolean {
        val now = event.eventTime
        val isDouble = now - lastTapTime < 250 &&
            hypot(event.x - lastTapX, event.y - lastTapY) < 80f
        lastTapTime = now
        lastTapX = event.x
        lastTapY = event.y
        return isDouble
    }

    private fun stripVisible(): Boolean =
        toy.screen == Screen.PLAY && toy.mode != Mode.DIAL && !toy.drawerOpen &&
            !(toy.editing && toy.mode == Mode.BUMPERS)

    // ---- persistence ------------------------------------------------------

    private fun save() {
        val table = toy.table.joinToString(";") {
            "${it.nx},${it.ny},${it.size},${it.shape.name},${it.rot}"
        }
        prefs.edit()
            .putString("table", table)
            .putInt("sizeIndex", toy.sizeIndex)
            .putString("shape", toy.shape.name)
            .putBoolean("mustCatch", toy.mustCatch)
            .putBoolean("paintOnBumpers", toy.paintOnBumpers)
            .putInt("inkFamily", toy.inkFamily)
            .putInt("inkTone", toy.inkTone)
            .putInt("inkAlpha", toy.inkAlphaIndex)
            .putInt("scrim", toy.scrimIndex)
            .putInt("canvas", toy.canvasIndex)
            .putInt("haptic", toy.hapticIndex)
            .putInt("prefsVersion", 3)
            .putString("mode", toy.mode.name)
            .apply()
    }

    private fun load() {
        runCatching {
            // The sheer defaults changed after the first build. Anything saved
            // before that was saved by someone who could not reach the controls
            // to choose it, so take it back to the new default once.
            if (prefs.getInt("prefsVersion", 1) < 2) {
                prefs.edit()
                    .putInt("prefsVersion", 2)
                    .remove("inkAlpha")
                    .remove("scrim")
                    .apply()
            }
            prefs.edit().putInt("prefsVersion", 3).apply()
            prefs.getString("table", null)?.takeIf { it.isNotBlank() }?.let { raw ->
                val parsed = raw.split(";").mapNotNull { row ->
                    val f = row.split(",")
                    if (f.size != 5) null else Bumper(
                        Geom.clamp(f[0].toFloat(), 0f, 1f),
                        Geom.clamp(f[1].toFloat(), 0f, 1f),
                        Geom.clamp(f[2].toFloat(), Toy.MIN_BUMPER, Toy.MAX_BUMPER),
                        Shape.valueOf(f[3]),
                        f[4].toFloat(),
                    )
                }.toMutableList()
                if (parsed.isNotEmpty()) toy.table = parsed
            }
            toy.sizeIndex = prefs.getInt("sizeIndex", Toy.DEFAULT_SIZE)
                .coerceIn(0, Toy.SIZES.size - 1)
            toy.shape = Shape.valueOf(prefs.getString("shape", Shape.CIRCLE.name)!!)
            toy.mustCatch = prefs.getBoolean("mustCatch", false)
            toy.paintOnBumpers = prefs.getBoolean("paintOnBumpers", true)
            toy.inkFamily = prefs.getInt("inkFamily", 0).coerceIn(0, Palette.NAMES.size - 1)
            toy.inkTone = prefs.getInt("inkTone", 2).coerceIn(0, Palette.TONE_MIX.size - 1)
            toy.inkAlphaIndex = prefs.getInt("inkAlpha", 3).coerceIn(0, Palette.ALPHAS.size - 1)
            toy.scrimIndex = prefs.getInt("scrim", 1).coerceIn(0, Palette.SCRIMS.size - 1)
            toy.canvasIndex = prefs.getInt("canvas", 0).coerceIn(0, Palette.CANVAS_NAMES.size - 1)
            toy.hapticIndex = prefs.getInt("haptic", 2).coerceIn(0, Palette.HAPTIC_NAMES.size - 1)
            toy.mode = Mode.valueOf(prefs.getString("mode", Mode.BALL.name)!!)
            // The opening screen is the opening screen: whatever you were
            // playing with last time is remembered, but you still come back
            // to the front door.
            toy.screen = Screen.TITLE
        }
    }

    // ---- drawing ----------------------------------------------------------

    private fun outline(canvas: Canvas, pts: Array<FloatArray>?, cx: Float, cy: Float, r: Float,
                        fillColor: Int?, alpha: Float, withRim: Boolean) {
        if (fillColor != null) {
            fill.color = fillColor
            fill.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        }
        rim.alpha = (maxOf(0.5f, alpha) * 90f).toInt().coerceIn(0, 255)
        if (pts == null) {
            if (fillColor != null) canvas.drawCircle(cx, cy, r, fill)
            if (withRim) canvas.drawCircle(cx, cy, r, rim)
            return
        }
        path.rewind()
        path.moveTo(pts[0][0], pts[0][1])
        for (i in 1 until pts.size) path.lineTo(pts[i][0], pts[i][1])
        path.close()
        if (fillColor != null) canvas.drawPath(path, fill)
        if (withRim) canvas.drawPath(path, rim)
    }

    override fun onDraw(canvas: Canvas) {
        // The ground. Sheer leaves the window translucent and whatever is
        // behind the app shows through, which is the reason it exists; any
        // other canvas is painted solid first. The tint then washes over
        // either one, so it means the same thing in both.
        if (!toy.sheer()) canvas.drawColor(toy.canvasColor())
        val scrim = toy.scrim()
        if (scrim > 0f) canvas.drawColor(Color.argb((scrim * 255f).toInt(), 0, 0, 0))

        if (toy.screen == Screen.TITLE) {
            drawTitle(canvas)
            return
        }

        if (toy.mode == Mode.DIAL) {
            drawDial(canvas)
            // The mode row used to be skipped here, which left the way out of
            // the dial drawn nowhere and reachable only by a double tap.
            drawModeRow(canvas)
            if (toy.drawerOpen) drawDrawer(canvas)
            return
        }

        if (toy.painting()) {
            trail?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            if (strokeLive) {
                stroke?.let {
                    layerPaint.alpha = (toy.inkAlpha() * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(it, 0f, 0f, layerPaint)
                }
            }
        }

        if (toy.mode == Mode.BUMPERS) {
            for (i in toy.table.indices) {
                val b = toy.table[i]
                val c = toy.bumperCenter(b)
                outline(canvas, toy.bumperPoints(b), c[0], c[1], toy.bumperRadius(b),
                    null, 1f, true)
            }
        }

        outline(canvas, toy.ballPoints(), toy.bx, toy.by, toy.ballR(),
            toy.inkColor(), toy.inkAlpha(), true)

        drawMiss(canvas)
        if (toy.editing && toy.mode == Mode.BUMPERS) drawEditUi(canvas)
        else if (stripVisible()) drawStrip(canvas)
        drawModeRow(canvas)
        if (toy.drawerOpen) drawDrawer(canvas)
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(color: Int, target: Int, t: Float): Int = Color.rgb(
        (Color.red(color) + (Color.red(target) - Color.red(color)) * t).toInt().coerceIn(0, 255),
        (Color.green(color) + (Color.green(target) - Color.green(color)) * t).toInt().coerceIn(0, 255),
        (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * t).toInt().coerceIn(0, 255),
    )

    // ---- the opening scene ------------------------------------------------

    /**
     * On a solid canvas the scene sits straight on it. On a sheer one there is
     * no telling what is behind the window, so the title lays down its own
     * ground — still see-through, but dark enough that the name reads.
     */
    private fun titleVeil(): Float = if (toy.sheer()) 0.62f else 0f

    private fun titleInk(): Int =
        if (toy.sheer()) Color.rgb(238, 234, 226) else contrastOn(toy.canvasColor(), 1f)

    private val menuGlyphs = mapOf(
        "ball" to Shape.CIRCLE, "dial" to Shape.CIRCLE, "bumpers" to Shape.HEXAGON,
        "paint" to Shape.BAR, "ink" to Shape.SQUARE,
    )

    private fun drawTitle(canvas: Canvas) {
        val veil = titleVeil()
        if (veil > 0f) canvas.drawColor(Color.argb((veil * 255f).toInt(), 12, 12, 14))

        val ink = titleInk()
        val darkScene = Color.red(ink) > 128
        val cx = toy.w / 2f

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.letterSpacing = 0.16f
        textPaint.textSize = toy.w * 0.15f
        val fit = toy.w * 0.80f
        val measured = textPaint.measureText("NONSENSE")
        if (measured > fit) textPaint.textSize = textPaint.textSize * fit / measured
        textPaint.color = ink
        canvas.drawText("NONSENSE", cx, toy.titleBaseline(), textPaint)

        textPaint.letterSpacing = 0.08f
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = toy.w * 0.031f
        textPaint.color = withAlpha(ink, 140)
        canvas.drawText(
            "something to do with your hands",
            cx, toy.titleBaseline() + toy.viewH * 0.030f, textPaint,
        )
        textPaint.letterSpacing = 0f

        val items = toy.menuItems()
        for (c in toy.menuRows()) {
            val item = items[c.i]
            val round = c.h * 0.24f
            panelPaint.color = if (darkScene) Color.argb(28, 255, 255, 255)
            else Color.argb(24, 0, 0, 0)
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, panelPaint)
            ringPaint.color = withAlpha(ink, 52)
            ringPaint.alpha = 52
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, ringPaint)

            textPaint.textAlign = Paint.Align.LEFT
            val tx = c.x + c.h * 0.40f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = c.h * 0.29f
            textPaint.letterSpacing = 0.06f
            textPaint.color = ink
            canvas.drawText(item.label.uppercase(), tx, c.y + c.h * 0.45f, textPaint)
            textPaint.letterSpacing = 0f
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = c.h * 0.195f
            textPaint.color = withAlpha(ink, 135)
            canvas.drawText(item.blurb, tx, c.y + c.h * 0.75f, textPaint)

            // A small mark of what each one is, in the ink you are using.
            val gr = c.h * 0.24f
            val gx = c.x + c.w - c.h * 0.52f
            val gy = c.y + c.h / 2f
            val shape = menuGlyphs[item.key] ?: Shape.CIRCLE
            val glyphColor = if (item.key == "ink") toy.inkColor() else withAlpha(ink, 165)
            outline(canvas, Outlines.points(shape, gx, gy, gr, 0f), gx, gy, gr,
                glyphColor, 1f, false)
            if (item.key == "dial") {
                ringPaint.color = withAlpha(if (darkScene) Color.BLACK else Color.WHITE, 190)
                ringPaint.alpha = 190
                for (i in 0 until 8) {
                    val a = (i * Math.PI / 4.0).toFloat()
                    canvas.drawLine(
                        gx + cos(a) * gr * 0.45f, gy + sin(a) * gr * 0.45f,
                        gx + cos(a) * gr * 0.95f, gy + sin(a) * gr * 0.95f, ringPaint,
                    )
                }
            }
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    // ---- the dial ---------------------------------------------------------

    /**
     * A knurled wheel. It used to be a plain disc with one dot on it, which is
     * why nothing ever looked like it was turning: there was nothing on it to
     * watch. Now it has ribs, and at speed they are drawn wider and fainter so
     * a fast spin blurs instead of strobing backwards.
     */
    private fun drawDial(canvas: Canvas) {
        val cx = toy.w / 2f
        val cy = toy.h / 2f
        val r = toy.dialR
        val a = toy.inkAlpha()
        val ink = toy.inkColor()
        val fast = toy.dialSpeedFraction()

        fill.color = ink
        fill.alpha = (a * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, fill)

        val n = toy.dialRibs
        val step = (2.0 * Math.PI / n).toFloat()
        val inner = r * 0.54f
        val outer = r * 0.94f
        ribPaint.strokeWidth = r * (0.085f + 0.075f * fast)
        for (i in 0 until n) {
            val ang = toy.dialAngle + step * i
            val c = cos(ang)
            val si = sin(ang)
            // One rib is marked, so you can count turns however fast it goes.
            val marked = i == 0
            ribPaint.color = if (marked) mix(ink, Color.BLACK, 0.55f)
            else mix(ink, Color.WHITE, 0.5f)
            ribPaint.alpha = ((if (marked) 0.95f else 0.8f - 0.3f * fast) * a * 255f)
                .toInt().coerceIn(0, 255)
            canvas.drawLine(
                cx + c * inner, cy + si * inner, cx + c * outer, cy + si * outer, ribPaint,
            )
        }

        fill.color = mix(ink, Color.BLACK, 0.28f)
        fill.alpha = (a * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r * 0.46f, fill)
        rim.alpha = (maxOf(0.5f, a) * 90f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r * 0.46f, rim)
        canvas.drawCircle(cx, cy, r, rim)

        // The index the ribs click past, just outside the rim at the top.
        path.rewind()
        val tip = cy - r * 1.02f
        path.moveTo(cx, tip + r * 0.09f)
        path.lineTo(cx - r * 0.055f, tip - r * 0.02f)
        path.lineTo(cx + r * 0.055f, tip - r * 0.02f)
        path.close()
        fill.color = Color.rgb(112, 41, 41)
        fill.alpha = 235
        canvas.drawPath(path, fill)
    }

    /** A missed catch leaves a ring for a moment. No sound, no score. */
    private fun drawMiss(canvas: Canvas) {
        if (toy.missAt <= 0L) return
        val age = (System.currentTimeMillis() - toy.missAt) / 420f
        if (age < 0f || age > 1f) return
        ringPaint.color = toy.inkColor()
        ringPaint.alpha = ((1f - age) * 115f).toInt().coerceIn(0, 255)
        val base = minOf(toy.w, toy.h) * 0.02f
        canvas.drawCircle(toy.missX, toy.missY, base + age * base * 3.5f, ringPaint)
    }

    /** The four toys, the palette, and whatever toggle this mode has. */
    private fun drawModeRow(canvas: Canvas) {
        val cells = toy.modeCells()
        val labels = toy.modeLabels()
        textPaint.textAlign = Paint.Align.CENTER
        // The row can hold anywhere from six chips to eight, so the type has
        // to follow the cell: at a fixed size "bumpers" overran its own chip
        // as soon as the menu button joined the row.
        textPaint.textSize = minOf(
            minOf(toy.w, toy.viewH) * 0.028f,
            (cells.firstOrNull()?.w ?: toy.w) * 0.155f,
        )
        for (c in cells) {
            val label = labels[c.i]
            val on = when (label) {
                "ball" -> toy.mode == Mode.BALL
                "dial" -> toy.mode == Mode.DIAL
                "bumpers" -> toy.mode == Mode.BUMPERS
                "paint" -> toy.mode == Mode.PAINT
                "ink" -> toy.drawerOpen
                "edit" -> toy.editing
                "catch" -> toy.mustCatch
                else -> false
            }
            panelPaint.color = if (on) Color.argb(235, 58, 58, 60) else Color.argb(150, 226, 220, 205)
            canvas.drawRect(c.x + 2f, c.y + 2f, c.x + c.w - 2f, c.y + c.h - 2f, panelPaint)
            textPaint.color = if (on) Color.rgb(232, 228, 220) else Color.argb(190, 58, 58, 60)
            canvas.drawText(
                label.uppercase(), c.x + c.w / 2f,
                c.y + c.h / 2f + textPaint.textSize * 0.36f, textPaint,
            )
        }
    }

    private fun drawStrip(canvas: Canvas) {
        val sh = toy.stripH()
        val cy = toy.stripTop() + sh / 2f
        val chipR = sh * 0.28f
        for (z in toy.stripZones()) {
            val step = (z.x1 - z.x0) / z.count
            for (i in 0 until z.count) {
                val cx = z.x0 + step * (i + 0.5f)
                when (z.kind) {
                    "color" -> {
                        fill.color = Palette.COLORS[i][toy.inkTone]
                        fill.alpha = 255
                        canvas.drawCircle(cx, cy, chipR, fill)
                        if (i == toy.inkFamily) {
                            rim.alpha = 200
                            canvas.drawCircle(cx, cy, chipR + 5f, rim)
                        }
                    }
                    "size" -> {
                        val rr = chipR * (0.3f + 0.85f * (Toy.SIZES[i] / Toy.SIZES.last()))
                        val pts = Outlines.points(toy.shape, cx, cy, rr, 0f)
                        outline(canvas, pts, cx, cy, rr, Color.argb(140, 58, 58, 60), 1f,
                            i == toy.sizeIndex)
                    }
                    "shape" -> {
                        val s = Shape.entries[i]
                        val on = s == toy.shape
                        val pts = Outlines.points(s, cx, cy, chipR * 0.92f, 0f)
                        outline(canvas, pts, cx, cy, chipR * 0.92f,
                            if (on) Color.rgb(58, 58, 60) else Color.argb(90, 58, 58, 60),
                            1f, on)
                    }
                }
            }
        }
    }

    private fun drawEditUi(canvas: Canvas) {
        toy.table.getOrNull(toy.selected)?.let { b ->
            val c = toy.bumperCenter(b)
            val r = toy.bumperRadius(b)
            val pts = toy.bumperPoints(b)
            if (pts == null) canvas.drawCircle(c[0], c[1], r, dashed)
            else {
                path.rewind()
                path.moveTo(pts[0][0], pts[0][1])
                for (i in 1 until pts.size) path.lineTo(pts[i][0], pts[i][1])
                path.close()
                canvas.drawPath(path, dashed)
            }
            val hs = toy.handles(b)
            val hr = minOf(toy.w, toy.h) * 0.022f
            fill.color = Color.rgb(112, 41, 41); fill.alpha = 255
            canvas.drawCircle(hs[0][0], hs[0][1], hr, fill)
            if (b.shape != Shape.CIRCLE) {
                rim.alpha = 120
                canvas.drawLine(c[0], c[1], hs[1][0], hs[1][1], rim)
                fill.color = Color.rgb(70, 90, 120)
                canvas.drawCircle(hs[1][0], hs[1][1], hr, fill)
            }
        }

        val btns = toy.toolbarButtons()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = minOf(toy.w, toy.h) * 0.032f
        for (btn in btns) {
            panelPaint.color = Color.argb(235, 226, 220, 205)
            canvas.drawRoundRect(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, 6f, 6f, panelPaint)
            rim.alpha = 60
            canvas.drawRoundRect(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, 6f, 6f, rim)
            val live = btn.i == 0 || toy.toolbarLabels[btn.i] == "reset" ||
                toy.toolbarLabels[btn.i] == "done" || toy.selected >= 0
            textPaint.color = if (live) Color.rgb(58, 58, 60) else Color.argb(90, 58, 58, 60)
            canvas.drawText(
                toy.toolbarLabels[btn.i],
                btn.x + btn.w / 2f,
                btn.y + btn.h / 2f + textPaint.textSize * 0.35f,
                textPaint,
            )
        }
    }

    private fun drawDrawer(canvas: Canvas) {
        val b = toy.drawerBox()
        panelPaint.color = Color.argb(247, 232, 228, 220)
        canvas.drawRoundRect(b.x, b.y, b.x + b.w, b.y + b.h, 10f, 10f, panelPaint)
        rim.alpha = 70
        canvas.drawRoundRect(b.x, b.y, b.x + b.w, b.y + b.h, 10f, 10f, rim)

        textPaint.textSize = minOf(toy.w, toy.h) * 0.026f
        textPaint.color = Color.argb(150, 58, 58, 60)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("INK  ·  ${Palette.NAMES[toy.inkFamily]}", b.gx, b.gy - textPaint.textSize * 0.5f, textPaint)

        for (f in Palette.COLORS.indices) {
            for (t in Palette.COLORS[f].indices) {
                val x = b.gx + f * b.cell
                val y = b.gy + t * b.cell
                fill.color = Palette.COLORS[f][t]
                fill.alpha = 255
                canvas.drawRect(x + 2f, y + 2f, x + b.cell - 2f, y + b.cell - 2f, fill)
                rim.alpha = 46                  // or the palest tones dissolve
                canvas.drawRect(x + 2f, y + 2f, x + b.cell - 2f, y + b.cell - 2f, rim)
                if (f == toy.inkFamily && t == toy.inkTone) {
                    selPaint.color = contrastOn(Palette.COLORS[f][t], 1f)
                    canvas.drawRect(x + 5f, y + 5f, x + b.cell - 5f, y + b.cell - 5f, selPaint)
                }
            }
        }

        drawChipRow(canvas, b, b.ay, Palette.ALPHAS.size, "TRANSLUCENCY", toy.inkAlphaIndex) { c, i ->
            fill.color = toy.inkColor()
            fill.alpha = (Palette.ALPHAS[i] * 255f).toInt()
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
            textPaint.color = contrastOn(toy.inkColor(), Palette.ALPHAS[i])
            "${(Palette.ALPHAS[i] * 100f).toInt()}%"
        }
        drawChipRow(canvas, b, b.ky, Palette.CANVAS_NAMES.size, "CANVAS", toy.canvasIndex) { c, i ->
            if (i == 0) {
                // Sheer has no colour to show, so show the absence of one.
                ringPaint.color = Color.argb(120, 58, 58, 60)
                ringPaint.alpha = 120
                var x = c.x + 4f
                while (x < c.x + c.w - 4f) {
                    canvas.drawLine(x, c.y + c.h - 4f, x + c.h * 0.5f, c.y + 4f, ringPaint)
                    x += c.h * 0.34f
                }
                textPaint.color = Color.rgb(58, 58, 60)
            } else {
                fill.color = Palette.CANVAS_COLORS[i]
                fill.alpha = 255
                canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
                textPaint.color = contrastOn(Palette.CANVAS_COLORS[i], 1f)
            }
            Palette.CANVAS_NAMES[i]
        }
        drawChipRow(canvas, b, b.sy, Palette.SCRIMS.size, "SCREEN TINT", toy.scrimIndex) { c, i ->
            fill.color = Color.BLACK
            fill.alpha = (Palette.SCRIMS[i] * 255f).toInt()
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
            textPaint.color = contrastOn(Color.BLACK, Palette.SCRIMS[i])
            "${(Palette.SCRIMS[i] * 100f).toInt()}%"
        }
        drawChipRow(canvas, b, b.hy, Palette.HAPTIC_NAMES.size, "HAPTICS", toy.hapticIndex) { c, i ->
            val on = Palette.HAPTIC_SCALES[i]
            fill.color = Color.rgb(58, 58, 60)
            fill.alpha = (34f + on * 150f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
            textPaint.color = contrastOn(Color.rgb(58, 58, 60), 0.13f + on * 0.59f)
            Palette.HAPTIC_NAMES[i]
        }
    }

    private fun drawChipRow(
        canvas: Canvas, b: Toy.Box, y: Float, n: Int, label: String, selected: Int,
        body: (Toy.Chip, Int) -> String,
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = minOf(toy.w, toy.h) * 0.026f
        textPaint.color = Color.argb(150, 58, 58, 60)
        canvas.drawText(label, b.gx, y - textPaint.textSize * 0.5f, textPaint)
        for (c in toy.drawerChips(y, n, b)) {
            panelPaint.color = Color.argb(210, 255, 255, 255)
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, panelPaint)
            val text = body(c, c.i)
            rim.alpha = if (c.i == selected) 220 else 50
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, rim)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = minOf(toy.w, toy.h) * 0.026f
            canvas.drawText(text, c.x + c.w / 2f, c.y + c.h / 2f + textPaint.textSize * 0.35f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    /** Text that stays readable on a swatch, whatever it is filled with. */
    private fun contrastOn(color: Int, alpha: Float): Int {
        val r = 255 + (((color shr 16) and 0xff) - 255) * alpha
        val g = 255 + (((color shr 8) and 0xff) - 255) * alpha
        val bl = 255 + ((color and 0xff) - 255) * alpha
        val lum = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f
        return if (lum > 0.55f) Color.rgb(58, 58, 60) else Color.rgb(242, 239, 232)
    }
}

/**
 * The phone's answer to a collision.
 *
 * The first cut of this used performHapticFeedback(CLOCK_TICK), which is the
 * lightest constant the platform has and is silenced outright when the system
 * touch-feedback switch is off. The second used createOneShot for nine to
 * twenty-four milliseconds — and a nine millisecond pulse is shorter than the
 * time a linear actuator takes to reach full travel, so on a modern phone it
 * moves almost nothing. Both were code that ran and could not be felt.
 *
 * So: composition primitives where the device has them, which are the tuned
 * waveforms the platform itself uses for its own clicks; the predefined
 * effects below that; and a one-shot long enough to actually move an actuator
 * as the floor. Every one carries game usage attributes, so this is a toy
 * making a noise rather than a button being pressed, and the touch-feedback
 * switch does not apply.
 */
private class Haptics(context: Context) {

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Held in a local: a null check on a property does not carry into a
    // lambda, so reading `vibrator` straight inside runCatching will not
    // compile however obviously non-null it looks from here.
    private val hasPrimitives: Boolean = run {
        val v = vibrator
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v != null &&
            runCatching {
                v.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                )
            }.getOrDefault(false)
    }

    /** A ball meeting something solid. [strength] runs 0 to 1. */
    fun knock(strength: Float, sharp: Boolean) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.02f) return
        if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = VibrationEffect.startComposition()
            c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.25f + 0.75f * s)
            // A bumper throws the ball back, so it gets a second, softer beat
            // a few milliseconds later. A wall stays a single flat knock.
            if (!sharp && s > 0.35f) {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f * s, 18)
            }
            play(c.compose())
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            play(
                VibrationEffect.createPredefined(
                    when {
                        s > 0.6f -> VibrationEffect.EFFECT_HEAVY_CLICK
                        s > 0.25f -> VibrationEffect.EFFECT_CLICK
                        else -> VibrationEffect.EFFECT_TICK
                    },
                ),
            )
            return
        }
        oneShot((20f + s * 30f).toLong(), (90f + s * 165f).toInt())
    }

    /** A detent, a chip, a control answering. */
    fun tick(strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.02f) return
        if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            play(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.2f + 0.8f * s)
                    .compose(),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            return
        }
        oneShot(18L, (70f + s * 120f).toInt())
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255)
        else VibrationEffect.DEFAULT_AMPLITUDE
        play(VibrationEffect.createOneShot(ms, amp))
    }

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        runCatching { v.vibrate(effect, attrs) }
    }
}
