package app.perfectdnsmanager.service

import android.net.VpnService
import android.util.Log
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.QuicStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Client DNS over QUIC (DoQ, RFC 9250)
 *
 * - Pool de connexions QUIC persistantes (1 par endpoint)
 * - Format wire DoQ : 2 octets longueur + message DNS avec ID=0
 * - Chaque requête = 1 stream QUIC bidirectionnel
 * - Restauration du transaction ID original dans la réponse
 * - Timeout 5s, reconnexion auto si connexion morte
 */
class DoQClient(private val vpnService: VpnService) {

    companion object {
        private const val T = "DoQClient"
        private const val DEFAULT_PORT = 853
        private const val CONNECT_TIMEOUT_MS = 5000L
    }

    private val connections = ConcurrentHashMap<String, QuicClientConnection>()

    /**
     * Envoie une requête DNS via QUIC (DoQ).
     * @param dnsPayload le message DNS brut (avec transaction ID original)
     * @param quicUrl URL au format quic://host[:port][/path]
     * @return la réponse DNS brute (avec transaction ID restauré), ou null si erreur
     */
    fun query(dnsPayload: ByteArray, quicUrl: String): ByteArray? {
        return try {
            // 1. Parse quic://host[:port]
            val uri = URI(quicUrl.replace("quic://", "https://"))
            val host = uri.host
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            val key = "$host:$port"

            // 2. Sauvegarder le transaction ID original
            val originalId = ((dnsPayload[0].toInt() and 0xFF) shl 8) or (dnsPayload[1].toInt() and 0xFF)

            // 3. Mettre l'ID à 0 (RFC 9250 : ID MUST be 0)
            val doqPayload = dnsPayload.copyOf()
            doqPayload[0] = 0
            doqPayload[1] = 0

            // 4. Préparer le message DoQ : 2 octets longueur + payload
            val wireMsg = ByteBuffer.allocate(2 + doqPayload.size)
            wireMsg.putShort(doqPayload.size.toShort())
            wireMsg.put(doqPayload)
            val wireMsgBytes = wireMsg.array()

            // 5. Obtenir ou créer la connexion QUIC
            val conn = getOrCreateConnection(key, host, port)
                ?: return null

            // 6. Ouvrir un stream bidirectionnel, écrire, lire
            val stream: QuicStream = conn.createStream(true)
            stream.outputStream.write(wireMsgBytes)
            stream.outputStream.close()

            // Lire la réponse
            val response = readFully(stream)
            if (response == null || response.size < 14) {
                Log.w(T, "DoQ: response too short (${response?.size ?: 0} bytes)")
                return null
            }

            // 7. Retirer le préfixe 2 octets longueur
            val dnsResp = if (response.size > 2) {
                val len = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
                if (len + 2 <= response.size) {
                    response.copyOfRange(2, 2 + len)
                } else {
                    response.copyOfRange(2, response.size)
                }
            } else {
                response
            }

            // 8. Restaurer le transaction ID original
            if (dnsResp.size >= 2) {
                dnsResp[0] = (originalId shr 8).toByte()
                dnsResp[1] = (originalId and 0xFF).toByte()
            }

            dnsResp
        } catch (e: Exception) {
            Log.w(T, "DoQ query err: ${e.javaClass.simpleName}: ${e.message}")
            // Invalider la connexion en cas d'erreur
            try {
                val uri = URI(quicUrl.replace("quic://", "https://"))
                val key = "${uri.host}:${if (uri.port > 0) uri.port else DEFAULT_PORT}"
                connections.remove(key)?.close()
            } catch (_: Exception) {}
            null
        }
    }

    private fun readFully(stream: QuicStream): ByteArray? = try {
        val inputStream = stream.inputStream
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var totalRead = 0
        while (totalRead < 65535) {
            val n = inputStream.read(buf)
            if (n < 0) break
            baos.write(buf, 0, n)
            totalRead += n
        }
        baos.toByteArray()
    } catch (e: Exception) {
        Log.w(T, "readFully err: ${e.message}")
        null
    }

    private fun getOrCreateConnection(key: String, host: String, port: Int): QuicClientConnection? {
        // Vérifier si la connexion existante est encore valide
        connections[key]?.let { conn ->
            if (!conn.isConnected) {
                connections.remove(key)
                try { conn.close() } catch (_: Exception) {}
            } else {
                return conn
            }
        }

        // Créer une nouvelle connexion
        return try {
            // Résoudre le hostname en bypassant le VPN
            val resolved = resolveHostBypass(host) ?: run {
                Log.w(T, "Cannot resolve $host")
                return null
            }

            val builder = QuicClientConnection.newBuilder()
                .uri(URI("https://$host:$port"))
                .host(resolved.hostAddress)
                .port(port)
                .applicationProtocol("doq")
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                // SÉCURITÉ : on connecte à l'IP (résolue en bypass VPN) mais on
                // VALIDE le certificat contre les CA système ET le hostname réel
                // (`host`). Avant, `noServerCertificateCheck()` acceptait n'importe
                // quel certificat → MITM total du DNS chiffré possible.
                .customTrustManager(buildTrustManager(host))
                .socketFactory { _ ->
                    DatagramSocket().also { vpnService.protect(it) }
                }

            val conn = builder.build()
            conn.connect()

            if (conn.isConnected) {
                connections[key] = conn
                Log.i(T, "QUIC connected: $host:$port")
                conn
            } else {
                Log.w(T, "QUIC connect failed: $host:$port")
                null
            }
        } catch (e: Exception) {
            Log.w(T, "QUIC connect err $host:$port: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── Validation TLS du résolveur DoQ ──────────────────────────────────

    /** TrustManager X509 système (CA de l'appareil), construit une fois. */
    private val systemTrustManager: javax.net.ssl.X509TrustManager? by lazy {
        try {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(T, "systemTrustManager init err: ${e.message}"); null
        }
    }

    /** TrustManager qui valide la chaîne (CA système) PUIS le hostname attendu. */
    private fun buildTrustManager(expectedHost: String): javax.net.ssl.X509TrustManager =
        object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                val tm = systemTrustManager
                    ?: throw java.security.cert.CertificateException("no system trust manager")
                tm.checkServerTrusted(chain, authType) // valide la chaîne contre les CA système
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("empty certificate chain")
                if (!hostnameMatches(leaf, expectedHost))
                    throw java.security.cert.CertificateException("DoQ hostname mismatch for $expectedHost")
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                systemTrustManager?.acceptedIssuers ?: arrayOf()
        }

    /** Vérifie que le certificat couvre `host` (SAN dNSName, sinon CN), avec wildcard RFC 6125. */
    private fun hostnameMatches(cert: java.security.cert.X509Certificate, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        try {
            val sans = cert.subjectAlternativeNames
            if (sans != null) {
                var hadDns = false
                for (san in sans) {
                    if (san.size >= 2 && (san[0] as? Int) == 2) { // 2 = dNSName
                        hadDns = true
                        val pattern = (san[1] as? String)?.lowercase()?.trimEnd('.') ?: continue
                        if (matchesDnsName(h, pattern)) return true
                    }
                }
                if (hadDns) return false // SAN présents mais aucun ne matche → rejet (RFC 6125)
            }
        } catch (_: Exception) {}
        return try {
            val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)
                ?.groupValues?.get(1)?.lowercase()?.trimEnd('.')
            cn != null && matchesDnsName(h, cn)
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

    /**
     * Résoudre un hostname en bypassant le VPN via une requête DNS UDP directe.
     * Politique sans-Google : bootstrap sur Cloudflare (1.1.1.1) puis fallback
     * Quad9 (9.9.9.9). Aligné sur DnsVpnService.resolveHostBypass.
     */
    private fun resolveHostBypass(host: String): InetAddress? = try {
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            InetAddress.getByName(host)
        } else {
            queryBootstrapDns(host, byteArrayOf(1, 1, 1, 1))
                ?: queryBootstrapDns(host, byteArrayOf(9, 9, 9, 9))
        }
    } catch (e: Exception) {
        Log.w(T, "resolveHostBypass: ${e.message}")
        null
    }

    /** Une requête DNS UDP type A vers une IP de bootstrap, en bypass VPN. */
    private fun queryBootstrapDns(host: String, serverIp: ByteArray): InetAddress? = try {
        val query = buildDnsQuery(host)
        val sock = DatagramSocket()
        vpnService.protect(sock)
        sock.soTimeout = 3000
        val server = InetAddress.getByAddress(serverIp)
        sock.send(DatagramPacket(query, query.size, server, 53))
        val resp = ByteArray(512)
        val pkt = DatagramPacket(resp, resp.size)
        sock.receive(pkt)
        sock.close()
        parseDnsResponseIp(resp, pkt.length)
    } catch (e: Exception) {
        Log.w(T, "bootstrap DNS ${serverIp.joinToString(".") { (it.toInt() and 0xFF).toString() }} failed: ${e.message}")
        null
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(0x12); buf.write(0x34)
        buf.write(0x01); buf.write(0x00)
        buf.write(0x00); buf.write(0x01)
        buf.write(0x00); buf.write(0x00)
        buf.write(0x00); buf.write(0x00)
        buf.write(0x00); buf.write(0x00)
        for (label in host.split(".")) {
            buf.write(label.length)
            buf.write(label.toByteArray())
        }
        buf.write(0x00)
        buf.write(0x00); buf.write(0x01)
        buf.write(0x00); buf.write(0x01)
        return buf.toByteArray()
    }

    private fun parseDnsResponseIp(data: ByteArray, length: Int): InetAddress? {
        if (length < 12) return null
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (anCount == 0) return null
        var offset = 12
        while (offset < length && data[offset].toInt() != 0) {
            val len = data[offset].toInt() and 0xFF
            if (len >= 0xC0) { offset += 2; break }
            offset += len + 1
        }
        if (offset < length && data[offset].toInt() == 0) offset++
        offset += 4
        for (i in 0 until anCount) {
            if (offset >= length) break
            if ((data[offset].toInt() and 0xC0) == 0xC0) offset += 2
            else { while (offset < length && data[offset].toInt() != 0) offset++; offset++ }
            if (offset + 10 > length) break
            val rtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdlen = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10
            if (rtype == 1 && rdlen == 4 && offset + 4 <= length) {
                return InetAddress.getByAddress(data.copyOfRange(offset, offset + 4))
            }
            offset += rdlen
        }
        return null
    }

    /** Ferme toutes les connexions QUIC */
    fun closeAll() {
        for ((key, conn) in connections) {
            try { conn.close() } catch (_: Exception) {}
        }
        connections.clear()
        Log.i(T, "All QUIC connections closed")
    }
}
