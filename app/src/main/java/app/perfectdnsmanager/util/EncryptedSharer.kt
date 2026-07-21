package app.perfectdnsmanager.util

import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import app.perfectdnsmanager.BuildConfig
import app.perfectdnsmanager.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Partage E2EE avec lien court + mot de passe.
 *
 * Modèle : user partage deux choses (URL courte + password 6 chars).
 * Clé AES-128 dérivée via Argon2id(m=32 MiB, t=3, p=1, salt=16B).
 * Ciphertext stocké côté serveur sans aucun secret ; le password transite via
 * canal utilisateur (oral, SMS, etc.) et n'atteint jamais le serveur.
 *
 * Format upload body v2 : 0x02 || salt(16) || IV(12) || ciphertext || tag(16).
 */
class EncryptedSharer {

    companion object {
        private const val TAG = "EncryptedSharer"

        private const val FORMAT_VERSION: Byte = 0x02
        private const val AES_KEY_BYTES = 16
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128
        private const val SALT_SIZE = 16

        // Argon2id — RFC 9106 profil mobile-friendly. ~0.5-2s CPU mobile,
        // mémoire 32 MiB tue l'avantage GPU/ASIC vs PBKDF2.
        private const val ARGON2_M_KIB = 32 * 1024   // 32 MiB
        private const val ARGON2_T_COST = 3
        private const val ARGON2_PARALLEL = 1

        private const val PASSWORD_LEN = 6

        private const val PDM_BASE_URL = "https://perfectdnsmanager.app"

        private val SLUG_RE = Regex("^[0-9]{6}$|^[a-z0-9]{5,16}$")

        // Alphabet sans caractères ambigus (0/O, 1/l/I exclus) pour dictée orale.
        private const val PWD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        data class UploadResult(
            val shortCode: String,
            val password: String,
            val fullUrl: String,
            val fileUrl: String
        )

        fun encryptAndUpload(context: android.content.Context, content: String, fileName: String = "data.enc", expiresIn: String = "1h"): UploadResult {
            val rng = SecureRandom()

            val password = generatePassword(rng, PASSWORD_LEN)
            val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
            val keyBytes = argon2id(password.toByteArray(Charsets.UTF_8), salt, AES_KEY_BYTES)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val iv = ByteArray(GCM_IV_SIZE).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            val combined = byteArrayOf(FORMAT_VERSION) + salt + iv + encrypted

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            // Fermer le client dans finally : sinon son thread pool + connexions
            // fuient (cf. BlockingAuthoritiesManager.syncFromRemote).
            try {
                // Token éphémère — pas de clé statique extractible de l'APK.
                // Le worker rate-limite /api/challenge, donc un attaquant ne peut
                // pas se pré-émettre un stock de tokens. TTL 5 min côté serveur.
                val token = fetchUploadToken(client)

                val nameParam = java.net.URLEncoder.encode(fileName, "UTF-8")
                val uploadUrl = "$PDM_BASE_URL/api/upload?expires_in=$expiresIn&name=$nameParam"

                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("X-Upload-Token", token)
                    .post(combined.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                if (BuildConfig.DEBUG) Log.d(TAG, "Uploading ${combined.size} bytes (Argon2id v2)")
                val response = client.newCall(request).execute()
                val body = response.body?.string()?.trim() ?: ""
                response.close()

                if (!response.isSuccessful) throw Exception("Upload failed (${response.code})")

                val json = JSONObject(body)
                val slug = json.optString("slug", "")
                val shortUrl = json.optString("short_url", "")
                val rawUrl = json.optString("raw_url", "")
                if (slug.isBlank() || shortUrl.isBlank()) throw Exception("Upload response invalid")

                return UploadResult(
                    shortCode = slug,
                    password = password,
                    fullUrl = shortUrl,
                    fileUrl = rawUrl
                )
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        fun downloadAndDecrypt(context: android.content.Context, shortCodeOrUrl: String, password: String): String {
            val slug = parseSlug(context, shortCodeOrUrl)
            if (password.isBlank()) throw Exception(context.getString(R.string.es_err_password_required))

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            // Fermer le client dans finally : sinon son thread pool + connexions
            // fuient (cf. BlockingAuthoritiesManager.syncFromRemote).
            val bytes: ByteArray
            try {
                val request = Request.Builder().url("$PDM_BASE_URL/r/$slug").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw Exception(context.getString(R.string.es_err_download_failed_fmt, code))
                }
                bytes = response.body?.bytes() ?: ByteArray(0)
                response.close()
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }

            if (bytes.isEmpty() || bytes[0] != FORMAT_VERSION) {
                throw Exception(context.getString(R.string.es_err_format_unsupported))
            }
            if (bytes.size < 1 + SALT_SIZE + GCM_IV_SIZE + 16) {
                throw Exception(context.getString(R.string.es_err_content_too_short_fmt, bytes.size))
            }

            val salt = bytes.copyOfRange(1, 1 + SALT_SIZE)
            val iv = bytes.copyOfRange(1 + SALT_SIZE, 1 + SALT_SIZE + GCM_IV_SIZE)
            val ct = bytes.copyOfRange(1 + SALT_SIZE + GCM_IV_SIZE, bytes.size)

            val keyBytes = argon2id(password.toByteArray(Charsets.UTF_8), salt, AES_KEY_BYTES)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val decrypted = try {
                cipher.doFinal(ct)
            } catch (e: Exception) {
                throw Exception(context.getString(R.string.es_err_wrong_password))
            }
            return String(decrypted, Charsets.UTF_8)
        }

        /**
         * Demande un token de session au worker via /api/challenge. Token signé
         * HMAC-SHA256 côté serveur, lié à l'IP du client, TTL 5 min. Empêche
         * d'avoir une clé statique extractible de l'APK.
         */
        private fun fetchUploadToken(client: OkHttpClient): String {
            val req = Request.Builder()
                .url("$PDM_BASE_URL/api/challenge")
                .post("".toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw Exception("Challenge failed (${resp.code})")
                val token = JSONObject(body).optString("token", "")
                if (token.isBlank()) throw Exception("Challenge response invalid")
                return token
            }
        }

        /** Accepte "abcd1234" (slug), "https://perfectdnsmanager.app/d/abcd1234", etc. */
        private fun parseSlug(context: android.content.Context, input: String): String {
            val trimmed = input.trim()
            if (trimmed.contains("appstorefr.github.io", ignoreCase = true)) {
                throw Exception(context.getString(R.string.es_err_legacy_unsupported))
            }
            val base = if (trimmed.contains('#')) trimmed.substringBefore('#') else trimmed
            val raw = if (base.contains("/")) base.trimEnd('/').substringAfterLast('/') else base
            val clean = raw.substringBefore('?')
            if (!clean.matches(SLUG_RE)) throw Exception(context.getString(R.string.es_err_invalid_code_fmt, clean))
            return clean
        }

        private fun argon2id(password: ByteArray, salt: ByteArray, keyLen: Int): ByteArray {
            val argon = Argon2Kt()
            val result = argon.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = salt,
                tCostInIterations = ARGON2_T_COST,
                mCostInKibibyte = ARGON2_M_KIB,
                parallelism = ARGON2_PARALLEL,
                hashLengthInBytes = keyLen
            )
            return result.rawHashAsByteArray()
        }

        private fun generatePassword(rng: SecureRandom, len: Int): String =
            (1..len).map { PWD_ALPHABET[rng.nextInt(PWD_ALPHABET.length)] }.joinToString("")
    }
}
