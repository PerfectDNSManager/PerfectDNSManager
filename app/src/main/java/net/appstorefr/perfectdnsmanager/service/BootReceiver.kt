package net.appstorefr.perfectdnsmanager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import net.appstorefr.perfectdnsmanager.MainActivity
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import com.google.gson.Gson

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DELAY_MS = 8_000L
        private const val CHANNEL_ID = "boot_notification"
        private const val NOTIF_ID = 9001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in validActions) return

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val autoReconnect = prefs.getBoolean("auto_reconnect_dns", false)
        if (!autoReconnect) {
            Log.i(TAG, "Auto-reconnect DNS disabled, ignoring boot.")
            return
        }

        // ADB / Shizuku / Settings : le DNS privé persiste au reboot, pas besoin de reconnecter
        val lastMethod = prefs.getString("last_method", "VPN") ?: "VPN"
        if (lastMethod == "ADB" || lastMethod == "Shizuku" || lastMethod == "Settings") {
            Log.i(TAG, "Last method = $lastMethod, DNS persists across reboot. Nothing to do.")
            return
        }

        // Reconnecter le VPN — priorité : DNS par défaut > dernier DNS sélectionné
        val defaultProfileJson = prefs.getString("default_profile_json", null)
        val selectedProfileJson = prefs.getString("selected_profile_json", null)
        val profileJson = defaultProfileJson ?: selectedProfileJson
        if (profileJson == null) {
            Log.i(TAG, "No saved DNS profile, nothing to reconnect.")
            return
        }

        val profile = try {
            Gson().fromJson(profileJson, DnsProfile::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DNS profile: ${e.message}")
            return
        }

        Log.i(TAG, "Using ${if (defaultProfileJson != null) "default" else "last selected"} profile")
        Log.i(TAG, "Auto-reconnect VPN: ${profile.providerName} - ${profile.primary}")

        val pendingResult = goAsync()
        Thread {
            try {
                Thread.sleep(BOOT_DELAY_MS)

                // Check if VPN permission is granted (prepare() returns null if OK)
                val vpnPrepare = VpnService.prepare(context)
                if (vpnPrepare == null) {
                    // Permission OK → start VPN service directly
                    val vpnIntent = Intent(context, DnsVpnService::class.java).apply {
                        this.action = DnsVpnService.ACTION_START
                        putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
                        profile.secondary?.let { putExtra(DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                    val label = "VPN: ${profile.providerName}\n${profile.primary}"
                    prefs.edit()
                        .putBoolean("vpn_active", true)
                        .putString("vpn_label", label)
                        .apply()
                    Log.i(TAG, "VPN reconnection started successfully")
                } else {
                    // VPN permission needed → on ne peut PAS faire startActivity() depuis Android 10+
                    // → poster une notification avec PendingIntent vers MainActivity + AUTO_RECONNECT
                    Log.i(TAG, "VPN permission needed, posting notification with AUTO_RECONNECT")
                    postVpnPermissionNotification(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during VPN reconnection: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Boot DNS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notification for DNS auto-start at boot"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun postVpnPermissionNotification(context: Context) {
        ensureNotificationChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("AUTO_RECONNECT", true)
        }
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_open_app_vpn))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }
}
