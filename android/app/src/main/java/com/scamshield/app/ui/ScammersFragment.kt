package com.scamshield.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.scamshield.app.R
import com.scamshield.app.data.ScammerRepository
import com.scamshield.app.databinding.FragmentScammersBinding
import com.scamshield.app.service.AlertNotificationManager
import com.scamshield.app.service.BaitingManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScammersFragment : Fragment() {

    companion object {
        private const val TAG = "ScammersFragment"
    }

    private var _binding: FragmentScammersBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var scammerRepository: ScammerRepository
    @Inject lateinit var baitingManager: BaitingManager
    @Inject lateinit var alertNotificationManager: AlertNotificationManager

    private lateinit var adapter: ScammerListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScammersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ScammerListAdapter(
            onAiToggle = { scammer, enabled ->
                lifecycleScope.launch {
                    try {
                        scammerRepository.setAiEnabled(scammer.phoneNumber, enabled)
                        Log.i(TAG, "AI ${if (enabled) "enabled" else "disabled"} for ${scammer.phoneNumber}")
                        if (enabled) {
                            alertNotificationManager.dismissPendingScamAlertForSender(scammer.phoneNumber)
                            Log.i(TAG, "Starting immediate baiting session for ${scammer.phoneNumber} from toggle")
                            baitingManager.forceStartOrResumeSession(scammer.phoneNumber, scammer.lastMessage)
                        } else {
                            baitingManager.stopBaitingSession(scammer.phoneNumber)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to toggle AI for ${scammer.phoneNumber}", e)
                    }
                }
            },
            onShowChat = { scammer ->
                // Sync history first (non-blocking — failure is OK)
                lifecycleScope.launch {
                    try {
                        scammerRepository.syncHistory(scammer.phoneNumber)
                    } catch (e: Exception) {
                        Log.w(TAG, "History sync failed for ${scammer.phoneNumber} (non-fatal)", e)
                    }

                    // Open baiting log to show chat
                    startActivity(
                        Intent(requireContext(), BaitingLogActivity::class.java).apply {
                            putExtra("sender", scammer.phoneNumber)
                        }
                    )
                }
            }
        )
        binding.rvScammers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScammers.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadScammers()
    }

    private fun loadScammers() {
        lifecycleScope.launch {
            try {
                val data = scammerRepository.getAllScammers()
                    .filterNot { isSelfPlaceholderEntry(it.phoneNumber) }
                Log.i(TAG, "Loaded ${data.size} scammers from DB")

                binding.tvScammerCount.text = getString(R.string.scammers_tracked_count, data.size)
                val prefs = requireContext().getSharedPreferences("scamshield_prefs", 0)
                val clearTime = prefs.getLong("last_cache_clear_time", 0L)
                adapter.submit(data, clearTime)
                val empty = data.isEmpty()
                binding.tvEmptyScammers.visibility = if (empty) View.VISIBLE else View.GONE
                binding.rvScammers.visibility = if (empty) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load scammers", e)
                binding.tvEmptyScammers.visibility = View.VISIBLE
                binding.tvEmptyScammers.text = "Error loading scammers: ${e.message}"
                binding.rvScammers.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Hide mistaken "You" / self rows saved before outgoing-message filtering. */
    private fun isSelfPlaceholderEntry(phoneOrKey: String): Boolean {
        val c = phoneOrKey.trim().lowercase()
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return c == "you" || c == "me" || c == "u"
    }
}
