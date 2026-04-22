package net.appstorefr.perfectdnsmanager.util

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedSharer {

    companion object {
        private const val TAG = "EncryptedSharer"
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128

        private const val PDM_BASE_URL = "https://pdm.appstorefr.net"
        private const val PDM_API_KEY = "2b396e45cea4b5f9d7176bad3552c5ad0ba2c9170e917f5aa235e43f9292ba2e"

        data class UploadResult(
            val shortCode: String,
            val decryptionKey: String,
            val fullUrl: String,
            val fileUrl: String
        )

        /**
         * Chiffre le contenu en AES-256-GCM (IV||ciphertext||tag), upload sur pdm.appstorefr.net,
         * retourne un slug court (6 chiffres) et l'URL de déchiffrement avec la clé en fragment.
         */
        fun encryptAndUpload(content: String, fileName: String = "data.enc", expiresIn: String = "1h"): UploadResult {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val secretKey = keyGen.generateKey()
            val keyBase64 = Base64.encodeToString(secretKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val iv = ByteArray(GCM_IV_SIZE)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            val combined = iv + encrypted

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val nameParam = java.net.URLEncoder.encode(fileName, "UTF-8")
            val keyParam = java.net.URLEncoder.encode(keyBase64, "UTF-8")
            val uploadUrl = "$PDM_BASE_URL/api/upload?expires_in=$expiresIn&name=$nameParam&k=$keyParam"

            val request = Request.Builder()
                .url(uploadUrl)
                .header("X-API-Key", PDM_API_KEY)
                .post(combined.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            Log.d(TAG, "Uploading ${combined.size} bytes to pdm.appstorefr.net")
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: ""
            response.close()

            if (!response.isSuccessful) {
                throw Exception("Upload failed (${response.code}): $body")
            }

            val json = JSONObject(body)
            val slug = json.optString("slug", "")
            val shortUrl = json.optString("short_url", "")
            val rawUrl = json.optString("raw_url", "")
            if (slug.isBlank() || shortUrl.isBlank()) {
                throw Exception("Upload response invalid: $body")
            }

            Log.d(TAG, "slug=$slug short=$shortUrl")

            return UploadResult(
                shortCode = slug,
                decryptionKey = keyBase64,
                fullUrl = shortUrl,
                fileUrl = rawUrl
            )
        }

        /**
         * Télécharge et déchiffre depuis un code 6 chiffres ou une URL complète (optionnellement avec #clé).
         * Pour un code nu, la clé doit être fournie séparément via [decrypt].
         */
        fun downloadAndDecrypt(shortCodeOrUrl: String, explicitKey: String? = null): String {
            val (slug, keyFromFragment) = parseSlugAndKey(shortCodeOrUrl)
            val key = explicitKey ?: keyFromFragment ?: resolveKeyFromSlug(slug)
                ?: throw Exception("Clé introuvable pour ce code (lien peut-être expiré)")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$PDM_BASE_URL/r/$slug")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("Téléchargement échoué (${response.code}) pour slug=$slug")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            response.close()

            if (bytes.size < GCM_IV_SIZE + 16) {
                throw Exception("Contenu trop court (${bytes.size} octets)")
            }

            val keyBytes = Base64.decode(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
            val iv = bytes.copyOfRange(0, GCM_IV_SIZE)
            val encrypted = bytes.copyOfRange(GCM_IV_SIZE, bytes.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, Charsets.UTF_8)
        }

        /**
         * Résout la clé depuis un slug nu en suivant le 302 du Worker sans le follower :
         *   GET /decrypt/:slug  →  Location: decrypt.html?s=:slug#KEY
         * Le fragment du Location header est l'AES key base64url.
         */
        private fun resolveKeyFromSlug(slug: String): String? {
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("$PDM_BASE_URL/decrypt/$slug")
                .head()
                .build()
            return try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code !in 300..399) return null
                    val loc = resp.header("Location") ?: return null
                    val hashIdx = loc.indexOf('#')
                    if (hashIdx < 0) null else loc.substring(hashIdx + 1).takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) { null }
        }

        /**
         * Accepte : "123456", "123456#clé", "https://pdm.appstorefr.net/decrypt/123456#clé",
         * ou anciennes URLs github.io avec fragment "fileUrl|key".
         * Retourne (slug, key?) — key peut être null si absente.
         */
        private fun parseSlugAndKey(input: String): Pair<String, String?> {
            val trimmed = input.trim()

            // URL github.io legacy : #encodedFileUrl|key → on ne peut pas extraire de slug, on lève
            if (trimmed.contains("appstorefr.github.io", ignoreCase = true)) {
                throw Exception("Format legacy github.io non supporté dans cette version")
            }

            val hashIndex = trimmed.indexOf('#')
            val base = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
            val key = if (hashIndex >= 0) trimmed.substring(hashIndex + 1).takeIf { it.isNotBlank() } else null

            // URL complète : extraire dernier segment
            val slug = if (base.contains("/")) {
                base.trimEnd('/').substringAfterLast('/')
            } else {
                base
            }

            val cleanSlug = slug.substringBefore('?')
            if (!cleanSlug.matches(Regex("^[0-9]{6}$|^[a-z0-9]{5,8}$"))) {
                throw Exception("Code invalide : $cleanSlug")
            }

            return cleanSlug to key
        }

        /**
         * Point d'entrée pour l'ancien code qui passait base64(IV||ct||tag) + clé.
         * Conservé pour compatibilité avec d'anciens flux internes (pas de dépendance réseau).
         */
        fun decrypt(encryptedBase64: String, keyBase64: String): String {
            val keyBytes = Base64.decode(keyBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val encrypted = combined.copyOfRange(GCM_IV_SIZE, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, Charsets.UTF_8)
        }
    }
}
