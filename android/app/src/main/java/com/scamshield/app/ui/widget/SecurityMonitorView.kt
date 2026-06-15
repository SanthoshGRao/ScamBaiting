package com.scamshield.app.ui.widget

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.scamshield.app.R
import kotlin.math.min

/**
 * Canvas-rendered security monitor with a quiet protection heartbeat.
 */
class SecurityMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class State { INACTIVE, PROTECTED, SCANNING, THREAT }

    private val shieldIcon: ImageView

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var currentState = State.INACTIVE
    private var frameAnimator: ValueAnimator? = null
    private var shieldBreathingAnimator: ObjectAnimator? = null
    private var startTimeMs = 0L
    private var baseGlowAlpha = 0.05f

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    init {
        LayoutInflater.from(context).inflate(R.layout.view_security_monitor, this, true)
        shieldIcon = findViewById(R.id.ivShieldCenter)
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setState(State.INACTIVE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val fallback = resources.getDimensionPixelSize(R.dimen.security_ring_size)
        val size = when {
            width > 0 && height > 0 -> min(width, height)
            width > 0 -> width
            height > 0 -> height
            else -> fallback
        }
        val squareSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(squareSpec, squareSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val elapsedMs = if (startTimeMs == 0L) 0L else System.currentTimeMillis() - startTimeMs
        val ringRadius = size * 0.38f
        val innerRadius = size * 0.17f
        val active = currentState != State.INACTIVE
        val pulseProgress = getPulseProgress(elapsedMs)

        drawRadialGlow(canvas, cx, cy, size, active, pulseProgress)
        drawInnerGlow(canvas, cx, cy, innerRadius)
        drawProtectionRing(canvas, cx, cy, ringRadius, active, pulseProgress)
        if (active && pulseProgress != null) drawHeartbeatPulse(canvas, cx, cy, size, pulseProgress)
        drawCenterPlate(canvas, cx, cy, size * 0.18f)
    }

    private fun drawRadialGlow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        active: Boolean,
        pulseProgress: Float?,
    ) {
        val pulseBoost = if (active && pulseProgress != null) {
            baseGlowAlpha * 0.1f * kotlin.math.sin(Math.PI * pulseProgress).toFloat().coerceAtLeast(0f)
        } else {
            0f
        }
        val alpha = if (active) baseGlowAlpha + pulseBoost else 0.025f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            size * 0.22f,
            intArrayOf(withAlpha(Color.parseColor("#FF1E90FF"), (alpha * 255).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.95f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, size * 0.22f, glowPaint)
        glowPaint.shader = null
    }

    private fun drawInnerGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.parseColor("#101E90FF"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null
    }

    private fun drawProtectionRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        active: Boolean,
        pulseProgress: Float?,
    ) {
        val heartbeat = if (active && pulseProgress != null) {
            kotlin.math.sin(Math.PI * pulseProgress).toFloat().coerceAtLeast(0f)
        } else {
            0f
        }
        ringPaint.strokeWidth = dp(3f)
        ringPaint.maskFilter = null
        ringPaint.color = withAlpha(Color.parseColor("#FF1E90FF"), (46 + 20 * heartbeat).toInt())
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    private fun drawHeartbeatPulse(canvas: Canvas, cx: Float, cy: Float, size: Float, progress: Float) {
        val eased = 1f - (1f - progress) * (1f - progress)
        val radius = size * (0.2f + 0.2f * eased)
        val alpha = (31 * (1f - eased)).toInt()
        pulsePaint.strokeWidth = dp(2f)
        pulsePaint.color = withAlpha(Color.parseColor("#FF1E90FF"), alpha)
        pulsePaint.maskFilter = null
        canvas.drawCircle(cx, cy, radius, pulsePaint)
    }

    private fun drawCenterPlate(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.parseColor("#101E90FF"), Color.parseColor("#0307152F")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null
    }

    fun setState(state: State) {
        if (state == currentState && state != State.THREAT) return
        val wasThreat = currentState == State.THREAT
        currentState = state
        stopAnimations()

        when (state) {
            State.INACTIVE -> applyInactive()
            State.PROTECTED -> applyActive(R.color.primary)
            State.SCANNING -> applyActive(R.color.accent)
            State.THREAT -> {
                applyActive(R.color.risk_high)
                if (!wasThreat) playThreatShake()
            }
        }
        invalidate()
    }

    private fun applyInactive() {
        baseGlowAlpha = 0.025f
        shieldIcon.scaleX = 1f
        shieldIcon.scaleY = 1f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.ring_inactive))
    }

    private fun applyActive(iconColor: Int) {
        shieldIcon.setColorFilter(ContextCompat.getColor(context, iconColor))
        baseGlowAlpha = 0.05f
        startTimeMs = System.currentTimeMillis()
        startFrameLoop()
        startShieldBreathing()
    }

    private fun startFrameLoop() {
        frameAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun startShieldBreathing() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.01f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.01f, 1f)
        shieldBreathingAnimator = ObjectAnimator.ofPropertyValuesHolder(shieldIcon, scaleX, scaleY).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun playThreatShake() {
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -12f, 12f, -6f, 6f, 0f).apply {
            duration = 400L
            start()
        }
    }

    private fun stopAnimations() {
        frameAnimator?.cancel()
        shieldBreathingAnimator?.cancel()
        frameAnimator = null
        shieldBreathingAnimator = null
        ringPaint.maskFilter = null
        pulsePaint.maskFilter = null
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun getPulseProgress(elapsedMs: Long): Float? {
        val cycleMs = elapsedMs % 3000L
        return if (cycleMs <= 800L) cycleMs / 800f else null
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }
}
