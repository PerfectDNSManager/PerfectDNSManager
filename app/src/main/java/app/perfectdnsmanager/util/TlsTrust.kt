package app.perfectdnsmanager.util

import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Validation TLS d'un résolveur chiffré (DoQ, DoT).
 *
 * Extrait de DoQClient pour être réutilisable : le testeur de latence utilisait
 * `noServerCertificateCheck()` en DoQ et un `SSLSocket` sans vérification de nom
 * en DoT. Ces mesures alimentent le classement sur lequel l'utilisateur CHOISIT
 * son résolveur — un attaquant sur le chemin pouvait donc peser sur ce choix.
 */
object TlsTrust {

    private const val T = "TlsTrust"

    /** TrustManager X509 système (CA de l'appareil), construit une fois. */
    private val systemTrustManager: X509TrustManager? by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(T, "systemTrustManager init err: ${e.message}"); null
        }
    }

    /** TrustManager qui valide la chaîne (CA système) PUIS le hostname attendu. */
    fun forHost(expectedHost: String): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client authentication is not supported by this server trust manager")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val tm = systemTrustManager ?: throw CertificateException("no system trust manager")
            // authType="UNKNOWN" et NON celui passé par kwik ("RSA") : sinon le
            // validateur exige un KeyUsage keyEncipherment, absent des certs
            // ECDSA/TLS1.3 modernes → rejet à tort. "UNKNOWN" valide la chaîne
            // (racine + expiration) sans ce contrôle spécifique. (Prouvé JVM.)
            tm.checkServerTrusted(chain, "UNKNOWN")
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            // Compense le "UNKNOWN" (qui saute le contrôle EKU) : on exige
            // explicitement l'usage serverAuth (ou aucun EKU) sur le leaf.
            val eku = leaf.extendedKeyUsage
            if (eku != null && !eku.contains("1.3.6.1.5.5.7.3.1") && !eku.contains("2.5.29.37.0"))
                throw CertificateException("cert non destiné à l'auth serveur (EKU)")
            if (!hostnameMatches(leaf, expectedHost))
                throw CertificateException("hostname mismatch for $expectedHost")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            systemTrustManager?.acceptedIssuers ?: arrayOf()
    }

    /**
     * Active la vérification du nom d'hôte sur un [SSLSocket].
     *
     * Sans cet appel, un `SSLSocket` issu de `SSLSocketFactory.getDefault()`
     * valide la CHAÎNE mais PAS le nom : n'importe quel certificat valide, émis
     * pour n'importe quel domaine, est accepté. C'est le piège Java classique.
     */
    fun enableHostnameVerification(socket: SSLSocket) {
        if (android.os.Build.VERSION.SDK_INT < 24) return // Caller also verifies the peer after handshake.
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = params
    }

    /** Vérifie que le certificat couvre `host` (SAN dNSName, sinon CN), wildcard RFC 6125. */
    fun hostnameMatches(cert: X509Certificate, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val ip = ProfileValidation.isIpv4(h) || h.contains(':')
        return try {
            cert.subjectAlternativeNames?.any { san ->
                if (san.size < 2) false
                else if (ip && (san[0] as? Int) == 7) {
                    val value = san[1] as? String
                    value != null && java.net.InetAddress.getByName(value) == java.net.InetAddress.getByName(h)
                } else if (!ip && (san[0] as? Int) == 2) {
                    val pattern = (san[1] as? String)?.lowercase()?.trimEnd('.')
                    pattern != null && matchesDnsName(h, pattern)
                } else false
            } == true
        } catch (_: Exception) { false }
    }

    private fun matchesDnsName(host: String, pattern: String): Boolean {
        if (pattern == host) return true
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(1) // ".example.com"
            val idx = host.indexOf('.')
            return idx > 0 && host.substring(idx) == suffix && !host.substring(0, idx).contains('*')
        }
        return false
    }
}
