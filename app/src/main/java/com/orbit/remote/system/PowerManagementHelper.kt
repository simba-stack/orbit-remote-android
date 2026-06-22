package com.orbit.remote.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for working around aggressive battery savers on OEM skins. Many vendors
 * (Xiaomi/Redmi/Poco, Oppo/Realme, Vivo, OnePlus, Samsung, Huawei) kill background
 * apps unless the user grants "autostart" / disables battery optimisation. We can
 * only *open* the relevant settings screen — the user must confirm.
 */
object PowerManagementHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Returns an intent to the vendor-specific autostart / protected-apps screen,
     * or null if the manufacturer is not recognised. Wrapped components are
     * best-effort and may differ across firmware versions.
     */
    fun autostartSettingsIntent(): Intent? {
        val component: ComponentName? = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo", "realme" -> ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            "vivo" -> ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "oneplus" -> ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            "huawei", "honor" -> ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            else -> null
        }
        return component?.let {
            Intent().apply {
                this.component = it
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
