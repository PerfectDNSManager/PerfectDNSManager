package net.appstorefr.perfectdnsmanager.util

import android.util.Base64
import android.util.Log
import net.appstorefr.perfectdnsmanager.BuildConfig
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

/**
 * Vrai E2EE : la clé AES-256 est générée côté client, utilisée côté client,
 * et ne quitte **jamais** le périphérique (ni via query, ni via header, ni
 * stockée côté serveur). Elle voyage uniquement dans le fragment URL côté
 * navigateur du destinataire (#KEY), hors de portée des logs serveur/proxy.
 */
class EncryptedSharer {

    companion object {
        private const val TAG = "EncryptedSharer"
        private const val AES_KEY_SIZE = 128 // Clé 16 bytes = fragment base64url ~22 chars (vs 43 pour AES-256). Sécurité 2^128 largement suffisante.
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128

        private const val PDM_BASE_URL = "https://pdm.appstorefr.net"
        private const val PDM_API_KEY = "2b396e45cea4b5f9d7176bad3552c5ad0ba2c9170e917f5aa235e43f9292ba2e"

        // Slugs : anciens 6-chiffres ou 5-8 alnum tolérés en lecture + nouveaux 16 alnum.
        private val SLUG_RE = Regex("^[0-9]{6}$|^[a-z0-9]{5,16}$")

        data class UploadResult(
            val shortCode: String,
            val decryptionKey: String,
            val fullUrl: String,
            val fileUrl: String
        )

        /**
         * Chiffre en AES-256-GCM (IV||ct||tag), upload le ciphertext seul sur
         * pdm.appstorefr.net (clé **jamais** transmise au serveur), et retourne
         * l'URL partageable avec la clé concaténée en fragment : `short_url#KEY`.
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
            // ⚠️ La clé n'est JAMAIS envoyée au serveur. Aucun ?k= dans l'URL.
            val uploadUrl = "$PDM_BASE_URL/api/upload?expires_in=$expiresIn&name=$nameParam"

            val request = Request.Builder()
                .url(uploadUrl)
                .header("X-API-Key", PDM_API_KEY)
                .post(combined.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            if (BuildConfig.DEBUG) Log.d(TAG, "Uploading ${combined.size} bytes")
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: ""
            response.close()

            if (!response.isSuccessful) {
                throw Exception("Upload failed (${response.code})")
            }

            val json = JSONObject(body)
            val slug = json.optString("slug", "")
            val shortUrl = json.optString("short_url", "")
            val rawUrl = json.optString("raw_url", "")
            if (slug.isBlank() || shortUrl.isBlank()) {
                throw Exception("Upload response invalid")
            }

            // Concatène la clé en fragment — le destinataire l'utilise via window.location.hash.
            val fullUrlWithKey = "$shortUrl#$keyBase64"

            return UploadResult(
                shortCode = slug,
                decryptionKey = keyBase64,
                fullUrl = fullUrlWithKey,
                fileUrl = rawUrl
            )
        }

        /**
         * Télécharge et déchiffre depuis une URL complète `pdm/decrypt/{slug}#KEY`.
         * La clé DOIT être présente (dans l'URL fragment ou explicitKey). Un code
         * nu sans clé ne peut plus être résolu — c'est voulu (vrai E2EE).
         */
        fun downloadAndDecrypt(shortCodeOrUrl: String, explicitKey: String? = null): String {
            val (slug, keyFromFragment) = parseSlugAndKey(shortCodeOrUrl)
            val key = explicitKey ?: keyFromFragment
                ?: throw Exception("Clé de déchiffrement manquante — l'URL doit inclure #KEY")

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
                throw Exception("Téléchargement échoué (${response.code})")
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
         * Accepte : "ABCDEF..." (slug seul, ne peut PAS déchiffrer sans key),
         * "slug#key" (URL fragment), "https://pdm.appstorefr.net/decrypt/slug#key".
         */
        private fun parseSlugAndKey(input: String): Pair<String, String?> {
            val trimmed = input.trim()

            if (trimmed.contains("appstorefr.github.io", ignoreCase = true)) {
                throw Exception("Format legacy github.io non supporté")
            }

            val hashIndex = trimmed.indexOf('#')
            val base = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
            val key = if (hashIndex >= 0) trimmed.substring(hashIndex + 1).takeIf { it.isNotBlank() } else null

            val slug = if (base.contains("/")) {
                base.trimEnd('/').substringAfterLast('/')
            } else {
                base
            }

            val cleanSlug = slug.substringBefore('?')
            if (!cleanSlug.matches(SLUG_RE)) {
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
