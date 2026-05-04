package net.appstorefr.perfectdnsmanager.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bootstrap WRITE_SECURE_SETTINGS sur Android 11+ phone via le flow
 * Wireless Debugging natif (no-PC), sans dépendance externe (Shizuku).
 *
 * Flow :
 *   1. user active "Débogage sans fil" puis "Coupler avec un code"
 *   2. téléphone affiche un code 6 chiffres + IP:port
 *   3. on découvre le port pairing via mDNS (_adb-tls-pairing._tcp)
 *   4. TLS-PSK handshake avec le code → SPAKE2 → échange RSA pubkey
 *   5. on découvre le port connect via mDNS (_adb-tls-connect._tcp)
 *   6. ADB connect en TLS → pm grant WRITE_SECURE_SETTINGS
 *   7. la perm persiste à vie (jusqu'à désinstall) — plus jamais besoin d'ADB
 *
 * Code natif : libadb.so (vendored depuis Shizuku, voir jniLibs/).
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbPairingManager"
        private const val MDNS_TIMEOUT_MS = 30_000L  // 30s pour découvrir port
        private const val ADB_CONNECT_TIMEOUT_MS = 10_000L
    }

    interface Callback {
        fun onProgress(step: String)
        fun onSuccess()
        fun onError(error: String)
    }

    private val keyStore = PreferenceAdbKeyStore(
        context.getSharedPreferences("adb_pairing_key", Context.MODE_PRIVATE)
    )
    private val key = AdbKey(keyStore, "PerfectDNSManager")

    /**
     * @param pairCode code 6 chiffres affiché par Android sur l'écran "Coupler avec un code"
     * @param callback callback (sur le thread appelant — appeler depuis un Thread dédié)
     */
    fun pairAndGrant(pairCode: String, callback: Callback) {
        if (!AdbPairingClient.available()) {
            callback.onError("PAIRING_NATIVE_UNAVAILABLE")
            return
        }

        callback.onProgress("DISCOVERING_PAIR_PORT")
        val pairPort = discoverPort(AdbMdns.TLS_PAIRING)
        if (pairPort <= 0) {
            callback.onError("MDNS_PAIR_NOT_FOUND")
            return
        }
        Log.i(TAG, "Pairing port discovered: $pairPort")

        callback.onProgress("PAIRING")
        val pairOk = try {
            AdbPairingClient("127.0.0.1", pairPort, pairCode, key).use { it.start() }
        } catch (e: AdbInvalidPairingCodeException) {
            callback.onError("INVALID_PAIR_CODE")
            return
        } catch (e: Throwable) {
            Log.e(TAG, "Pairing failed: ${e.message}", e)
            callback.onError("PAIR_FAILED:${e.message}")
            return
        }
        if (!pairOk) {
            callback.onError("PAIR_FAILED")
            return
        }
        Log.i(TAG, "Pairing OK")

        callback.onProgress("DISCOVERING_CONNECT_PORT")
        val connectPort = discoverPort(AdbMdns.TLS_CONNECT)
        if (connectPort <= 0) {
            callback.onError("MDNS_CONNECT_NOT_FOUND")
            return
        }
        Log.i(TAG, "Connect port discovered: $connectPort")

        callback.onProgress("GRANTING")
        try {
            AdbClient("127.0.0.1", connectPort, key).use { client ->
                client.connect()
                val output = StringBuilder()
                client.shellCommand(
                    "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                ) { data -> output.append(String(data)) }
                Log.i(TAG, "pm grant output: $output")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ADB connect/grant failed: ${e.message}", e)
            callback.onError("GRANT_FAILED:${e.message}")
            return
        }

        // Verify
        Thread.sleep(500)
        val granted = context.checkCallingOrSelfPermission(
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback.onSuccess()
        } else {
            callback.onError("GRANT_NOT_EFFECTIVE")
        }
    }

    /**
     * Bloquante, attend MDNS_TIMEOUT_MS qu'un port soit annoncé via mDNS
     * pour le service donné. Retourne -1 si timeout.
     */
    private fun discoverPort(serviceType: String): Int {
        val latch = CountDownLatch(1)
        var foundPort = -1
        val mdns = AdbMdns(context, serviceType, Observer { port ->
            if (port > 0 && foundPort < 0) {
                foundPort = port
                latch.countDown()
            }
        })
        mdns.start()
        try {
            latch.await(MDNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return foundPort
    }
}
