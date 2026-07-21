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
        private const val MAX_IDLE_MS = 15_000L      // ferme une connexion QUIC oisive
        private const val QUERY_TIMEOUT_MS = 5000L   // deadline par requête (watchdog)
        private const val MAX_RESP_BYTES = 8192      // réponse DNS plausible (anti-oversize)
    }

    private val connections = ConcurrentHashMap<String, QuicClientConnection>()
    private val connLock = Any()
    /** Watchdog : ferme un stream/conn bloqué en lecture (kwik n'a pas de read-timeout). */
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DoQWatchdog").apply { isDaemon = true }
    }

    /** Masque le label gauche du hostname (souvent l'ID de compte NextDNS/ControlD). */
    private fun redactHost(h: String): String {
        val dot = h.indexOf('.')
        return if (dot > 0) "***" + h.substring(dot) else "***"
    }

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
            // Watchdog : kwik n'a PAS de read-timeout. Sans ça, un serveur (ou MITM)
            // qui accepte le stream sans jamais répondre bloque `read()` à l'infini →
            // les threads du pool queryExecutor se remplissent → « plus d'internet ».
            val wd = watchdog.schedule({
                try { stream.abortReading(0) } catch (_: Throwable) {}
                try { stream.resetStream(0) } catch (_: Throwable) {}
                try { connections.remove(key)?.close() } catch (_: Throwable) {}
            }, QUERY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            val response = try {
                stream.outputStream.write(wireMsgBytes)
                stream.outputStream.close()
                readFully(stream)
            } finally {
                wd.cancel(false)
                try { stream.inputStream.close() } catch (_: Exception) {}
            }
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
        while (totalRead <= MAX_RESP_BYTES) {
            val n = inputStream.read(buf)
            if (n < 0) break
            baos.write(buf, 0, n)
            totalRead += n
        }
        // Réponse DNS surdimensionnée (serveur/MITM malveillant) → rejet, sinon
        // buildPkt déborderait le champ Total Length IPv4 16 bits.
        if (totalRead > MAX_RESP_BYTES) { Log.w(T, "DoQ: réponse trop grande ($totalRead o) — rejet"); null }
        else baos.toByteArray()
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

        // Sérialise la CRÉATION (le fast-path ci-dessus reste hors verrou) : sinon
        // plusieurs threads du pool créent des connexions concurrentes vers le même
        // serveur, la dernière écrase les autres → connexions QUIC fuitées.
        synchronized(connLock) {
            connections[key]?.let { c ->
                if (c.isConnected) return c
                connections.remove(key); try { c.close() } catch (_: Exception) {}
            }

            // Pré-résoudre l'IP via bootstrap protégé (hors tunnel → pas de récursion).
            val resolved = resolveHostBypass(host) ?: run { Log.w(T, "Cannot resolve ${redactHost(host)}"); return null }

            // Split-horizon : hostname→IP dans le service VPN → kwik résout localement
            // (pas de récursion) → on passe le HOSTNAME à kwik (SNI correct) + valide le cert.
            val addr = resolved.address
            val dnsService = vpnService as? DnsVpnService
            if (dnsService == null || addr == null || addr.size != 4) {
                // Sans split-horizon (serveur IPv6-only, ou contexte inattendu) on ne
                // peut PAS valider → on REFUSE (fail-closed). Jamais de connexion non
                // validée, même en secours : ce serait un vecteur de downgrade MITM.
                Log.w(T, "DoQ: split-horizon indisponible → refus (fail-closed): ${redactHost(host)}")
                return null
            }
            dnsService.registerLocalDns(host, addr)

            // Unique chemin : serverName = hostname + validation cert. FAIL-CLOSED
            // pour TOUT échec (réseau OU cert) : un attaquant on-path pourrait sinon
            // forcer un repli non validé en coupant la 1re connexion (downgrade).
            return try {
                val conn = QuicClientConnection.newBuilder()
                    .uri(URI("https://$host:$port")) // host = hostname (pas .host(ip))
                    .applicationProtocol("doq")
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .maxIdleTimeout(Duration.ofMillis(MAX_IDLE_MS)) // borne les hangs
                    .customTrustManager(buildTrustManager(host))
                    .socketFactory { _ -> DatagramSocket().also { vpnService.protect(it) } }
                    .build()
                conn.connect()
                if (conn.isConnected) {
                    connections[key] = conn
                    Log.i(T, "QUIC connecté (cert validé): ${redactHost(host)}:$port")
                    conn
                } else {
                    try { conn.close() } catch (_: Exception) {}
                    Log.w(T, "DoQ non connecté: ${redactHost(host)}:$port"); null
                }
            } catch (e: Exception) {
                if (isCertificateFailure(e))
                    Log.w(T, "DoQ: validation certificat ÉCHOUÉE (MITM possible) — refus")
                else
                    Log.w(T, "DoQ connexion KO (fail-closed): ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Détecte un échec dû à la validation du certificat (≠ erreur réseau/timeout). */
    private fun isCertificateFailure(e: Throwable?): Boolean {
        var t = e; var depth = 0
        while (t != null && depth < 8) {
            if (t is java.security.cert.CertificateException) return true
            val n = t.javaClass.name.lowercase()
            val m = (t.message ?: "").lowercase()
            if (n.contains("certificate") || n.contains("badcertificate")) return true
            if (m.contains("certificate") || m.contains("hostname mismatch") || m.contains("bad_certificate")) return true
            t = t.cause; depth++
        }
        return false
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
                // authType="UNKNOWN" et NON celui passé par kwik ("RSA") : sinon le
                // validateur exige un KeyUsage keyEncipherment, absent des certs
                // ECDSA/TLS1.3 modernes → rejet à tort. "UNKNOWN" valide la chaîne
                // (racine + expiration) sans ce contrôle spécifique. (Prouvé JVM.)
                tm.checkServerTrusted(chain, "UNKNOWN")
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("empty certificate chain")
                // Compense le "UNKNOWN" (qui saute le contrôle EKU) : on exige
                // explicitement l'usage serverAuth (ou aucun EKU) sur le leaf.
                val eku = try { leaf.extendedKeyUsage } catch (_: Exception) { null }
                if (eku != null && !eku.contains("1.3.6.1.5.5.7.3.1") && !eku.contains("2.5.29.37.0"))
                    throw java.security.cert.CertificateException("cert non destiné à l'auth serveur (EKU)")
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
        for ((_, conn) in connections) {
            try { conn.close() } catch (_: Exception) {}
        }
        connections.clear()
        try { watchdog.shutdownNow() } catch (_: Exception) {}
        Log.i(T, "All QUIC connections closed")
    }
}
