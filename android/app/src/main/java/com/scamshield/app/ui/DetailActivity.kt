package com.scamshield.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scamshield.app.R
import com.scamshield.app.databinding.ActivityDetailBinding
import com.scamshield.app.util.formatCategoryLabel
import dagger.hilt.android.AndroidEntryPoint

/**
 * DetailActivity — Shows detailed scam detection results.
 * Opened from notification action "View Details".
 */
@AndroidEntryPoint
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sender = intent.getStringExtra("sender") ?: "Unknown"
        val category = intent.getStringExtra("category") ?: "unknown"
        val confidence = intent.getStringExtra("confidence")?.toFloatOrNull() ?: 0f
        val confPercent = (confidence * 100).toInt()
        val isScam = confidence >= 0.65f

        binding.tvSender.text = sender
        binding.tvCategory.text = category.formatCategoryLabel()
        binding.tvConfidence.text = "$confPercent%"
        binding.tvConfidence.setTextColor(
            ContextCompat.getColor(this, when {
                confidence >= 0.80f -> R.color.risk_high
                confidence >= 0.65f -> R.color.risk_medium
                confidence >= 0.30f -> R.color.risk_low
                else -> R.color.risk_safe
            })
        )
        binding.tvVerdict.text = if (isScam)
            getString(R.string.verdict_scam) else getString(R.string.verdict_safe)
        binding.tvVerdict.setTextColor(
            ContextCompat.getColor(this, if (isScam) R.color.risk_high else R.color.risk_safe)
        )

        binding.btnBack.setOnClickListener { finish() }
    }
}
