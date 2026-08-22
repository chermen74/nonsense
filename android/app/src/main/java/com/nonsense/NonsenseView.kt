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
 *   double tap            cycle the five toys
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

    /** Whether the code has been entered on this install. */
    private var codeUnlocked = false

    /**
     * Set by whoever owns the store connection. The view knows nothing about
     * Play — it asks for a purchase and is told, later, that the tier changed.
     */
    var onBuy: (() -> Unit)? = null
    var onRestore: (() -> Unit)? = null

    /** Called from the billing callback; safe from any thread. */
    fun applyTier(tier: Tier, price: String?) {
        post {
            toy.priceText = price
            // The code outlives Play's answer. There is no subscription for
            // Play to find, so without this every launch would take the
            // unlock straight back off again.
            if (codeUnlocked) toy.tier = Tier.FULL
            else if (tier == Tier.FULL && !toy.full()) toy.unlock() else toy.tier = tier
            toy.clampToTier()
            save()
            invalidate()
        }
    }

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
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        // two-finger tap wipes the painting, the etched lightning or the glass
        if ((toy.painting() || toy.mode == Mode.BOLT || toy.mode == Mode.GLASS) &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.pointerCount == 2
        ) {
            removeCallbacks(longPress)
            clearTrail()
            toy.clearEtched()
            toy.clearGlass()
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

        if (toy.screen == Screen.PAYWALL) {
            if (toy.codeOpen) {
                toy.keypadHit(x, y)?.let { key ->
                    tick()
                    if (toy.typeCode(key)) {
                        // Remembered, and it outranks the store's answer on
                        // the next launch — the store will go on saying "not
                        // subscribed" for ever.
                        codeUnlocked = true
                        prefs.edit().putBoolean("codeUnlock", true).apply()
                        save()
                    }
                }
                return
            }
            val p = toy.codePromptCell()
            if (x >= p.x && x <= p.x + p.w && y >= p.y && y <= p.y + p.h) {
                tick(); toy.openCode(); return
            }
            when (toy.paywallHit(x, y)) {
                "subscribe" -> { tick(); onBuy?.invoke() }
                "restore" -> { tick(); onRestore?.invoke() }
                "not now" -> { tick(); toy.dismissPaywall() }
            }
            return
        }

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
                "outside" -> toy.closeDrawer()
                "locked" -> tick()
                "bumper" -> tick()
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
                if (hypot(x - hs[0][0], y - hs[0][1]) < reach) { editDrag = "pull"; return }
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

        if (toy.mode == Mode.GLASS) {
            // A press is the whole gesture. It breaks where you pressed, at
            // once, because glass does not wait to see whether you meant it.
            if (toy.breakGlass(x, y)) {
                removeCallbacks(longPress)
                tick()
            }
            return
        }

        if (toy.mode == Mode.BOLT) {
            // Nothing to grab: a bolt is thrown, not carried.
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
            velocityTracker?.addMovement(event)
            return
        }

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
                // Pulls both axes at once: out along the bumper's width and
                // it widens, down and it heightens. Stretching needs no third
                // handle this way, and a phone has nowhere to put one.
                "pull" -> toy.pullTo(b, x, y)
                "rotate" -> {
                    b.rot = kotlin.math.atan2(y - b.ny * toy.h, x - b.nx * toy.w) -
                        (Math.PI / 2.0).toFloat()
                }
            }
            return
        }

        if (toy.mode == Mode.BOLT) {
            velocityTracker?.addMovement(event)
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
        if (toy.mode == Mode.BOLT) {
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
            // A missed strike gets the same faint tap as a missed catch: you
            // reached, and nothing happened, and you should know which.
            if (!toy.fireBolt(event.x, event.y, fx, fy)) tick()
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

    // Not on the dial, which has neither a ball nor an ink to choose. On
    // lightning and glass the strip is the palette and nothing else — see
    // stripZones.
    private fun stripVisible(): Boolean =
        toy.screen == Screen.PLAY && toy.mode != Mode.DIAL &&
            !toy.drawerOpen && !(toy.editing && toy.mode == Mode.BUMPERS)

    // ---- persistence ------------------------------------------------------

    private fun save() {
        prefs.edit()
            .putString("table", toy.encodeTable())
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
            .putString("tier", toy.tier.name)
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
            // The ground the app opens on changed from sheer to slate. An
            // install that had never been told there was a choice would have
            // kept the old default for ever, so it is taken back once.
            if (prefs.getInt("prefsVersion", 1) < 4) {
                prefs.edit().putInt("prefsVersion", 4).remove("canvas").apply()
            }
            prefs.getString("table", null)?.takeIf { it.isNotBlank() }?.let { raw ->
                val parsed = toy.decodeTable(raw)
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
            // A cached answer, so the free tier is not the first thing a
            // paying customer sees on a cold start. Play is asked again on
            // every launch and its answer wins.
            toy.tier = Tier.valueOf(prefs.getString("tier", Tier.FREE.name)!!)
            codeUnlocked = prefs.getBoolean("codeUnlock", false)
            if (codeUnlocked) toy.tier = Tier.FULL
            toy.canvasIndex = prefs.getInt("canvas", Toy.DEFAULT_CANVAS)
                .coerceIn(0, Palette.CANVAS_NAMES.size - 1)
            toy.hapticIndex = prefs.getInt("haptic", 2).coerceIn(0, Palette.HAPTIC_NAMES.size - 1)
            toy.mode = Mode.valueOf(prefs.getString("mode", Mode.BALL.name)!!)
            // The opening screen is the opening screen: whatever you were
            // playing with last time is remembered, but you still come back
            // to the front door.
            toy.screen = Screen.TITLE
            toy.clampToTier()
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

        if (toy.screen == Screen.PAYWALL) {
            if (toy.codeOpen) drawKeypad(canvas) else drawPaywall(canvas)
            return
        }

        if (toy.screen == Screen.TITLE) {
            drawTitle(canvas)
            return
        }

        if (toy.mode == Mode.GLASS) {
            drawGlass(canvas)
            if (stripVisible()) drawStrip(canvas)
            drawModeRow(canvas)
            if (toy.drawerOpen) drawDrawer(canvas)
            return
        }

        if (toy.mode == Mode.BOLT) {
            drawBolts(canvas)
            if (stripVisible()) drawStrip(canvas)
            drawModeRow(canvas)
            if (toy.drawerOpen) drawDrawer(canvas)
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
                // Not quite solid, so a painting still shows faintly through
                // the table rather than being walled off by it.
                drawBumper(canvas, b, c)
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
            // The blurb has to end before the glyph does, and a phone row is
            // narrow enough that at a fixed size it ran off its own row.
            textPaint.textSize = c.h * 0.195f
            val room = c.w - (tx - c.x) - c.h * 0.95f
            val measured = textPaint.measureText(item.blurb)
            if (measured > room) textPaint.textSize =
                maxOf(textPaint.textSize * room / measured, toy.viewH * 0.012f)
            textPaint.color = withAlpha(ink, 135)
            canvas.drawText(item.blurb, tx, c.y + c.h * 0.75f, textPaint)

            // A small mark of what each one is, in the ink you are using.
            val gr = c.h * 0.24f
            val gx = c.x + c.w - c.h * 0.52f
            val gy = c.y + c.h / 2f
            val shape = menuGlyphs[item.key] ?: Shape.CIRCLE
            val locked = toy.menuLocked(item.key)
            val glyphColor = if (item.key == "ink") toy.inkColor() else withAlpha(ink, 165)
            if (locked) drawLock(canvas, gx, gy, gr, withAlpha(ink, 150))
            else if (item.key == "unlock") drawLock(canvas, gx, gy, gr, Color.rgb(112, 41, 41))
            else if (item.key == "bolt") drawBoltGlyph(canvas, gx, gy, gr, glyphColor)
            else outline(canvas, Outlines.points(shape, gx, gy, gr, 0f), gx, gy, gr,
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

    /**
     * Lightning gets a stroke rather than an outline. Every other glyph is the
     * thing itself; a triangle said nothing about what the row does.
     */
    private fun drawBoltGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        ringPaint.color = color
        ringPaint.alpha = Color.alpha(color)
        ringPaint.strokeWidth = r * 0.3f
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.strokeJoin = Paint.Join.ROUND
        path.rewind()
        path.moveTo(cx - r * 0.5f, cy - r * 0.9f)
        path.lineTo(cx + r * 0.3f, cy - r * 0.3f)
        path.lineTo(cx - r * 0.3f, cy + r * 0.3f)
        path.lineTo(cx + r * 0.5f, cy + r * 0.9f)
        canvas.drawPath(path, ringPaint)
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.strokeJoin = Paint.Join.MITER
    }

    /** A padlock, small enough to sit on a chip. */
    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        fill.alpha = 255
        canvas.drawRoundRect(
            cx - r * 0.62f, cy - r * 0.1f, cx + r * 0.62f, cy + r * 0.78f,
            r * 0.18f, r * 0.18f, fill,
        )
        ringPaint.color = color
        ringPaint.alpha = 255
        ringPaint.strokeWidth = maxOf(1.5f, r * 0.24f)
        path.rewind()
        path.addArc(cx - r * 0.38f, cy - r * 0.78f, cx + r * 0.38f, cy + r * 0.02f, 180f, 180f)
        canvas.drawPath(path, ringPaint)
        ringPaint.strokeWidth = 3f
    }

    /**
     * What the unlock buys, in words, with the price on the button. It is a
     * screen rather than a dialog because it has to say enough to be worth
     * reading, and because a fidget toy interrupting you with a modal is a
     * worse thing than a page you chose to open.
     */
    private fun drawPaywall(canvas: Canvas) {
        val veil = titleVeil()
        if (veil > 0f) canvas.drawColor(Color.argb((veil * 255f).toInt(), 12, 12, 14))
        val ink = titleInk()
        val darkScene = Color.red(ink) > 128
        val cx = toy.w / 2f

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.letterSpacing = 0.12f
        textPaint.textSize = minOf(toy.w * 0.085f, toy.viewH * 0.042f)
        textPaint.color = ink
        canvas.drawText("UNLOCK EVERYTHING", cx, toy.viewH * 0.19f, textPaint)
        textPaint.letterSpacing = 0f
        textPaint.typeface = Typeface.DEFAULT

        val lines = toy.paywallLines()
        textPaint.textAlign = Paint.Align.LEFT
        // Measured, not assumed: at a fixed size the longer promises ran off
        // the right edge, which is a poor advertisement for care.
        textPaint.textSize = minOf(toy.w * 0.036f, toy.viewH * 0.019f)
        val lx = toy.w * 0.14f
        val room = toy.w - lx - toy.w * 0.06f
        val widest = lines.maxOf { textPaint.measureText(it) }
        if (widest > room) textPaint.textSize =
            maxOf(textPaint.textSize * room / widest, toy.viewH * 0.011f)

        val top = toy.viewH * 0.28f
        val step = minOf(textPaint.textSize * 2.4f,
            (toy.paywallButtons().first().y - top) / lines.size)
        var ly = top + step * 0.5f
        for (line in lines) {
            fill.color = ink
            fill.alpha = 150
            canvas.drawCircle(lx - toy.w * 0.035f, ly - textPaint.textSize * 0.3f,
                textPaint.textSize * 0.16f, fill)
            textPaint.color = withAlpha(ink, 215)
            canvas.drawText(line, lx, ly, textPaint)
            ly += step
        }

        for (c in toy.paywallButtons()) {
            val label = toy.paywallLabels[c.i]
            val primary = label == "subscribe"
            val round = c.h * 0.22f
            panelPaint.color = when {
                primary -> Color.rgb(112, 41, 41)
                darkScene -> Color.argb(30, 255, 255, 255)
                else -> Color.argb(24, 0, 0, 0)
            }
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, panelPaint)
            if (!primary) {
                ringPaint.color = withAlpha(ink, 60)
                ringPaint.alpha = 60
                canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, ringPaint)
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = c.h * 0.3f
            textPaint.color = if (primary) Color.rgb(240, 236, 228) else withAlpha(ink, 190)
            canvas.drawText(
                if (primary) toy.unlockLabel() else label,
                c.x + c.w / 2f, c.y + c.h / 2f + textPaint.textSize * 0.35f, textPaint,
            )
        }

        // The terms, above the buttons: what it costs, how often, and that it
        // renews. Apple rejects a subscription paywall that leaves this to the
        // store sheet, and it is the honest thing to do anyway.
        val prompt = toy.codePromptCell()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = minOf(toy.w * 0.031f, toy.viewH * 0.016f)
        val terms = toy.subscriptionTerms()
        var ty = prompt.y - prompt.h * 0.5f - textPaint.textSize * terms.size * 1.35f
        for (line in terms) {
            textPaint.color = withAlpha(ink, 150)
            canvas.drawText(line, cx, ty, textPaint)
            ty += textPaint.textSize * 1.35f
        }

        textPaint.textSize = minOf(prompt.h * 0.45f, toy.w * 0.036f)
        textPaint.color = withAlpha(ink, 170)
        canvas.drawText(Toy.CODE_PROMPT, cx,
            prompt.y + prompt.h / 2f + textPaint.textSize * 0.35f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** The keypad, which replaces the buttons once the code row is tapped. */
    private fun drawKeypad(canvas: Canvas) {
        val veil = titleVeil()
        if (veil > 0f) canvas.drawColor(Color.argb((veil * 255f).toInt(), 12, 12, 14))
        val ink = titleInk()
        val darkScene = Color.red(ink) > 128
        val cx = toy.w / 2f

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.letterSpacing = 0.12f
        textPaint.textSize = minOf(toy.w * 0.07f, toy.viewH * 0.034f)
        textPaint.color = ink
        canvas.drawText(if (toy.codeWrong) "NOT THAT ONE" else "ENTER THE CODE",
            cx, toy.viewH * 0.19f, textPaint)
        textPaint.letterSpacing = 0f
        textPaint.typeface = Typeface.DEFAULT

        // One dot per digit, filled as they are typed. A row of dots says how
        // long the code is without a field that needs a cursor.
        val cells = toy.keypadCells()
        val r = minOf(toy.w * 0.016f, toy.viewH * 0.008f)
        val gap = r * 3.4f
        val dotsY = cells.first().y - r * 6f
        val x0 = cx - (Toy.CODE_LENGTH - 1) * gap / 2f
        for (i in 0 until Toy.CODE_LENGTH) {
            val on = i < toy.codeEntry.length
            fill.color = ink
            fill.alpha = if (on) 230 else 60
            canvas.drawCircle(x0 + gap * i, dotsY, r, fill)
        }

        val keys = toy.keypadKeys()
        for (c in cells) {
            val key = keys[c.i]
            if (key.isEmpty()) continue
            val round = c.h * 0.22f
            panelPaint.color =
                if (darkScene) Color.argb(30, 255, 255, 255) else Color.argb(24, 0, 0, 0)
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, panelPaint)
            ringPaint.color = withAlpha(ink, 60)
            ringPaint.alpha = 60
            canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, round, round, ringPaint)
            textPaint.textSize = c.h * (if (key == "del") 0.3f else 0.42f)
            textPaint.color = withAlpha(ink, 210)
            canvas.drawText(if (key == "del") "back" else key,
                c.x + c.w / 2f, c.y + c.h / 2f + textPaint.textSize * 0.35f, textPaint)
        }
    }

    /**
     * Lightning: a wide dim wash of the ink under a hairline of near-white.
     * One stroke would read as a wire; it is the trio that reads as a glow,
     * and it costs three draws rather than a blur filter.
     *
     * The etchings go down first, cool, so a fresh strike is plainly the
     * bright one and the scene behind it is a record rather than a crowd.
     */
    private fun drawBolts(canvas: Canvas) {
        val short = minOf(toy.w, toy.h)
        for (e in toy.etched)
            boltPath(canvas, e.nodes, null, e.argb, short, Toy.ETCH_ALPHA,
                Toy.boltWeight(e.gen), Toy.BOLT_CORE_COOL, cool = true)
        for (b in toy.bolts) {
            if (b.nodes.size < 2) continue
            boltPath(canvas, b.nodes, if (b.struck) null else floatArrayOf(b.x, b.y),
                b.argb, short, toy.boltAlpha(b), Toy.boltWeight(b.gen), Toy.BOLT_CORE_HOT,
                cool = false)
        }
    }

    /**
     * Glass. The seam is dark and the edge beside it is the ink you chose —
     * that pair is the whole illusion: a fracture face catching the light, in
     * a colour, against the dark of the crack itself.
     */
    private fun drawGlass(canvas: Canvas) {
        val short = minOf(toy.w, toy.h)
        for (b in toy.breaks) {
            val edge = mix(b.argb, Color.WHITE, 0.34f)
            for (c in b.cracks) {
                if (c.nodes.size < 2) continue
                path.rewind()
                path.moveTo(c.nodes[0][0], c.nodes[0][1])
                for (i in 1 until c.nodes.size) path.lineTo(c.nodes[i][0], c.nodes[i][1])

                // The seam: the gap itself, darker than anything around it.
                boltPaint.color = Color.BLACK
                boltPaint.alpha = if (c.ring) 90 else 120
                boltPaint.strokeWidth = short * (if (c.ring) 0.006f else 0.008f)
                canvas.drawPath(path, boltPaint)

                // The exposed edge, offset by a hair so it reads as a face
                // rather than as an outline.
                canvas.save()
                canvas.translate(short * 0.0022f, -short * 0.0022f)
                boltPaint.color = edge
                boltPaint.alpha = if (c.ring) 150 else 205
                boltPaint.strokeWidth = short * (if (c.ring) 0.0028f else 0.0038f)
                canvas.drawPath(path, boltPaint)
                canvas.restore()
            }
            // The point of impact: a small crater of the same ink.
            fill.color = mix(b.argb, Color.WHITE, 0.5f)
            fill.alpha = 190
            canvas.drawCircle(b.x, b.y, short * 0.012f, fill)
        }
    }

    /** One path, three widths. [head] extends it to a bolt still travelling. */
    private fun boltPath(
        canvas: Canvas,
        nodes: List<FloatArray>,
        head: FloatArray?,
        argb: Int,
        short: Float,
        a: Float,
        weight: Float,
        hot: Float,
        cool: Boolean,
    ) {
        if (nodes.size < 2) return
        val core = mix(argb, Color.WHITE, hot)
        path.rewind()
        path.moveTo(nodes[0][0], nodes[0][1])
        for (i in 1 until nodes.size) path.lineTo(nodes[i][0], nodes[i][1])
        head?.let { path.lineTo(it[0], it[1]) }

        // Three passes, and the middle one is the reason it reads on a pale
        // ground as well as a dark one: a white filament laid straight on
        // paper is invisible, so it gets its own dark sheath to sit against.
        boltPaint.color = argb
        boltPaint.alpha = (a * 72f).toInt().coerceIn(0, 255)
        boltPaint.strokeWidth = short * 0.026f * weight
        canvas.drawPath(path, boltPaint)

        // Etchings get two passes rather than three: a fan lands a lot of
        // them, and the middle sheath is what makes a live strike sit on a
        // pale ground, not what makes a cooled one readable.
        if (!cool) {
            boltPaint.alpha = (a * 178f).toInt().coerceIn(0, 255)
            boltPaint.strokeWidth = short * 0.013f * weight
            canvas.drawPath(path, boltPaint)
        }

        boltPaint.color = core
        boltPaint.alpha = (a * 242f).toInt().coerceIn(0, 255)
        boltPaint.strokeWidth = short * 0.005f * weight
        canvas.drawPath(path, boltPaint)

        if (head != null) {
            fill.color = core
            fill.alpha = (a * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(head[0], head[1], short * 0.011f * a * weight, fill)
        }
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

    /** The five toys, the palette, and whatever toggle this mode has. */
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
                "bolt" -> toy.mode == Mode.BOLT
                "glass" -> toy.mode == Mode.GLASS
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
        // On a phone the strip is the only way to reach size and shape, and on
        // a phone the cells are half as wide as they are on a laptop: a chip
        // sized off the strip's height alone overlapped its neighbours and ran
        // off the left edge. It gets whichever is smaller.
        val chipCap = sh * 0.28f
        // Graphite chips on the ink canvas are a hole rather than a control.
        // Sheer is left alone: what is behind the window is anyone's guess.
        val chipInk = if (toy.sheer()) Color.rgb(58, 58, 60)
                      else contrastOn(toy.canvasColor(), 1f)
        for (z in toy.stripZones()) {
            val step = (z.x1 - z.x0) / z.count
            val chipR = minOf(chipCap, step * 0.42f)
            for (i in 0 until z.count) {
                val cx = z.x0 + step * (i + 0.5f)
                when (z.kind) {
                    "color" -> {
                        val locked = toy.familyLocked(i)
                        fill.color = Palette.COLORS[i][toy.inkTone]
                        fill.alpha = if (locked) 70 else 255
                        canvas.drawCircle(cx, cy, chipR, fill)
                        if (locked) drawLock(canvas, cx, cy, chipR * 0.62f, Color.argb(190, 58, 58, 60))
                        if (i == toy.inkFamily) {
                            rim.alpha = 200
                            canvas.drawCircle(cx, cy, chipR + 5f, rim)
                        }
                    }
                    "size" -> {
                        val rr = chipR * (0.3f + 0.85f * (Toy.SIZES[i] / Toy.SIZES.last()))
                        val pts = Outlines.points(toy.shape, cx, cy, rr, 0f)
                        outline(canvas, pts, cx, cy, rr, withAlpha(chipInk, 140), 1f,
                            i == toy.sizeIndex)
                    }
                    "shape" -> {
                        val s = Shape.entries[i]
                        val on = s == toy.shape
                        val pts = Outlines.points(s, cx, cy, chipR * 0.92f, 0f)
                        outline(canvas, pts, cx, cy, chipR * 0.92f,
                            withAlpha(chipInk, if (on) 255 else 90), 1f, on)
                    }
                }
            }
        }
    }

    /**
     * A bumper, whatever it is made of. A letter is several boxes, so they go
     * into one path and are filled once — filled separately they would show
     * seams where they meet, at the alpha a bumper is drawn at.
     */
    private fun drawBumper(canvas: Canvas, b: Bumper, c: FloatArray) {
        val parts = toy.bumperParts(b)
        if (parts.isEmpty()) {
            // A circle, or a circle pulled into an ellipse.
            val r = toy.bumperRadius(b)
            canvas.save()
            canvas.rotate(Math.toDegrees(b.rot.toDouble()).toFloat(), c[0], c[1])
            canvas.scale(b.sx, b.sy, c[0], c[1])
            outline(canvas, null, c[0], c[1], r, toy.bumperColor(b), Toy.BUMPER_ALPHA, true)
            canvas.restore()
            return
        }
        if (b.glyph.isEmpty()) {
            outline(canvas, parts[0], c[0], c[1], toy.bumperRadius(b),
                toy.bumperColor(b), Toy.BUMPER_ALPHA, true)
            return
        }
        path.rewind()
        path.fillType = android.graphics.Path.FillType.EVEN_ODD
        for (loop in toy.bumperLoops(b)) {
            path.moveTo(loop[0][0], loop[0][1])
            for (i in 1 until loop.size) path.lineTo(loop[i][0], loop[i][1])
            path.close()
        }
        fill.color = toy.bumperColor(b)
        fill.alpha = (Toy.BUMPER_ALPHA * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(path, fill)
        rim.alpha = 90
        canvas.drawPath(path, rim)
    }

    private fun drawEditUi(canvas: Canvas) {
        toy.table.getOrNull(toy.selected)?.let { b ->
            val c = toy.bumperCenter(b)
            val r = toy.bumperRadius(b)
            val parts = toy.bumperParts(b)
            if (parts.isEmpty()) {
                canvas.save()
                canvas.rotate(Math.toDegrees(b.rot.toDouble()).toFloat(), c[0], c[1])
                canvas.scale(b.sx, b.sy, c[0], c[1])
                canvas.drawCircle(c[0], c[1], r, dashed)
                canvas.restore()
            } else {
                path.rewind()
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                for (loop in toy.bumperLoops(b)) {
                    path.moveTo(loop[0][0], loop[0][1])
                    for (i in 1 until loop.size) path.lineTo(loop[i][0], loop[i][1])
                    path.close()
                }
                canvas.drawPath(path, dashed)
            }
            val hs = toy.handles(b)
            val hr = minOf(toy.w, toy.h) * 0.022f
            fill.color = Color.rgb(112, 41, 41); fill.alpha = 255
            canvas.drawCircle(hs[0][0], hs[0][1], hr, fill)
            // A perfectly round bumper has no orientation to show; a pulled
            // one does, so the turn handle appears the moment it stops being
            // a circle.
            if (b.shape != Shape.CIRCLE || b.glyph.isNotEmpty() || b.sx != b.sy) {
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
            val label = toy.toolbarLabels[btn.i]
            val live = btn.i == 0 || label == "reset" || label == "done" || toy.selected >= 0
            // "ink" says what colour it would change as well as that it can.
            if (label == "ink" && live) {
                val sw = minOf(btn.w, btn.h) * 0.34f
                val cy = btn.y + btn.h * 0.5f
                fill.color = toy.bumperColor(toy.table[toy.selected])
                fill.alpha = 255
                canvas.drawRect(
                    btn.x + btn.w * 0.5f - sw, cy - sw, btn.x + btn.w * 0.5f + sw, cy + sw, fill,
                )
                rim.alpha = 120
                canvas.drawRect(
                    btn.x + btn.w * 0.5f - sw, cy - sw, btn.x + btn.w * 0.5f + sw, cy + sw, rim,
                )
                continue
            }
            textPaint.color = if (live) Color.rgb(58, 58, 60) else Color.argb(90, 58, 58, 60)
            canvas.drawText(
                label,
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
        canvas.drawText(toy.drawerHeading(), b.gx, b.gy - textPaint.textSize * 0.5f, textPaint)
        val selFamily = toy.drawerFamily()
        val selTone = toy.drawerTone()

        for (f in Palette.COLORS.indices) {
            for (t in Palette.COLORS[f].indices) {
                val x = b.gx + f * b.cell
                val y = b.gy + t * b.cell
                val locked = toy.familyLocked(f)
                fill.color = Palette.COLORS[f][t]
                fill.alpha = if (locked) 60 else 255
                canvas.drawRect(x + 2f, y + 2f, x + b.cell - 2f, y + b.cell - 2f, fill)
                if (locked && t == 0) {
                    drawLock(canvas, x + b.cell / 2f, y + b.cell * 1.5f, b.cell * 0.26f,
                        Color.argb(170, 58, 58, 60))
                }
                rim.alpha = 46                  // or the palest tones dissolve
                canvas.drawRect(x + 2f, y + 2f, x + b.cell - 2f, y + b.cell - 2f, rim)
                if (f == selFamily && t == selTone) {
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
                val locked = toy.canvasLocked(i)
                fill.color = Palette.CANVAS_COLORS[i]
                fill.alpha = if (locked) 70 else 255
                canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 5f, 5f, fill)
                textPaint.color = if (locked) Color.argb(150, 58, 58, 60)
                else contrastOn(Palette.CANVAS_COLORS[i], 1f)
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
