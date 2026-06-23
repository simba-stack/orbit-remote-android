package com.orbit.remote.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Returns the FULL physical display size (including status/navigation bars), which
 * is what MediaProjection actually captures. Using resources.displayMetrics instead
 * excludes the nav bar on some OEM skins (e.g. Tecno/HiOS), which makes remote taps
 * land offset because the controller normalizes against the full captured frame.
 */
object DisplayMetricsCompat {
    fun realSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        @Suppress("DEPRECATION")
        val display: Display =
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
}
