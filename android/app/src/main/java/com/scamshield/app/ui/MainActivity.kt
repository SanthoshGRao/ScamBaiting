package com.scamshield.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.scamshield.app.R
import com.scamshield.app.databinding.ActivityMainBinding
import com.scamshield.app.service.NotificationListenerReviver
import com.scamshield.app.service.ProtectionKeepAliveService
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity — Navigation host with bottom navigation bar.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 101
        private const val REQUEST_SMS_PERMISSIONS = 102
        private const val PREFS_NAME = "scamshield_prefs"
        private const val PREF_MIUI_SURVIVAL_PROMPT_SHOWN = "miui_survival_prompt_shown"
    }

    private lateinit var binding: ActivityMainBinding
    private var activeFragmentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        requestSmsPermissionsIfNeeded()

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
        ProtectionKeepAliveService.start(this)
        promptForBackgroundSurvivalIfNeeded()
    }

    private fun promptForBackgroundSurvivalIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isXiaomiDevice = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("redmi", ignoreCase = true) ||
            Build.BRAND.equals("poco", ignoreCase = true)
        val ignoringBattery = isIgnoringBatteryOptimizations()

        if (!isXiaomiDevice && ignoringBattery) return
        if (prefs.getBoolean(PREF_MIUI_SURVIVAL_PROMPT_SHOWN, false)) return

        prefs.edit().putBoolean(PREF_MIUI_SURVIVAL_PROMPT_SHOWN, true).apply()

        AlertDialog.Builder(this)
            .setTitle("Keep ScamShield running")
            .setMessage(
                "This phone can stop ScamShield after closing the app. Allow unrestricted battery usage and, on Xiaomi/MIUI, enable AutoStart and lock ScamShield in Recents."
            )
            .setPositiveButton("Battery settings") { _, _ -> openBatteryOptimizationSettings() }
            .setNegativeButton("MIUI AutoStart") { _, _ -> openMiuiAutostartSettings() }
            .setNeutralButton("Later", null)
            .show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (startActivitySafely(requestIntent)) return
        }

        startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openMiuiAutostartSettings() {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
        if (intents.none { startActivitySafely(it) }) {
            Toast.makeText(this, "Open Security > Permissions > AutoStart for ScamShield", Toast.LENGTH_LONG).show()
        }
    }

    private fun startActivitySafely(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    private fun requestSmsPermissionsIfNeeded() {
        val missing = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) return
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_SMS_PERMISSIONS)
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
