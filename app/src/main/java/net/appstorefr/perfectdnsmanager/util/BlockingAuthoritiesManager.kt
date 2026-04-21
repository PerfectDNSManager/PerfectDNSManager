package net.appstorefr.perfectdnsmanager.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Gère la liste des IP de blocage et leurs autorités (FAI, ARCOM, etc.)
 *
 * Priorité de chargement :
 * 1. SharedPreferences (données téléchargées depuis GitHub Pages si version > locale)
 * 2. Fichier embarqué assets/blocking-authorities.json (intégré à chaque build)
 *
 * Le téléchargement distant ne se fait que si la version distante est supérieure.
 */
object BlockingAuthoritiesManager {

    private const val TAG = "BlockingAuth"
    private const val PREFS_NAME = "blocking_authorities"
    private const val KEY_JSON = "authorities_json"
    private const val KEY_VERSION = "authorities_version"
    private const val ASSET_FILE = "blocking-authorities.json"
    private const val REMOTE_URL = "https://appstorefr.github.io/PerfectDNSManager/blocking-authorities.json"

    data class BlockingAuthority(
        val ip: String,
        val type: String,
        val label: String,
        val country: String
    )

    /**
     * Retourne l'autorité de blocage pour une IP donnée, ou null si inconnue.
     */
    fun getAuthority(context: Context, ip: String): BlockingAuthority? {
        val authorities = loadAuthorities(context)
        return authorities.find { it.ip == ip }
    }

    /**
     * Retourne le label d'autorité pour une IP, ou null.
     */
    fun getAuthorityLabel(context: Context, ip: String): String? {
        return getAuthority(context, ip)?.label
    }

    /**
     * Charge les autorités :
     * 1. SharedPreferences (si une version distante a été téléchargée)
     * 2. Fichier assets embarqué (fallback)
     */
    fun loadAuthorities(context: Context): List<BlockingAuthority> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null)
        if (json != null) {
            return parseAuthorities(json)
        }
        // Charger depuis le fichier assets embarqué
        return loadFromAssets(context)
    }

    /**
     * Télécharge la liste distante et met à jour si version plus récente.
     * Compare avec la version locale la plus haute (SharedPrefs ou assets).
     * À appeler en background (ex: au même moment que checkForUpdate).
     */
    fun syncFromRemote(context: Context) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(REMOTE_URL).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()

            if (!response.isSuccessful || body.isEmpty()) {
                Log.w(TAG, "Sync failed: HTTP ${response.code}")
                return
            }

            val remoteJson = JSONObject(body)
            val remoteVersion = remoteJson.optInt("version", 0)
            val localVersion = getLocalVersion(context)

            if (remoteVersion > localVersion) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_JSON, body)
                    .putInt(KEY_VERSION, remoteVersion)
                    .apply()
                Log.i(TAG, "Updated blocking authorities: v$localVersion -> v$remoteVersion")
            } else {
                Log.i(TAG, "Blocking authorities up to date (v$localVersion)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync error: ${e.message}")
        }
    }

    /**
     * Retourne la version locale la plus haute entre SharedPrefs et assets.
     */
    private fun getLocalVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefsVersion = prefs.getInt(KEY_VERSION, 0)
        val assetsVersion = getAssetsVersion(context)
        return maxOf(prefsVersion, assetsVersion)
    }

    private fun getAssetsVersion(context: Context): Int {
        return try {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            JSONObject(json).optInt("version", 0)
        } catch (_: Exception) { 0 }
    }

    private fun loadFromAssets(context: Context): List<BlockingAuthority> {
        return try {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            parseAuthorities(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from assets: ${e.message}")
            emptyList()
        }
    }

    private fun parseAuthorities(json: String): List<BlockingAuthority> {
        return try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("authorities")
            val list = mutableListOf<BlockingAuthority>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(BlockingAuthority(
                    ip = item.getString("ip"),
                    type = item.optString("type", "UNKNOWN"),
                    label = item.optString("label", "Blocage inconnu"),
                    country = item.optString("country", "ALL")
                ))
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            emptyList()
        }
    }
}
