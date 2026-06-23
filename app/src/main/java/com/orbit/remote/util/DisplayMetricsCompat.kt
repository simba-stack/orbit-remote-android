package com.orbit.remote.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * Returns the FULL physical display size (including status/navigation bars), which
 * is what MediaProjection actually captures. Using resources.displayMetrics instead
 * excludes the nav bar on some OEM skins (e.g. Tecno/HiOS), which makes remote taps
 * land offset because the controller normalizes against the full captured frame.
 *
 * Uses Display.getRealMetrics via DisplayManager — this works reliably from a
 * background Service on every API level (26..34+). WindowManager.currentWindowMetrics
 * is NOT used because it can return empty/invalid bounds when obtained from a
 * non-visual (Service) context on some devices.
 */
object DisplayMetricsCompat {
    fun realSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        runCatching {
            val display: Display =
                (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        // Defensive fallback if the real metrics came back empty.
        if (w <= 0 || h <= 0) {
            val dm = context.resources.displayMetrics
            w = dm.widthPixels
            h = dm.heightPixels
        }
        return w to h
    }
}
