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
import app.perfectdnsmanager.util.PrivateDnsGuard
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
        private const val CH_ID = "dot_monitor_channel"        // persistante, IMPORTANCE_LOW
        private const val CH_ID_ALERT = "dot_alert_channel"    // alertes, IMPORTANCE_HIGH (heads-up)
        private const val NOTIF_ID = 1002
        private const val NOTIF_ID_ALERT = 1003
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
    // Handler sur un thread de FOND (pas le main looper) : le health-check bloque
    // jusqu'à CHECK_TIMEOUT_S — sur le main thread ça déclencherait un ANR (>5s).
    private val bgThread = android.os.HandlerThread("DotMonitor").apply { start() }
    private val handler by lazy { Handler(bgThread.looper) }
    // Pool (pas mono-thread) : InetAddress.getAllByName n'est PAS interruptible ; avec
    // un seul thread, une résolution coincée empêcherait les sondes suivantes de
    // DÉMARRER → f.get timeout sur une tâche jamais lancée → faux échecs → auto-repli
    // à tort. Un pool laisse chaque sonde démarrer et refléter le vrai état du DNS.
    private val probeExecutor = Executors.newFixedThreadPool(4, { r ->
        Thread(r, "DotProbe").apply { isDaemon = true }
    })
    @Volatile private var monitoring = false
    /** Garantit qu'une seule séquence disable/stop s'exécute (anti double-repli). */
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var failures = 0
    @Volatile private var probeIndex = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                if (hostname.isEmpty()) {
                    val saved = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    hostname = saved.getString("dot_hostname", "") ?: ""
                    label = saved.getString("dot_label", "") ?: ""
                }
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
            if (!PrivateDnsGuard.isStrictActive(this) || PrivateDnsGuard.specifier(this) != hostname) {
                // DNS privé coupé ou changé ailleurs (Réglages Android…) : l'état
                // « DoT actif » est périmé, y compris après un échec de désactivation.
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("dot_active", false).apply()
                monitoring = false; stopSelf(); return@postDelayed
            }
            // A disconnected network does not establish that the chosen DNS has failed.
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            @Suppress("DEPRECATION")
            val connected = cm.activeNetworkInfo?.isConnected == true
            if (!connected) { failures = 0; scheduleCheck(); return@postDelayed }
            val ok = probeDnsWithTimeout()
            if (!monitoring) return@postDelayed
            if (ok) {
                if (failures > 0) { // rétabli après un ou plusieurs échecs
                    failures = 0
                    notify(buildNotif(warning = false))
                    // Annuler le heads-up « injoignable » périmé.
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_ALERT)
                }
            } else {
                failures++
                Log.w(T, "DNS probe failed ($failures/$MAX_FAILURES)")
                if (failures == 1) { // avertir dès le 1er échec : persistante MAJ + heads-up
                    notify(buildNotif(warning = true))
                    postAlert(getString(R.string.dot_warning_unreachable))
                }
                if (failures >= MAX_FAILURES) { autoFallback(); return@postDelayed }
            }
            scheduleCheck()
        }, CHECK_INTERVAL_MS)
    }

    /** Résolution DNS (= via le serveur DoT en mode strict) avec timeout borné. */
    private fun probeDnsWithTimeout(): Boolean {
        val host = PROBES[probeIndex % PROBES.size]; probeIndex++
        val f = probeExecutor.submit<Boolean> {
            try { InetAddress.getAllByName(host).isNotEmpty() } catch (_: Exception) { false }
        }
        return try {
            f.get(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (_: Exception) {
            f.cancel(true) // sinon la tâche bloquée occupe le thread unique du pool
            false          // timeout ou erreur = DNS considéré indisponible
        }
    }

    /** B : le DNS ne répond plus → prévenir + désactiver pour rétablir internet. */
    private fun autoFallback() {
        monitoring = false
        if (!stopping.compareAndSet(false, true)) return // déjà en cours (ex. tap "Désactiver")
        Thread {
            val freed = try { if (PrivateDnsGuard.specifier(this) != hostname) true else PrivateDnsGuard.tryDisable(this) } catch (_: Exception) { false }
            Log.i(T, "Auto-fallback: private DNS freed=$freed")
            if (freed) {
                // Tout fait ICI (pas via handler) : si bgThread est déjà quitté, un post
                // serait silencieusement perdu → `dot_active` resterait true (état zombie).
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("dot_active", false).apply()
                isRunning = false
                stopForegroundCompat(removeNotif = true) // retirer la persistante…
                postAlert(getString(R.string.dot_autodisabled)) // …et heads-up d'info (channel HIGH)
                stopSelf()
            } else {
                onDisableFailed()
            }
        }.start()
    }

    /** A : l'utilisateur a tapé « Désactiver ». */
    private fun disableAndStop() {
        monitoring = false
        if (!stopping.compareAndSet(false, true)) return // déjà en cours (ex. auto-repli)
        Thread {
            val freed = try { if (PrivateDnsGuard.specifier(this) != hostname) true else PrivateDnsGuard.tryDisable(this) } catch (_: Exception) { false }
            if (freed) {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("dot_active", false).apply()
                isRunning = false
                stopForegroundCompat(removeNotif = true)
                stopSelf()
            } else {
                onDisableFailed()
            }
        }.start()
    }

    /**
     * On n'a PAS pu couper le DNS privé (débogage ADB désactivé depuis
     * l'activation, WRITE_SECURE_SETTINGS révoquée, réglage posé par la box…).
     *
     * Surtout ne pas disparaître en silence : l'utilisateur croirait son
     * internet rétabli alors que le mode strict tourne toujours. On garde la
     * notification persistante, on bascule son texte sur l'état « verrouillé »
     * et on ajoute un raccourci vers l'écran système, seul endroit où le
     * réglage peut encore être coupé. `stopping` est relâché pour qu'un
     * nouvel appui sur « Désactiver » puisse retenter (si l'ADB revient).
     */
    private fun onDisableFailed() {
        Log.w(T, "Désactivation impossible : DNS privé verrouillé au niveau système")
        stopping.set(false)
        // Le health-check n'est PAS relancé : il ne pourrait que ré-échouer en
        // boucle et re-notifier toutes les 2 min sans rien pouvoir corriger.
        notify(buildNotif(warning = true, locked = true))
        postAlert(getString(R.string.dot_disable_failed), withSettings = true)
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
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "DNS privé (DoT)", NotificationManager.IMPORTANCE_LOW)
            )
            // Channel séparé HIGH : sinon l'importance LOW du channel écrase la
            // PRIORITY_HIGH → l'alerte « DNS injoignable » ne ferait ni heads-up ni son.
            nm.createNotificationChannel(
                NotificationChannel(CH_ID_ALERT, "Alerte DNS privé", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /** Poste une alerte (heads-up) sur le channel HIGH, en plus de la notif persistante. */
    private fun postAlert(text: String, withSettings: Boolean = false) {
        ensureChannel()
        val b = NotificationCompat.Builder(this, CH_ID_ALERT)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPi())
            .addAction(disableAction())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (withSettings) b.addAction(settingsAction())
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_ALERT, b.build())
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
        this, 0, Intent(this, app.perfectdnsmanager.NotificationActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Raccourci vers l'écran système « DNS privé ». On passe par MainActivity
     * (et non par un Intent Settings direct) parce que l'action
     * `PRIVATE_DNS_SETTINGS` est absente de certaines ROMs de box TV : le
     * PendingIntent serait alors mort au tap, alors que MainActivity peut
     * dérouler la cascade de repli de PrivateDnsGuard.openSettings().
     */
    private fun settingsAction(): NotificationCompat.Action {
        val i = Intent(this, app.perfectdnsmanager.NotificationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PRIVATE_DNS_SETTINGS, true)
        val pi = PendingIntent.getActivity(
            this, 2, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, getString(R.string.private_dns_open_settings), pi)
    }

    private fun buildNotif(warning: Boolean, locked: Boolean = false): Notification {
        ensureChannel()
        val text = when {
            locked -> getString(R.string.dot_locked_notif)
            warning -> getString(R.string.dot_warning_unreachable)
            else -> getString(R.string.dot_active_fmt, label.ifEmpty { redactHost(hostname) })
        }
        val b = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPi())
            .addAction(disableAction())
            .setOngoing(true)
            .setPriority(if (warning) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        if (locked) b.addAction(settingsAction())
        return b.build()
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
        try { bgThread.quitSafely() } catch (_: Exception) {}
        isRunning = false
        super.onDestroy()
    }
}
