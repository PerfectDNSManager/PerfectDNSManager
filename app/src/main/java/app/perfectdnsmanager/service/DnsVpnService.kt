package app.perfectdnsmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import app.perfectdnsmanager.MainActivity
import app.perfectdnsmanager.R
import app.perfectdnsmanager.data.DnsRewriteRepository
import app.perfectdnsmanager.data.DnsRewriteRule
import app.perfectdnsmanager.util.redactDnsUrl
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * VPN DNS Proxy v34
 *
 * v34 : DoH via OkHttp (HTTP/2), sockets protégés via protect()
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsServer: String = "1.1.1.1"
    private var dnsServerSecondary: String? = null
    @Volatile private var isRunning = false
    private var tunReaderThread: Thread? = null
    private var dnsReceiverThread: Thread? = null
    private var tunOut: FileOutputStream? = null
    private val tunOutLock = Any()

    private var dnsSocket: DatagramSocket? = null
    private var doqClient: DoQClient? = null
    private val upstreamMap = ConcurrentHashMap<String, String>()

    /**
     * Pool BORNÉ pour les requêtes DoH/DoQ. Avant, chaque requête DNS créait
     * un `Thread` brut : sous charge (apps bavardes, retries), le nombre de
     * threads + connexions TLS explosait → épuisement mémoire/FD → plus aucune
     * réponse DNS écrite → « plus d'internet ». Un pool fixe borne la
     * concurrence et recycle les threads.
     */
    private var queryExecutor: java.util.concurrent.ThreadPoolExecutor? = null

    /** Compteur pour attribuer un ID de transaction UNIQUE aux requêtes UDP
     *  sortantes (évite les collisions sur l'ID 16 bits du client → réponses
     *  mal routées) et permettre la validation source. */
    private val txnCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** IPs upstream autorisées à répondre sur le socket UDP (anti-spoofing). */
    private val udpUpstreamIps = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Purge périodique de `pending` (pas à chaque paquet — chemin chaud). */
    private val cleanupCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Split-horizon DNS : hostname (lowercase) → IPv4 (4 octets) du/des serveur(s)
     * DoQ, pré-résolus via bootstrap protégé. Quand kwik résout le hostname du
     * serveur DoQ, la requête tombe ici (onTunPacket) et on répond DIRECTEMENT
     * depuis cette map au lieu de la forwarder → pas de récursion → kwik peut
     * valider le certificat contre le hostname (SNI correct). Cf. DoQClient.
     */
    private val localDnsMap = ConcurrentHashMap<String, ByteArray>()

    /** Enregistre une résolution locale (appelé par DoQClient avant de connecter). */
    fun registerLocalDns(hostname: String, ipv4: ByteArray) {
        if (ipv4.size == 4) localDnsMap[hostname.lowercase()] = ipv4
    }

    /** OkHttpClient with protected sockets (bypass VPN) and custom DNS resolver */
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().also { protect(it) }
                override fun createSocket(host: String, port: Int): Socket =
                    Socket(host, port).also { protect(it) }
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                    Socket(host, port, localHost, localPort).also { protect(it) }
                override fun createSocket(host: InetAddress, port: Int): Socket =
                    Socket(host, port).also { protect(it) }
                override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                    Socket(host, port, localAddress, localPort).also { protect(it) }
            })
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val resolved = resolveHostBypass(hostname)
                        ?: throw java.net.UnknownHostException("Cannot resolve $hostname")
                    return listOf(resolved)
                }
            })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // DNS Rewrite
    private var rewriteRules = listOf<DnsRewriteRule>()

    // Pending: on stocke aussi le qname original encodé pour restaurer la réponse si rewrite.
    // origId = ID de transaction ORIGINAL du client (pour le restaurer sur le chemin UDP
    // où l'ID sortant est réécrit en ID unique).
    data class Pending(
        val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int,
        val time: Long, val wasRewritten: Boolean, val originalQnameEncoded: ByteArray?,
        val origId: Int = 0
    )
    // Clé = ID de transaction UNIQUE (uniquement pour le chemin UDP/Do53).
    // DoH/DoQ n'utilisent plus cette map (réponse traitée dans la tâche même).
    private val pending = ConcurrentHashMap<Int, Pending>()

    companion object {
        const val ACTION_START = "app.perfectdnsmanager.START_VPN"
        const val ACTION_STOP = "app.perfectdnsmanager.STOP_VPN"
        const val ACTION_RESTART = "app.perfectdnsmanager.RESTART_VPN"
        const val ACTION_RELOAD_RULES = "app.perfectdnsmanager.RELOAD_RULES"
        const val EXTRA_DNS_PRIMARY = "dns_primary"
        const val EXTRA_DNS_SECONDARY = "dns_secondary"
        private const val CH_ID = "dns_vpn_channel"
        private const val NOTIF_ID = 1001
        private const val T = "DnsVPN"
        @Volatile var isVpnRunning = false; private set

        /** Instance statique pour accéder à protect() depuis l'extérieur */
        @Volatile var instance: DnsVpnService? = null; private set

        /** Protège un DatagramSocket pour qu'il bypass le VPN tunnel */
        fun protectSocket(socket: java.net.DatagramSocket): Boolean {
            return instance?.protect(socket) ?: false
        }

        /** Map of IP-based DoH endpoints to their correct TLS/SNI hostname */
        private val DOH_SNI_MAP = mapOf(
            "9.9.9.9" to "dns.quad9.net",
            "9.9.9.10" to "dns.quad9.net",
            "9.9.9.11" to "dns.quad9.net",
            "9.9.9.12" to "dns.quad9.net",
            "149.112.112.112" to "dns.quad9.net",
            "149.112.112.9" to "dns.quad9.net",
            "149.112.112.10" to "dns.quad9.net",
            "149.112.112.11" to "dns.quad9.net",
            "149.112.112.12" to "dns.quad9.net",
            "1.1.1.1" to "cloudflare-dns.com",
            "1.0.0.1" to "cloudflare-dns.com",
            "1.1.1.2" to "cloudflare-dns.com",
            "1.0.0.2" to "cloudflare-dns.com",
            "1.1.1.3" to "cloudflare-dns.com",
            "1.0.0.3" to "cloudflare-dns.com",
            "8.8.8.8" to "dns.google",
            "8.8.4.4" to "dns.google"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v31: startForeground IMMÉDIATEMENT pour éviter crash ANR au boot
        startForeground(NOTIF_ID, mkNotif(getString(R.string.notif_starting)))
        when (intent?.action) {
            ACTION_START -> {
                dnsServer = intent.getStringExtra(EXTRA_DNS_PRIMARY) ?: "1.1.1.1"
                dnsServerSecondary = intent.getStringExtra(EXTRA_DNS_SECONDARY)
                stopVpn(); startVpn()
            }
            ACTION_RESTART -> {
                // Changement de profil : stop visible (icône VPN disparaît) puis restart après délai
                dnsServer = intent.getStringExtra(EXTRA_DNS_PRIMARY) ?: "1.1.1.1"
                dnsServerSecondary = intent.getStringExtra(EXTRA_DNS_SECONDARY)
                stopVpn()
                android.os.Handler(mainLooper).postDelayed({ startVpn() }, 600)
            }
            ACTION_STOP -> { stopVpn(); stopSelf() }
            ACTION_RELOAD_RULES -> {
                rewriteRules = DnsRewriteRepository(this).getAllRules().filter { it.isEnabled }
                Log.i(T, "Reloaded ${rewriteRules.size} DNS rewrite rules.")
            }
            else -> {
                // Always-on VPN (system-initiated) ou intent sans action
                // Charger le profil sauvegardé depuis les SharedPreferences
                if (!isRunning) {
                    val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    val profileJson = prefs.getString("selected_profile_json", null)
                    if (profileJson != null) {
                        try {
                            val profile = com.google.gson.Gson().fromJson(
                                profileJson,
                                app.perfectdnsmanager.data.DnsProfile::class.java
                            )
                            dnsServer = profile.primary
                            dnsServerSecondary = profile.secondary
                            Log.i(T, "Always-on/system start: ${profile.providerName} - ${redactDnsUrl(profile.primary)}")
                            stopVpn(); startVpn()
                        } catch (e: Exception) {
                            Log.e(T, "Failed to parse saved profile: ${e.message}")
                            stopSelf()
                        }
                    } else {
                        Log.w(T, "System started VPN but no saved profile found")
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun isDoH(s: String) = s.startsWith("https://")
    private fun isDoQ(s: String) = s.startsWith("quic://")

    private fun startVpn() {
        try {
            Log.i(T, "=== START VPN ===  primary=${redactDnsUrl(dnsServer)}  secondary=${redactDnsUrl(dnsServerSecondary)}")

            // Load rewrite rules
            rewriteRules = DnsRewriteRepository(this).getAllRules().filter { it.isEnabled }
            Log.i(T, "Loaded ${rewriteRules.size} DNS rewrite rules.")

            val builder = Builder()
                .setSession("Perfect DNS Manager")
                .setMtu(1500)
                .addAddress("192.168.50.1", 24)
                .setBlocking(true)

            val disableIpv6 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("disable_ipv6", false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.allowBypass()
                try { builder.allowFamily(OsConstants.AF_INET) } catch (_: Exception) {}
                // Pour bloquer IPv6, il faut aussi capturer la famille AF_INET6
                if (disableIpv6) {
                    try { builder.allowFamily(OsConstants.AF_INET6) } catch (_: Exception) {}
                }

                // Split tunneling : exclure certaines apps du VPN
                val excludedAppsJson = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .getString("excluded_apps_json", null)
                if (!excludedAppsJson.isNullOrEmpty()) {
                    try {
                        val arr = org.json.JSONArray(excludedAppsJson)
                        for (i in 0 until arr.length()) {
                            val pkg = arr.getString(i)
                            try {
                                builder.addDisallowedApplication(pkg)
                                Log.i(T, "Split tunnel: excluded $pkg")
                            } catch (e: Exception) {
                                Log.w(T, "Split tunnel: cannot exclude $pkg: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(T, "Split tunnel parse error: ${e.message}")
                    }
                }
            }

            // IPv6 disable : capturer tout le trafic IPv6 dans le VPN (qui ne le transmet pas)
            if (disableIpv6) {
                try {
                    builder.addAddress("fdfe:dcba:9876::1", 126)
                    builder.addRoute("::", 0)
                    Log.i(T, "IPv6 DISABLED (route ::/0 + AF_INET6)")
                } catch (e: Exception) { Log.w(T, "IPv6 block err: ${e.message}") }
            }

            upstreamMap.clear()
            val a1 = "192.0.2.2"
            upstreamMap[a1] = dnsServer
            builder.addDnsServer(a1)
            builder.addRoute(a1, 32)
            if (!dnsServerSecondary.isNullOrEmpty()) {
                val a2 = "192.0.2.3"
                upstreamMap[a2] = dnsServerSecondary!!
                builder.addDnsServer(a2)
                builder.addRoute(a2, 32)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.w(T, "establish() null, retry in 3s...")
                Thread.sleep(3000)
                vpnInterface = builder.establish()
            }
            if (vpnInterface == null) {
                Log.e(T, "establish() still null - VPN consent not granted")
                sendVpnPermissionNeededNotification()
                stopSelf()
                return
            }
            dnsSocket = DatagramSocket().also { protect(it) }
            doqClient = DoQClient(this)
            tunOut = FileOutputStream(vpnInterface!!.fileDescriptor)
            // Pool borné : 16 requêtes upstream simultanées max, file de 512. Si
            // saturée (flood DNS extrême), DiscardOldestPolicy jette la requête la
            // plus ANCIENNE en attente (le stub client re-tentera après timeout) —
            // préférable à CallerRuns qui bloquerait le TunReader.
            queryExecutor = java.util.concurrent.ThreadPoolExecutor(
                4, 16, 30, TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue(512),
                java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
            )
            udpUpstreamIps.clear()
            isRunning = true; isVpnRunning = true; instance = this

            tunReaderThread = Thread({
                val input = FileInputStream(vpnInterface!!.fileDescriptor)
                val buf = ByteArray(32767)
                while (isRunning) {
                    try {
                        val n = input.read(buf)
                        if (n > 0) onTunPacket(buf.copyOf(n))
                        else if (n < 0) break
                    } catch (e: Exception) {
                        if (isRunning) Log.e(T, "TunReader err", e)
                        break
                    }
                }
                if (isRunning) { stopVpn(); stopSelf() }
            }, "TunReader")

            dnsReceiverThread = Thread({
                val rbuf = ByteArray(4096)
                while (isRunning) {
                    try {
                        val pkt = DatagramPacket(rbuf, rbuf.size)
                        dnsSocket?.soTimeout = 1000
                        dnsSocket?.receive(pkt)
                        // Anti-spoofing : n'accepter que les réponses venant d'une IP upstream connue.
                        val srcAddr = pkt.address?.hostAddress
                        if (pkt.length > 12 && srcAddr != null && udpUpstreamIps.contains(srcAddr)) {
                            onDnsResponse(rbuf.copyOf(pkt.length))
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isRunning) Log.e(T, "DnsRecv err", e)
                        break
                    }
                }
                if (isRunning) { stopVpn(); stopSelf() }
            }, "DnsReceiver")

            tunReaderThread!!.start()
            dnsReceiverThread!!.start()
            // Mettre à jour la notification avec le vrai DNS (startForeground déjà appelé dans onStartCommand)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, mkNotif("DNS: ${redactDnsUrl(dnsServer)}"))
        } catch (e: Exception) {
            Log.e(T, "Start err", e); stopVpn(); stopSelf()
        }
    }

    // ── Traitement paquet TUN → forward DNS ───────────────────────────────

    private fun onTunPacket(buf: ByteArray) {
        try {
            // IPv4 only
            if ((buf[0].toInt() and 0xF0) shr 4 != 4) return
            val ihl = (buf[0].toInt() and 0x0F) * 4
            // Header IPv4 minimal = 20 octets. Sans ce garde, un paquet malformé
            // (ihl < 20) fait planter copyOfRange(12,16)/(16,20) → l'exception
            // remonte, tue le TunReader et ARRÊTE le VPN (« plus d'internet »).
            if (ihl < 20 || buf.size < ihl + 8) return
            if (buf[9].toInt() and 0xFF != 17) return
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) return

            val srcIp = buf.copyOfRange(12, 16)
            val dstIp = buf.copyOfRange(16, 20)
            val srcPort = (buf[ihl].toInt() and 0xFF) shl 8 or (buf[ihl + 1].toInt() and 0xFF)
            val real = upstreamMap[ipStr(dstIp)] ?: return

            val off = ihl + 8
            if (buf.size - off < 12) return
            var query = buf.copyOfRange(off, buf.size)
            val origId = (query[0].toInt() and 0xFF) shl 8 or (query[1].toInt() and 0xFF)

            // Rewrite check
            var wasRewritten = false
            var originalQnameEncoded: ByteArray? = null
            val (qname, modifiedQuery) = getQNameAndApplyRewrite(query)

            // Split-horizon : si la requête vise le hostname d'un serveur DoQ
            // (pré-résolu via bootstrap), on répond localement au lieu de la
            // forwarder → kwik obtient l'IP sans récursion et valide le cert.
            if (localDnsMap.isNotEmpty()) {
                val ip = localDnsMap[qname.lowercase()]
                if (ip != null) {
                    val resp = synthesizeDnsResponse(query, ip)
                    writeTun(Pending(srcIp, dstIp, srcPort, System.currentTimeMillis(), false, null, origId), resp)
                    return
                }
            }

            if (modifiedQuery != null) {
                // (sécurité) ne PAS logger le domaine interrogé
                Log.i(T, "DNS Rewrite rule matched")
                originalQnameEncoded = encodeQName(qname)
                query = modifiedQuery
                wasRewritten = true
            }

            val p = Pending(srcIp, dstIp, srcPort, System.currentTimeMillis(), wasRewritten, originalQnameEncoded, origId)

            when {
                // DoH/DoQ : la réponse est traitée dans la tâche même (closure sur `p`),
                // plus besoin de la map `pending` → pas de collision d'ID possible.
                isDoH(real) -> {
                    val q = query
                    submitQuery { val resp = doH(q, real); if (resp != null) writeTun(p, resp) }
                }
                isDoQ(real) -> {
                    val q = query
                    submitQuery { val resp = doqClient?.query(q, real); if (resp != null) writeTun(p, resp) }
                }
                else -> {
                    // Chemin UDP/Do53 : ID de transaction UNIQUE côté sortie (évite
                    // que deux apps avec le même ID s'écrasent) + IP upstream
                    // enregistrée pour valider la source de la réponse.
                    try {
                        val upstreamAddr = InetAddress.getByName(real)
                        upstreamAddr.hostAddress?.let { udpUpstreamIps.add(it) }
                        val uniqueId = txnCounter.incrementAndGet() and 0xFFFF
                        query[0] = (uniqueId shr 8).toByte()
                        query[1] = (uniqueId and 0xFF).toByte()
                        pending[uniqueId] = p
                        dnsSocket?.send(DatagramPacket(query, query.size, upstreamAddr, 53))
                    } catch (e: Exception) { Log.w(T, "UDP send: ${e.message}") }
                }
            }

            // Cleanup périodique (1 fois sur 256) au lieu d'un scan complet par paquet.
            if (cleanupCounter.incrementAndGet() and 0xFF == 0) {
                val now = System.currentTimeMillis()
                pending.entries.removeAll { now - it.value.time > 10_000 }
            }
        } catch (e: Exception) {
            if (isRunning) Log.w(T, "onTunPacket err: ${e.message}")
        }
    }

    /** Soumet une requête upstream au pool borné (jamais un Thread brut). */
    private fun submitQuery(task: () -> Unit) {
        try {
            queryExecutor?.execute {
                try { task() } catch (e: Exception) { Log.w(T, "query task err: ${e.message}") }
            }
        } catch (e: Exception) { Log.w(T, "submitQuery rejected: ${e.message}") }
    }

    private fun onDnsResponse(resp: ByteArray) {
        val uniqueId = (resp[0].toInt() and 0xFF) shl 8 or (resp[1].toInt() and 0xFF)
        val p = pending.remove(uniqueId) ?: return
        // Restaurer l'ID de transaction original attendu par le client.
        resp[0] = (p.origId shr 8).toByte()
        resp[1] = (p.origId and 0xFF).toByte()
        writeTun(p, resp)
    }

    // ── Rewrite : restaurer le qname original dans la réponse ─────────────

    private fun writeTun(p: Pending, payload: ByteArray) {
        val finalPayload = if (p.wasRewritten && p.originalQnameEncoded != null) {
            restoreOriginalQName(payload, p.originalQnameEncoded)
        } else {
            payload
        }
        val pkt = buildPkt(p.dstIp, p.srcIp, 53, p.srcPort, finalPayload)
        try {
            synchronized(tunOutLock) { if (isRunning) tunOut?.write(pkt) }
        } catch (e: Exception) { Log.w(T, "TUN write: ${e.message}") }
    }

    /**
     * Remplace le qname dans la réponse DNS par le qname original.
     * La réponse du serveur contient le domaine réécrit, mais le client
     * s'attend au domaine qu'il a demandé à l'origine.
     */
    private fun restoreOriginalQName(response: ByteArray, originalQnameEncoded: ByteArray): ByteArray {
        try {
            // Trouver la fin du qname dans la réponse (section question, offset 12)
            val rewrittenQnameLen = getQNameLength(response, 12)
            // +4 pour QTYPE + QCLASS
            val afterQuestion = 12 + rewrittenQnameLen + 4
            if (afterQuestion > response.size) return response

            // Reconstruire : header + original qname + QTYPE/QCLASS + reste de la réponse
            val header = response.copyOfRange(0, 12)
            val qtypeClass = response.copyOfRange(12 + rewrittenQnameLen, afterQuestion)
            val rest = if (afterQuestion < response.size) response.copyOfRange(afterQuestion, response.size) else byteArrayOf()

            val result = ByteBuffer.allocate(header.size + originalQnameEncoded.size + qtypeClass.size + rest.size)
            result.put(header)
            result.put(originalQnameEncoded)
            result.put(qtypeClass)
            result.put(rest)
            return result.array()
        } catch (e: Exception) {
            Log.w(T, "restoreOriginalQName err: ${e.message}")
            return response
        }
    }

    // ── DNS Rewrite helpers ───────────────────────────────────────────────

    private fun getQNameAndApplyRewrite(query: ByteArray): Pair<String, ByteArray?> {
        val buffer = ByteBuffer.wrap(query).asReadOnlyBuffer()
        buffer.position(12)
        val qname = parseQName(buffer)
        rewriteRules.find { it.fromDomain.equals(qname, ignoreCase = true) }?.let {
            return Pair(qname, buildNewQuery(query, it.toDomain))
        }
        return Pair(qname, null)
    }

    private fun parseQName(buffer: ByteBuffer): String {
        val sb = StringBuilder()
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt()
            if (len == 0) break
            if (len < 0 || len > 63) return "<invalid>"
            val bytes = ByteArray(len)
            buffer.get(bytes)
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(bytes))
        }
        return sb.toString()
    }

    private fun getQNameLength(data: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < data.size && data[pos].toInt() != 0) {
            val labelLen = data[pos].toInt() and 0xFF
            if (labelLen and 0xC0 == 0xC0) return pos - offset + 2 // compression pointer
            pos += labelLen + 1
        }
        return pos - offset + 1 // +1 pour le 0x00 terminal
    }

    /** Encode un nom de domaine en wire format DNS (labels + 0x00) */
    private fun encodeQName(domain: String): ByteArray {
        val buf = ByteBuffer.allocate(256)
        domain.split(".").forEach { label ->
            buf.put(label.length.toByte())
            buf.put(label.toByteArray())
        }
        buf.put(0.toByte())
        return buf.array().copyOf(buf.position())
    }

    private fun buildNewQuery(originalQuery: ByteArray, newDomain: String): ByteArray {
        val header = originalQuery.copyOfRange(0, 12)
        val origQnameLen = getQNameLength(originalQuery, 12)
        val qtypeClass = originalQuery.copyOfRange(12 + origQnameLen, 12 + origQnameLen + 4)

        val buffer = ByteBuffer.allocate(512)
        buffer.put(header)
        newDomain.split(".").forEach { label ->
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray())
        }
        buffer.put(0.toByte())
        buffer.put(qtypeClass)
        return buffer.array().copyOf(buffer.position())
    }

    /** Construit une réponse DNS locale : A→IP pour un type A, NODATA sinon. */
    private fun synthesizeDnsResponse(query: ByteArray, ipv4: ByteArray): ByteArray {
        val qnameLen = getQNameLength(query, 12)
        val qEnd = 12 + qnameLen + 4 // fin de la section question
        val qtype = if (qEnd <= query.size)
            ((query[12 + qnameLen].toInt() and 0xFF) shl 8) or (query[12 + qnameLen + 1].toInt() and 0xFF)
        else 0
        val isA = qtype == 1 && ipv4.size == 4
        val out = java.io.ByteArrayOutputStream()
        // Header
        out.write(query[0].toInt() and 0xFF); out.write(query[1].toInt() and 0xFF) // ID
        out.write(0x81); out.write(0x80)                        // QR=1, RD=1, RA=1
        out.write(0x00); out.write(0x01)                        // QDCOUNT=1
        out.write(0x00); out.write(if (isA) 0x01 else 0x00)    // ANCOUNT
        out.write(0x00); out.write(0x00)                        // NSCOUNT
        out.write(0x00); out.write(0x00)                        // ARCOUNT
        // Question recopiée
        val qLen = (qEnd - 12).coerceAtMost(query.size - 12)
        if (qLen > 0) out.write(query, 12, qLen)
        // Réponse (type A uniquement ; sinon NODATA = pas de section answer)
        if (isA) {
            out.write(0xC0); out.write(0x0C)                    // pointeur vers qname (offset 12)
            out.write(0x00); out.write(0x01)                    // TYPE A
            out.write(0x00); out.write(0x01)                    // CLASS IN
            out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C) // TTL 60s
            out.write(0x00); out.write(0x04)                    // RDLENGTH 4
            out.write(ipv4, 0, 4)                               // RDATA
        }
        return out.toByteArray()
    }

    // ── DoH via OkHttp (HTTP/2) ─────────────────────────────────────────

    private fun doH(q: ByteArray, url: String): ByteArray? = try {
        // For IP-based URLs (e.g. https://9.9.9.9/dns-query), rewrite to hostname for TLS/SNI
        val finalUrl = run {
            val parsed = java.net.URL(url)
            val host = parsed.host
            val isIpHost = host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
            if (isIpHost) {
                val tlsHost = DOH_SNI_MAP[host] ?: host
                val path = if (parsed.path.isNullOrEmpty()) "/dns-query" else parsed.path
                val port = if (parsed.port > 0) ":${parsed.port}" else ""
                "https://$tlsHost$port$path"
            } else url
        }

        val body = q.toRequestBody("application/dns-message".toMediaType())
        val request = Request.Builder()
            .url(finalUrl)
            .post(body)
            .header("Accept", "application/dns-message")
            .build()

        // .use{} garantit la fermeture de la connexion MÊME si .bytes() throw
        // (timeout/reset sous charge). Sans ça, chaque erreur fuit un FD → à
        // terme épuisement des descripteurs → plus aucune résolution DoH.
        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.bytes()
            if (response.isSuccessful && responseBody != null && responseBody.size >= 12) {
                responseBody
            } else {
                Log.w(T, "DoH: HTTP ${response.code} body=${responseBody?.size ?: 0}")
                null
            }
        }
    } catch (e: Exception) { Log.w(T, "DoH err: ${e.javaClass.simpleName}: ${e.message}"); null }

    /**
     * Résoudre un hostname en bypassant le VPN (requête DNS directe UDP).
     * Bootstrap DNS sans Google : Cloudflare 1.1.1.1 d'abord, puis Quad9 9.9.9.9
     * en fallback. Cohérent avec une app de DNS privé : on n'envoie pas le
     * hostname amorce à Google.
     */
    private fun resolveHostBypass(host: String): InetAddress? = try {
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            InetAddress.getByName(host)
        } else {
            queryBootstrapDns(host, byteArrayOf(1, 1, 1, 1))
                ?: queryBootstrapDns(host, byteArrayOf(9, 9, 9, 9))
        }
    } catch (e: Exception) { Log.w(T, "resolveHostBypass: ${e.message}"); null }

    /** Une requête DNS UDP vers une IP de bootstrap, VPN bypass. */
    private fun queryBootstrapDns(host: String, serverIp: ByteArray): InetAddress? = try {
        val query = buildDnsQuery(host)
        DatagramSocket().use { sock ->
            protect(sock)
            sock.soTimeout = 3000
            val server = InetAddress.getByAddress(serverIp)
            sock.send(DatagramPacket(query, query.size, server, 53))
            val resp = ByteArray(512)
            val pkt = DatagramPacket(resp, resp.size)
            sock.receive(pkt)
            parseDnsResponseIp(resp, pkt.length)
        }
    } catch (e: Exception) {
        Log.w(T, "bootstrap DNS ${serverIp.joinToString(".") { (it.toInt() and 0xFF).toString() }} failed: ${e.message}")
        null
    }

    /** Construire une requête DNS type A pour un hostname */
    private fun buildDnsQuery(host: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        // Transaction ID
        buf.write(0x12); buf.write(0x34)
        // Flags: standard query
        buf.write(0x01); buf.write(0x00)
        // Questions: 1
        buf.write(0x00); buf.write(0x01)
        // Answer, Authority, Additional: 0
        buf.write(0x00); buf.write(0x00)
        buf.write(0x00); buf.write(0x00)
        buf.write(0x00); buf.write(0x00)
        // QNAME
        for (label in host.split(".")) {
            buf.write(label.length)
            buf.write(label.toByteArray())
        }
        buf.write(0x00) // end
        // QTYPE: A (1)
        buf.write(0x00); buf.write(0x01)
        // QCLASS: IN (1)
        buf.write(0x00); buf.write(0x01)
        return buf.toByteArray()
    }

    /** Parser une réponse DNS pour extraire la première adresse IPv4 */
    private fun parseDnsResponseIp(data: ByteArray, length: Int): InetAddress? {
        if (length < 12) return null
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (anCount == 0) return null
        // Skip question section
        var offset = 12
        while (offset < length && data[offset].toInt() != 0) {
            val len = data[offset].toInt() and 0xFF
            if (len >= 0xC0) { offset += 2; break } // compression pointer
            offset += len + 1
        }
        if (offset < length && data[offset].toInt() == 0) offset++ // null terminator
        offset += 4 // QTYPE + QCLASS
        // Parse answer records
        for (i in 0 until anCount) {
            if (offset >= length) break
            // Name (may be compressed)
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


    // ── Utilitaires réseau ────────────────────────────────────────────────

    private fun ipStr(b: ByteArray) =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    private fun buildPkt(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
        val totalLen = 20 + 8 + data.size
        val p = ByteArray(totalLen)
        // IPv4 header
        p[0] = 0x45.toByte()
        p[2] = (totalLen shr 8).toByte(); p[3] = totalLen.toByte()
        p[6] = 0x40.toByte() // Don't fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        // IP checksum
        var s = 0L
        for (i in 0 until 20 step 2) s += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        val chk = s.toInt().inv() and 0xFFFF
        p[10] = (chk shr 8).toByte(); p[11] = chk.toByte()
        // UDP header
        p[20] = (sp shr 8).toByte(); p[21] = sp.toByte()
        p[22] = (dp shr 8).toByte(); p[23] = dp.toByte()
        val udpLen = 8 + data.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // payload
        System.arraycopy(data, 0, p, 28, data.size)
        return p
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Notification si le VPN ne peut pas démarrer au boot (permission non accordée) */
    private fun sendVpnPermissionNeededNotification() {
        val channelId = "vpn_permission_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(channelId, "VPN Permission", NotificationManager.IMPORTANCE_HIGH)
                )
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Perfect DNS Manager")
            .setContentText(getString(R.string.notif_open_app_vpn))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(PendingIntent.getActivity(this, 1,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("AUTO_RECONNECT", true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(2001, notif)
    }

    private fun stopVpn() {
        if (!isRunning) return
        Log.i(T, "=== STOP VPN v34 ===")
        isRunning = false; isVpnRunning = false; instance = null
        tunReaderThread?.interrupt(); dnsReceiverThread?.interrupt()
        try { tunReaderThread?.join(1000) } catch (_: InterruptedException) {}
        try { dnsReceiverThread?.join(1000) } catch (_: InterruptedException) {}
        try { queryExecutor?.shutdownNow() } catch (_: Exception) {}; queryExecutor = null
        pending.clear(); udpUpstreamIps.clear(); localDnsMap.clear(); rewriteRules = emptyList()
        try { doqClient?.closeAll() } catch (_: Exception) {}; doqClient = null
        try { dnsSocket?.close() } catch (_: Exception) {}
        synchronized(tunOutLock) { try { tunOut?.close() } catch (_: Exception) {} }
        try { vpnInterface?.close() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun mkNotif(msg: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH_ID, "DNS VPN", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Perfect DNS Manager")
            .setContentText(msg)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).build()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() {
        stopVpn()
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("vpn_active", false).putString("vpn_label", "").apply()
        stopSelf(); super.onRevoke()
    }
}
