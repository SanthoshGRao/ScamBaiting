package com.scamshield.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scamshield.app.R
import com.scamshield.app.databinding.FragmentSimulatorBinding
import com.scamshield.app.databinding.ItemSimMessageBinding
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * SimulatorFragment — Live Scam Conversation Simulator.
 *
 * Demonstrates AI scam-baiting engagement in a controlled environment
 * with WhatsApp-style chat UI, scenario selection, and persona switching.
 */
@AndroidEntryPoint
class SimulatorFragment : Fragment() {

    private var _binding: FragmentSimulatorBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val chatMessages = mutableListOf<SimMessage>()
    private lateinit var chatAdapter: SimChatAdapter
    private lateinit var prefs: SharedPreferences

    private var isRunning = false
    private var currentScenario = "lottery"
    private var currentStep = 0
    private var messageCount = 0
    private var scriptedScenarios: Map<String, List<Pair<String, String>>> = emptyMap()
    private var scriptVariantIndex = 0

    private var dotIndex = 0
    private var typingDotRunner: Runnable? = null

    private enum class SessionEnd { None, UserStop, HitReplyLimit, ScriptComplete }

    private var sessionEnd = SessionEnd.None
    private var typingRevealIndex: Int? = null
    private val textRevealRunnables = mutableMapOf<Int, Runnable>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "max_replies") {
            view?.post {
                onMaxRepliesChanged()
                if (isRunning && currentStep >= maxRepliesFromPrefs()) {
                    sessionEnd = SessionEnd.HitReplyLimit
                    stopSimulation(fromUser = false)
                }
            }
        }
    }

    data class SimMessage(
        val text: String,
        val isScammer: Boolean,
        val timestamp: String,
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSimulatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences("scamshield_prefs", Context.MODE_PRIVATE)
        prefs.getInt("max_replies", 10).let { saved ->
            if (saved > SimulatorScriptBank.MAX_SIM_ROUNDS) {
                prefs.edit().putInt("max_replies", SimulatorScriptBank.MAX_SIM_ROUNDS).apply()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        chatAdapter = SimChatAdapter(chatMessages)
        binding.rvChatMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            visibility = View.GONE
        }
        binding.layoutEmptyChat.visibility = View.VISIBLE

        setupScenarioChips()
        setupButtons()
        setupSimRepliesSlider()
        loadPersonaFromPrefs()
        syncSimRepliesSliderFromPrefs()
        scriptedScenarios = loadScenarioOverrides()
        updateRoundProgress()
        updateContinueVisibility()
    }

    override fun onResume() {
        super.onResume()
        loadPersonaFromPrefs()
        syncSimRepliesSliderFromPrefs()
        onMaxRepliesChanged()
    }

    override fun onDestroyView() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        cancelAllTypingReveals()
        cancelTypingDotRunner()
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    private fun maxRepliesFromPrefs(): Int =
        prefs.getInt("max_replies", 10).coerceIn(1, SimulatorScriptBank.MAX_SIM_ROUNDS)

    private fun setupSimRepliesSlider() {
        binding.sliderSimReplies.addOnChangeListener { _, value, fromUser ->
            val c = value.toInt().coerceIn(1, SimulatorScriptBank.MAX_SIM_ROUNDS)
            binding.tvSimRepliesValue.text = getString(R.string.replies_value, c)
            if (fromUser) {
                prefs.edit().putInt("max_replies", c).apply()
                hapticLight()
            }
            updateRoundProgress()
            onMaxRepliesChanged()
        }
    }

    private fun syncSimRepliesSliderFromPrefs() {
        if (_binding == null) return
        val v = maxRepliesFromPrefs().toFloat()
        if (kotlin.math.abs(binding.sliderSimReplies.value - v) > 0.01f) {
            binding.sliderSimReplies.value = v
        }
        binding.tvSimRepliesValue.text = getString(R.string.replies_value, maxRepliesFromPrefs())
    }

    private fun onMaxRepliesChanged() {
        if (_binding == null) return
        syncSimRepliesSliderFromPrefs()
        updateRoundProgress()
        val limit = maxRepliesFromPrefs()
        val scriptLen = fullScriptBuffer().size
        if (sessionEnd == SessionEnd.HitReplyLimit && !isRunning &&
            chatMessages.isNotEmpty() && currentStep < scriptLen && limit > currentStep
        ) {
            resumeAfterLimitIncrease()
        }
        updateContinueVisibility()
    }

    private fun resumeAfterLimitIncrease() {
        sessionEnd = SessionEnd.None
        isRunning = true
        binding.btnStartSim.isEnabled = false
        binding.btnStopSim.isEnabled = true
        binding.scrollScenarios.alpha = 0.5f
        binding.btnContinueSim.visibility = View.GONE
        postScammerMessage(400L)
    }

    private fun hapticLight() {
        runCatching {
            binding.sliderSimReplies.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun loadPersonaFromPrefs() {
        if (_binding == null) return
        val persona = prefs.getString("active_persona", "busy_professional") ?: "busy_professional"
        val names = mapOf(
            "busy_professional" to "Busy Professional", "skeptical_buyer" to "Skeptical Buyer",
            "half_understanding_user" to "Half-Understanding User", "lonely_conversationalist" to "Lonely Conversationalist",
            "hopeful_opportunity_seeker" to "Hopeful Opportunity Seeker", "curious_user" to "Curious User",
        )
        binding.tvSimPersona.text = names[persona]
            ?: persona.replace("_", " ").replaceFirstChar { it.uppercaseChar() }
    }

    private fun setupScenarioChips() {
        binding.chipGroupScenario.setOnCheckedStateChangeListener { _, checkedIds ->
            currentScenario = when {
                checkedIds.contains(R.id.chipLottery) -> "lottery"
                checkedIds.contains(R.id.chipOtp) -> "otp"
                checkedIds.contains(R.id.chipInvestment) -> "investment"
                checkedIds.contains(R.id.chipTech) -> "tech"
                checkedIds.contains(R.id.chipDelivery) -> "delivery"
                else -> "lottery"
            }
        }
    }

    private fun setupButtons() {
        binding.btnStartSim.setOnClickListener {
            if (!isRunning) startSimulation()
        }
        binding.btnStopSim.setOnClickListener {
            if (isRunning) stopSimulation(fromUser = true)
        }
        binding.btnContinueSim.setOnClickListener {
            if (binding.btnContinueSim.isEnabled) resumeAfterLimitIncrease()
        }
    }

    private fun startSimulation() {
        isRunning = true
        sessionEnd = SessionEnd.None
        currentStep = 0
        messageCount = 0
        val n = SimulatorScriptBank.variantCount(currentScenario)
        scriptVariantIndex = if (n <= 1) 0 else Random.nextInt(n)
        cancelAllTypingReveals()
        chatMessages.clear()
        chatAdapter.notifyDataSetChanged()

        binding.layoutEmptyChat.visibility = View.GONE
        binding.rvChatMessages.visibility = View.VISIBLE
        binding.btnStartSim.isEnabled = false
        binding.btnStopSim.isEnabled = true
        binding.tvSessionInfo.visibility = View.VISIBLE
        binding.tvSimRoundProgress.visibility = View.VISIBLE
        binding.scrollScenarios.alpha = 0.5f
        binding.btnContinueSim.visibility = View.GONE

        binding.btnStopSim.scaleX = 0.8f
        binding.btnStopSim.scaleY = 0.8f
        binding.btnStopSim.animate().scaleX(1f).scaleY(1f).setDuration(250).start()

        Toast.makeText(requireContext(), "Simulation started: ${currentScenario.uppercase()}", Toast.LENGTH_SHORT).show()
        updateRoundProgress()
        postScammerMessage(0)
    }

    private fun stopSimulation(fromUser: Boolean = false) {
        isRunning = false
        cancelTypingDotRunner()
        handler.removeCallbacksAndMessages(null)
        if (fromUser) sessionEnd = SessionEnd.UserStop
        binding.btnStartSim.isEnabled = true
        binding.btnStopSim.isEnabled = false
        binding.layoutTypingIndicator.visibility = View.GONE
        binding.scrollScenarios.alpha = 1f
        updateRoundProgress()
        updateContinueVisibility()
        Toast.makeText(requireContext(), "Session ended: $messageCount messages", Toast.LENGTH_SHORT).show()
    }

    private fun updateRoundProgress() {
        if (_binding == null) return
        val limit = maxRepliesFromPrefs()
        val cap = kotlin.math.min(fullScriptBuffer().size, limit)
        if (isRunning || chatMessages.isNotEmpty()) {
            binding.tvSimRoundProgress.visibility = View.VISIBLE
            binding.tvSimRoundProgress.text = getString(
                R.string.sim_round_progress,
                currentStep.coerceAtMost(cap),
                cap,
            )
        }
    }

    private fun updateContinueVisibility() {
        if (_binding == null) return
        val scriptLen = fullScriptBuffer().size
        val show = !isRunning &&
            sessionEnd == SessionEnd.HitReplyLimit &&
            chatMessages.isNotEmpty() &&
            currentStep < scriptLen
        binding.btnContinueSim.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnContinueSim.isEnabled = maxRepliesFromPrefs() > currentStep
    }

    private fun showTypingIndicator(label: String) {
        if (_binding == null) return
        cancelTypingDotRunner()
        binding.layoutTypingIndicator.visibility = View.VISIBLE
        binding.tvTypingIndicator.text = label
        dotIndex = 0
        val dots = listOf(binding.dotTyping1, binding.dotTyping2, binding.dotTyping3)
        val runner = object : Runnable {
            override fun run() {
                if (_binding == null || binding.layoutTypingIndicator.visibility != View.VISIBLE) return
                dots.forEachIndexed { i, v ->
                    v.alpha = if (i == dotIndex % 3) 1f else 0.28f
                    v.scaleX = if (i == dotIndex % 3) 1.15f else 0.9f
                    v.scaleY = if (i == dotIndex % 3) 1.15f else 0.9f
                }
                dotIndex++
                handler.postDelayed(this, 320L)
            }
        }
        typingDotRunner = runner
        handler.post(runner)
    }

    private fun cancelTypingDotRunner() {
        typingDotRunner?.let { handler.removeCallbacks(it) }
        typingDotRunner = null
    }

    private fun hideTypingIndicator() {
        if (_binding == null) return
        cancelTypingDotRunner()
        binding.layoutTypingIndicator.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (_binding != null) {
                    binding.layoutTypingIndicator.visibility = View.GONE
                    binding.layoutTypingIndicator.alpha = 1f
                }
            }
            .start()
    }

    private fun getRevealTimeMs(text: String): Long {
        val step = if (text.length > 120) 3 else if (text.length > 60) 2 else 1
        val delayMs = if (text.length > 120) 10L else 14L
        return ((text.length / step) * delayMs) + 150L
    }

    private fun postScammerMessage(delay: Long) {
        if (!isRunning) return
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning || _binding == null) return

                val script = fullScriptBuffer()
                val limit = maxRepliesFromPrefs()

                if (currentStep >= script.size) {
                    sessionEnd = SessionEnd.ScriptComplete
                    stopSimulation(fromUser = false)
                    return
                }
                if (currentStep >= limit) {
                    sessionEnd = SessionEnd.HitReplyLimit
                    stopSimulation(fromUser = false)
                    return
                }

                val pair = script[currentStep]
                showTypingIndicator(getString(R.string.typing_scammer_composing))

                val scammerTypingTime = 300L

                handler.postDelayed(object : Runnable {
                    override fun run() {
                        if (!isRunning || _binding == null) return
                        hideTypingIndicator()
                        addMessage(pair.first, isScammer = true)

                        val scammerRevealTime = getRevealTimeMs(pair.first)

                        handler.postDelayed(object : Runnable {
                            override fun run() {
                                if (!isRunning || _binding == null) return
                                showTypingIndicator(getString(R.string.typing_agent_composing))

                                val totalResponseDelay = 300L

                                handler.postDelayed(object : Runnable {
                                    override fun run() {
                                        if (!isRunning || _binding == null) return
                                        hideTypingIndicator()
                                        addMessage(pair.second, isScammer = false)
                                        currentStep++
                                        updateRoundProgress()
                                        updateContinueVisibility()

                                        val aiRevealTime = getRevealTimeMs(pair.second)
                                        postScammerMessage(aiRevealTime + 300L)
                                    }
                                }, totalResponseDelay)
                            }
                        }, scammerRevealTime)
                    }
                }, scammerTypingTime)
            }
        }, delay)
    }

    private fun addMessage(text: String, isScammer: Boolean) {
        // Strip commas from AI (non-scammer) replies as requested by user
        val processedText = if (!isScammer) text.replace(",", "") else text
        
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val msg = SimMessage(processedText, isScammer, timeFormat.format(Date()))
        chatMessages.add(msg)
        messageCount++
        typingRevealIndex = chatMessages.size - 1
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        
        // Ensure scroll happens after layout to prevent messages going out of screen
        binding.rvChatMessages.post {
            binding.rvChatMessages.scrollToPosition(chatMessages.size - 1)
        }
        binding.tvSessionInfo.text = "$messageCount msgs"
    }

    private fun scenarioBasePairs(): List<Pair<String, String>> {
        val persona = SimulatorScriptBank.normalizePersona(prefs.getString("active_persona", "busy_professional"))
        val compositeKey = "${currentScenario}_$persona"
        scriptedScenarios[compositeKey]?.let { return it }
        return when (currentScenario) {
            "lottery" -> SimulatorScriptBank.lottery(persona, scriptVariantIndex)
            "otp" -> SimulatorScriptBank.otp(persona, scriptVariantIndex)
            "investment" -> SimulatorScriptBank.investment(persona, scriptVariantIndex)
            "tech" -> SimulatorScriptBank.tech(persona, scriptVariantIndex)
            "delivery" -> SimulatorScriptBank.delivery(persona, scriptVariantIndex)
            else -> SimulatorScriptBank.lottery(persona, scriptVariantIndex)
        }
    }

    private fun fullScriptBuffer(): List<Pair<String, String>> {
        val persona = SimulatorScriptBank.normalizePersona(prefs.getString("active_persona", "busy_professional"))
        val base = scenarioBasePairs()
        return SimulatorScriptBank.scenarioExtendedFromBase(
            currentScenario,
            persona,
            base,
            SimulatorScriptBank.MAX_SIM_ROUNDS,
        )
    }

    private fun cancelAllTypingReveals() {
        textRevealRunnables.values.forEach { handler.removeCallbacks(it) }
        textRevealRunnables.clear()
        typingRevealIndex = null
    }

    private fun loadScenarioOverrides(): Map<String, List<Pair<String, String>>> {
        return try {
            val raw = requireContext().assets.open("demo_messages.json").bufferedReader().readText()
            val root = JSONObject(raw)
            root.keys().asSequence().mapNotNull { key ->
                val arr = root.optJSONArray(key) ?: return@mapNotNull null
                key to parseMessagePairs(arr)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseMessagePairs(arr: JSONArray): List<Pair<String, String>> {
        return (0 until arr.length()).map { i ->
            val pair = arr.getJSONArray(i)
            pair.getString(0) to pair.getString(1)
        }
    }

    inner class SimChatAdapter(
        private val messages: List<SimMessage>,
    ) : RecyclerView.Adapter<SimChatAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemSimMessageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemSimMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            textRevealRunnables.remove(position)?.let { handler.removeCallbacks(it) }

            if (msg.isScammer) {
                holder.binding.layoutScammer.visibility = View.VISIBLE
                holder.binding.layoutAI.visibility = View.GONE
                holder.binding.tvScammerTime.text = msg.timestamp
                bindWithOptionalReveal(holder.binding.tvScammerMessage, msg.text, position)
            } else {
                holder.binding.layoutScammer.visibility = View.GONE
                holder.binding.layoutAI.visibility = View.VISIBLE
                holder.binding.tvAITime.text = msg.timestamp
                val personaKey = prefs.getString("active_persona", "busy_professional") ?: "busy_professional"
                val names = mapOf(
                    "busy_professional" to "Busy Professional", "skeptical_buyer" to "Skeptical Buyer",
                    "half_understanding_user" to "Half-Understanding User", "lonely_conversationalist" to "Lonely Conversationalist",
                    "hopeful_opportunity_seeker" to "Hopeful Opportunity Seeker", "curious_user" to "Curious User",
                )
                holder.binding.tvAILabel.text = "AI \u2022 ${names[personaKey] ?: personaKey}"
                bindWithOptionalReveal(holder.binding.tvAIMessage, msg.text, position)
            }

            val translationStart = if (msg.isScammer) -72f else 72f
            holder.itemView.alpha = 0f
            holder.itemView.translationX = translationStart
            holder.itemView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        override fun onViewRecycled(holder: ViewHolder) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                textRevealRunnables.remove(pos)?.let { handler.removeCallbacks(it) }
            }
            super.onViewRecycled(holder)
        }

        private fun bindWithOptionalReveal(tv: android.widget.TextView, full: String, position: Int) {
            val doReveal = position == typingRevealIndex && full.length > 6
            if (!doReveal) {
                tv.text = full
                if (position == typingRevealIndex) typingRevealIndex = null
                return
            }
            tv.text = ""
            var idx = 0
            val step = if (full.length > 120) 3 else if (full.length > 60) 2 else 1
            val delayMs = if (full.length > 120) 10L else 14L
            val runnable = object : Runnable {
                override fun run() {
                    if (_binding == null) return
                    idx = (idx + step).coerceAtMost(full.length)
                    tv.text = full.take(idx)
                    
                    // Auto-scroll to keep expanding text visible
                    if (position == chatMessages.size - 1) {
                        _binding?.rvChatMessages?.scrollToPosition(position)
                    }

                    if (idx < full.length) {
                        handler.postDelayed(this, delayMs)
                    } else {
                        textRevealRunnables.remove(position)
                        typingRevealIndex = null
                    }
                }
            }
            textRevealRunnables[position] = runnable
            handler.post(runnable)
        }

        override fun getItemCount() = messages.size
    }
}
