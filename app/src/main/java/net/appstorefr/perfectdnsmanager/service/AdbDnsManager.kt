package net.appstorefr.perfectdnsmanager.service

import net.appstorefr.perfectdnsmanager.R

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import net.appstorefr.perfectdnsmanager.adblib.AndroidBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbDnsManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbDnsManager"
        private const val KEY_DNS_MODE = "private_dns_mode"
        private const val KEY_DNS_SPECIFIER = "private_dns_specifier"
        private const val ADB_HOST = "localhost"
        private val ADB_PORTS = listOf(5555, 5556, 5557)
        private const val CONN_TIMEOUT = 5000
        private const val PREF_PERMISSION_GRANTED = "adb_permission_self_granted"
        private const val PREF_LAST_ADB_PORT = "last_adb_port"
        private const val PUBLIC_KEY_NAME = "public.key"
        private const val PRIVATE_KEY_NAME = "private.key"

        // Validation stricte du hostname avant toute commande shell (sh -c / ADB).
        // Format RFC 1035 : 1..253 chars, segments alnum/hyphen séparés par points.
        // Bloque les métachars shell (;, |, $, `, \, espaces, etc.).
        private val HOSTNAME_RE = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")

        fun isValidHostname(h: String): Boolean =
            h.isNotBlank() && h.length <= 253 && HOSTNAME_RE.matches(h)
    }

    // Dernier message d'erreur pour feedback utilisateur
    var lastError: String = ""
        private set

    // Dernière méthode qui a fonctionné
    var lastMethod: String = ""
        private set

    // ─── API publiques ────────────────────────────────────────────────────────
    //
    // Stratégie d'activation (en cascade) :
    //   1. Settings.Global directe — si WRITE_SECURE_SETTINGS déjà accordée
    //      (perm persiste à vie tant que l'app n'est pas désinstallée)
    //   2. ADB TCP localhost — Android TV (port 5555 ouvert nativement),
    //      auto-grant via cgutman/adblib, puis Settings API prend le relais
    //   3. (UI dialog) Pairing Wireless Debugging Android 11+ — phones,
    //      via AdbPairingManager (libadb.so vendored Shizuku) → cf MainActivity
    //
    // L'ancienne méthode "Shizuku externe (app moe.shizuku.privileged.api)"
    // a été retirée — son rôle (no-PC ADB pairing) est maintenant assuré
    // par la lib vendored, sans dépendance externe.

    fun enablePrivateDns(hostname: String): Boolean {
        Log.i(TAG, "=== ACTIVATION DNS: $hostname ===")
        lastError = ""
        lastMethod = ""

        // Durcissement : refuser tout hostname non-RFC1035 avant shell/ADB.
        // Empêche l'injection de commande via métachars (;, `, $, |, \n…).
        if (!isValidHostname(hostname)) {
            lastError = context.getString(R.string.adb_err_invalid_hostname_fmt, hostname)
            Log.w(TAG, lastError)
            return false
        }

        // Méthode 1 : Settings.Global directe (si permission déjà accordée)
        if (trySettingsEnable(hostname)) {
            lastMethod = "Settings"
            return true
        }

        // Méthode 2 : ADB TCP localhost (TV boxes, appareils avec ADB réseau)
        val adbResult = runCommandsViaAdb(listOf(
            "settings put global $KEY_DNS_MODE hostname",
            "settings put global $KEY_DNS_SPECIFIER $hostname"
        ))
        if (adbResult) lastMethod = "ADB"
        return adbResult
    }

    fun disablePrivateDns(): Boolean {
        Log.i(TAG, "=== DÉSACTIVATION DNS ===")
        lastError = ""
        lastMethod = ""

        // Méthode 1 : Settings.Global directe
        if (trySettingsDisable()) {
            lastMethod = "Settings"
            return true
        }

        // Méthode 2 : ADB TCP localhost
        val adbResult = runCommandsViaAdb(listOf(
            "settings put global $KEY_DNS_MODE off",
            "settings delete global $KEY_DNS_SPECIFIER"
        ))
        if (adbResult) lastMethod = "ADB"
        return adbResult
    }

    /** Réinitialiser les clés ADB (utile si la connexion est refusée) */
    fun resetAdbKeys() {
        val dir = context.filesDir
        File(dir, PUBLIC_KEY_NAME).delete()
        File(dir, PRIVATE_KEY_NAME).delete()
        context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
            .edit().remove(PREF_PERMISSION_GRANTED).apply()
        Log.i(TAG, "ADB keys and permission reset")
    }

    // ─── Vérification permission ─────────────────────────────────────────────

    /** Vérifie si WRITE_SECURE_SETTINGS est déjà accordée (sans réseau) */
    fun isPermissionGranted(): Boolean {
        return try {
            val result = context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
            result == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    // ─── Self-grant ADB (API publique pour UI) ──────────────────────────────

    interface SelfGrantCallback {
        fun onProgress(step: String)
        fun onSuccess()
        fun onError(error: String)
    }

    /**
     * Tente de se connecter en ADB localhost et de s'auto-accorder WRITE_SECURE_SETTINGS.
     * Appeler depuis un Thread dédié (cette méthode est bloquante).
     * @param callback callback sur le thread appelant (NON sur le UI thread)
     */
    fun selfGrantPermission(callback: SelfGrantCallback) {
        Log.i(TAG, "=== SELF-GRANT PERMISSION ===")

        // Déjà accordée ?
        if (isPermissionGranted()) {
            callback.onSuccess()
            return
        }

        callback.onProgress("crypto")

        val crypto = readOrCreateCrypto()
        if (crypto == null) {
            callback.onError(context.getString(R.string.adb_err_key_creation_failed))
            return
        }

        callback.onProgress("connecting")

        val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
        val lastPort = prefs.getInt(PREF_LAST_ADB_PORT, 5555)
        val portsToTry = listOf(lastPort) + ADB_PORTS.filter { it != lastPort }

        var socket: Socket? = null
        var connection: AdbConnection? = null

        try {
            var connected = false
            for (port in portsToTry) {
                try {
                    Log.i(TAG, "Self-grant: tentative $ADB_HOST:$port...")
                    callback.onProgress("port:$port")
                    socket = Socket()
                    socket.soTimeout = CONN_TIMEOUT
                    socket.connect(InetSocketAddress(ADB_HOST, port), CONN_TIMEOUT)

                    connection = AdbConnection.create(socket, crypto)
                    connection.connect()
                    Log.i(TAG, "Self-grant: connected on port $port")
                    prefs.edit().putInt(PREF_LAST_ADB_PORT, port).apply()
                    connected = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Self-grant port $port: ${e.message}")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    connection = null
                }
            }

            if (!connected || connection == null) {
                callback.onError("ADB_NOT_REACHABLE")
                return
            }

            callback.onProgress("granting")

            // Exécuter pm grant
            val grantResult = execShellCommand(
                connection,
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
            Log.i(TAG, "Self-grant result: '$grantResult'")

            if (grantResult.contains("Exception", ignoreCase = true) ||
                grantResult.contains("error", ignoreCase = true) ||
                grantResult.contains("denied", ignoreCase = true)) {
                callback.onError("GRANT_FAILED:$grantResult")
                return
            }

            // Vérifier que la permission est bien accordée
            Thread.sleep(500)
            if (isPermissionGranted()) {
                prefs.edit().putBoolean(PREF_PERMISSION_GRANTED, true).apply()
                Log.i(TAG, "Self-grant: permission granted successfully")
                callback.onSuccess()
            } else {
                // La commande n'a pas retourné d'erreur mais la permission n'est pas là
                // Essayer une seconde fois
                Log.w(TAG, "Self-grant: pm grant OK mais permission pas encore effective, retry...")
                val retryResult = execShellCommand(
                    connection,
                    "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                )
                Thread.sleep(500)
                if (isPermissionGranted()) {
                    prefs.edit().putBoolean(PREF_PERMISSION_GRANTED, true).apply()
                    callback.onSuccess()
                } else {
                    callback.onError("GRANT_NOT_EFFECTIVE")
                }
            }

        } catch (e: IOException) {
            Log.e(TAG, "Self-grant IOException: ${e.message}")
            callback.onError("IO:${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Self-grant error: ${e.message}")
            callback.onError("${e.javaClass.simpleName}:${e.message}")
        } finally {
            try { connection?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ─── Méthode 1 : Settings.Global ─────────────────────────────────────────

    private fun trySettingsEnable(hostname: String): Boolean {
        return try {
            Settings.Global.putString(context.contentResolver, KEY_DNS_SPECIFIER, hostname)
            Settings.Global.putString(context.contentResolver, KEY_DNS_MODE, "hostname")
            val mode = getCurrentPrivateDnsMode()
            val ok = mode == "hostname"
            Log.i(TAG, "Settings API enable -> mode=$mode ok=$ok")
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings API: permission denied")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Settings API: ${e.message}")
            false
        }
    }

    private fun trySettingsDisable(): Boolean {
        return try {
            Settings.Global.putString(context.contentResolver, KEY_DNS_MODE, "off")
            Settings.Global.putString(context.contentResolver, KEY_DNS_SPECIFIER, "")
            val mode = getCurrentPrivateDnsMode()
            val ok = mode == "off" || mode.isNullOrEmpty()
            Log.i(TAG, "Settings API disable -> mode=$mode ok=$ok")
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings API: permission denied")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Settings API: ${e.message}")
            false
        }
    }

    // ─── Méthode 3 : ADB local ────────────────────────────────────────────────

    private fun runCommandsViaAdb(commands: List<String>): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        Thread {
            var socket: Socket? = null
            var connection: AdbConnection? = null
            var shellStream: AdbStream? = null

            try {
                val crypto = readOrCreateCrypto()
                if (crypto == null) {
                    lastError = context.getString(R.string.adb_err_key_load_failed)
                    Log.e(TAG, lastError)
                    return@Thread
                }

                // Essayer le dernier port qui a fonctionné d'abord
                val prefs = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                val lastPort = prefs.getInt(PREF_LAST_ADB_PORT, 5555)
                val portsToTry = listOf(lastPort) + ADB_PORTS.filter { it != lastPort }

                var connected = false
                for (port in portsToTry) {
                    try {
                        Log.i(TAG, "Tentative ADB $ADB_HOST:$port (timeout ${CONN_TIMEOUT}ms)...")
                        socket = Socket()
                        socket.soTimeout = CONN_TIMEOUT
                        socket.connect(InetSocketAddress(ADB_HOST, port), CONN_TIMEOUT)

                        connection = AdbConnection.create(socket, crypto)
                        connection.connect()
                        Log.i(TAG, "ADB connection established on port $port")
                        prefs.edit().putInt(PREF_LAST_ADB_PORT, port).apply()
                        connected = true
                        break
                    } catch (e: IOException) {
                        Log.w(TAG, "Port $port: ${e.message}")
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                        connection = null
                    }
                }

                if (!connected || connection == null) {
                    lastError = context.getString(
                        R.string.adb_err_check_setup_fmt,
                        portsToTry.joinToString()
                    )
                    Log.e(TAG, lastError)
                    return@Thread
                }

                // Auto-grant WRITE_SECURE_SETTINGS si pas (ou plus) accordée.
                // Le flag SharedPref survit à une réinstallation alors que la
                // permission elle est révoquée à chaque install — donc on
                // checke la vraie permission, pas le flag stocké.
                if (!isPermissionGranted()) {
                    Log.i(TAG, "Auto-grant WRITE_SECURE_SETTINGS...")
                    val grantResult = execShellCommand(
                        connection,
                        "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                    )
                    Log.i(TAG, "Grant result: '$grantResult'")

                    // Vérifier si le grant a échoué
                    if (grantResult.contains("Exception", ignoreCase = true) ||
                        grantResult.contains("error", ignoreCase = true) ||
                        grantResult.contains("denied", ignoreCase = true)) {
                        lastError = context.getString(
                            R.string.adb_err_permission_denied_fmt,
                            grantResult
                        ) + "\n\nadb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                        Log.e(TAG, lastError)
                        return@Thread
                    }

                    prefs.edit().putBoolean(PREF_PERMISSION_GRANTED, true).apply()

                    // Retry Settings API maintenant qu'on a la permission
                    val retryOk = commands.any { it.contains("off") || it.contains("delete") }
                    val apiOk = if (retryOk) trySettingsDisable() else {
                        val hostname = commands.lastOrNull()?.substringAfterLast(" ") ?: ""
                        if (hostname.isNotEmpty()) trySettingsEnable(hostname) else false
                    }
                    if (apiOk) {
                        Log.i(TAG, "Settings API succeeded after grant")
                        success = true
                        return@Thread
                    }
                }

                // Ouvrir un shell et envoyer chaque commande
                shellStream = connection.open("shell:")
                for (cmd in commands) {
                    Log.i(TAG, "ADB shell: $cmd")
                    shellStream.write("$cmd\n".toByteArray(Charsets.UTF_8))
                    Thread.sleep(300)
                }
                success = true
                lastError = ""
                Log.i(TAG, "ADB commands sent successfully")

            } catch (e: IOException) {
                lastError = context.getString(R.string.adb_err_connection_fmt, e.message ?: "")
                Log.e(TAG, "ADB IOException: ${e.message}")
                context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)
                    .edit().remove(PREF_PERMISSION_GRANTED).apply()
            } catch (e: InterruptedException) {
                lastError = context.getString(R.string.adb_err_interrupted)
                Log.e(TAG, "ADB InterruptedException: ${e.message}")
            } catch (e: Exception) {
                lastError = context.getString(R.string.adb_err_generic_fmt, e.javaClass.simpleName, e.message ?: "")
                Log.e(TAG, lastError)
            } finally {
                try { shellStream?.close() } catch (_: Exception) {}
                try { connection?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                latch.countDown()
            }
        }.start()

        latch.await(20, TimeUnit.SECONDS)
        return success
    }

    private fun execShellCommand(connection: AdbConnection, cmd: String): String {
        var stream: AdbStream? = null
        return try {
            stream = connection.open("shell:$cmd")
            val result = StringBuilder()
            val deadline = System.currentTimeMillis() + 3000
            while (!stream.isClosed && System.currentTimeMillis() < deadline) {
                try {
                    val data = stream.read()
                    if (data != null) result.append(String(data))
                } catch (_: Exception) { break }
            }
            result.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "execShellCommand '$cmd': ${e.message}")
            ""
        } finally {
            try { stream?.close() } catch (_: Exception) {}
        }
    }

    // ─── Crypto ADB ──────────────────────────────────────────────────────────

    private fun readOrCreateCrypto(): AdbCrypto? {
        val dir = context.filesDir
        val pubFile = File(dir, PUBLIC_KEY_NAME)
        val privFile = File(dir, PRIVATE_KEY_NAME)
        return try {
            if (pubFile.exists() && privFile.exists()) {
                Log.i(TAG, "Loading existing ADB keys")
                AdbCrypto.loadAdbKeyPair(AndroidBase64(), privFile, pubFile)
            } else {
                Log.i(TAG, "Generating new ADB key pair...")
                val crypto = AdbCrypto.generateAdbKeyPair(AndroidBase64())
                crypto.saveAdbKeyPair(privFile, pubFile)
                Log.i(TAG, "ADB keys generated and saved")
                crypto
            }
        } catch (e: Exception) {
            Log.e(TAG, "Crypto error: ${e.message}")
            null
        }
    }

    // ─── Lecture état DNS ─────────────────────────────────────────────────────

    fun getCurrentPrivateDnsMode(): String? =
        try { Settings.Global.getString(context.contentResolver, KEY_DNS_MODE) }
        catch (_: Exception) { null }

    fun getCurrentPrivateDnsHost(): String =
        try { Settings.Global.getString(context.contentResolver, KEY_DNS_SPECIFIER) ?: "" }
        catch (_: Exception) { "" }

    fun getFullDnsReport(): String {
        val mode = getCurrentPrivateDnsMode() ?: "inconnu"
        val host = getCurrentPrivateDnsHost()
        return "Mode: $mode\nServeur: ${host.ifEmpty { "(aucun)" }}"
    }
}
