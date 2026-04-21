package net.appstorefr.perfectdnsmanager.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.appstorefr.perfectdnsmanager.BuildConfig
import net.appstorefr.perfectdnsmanager.IShellService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private var shellService: IShellService? = null
    private var serviceConnected = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            serviceConnected = true
            Log.i(TAG, "ShellService connecté via Shizuku")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceConnected = false
            Log.i(TAG, "ShellService déconnecté")
        }
    }

    // ─── Vérifications d'état ───────────────────────────────────────────────

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isShizukuPermissionGranted(): Boolean {
        return try {
            if (!isShizukuRunning()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun isShizukuAvailable(): Boolean {
        return isShizukuInstalled() && isShizukuRunning() && isShizukuPermissionGranted()
    }

    // ─── Permission ─────────────────────────────────────────────────────────

    fun requestPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur demande permission Shizuku: ${e.message}")
        }
    }

    // ─── Bind/Unbind user service ───────────────────────────────────────────

    private fun bindShellService(): Boolean {
        if (serviceConnected && shellService != null) return true
        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, ShellService::class.java)
            )
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.BUILD_NUMBER)

            Shizuku.bindUserService(args, serviceConnection)
            // Attendre la connexion (max 5s)
            val start = System.currentTimeMillis()
            while (!serviceConnected && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(100)
            }
            serviceConnected
        } catch (e: Exception) {
            Log.e(TAG, "Erreur bind ShellService: ${e.message}")
            false
        }
    }

    private fun unbindShellService() {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, ShellService::class.java)
            )
            Shizuku.unbindUserService(args, serviceConnection, true)
        } catch (_: Exception) {}
        shellService = null
        serviceConnected = false
    }

    // ─── Exécution de commande ──────────────────────────────────────────────

    private fun executeCommand(command: String): String {
        if (!bindShellService()) {
            throw RuntimeException("Impossible de se connecter au ShellService Shizuku")
        }
        return shellService?.exec(command) ?: throw RuntimeException("ShellService null")
    }

    // ─── Commandes DNS ──────────────────────────────────────────────────────

    fun enablePrivateDns(hostname: String): Boolean {
        Log.i(TAG, "=== Shizuku: ACTIVATION DNS: $hostname ===")
        try {
            // 1. Accorder WRITE_SECURE_SETTINGS (persiste, Method 1 marchera ensuite)
            val grantResult = executeCommand(
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
            Log.i(TAG, "Grant result: $grantResult")

            // 2. Configurer le DNS privé
            val modeResult = executeCommand("settings put global private_dns_mode hostname")
            Log.i(TAG, "Mode result: $modeResult")

            val specResult = executeCommand("settings put global private_dns_specifier $hostname")
            Log.i(TAG, "Specifier result: $specResult")

            // Vérifier le résultat
            val verifyMode = executeCommand("settings get global private_dns_mode")
            val ok = verifyMode.trim() == "hostname"
            Log.i(TAG, "Shizuku enable -> mode=$verifyMode ok=$ok")
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "Erreur Shizuku enablePrivateDns: ${e.message}")
            return false
        }
    }

    fun disablePrivateDns(): Boolean {
        Log.i(TAG, "=== Shizuku: DESACTIVATION DNS ===")
        try {
            executeCommand("settings put global private_dns_mode off")
            executeCommand("settings delete global private_dns_specifier")

            val verifyMode = executeCommand("settings get global private_dns_mode")
            val ok = verifyMode.trim() == "off" || verifyMode.trim().isEmpty()
            Log.i(TAG, "Shizuku disable -> mode=$verifyMode ok=$ok")
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "Erreur Shizuku disablePrivateDns: ${e.message}")
            return false
        }
    }

    // ─── Statut pour l'UI ───────────────────────────────────────────────────

    fun getStatusString(): String {
        return when {
            !isShizukuInstalled() -> "not_installed"
            !isShizukuRunning() -> "not_running"
            !isShizukuPermissionGranted() -> "no_permission"
            else -> "ready"
        }
    }

    // ─── Listeners Shizuku ──────────────────────────────────────────────────

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun isAndroid11OrAbove(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun cleanup() {
        unbindShellService()
    }
}
