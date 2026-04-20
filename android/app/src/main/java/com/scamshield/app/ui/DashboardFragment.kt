package com.scamshield.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.scamshield.app.R
import com.scamshield.app.databinding.FragmentDashboardBinding
import com.scamshield.app.service.ScamShieldNotificationService
import com.scamshield.app.util.formatCategoryLabel
import dagger.hilt.android.AndroidEntryPoint

/**
 * DashboardFragment — Main dashboard with gradient hero protection status,
 * icon-badged stats cards, quick test analyzer with Explainable AI results,
 * and adaptive learning feedback buttons.
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var pulseAnimator: ObjectAnimator? = null
    private var lastAnalyzedText: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
        // Trigger staggered layout animation
        binding.dashboardRoot.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_stagger)
        binding.dashboardRoot.scheduleLayoutAnimation()
    }

    override fun onResume() {
        super.onResume()
        updateProtectionStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        _binding = null
    }

    private fun setupUI() {
        binding.btnToggleProtection.setOnClickListener {
            if (isNotificationListenerEnabled()) {
                Toast.makeText(requireContext(), "Protection is already active", Toast.LENGTH_SHORT).show()
            } else {
                openNotificationAccessSettings()
            }
        }

        // Hidden Sandbox Simulator: Long press to inject mock notification
        binding.btnToggleProtection.setOnLongClickListener {
            fireSandboxNotification()
            true
        }

        binding.btnAnalyze.setOnClickListener {
            val text = binding.etTestMessage.text?.toString()?.trim()
            if (text.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Please enter a message to analyze", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastAnalyzedText = text
            val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)
            val demoMode = prefs.getBoolean("demo_mode", false)
            viewModel.analyzeMessage(text, demoMode)
        }
        binding.btnExplainToggle.setOnClickListener {
            val visible = binding.layoutExplainPanel.visibility == View.VISIBLE
            binding.layoutExplainPanel.visibility = if (visible) View.GONE else View.VISIBLE
        }

        // Adaptive Learning Buttons
        binding.btnMarkSafe.setOnClickListener {
            lastAnalyzedText?.let { text ->
                viewModel.markAsSafe(text.hashCode().toString())
                hapticFeedback()
                Toast.makeText(requireContext(), getString(R.string.marked_safe_toast), Toast.LENGTH_SHORT).show()
                binding.btnMarkSafe.isEnabled = false
                binding.btnMarkScam.isEnabled = false
                binding.btnMarkSafe.text = "Marked Safe"
                binding.btnMarkSafe.alpha = 0.7f
            }
        }

        binding.btnMarkScam.setOnClickListener {
            lastAnalyzedText?.let { text ->
                viewModel.markAsScam(text.hashCode().toString())
                hapticFeedback()
                Toast.makeText(requireContext(), getString(R.string.marked_scam_toast), Toast.LENGTH_SHORT).show()
                binding.btnMarkSafe.isEnabled = false
                binding.btnMarkScam.isEnabled = false
                binding.btnMarkScam.text = "Marked Scam"
                binding.btnMarkScam.alpha = 0.7f
            }
        }

        binding.btnOpenMessageView.setOnClickListener {
            startActivity(Intent(requireContext(), MessageInsightsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.analysisResult.observe(viewLifecycleOwner) { result ->
            if (result == null) return@observe

            binding.cardResult.visibility = View.VISIBLE
            // Scale + fade animation on result card
            binding.cardResult.scaleX = 0.85f
            binding.cardResult.scaleY = 0.85f
            binding.cardResult.alpha = 0f
            binding.cardResult.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()

            val confPercent = (result.bestConfidence * 100).toInt()
            val isScam = result.shouldAlert

            // Verdict title
            val riskLabel = when {
                confPercent >= 75 -> "HIGH"
                confPercent >= 45 -> "MEDIUM"
                confPercent >= 20 -> "LOW"
                else -> "SAFE"
            }
            binding.tvResultTitle.text = if (isScam)
                "⚠️ $riskLabel RISK SCAM ($confPercent%)" else "✅ SAFE MESSAGE ($confPercent%)"
            binding.tvResultTitle.setTextColor(
                ContextCompat.getColor(requireContext(), if (isScam) R.color.risk_high else R.color.risk_safe)
            )
            val explanation = result.serverVerdict?.explanation?.trim().orEmpty()
            binding.tvResultSummary.text = when {
                explanation.isNotEmpty() -> explanation
                result.ruleVerdict.matchedRules.isNotEmpty() ->
                    "On-device rules: " + result.ruleVerdict.matchedRules.joinToString(", ") { it.rule }
                else -> result.statusMessage
            }

            // Animated confidence badge
            binding.tvResultConfBadge.text = "$confPercent%"
            binding.tvResultConfBadge.setTextColor(
                ContextCompat.getColor(requireContext(),
                    when {
                        confPercent >= 70 -> R.color.risk_high
                        confPercent >= 40 -> R.color.risk_medium
                        else -> R.color.risk_safe
                    }
                )
            )
            // Animated count-up on confidence
            animateCounter(binding.tvResultConfBadge, 0, confPercent, "%d%%")

            // Category
            binding.tvResultCategory.text = "Category: ${result.bestCategory.formatCategoryLabel()}"
            val linkRisk = result.serverVerdict?.link_risk_score ?: 0f
            if (linkRisk > 0.4f) {
                binding.tvResultCategory.append("  •  Link risk ${(linkRisk * 100).toInt()}%")
            }

            // Rules matched
            binding.tvResultRules.text = "Rules matched: ${result.ruleVerdict.matchedRules.size} | ${result.statusMessage}"

            // Explainable AI: Generate reason chips
            if (isScam) {
                binding.btnExplainToggle.visibility = View.VISIBLE
                binding.chipGroupReasons.visibility = View.VISIBLE
                binding.chipGroupReasons.removeAllViews()
                val explain = result.serverVerdict?.explainability
                binding.tvExplainScores.text = "Rule ${(explain?.rule_score ?: result.ruleVerdict.confidence) * 100f}%  •  " +
                    "LLM ${(explain?.llm_score ?: 0f) * 100f}%  •  " +
                    "Sender ${(explain?.sender_reputation ?: 0.5f) * 100f}%  •  " +
                    "Final ${(explain?.final_score ?: result.bestConfidence) * 100f}%"

                val reasons = generateExplainableReasons(result)
                reasons.forEachIndexed { index, reason ->
                    val chip = Chip(requireContext()).apply {
                        text = reason
                        isCheckable = false
                        isClickable = false
                        setChipBackgroundColorResource(R.color.chip_bg)
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        textSize = 12f
                        chipStrokeWidth = 1f
                        setChipStrokeColorResource(R.color.primary_30)
                    }
                    // Staggered chip entrance
                    chip.alpha = 0f
                    chip.translationY = 16f
                    binding.chipGroupReasons.addView(chip)
                    chip.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay((index * 80).toLong())
                        .setDuration(250)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }

                // Explainable AI: Highlight exact tokens in the input text
                result.serverVerdict?.red_flags?.takeIf { it.isNotEmpty() }?.let { flags ->
                    lastAnalyzedText?.let { original ->
                        val spannable = android.text.SpannableStringBuilder(original)
                        val highRiskColor = ContextCompat.getColor(requireContext(), R.color.risk_high)
                        flags.forEach { flag ->
                            var startIndex = original.indexOf(flag, ignoreCase = true)
                            while (startIndex >= 0) {
                                spannable.setSpan(
                                    android.text.style.ForegroundColorSpan(highRiskColor),
                                    startIndex,
                                    startIndex + flag.length,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                spannable.setSpan(
                                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    startIndex,
                                    startIndex + flag.length,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                startIndex = original.indexOf(flag, startIndex + flag.length, ignoreCase = true)
                            }
                        }
                        binding.etTestMessage.setText(spannable)
                        binding.etTestMessage.clearFocus()
                    }
                }
            } else {
                binding.btnExplainToggle.visibility = View.GONE
                binding.layoutExplainPanel.visibility = View.GONE
                binding.chipGroupReasons.visibility = View.GONE
            }

            // Reset feedback buttons
            binding.btnMarkSafe.isEnabled = true
            binding.btnMarkScam.isEnabled = true
            binding.btnMarkSafe.alpha = 1f
            binding.btnMarkScam.alpha = 1f
            binding.btnMarkSafe.text = getString(R.string.mark_safe)
            binding.btnMarkScam.text = getString(R.string.mark_scam)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.btnAnalyze.isEnabled = !loading
            binding.btnAnalyze.text = if (loading) "Analyzing..." else "Analyze Message"
        }
    }

    /**
     * Animate a counter from startVal to endVal on a TextView.
     */
    private fun animateCounter(view: android.widget.TextView, startVal: Int, endVal: Int, format: String) {
        val animator = ValueAnimator.ofInt(startVal, endVal)
        animator.duration = 600
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            view.text = String.format(format, value)
        }
        animator.start()
    }

    /**
     * Generate human-readable explanations for why a message was flagged.
     * This is the core of the Explainable AI feature.
     */
    private fun generateExplainableReasons(result: com.scamshield.app.detection.DetectionResult): List<String> {
        val reasons = mutableListOf<String>()
        result.serverVerdict?.explainability?.reasons?.let { reasons.addAll(it) }
        val rules = result.ruleVerdict.matchedRules

        // Check matched rule categories and generate explanations
        rules.forEach { rule ->
            val lower = rule.rule.lowercase()
            when {
                lower.contains("otp") || lower.contains("verif") ->
                    reasons.add("OTP / verification request")
                lower.contains("urgency") || lower.contains("urgent") || lower.contains("act now") ->
                    reasons.add("Urgency phrases detected")
                lower.contains("link") || lower.contains("url") || lower.contains("http") ->
                    reasons.add("Suspicious link detected")
                lower.contains("money") || lower.contains("bank") || lower.contains("payment") || lower.contains("financial") ->
                    reasons.add("Financial request detected")
                lower.contains("lottery") || lower.contains("prize") || lower.contains("winner") ->
                    reasons.add("Lottery / prize scam pattern")
                lower.contains("password") || lower.contains("credentials") ->
                    reasons.add("Credential theft attempt")
                else ->
                    reasons.add(rule.rule.formatCategoryLabel())
            }
        }

        // Add confidence-based reason
        val conf = (result.bestConfidence * 100).toInt()
        if (conf >= 80) reasons.add("High AI confidence ($conf%)")
        else if (conf >= 50) reasons.add("Moderate AI confidence ($conf%)")

        // Add category-based reason
        val cat = result.bestCategory.lowercase()
        when {
            cat.contains("phish") -> reasons.add("Phishing pattern recognized")
            cat.contains("romance") -> reasons.add("Romance scam indicators")
            cat.contains("tech") -> reasons.add("Tech support scam pattern")
            cat.contains("invest") -> reasons.add("Investment fraud signals")
        }

        return reasons.distinct().take(6) // Max 6 reasons for clean UI
    }

    private fun updateProtectionStatus() {
        val ctx = context ?: return
        val enabled = isNotificationListenerEnabled()

        binding.tvStatus.text = if (enabled)
            getString(R.string.protection_active) else getString(R.string.protection_inactive)

        val dotColor = if (enabled) R.color.status_active else R.color.status_inactive
        binding.statusDot.background.setTint(ContextCompat.getColor(ctx, dotColor))

        binding.tvStatusDetail.text = if (enabled)
            "Monitoring WhatsApp, Telegram, SMS, Email, Instagram"
        else
            "Tap below to grant notification access"

        binding.btnToggleProtection.text = if (enabled)
            getString(R.string.protection_active_btn) else getString(R.string.grant_access)

        // Show LIVE badge when active
        binding.tvLiveBadge.visibility = if (enabled) View.VISIBLE else View.GONE

        // Pulse animation on active dot
        pulseAnimator?.cancel()
        if (enabled) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f, 1f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.6f, 1f)
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.statusDot, scaleX, scaleY, alpha).apply {
                duration = 1500
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val ctx = context ?: return false
        val cn = ComponentName(ctx, ScamShieldNotificationService::class.java)
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun fireSandboxNotification() {
        val ctx = requireContext()
        hapticFeedback()
        
        // Android 13+ requires POST_NOTIFICATIONS runtime permission
        if (android.os.Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requireActivity().requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                Toast.makeText(ctx, "Please grant Notification Permission first and try again.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val notificationManager = ctx.getSystemService(android.app.NotificationManager::class.java)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "scam_sandbox",
                "Sandbox Demo",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = androidx.core.app.NotificationCompat.Builder(ctx, "scam_sandbox")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("SBI Bank Alert")
            .setContentText("URGENT: Your account was suspended for unusual activity. Click here for a KYC update and to verify your PAN card: http://sbi-kyc-verify.com")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
            
        notificationManager.notify(999, notification)
        Toast.makeText(ctx, "Sandbox: Injected Live Mock Scenario", Toast.LENGTH_SHORT).show()
    }

    private fun hapticFeedback() {
        try {
            val vibrator = ContextCompat.getSystemService(requireContext(), Vibrator::class.java)
            vibrator?.vibrate(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) { }
    }
}
