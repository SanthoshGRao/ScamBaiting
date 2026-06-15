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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.scamshield.app.R
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.databinding.FragmentDashboardBinding
import com.scamshield.app.service.ScamShieldNotificationService
import com.scamshield.app.ui.widget.SecurityMonitorView
import com.scamshield.app.util.formatCategoryLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var notificationPulseAnimator: ObjectAnimator? = null
    private var lastAnalyzedText: String? = null
    private var lastScannedCount = 0
    private var lastThreatCount = 0
    private var lastBaitedCount = 0
    private var lastProtectionEnabled: Boolean? = null
    private val sandboxMessage = "URGENT: Your account was suspended for unusual activity. Click here for a KYC update and to verify your PAN card: http://sbi-kyc-verify.com"

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
        viewModel.loadAnalytics()
        runEntranceAnimations()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAnalytics()
        updateProtectionStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        notificationPulseAnimator?.cancel()
        _binding = null
    }

    private fun setupUI() {
        binding.btnToggleProtection.setOnClickListener {
            val notifEnabled = isNotificationListenerEnabled()
            if (notifEnabled) {
                Toast.makeText(requireContext(), "Protection is fully active", Toast.LENGTH_SHORT).show()
            } else {
                openNotificationAccessSettings()
            }
        }
        binding.layoutNotificationAccessIndicator.setOnClickListener { openNotificationAccessSettings() }

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
            val isVisible = binding.layoutExplainPanel.visibility == View.VISIBLE
            binding.layoutExplainPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnExplainToggle.setIconResource(if (isVisible) R.drawable.ic_info else R.drawable.ic_close)
        }

        binding.tvViewDetails.setOnClickListener { openMessageDetails() }
        binding.tvViewAllActivity.setOnClickListener {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = R.id.nav_scammers
        }


        startNotificationBellPulse()

        // Add glow focus to the text input
        binding.etTestMessage.setOnFocusChangeListener { _, hasFocus ->
            binding.etTestMessage.animate()
                .scaleX(if (hasFocus) 1.01f else 1f)
                .scaleY(if (hasFocus) 1.01f else 1f)
                .setDuration(200)
                .start()
        }
    }

    private fun runEntranceAnimations() {
        val targets = listOf(
            binding.cardHero,
            binding.cardStatScanned,
            binding.cardStatThreats,
            binding.cardStatBaited,
            binding.cardQuickTest,
            binding.cardRecentActivity,
        )
        targets.forEachIndexed { index, target ->
            target.alpha = 0f
            target.translationY = 28f
            target.scaleX = 0.98f
            target.scaleY = 0.98f
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 100L).coerceAtMost(300L))
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun startNotificationBellPulse() {
        notificationPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.tvNotificationStatusBadge,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 1f, 0.85f, 1f),
        ).apply {
            duration = 10_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun observeViewModel() {
        viewModel.detectionHistory.observe(viewLifecycleOwner) { history ->
            val items = history.orEmpty()
            val scanned = items.size
            val threats = items.count { it.isSuspicious }
            animateCounter(binding.tvScannedCount, lastScannedCount, scanned)
            animateCounter(binding.tvThreatsCount, lastThreatCount, threats)
            lastScannedCount = scanned
            lastThreatCount = threats
            updateThreatCardStyle(threats)
            renderRecentActivity(items)
            // Animate progress bars
            ObjectAnimator.ofInt(binding.pbStatScanned, "progress", 0, 100).setDuration(1500).start()
            val threatProgress = if (scanned > 0) (threats.toFloat() / scanned * 100).toInt().coerceAtLeast(10) else 100
            ObjectAnimator.ofInt(binding.pbStatThreats, "progress", 0, threatProgress).setDuration(1500).start()

        }

        viewModel.baitingSessions.observe(viewLifecycleOwner) { sessions ->
            val count = sessions.orEmpty().size
            animateCounter(binding.tvBaitedCount, lastBaitedCount, count)
            lastBaitedCount = count
            ObjectAnimator.ofInt(binding.pbStatBaited, "progress", 0, 100).setDuration(1500).start()
        }

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
                confPercent >= 75 -> "HIGH RISK"
                confPercent >= 45 -> "MEDIUM RISK"
                confPercent >= 20 -> "LOW RISK"
                else -> "SAFE"
            }
            binding.tvResultBadge.text = riskLabel
            binding.tvResultBadge.setBackgroundResource(
                when {
                    confPercent >= 75 -> R.drawable.bg_badge_high
                    confPercent >= 45 -> R.drawable.bg_badge_medium
                    confPercent >= 20 -> R.drawable.bg_badge_low
                    else -> R.drawable.bg_badge_safe
                }
            )
            binding.tvResultTitle.text = if (isScam) "Threat Detected" else "Message appears safe"
            binding.tvResultTitle.setTextColor(ContextCompat.getColor(requireContext(),
                if (isScam) R.color.risk_high else R.color.risk_safe))

            val explanation = result.serverVerdict?.explanation?.trim().orEmpty()
            binding.tvResultSummary.text = when {
                explanation.isNotEmpty() -> explanation
                result.ruleVerdict.matchedRules.isNotEmpty() -> {
                    val count = result.ruleVerdict.matchedRules.size
                    "Detected $count suspicious ${if (count == 1) "pattern" else "patterns"} in the message. Tap below to see details."
                }
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

            // Category chip
            binding.tvResultCategory.text = result.bestCategory.formatCategoryLabel()
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

            if (isScam) {
                binding.securityMonitor.setState(SecurityMonitorView.State.THREAT)
            } else {
                updateProtectionStatus()
            }
            viewModel.loadAnalytics()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.btnAnalyze.isEnabled = !loading
            binding.btnAnalyze.text = if (loading) "Analyzing..." else "Analyze Message"
            if (loading) {
                binding.securityMonitor.setState(SecurityMonitorView.State.SCANNING)
            } else if (!isNotificationListenerEnabled()) {
                binding.securityMonitor.setState(SecurityMonitorView.State.INACTIVE)
            } else {
                binding.securityMonitor.setState(SecurityMonitorView.State.PROTECTED)
            }
        }
    }

    private fun openMessageDetails() {
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = R.id.nav_analytics
    }

    private fun renderRecentActivity(items: List<DetectionCacheEntity>) {
        val recent = items.sortedByDescending { it.timestamp }.take(3)
        binding.layoutRecentRows.removeAllViews()

        if (recent.isEmpty()) {
            binding.ivRecentActivityShield.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.layoutRecentRows.visibility = View.GONE
            binding.tvEmptyState.text = "You're protected.\nNo threats detected yet."
            return
        }

        binding.ivRecentActivityShield.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE
        binding.layoutRecentRows.visibility = View.VISIBLE

        recent.forEach { item ->
            binding.layoutRecentRows.addView(createRecentActivityRow(item))
        }
    }

    private fun createRecentActivityRow(item: DetectionCacheEntity): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_recent_activity_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        val dot = View(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_status_dot)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (item.isSuspicious) R.color.risk_medium else R.color.risk_safe)
            )
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
        }

        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            text = if (item.isSuspicious) "Threat reviewed" else "Message checked"
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val meta = TextView(ctx).apply {
            val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(item.timestamp))
            text = "${item.category.formatCategoryLabel()} • ${(item.confidence * 100).toInt()}% • $time"
            setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
            textSize = 12f
        }
        textColumn.addView(title)
        textColumn.addView(meta)

        row.addView(dot)
        row.addView(textColumn)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateThreatCardStyle(threatCount: Int) {
        val ctx = requireContext()
        val hasThreats = threatCount > 0
        val iconColor = if (hasThreats) R.color.primary_light else R.color.text_secondary
        val countColor = if (hasThreats) R.color.primary_light else R.color.text_primary
        binding.ivThreatIcon.setColorFilter(ContextCompat.getColor(ctx, iconColor))
        binding.tvThreatsCount.setTextColor(ContextCompat.getColor(ctx, countColor))
    }

    private fun animateCounter(
        view: android.widget.TextView,
        startVal: Int,
        endVal: Int,
        format: String = "%d",
    ) {
        if (startVal == endVal) {
            view.text = String.format(format, endVal)
            return
        }
        val animator = ValueAnimator.ofInt(startVal, endVal)
        animator.duration = 350
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
        if (context == null) return
        val enabled = isNotificationListenerEnabled()
        val loading = viewModel.isLoading.value == true

        if (enabled) {
            binding.tvNotificationStatusBadge.text = "LIVE"
            binding.tvNotificationStatusBadge.setBackgroundResource(R.drawable.bg_badge_live)
            binding.tvNotificationStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.risk_safe))
        } else {
            binding.tvNotificationStatusBadge.text = "OFFLINE"
            binding.tvNotificationStatusBadge.setBackgroundResource(R.drawable.bg_reason_chip)
            binding.tvNotificationStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }

        if (!loading) {
            binding.securityMonitor.setState(
                if (enabled) SecurityMonitorView.State.PROTECTED
                else SecurityMonitorView.State.INACTIVE
            )
        }

        val status = if (enabled) "Your device is protected" else getString(R.string.protection_inactive)
        val detail = if (enabled) {
            "Monitoring WhatsApp, Telegram, SMS, Email and Instagram."
        } else {
            "Enable notification access to begin protection"
        }
        crossfadeProtectionStatus(enabled, status, detail)
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(requireContext(), if (enabled) R.color.text_primary else R.color.text_secondary)
        )
        binding.tvStatusHeader.setTextColor(
            ContextCompat.getColor(requireContext(), if (enabled) R.color.primary else R.color.text_hint)
        )

        binding.btnToggleProtection.text = if (enabled)
            getString(R.string.protection_active_btn)
        else
            getString(R.string.grant_access)
    }

    private fun crossfadeProtectionStatus(enabled: Boolean, status: String, detail: String) {
        val previous = lastProtectionEnabled
        lastProtectionEnabled = enabled
        if (previous == null || previous == enabled) {
            binding.tvStatus.text = status
            binding.tvStatusDetail.text = detail
            return
        }

        binding.layoutProtectionStatus.animate()
            .alpha(0f)
            .translationY(8f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.tvStatus.text = status
                binding.tvStatusDetail.text = detail
                binding.layoutProtectionStatus.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
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
            .setContentText(sandboxMessage)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
             
        notificationManager.notify(999, notification)
        lastAnalyzedText = sandboxMessage
        viewModel.analyzeMessage(sandboxMessage, demoMode = true)
        Toast.makeText(ctx, "Sandbox: Offline demo detection started", Toast.LENGTH_SHORT).show()
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
