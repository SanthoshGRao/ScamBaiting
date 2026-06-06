package com.scamshield.app.ui

import android.content.ComponentName
import android.content.SharedPreferences
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.scamshield.app.R
import com.scamshield.app.databinding.FragmentSettingsBinding
import com.scamshield.app.service.ScamShieldNotificationService
import dagger.hilt.android.AndroidEntryPoint

/**
 * SettingsFragment — Premium settings panel with organized sections:
 * Protection toggles, Detection tuning sliders, Baiting configuration,
 * Privacy & Data management, and About section.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private data class PersonaUi(
        val key: String,
        val titleRes: Int,
        val descRes: Int,
        @DrawableRes val iconRes: Int,
    )

    private val baitPersonas: List<PersonaUi> = listOf(
        PersonaUi("busy_professional", R.string.persona_busy_professional, R.string.persona_busy_professional_desc, R.drawable.ic_persona_busy),
        PersonaUi("skeptical_buyer", R.string.persona_skeptical_buyer, R.string.persona_skeptical_buyer_desc, R.drawable.ic_persona_skeptical),
        PersonaUi("half_understanding_user", R.string.persona_half_understanding_user, R.string.persona_half_understanding_user_desc, R.drawable.ic_persona_half_understanding),
        PersonaUi("lonely_conversationalist", R.string.persona_lonely_conversationalist, R.string.persona_lonely_conversationalist_desc, R.drawable.ic_persona_lonely),
        PersonaUi("hopeful_opportunity_seeker", R.string.persona_hopeful_opportunity_seeker, R.string.persona_hopeful_opportunity_seeker_desc, R.drawable.ic_persona_hopeful),
        PersonaUi("curious_user", R.string.persona_curious_user, R.string.persona_curious_user_desc, R.drawable.ic_persona_curious),
    )

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupProtectionSection()
        setupDetectionTuning()
        setupBaitingConfig()
        setupPrivacyData()
        setupAppearance()
        setupAbout()

        // Trigger fall-down animation
        binding.settingsRoot.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_fall_down)
        binding.settingsRoot.scheduleLayoutAnimation()
    }

    override fun onResume() {
        super.onResume()
        updateNotifAccessStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════
    // PROTECTION SECTION
    // ═══════════════════════════════════════
    private fun setupProtectionSection() {
        val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)

        // Notification access
        binding.btnNotifAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Real-time Protection (formerly Privacy Mode — inverted logic)
        binding.switchPrivacy.isChecked = !prefs.getBoolean("privacy_mode", false)
        binding.switchPrivacy.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean("privacy_mode", !checked)
                .putBoolean("realtime_protection", checked)
                .apply()
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "Real-time protection enabled" else "Real-time protection disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Auto-Bait toggle
        binding.switchAutoBait.isChecked = prefs.getBoolean("auto_bait", false)
        binding.switchAutoBait.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_bait", checked).apply()
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "Auto-bait enabled" else "Auto-bait disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Stealth Mode toggle
        binding.switchStealth.isChecked = prefs.getBoolean("stealth_mode", false)
        binding.switchStealth.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("stealth_mode", checked).apply()
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "Stealth mode: responses will be delayed randomly"
                else "Stealth mode disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Sound Alerts toggle
        binding.switchSound.isChecked = prefs.getBoolean("sound_alerts", true)
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sound_alerts", checked).apply()
            hapticFeedback()
        }

        // Vibration Alerts toggle
        binding.switchVibrate.isChecked = prefs.getBoolean("vibration_alerts", true)
        binding.switchVibrate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("vibration_alerts", checked).apply()
            hapticFeedback()
        }

        // Floating overlay toggle removed — not needed
    }

    // ═══════════════════════════════════════
    // DETECTION TUNING SECTION
    // ═══════════════════════════════════════
    private fun setupDetectionTuning() {
        val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)

        // Sensitivity Slider
        val savedSens = prefs.getInt("sensitivity", 5).toFloat()
        binding.sliderSensitivity.value = savedSens
        binding.tvSensitivityValue.text = "Level ${savedSens.toInt()}"

        binding.sliderSensitivity.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val level = value.toInt()
                binding.tvSensitivityValue.text = "Level $level"
                prefs.edit().putInt("sensitivity", level).apply()
                hapticFeedback()
            }
        })

        // Confidence Threshold Slider
        val savedThreshold = prefs.getInt("confidence_threshold", 60).toFloat()
        binding.sliderThreshold.value = savedThreshold
        binding.tvThresholdValue.text = "${savedThreshold.toInt()}%"

        binding.sliderThreshold.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val pct = value.toInt()
                binding.tvThresholdValue.text = "$pct%"
                prefs.edit().putInt("confidence_threshold", pct).apply()
                hapticFeedback()
            }
        })
    }

    // ═══════════════════════════════════════
    // BAITING CONFIGURATION SECTION
    // ═══════════════════════════════════════
    private fun setupBaitingConfig() {
        val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)
        if (prefs.getInt("max_replies", 10) > SimulatorScriptBank.MAX_SIM_ROUNDS) {
            prefs.edit().putInt("max_replies", SimulatorScriptBank.MAX_SIM_ROUNDS).apply()
        }

        val currentKey = prefs.getString("active_persona", "busy_professional")
        val currentIndex = baitPersonas.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        binding.tvCurrentPersona.text = getString(baitPersonas[currentIndex].titleRes)

        binding.btnChangePersona.setOnClickListener {
            val idx = baitPersonas.indexOfFirst {
                it.key == prefs.getString("active_persona", "busy_professional")
            }.coerceAtLeast(0)
            showPersonaPickerDialog(prefs, idx)
        }

        // Response Delay Slider
        val savedDelay = prefs.getInt("response_delay", 5).toFloat()
        binding.sliderDelay.value = savedDelay
        binding.tvDelayValue.text = "${savedDelay.toInt()}s"

        binding.sliderDelay.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sec = value.toInt()
                binding.tvDelayValue.text = "${sec}s"
                prefs.edit().putInt("response_delay", sec).apply()
                hapticFeedback()
            }
        })

        // Max Replies Slider (1–20; simulator + session cap)
        val savedReplies = prefs.getInt("max_replies", 10).coerceIn(1, 20).toFloat()
        binding.sliderMaxReplies.value = savedReplies
        binding.tvMaxRepliesValue.text = getString(R.string.replies_value, savedReplies.toInt())

        binding.sliderMaxReplies.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val count = value.toInt().coerceIn(1, 20)
                binding.tvMaxRepliesValue.text = getString(R.string.replies_value, count)
                prefs.edit().putInt("max_replies", count).apply()
                hapticFeedback()
            }
        })

        // Auto-Report Toggle
        binding.switchAutoReport.isChecked = prefs.getBoolean("auto_report", false)
        binding.switchAutoReport.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_report", checked).apply()
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "Auto-report enabled" else "Auto-report disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showPersonaPickerDialog(prefs: SharedPreferences, currentIndex: Int) {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_persona_picker, null, false)
        val container = dialogView.findViewById<LinearLayout>(R.id.persona_options_container)
        var selectedIdx = currentIndex

        val strokePx = (2f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        val transparent = ContextCompat.getColor(requireContext(), R.color.transparent)
        val cardBg = ContextCompat.getColor(requireContext(), R.color.card_bg)
        val selectedBg = ContextCompat.getColor(requireContext(), R.color.primary_10)

        fun applyVisualSelection() {
            for (i in 0 until container.childCount) {
                val card = container.getChildAt(i) as MaterialCardView
                val check = card.findViewById<ImageView>(R.id.persona_check)
                val selected = i == selectedIdx
                if (selected) {
                    card.strokeWidth = strokePx
                    card.strokeColor = primary
                    card.setCardBackgroundColor(selectedBg)
                    check.visibility = View.VISIBLE
                } else {
                    card.strokeWidth = 0
                    card.strokeColor = transparent
                    card.setCardBackgroundColor(cardBg)
                    check.visibility = View.GONE
                }
            }
        }

        baitPersonas.forEachIndexed { index, persona ->
            val card = inflater.inflate(R.layout.item_persona_option, container, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.persona_icon).setImageResource(persona.iconRes)
            card.findViewById<TextView>(R.id.persona_title).text = getString(persona.titleRes)
            card.findViewById<TextView>(R.id.persona_desc).text = getString(persona.descRes)
            card.setOnClickListener {
                selectedIdx = index
                applyVisualSelection()
                hapticFeedback()
            }
            container.addView(card)
        }

        applyVisualSelection()

        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton(R.string.persona_picker_save) { dialog, _ ->
                val persona = baitPersonas[selectedIdx]
                prefs.edit().putString("active_persona", persona.key).apply()
                binding.tvCurrentPersona.text = getString(persona.titleRes)
                hapticFeedback()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.persona_picker_saved_fmt, getString(persona.titleRes)),
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ═══════════════════════════════════════
    // PRIVACY & DATA SECTION
    // ═══════════════════════════════════════
    private fun setupPrivacyData() {
        val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)

        // On-Device Analysis Toggle
        binding.switchOnDevice.isChecked = prefs.getBoolean("on_device_only", false)
        updatePrivacyBadge(prefs.getBoolean("on_device_only", false))

        binding.switchOnDevice.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("on_device_only", checked).apply()
            updatePrivacyBadge(checked)
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "On-device mode: no data leaves your device"
                else "Cloud analysis enabled for higher accuracy",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.switchDemoMode.isChecked = prefs.getBoolean("demo_mode", false)
        binding.switchDemoMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("demo_mode", checked).apply()
            hapticFeedback()
            Toast.makeText(
                requireContext(),
                if (checked) "Demo mode enabled (offline simulation)" else "Demo mode disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Data Retention Slider
        val savedRetention = prefs.getInt("data_retention_days", 7).toFloat()
        binding.sliderRetention.value = savedRetention
        binding.tvRetentionValue.text = "${savedRetention.toInt()} days"

        binding.sliderRetention.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val days = value.toInt()
                binding.tvRetentionValue.text = "$days days"
                prefs.edit().putInt("data_retention_days", days).apply()
                hapticFeedback()
            }
        })

        // Clear cache
        binding.btnClearCache.setOnClickListener {
            hapticFeedback()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear All Data")
                .setMessage("Are you sure you want to completely erase the detection cache, baiting history, active sessions, sender reputation data, and the verified scammer list?\n\nThis resets on-device learning and clears live-notification deduplication so the same scenario (e.g. sandbox demo) can trigger detection again.")
                .setPositiveButton("Continue") { dialog, _ ->
                    viewModel.clearCache()
                    
                    // Save timestamp to hide AI buttons for older scammers in the UI
                    prefs.edit()
                        .putLong("last_cache_clear_time", System.currentTimeMillis())
                        .putLong("detection_runtime_generation", System.currentTimeMillis())
                        .apply()
                    
                    hapticFeedback()
                    Toast.makeText(requireContext(), "All history and active sessions cleared", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        // Reload keywords
        binding.btnResetKeywords.setOnClickListener {
            viewModel.reloadKeywords()
            hapticFeedback()
            Toast.makeText(requireContext(), "Keyword database reloaded", Toast.LENGTH_SHORT).show()
        }

        // Export Logs
        binding.btnExportLogs.setOnClickListener {
            hapticFeedback()
            exportBaitingLogs()
        }
    }

    // ═══════════════════════════════════════
    // ABOUT SECTION
    // ═══════════════════════════════════════
    
    // ═══════════════════════════════════════
    // APPEARANCE SECTION
    // ═══════════════════════════════════════
    private fun setupAppearance() {
        val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)
        
        val isDarkMode = prefs.getBoolean("dark_mode_enabled", true)
        binding.switchDarkMode.isChecked = isDarkMode
        
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_mode_enabled", checked).apply()
            hapticFeedback()

            val targetMode = if (checked) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        }
    }
    private fun setupAbout() {
        viewModel.keywordCount.observe(viewLifecycleOwner) { count ->
            binding.tvKeywordCount.text = "Keywords loaded: $count"
        }
        viewModel.loadKeywordCount()
    }

    // ═══════════════════════════════════════
    // HELPER FUNCTIONS
    // ═══════════════════════════════════════
    private fun updateNotifAccessStatus() {
        val ctx = context ?: return
        val cn = ComponentName(ctx, ScamShieldNotificationService::class.java)
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())

        binding.tvNotifAccessStatus.text = if (enabled) "Granted" else "Not granted"
        binding.tvNotifAccessStatus.setTextColor(
            ContextCompat.getColor(ctx, if (enabled) R.color.risk_safe else R.color.risk_high)
        )
        binding.btnNotifAccess.text = if (enabled) "Active" else "Grant"
        binding.btnNotifAccess.isEnabled = !enabled
    }

    private fun updatePrivacyBadge(onDevice: Boolean) {
        binding.tvPrivacyBadge.text = if (onDevice)
            getString(R.string.privacy_badge_on)
        else
            getString(R.string.privacy_badge_off)
    }

    private fun hapticFeedback() {
        try {
            val vibrator = ContextCompat.getSystemService(requireContext(), Vibrator::class.java)
            vibrator?.vibrate(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) { }
    }

    private fun exportBaitingLogs() {
        viewModel.baitingSessions.observe(viewLifecycleOwner) { sessions ->
            if (sessions.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "No baiting logs to export", Toast.LENGTH_SHORT).show()
                return@observe
            }
            val sb = StringBuilder("=== ScamShield Baiting Logs ===\n\n")
            sessions.forEach { session ->
                sb.appendLine("Sender: ${session.senderId}")
                sb.appendLine("Persona: ${session.persona}")
                sb.appendLine("Messages: ${session.totalMessages}")
                sb.appendLine("Status: ${if (session.isActive) "Active" else "Closed"}")
                sb.appendLine("Started: ${session.startTime}")
                sb.appendLine("---")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ScamShield Baiting Logs")
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            }
            startActivity(Intent.createChooser(shareIntent, "Export Logs"))
        }
        viewModel.loadAnalytics()
    }
}
