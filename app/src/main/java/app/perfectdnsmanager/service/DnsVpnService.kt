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
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.PrivateDnsGuard
import app.perfectdnsmanager.util.ProfileValidation
import app.perfectdnsmanager.util.DnsWire
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
    private var tunOut: FileOutputStream? = null
    private val tunOutLock = Any()

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

    private val control = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var sessionGeneration = 0L
    @Volatile private var destroyed = false

    /**
     * Split-horizon DNS : hostname (lowercase) → IPv4 (4 octets) du/des serveur(s)
     * DoQ, pré-résolus via bootstrap protégé. Quand kwik résout le hostname du
     * serveur DoQ, la requête tombe ici (onTunPacket) et on répond DIRECTEMENT
     * depuis cette map au lieu de la forwarder → pas de récursion → kwik peut
     * valider le certificat contre le hostname (SNI correct). Cf. DoQClient.
     */
    private val localDnsMap = ConcurrentHashMap<String, LocalDns>()

    /** Entrée split-horizon : IP pré-résolue + date, pour ne pas l'épingler à vie. */
    private data class LocalDns(val ipv4: ByteArray, val at: Long)

    /**
     * Durée de vie d'une entrée split-horizon. Sans elle, l'IP d'un serveur DoQ
     * restait épinglée pour TOUTE la session VPN : un basculement anycast
     * coinçait DoQ jusqu'au redémarrage du tunnel. Alignée sur le TTL de 60 s
     * annoncé dans la réponse synthétisée.
     */
    private val localDnsTtlMs = 60_000L

    /** Enregistre une résolution locale (appelé par DoQClient avant de connecter). */
    fun registerLocalDns(hostname: String, ipv4: ByteArray) {
        if (ipv4.size == 4) localDnsMap[hostname.lowercase()] = LocalDns(ipv4, System.currentTimeMillis())
    }

    /** OkHttpClient with protected sockets (bypass VPN) and custom DNS resolver */
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = Socket().also {
                    if (!protect(it)) { it.close(); throw java.io.IOException("Cannot protect DNS socket") }
                }
                private fun connected(host: InetAddress, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket {
                    val socket = createSocket()
                    try {
                        if (local != null) socket.bind(java.net.InetSocketAddress(local, localPort))
                        socket.connect(java.net.InetSocketAddress(host, port), 5000)
                        return socket
                    } catch (e: Exception) { socket.close(); throw e }
                }
                override fun createSocket(host: String, port: Int): Socket = connected(resolveHostBypass(host) ?: throw java.net.UnknownHostException(), port)
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = connected(resolveHostBypass(host) ?: throw java.net.UnknownHostException(), port, localHost, localPort)
                override fun createSocket(host: InetAddress, port: Int): Socket = connected(host, port)
                override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = connected(host, port, localAddress, localPort)
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
            .callTimeout(12, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    // DNS Rewrite
    @Volatile private var rewriteRules = listOf<DnsRewriteRule>()

    // Pending: on stocke aussi le qname original encodé pour restaurer la réponse si rewrite.
    // origId = ID de transaction ORIGINAL du client (pour le restaurer sur le chemin UDP
    // où l'ID sortant est réécrit en ID unique).
    data class Pending(
        val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int,
        val time: Long, val wasRewritten: Boolean, val originalQnameEncoded: ByteArray?,
        val origId: Int = 0, val generation: Long = 0, val query: ByteArray = byteArrayOf()
    )
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

        private val IPV4_RE = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Plafond d'une réponse DNS acceptée en amont (DoH comme DoQ). Sans lui,
         * un résolveur hostile fait allouer une mémoire arbitraire par requête et
         * `buildPkt` tronque silencieusement le champ Total Length IPv4 (16 bits).
         * Aligné sur DoQClient.MAX_RESP_BYTES.
         */
        private const val MAX_RESP_BYTES = 8192

        /** Map of IP-based DoH endpoints to their correct TLS/SNI hostname */
        private val DOH_SNI_MAP = mapOf(
            "9.9.9.9" to "dns.quad9.net",
            "9.9.9.10" to "dns10.quad9.net",
            "9.9.9.11" to "dns11.quad9.net",
            "9.9.9.12" to "dns12.quad9.net",
            "149.112.112.112" to "dns.quad9.net",
            "149.112.112.9" to "dns.quad9.net",
            "149.112.112.10" to "dns10.quad9.net",
            "149.112.112.11" to "dns11.quad9.net",
            "149.112.112.12" to "dns12.quad9.net",
            "1.1.1.1" to "cloudflare-dns.com",
            "1.0.0.1" to "cloudflare-dns.com",
            "1.1.1.2" to "security.cloudflare-dns.com",
            "1.0.0.2" to "security.cloudflare-dns.com",
            "1.1.1.3" to "family.cloudflare-dns.com",
            "1.0.0.3" to "family.cloudflare-dns.com",
            "8.8.8.8" to "dns.google",
            "8.8.4.4" to "dns.google"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, mkNotif(getString(R.string.notif_starting)))
        if (intent?.action == ACTION_RELOAD_RULES) {
            control.execute { rewriteRules = DnsRewriteRepository(this).getAllRules().filter { it.isEnabled } }
            return START_STICKY
        }
        val requestGeneration = generation.incrementAndGet()
        control.execute {
            if (destroyed || requestGeneration != generation.get()) return@execute
            if (intent?.action == ACTION_STOP) {
                stopVpn(); stopSelfResult(startId); return@execute
            }
            // All launches (including START_STICKY and the system) use this guard.
            stopVpn()
            try {
                val primary: String
                val secondary: String?
                if (intent?.action == ACTION_START || intent?.action == ACTION_RESTART) {
                    primary = intent.getStringExtra(EXTRA_DNS_PRIMARY) ?: error("Missing DNS")
                    secondary = intent.getStringExtra(EXTRA_DNS_SECONDARY)
                } else {
                    val json = getSharedPreferences("prefs", MODE_PRIVATE).getString("selected_profile_json", null)
                        ?: error("Missing saved profile")
                    val profile = com.google.gson.Gson().fromJson(json, app.perfectdnsmanager.data.DnsProfile::class.java)
                    require(ProfileValidation.isUsable(profile) && profile.type != app.perfectdnsmanager.data.DnsType.DOT)
                    primary = profile.primary; secondary = profile.secondary
                }
                fun usable(v: String) = ProfileValidation.isIpv4(v) ||
                    ProfileValidation.isValidPrimary(app.perfectdnsmanager.data.DnsType.DOH, v) ||
                    ProfileValidation.isValidPrimary(app.perfectdnsmanager.data.DnsType.DOQ, v)
                require(usable(primary) && (secondary.isNullOrBlank() || usable(secondary)))
                if (!PrivateDnsGuard.tryDisable(this)) {
                    postPrivateDnsLockedNotification()
                    stopSelfResult(startId); return@execute
                }
                if (destroyed || requestGeneration != generation.get()) return@execute
                dnsServer = ProfileValidation.normalizeEndpoint(primary)
                dnsServerSecondary = secondary?.takeIf { it.isNotBlank() }?.let(ProfileValidation::normalizeEndpoint)
                sessionGeneration = requestGeneration
                startVpn(startId)
            } catch (e: Exception) {
                Log.w(T, "VPN start refused: ${e.javaClass.simpleName}")
                stopVpn(); stopSelfResult(startId)
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun postPrivateDnsLockedNotification() {
        val pi = PendingIntent.getActivity(this, 2,
            Intent(this, app.perfectdnsmanager.NotificationActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PRIVATE_DNS_SETTINGS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2002, NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.private_dns_locked_boot)).setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun isDoH(s: String) = s.startsWith("https://", true)
    private fun isDoQ(s: String) = s.startsWith("quic://", true)

    private fun startVpn(startId: Int) {
        // Garde anti double-start : si une pile VPN tourne déjà (ex. deux ACTION_RESTART
        // rapprochés, ou restart différé + start), on la ferme d'abord — sinon on écrase
        // vpnInterface/tunOut/sockets/executor/doqClient sans les libérer (grosse fuite).
        if (isRunning) stopVpn()
        try {
            Log.i(T, "=== START VPN ===  primary=${redactDnsUrl(dnsServer)}  secondary=${redactDnsUrl(dnsServerSecondary)}")

            // Load rewrite rules
            rewriteRules = DnsRewriteRepository(this).getAllRules().filter { it.isEnabled }
            Log.i(T, "Loaded ${rewriteRules.size} DNS rewrite rules.")

            val builder = Builder()
                .setSession("Perfect DNS Manager")
                .setMtu(1500)
                .addAddress("192.0.2.1", 32)
                .setBlocking(true)

            val disableIpv6 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("disable_ipv6", false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.allowBypass()
                try { builder.allowFamily(OsConstants.AF_INET) } catch (_: Exception) {}
                // Without an IPv6 address/route Android blocks this family by default.
                // Permit bypass when IPv6 blocking is OFF; the ::/0 route below captures it when ON.
                if (!disableIpv6) builder.allowFamily(OsConstants.AF_INET6)

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
                Log.e(T, "establish() still null - VPN consent not granted")
                sendVpnPermissionNeededNotification()
                stopSelfResult(startId)
                return
            }
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
            val localGeneration = sessionGeneration
            val localInput = FileInputStream(vpnInterface!!.fileDescriptor)
            isRunning = true; isVpnRunning = true; instance = this

            tunReaderThread = Thread({
                val input = localInput
                val buf = ByteArray(32767)
                while (isRunning && localGeneration == generation.get()) {
                    try {
                        val n = input.read(buf)
                        if (n > 0) onTunPacket(buf.copyOf(n), localGeneration)
                        else if (n < 0) break
                    } catch (e: Exception) {
                        if (isRunning) Log.e(T, "TunReader err", e)
                        break
                    }
                }
                if (isRunning && localGeneration == generation.get() && !destroyed) {
                    runCatching { control.execute {
                        if (localGeneration == generation.get()) { stopVpn(); stopSelfResult(startId) }
                    } }
                }
            }, "TunReader")

            tunReaderThread!!.start()
            // Mettre à jour la notification avec le vrai DNS (startForeground déjà appelé dans onStartCommand)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            startForeground(NOTIF_ID, mkNotif("DNS: ${redactDnsUrl(dnsServer)}"))
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("vpn_active", true).apply()
        } catch (e: Exception) {
            Log.e(T, "Start err", e); stopVpn(); stopSelfResult(startId)
        }
    }

    // ── Traitement paquet TUN → forward DNS ───────────────────────────────

    private fun onTunPacket(buf: ByteArray, localGeneration: Long) {
        try {
            if (localGeneration != generation.get() || buf.size < 20) return
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

            // Early-exit chemin chaud : ne parser le qname (ByteBuffer + String par
            // label) que si une règle de rewrite OU un split-horizon DoQ est actif —
            // sinon (config majoritaire) on forwarde directement sans parser.
            var wasRewritten = false
            var originalQnameEncoded: ByteArray? = null
            var modifiedQuery: ByteArray? = null
            if (rewriteRules.isNotEmpty() || localDnsMap.isNotEmpty()) {
                val (qname, modQ) = getQNameAndApplyRewrite(query)
                modifiedQuery = modQ

                // Split-horizon : requête vers le hostname d'un serveur DoQ pré-résolu
                // → on répond localement (kwik obtient l'IP sans récursion + valide le cert).
                if (localDnsMap.isNotEmpty()) {
                    val key = qname.lowercase()
                    val entry = localDnsMap[key]
                    // Entrée périmée : on la retire et on laisse la requête suivre
                    // le chemin normal (DoQClient re-résoudra et ré-enregistrera).
                    val ip = if (entry != null &&
                        System.currentTimeMillis() - entry.at <= localDnsTtlMs) {
                        entry.ipv4
                    } else {
                        if (entry != null) localDnsMap.remove(key, entry)
                        null
                    }
                    if (ip != null) {
                        val resp = synthesizeDnsResponse(query, ip)
                        writeTun(Pending(srcIp, dstIp, srcPort, System.currentTimeMillis(), false, null, origId, localGeneration, query), resp)
                        return
                    }
                }
                if (modifiedQuery != null) originalQnameEncoded =
                    query.copyOf()
            }

            modifiedQuery?.let {
                // (sécurité) ne PAS logger le domaine interrogé. originalQnameEncoded
                // a déjà été calculé plus haut (qname est local au bloc de parsing).
                Log.i(T, "DNS Rewrite rule matched")
                query = it
                wasRewritten = true
            }

            val p = Pending(srcIp, dstIp, srcPort, System.currentTimeMillis(), wasRewritten, originalQnameEncoded, origId, localGeneration, query)

            when {
                // DoH/DoQ : la réponse est traitée dans la tâche même (closure sur `p`),
                // plus besoin de la map `pending` → pas de collision d'ID possible.
                isDoH(real) -> {
                    val q = query
                    submitQuery { val resp = doH(q, real); if (resp != null) writeTun(p, resp) }
                }
                isDoQ(real) -> {
                    val q = query
                    val client = doqClient
                    submitQuery { val resp = client?.query(q, real); if (resp != null) writeTun(p, resp) }
                }
                else -> {
                    val q = query
                    submitQuery {
                        if (localGeneration == generation.get()) {
                            val resp = DnsWire.exchange(InetAddress.getByName(real), q) { sock ->
                                localGeneration == generation.get() && protect(sock)
                            }
                            if (resp != null) writeTun(p, resp)
                        }
                    }
                }
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

    // ── Rewrite : restaurer le qname original dans la réponse ─────────────

    private fun writeTun(p: Pending, payload: ByteArray) {
        if (p.generation != generation.get() || !DnsMessages.matches(p.query, payload)) return
        val finalPayload = if (p.wasRewritten && p.originalQnameEncoded != null) {
            DnsMessages.restoreResponse(payload, p.originalQnameEncoded) ?: return
        } else {
            payload
        }
        try {
            val pkt = buildPkt(p.dstIp, p.srcIp, 53, p.srcPort, finalPayload)
            synchronized(tunOutLock) { if (isRunning && p.generation == generation.get()) tunOut?.write(pkt) }
        } catch (e: Exception) { Log.w(T, "TUN write: ${e.message}") }
    }

    // ── DNS Rewrite helpers ───────────────────────────────────────────────

    private fun getQNameAndApplyRewrite(query: ByteArray): Pair<String, ByteArray?> {
        val qname = DnsWire.decodeQName(query, 12, query.size) ?: return Pair("", null)
        rewriteRules.find { it.fromDomain.equals(qname, ignoreCase = true) }?.let {
            return Pair(qname, buildNewQuery(query, it.toDomain))
        }
        return Pair(qname, null)
    }

    /**
     * Réécrit la question avec `newDomain`. Renvoie null si le domaine cible est
     * invalide (label > 63 octets…) : mieux vaut laisser passer la requête
     * d'origine que d'émettre un paquet DNS malformé.
     */
    private fun buildNewQuery(originalQuery: ByteArray, newDomain: String): ByteArray? =
        DnsMessages.rewriteQuery(originalQuery, newDomain)

    /** Construit une réponse DNS locale : A→IP pour un type A, NODATA sinon. */
    private fun synthesizeDnsResponse(query: ByteArray, ipv4: ByteArray): ByteArray {
        val qnameLen = DnsWire.qNameLength(query, 12)
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
            val isIpHost = host.matches(IPV4_RE)
            if (isIpHost) {
                val tlsHost = DOH_SNI_MAP[host] ?: host
                val path = if (parsed.path.isNullOrEmpty()) "/dns-query" else parsed.path
                val port = if (parsed.port > 0) ":${parsed.port}" else ""
                "https://$tlsHost$port$path" + (parsed.query?.let { "?$it" } ?: "")
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
            // Lecture BORNÉE : `body.bytes()` chargeait la réponse entière, sans
            // limite, alors que DoQ plafonnait déjà à MAX_RESP_BYTES. On lit un
            // octet de plus que le plafond pour détecter le dépassement.
            // On lit un octet de plus que le plafond : si on l'atteint, la
            // réponse dépasse et on la rejette.
            val responseBody = response.body?.byteStream()?.use { readBounded(it, MAX_RESP_BYTES + 1) }
            when {
                responseBody == null || !response.isSuccessful -> {
                    Log.w(T, "DoH: HTTP ${response.code} body=${responseBody?.size ?: 0}")
                    null
                }
                responseBody.size > MAX_RESP_BYTES -> {
                    Log.w(T, "DoH: réponse trop grande (> $MAX_RESP_BYTES o) — rejet")
                    null
                }
                responseBody.size < 12 -> {
                    Log.w(T, "DoH: réponse tronquée (${responseBody.size} o)")
                    null
                }
                else -> responseBody
            }
        }
    } catch (e: Exception) { Log.w(T, "DoH err: ${e.javaClass.simpleName}: ${e.message}"); null }

    /** Lit au plus [max] octets d'un flux, sans allouer sur la foi du serveur. */
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Résoudre un hostname en bypassant le VPN (requête DNS directe UDP).
     * Bootstrap DNS sans Google : Cloudflare 1.1.1.1 d'abord, puis Quad9 9.9.9.9
     * en fallback. Cohérent avec une app de DNS privé : on n'envoie pas le
     * hostname amorce à Google.
     */
    private fun resolveHostBypass(host: String): InetAddress? = try {
        if (host.matches(IPV4_RE)) {
            InetAddress.getByName(host)
        } else {
            // Socket connecté + ID aléatoire + réponse validée (cf. DnsWire) :
            // l'ancienne version acceptait n'importe quel datagramme arrivant sur
            // le port, avec un ID de transaction constant 0x1234.
            bootstrapResolve(host, byteArrayOf(1, 1, 1, 1))
                ?: bootstrapResolve(host, byteArrayOf(9, 9, 9, 9))
        }
    } catch (e: Exception) { Log.w(T, "resolveHostBypass: ${e.javaClass.simpleName}"); null }

    private fun bootstrapResolve(host: String, serverIp: ByteArray): InetAddress? =
        DnsWire.resolveA(InetAddress.getByAddress(serverIp), host) { sock -> protect(sock) }

    // ── Utilitaires réseau ────────────────────────────────────────────────

    private fun ipStr(b: ByteArray) =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    private fun buildPkt(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
        // Le champ Total Length d'un en-tête IPv4 tient sur 16 bits : au-delà, la
        // valeur écrite serait tronquée et le paquet injecté dans le TUN malformé.
        require(data.size <= MAX_RESP_BYTES) { "payload DNS trop grand (${data.size} o)" }
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
                Intent(this, app.perfectdnsmanager.NotificationActivity::class.java).apply {
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

    /** Called only by the serial control executor. Close first to wake blocked reads. */
    private fun stopVpn() {
        isRunning = false; isVpnRunning = false
        if (instance === this) instance = null
        try { okHttpClient.dispatcher.cancelAll(); okHttpClient.connectionPool.evictAll() } catch (_: Exception) {}
        try { queryExecutor?.shutdownNow() } catch (_: Exception) {}; queryExecutor = null
        try { doqClient?.closeAll() } catch (_: Exception) {}; doqClient = null
        synchronized(tunOutLock) { try { tunOut?.close() } catch (_: Exception) {}; tunOut = null }
        try { vpnInterface?.close() } catch (_: Exception) {}; vpnInterface = null
        tunReaderThread?.interrupt()
        try { tunReaderThread?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        tunReaderThread = null
        localDnsMap.clear(); rewriteRules = emptyList()
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
        // Keep foreground status during a profile change. Android removes it when the service stops.
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
                Intent(this, app.perfectdnsmanager.NotificationActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        destroyed = true; generation.incrementAndGet()
        control.execute { stopVpn() }
        control.shutdown()
        super.onDestroy()
    }
    override fun onRevoke() {
        generation.incrementAndGet()
        control.execute { stopVpn(); stopSelf() }
        super.onRevoke()
    }
}
