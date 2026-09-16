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

    /**
     * Verdict d'une résolution. Le booléen `isBlocked` d'origine confondait
     * « le FAI bloque ce domaine » et « la requête a échoué » : un timeout ou
     * une coupure réseau était rapportée comme un blocage caractérisé, dans le
     * rapport que l'utilisateur publie ensuite. D'où le troisième état.
     */
    enum class Status { BLOCKED, ACCESSIBLE, UNKNOWN }

    data class ResolutionResult(
        val ip: String?,
        val status: Status,
        val error: String?,
        val authorityLabel: String? = null
    ) {
        /** Vrai UNIQUEMENT pour un blocage constaté — jamais pour un échec réseau. */
        val isBlocked: Boolean get() = status == Status.BLOCKED

        /** Vrai si le test n'a pas pu conclure (réseau indisponible, DNS muet…). */
        val isUnknown: Boolean get() = status == Status.UNKNOWN
    }

    private fun resolved(ip: String) = ResolutionResult(ip, statusFor(ip), null)
    private fun unknown(err: String?) = ResolutionResult(null, Status.UNKNOWN, err)
    private fun statusFor(ip: String) = if (isBlockedIp(ip)) Status.BLOCKED else Status.ACCESSIBLE

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
        val activeResult = resolveViaSystem(domain).annotate(context)
        return BlockingResult(domain, ispResult, activeResult)
    }

    /** Masque le label gauche d'un domaine avant de le logger. */
    private fun redactHost(h: String): String {
        val dot = h.indexOf('.')
        return if (dot > 0) "***" + h.substring(dot) else "***"
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
                linkProps?.dnsServers?.firstOrNull() ?: return unknown("ISP DNS unavailable")
            } else {
                return unknown("ISP DNS unavailable")
            }

            // Créer un socket : protégé si VPN actif, normal sinon.
            // `use{}` ferme le socket MÊME sur SocketTimeoutException (cas très
            // fréquent : DNS FAI qui ne répond pas) — sans ça, un FD fuitait à
            // chaque timeout, et ce testeur tourne en boucle sur toute la liste
            // de domaines → épuisement des descripteurs.
            // DnsWire : socket connecté + ID aléatoire + réponse validée.
            val lookup = DnsWire.lookupA(ispDnsServer, domain, timeoutMs = 5000) { socket ->
                // Sans VPN le socket normal sort déjà par le réseau physique ;
                // avec VPN il faut le protéger pour ne pas boucler dans le tunnel.
                if (vpnRunning && !DnsVpnService.protectSocket(socket)) {
                    Log.w(TAG, "Could not protect socket")
                    return@lookupA false
                }
                true
            }
            when {
                lookup == null -> null
                lookup.address != null -> lookup.address.hostAddress?.let { resolved(it) }
                // Réponse explicite du DNS FAI « ce domaine n'existe pas » : c'est
                // une méthode de blocage répandue, donc un blocage constaté.
                lookup.rcode == org.xbill.DNS.Rcode.NXDOMAIN -> ResolutionResult(null, Status.BLOCKED, "NXDOMAIN")
                else -> unknown("DNS rcode ${org.xbill.DNS.Rcode.string(lookup.rcode)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Protected socket resolve ${redactHost(domain)}: ${e.javaClass.simpleName}")
            null // échec de la requête UDP
        }

        // If UDP succeeded with a valid result, return it
        // Une réponse NXDOMAIN du DNS FAI est aussi un résultat définitif.
        if (udpResult != null && (udpResult.ip != null || udpResult.status == Status.BLOCKED)) return udpResult

        // Fallback: if no VPN is active, system resolver IS the ISP DNS,
        // so InetAddress.getByName() gives us the ISP resolution directly
        if (!vpnRunning && !PrivateDnsGuard.isStrictActive(context)) {
            return try {
                val addr = InetAddress.getByName(domain)
                val ip = addr.hostAddress ?: ""
                resolved(ip)
            } catch (e: java.net.UnknownHostException) {
                // NXDOMAIN via le résolveur système = réponse négative réelle.
                unknown("DNS resolution failed")
            } catch (e: Exception) {
                Log.w(TAG, "System fallback resolve ${redactHost(domain)}: ${e.javaClass.simpleName}")
                udpResult ?: unknown(e.message)
            }
        }

        // VPN actif mais la requête UDP a échoué : on ne conclut PAS au blocage.
        return udpResult ?: unknown("DNS query failed")
    }

    /**
     * Resolve domain via system DNS (goes through VPN/active DNS config).
     */
    private fun resolveViaSystem(domain: String): ResolutionResult {
        return try {
            val addr = InetAddress.getByName(domain)
            val ip = addr.hostAddress ?: ""
            resolved(ip)
        } catch (e: java.net.UnknownHostException) {
            unknown("DNS resolution failed")
        } catch (e: Exception) {
            Log.w(TAG, "System resolve ${redactHost(domain)}: ${e.javaClass.simpleName}")
            unknown(e.message)
        }
    }

    private fun isBlockedIp(ip: String?): Boolean {
        if (ip == null) return true
        return ip in BLOCKED_IPS || ip.startsWith("127.") || ip.startsWith("0.") || ip == "::1" || ip == "::"
    }
}
