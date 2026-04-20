package com.scamshield.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.scamshield.app.R
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.databinding.FragmentAnalyticsBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * AnalyticsFragment — Detection history, threat statistics,
 * scam type breakdown, persona effectiveness, and filter chips.
 */
@AndroidEntryPoint
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var historyAdapter: DetectionHistoryAdapter
    private lateinit var baitingAdapter: BaitingSessionsAdapter

    private var allHistory: List<DetectionCacheEntity> = emptyList()
    private var currentFilter: String = "all"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyAdapter = DetectionHistoryAdapter()
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }

        baitingAdapter = BaitingSessionsAdapter(
            onItemClick = { senderId ->
                val intent = android.content.Intent(requireContext(), BaitingLogActivity::class.java).apply {
                    putExtra("sender", senderId)
                }
                startActivity(intent)
            },
            onBaitToggle = { senderId, isActive ->
                if (isActive) {
                    viewModel.stopBaiting(senderId)
                } else {
                    viewModel.startBaiting(senderId)
                }
                // Refresh after a short delay
                view.postDelayed({ viewModel.loadAnalytics() }, 500)
            }
        )
        binding.rvSenders.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = baitingAdapter
        }

        setupFilterChips()
        configureDetectionsTrendChart()
        observeViewModel()
        viewModel.loadAnalytics()

        // Trigger staggered layout animation
        binding.analyticsRoot.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_stagger)
        binding.analyticsRoot.scheduleLayoutAnimation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipScam) -> "scam"
                checkedIds.contains(R.id.chipSafe) -> "safe"
                checkedIds.contains(R.id.chipBaited) -> "baited"
                else -> "all"
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            "scam" -> allHistory.filter { it.isSuspicious }
            "safe" -> allHistory.filter { !it.isSuspicious }
            "baited" -> allHistory.filter { it.isSuspicious } // approximate
            else -> allHistory
        }
        historyAdapter.submitList(filtered)
        binding.tvEmptyAnalytics.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvHistory.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun observeViewModel() {
        viewModel.detectionHistory.observe(viewLifecycleOwner) { history ->
            allHistory = history
            applyFilter()

            // Stats
            binding.tvTotalAnalyzed.text = history.size.toString()
            val threats = history.count { it.isSuspicious }
            val rate = if (history.isNotEmpty()) (threats * 100 / history.size) else 0
            binding.tvThreatRate.text = "$rate%"
            binding.tvThreatRate.setTextColor(
                ContextCompat.getColor(requireContext(), when {
                    rate > 50 -> R.color.risk_high
                    rate > 25 -> R.color.risk_medium
                    else -> R.color.risk_safe
                })
            )

            // Scam Type Breakdown
            updateScamTypeBreakdown(history)
        }

        viewModel.baitingSessions.observe(viewLifecycleOwner) { sessions ->
            baitingAdapter.submitList(sessions)
            binding.tvEmptySenders.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            binding.rvSenders.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE

            // Persona effectiveness
            updatePersonaEffectiveness(sessions)
        }

        viewModel.analyticsSummary.observe(viewLifecycleOwner) { summary ->
            if (summary == null) return@observe
            binding.tvTotalAnalyzed.text = summary.total_messages_processed.toString()
            val threatRate = if (summary.total_messages_processed == 0) 0 else
                (summary.total_scams_detected * 100 / summary.total_messages_processed)
            binding.tvThreatRate.text = "$threatRate%"
            renderTrend(summary.detection_trend.mapIndexed { index, p -> Entry(index.toFloat(), p.count.toFloat()) })
        }
    }

    private fun configureDetectionsTrendChart() {
        val ctx = requireContext()
        val axisLabel = ContextCompat.getColor(ctx, R.color.text_secondary)
        val axisLine = ContextCompat.getColor(ctx, R.color.chart_axis_line)
        val grid = ContextCompat.getColor(ctx, R.color.chart_grid)
        val legendText = ContextCompat.getColor(ctx, R.color.text_primary)

        binding.lineTrend.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            setNoDataText(getString(R.string.chart_no_data))
            setNoDataTextColor(axisLabel)
            axisRight.isEnabled = false
            setExtraOffsets(10f, 8f, 14f, 18f)
        }

        binding.lineTrend.legend.apply {
            verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
            textColor = legendText
            textSize = 12f
            formToTextSpace = 6f
            xEntrySpace = 8f
            yOffset = 4f
        }

        binding.lineTrend.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = axisLabel
            textSize = 10f
            setDrawGridLines(true)
            gridColor = grid
            gridLineWidth = 0.9f
            axisLineColor = axisLine
            axisLineWidth = 1.1f
            setDrawAxisLine(true)
            granularity = 1f
            isGranularityEnabled = true
            yOffset = 8f
        }

        binding.lineTrend.axisLeft.apply {
            textColor = axisLabel
            textSize = 10f
            setDrawGridLines(true)
            gridColor = grid
            gridLineWidth = 0.9f
            axisLineColor = axisLine
            axisLineWidth = 1.1f
            setDrawAxisLine(true)
            axisMinimum = 0f
            spaceTop = 12f
        }
    }

    private fun renderTrend(points: List<Entry>) {
        val ctx = requireContext()
        val primary = ContextCompat.getColor(ctx, R.color.primary)
        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val valueLabel = ContextCompat.getColor(ctx, R.color.text_primary)
        val hole = ContextCompat.getColor(ctx, R.color.card_bg)

        if (points.isEmpty()) {
            binding.lineTrend.data = null
            binding.lineTrend.invalidate()
            return
        }

        val dataSet = LineDataSet(points, getString(R.string.chart_legend_detections)).apply {
            color = primary
            setCircleColor(accent)
            lineWidth = 2.2f
            circleRadius = 4f
            valueTextSize = 10f
            valueTextColor = valueLabel
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.12f
            setDrawCircleHole(true)
            circleHoleRadius = 1.6f
            setCircleHoleColor(hole)
        }
        binding.lineTrend.data = LineData(dataSet)
        binding.lineTrend.invalidate()
        binding.lineTrend.animateX(380)
    }

    private fun updateScamTypeBreakdown(history: List<DetectionCacheEntity>) {
        if (history.isEmpty()) return
        val total = history.size.toFloat()

        // Count categories
        var phishing = 0; var otp = 0; var financial = 0; var other = 0
        history.forEach { item ->
            val cat = item.category.lowercase()
            when {
                cat.contains("phish") || cat.contains("link") -> phishing++
                cat.contains("otp") || cat.contains("verify") -> otp++
                cat.contains("financial") || cat.contains("money") || cat.contains("bank") -> financial++
                else -> if (item.isSuspicious) other++
            }
        }

        val phishPct = (phishing / total * 100).toInt()
        val otpPct = (otp / total * 100).toInt()
        val finPct = (financial / total * 100).toInt()
        val otherPct = (other / total * 100).toInt()

        // Update bars with weight-based width simulation
        binding.tvPhishingPct.text = "$phishPct%"
        binding.tvOtpPct.text = "$otpPct%"
        binding.tvFinancialPct.text = "$finPct%"
        binding.tvOtherPct.text = "$otherPct%"

        // Animate bar widths with overshoot
        animateBar(binding.barPhishing, phishPct)
        animateBar(binding.barOtp, otpPct)
        animateBar(binding.barFinancial, finPct)
        animateBar(binding.barOther, otherPct)
    }

    private fun animateBar(bar: View, percent: Int) {
        val parent = bar.parent as? View ?: return
        bar.post {
            val targetWidth = (parent.width * percent / 100f).toInt().coerceAtLeast(if (percent > 0) 4 else 0)
            val params = bar.layoutParams
            params.width = 0
            bar.layoutParams = params

            bar.animate()
                .setDuration(700)
                .setInterpolator(OvershootInterpolator(0.8f))
                .setUpdateListener {
                    val p = bar.layoutParams
                    p.width = (targetWidth * it.animatedFraction).toInt()
                    bar.layoutParams = p
                }
                .start()
        }
    }

    private fun updatePersonaEffectiveness(sessions: List<com.scamshield.app.data.local.entity.BaitingSessionEntity>) {
        val container = binding.layoutPersonaStats
        val emptyText = binding.tvPersonaStatsEmpty

        if (sessions.isEmpty()) {
            for (i in container.childCount - 1 downTo 0) {
                if (container.getChildAt(i).id != R.id.tvPersonaStatsEmpty) {
                    container.removeViewAt(i)
                }
            }
            emptyText.visibility = View.VISIBLE
            return
        }

        emptyText.visibility = View.GONE

        // Remove previous dynamic views (keep the empty text)
        val childCount = container.childCount
        for (i in childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.id != R.id.tvPersonaStatsEmpty) {
                container.removeViewAt(i)
            }
        }

        // Group by persona — no emojis
        val grouped = sessions.groupBy { it.persona }
        val personaNames = mapOf(
            "busy_professional" to "Busy Professional", "skeptical_buyer" to "Skeptical Buyer",
            "half_understanding_user" to "Half-Understanding User", "lonely_conversationalist" to "Lonely Conversationalist",
            "hopeful_opportunity_seeker" to "Hopeful Opportunity Seeker", "curious_user" to "Curious User"
        )

        grouped.forEach { (persona, personaSessions) ->
            val avgMessages = if (personaSessions.isNotEmpty()) personaSessions.map { it.totalMessages }.average().toInt() else 0
            val displayName = personaNames[persona]
                ?: persona.replace("_", " ").replaceFirstChar { it.uppercaseChar() }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = displayName
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }

            val stat = TextView(requireContext()).apply {
                text = "$avgMessages avg msgs \u00b7 ${personaSessions.size} sessions"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            }

            row.addView(label)
            row.addView(stat)
            container.addView(row)

            // Fade-in animation for each row
            row.alpha = 0f
            row.animate().alpha(1f).setDuration(300).setStartDelay(100L).start()
        }
    }
}
