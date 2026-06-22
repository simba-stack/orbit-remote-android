package com.orbit.remote.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.orbit.remote.R
import com.orbit.remote.ui.MainActivity

/**
 * Re-arms the agent after reboot or app update.
 *
 * Note: Android requires fresh user consent for MediaProjection after every reboot,
 * so screen sharing cannot silently resume. We post a notification that re-opens the
 * app with one tap to re-grant and reconnect. This is an OS limitation, documented
 * in the README.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            postReconnectNotification(context)
        }
    }

    private fun postReconnectNotification(context: Context) {
        val channelId = "orbit_boot"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Reconnect",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Tap to re-enable remote control after restart")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify(2002, notification)
    }
}
