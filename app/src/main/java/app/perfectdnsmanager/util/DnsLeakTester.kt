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
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun runLeakTestComparison(context: Context): LeakComparisonResult {
        // 1. ISP DNS via socket protégé (bypass VPN)
        val ispResolvers = detectResolversViaProtectedSocket(context)

        // 2. VPN DNS via système
        clearDnsCache()
        val vpnResolvers = detectResolversViaSystem()

        // 3. GeoIP lookup
        val allIps = (ispResolvers + vpnResolvers).toSet()
        val client = createClient()
        val geoCache = mutableMapOf<String, ResolverInfo>()
        for (ip in allIps) {
            geoCache[ip] = lookupGeoIp(client, ip)
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

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
                linkProps?.dnsServers?.firstOrNull() ?: InetAddress.getByName("8.8.8.8")
            } else {
                InetAddress.getByName("8.8.8.8")
            }

            // whoami.akamai.net
            val ip1 = resolveViaProtectedSocket(ispDns, "whoami.akamai.net")
            if (ip1 != null) resolverIps.add(ip1)

            // myip.opendns.com via OpenDNS
            val openDns = InetAddress.getByName("208.67.222.222")
            val ip2 = resolveViaProtectedSocket(openDns, "myip.opendns.com")
            if (ip2 != null) resolverIps.add(ip2)

        } catch (e: Exception) {
            Log.w(TAG, "Protected socket leak detect failed: ${e.message}")
        }
        return resolverIps
    }

    private fun resolveViaProtectedSocket(dnsServer: InetAddress, hostname: String): String? {
        return try {
            // .use{} : ferme le FD même sur receive() timeout (DNS FAI muet = cas
            // fréquent). Appelé 2× par génération de rapport → sinon fuite de FD.
            DatagramSocket().use { socket ->
                if (!DnsVpnService.protectSocket(socket)) {
                    Log.w(TAG, "Cannot protect socket for $hostname")
                    return null
                }
                socket.soTimeout = 5000
                val query = buildDnsQuery(hostname)
                socket.send(DatagramPacket(query, query.size, dnsServer, 53))
                val buf = ByteArray(512)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                parseDnsResponseIp(buf, response.length)?.hostAddress
            }
        } catch (e: Exception) {
            Log.w(TAG, "Protected socket resolve $hostname: ${e.message}")
            null
        }
    }

    private fun detectResolversViaSystem(): Set<String> {
        clearDnsCache()
        val resolverIps = mutableSetOf<String>()
        try {
            val addr = InetAddress.getByName("whoami.akamai.net")
            val ip = addr.hostAddress
            if (ip != null && ip.isNotEmpty()) resolverIps.add(ip)
        } catch (e: Exception) {
            Log.w(TAG, "whoami.akamai.net failed: ${e.message}")
        }
        try {
            val addr = InetAddress.getByName("myip.opendns.com")
            val ip = addr.hostAddress
            if (ip != null && ip.isNotEmpty()) resolverIps.add(ip)
        } catch (e: Exception) {
            Log.w(TAG, "myip.opendns.com failed: ${e.message}")
        }
        return resolverIps
    }

    private fun clearDnsCache() {
        // Vide le cache DNS de la JVM par réflexion afin que le leak test
        // reflète la résolution courante et non une entrée mise en cache. Un
        // échec (API cachée modifiée/restreinte selon la ROM) fausse le test :
        // on le loggue au lieu de l'avaler silencieusement.
        try {
            val f = InetAddress::class.java.getDeclaredField("addressCache")
            f.isAccessible = true
            val c = f.get(null)
            c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) }
        } catch (e: Exception) {
            Log.w(TAG, "clearDnsCache(addressCache) failed: ${e.message}")
        }
        try {
            val f = InetAddress::class.java.getDeclaredField("negativeCache")
            f.isAccessible = true
            val c = f.get(null)
            c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) }
        } catch (e: Exception) {
            Log.w(TAG, "clearDnsCache(negativeCache) failed: ${e.message}")
        }
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val txId = (System.currentTimeMillis() and 0xFFFF).toInt()
        buf.write(txId shr 8); buf.write(txId and 0xFF)
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

    private fun lookupGeoIp(client: OkHttpClient, ip: String): ResolverInfo {
        return try {
            val request = Request.Builder()
                .url("https://ipapi.co/$ip/json/")
                .header("User-Agent", "PerfectDNSManager/1.0")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (response.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val country = json.optString("country_name", "").ifEmpty { null }
                val org = json.optString("org", "").ifEmpty { null }
                ResolverInfo(ip, country, org)
            } else {
                ResolverInfo(ip, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GeoIP lookup failed for $ip: ${e.message}")
            ResolverInfo(ip, null, null)
        }
    }
}
