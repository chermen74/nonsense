package com.nonsense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One view, four toys. Double-tap cycles:
 *
 *   BALL    — flick from anywhere; coasts, bounces off edges, haptic taps.
 *   DIAL    — solid disc; spin it, detents tick every 12 degrees.
 *   BUMPERS — the ball plus five fixed round bumpers that kick it back.
 *   PAINT   — the ball leaves a trail. Muted palette + three sizes along
 *             the bottom edge; two-finger tap clears the canvas.
 *
 * No scores, no sounds, no skins. The scrim is a 12% tint so the screen
 * underneath stays visible.
 */
class NonsenseView(context: Context) : View(context), Choreographer.FrameCallback {

    private enum class Mode { BALL, DIAL, BUMPERS, PAINT }

    private var mode = Mode.BALL

    // ---- shared -----------------------------------------------------------
    private val scrimPaint = Paint().apply { color = Color.argb(31, 0, 0, 0) }
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 58, 60)                     // matte graphite
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(90, 0, 0, 0)
    }

    private var lastFrameNanos = 0L
    private val choreographer = Choreographer.getInstance()

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // ---- ball state (shared by BALL, BUMPERS, PAINT) ----------------------
    private var bx = 0f
    private var by = 0f
    private var bvx = 0f
    private var bvy = 0f
    private var baseRadius = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null

    private val friction = 0.55f
    private val restitution = 0.82f
    private val bounceHapticMinV = 350f

    // ---- dial state -------------------------------------------------------
    private var dialAngle = 0f
    private var dialOmega = 0f
    private var dialRadius = 0f
    private var dialGrab = false
    private var lastMoveAngle = 0f
    private var lastMoveTime = 0L
    private val detentRad = Math.toRadians(12.0).toFloat()
    private var lastDetentIndex = 0
    private val dialFriction = 0.35f

    // ---- bumpers ----------------------------------------------------------
    // proportional (x, y, r); laid out like a loose pinball field
    private val bumperSpec = listOf(
        Triple(0.25f, 0.30f, 0.075f),
        Triple(0.75f, 0.30f, 0.075f),
        Triple(0.50f, 0.50f, 0.090f),
        Triple(0.25f, 0.72f, 0.075f),
        Triple(0.75f, 0.72f, 0.075f),
    )
    private data class Bumper(val x: Float, val y: Float, val r: Float)
    private var bumpers: List<Bumper> = emptyList()
    private val bumperKick = 1.06f          // small energy add per bumper hit
    private val maxSpeed = 6000f            // px/s cap so kicks can't run away

    // ---- paint mode -------------------------------------------------------
    private var trail: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // muted, adult palette — graphite, oxblood, slate, moss, ochre, bone
    private val palette = intArrayOf(
        Color.rgb(58, 58, 60),
        Color.rgb(112, 41, 41),
        Color.rgb(70, 90, 120),
        Color.rgb(92, 110, 74),
        Color.rgb(176, 137, 64),
        Color.rgb(226, 220, 205),
    )
    private var colorIndex = 0
    private val sizeMul = floatArrayOf(0.5f, 1.0f, 1.8f)
    private var sizeIndex = 1
    private var lastTrailX = 0f
    private var lastTrailY = 0f
    private var trailStarted = false
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stripH = 0f                  // palette strip height (paint mode)

    private fun ballRadius(): Float =
        if (mode == Mode.PAINT) baseRadius * sizeMul[sizeIndex] else baseRadius

    // ----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseRadius = minOf(w, h) * 0.075f
        dialRadius = minOf(w, h) * 0.30f
        stripH = h * 0.07f
        if (bx == 0f && by == 0f) { bx = w / 2f; by = h / 2f }
        bumpers = bumperSpec.map {
            Bumper(it.first * w, it.second * h, it.third * minOf(w, h))
        }
        if (trail == null || trail?.width != w || trail?.height != h) {
            trail = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trail!!)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    // ---- physics loop -----------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0)
                .toFloat().coerceIn(0f, 0.05f)
            when (mode) {
                Mode.DIAL -> stepDial(dt)
                else -> stepBall(dt)
            }
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        choreographer.postFrameCallback(this)
    }

    private fun stepBall(dt: Float) {
        if (!dragging) {
            val decay = Math.pow(friction.toDouble(), dt.toDouble()).toFloat()
            bvx *= decay
            bvy *= decay
            if (hypot(bvx, bvy) < 4f) { bvx = 0f; bvy = 0f }
            bx += bvx * dt
            by += bvy * dt
        }

        val r = ballRadius()
        val minX = r; val maxX = width - r
        val minY = r; val maxY = height - r
        var bounced = false
        var impact = 0f

        if (bx < minX) { bx = minX; impact = abs(bvx); bvx = -bvx * restitution; bounced = true }
        if (bx > maxX) { bx = maxX; impact = abs(bvx); bvx = -bvx * restitution; bounced = true }
        if (by < minY) { by = minY; impact = maxOf(impact, abs(bvy)); bvy = -bvy * restitution; bounced = true }
        if (by > maxY) { by = maxY; impact = maxOf(impact, abs(bvy)); bvy = -bvy * restitution; bounced = true }

        if (mode == Mode.BUMPERS && !dragging) collideBumpers()

        if (bounced && impact > bounceHapticMinV) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        if (mode == Mode.PAINT) layTrail()
    }

    private fun collideBumpers() {
        val r = ballRadius()
        for (b in bumpers) {
            val dx = bx - b.x
            val dy = by - b.y
            val dist = hypot(dx, dy)
            val minDist = r + b.r
            if (dist < minDist && dist > 0.001f) {
                // push out along the normal, reflect velocity, small kick
                val nx = dx / dist
                val ny = dy / dist
                bx = b.x + nx * minDist
                by = b.y + ny * minDist
                val dot = bvx * nx + bvy * ny
                if (dot < 0f) {
                    bvx = (bvx - 2f * dot * nx) * bumperKick
                    bvy = (bvy - 2f * dot * ny) * bumperKick
                    val speed = hypot(bvx, bvy)
                    if (speed > maxSpeed) {
                        bvx *= maxSpeed / speed
                        bvy *= maxSpeed / speed
                    }
                    performHapticFeedbackSafe()
                }
            }
        }
    }

    private fun performHapticFeedbackSafe() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun layTrail() {
        if (!trailStarted) {
            lastTrailX = bx; lastTrailY = by
            trailStarted = true
            return
        }
        if (bx != lastTrailX || by != lastTrailY) {
            trailPaint.color = palette[colorIndex]
            trailPaint.strokeWidth = ballRadius() * 2f
            trailCanvas?.drawLine(lastTrailX, lastTrailY, bx, by, trailPaint)
            lastTrailX = bx; lastTrailY = by
        }
    }

    private fun stepDial(dt: Float) {
        if (dialGrab) return
        val decay = Math.pow(dialFriction.toDouble(), dt.toDouble()).toFloat()
        dialOmega *= decay
        if (abs(dialOmega) < 0.05f) dialOmega = 0f
        dialAngle += dialOmega * dt
        tickDetents()
    }

    private fun tickDetents() {
        val idx = floor(dialAngle / detentRad).toInt()
        if (idx != lastDetentIndex) {
            lastDetentIndex = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    // ---- input ------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // two-finger tap in paint mode wipes the canvas
        if (mode == Mode.PAINT &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.pointerCount == 2
        ) {
            trail?.eraseColor(Color.TRANSPARENT)
            trailStarted = false
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            dragging = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // palette strip taps (paint mode only)
                if (mode == Mode.PAINT && event.y > height - stripH) {
                    handleStripTap(event.x)
                    return true
                }
                if (isDoubleTap(event)) { cycleMode(); return true }
                when (mode) {
                    Mode.DIAL -> {
                        dialGrab = true
                        dialOmega = 0f
                        lastMoveAngle = angleTo(event.x, event.y)
                        lastMoveTime = event.eventTime
                    }
                    else -> {
                        dragging = true
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        bx = event.x; by = event.y
                        bvx = 0f; bvy = 0f
                        if (mode == Mode.PAINT) {
                            lastTrailX = bx; lastTrailY = by; trailStarted = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DIAL -> {
                    if (dialGrab) {
                        val a = angleTo(event.x, event.y)
                        var delta = a - lastMoveAngle
                        while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                        while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                        dialAngle += delta
                        val dtms = (event.eventTime - lastMoveTime).coerceAtLeast(1)
                        dialOmega = delta / (dtms / 1000f)
                        lastMoveAngle = a
                        lastMoveTime = event.eventTime
                        tickDetents()
                    }
                }
                else -> {
                    if (dragging) {
                        velocityTracker?.addMovement(event)
                        bx = event.x; by = event.y
                        if (mode == Mode.PAINT) layTrail()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (mode) {
                Mode.DIAL -> dialGrab = false
                else -> {
                    if (dragging) {
                        dragging = false
                        velocityTracker?.let {
                            it.addMovement(event)
                            it.computeCurrentVelocity(1000)
                            bvx = it.xVelocity
                            bvy = it.yVelocity
                            it.recycle()
                        }
                        velocityTracker = null
                    }
                }
            }
        }
        return true
    }

    private fun handleStripTap(x: Float) {
        // left 2/3 of the strip: six color chips; right 1/3: three size dots
        val colorZone = width * 0.66f
        if (x < colorZone) {
            colorIndex = ((x / colorZone) * palette.size).toInt()
                .coerceIn(0, palette.size - 1)
        } else {
            sizeIndex = (((x - colorZone) / (width - colorZone)) * sizeMul.size)
                .toInt().coerceIn(0, sizeMul.size - 1)
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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

    private fun cycleMode() {
        mode = when (mode) {
            Mode.BALL -> Mode.DIAL
            Mode.DIAL -> Mode.BUMPERS
            Mode.BUMPERS -> Mode.PAINT
            Mode.PAINT -> Mode.BALL
        }
        trailStarted = false
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun angleTo(x: Float, y: Float): Float =
        atan2(y - height / 2f, x - width / 2f)

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(scrimPaint)

        when (mode) {
            Mode.DIAL -> {
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawCircle(cx, cy, dialRadius, solidPaint)
                canvas.drawCircle(cx, cy, dialRadius, rimPaint)
                val mx = cx + cos(dialAngle) * dialRadius * 0.8f
                val my = cy + sin(dialAngle) * dialRadius * 0.8f
                canvas.drawCircle(mx, my, dialRadius * 0.06f, rimPaint)
            }
            else -> {
                if (mode == Mode.BUMPERS) {
                    for (b in bumpers) {
                        canvas.drawCircle(b.x, b.y, b.r, rimPaint)
                    }
                }
                if (mode == Mode.PAINT) {
                    trail?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                    drawStrip(canvas)
                }
                val r = ballRadius()
                if (mode == Mode.PAINT) solidPaint.color = palette[colorIndex]
                else solidPaint.color = Color.rgb(58, 58, 60)
                canvas.drawCircle(bx, by, r, solidPaint)
                canvas.drawCircle(bx, by, r, rimPaint)
            }
        }
    }

    private fun drawStrip(canvas: Canvas) {
        val top = height - stripH
        val cy = top + stripH / 2f
        val colorZone = width * 0.66f
        val chipR = stripH * 0.28f
        for (i in palette.indices) {
            val cx = colorZone * (i + 0.5f) / palette.size
            chipPaint.color = palette[i]
            canvas.drawCircle(cx, cy, chipR, chipPaint)
            if (i == colorIndex) canvas.drawCircle(cx, cy, chipR + 4f, rimPaint)
        }
        for (i in sizeMul.indices) {
            val cx = colorZone + (width - colorZone) * (i + 0.5f) / sizeMul.size
            chipPaint.color = Color.argb(140, 58, 58, 60)
            val rr = chipR * (0.45f + 0.35f * i)
            canvas.drawCircle(cx, cy, rr, chipPaint)
            if (i == sizeIndex) canvas.drawCircle(cx, cy, rr + 4f, rimPaint)
        }
    }
}
