package com.scamshield.app.ui.widget

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.scamshield.app.R

/**
 * Animated circular security monitor — UI only, no business logic.
 */
class SecurityMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class State { INACTIVE, PROTECTED, SCANNING, THREAT }

    private val ringOuterContainer: View
    private val ringMiddleContainer: View
    private val ringInner: View
    private val ringGlow: View
    private val shieldIcon: ImageView

    private var rotateOuter: ObjectAnimator? = null
    private var rotateMiddle: ObjectAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var currentState = State.INACTIVE

    private var shieldBreathingAnimator: ObjectAnimator? = null
    private var particleAnimator: ValueAnimator? = null
    private var lastTime = 0L

    private val particlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }

    private data class Particle(
        var angle: Float,
        val radiusOffset: Float, // Added to base radius
        val color: Int,
        val speed: Float, // degrees per sec
        val size: Float,
        val trailLength: Float
    )

    private val particles = mutableListOf<Particle>()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_security_monitor, this, true)
        ringOuterContainer = findViewById(R.id.ringOuterContainer)
        ringMiddleContainer = findViewById(R.id.ringMiddleContainer)
        ringInner = findViewById(R.id.ringInner)
        ringGlow = findViewById(R.id.ringGlow)
        shieldIcon = findViewById(R.id.ivShieldCenter)
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        initParticles()
        setState(State.INACTIVE)
    }

    private fun initParticles() {
        val blue = ContextCompat.getColor(context, R.color.primary_light)
        val cyan = ContextCompat.getColor(context, R.color.accent_light)
        particles.addAll(listOf(
            Particle(0f, 40f, blue, 60f, 10f, 60f),
            Particle(120f, -20f, cyan, -45f, 8f, 40f),
            Particle(240f, 50f, blue, 50f, 12f, 50f)
        ))
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (currentState == State.INACTIVE) return

        val now = System.currentTimeMillis()
        if (lastTime == 0L) lastTime = now
        val dt = (now - lastTime) / 1000f
        lastTime = now

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = width / 3f

        for (p in particles) {
            p.angle = (p.angle + p.speed * dt) % 360f
            val r = baseRadius + p.radiusOffset

            // Draw trail
            val steps = 20
            for (i in 0 until steps) {
                val trailAngle = p.angle - (p.speed / kotlin.math.abs(p.speed)) * (p.trailLength * i / steps)
                val rad = Math.toRadians(trailAngle.toDouble())
                val tx = cx + r * kotlin.math.cos(rad).toFloat()
                val ty = cy + r * kotlin.math.sin(rad).toFloat()
                
                val alpha = (255 * (1f - i / steps.toFloat())).toInt()
                particlePaint.color = p.color
                particlePaint.alpha = alpha
                val size = p.size * (1f - i / steps.toFloat())
                canvas.drawCircle(tx, ty, size, particlePaint)
            }
        }
    }

    fun setState(state: State) {
        if (state == currentState && state != State.THREAT) return
        val wasThreat = currentState == State.THREAT
        currentState = state
        stopAnimations()

        when (state) {
            State.INACTIVE -> applyInactive()
            State.PROTECTED -> applyProtected()
            State.SCANNING -> applyScanning()
            State.THREAT -> {
                applyThreat()
                if (!wasThreat) playThreatShake()
            }
        }
    }

    private fun applyInactive() {
        ringGlow.alpha = 0.1f
        ringInner.alpha = 0.2f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.ring_inactive))
        ringOuterContainer.rotation = 0f
        ringMiddleContainer.rotation = 0f
    }

    private fun applyProtected() {
        // Futuristic intense glows
        ringGlow.alpha = 0.6f
        ringInner.alpha = 0.9f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
        startRotation(ringOuterContainer, 14_000L, clockwise = true)
        startRotation(ringMiddleContainer, 10_000L, clockwise = false)
        startPulse()
        startShieldBreathing()
        startParticleEngine()
    }

    private fun applyScanning() {
        ringGlow.alpha = 0.8f
        ringInner.alpha = 1f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent))
        startRotation(ringOuterContainer, 3_000L, clockwise = true)
        startRotation(ringMiddleContainer, 2_000L, clockwise = false)
        startParticleEngine()
        val sweep = ObjectAnimator.ofFloat(ringGlow, View.ROTATION, 0f, 360f).apply {
            duration = 2_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        sweep.start()
    }

    private fun applyThreat() {
        ringGlow.alpha = 0.9f
        ringInner.alpha = 1f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.risk_high))
        startRotation(ringOuterContainer, 6_000L, clockwise = true)
        startPulse(fast = true)
        startShieldBreathing()
        startParticleEngine()
    }

    private fun startParticleEngine() {
        lastTime = System.currentTimeMillis()
        particleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun startShieldBreathing() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.98f, 1.02f, 0.98f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.98f, 1.02f, 0.98f)
        shieldBreathingAnimator = ObjectAnimator.ofPropertyValuesHolder(shieldIcon, scaleX, scaleY).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startRotation(view: View, durationMs: Long, clockwise: Boolean) {
        val animator = ObjectAnimator.ofFloat(
            view,
            View.ROTATION,
            0f,
            if (clockwise) 360f else -360f,
        ).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        animator.start()
        if (view === ringOuterContainer) rotateOuter = animator else rotateMiddle = animator
    }

    private fun startPulse(fast: Boolean = false) {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, if (fast) 1.25f else 1.15f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, if (fast) 1.25f else 1.15f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f, 0.8f, 0.3f)
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ringGlow, scaleX, scaleY, alpha).apply {
            this.duration = if (fast) 800L else 2_500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun playThreatShake() {
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -12f, 12f, -6f, 6f, 0f).apply {
            duration = 400
            start()
        }
    }

    private fun stopAnimations() {
        rotateOuter?.cancel()
        rotateMiddle?.cancel()
        pulseAnimator?.cancel()
        shieldBreathingAnimator?.cancel()
        particleAnimator?.cancel()
        rotateOuter = null
        rotateMiddle = null
        pulseAnimator = null
        shieldBreathingAnimator = null
        particleAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }
}
