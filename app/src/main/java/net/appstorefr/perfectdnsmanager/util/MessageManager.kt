package net.appstorefr.perfectdnsmanager.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Récupère le message de broadcast publié par le serveur (pdm.appstorefr.net/api/message)
 * et l'affiche à l'utilisateur s'il est nouveau. L'id du dernier message vu est conservé
 * dans SharedPreferences pour ne pas le ré-afficher à chaque lancement.
 */
object MessageManager {

    private const val TAG = "MessageManager"
    private const val ENDPOINT = "https://pdm.appstorefr.net/api/message"
    private const val PREFS = "pdm_messages"
    private const val KEY_LAST_SEEN = "last_seen_id"

    fun fetchAndShow(activity: Activity) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url(ENDPOINT).build()
                val resp = client.newCall(req).execute()
                if (resp.code == 204) {
                    resp.close()
                    return@Thread
                }
                if (!resp.isSuccessful) {
                    Log.d(TAG, "Message fetch non-OK: ${resp.code}")
                    resp.close()
                    return@Thread
                }
                val body = resp.body?.string() ?: ""
                resp.close()
                if (body.isBlank()) return@Thread

                val json = JSONObject(body)
                val id = json.optString("id", "")
                if (id.isBlank()) return@Thread

                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getString(KEY_LAST_SEEN, "") == id) return@Thread

                val text = json.optString("text", "")
                val severity = json.optString("severity", "info")
                val url = json.optString("url", "")

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val title = when (severity) {
                        "error" -> "⚠ Alerte"
                        "warn" -> "Avertissement"
                        else -> "Information"
                    }
                    val builder = AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(text)
                        .setPositiveButton("OK") { _, _ ->
                            prefs.edit().putString(KEY_LAST_SEEN, id).apply()
                        }
                        .setOnCancelListener {
                            prefs.edit().putString(KEY_LAST_SEEN, id).apply()
                        }
                    if (url.startsWith("http")) {
                        builder.setNeutralButton("Ouvrir") { _, _ ->
                            prefs.edit().putString(KEY_LAST_SEEN, id).apply()
                            try {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) { }
                        }
                    }
                    builder.show()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchAndShow error: ${e.message}")
            }
        }.start()
    }
}
