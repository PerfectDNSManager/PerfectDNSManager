package net.appstorefr.perfectdnsmanager.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import net.appstorefr.perfectdnsmanager.R
import org.json.JSONObject
import java.io.File

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_REPO = "appstorefr/PerfectDNSManager"
    }

    /**
     * Compare deux versions sémantiques (ex: "1.0.52" vs "1.0.55").
     * @return positif si remote > local, 0 si égales, négatif si remote < local
     */
    private fun compareVersions(remote: String, local: String): Int {
        val r = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rp = r.getOrElse(i) { 0 }
            val lp = l.getOrElse(i) { 0 }
            if (rp != lp) return rp - lp
        }
        return 0
    }

    /**
     * Vérification manuelle (About) : affiche Toast "à jour" ou télécharge directement.
     */
    fun checkForUpdateGitHub(githubRepo: String, currentVersion: String) {
        fetchLatestRelease(githubRepo) { tagName, apkUrl, _ ->
            if (tagName != null && apkUrl != null && compareVersions(tagName, currentVersion) > 0) {
                showToastOnMainThread(context.getString(R.string.update_available, tagName))
                downloadAndInstallUpdate(apkUrl)
            } else if (tagName != null) {
                showToastOnMainThread(context.getString(R.string.app_up_to_date))
            }
        }
    }

    /**
     * Vérification silencieuse au lancement : affiche un AlertDialog si MAJ dispo,
     * ne fait rien sinon. Ne se déclenche qu'une fois par version détectée.
     */
    fun checkOnLaunch(currentVersion: String) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val dismissedVersion = prefs.getString("dismissed_version", null)

        fetchLatestRelease(GITHUB_REPO) { tagName, apkUrl, apkSize ->
            if (tagName != null && apkUrl != null && compareVersions(tagName, currentVersion) > 0) {
                // Ne pas re-proposer une version déjà refusée
                if (tagName == dismissedVersion) return@fetchLatestRelease

                val sizeStr = if (apkSize > 0) {
                    String.format("%.1f Mo", apkSize / 1_000_000.0)
                } else ""

                runOnMainThread {
                    if (context is Activity && !context.isFinishing) {
                        AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.update_dialog_title))
                            .setMessage(context.getString(R.string.update_dialog_message, tagName, sizeStr))
                            .setPositiveButton(context.getString(R.string.update_dialog_install)) { _, _ ->
                                downloadAndInstallUpdate(apkUrl)
                            }
                            .setNegativeButton(context.getString(R.string.update_dialog_later)) { _, _ ->
                                prefs.edit().putString("dismissed_version", tagName).apply()
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }
    }

    private fun fetchLatestRelease(githubRepo: String, callback: (tagName: String?, apkUrl: String?, apkSize: Long) -> Unit) {
        val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
        Log.i(TAG, "Vérification mise à jour GitHub: $apiUrl")

        Fuel.get(apiUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .responseString { _, _, result ->
                when (result) {
                    is Result.Success -> {
                        try {
                            val json = JSONObject(result.get())
                            val tagName = json.getString("tag_name").removePrefix("v")
                            Log.i(TAG, "Version actuelle, GitHub: $tagName")

                            val assets = json.getJSONArray("assets")
                            var apkUrl: String? = null
                            var apkSize: Long = 0
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.getString("name")
                                if (name == "latest.apk") {
                                    apkUrl = asset.getString("browser_download_url")
                                    apkSize = asset.optLong("size", 0)
                                    break
                                }
                                if (apkUrl == null && name.endsWith(".apk")) {
                                    apkUrl = asset.getString("browser_download_url")
                                    apkSize = asset.optLong("size", 0)
                                }
                            }
                            callback(tagName, apkUrl, apkSize)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur parsing GitHub API", e)
                            showToastOnMainThread(context.getString(R.string.update_check_error))
                        }
                    }
                    is Result.Failure -> {
                        Log.e(TAG, "Erreur vérification MAJ GitHub", result.getException())
                        // Silencieux en mode auto-check
                    }
                }
            }
    }

    private fun downloadAndInstallUpdate(apkUrl: String) {
        showToastOnMainThread(context.getString(R.string.update_downloading))
        val destination = File(context.cacheDir, "update.apk")

        Fuel.download(apkUrl).fileDestination { _, _ -> destination }.response { _, _, result ->
            when (result) {
                is Result.Success -> {
                    Log.i(TAG, "Téléchargement terminé: ${destination.absolutePath}")
                    installApk(destination)
                }
                is Result.Failure -> {
                    Log.e(TAG, "Erreur téléchargement", result.getException())
                    showToastOnMainThread(context.getString(R.string.update_download_error))
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur installation APK", e)
            showToastOnMainThread(context.getString(R.string.update_install_error))
        }
    }

    private fun showToastOnMainThread(message: String) {
        runOnMainThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (context is Activity) {
            context.runOnUiThread(action)
        }
    }
}
