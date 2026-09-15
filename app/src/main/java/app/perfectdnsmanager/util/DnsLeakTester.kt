package app.perfectdnsmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import app.perfectdnsmanager.service.DnsVpnService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS Leak Test avec comparaison avant/après via socket protégé.
 *
 * ISP DNS : résolution via socket protégé par DnsVpnService.protectSocket() (bypass VPN)
 * VPN DNS : résolution via InetAddress système (passe par le VPN)
 */
object DnsLeakTester {

    private const val TAG = "DnsLeakTester"

    data class ResolverInfo(
        val ip: String,
        val country: String?,
        val isp: String?
    )

    data class LeakResult(
        val resolverIps: List<ResolverInfo>,
        val error: String?
    )

    data class LeakComparisonResult(
        val ispResult: LeakResult,
        val vpnResult: LeakResult
    )

    private fun createClient(): OkHttpClient {
        return Http.withTimeouts(connectSec = 10, readSec = 10)
    }

    fun runLeakTestComparison(context: Context): LeakComparisonResult {
        // 1. ISP DNS via socket protégé (bypass VPN)
        val ispResolvers = detectResolversViaProtectedSocket(context)

        // 2. VPN DNS via système
        val vpnResolvers = detectResolversViaSystem()

        // 3. GeoIP lookup
        val allIps = (ispResolvers + vpnResolvers).toSet()
        val client = createClient()
        val geoCache = mutableMapOf<String, ResolverInfo>()
        for (ip in allIps) {
            geoCache[ip] = lookupGeoIp(client, ip)
        }

        return LeakComparisonResult(
            LeakResult(
                ispResolvers.map { geoCache[it] ?: ResolverInfo(it, null, null) },
                if (ispResolvers.isEmpty()) "Could not detect ISP resolvers" else null
            ),
            LeakResult(
                vpnResolvers.map { geoCache[it] ?: ResolverInfo(it, null, null) },
                if (vpnResolvers.isEmpty()) "Could not detect VPN resolvers" else null
            )
        )
    }

    /**
     * Détecte les résolveurs DNS via socket protégé (bypass VPN).
     * Utilise DnsVpnService.protectSocket() pour bypasser le tunnel VPN.
     */
    private fun detectResolversViaProtectedSocket(context: Context): Set<String> {
        val resolverIps = mutableSetOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Trouver le DNS ISP
            val ispDns: InetAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val physicalNetwork = cm.allNetworks.firstOrNull { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps != null &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                val linkProps = physicalNetwork?.let { cm.getLinkProperties(it) }
                linkProps?.dnsServers?.firstOrNull() ?: return emptySet()
            } else {
                return emptySet()
            }

            // whoami.akamai.net
            val ip1 = resolveViaProtectedSocket(ispDns, "whoami.akamai.net")
            if (ip1 != null) resolverIps.add(ip1)


        } catch (e: Exception) {
            Log.w(TAG, "Protected socket leak detect failed: ${e.javaClass.simpleName}")
        }
        return resolverIps
    }

    // NOTE — il n'existe plus de moyen de vider le cache DNS de la JVM :
    // `InetAddress.addressCache`/`negativeCache` sont des API cachées, bloquées
    // depuis Android 9 (l'app cible le SDK 34). L'ancien `clearDnsCache()` par
    // réflexion échouait en silence dans deux `catch` vides, en laissant croire
    // que le cache était purgé. Les mesures qui doivent être fraîches passent
    // donc par resolveViaProtectedSocket(), en UDP direct, sans cache.

    /** Masque le label gauche d'un hostname avant de le logger. */
    private fun redactHost(h: String): String {
        val dot = h.indexOf('.')
        return if (dot > 0) "***" + h.substring(dot) else "***"
    }

    private fun resolveViaProtectedSocket(dnsServer: InetAddress, hostname: String): String? =
        // DnsWire : socket connecté + ID aléatoire + réponse validée. La copie
        // locale précédente acceptait tout datagramme arrivant sur le port.
        DnsWire.resolveA(dnsServer, hostname, timeoutMs = 5000) { socket ->
            DnsVpnService.protectSocket(socket).also {
                if (!it) Log.w(TAG, "Cannot protect socket for ${redactHost(hostname)}")
            }
        }?.hostAddress

    private fun detectResolversViaSystem(): Set<String> {
        val resolverIps = mutableSetOf<String>()
        try {
            val addr = InetAddress.getByName("whoami.akamai.net")
            val ip = addr.hostAddress
            if (ip != null && ip.isNotEmpty()) resolverIps.add(ip)
        } catch (e: Exception) {
            Log.w(TAG, "whoami.akamai.net failed: ${e.message}")
        }
        return resolverIps
    }

    private fun lookupGeoIp(client: OkHttpClient, ip: String): ResolverInfo {
        return try {
            val request = Request.Builder()
                .url("https://ipapi.co/$ip/json/")
                .header("User-Agent", "PerfectDNSManager/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = app.perfectdnsmanager.util.Http.readText(response.body)
                if (response.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val country = json.optString("country_name", "").ifEmpty { null }
                    val org = json.optString("org", "").ifEmpty { null }
                    ResolverInfo(ip, country, org)
                } else {
                    ResolverInfo(ip, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GeoIP lookup failed for $ip: ${e.message}")
            ResolverInfo(ip, null, null)
        }
    }
}
