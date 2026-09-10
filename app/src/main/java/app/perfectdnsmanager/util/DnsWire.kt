package app.perfectdnsmanager.util

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Encodage/décodage du wire format DNS (RFC 1035) et requête A « brute » en UDP.
 *
 * Ce code existait en QUATRE copies (DnsVpnService, DoQClient, DnsLeakTester,
 * UrlBlockingTester) avec trois stratégies d'ID de transaction différentes, dont
 * deux exploitables :
 *   - ID constant `0x1234` codé en dur (amorçage DoH/DoQ),
 *   - ID = `currentTimeMillis() and 0xFFFF` (prévisible).
 * Aucune ne connectait le socket ni ne vérifiait quoi que ce soit de la réponse :
 * le premier datagramme arrivé sur le port était accepté. Un attaquant off-path
 * n'avait donc qu'à deviner le port éphémère pour détourner la résolution du
 * serveur DoH/DoQ (TLS empêchait le MITM, mais pas le déni de service).
 *
 * [resolveA] ferme les trois trous à la fois :
 *   1. `socket.connect()` → le noyau jette les paquets d'une autre source ;
 *   2. ID tiré de [SecureRandom] à chaque requête ;
 *   3. la réponse est validée (bit QR, ID, et section question identique).
 */
object DnsWire {

    private const val T = "DnsWire"

    /** RFC 1035 §2.3.4 : 63 octets par label, 253 pour le nom complet. */
    private const val MAX_LABEL = 63
    private const val MAX_NAME = 253

    private val rng = SecureRandom()

    private fun randomTxnId(): Int = rng.nextInt(0x1_0000)

    /**
     * Encode un nom de domaine en wire format (labels préfixés + 0x00).
     * @throws IllegalArgumentException si un label dépasse 63 octets ou le nom 253.
     *   Sans ce contrôle, `put(label.length.toByte())` produisait une longueur
     *   négative au-delà de 127, réinterprétée en pointeur de compression.
     */
    fun encodeQName(host: String): ByteArray {
        val out = ByteArrayOutputStream()
        var total = 0
        for (label in host.trimEnd('.').split('.')) {
            // Un nom absolu ("example.com.") finit par un label vide : on l'ignore.
            require(label.isNotEmpty()) { "empty label" }
            val bytes = label.toByteArray(Charsets.ISO_8859_1)
            require(bytes.size <= MAX_LABEL) { "label > $MAX_LABEL octets" }
            total += bytes.size + 1
            require(total <= MAX_NAME) { "nom > $MAX_NAME octets" }
            out.write(bytes.size)
            out.write(bytes)
        }
        require(total > 0) { "nom vide" }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * Décode un nom depuis un buffer, SANS suivre les pointeurs de compression
     * (une section question n'en contient jamais). Renvoie null si malformé.
     */
    fun decodeQName(data: ByteArray, offset: Int, limit: Int): String? {
        val sb = StringBuilder()
        var pos = offset
        while (pos < limit) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return sb.toString()
            // Pointeur de compression ou label trop long → invalide ici.
            if (len > MAX_LABEL) return null
            if (pos + 1 + len > limit) return null
            if (sb.isNotEmpty()) sb.append('.')
            // ISO-8859-1 : le wire format est de l'octet brut. Le charset par
            // défaut de la plateforme corrompait les labels non-ASCII.
            sb.append(String(data, pos + 1, len, Charsets.ISO_8859_1))
            pos += 1 + len
        }
        return null
    }

    /** Longueur en octets du nom encodé démarrant à [offset] (0x00 terminal inclus). */
    fun qNameLength(data: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < data.size && data[pos].toInt() != 0) {
            val len = data[pos].toInt() and 0xFF
            if (len and 0xC0 == 0xC0) return pos - offset + 2 // pointeur de compression
            pos += len + 1
        }
        return pos - offset + 1
    }

    /** Requête DNS de type A/IN pour [host], avec l'ID de transaction donné. */
    fun buildQuery(host: String, txnId: Int): ByteArray {
        val qname = encodeQName(host)
        val out = ByteArrayOutputStream(12 + qname.size + 4)
        out.write((txnId shr 8) and 0xFF); out.write(txnId and 0xFF)
        out.write(0x01); out.write(0x00)   // flags : requête standard, RD=1
        out.write(0x00); out.write(0x01)   // QDCOUNT = 1
        out.write(0x00); out.write(0x00)   // ANCOUNT
        out.write(0x00); out.write(0x00)   // NSCOUNT
        out.write(0x00); out.write(0x00)   // ARCOUNT
        out.write(qname)
        out.write(0x00); out.write(0x01)   // QTYPE  = A
        out.write(0x00); out.write(0x01)   // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * Résout [host] en IPv4 via une requête UDP directe à [server].
     *
     * @param prepare appelé sur le socket avant connexion — sert à `protect()`
     *   le socket pour qu'il contourne notre propre VPN. Renvoyer false annule
     *   la requête (on ne veut pas d'une résolution qui repasse dans le tunnel).
     * @return l'adresse résolue, ou null (timeout, réponse invalide, pas de A).
     */
    fun resolveA(
        server: InetAddress,
        host: String,
        timeoutMs: Int = 3000,
        prepare: (DatagramSocket) -> Boolean = { true }
    ): InetAddress? = try {
        val query = buildQuery(host, randomTxnId())
        val response = exchange(server, query, timeoutMs, prepare) ?: return null
        parseAnswerIp(response, response.size, expectedHost = host)
    } catch (_: Exception) { null }

    /** One connected socket per transaction; bounded by the caller's executor. */
    fun exchange(server: InetAddress, query: ByteArray, timeoutMs: Int = 5000,
                 prepare: (DatagramSocket) -> Boolean = { true }): ByteArray? = try {
        require(query.size in 12..DnsMessages.MAX_BYTES)
        val wire = query.copyOf()
        val id = randomTxnId(); wire[0] = (id shr 8).toByte(); wire[1] = id.toByte()
        DatagramSocket().use { sock ->
            if (!prepare(sock)) return null
            sock.connect(server, 53)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            sock.send(DatagramPacket(wire, wire.size))
            val buf = ByteArray(DnsMessages.MAX_BYTES + 1)
            while (System.nanoTime() < deadline) {
                sock.soTimeout = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
                val pkt = DatagramPacket(buf, buf.size); sock.receive(pkt)
                val response = buf.copyOf(pkt.length)
                if (DnsMessages.matches(wire, response)) {
                    response[0] = query[0]; response[1] = query[1]
                    return response
                }
            }
            null
        }
    } catch (_: Exception) { null }

    /**
     * Extrait la première adresse IPv4 d'une réponse DNS, après avoir vérifié
     * que la réponse correspond bien à la question posée.
     *
     * @param expectedId ID de transaction attendu, ou null pour ne pas vérifier.
     * @param expectedHost nom attendu dans la section question, ou null.
     */
    fun parseAnswerIp(data: ByteArray, length: Int, expectedId: Int? = null,
                      expectedHost: String? = null): InetAddress? = try {
        require(length in 12..data.size)
        val m = org.xbill.DNS.Message(data.copyOf(length))
        require(m.header.getFlag(org.xbill.DNS.Flags.QR.toInt()) && m.rcode == org.xbill.DNS.Rcode.NOERROR)
        require(!m.header.getFlag(org.xbill.DNS.Flags.TC.toInt()) && m.header.getCount(org.xbill.DNS.Section.QUESTION) == 1)
        require(expectedId == null || m.header.id == expectedId)
        val q = m.question
        require(q.type == org.xbill.DNS.Type.A && q.dClass == org.xbill.DNS.DClass.IN)
        require(expectedHost == null || q.name == org.xbill.DNS.Name.fromString(expectedHost, org.xbill.DNS.Name.root))
        var name = q.name
        val records = m.getSectionArray(org.xbill.DNS.Section.ANSWER)
        repeat(16) {
            records.filterIsInstance<org.xbill.DNS.ARecord>().firstOrNull { it.name == name && it.dClass == q.dClass }
                ?.let { return it.address }
            val alias = records.filterIsInstance<org.xbill.DNS.CNAMERecord>().firstOrNull { it.name == name && it.dClass == q.dClass }
                ?: return null
            name = alias.target
        }
        null
    } catch (_: Exception) { null }
}
