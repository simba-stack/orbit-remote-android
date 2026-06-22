package com.orbit.remote.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orbit.remote.accessibility.OrbitAccessibilityService
import com.orbit.remote.service.ScreenCaptureService
import com.orbit.remote.system.PowerManagementHelper
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                viewModel.setMediaProjectionGranted(true)
                ContextCompat.startForegroundService(
                    this,
                    ScreenCaptureService.startIntent(this, result.resultCode, data)
                )
            } else {
                viewModel.setMediaProjectionGranted(false)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            AgentScreen(
                state = state,
                onEnableRemote = { startProjection() },
                onStop = { startService(ScreenCaptureService.stopIntent(this)) },
                onOpenAccessibility = { openAccessibilitySettings() },
                onIgnoreBattery = {
                    startActivity(PowerManagementHelper.requestIgnoreBatteryOptimizations(this))
                },
                onOpenAutostart = {
                    PowerManagementHelper.autostartSettingsIntent()?.let { runCatching { startActivity(it) } }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setAccessibilityEnabled(isAccessibilityEnabled())
        viewModel.setBatteryOptimizationIgnored(
            PowerManagementHelper.isIgnoringBatteryOptimizations(this)
        )
    }

    private fun startProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (OrbitAccessibilityService.isRunning()) return true
        val expected = ComponentNameString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun ComponentNameString(): String =
        "$packageName/${OrbitAccessibilityService::class.java.canonicalName}"
}
