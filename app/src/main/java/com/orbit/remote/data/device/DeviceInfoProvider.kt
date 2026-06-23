package com.orbit.remote.data.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.orbit.remote.domain.model.DeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun current(): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)

        return DeviceInfo(
            model = Build.MODEL ?: "Unknown",
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            androidVersion = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            batteryPercent = batteryPercent(),
            usedMemoryMb = totalMb - availMb,
            totalMemoryMb = totalMb,
            ipAddress = localIpAddress()
        )
    }

    fun displayName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * Stable, deterministic Device ID derived from the hardware ANDROID_ID. Unlike a
     * server-minted id stored in app data, this survives app reinstalls (same signing
     * key) and server restarts, so the user's saved device never "resets" on its own.
     * 9 digits, first digit non-zero.
     */
    fun stableDeviceId(): String {
        val h = (androidSeed().hashCode().toLong() and 0xFFFFFFFFL)
        val n = h % 900_000_000L + 100_000_000L
        return n.toString()
    }

    /** Stable, deterministic 6-digit connection code derived from the same seed. */
    fun stableCode(): String {
        val h = ((androidSeed() + "#orbit-code").hashCode().toLong() and 0xFFFFFFFFL)
        return (h % 1_000_000L).toString().padStart(6, '0')
    }

    private fun androidSeed(): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: (Build.FINGERPRINT ?: "orbit")

    private fun batteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
