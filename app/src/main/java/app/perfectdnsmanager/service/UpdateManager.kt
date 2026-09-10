package app.perfectdnsmanager.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import app.perfectdnsmanager.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    // [context] est souvent une Activity (dialog/Toast). Pour tout ce qui n'est
    // pas de l'UI (prefs, cacheDir, PackageManager, FileProvider, lancement de
    // l'intent d'install) on passe par l'applicationContext afin de ne pas
    // retenir l'Activity dans les callbacks de download asynchrones.
    private val appContext: Context = context.applicationContext

    // Poster sur le main thread depuis un thread de fond, sans dépendre d'une Activity.
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_REPO = "PerfectDNSManager/PerfectDNSManager"
        private const val LATEST_BETA_TAG = "latest-beta"
        private const val LATEST_STABLE_ASSET = "latest.apk"
        private const val LATEST_BETA_ASSET = "PerfectDNSManager-latest-beta.apk"
        private val BETA_BODY_VERSION_RE = Regex("""Build actuel\s*:\s*\**\s*(v?\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)""")

        /**
         * Hôtes admis pour le téléchargement de l'APK. L'URL vient du JSON de
         * l'API GitHub : sans ce filtre, une réponse altérée pouvait envoyer le
         * téléchargement n'importe où. La vérification de signature reste la
         * garantie de fond, mais autant échouer avant d'écrire 30 Mo sur disque.
         */
        private val ALLOWED_APK_HOSTS = setOf(
            "github.com", "www.github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com"
        )

        /** Marge tolérée autour de la taille annoncée par l'API (octets). */
        private const val SIZE_SLACK = 4L * 1024 * 1024
    }

    /**
     * Client HTTP unique. Remplace Fuel (kittinunf), qui n'était utilisé que
     * dans ce fichier : une seconde pile HTTP complète, non maintenue depuis
     * des années, embarquée dans le chemin le plus sensible de l'app.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
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
        appContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("beta_updates_enabled", false)

    /**
     * Vérification manuelle (About) : Toast "à jour" ou téléchargement direct.
     */
    fun checkForUpdateGitHub(githubRepo: String, currentVersion: String) {
        fetchBestRelease(githubRepo, betaEnabled()) { release ->
            if (release == null) return@fetchBestRelease
            if (compareVersions(release.version, currentVersion) > 0) {
                // Le téléchargement démarrait immédiatement : plusieurs dizaines
                // de Mo sans confirmation, y compris en données mobiles.
                val sizeStr = if (release.apkSize > 0)
                    String.format("%.1f Mo", release.apkSize / 1_000_000.0) else ""
                runOnMainThread {
                    if (context is Activity && !context.isFinishing) {
                        AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.update_dialog_title))
                            .setMessage(context.getString(R.string.update_dialog_message, release.version, sizeStr))
                            .setPositiveButton(context.getString(R.string.update_dialog_install)) { _, _ ->
                                downloadAndInstallUpdate(release.apkUrl, release.apkSize)
                            }
                            .setNegativeButton(context.getString(R.string.update_dialog_later), null)
                            .show()
                    } else {
                        showToastOnMainThread(appContext.getString(R.string.update_available, release.version))
                    }
                }
            } else {
                showToastOnMainThread(appContext.getString(R.string.app_up_to_date))
            }
        }
    }

    /**
     * Vérification silencieuse au lancement : AlertDialog si MAJ dispo, ne se déclenche
     * qu'une fois par version détectée.
     */
    fun checkOnLaunch(currentVersion: String) {
        val prefs = appContext.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
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
                            downloadAndInstallUpdate(release.apkUrl, release.apkSize)
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
        Thread {
            val info = try {
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Release indisponible ($apiUrl): HTTP ${resp.code}")
                        null
                    } else {
                        val json = JSONObject(resp.body?.string() ?: "")
                        val tagName = json.optString("tag_name", "")
                        val body = json.optString("body", "")
                        val version = if (tagName == LATEST_BETA_TAG) {
                            BETA_BODY_VERSION_RE.find(body)?.groupValues?.get(1)?.removePrefix("v") ?: tagName
                        } else {
                            tagName.removePrefix("v")
                        }
                        val (apkUrl, apkSize) = pickAsset(json.getJSONArray("assets"), preferredAsset)
                        if (apkUrl != null) ReleaseInfo(version, apkUrl, apkSize) else null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Release indisponible ($apiUrl): ${e.javaClass.simpleName}")
                null
            }
            callback(info)
        }.start()
    }

    private fun pickAsset(assets: org.json.JSONArray, preferredName: String): Pair<String?, Long> {
        var preferredUrl: String? = null
        var preferredSize: Long = 0
        var fallbackUrl: String? = null
        var fallbackSize: Long = 0
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            // `optString(key, null)` faisait inférer Nothing? à Kotlin (2 warnings
            // à chaque build) et masquait un null possible.
            val url = asset.optString("browser_download_url", "").ifEmpty { null }
            if (name == preferredName) {
                preferredUrl = url
                preferredSize = asset.optLong("size", 0)
                break
            }
            if (fallbackUrl == null && name.endsWith(".apk")) {
                fallbackUrl = url
                fallbackSize = asset.optLong("size", 0)
            }
        }
        return if (preferredUrl != null) preferredUrl to preferredSize else fallbackUrl to fallbackSize
    }

    private fun downloadAndInstallUpdate(apkUrl: String, expectedSize: Long = 0) {
        // A5 : l'URL vient d'un JSON distant — on refuse tout hôte inattendu.
        val host = try { java.net.URL(apkUrl).host?.lowercase() } catch (_: Exception) { null }
        if (!apkUrl.startsWith("https://") || host == null || host !in ALLOWED_APK_HOSTS) {
            Log.e(TAG, "URL de téléchargement refusée (hôte inattendu)")
            showToastOnMainThread(appContext.getString(R.string.update_download_error))
            return
        }

        showToastOnMainThread(appContext.getString(R.string.update_downloading))
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val destination = File.createTempFile("update-", ".apk", updatesDir)
        // Plafond : la taille annoncée par l'API + une marge. Sans lui, une URL
        // hostile pouvait remplir le cache avant même la vérif de signature.
        val maxBytes = if (expectedSize in 1..(200L * 1024 * 1024 - SIZE_SLACK)) expectedSize + SIZE_SLACK else 200L * 1024 * 1024

        Thread {
            try {
                val req = Request.Builder().url(apkUrl).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("empty body")
                    var written = 0L
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > maxBytes) throw java.io.IOException("APK trop volumineux")
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
                Log.i(TAG, "Download complete: ${destination.absolutePath}")
                installApk(destination)
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.javaClass.simpleName}: ${e.message}")
                destination.delete()
                showToastOnMainThread(appContext.getString(R.string.update_download_error))
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        // Déjà appelé depuis un thread de fond (cf. downloadAndInstallUpdate),
        // mais on garde le Thread : la vérif de signature parse l'APK entier et
        // ne doit jamais approcher le main thread (ANR).
        Thread {
            try {
                // Vérifier la signature AVANT de lancer l'install : si le compte
                // GitHub PerfectDNSManager est compromis ou si on subit un MitM avec un
                // cert custom installé sur l'appareil, l'APK téléchargé pourrait
                // venir d'un attaquant. Comparer avec la signature du package
                // courant ferme ce vecteur (PackageInstaller bloque ensuite tout
                // mismatch côté système, mais on échoue plus tôt et plus clair).
                if (!verifyApkSignature(apkFile)) {
                    Log.e(TAG, "APK signature mismatch — install aborted")
                    showToastOnMainThread(appContext.getString(R.string.update_signature_mismatch))
                    apkFile.delete()
                    return@Thread
                }
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runOnMainThread { appContext.startActivity(intent) }
                // A4 : l'APK ne restait dans le cache que sur le chemin d'échec.
                // On le supprime après que l'installateur a eu le temps de lire
                // l'URI (le FileProvider garde l'accès le temps de la lecture).
                mainHandler.postDelayed({ runCatching { apkFile.delete() } }, 24 * 60 * 60_000L)
            } catch (e: Exception) {
                Log.e(TAG, "APK install error", e)
                showToastOnMainThread(appContext.getString(R.string.update_install_error))
            }
        }.start()
    }

    /**
     * Compare le SHA-256 du certificat de signature de l'APK téléchargé avec
     * celui de l'app courante. Renvoie false si différent ou indéterminable.
     * On utilise l'API PackageManager pour parser l'APK (ne nécessite aucune
     * permission supplémentaire et fonctionne hors ligne).
     */
    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val pm = appContext.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val downloadedInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: run { Log.e(TAG, "Cannot parse APK"); return false }
            if (downloadedInfo.packageName != appContext.packageName) return false
            val installedInfo = pm.getPackageInfo(appContext.packageName, flags)

            val downloadedSigs = signaturesFor(downloadedInfo)
            val installedSigs = signaturesFor(installedInfo)
            if (downloadedSigs.isEmpty() || installedSigs.isEmpty()) {
                Log.e(TAG, "Empty signatures (downloaded=${downloadedSigs.size}, installed=${installedSigs.size})")
                return false
            }
            val match = installedSigs.any { it in downloadedSigs }
            if (!match) {
                Log.e(TAG, "Signature mismatch: installed=${installedSigs.firstOrNull()?.take(16)}… downloaded=${downloadedSigs.firstOrNull()?.take(16)}…")
            }
            match
        } catch (e: Exception) {
            Log.e(TAG, "verifyApkSignature error", e)
            false
        }
    }

    private fun signaturesFor(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            } ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun showToastOnMainThread(message: String) {
        runOnMainThread {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
