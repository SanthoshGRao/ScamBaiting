package com.scamshield.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scamshield.app.R
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MessageInsightsActivity : AppCompatActivity() {
    @Inject lateinit var detectionCacheDao: DetectionCacheDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_insights)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.rvIntercepted)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = InsightsAdapter()
        recycler.adapter = adapter

        lifecycleScope.launch {
            adapter.submit(detectionCacheDao.getAll())
        }
    }
}

private class InsightsAdapter : RecyclerView.Adapter<InsightsAdapter.Holder>() {
    private var items: List<DetectionCacheEntity> = emptyList()

    fun submit(data: List<DetectionCacheEntity>) {
        items = data
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val sender: TextView = view.findViewById(R.id.tvSender)
        val message: TextView = view.findViewById(R.id.tvMessage)
        val analysis: TextView = view.findViewById(R.id.tvAiAnalysis)
        val timestamp: TextView = view.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_intercepted_message, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.sender.text = "Sender: ${item.messageHash.take(8)}"
        holder.message.text = item.matchedRules.replace(",", ", ")
        holder.analysis.text = "AI analysis: ${item.category} • ${(item.confidence * 100).toInt()}%"
        holder.timestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }

    override fun getItemCount(): Int = items.size
}
