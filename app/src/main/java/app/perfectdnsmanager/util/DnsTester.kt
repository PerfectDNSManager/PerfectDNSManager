package app.perfectdnsmanager.util

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object DnsTester {

    private const val TAG = "DnsTester"

    data class DnsResult(val ip: String, val isBlocked: Boolean)

    private val rng = java.security.SecureRandom()
    fun execute(server: String, domain: String): DnsResult? = try {
        DnsWire.resolveA(InetAddress.getByName(server), domain, 5000)?.hostAddress?.let {
            DnsResult(it, it.startsWith("127.") || it == "0.0.0.0" || it == "54.246.190.12")
        }
    } catch (_: Exception) { null }

    private fun buildQuery(domain: String): ByteBuffer = ByteBuffer.wrap(DnsWire.buildQuery(domain, rng.nextInt(65536)))

    fun measureLatency(server: String, domain: String = "google.com"): Long? = try {
        val query = buildQuery(domain).array()
        val start = System.nanoTime()
        val result = DnsWire.exchange(InetAddress.getByName(server), query)
        if (result != null) (System.nanoTime() - start) / 1_000_000 else null
    } catch (_: Exception) { null }

    /** Client HTTP réutilisable pour les tests DoH (évite le coût TCP+TLS à chaque test) */
    private val dohClient: OkHttpClient by lazy {
        Http.withTimeouts(connectSec = 5, readSec = 5, writeSec = 5)
    }

    /**
     * Mesure la latence d'un serveur DNS over HTTPS (DoH).
     * @param client client HTTP à utiliser (réutiliser pour bénéficier du pool de connexions)
     * @return latence en millisecondes, ou null si erreur
     */
    fun measureDohLatency(url: String, domain: String = "google.com", client: OkHttpClient = dohClient): Long? {
        return try {
            val queryBuffer = buildQuery(domain)
            val queryBytes = queryBuffer.array().copyOf(queryBuffer.limit())
            val body = queryBytes.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/dns-message")
                .build()

            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                val bytes = response.body?.byteStream()?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1024)
                    while (out.size() <= DnsMessages.MAX_BYTES) {
                        val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                if (response.isSuccessful && bytes != null && DnsMessages.matches(queryBytes, bytes)) elapsed else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH latency test failed for ${redactDnsUrl(url)} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Mesure la latence d'un serveur DNS over QUIC (DoQ).
     * Connexion éphémère (pas de pool).
     * @return latence en millisecondes, ou null si erreur
     */
    fun measureDoqLatency(url: String, domain: String = "google.com"): Long? {
        return try {
            if (!ProfileValidation.isValidPrimary(app.perfectdnsmanager.data.DnsType.DOQ, url)) return null
            val uri = java.net.URI(ProfileValidation.normalizeEndpoint(url))
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 853

            // Pré-contrôle de joignabilité (échec rapide si le nom ne résout pas).
            // On ne passe PAS l'IP à kwik : sa classe de connexion n'a qu'UN champ
            // `host`, qui sert à la fois de serverName TLS et d'adresse. Lui donner
            // l'IP casserait la vérification de nom du certificat — c'est
            // exactement ce qui avait fait échouer le fix DoQ de la v2.1.1.
            InetAddress.getByName(host)

            val queryBuffer = buildQuery(domain)
            val queryBytes = queryBuffer.array().copyOf(queryBuffer.limit())

            // Mettre l'ID à 0 (RFC 9250)
            queryBytes[0] = 0; queryBytes[1] = 0

            // Préparer le message DoQ : 2 octets longueur + payload
            val wireMsg = java.nio.ByteBuffer.allocate(2 + queryBytes.size)
            wireMsg.putShort(queryBytes.size.toShort())
            wireMsg.put(queryBytes)
            val wireMsgBytes = wireMsg.array()

            val conn = tech.kwik.core.QuicClientConnection.newBuilder()
                .uri(java.net.URI("https://$host:$port"))
                .applicationProtocol("doq")
                .connectTimeout(java.time.Duration.ofMillis(5000))
                .maxIdleTimeout(java.time.Duration.ofSeconds(5)) // évite un read qui gèle le speedtest
                // Le certificat est VALIDÉ, comme dans DoQClient : ces latences
                // alimentent le classement sur lequel l'utilisateur choisit son
                // résolveur, donc un MITM ne doit pas pouvoir peser dessus.
                .customTrustManager(TlsTrust.forHost(host))
                .build()

            val timer = java.util.Timer(true)
            timer.schedule(object : java.util.TimerTask() { override fun run() { runCatching { conn.close() } } }, 10_000L)
            try {
                val start = System.currentTimeMillis()
                conn.connect()
                val stream = conn.createStream(true)
                stream.outputStream.write(wireMsgBytes)
                stream.outputStream.close()
                val response = DnsMessages.readFrame(stream.inputStream)
                val elapsed = System.currentTimeMillis() - start
                if (DnsMessages.matches(queryBytes, response)) elapsed else null
            } finally {
                timer.cancel()
                try { conn.close() } catch (_: Exception) {} // finally → pas de fuite sur exception
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoQ latency test failed for ${redactDnsUrl(url)} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Mesure la latence d'un serveur DNS over TLS (DoT, port 853).
     * Connexion TLS éphémère.
     * @return latence en millisecondes, ou null si erreur
     */
    fun measureDotLatency(hostname: String, domain: String = "google.com"): Long? {
        return try {
            val queryBuffer = buildQuery(domain)
            val queryBytes = queryBuffer.array().copyOf(queryBuffer.limit())

            // DoT : 2 octets longueur + payload DNS (RFC 7858)
            val wireMsg = java.nio.ByteBuffer.allocate(2 + queryBytes.size)
            wireMsg.putShort(queryBytes.size.toShort())
            wireMsg.put(queryBytes)
            val wireMsgBytes = wireMsg.array()

            val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val start = System.currentTimeMillis()
            java.net.Socket().use { raw ->
            raw.connect(java.net.InetSocketAddress(hostname, 853), 5000)
            (sslFactory.createSocket(raw, hostname, 853, true) as javax.net.ssl.SSLSocket).use { socket ->
                socket.soTimeout = 5000
                // Sans ceci, la chaîne est validée mais PAS le nom : n'importe
                // quel certificat valide pour n'importe quel domaine passerait.
                TlsTrust.enableHostnameVerification(socket)
                socket.startHandshake()
                require(TlsTrust.hostnameMatches(socket.session.peerCertificates[0] as java.security.cert.X509Certificate, hostname)) { "TLS hostname mismatch" }
                socket.outputStream.write(wireMsgBytes)
                socket.outputStream.flush()
                val resp = DnsMessages.readFrame(socket.inputStream)
                val elapsed = System.currentTimeMillis() - start
                if (DnsMessages.matches(queryBytes, resp)) elapsed else null
            }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoT latency test failed for ${redactDnsUrl(hostname)} (${e.javaClass.simpleName})")
            null
        }
    }

}
