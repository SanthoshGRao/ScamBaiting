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

    init {
        LayoutInflater.from(context).inflate(R.layout.view_security_monitor, this, true)
        ringOuterContainer = findViewById(R.id.ringOuterContainer)
        ringMiddleContainer = findViewById(R.id.ringMiddleContainer)
        ringInner = findViewById(R.id.ringInner)
        ringGlow = findViewById(R.id.ringGlow)
        shieldIcon = findViewById(R.id.ivShieldCenter)
        clipChildren = false
        clipToPadding = false
        setState(State.INACTIVE)
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
        ringGlow.alpha = 0.2f
        ringInner.alpha = 0.4f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.ring_inactive))
        ringOuterContainer.rotation = 0f
        ringMiddleContainer.rotation = 0f
    }

    private fun applyProtected() {
        ringGlow.alpha = 0.3f
        ringInner.alpha = 0.8f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
        startRotation(ringOuterContainer, 14_000L, clockwise = true)
        startRotation(ringMiddleContainer, 10_000L, clockwise = false)
        startPulse()
    }

    private fun applyScanning() {
        ringGlow.alpha = 0.4f
        ringInner.alpha = 1f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent))
        startRotation(ringOuterContainer, 3_000L, clockwise = true)
        startRotation(ringMiddleContainer, 2_000L, clockwise = false)
        val sweep = ObjectAnimator.ofFloat(ringGlow, View.ROTATION, 0f, 360f).apply {
            duration = 2_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        sweep.start()
        // Removed `rotateOuter = sweep` since it overwrites the container rotation.
    }

    private fun applyThreat() {
        ringGlow.alpha = 0.5f
        ringInner.alpha = 1f
        shieldIcon.setColorFilter(ContextCompat.getColor(context, R.color.risk_high))
        startRotation(ringOuterContainer, 6_000L, clockwise = true)
        startPulse(fast = true)
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
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, if (fast) 1.15f else 1.10f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, if (fast) 1.15f else 1.10f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.1f, 0.4f, 0.1f)
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ringGlow, scaleX, scaleY, alpha).apply {
            this.duration = if (fast) 800L else 2_500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun playThreatShake() {
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -8f, 8f, -4f, 4f, 0f).apply {
            duration = 350
            start()
        }
    }

    private fun stopAnimations() {
        rotateOuter?.cancel()
        rotateMiddle?.cancel()
        pulseAnimator?.cancel()
        rotateOuter = null
        rotateMiddle = null
        pulseAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }
}
