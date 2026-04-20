package com.scamshield.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scamshield.app.R
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BaitingLogActivity : AppCompatActivity() {

    @Inject
    lateinit var baitingDao: BaitingDao
    private lateinit var adapter: BaitingMessageAdapter
    private var senderId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_baiting_log)

        senderId = intent.getStringExtra("sender") ?: return finish()
        
        findViewById<TextView>(R.id.tvSessionHeader).text = "Baiting: $senderId"
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rvMessages)
        adapter = BaitingMessageAdapter()
        rv.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Start at the bottom
        }
        rv.adapter = adapter

        loadMessages()
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            val session = baitingDao.getSession(senderId)
            findViewById<TextView>(R.id.tvPersonaGoal).text =
                "Persona: ${session?.persona ?: "unknown"}  •  Goal: Wasting scammer time"
            val msgs = baitingDao.getMessagesForSender(senderId)
            adapter.submitList(msgs)
            findViewById<RecyclerView>(R.id.rvMessages).scrollToPosition(msgs.size - 1)
        }
    }
}

class BaitingMessageAdapter : RecyclerView.Adapter<BaitingMessageAdapter.ViewHolder>() {

    private var messages = listOf<BaitingMessageEntity>()

    fun submitList(newMessages: List<BaitingMessageEntity>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llScammer = view.findViewById<View>(R.id.llScammerMessage)
        val tvScammerText = view.findViewById<TextView>(R.id.tvScammerText)
        val llAi = view.findViewById<View>(R.id.llAiMessage)
        val tvAiText = view.findViewById<TextView>(R.id.tvAiText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        if (msg.role == "user") {
            // Scammer message
            holder.llScammer.visibility = View.VISIBLE
            holder.llAi.visibility = View.GONE
            holder.tvScammerText.text = msg.content
        } else {
            // AI message
            holder.llScammer.visibility = View.GONE
            holder.llAi.visibility = View.VISIBLE
            holder.tvAiText.text = msg.content
        }
    }

    override fun getItemCount() = messages.size
}
