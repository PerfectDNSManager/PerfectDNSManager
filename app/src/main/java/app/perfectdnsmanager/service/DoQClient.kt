package app.perfectdnsmanager.service

import android.net.VpnService
import android.util.Log
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.DnsWire
import app.perfectdnsmanager.util.TlsTrust
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
        private val IPV4_RE = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    }

    private val connections = ConcurrentHashMap<String, QuicClientConnection>()
    private val connLocks = ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    private val lifecycleLock = Any()
    @Volatile private var closed = false
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
        if (closed || dnsPayload.size < 12) return null
        val uri = try { URI(app.perfectdnsmanager.util.ProfileValidation.normalizeEndpoint(quicUrl)) } catch (_: Exception) { return null }
        if (!app.perfectdnsmanager.util.ProfileValidation.isValidPrimary(app.perfectdnsmanager.data.DnsType.DOQ, quicUrl)) return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        val key = "$host:$port"

        val originalId = ((dnsPayload[0].toInt() and 0xFF) shl 8) or (dnsPayload[1].toInt() and 0xFF)
        val doqPayload = dnsPayload.copyOf().also { it[0] = 0; it[1] = 0 } // ID=0 (RFC 9250)
        val wireMsgBytes = ByteBuffer.allocate(2 + doqPayload.size)
            .putShort(doqPayload.size.toShort()).put(doqPayload).array()

        val conn = getOrCreateConnection(key, host, port) ?: return null

        // Watchdog ARMÉ AVANT createStream : createStream(true) de kwik attend jusqu'à
        // ~10000 jours sur le crédit de streams (vérifié en décompil) → un serveur qui
        // n'accorde pas de crédit bloquerait à l'infini. Le conn.close() du watchdog
        // débloque createStream ET un read bloqué. `done` + remove par IDENTITÉ :
        // ne jamais tuer la connexion FRAÎCHE (re)créée par un autre thread.
        val stream = java.util.concurrent.atomic.AtomicReference<QuicStream?>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val wd = try { watchdog.schedule({
            if (done.get()) return@schedule
            try { stream.get()?.abortReading(0x3) } catch (_: Throwable) {}   // 0x3 = DOQ_REQUEST_CANCELLED
            try { stream.get()?.resetStream(0x3) } catch (_: Throwable) {}
            try { if (connections.remove(key, conn)) conn.close() } catch (_: Throwable) {}
        }, QUERY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { return null }

        return try {
            val s = conn.createStream(true); stream.set(s)
            s.outputStream.write(wireMsgBytes)
            s.outputStream.close()
            val dnsResp = DnsMessages.readFrame(s.inputStream)
            done.set(true)
            require(DnsMessages.matches(doqPayload, dnsResp)) { "Invalid DNS response" }
            dnsResp[0] = (originalId shr 8).toByte(); dnsResp[1] = originalId.toByte()
            dnsResp
        } catch (e: Exception) {
            Log.w(T, "DoQ query err: ${e.javaClass.simpleName}") // pas de $message (peut contenir l'URL/ID compte)
            try { if (connections.remove(key, conn)) conn.close() } catch (_: Exception) {} // par identité
            null
        } finally {
            done.set(true)
            wd.cancel(false)
            try { stream.get()?.inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun getOrCreateConnection(key: String, host: String, port: Int): QuicClientConnection? {
        if (closed) return null
        // Fast-path hors verrou : connexion valide déjà présente.
        connections[key]?.let { c ->
            if (c.isConnected) return c
            if (connections.remove(key, c)) try { c.close() } catch (_: Exception) {}
        }

        // Résolution bootstrap HORS du verrou (2×3s de timeout UDP) : sinon le verrou
        // serait tenu ~11s et un autre endpoint DoQ (secondaire) serait bloqué aussi.
        val resolved = resolveHostBypass(host) ?: run { Log.w(T, "Cannot resolve ${redactHost(host)}"); return null }
        val addr = resolved.address
        val dnsService = vpnService as? DnsVpnService
        if (dnsService == null || addr == null || addr.size != 4) {
            // Sans split-horizon (serveur IPv6-only, contexte inattendu) on ne peut PAS
            // valider → REFUS (fail-closed). Jamais de connexion non validée (downgrade).
            Log.w(T, "DoQ: split-horizon indisponible → refus (fail-closed): ${redactHost(host)}")
            return null
        }
        synchronized(lifecycleLock) {
            if (closed) return null
            dnsService.registerLocalDns(host, addr)
        }

        // Verrou PAR CLÉ (pas global) : un seul thread établit la connexion vers CET
        // endpoint ; les requêtes vers un autre endpoint ne sont pas bloquées. tryLock
        // fail-fast : les autres threads du même endpoint abandonnent (re-tentent → fast-path).
        val lock = connLocks.computeIfAbsent(key) { java.util.concurrent.locks.ReentrantLock() }
        if (!lock.tryLock(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return connections[key]?.takeIf { it.isConnected }
        }
        try {
            connections[key]?.let { c ->
                if (c.isConnected) return c
                if (connections.remove(key, c)) try { c.close() } catch (_: Exception) {}
            }
            // Unique chemin : serverName = hostname + validation cert. FAIL-CLOSED total.
            var candidate: QuicClientConnection? = null
            return try {
                val conn = QuicClientConnection.newBuilder()
                    .uri(URI("https://$host:$port")) // host = hostname (pas .host(ip))
                    .applicationProtocol("doq")
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .maxIdleTimeout(Duration.ofMillis(MAX_IDLE_MS))
                    .customTrustManager(TlsTrust.forHost(host))
                    .socketFactory { _ -> DatagramSocket().also { if (!vpnService.protect(it)) { it.close(); error("Socket protection failed") } } }
                    .build()
                candidate = conn
                conn.connect()
                val accepted = synchronized(lifecycleLock) {
                    if (conn.isConnected && !closed) { connections[key] = conn; true } else false
                }
                if (accepted) {
                    Log.i(T, "QUIC connecté (cert validé): ${redactHost(host)}:$port")
                    conn
                } else {
                    // !isConnected OU closeAll a eu lieu pendant le connect → on ferme
                    // (sinon on ré-insérerait une conn après le clear de closeAll = fuite).
                    try { conn.close() } catch (_: Exception) {}
                    Log.w(T, "DoQ non connecté: ${redactHost(host)}:$port"); null
                }
            } catch (e: Exception) {
                runCatching { candidate?.close() }
                if (isCertificateFailure(e))
                    Log.w(T, "DoQ: validation certificat ÉCHOUÉE (MITM possible) — refus")
                else
                    Log.w(T, "DoQ connexion KO (fail-closed): ${e.javaClass.simpleName}")
                null
            }
        } finally {
            lock.unlock()
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

    /**
     * Résoudre un hostname en bypassant le VPN via une requête DNS UDP directe.
     * Politique sans-Google : bootstrap sur Cloudflare (1.1.1.1) puis fallback
     * Quad9 (9.9.9.9). Socket connecté + ID aléatoire + réponse validée : cf.
     * [DnsWire] (l'ancienne copie locale acceptait n'importe quel datagramme
     * avec un ID de transaction constant 0x1234).
     */
    private fun resolveHostBypass(host: String): InetAddress? = try {
        if (host.matches(IPV4_RE)) {
            InetAddress.getByName(host)
        } else {
            bootstrapResolve(host, byteArrayOf(1, 1, 1, 1))
                ?: bootstrapResolve(host, byteArrayOf(9, 9, 9, 9))
        }
    } catch (e: Exception) {
        Log.w(T, "resolveHostBypass: ${e.javaClass.simpleName}")
        null
    }

    private fun bootstrapResolve(host: String, serverIp: ByteArray): InetAddress? =
        DnsWire.resolveA(InetAddress.getByAddress(serverIp), host) { sock ->
            vpnService.protect(sock)
        }

    /** Ferme toutes les connexions QUIC */
    fun closeAll() {
        val toClose = synchronized(lifecycleLock) {
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        for (conn in toClose) runCatching { conn.close() }
        try { watchdog.shutdownNow() } catch (_: Exception) {}
        Log.i(T, "All QUIC connections closed")
    }
}
