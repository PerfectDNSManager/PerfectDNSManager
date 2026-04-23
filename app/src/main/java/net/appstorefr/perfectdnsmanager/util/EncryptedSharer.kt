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
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Partage E2EE avec lien court + mot de passe.
 *
 * Modèle : user partage deux choses (URL courte 8 chars + passphrase 4 mots).
 * Clé AES-128 dérivée via PBKDF2-SHA256(password, salt_random_16b, 600_000 iter).
 * Ciphertext stocké côté serveur sans aucun secret ; le password transite via
 * canal utilisateur (oral, SMS, etc.) et n'atteint jamais le serveur.
 *
 * Format upload body : salt(16) || IV(12) || ciphertext || tag(16).
 */
class EncryptedSharer {

    companion object {
        private const val TAG = "EncryptedSharer"

        private const val AES_KEY_BYTES = 16 // AES-128
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128
        private const val SALT_SIZE = 16
        // PBKDF2 1M iter = ~3s mobile, ~1ms GPU → crack 6-char alnum = 25j single-GPU.
        // Expiration courte (1h default) rend la fenêtre d'attaque impraticable.
        private const val PBKDF2_ITER = 1_000_000
        private const val PASSWORD_LEN = 6

        private const val PDM_BASE_URL = "https://pdm.appstorefr.net"
        private const val PDM_API_KEY = "2b396e45cea4b5f9d7176bad3552c5ad0ba2c9170e917f5aa235e43f9292ba2e"

        private val SLUG_RE = Regex("^[0-9]{6}$|^[a-z0-9]{5,16}$")

        // Alphabet sans caractères ambigus (0/O, 1/l/I exclus) pour dictée orale.
        private const val PWD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        data class UploadResult(
            val shortCode: String,
            val password: String,
            val fullUrl: String,
            val fileUrl: String
        )

        /**
         * Chiffre en AES-128-GCM avec clé dérivée d'un mot de passe aléatoire
         * 4 mots. Upload ciphertext (salt||iv||ct||tag) sans aucun secret.
         * Retourne URL courte + password — les deux à partager séparément.
         */
        fun encryptAndUpload(content: String, fileName: String = "data.enc", expiresIn: String = "1h"): UploadResult {
            val rng = SecureRandom()

            val password = generatePassword(rng, PASSWORD_LEN)
            val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
            val keyBytes = pbkdf2(password.toCharArray(), salt, PBKDF2_ITER, AES_KEY_BYTES)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val iv = ByteArray(GCM_IV_SIZE).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            val combined = salt + iv + encrypted

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val nameParam = java.net.URLEncoder.encode(fileName, "UTF-8")
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
        }

        /**
         * Télécharge depuis une URL courte + déchiffre avec le mot de passe.
         * Le slug peut être un code court ou une URL complète.
         */
        fun downloadAndDecrypt(shortCodeOrUrl: String, password: String): String {
            val slug = parseSlug(shortCodeOrUrl)
            if (password.isBlank()) throw Exception("Mot de passe requis")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url("$PDM_BASE_URL/r/$slug").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("Téléchargement échoué (${response.code})")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            response.close()

            if (bytes.size < SALT_SIZE + GCM_IV_SIZE + 16) {
                throw Exception("Contenu trop court (${bytes.size} octets)")
            }

            val salt = bytes.copyOfRange(0, SALT_SIZE)
            val iv = bytes.copyOfRange(SALT_SIZE, SALT_SIZE + GCM_IV_SIZE)
            val ct = bytes.copyOfRange(SALT_SIZE + GCM_IV_SIZE, bytes.size)

            val keyBytes = pbkdf2(password.toCharArray(), salt, PBKDF2_ITER, AES_KEY_BYTES)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val decrypted = try {
                cipher.doFinal(ct)
            } catch (e: Exception) {
                throw Exception("Mot de passe incorrect ou contenu corrompu")
            }
            return String(decrypted, Charsets.UTF_8)
        }

        /** Accepte "abcd1234" (slug), "https://pdm.appstorefr.net/d/abcd1234", etc. */
        private fun parseSlug(input: String): String {
            val trimmed = input.trim()
            if (trimmed.contains("appstorefr.github.io", ignoreCase = true)) {
                throw Exception("Format legacy non supporté")
            }
            val base = if (trimmed.contains('#')) trimmed.substringBefore('#') else trimmed
            val raw = if (base.contains("/")) base.trimEnd('/').substringAfterLast('/') else base
            val clean = raw.substringBefore('?')
            if (!clean.matches(SLUG_RE)) throw Exception("Code invalide : $clean")
            return clean
        }

        private fun pbkdf2(password: CharArray, salt: ByteArray, iter: Int, keyLen: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iter, keyLen * 8)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }

        private fun generatePassword(rng: SecureRandom, len: Int): String =
            (1..len).map { PWD_ALPHABET[rng.nextInt(PWD_ALPHABET.length)] }.joinToString("")

        /**
         * Entrée legacy (base64(IV||ct||tag) + clé) — conservée pour code interne.
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
