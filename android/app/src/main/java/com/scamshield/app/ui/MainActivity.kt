package com.scamshield.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.scamshield.app.R
import com.scamshield.app.databinding.ActivityMainBinding
import com.scamshield.app.service.NotificationListenerReviver
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity — Navigation host with bottom navigation bar.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeFragmentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()

        if (savedInstanceState == null) {
            loadFragment(DashboardFragment(), "dashboard")
            binding.bottomNav.selectedItemId = R.id.nav_dashboard
        } else {
            val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            activeFragmentTag = current?.tag
            if (current == null) {
                loadFragment(DashboardFragment(), "dashboard")
                binding.bottomNav.selectedItemId = R.id.nav_dashboard
            } else {
                syncBottomNavToTag(activeFragmentTag)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationListenerReviver.pingFromUi(this)
    }

    // --- Navigation ---

    private fun syncBottomNavToTag(tag: String?) {
        val id = when (tag) {
            "dashboard" -> R.id.nav_dashboard
            "analytics" -> R.id.nav_analytics
            "simulator" -> R.id.nav_simulator
            "scammers" -> R.id.nav_scammers
            "settings" -> R.id.nav_settings
            else -> R.id.nav_dashboard
        }
        binding.bottomNav.selectedItemId = id
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment(), "dashboard")
                    true
                }
                R.id.nav_analytics -> {
                    loadFragment(AnalyticsFragment(), "analytics")
                    true
                }
                R.id.nav_simulator -> {
                    loadFragment(SimulatorFragment(), "simulator")
                    true
                }
                R.id.nav_scammers -> {
                    loadFragment(ScammersFragment(), "scammers")
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment(), "settings")
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        if (tag == activeFragmentTag) return
        activeFragmentTag = tag

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.fade_out,
                R.anim.fade_in,
                R.anim.slide_out_left
            )
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }
}
