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
        private const val LATEST_BETA_TAG = "latest-beta"
        private const val LATEST_STABLE_ASSET = "latest.apk"
        private const val LATEST_BETA_ASSET = "PerfectDNSManager-latest-beta.apk"
        private val BETA_BODY_VERSION_RE = Regex("""Build actuel\s*:\s*\**\s*(v?\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)""")
    }

    private data class ReleaseInfo(val version: String, val apkUrl: String, val apkSize: Long)

    /**
     * Compare deux versions sémantiques avec support des suffixes pré-release (`1.1.0-beta.3`).
     * Une version sans suffixe est supérieure à la même version avec suffixe.
     * Renvoie positif si remote > local, 0 si égales, négatif si remote < local.
     */
    private fun compareVersions(remote: String, local: String): Int {
        val (rBase, rSuffix) = splitVersion(remote)
        val (lBase, lSuffix) = splitVersion(local)
        val baseCmp = compareNumericParts(rBase, lBase)
        if (baseCmp != 0) return baseCmp
        if (rSuffix == null && lSuffix == null) return 0
        if (rSuffix == null) return 1
        if (lSuffix == null) return -1
        return compareNumericParts(rSuffix, lSuffix)
    }

    private fun splitVersion(v: String): Pair<String, String?> {
        val clean = v.removePrefix("v").trim()
        val idx = clean.indexOf('-')
        return if (idx < 0) clean to null else clean.substring(0, idx) to clean.substring(idx + 1)
    }

    private fun compareNumericParts(a: String, b: String): Int {
        val ap = a.split('.', '-').map { it.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
        val bp = b.split('.', '-').map { it.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }

    private fun betaEnabled(): Boolean =
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("beta_updates_enabled", false)

    /**
     * Vérification manuelle (About) : Toast "à jour" ou téléchargement direct.
     */
    fun checkForUpdateGitHub(githubRepo: String, currentVersion: String) {
        fetchBestRelease(githubRepo, betaEnabled()) { release ->
            if (release == null) return@fetchBestRelease
            if (compareVersions(release.version, currentVersion) > 0) {
                showToastOnMainThread(context.getString(R.string.update_available, release.version))
                downloadAndInstallUpdate(release.apkUrl)
            } else {
                showToastOnMainThread(context.getString(R.string.app_up_to_date))
            }
        }
    }

    /**
     * Vérification silencieuse au lancement : AlertDialog si MAJ dispo, ne se déclenche
     * qu'une fois par version détectée.
     */
    fun checkOnLaunch(currentVersion: String) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val dismissedVersion = prefs.getString("dismissed_version", null)

        fetchBestRelease(GITHUB_REPO, betaEnabled()) { release ->
            if (release == null) return@fetchBestRelease
            if (compareVersions(release.version, currentVersion) <= 0) return@fetchBestRelease
            if (release.version == dismissedVersion) return@fetchBestRelease

            val sizeStr = if (release.apkSize > 0) String.format("%.1f Mo", release.apkSize / 1_000_000.0) else ""
            runOnMainThread {
                if (context is Activity && !context.isFinishing) {
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.update_dialog_title))
                        .setMessage(context.getString(R.string.update_dialog_message, release.version, sizeStr))
                        .setPositiveButton(context.getString(R.string.update_dialog_install)) { _, _ ->
                            downloadAndInstallUpdate(release.apkUrl)
                        }
                        .setNegativeButton(context.getString(R.string.update_dialog_later)) { _, _ ->
                            prefs.edit().putString("dismissed_version", release.version).apply()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    /**
     * Récupère la « meilleure » release :
     *   - canal stable seul : `releases/latest` (la dernière non-prerelease)
     *   - canal bêta activé : compare la stable et le release pinné `latest-beta`,
     *     prend la version la plus haute. La version exacte de la bêta est lue
     *     dans le body du release `latest-beta` (« Build actuel : v1.1.0-beta.X »).
     */
    private fun fetchBestRelease(githubRepo: String, includeBeta: Boolean, callback: (ReleaseInfo?) -> Unit) {
        if (!includeBeta) {
            fetchRelease("https://api.github.com/repos/$githubRepo/releases/latest", LATEST_STABLE_ASSET, callback)
            return
        }
        fetchRelease("https://api.github.com/repos/$githubRepo/releases/latest", LATEST_STABLE_ASSET) { stable ->
            fetchRelease("https://api.github.com/repos/$githubRepo/releases/tags/$LATEST_BETA_TAG", LATEST_BETA_ASSET) { beta ->
                val best = when {
                    stable != null && beta != null -> if (compareVersions(beta.version, stable.version) > 0) beta else stable
                    else -> stable ?: beta
                }
                callback(best)
            }
        }
    }

    private fun fetchRelease(apiUrl: String, preferredAsset: String, callback: (ReleaseInfo?) -> Unit) {
        Log.i(TAG, "Fetching release: $apiUrl")
        Fuel.get(apiUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .responseString { _, _, result ->
                when (result) {
                    is Result.Success -> {
                        try {
                            val json = JSONObject(result.get())
                            val tagName = json.optString("tag_name", "")
                            val body = json.optString("body", "")
                            val version = if (tagName == LATEST_BETA_TAG) {
                                BETA_BODY_VERSION_RE.find(body)?.groupValues?.get(1)?.removePrefix("v") ?: tagName
                            } else {
                                tagName.removePrefix("v")
                            }
                            val (apkUrl, apkSize) = pickAsset(json.getJSONArray("assets"), preferredAsset)
                            if (apkUrl != null) callback(ReleaseInfo(version, apkUrl, apkSize)) else callback(null)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur parsing release", e)
                            callback(null)
                        }
                    }
                    is Result.Failure -> {
                        Log.w(TAG, "Release indisponible ($apiUrl): ${result.getException().message}")
                        callback(null)
                    }
                }
            }
    }

    private fun pickAsset(assets: org.json.JSONArray, preferredName: String): Pair<String?, Long> {
        var preferredUrl: String? = null
        var preferredSize: Long = 0
        var fallbackUrl: String? = null
        var fallbackSize: Long = 0
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name == preferredName) {
                preferredUrl = asset.optString("browser_download_url", null)
                preferredSize = asset.optLong("size", 0)
                break
            }
            if (fallbackUrl == null && name.endsWith(".apk")) {
                fallbackUrl = asset.optString("browser_download_url", null)
                fallbackSize = asset.optLong("size", 0)
            }
        }
        return if (preferredUrl != null) preferredUrl to preferredSize else fallbackUrl to fallbackSize
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
