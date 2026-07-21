package app.perfectdnsmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import app.perfectdnsmanager.service.DnsVpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Testeur de blocage d'URL via socket protégé (bypass VPN).
 *
 * Compare la résolution DNS :
 *   1. DNS FAI (sans VPN) : détecte le DNS opérateur via LinkProperties,
 *      crée un DatagramSocket protégé via DnsVpnService.protectSocket(),
 *      et envoie une requête DNS brute UDP
 *   2. DNS actif (avec VPN) : résout via InetAddress (passe par le VPN)
 */
object UrlBlockingTester {

    private const val TAG = "UrlBlockingTester"

    // Known blocking IPs (ISP redirects, localhost sinks)
    private val BLOCKED_IPS = setOf(
        "127.0.0.1", "0.0.0.0", "::1", "::0",
        "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0",
        "90.85.16.52", "194.6.135.126", "54.246.190.12"
    )

    data class ResolutionResult(
        val ip: String?,
        val isBlocked: Boolean,
        val error: String?,
        val authorityLabel: String? = null
    )

    data class BlockingResult(
        val domain: String,
        val ispDns: ResolutionResult,
        val activeDns: ResolutionResult
    )

    /**
     * Test URL blocking: ISP DNS (protected socket) vs active DNS (system resolver).
     *
     * @param context  Application context for ConnectivityManager
     * @param domain   Domain to test
     */
    fun testBeforeAfter(context: Context, domain: String = "ygg.re"): BlockingResult {
        val ispResult = resolveViaProtectedSocket(context, domain).annotate(context)
        clearDnsCache()
        val activeResult = resolveViaSystem(domain).annotate(context)
        return BlockingResult(domain, ispResult, activeResult)
    }

    /** Annote un résultat avec l'autorité de blocage si l'IP est connue */
    private fun ResolutionResult.annotate(context: Context): ResolutionResult {
        if (!isBlocked || ip == null) return this
        val label = BlockingAuthoritiesManager.getAuthorityLabel(context, ip)
        return if (label != null) copy(authorityLabel = label) else this
    }

    /**
     * Résout un domaine en bypassant le VPN via un socket protégé :
     * 1. Détecter le DNS opérateur via ConnectivityManager → LinkProperties.dnsServers
     * 2. Si VPN actif : créer un DatagramSocket protégé via DnsVpnService.protectSocket()
     * 3. Si VPN inactif : utiliser un socket UDP normal (pas de VPN à bypasser)
     * 4. Envoyer requête DNS brute UDP au DNS opérateur détecté
     */
    fun resolveViaProtectedSocket(context: Context, domain: String): ResolutionResult {
        val vpnRunning = DnsVpnService.isVpnRunning

        // Try raw UDP DNS query first
        val udpResult = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Trouver le DNS opérateur via le réseau physique
            val ispDnsServer: InetAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val physicalNetwork = cm.allNetworks.firstOrNull { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps != null &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                val linkProps = physicalNetwork?.let { cm.getLinkProperties(it) }
                linkProps?.dnsServers?.firstOrNull() ?: InetAddress.getByName("8.8.8.8")
            } else {
                InetAddress.getByName("8.8.8.8")
            }

            // Créer un socket : protégé si VPN actif, normal sinon.
            // `use{}` ferme le socket MÊME sur SocketTimeoutException (cas très
            // fréquent : DNS FAI qui ne répond pas) — sans ça, un FD fuitait à
            // chaque timeout, et ce testeur tourne en boucle sur toute la liste
            // de domaines → épuisement des descripteurs.
            DatagramSocket().use { socket ->
                if (vpnRunning) {
                    val isProtected = DnsVpnService.protectSocket(socket)
                    if (!isProtected) {
                        Log.w(TAG, "Could not protect socket, using unprotected")
                    }
                }
                // Sans VPN, le socket normal envoie directement via le réseau physique
                socket.soTimeout = 5000

                // Construire et envoyer la requête DNS
                val query = buildDnsQuery(domain)
                socket.send(DatagramPacket(query, query.size, ispDnsServer, 53))

                // Recevoir la réponse
                val buf = ByteArray(512)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)

                // Parser la réponse
                val ip = parseDnsResponseIp(buf, response.length)
                if (ip != null) {
                    val ipStr = ip.hostAddress ?: ""
                    ResolutionResult(ipStr, isBlockedIp(ipStr), null)
                } else {
                    null // UDP succeeded but no A record parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Protected socket resolve $domain: ${e.message}")
            null // UDP query failed
        }

        // If UDP succeeded with a valid result, return it
        if (udpResult != null && udpResult.ip != null) return udpResult

        // Fallback: if no VPN is active, system resolver IS the ISP DNS,
        // so InetAddress.getByName() gives us the ISP resolution directly
        if (!vpnRunning) {
            return try {
                val addr = InetAddress.getByName(domain)
                val ip = addr.hostAddress ?: ""
                ResolutionResult(ip, isBlockedIp(ip), null)
            } catch (e: java.net.UnknownHostException) {
                ResolutionResult(null, true, "NXDOMAIN")
            } catch (e: Exception) {
                Log.w(TAG, "System fallback resolve $domain: ${e.message}")
                udpResult ?: ResolutionResult(null, true, e.message)
            }
        }

        // VPN active but UDP failed — return the UDP error
        return udpResult ?: ResolutionResult(null, true, "DNS query failed")
    }

    /**
     * Resolve domain via system DNS (goes through VPN/active DNS config).
     */
    private fun resolveViaSystem(domain: String): ResolutionResult {
        return try {
            val addr = InetAddress.getByName(domain)
            val ip = addr.hostAddress ?: ""
            ResolutionResult(ip, isBlockedIp(ip), null)
        } catch (e: java.net.UnknownHostException) {
            ResolutionResult(null, true, "NXDOMAIN")
        } catch (e: Exception) {
            Log.w(TAG, "System resolve $domain: ${e.message}")
            ResolutionResult(null, true, e.message)
        }
    }

    private fun clearDnsCache() {
        try {
            val f = InetAddress::class.java.getDeclaredField("addressCache")
            f.isAccessible = true
            val c = f.get(null)
            c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) }
        } catch (_: Exception) {}
        try {
            val f = InetAddress::class.java.getDeclaredField("negativeCache")
            f.isAccessible = true
            val c = f.get(null)
            c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) }
        } catch (_: Exception) {}
    }

    /** Construire une requête DNS type A pour un hostname */
    private fun buildDnsQuery(host: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        // Transaction ID
        val txId = (System.currentTimeMillis() and 0xFFFF).toInt()
        buf.write(txId shr 8); buf.write(txId and 0xFF)
        // Flags: standard query, recursion desired
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
            if (len >= 0xC0) { offset += 2; break }
            offset += len + 1
        }
        if (offset < length && data[offset].toInt() == 0) offset++
        offset += 4 // QTYPE + QCLASS
        // Parse answer records
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

    private fun isBlockedIp(ip: String?): Boolean {
        if (ip == null) return true
        return ip in BLOCKED_IPS || ip.startsWith("127.") || ip.startsWith("0.") || ip == "::1" || ip == "::"
    }
}
