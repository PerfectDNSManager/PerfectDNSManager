package app.perfectdnsmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import app.perfectdnsmanager.MainActivity
import app.perfectdnsmanager.R
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Moniteur du DNS privé (DoT).
 *
 * Le DoT passe par le Private DNS "strict" d'Android : SANS repli. Si le serveur
 * devient injoignable (roaming, port 853 bloqué, panne serveur), toute la
 * résolution DNS échoue → « plus d'internet », et le réglage persiste même après
 * désinstallation de l'app. Ce service :
 *   A. affiche une notification persistante avec un bouton « Désactiver » (1 tap
 *      pour rétablir internet sans refaire l'ADB) ;
 *   B. vérifie périodiquement que la résolution DNS fonctionne ; si elle échoue
 *      plusieurs fois, PRÉVIENT l'utilisateur puis désactive automatiquement le
 *      DNS privé pour rétablir internet.
 */
class DotMonitorService : Service() {

    companion object {
        const val ACTION_START = "app.perfectdnsmanager.START_DOT_MONITOR"
        /** Déclenché par le bouton « Désactiver » de la notification. */
        const val ACTION_DISABLE = "app.perfectdnsmanager.DISABLE_DOT"
        const val EXTRA_HOSTNAME = "dot_hostname"
        const val EXTRA_LABEL = "dot_label"

        private const val T = "DotMonitor"
        private const val CH_ID = "dot_monitor_channel"
        private const val NOTIF_ID = 1002
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val CHECK_TIMEOUT_S = 6L
        private const val MAX_FAILURES = 2 // ~2 min avant auto-repli

        // Domaines de contrôle (rotation pour limiter l'effet du cache DNS).
        private val PROBES = arrayOf(
            "connectivitycheck.gstatic.com",
            "www.google.com",
            "cloudflare.com",
            "example.com"
        )

        @Volatile var isRunning = false; private set
    }

    private var hostname: String = ""
    private var label: String = ""
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val probeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var monitoring = false
    private var failures = 0
    private var probeIndex = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                disableAndStop()
                return START_NOT_STICKY
            }
            else -> {
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                hostname = intent?.getStringExtra(EXTRA_HOSTNAME)
                    ?: prefs.getString("dot_hostname", "") ?: ""
                label = intent?.getStringExtra(EXTRA_LABEL)
                    ?: prefs.getString("dot_label", "") ?: ""
                prefs.edit().putString("dot_hostname", hostname).putString("dot_label", label).apply()
                startForeground(NOTIF_ID, buildNotif(warning = false))
                isRunning = true
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        failures = 0
        scheduleCheck()
    }

    private fun scheduleCheck() {
        handler.postDelayed({
            if (!monitoring) return@postDelayed
            val ok = probeDnsWithTimeout()
            if (!monitoring) return@postDelayed
            if (ok) {
                if (failures > 0) { // rétabli après un ou plusieurs échecs
                    failures = 0
                    notify(buildNotif(warning = false))
                }
            } else {
                failures++
                Log.w(T, "DNS probe failed ($failures/$MAX_FAILURES)")
                if (failures == 1) notify(buildNotif(warning = true)) // avertir dès le 1er échec
                if (failures >= MAX_FAILURES) { autoFallback(); return@postDelayed }
            }
            scheduleCheck()
        }, CHECK_INTERVAL_MS)
    }

    /** Résolution DNS (= via le serveur DoT en mode strict) avec timeout borné. */
    private fun probeDnsWithTimeout(): Boolean {
        val host = PROBES[probeIndex % PROBES.size]; probeIndex++
        return try {
            val f = probeExecutor.submit<Boolean> {
                try { InetAddress.getAllByName(host).isNotEmpty() } catch (_: Exception) { false }
            }
            f.get(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false // timeout ou erreur = DNS considéré indisponible
        }
    }

    /** B : le DNS ne répond plus → prévenir + désactiver pour rétablir internet. */
    private fun autoFallback() {
        monitoring = false
        Thread {
            val disabled = try { AdbDnsManager(this).disablePrivateDns() } catch (_: Exception) { false }
            Log.i(T, "Auto-fallback: private DNS disabled=$disabled")
            handler.post {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("dot_active", false).apply()
                // Notification informative (non-ongoing) qui explique l'auto-repli.
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildInfoNotif(getString(R.string.dot_autodisabled)))
                isRunning = false
                stopForegroundCompat(removeNotif = false) // garder la notif info
                stopSelf()
            }
        }.start()
    }

    /** A : l'utilisateur a tapé « Désactiver ». */
    private fun disableAndStop() {
        monitoring = false
        Thread {
            try { AdbDnsManager(this).disablePrivateDns() } catch (_: Exception) {}
            handler.post {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("dot_active", false).apply()
                isRunning = false
                stopForegroundCompat(removeNotif = true)
                stopSelf()
            }
        }.start()
    }

    private fun stopForegroundCompat(removeNotif: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotif) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(removeNotif)
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_ID, "DNS privé (DoT)", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun disableAction(): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, DotMonitorService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, getString(R.string.dot_disable_action), pi)
    }

    private fun contentPi(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotif(warning: Boolean): Notification {
        ensureChannel()
        val text = if (warning) getString(R.string.dot_warning_unreachable)
        else getString(R.string.dot_active_fmt, label.ifEmpty { redactHost(hostname) })
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPi())
            .addAction(disableAction())
            .setOngoing(true)
            .setPriority(if (warning) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildInfoNotif(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPi())
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    /** Masque le sous-domaine gauche (l'ID de compte NextDNS/ControlD). */
    private fun redactHost(h: String): String {
        val dot = h.indexOf('.')
        return if (dot > 0) "***" + h.substring(dot) else "***"
    }

    override fun onDestroy() {
        monitoring = false
        probeExecutor.shutdownNow()
        isRunning = false
        super.onDestroy()
    }
}
