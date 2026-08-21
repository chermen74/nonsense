package com.nonsense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.HapticFeedbackConstants
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
    private val path = Path()

    // ---- haptics ----------------------------------------------------------
    // performHapticFeedback(CLOCK_TICK) is the lightest tap the platform has —
    // it is meant for scroll ticks, it is gated by the system touch-feedback
    // setting, and plenty of devices render it as nothing. A bump you can
    // actually feel needs the vibrator, with weight scaled to the impact.
    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }

    /** [strength] runs 0 to 1. A wall is a flat knock; a bumper kicks back. */
    private fun bump(strength: Float, wall: Boolean) {
        val v = vibrator ?: return
        if (strength <= 0f) return
        val ms = ((if (wall) 9f else 13f) + strength * (if (wall) 15f else 22f)).toLong()
        val amp = (70f + strength * 185f).toInt().coerceIn(1, 255)
        runCatching {
            v.vibrate(
                if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amp)
                else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

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
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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

        if (toy.drawerOpen) {
            val before = toy.inkFamily to toy.inkTone
            val alphaBefore = toy.inkAlphaIndex
            when (toy.drawerHit(x, y)) {
                "outside" -> toy.drawerOpen = false
                "ink", "alpha" -> {
                    // the stroke must settle before the ink under it changes
                    if (before != (toy.inkFamily to toy.inkTone) || alphaBefore != toy.inkAlphaIndex)
                        settleStroke()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                "scrim" -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            save()
            return
        }

        if (toy.editing && toy.mode == Mode.BUMPERS) {
            toy.toolbarHit(x, y)?.let {
                toy.doToolbar(it)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            save()
            return
        }

        if (stripVisible() && toy.inStrip(y)) {
            val familyBefore = toy.inkFamily
            if (toy.stripTap(x)) {
                if (familyBefore != toy.inkFamily) settleStroke()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                save()
                return
            }
        }

        if (isDoubleTap(event)) {
            removeCallbacks(longPress)
            toy.cycleMode()
            trailStarted = false
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            save()
            return
        }

        postDelayed(longPress, longPressMs)

        if (toy.mode == Mode.DIAL) {
            toy.grabDial(x, y)
            dialLastAngle = toy.angleTo(x, y)
            dialLastTime = event.eventTime
        } else {
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
            if (!toy.grab(x, y, System.currentTimeMillis())) {
                // reached for a ball that had to be caught, and it wasn't there
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return
            }
            if (toy.painting()) {
                lastTrailX = toy.bx; lastTrailY = toy.by; trailStarted = true
            }
        }
    }

    private var dialLastAngle = 0f
    private var dialLastTime = 0L

    private fun onMove(event: MotionEvent) {
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
            if (!toy.dialGrab) return
            val a = toy.angleTo(x, y)
            var delta = a - dialLastAngle
            while (delta > Math.PI) delta -= (2.0 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2.0 * Math.PI).toFloat()
            val dtms = (event.eventTime - dialLastTime).coerceAtLeast(1L)
            toy.dialAngle += delta
            toy.dialOmega = delta / (dtms / 1000f)
            dialLastAngle = a
            dialLastTime = event.eventTime
        } else if (toy.dragging) {
            velocityTracker?.addMovement(event)
            toy.drag(x, y)
            if (toy.painting()) layTrail()
        }
    }

    private fun onUp(event: MotionEvent) {
        removeCallbacks(longPress)
        if (toy.editing && toy.mode == Mode.BUMPERS) {
            if (editDrag != null) save()
            editDrag = null
            return
        }
        if (toy.mode == Mode.DIAL) {
            toy.dialGrab = false
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
        toy.mode != Mode.DIAL && !toy.drawerOpen && !(toy.editing && toy.mode == Mode.BUMPERS)

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
            .putInt("prefsVersion", 2)
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
            toy.mode = Mode.valueOf(prefs.getString("mode", Mode.BALL.name)!!)
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
        // The sheer scrim. On desktop this used to be a CSS background; painting
        // it here means one adjustable value and identical behaviour over a
        // translucent window.
        val scrim = toy.scrim()
        if (scrim > 0f) canvas.drawColor(Color.argb((scrim * 255f).toInt(), 0, 0, 0))

        if (toy.mode == Mode.DIAL) {
            val cx = toy.w / 2f
            val cy = toy.h / 2f
            outline(canvas, null, cx, cy, toy.dialR, toy.inkColor(), toy.inkAlpha(), true)
            canvas.drawCircle(
                cx + cos(toy.dialAngle) * toy.dialR * 0.8f,
                cy + sin(toy.dialAngle) * toy.dialR * 0.8f,
                toy.dialR * 0.06f, rim,
            )
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
        textPaint.textSize = minOf(toy.w, toy.viewH) * 0.028f
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
        drawChipRow(canvas, b, b.sy, Palette.SCRIMS.size, "SCREEN TINT", toy.scrimIndex) { c, i ->
            fill.color = Color.BLACK
            fill.alpha = (Palette.SCRIMS[i] * 255f).toInt()
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
            textPaint.color = contrastOn(Color.BLACK, Palette.SCRIMS[i])
            "${(Palette.SCRIMS[i] * 100f).toInt()}%"
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
