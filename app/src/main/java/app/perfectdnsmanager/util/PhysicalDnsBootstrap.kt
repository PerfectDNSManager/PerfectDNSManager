package app.perfectdnsmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress

/** Never resolve a provider against a VPN which may still be disappearing. */
object PhysicalDnsBootstrap {
    fun lookup(context: Context, host: String): List<InetAddress> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = try {
            cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.sortedByDescending { network ->
                Build.VERSION.SDK_INT >= 23 &&
                    cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        } catch (_: Exception) { emptyList() }
        return BootstrapAddresses.resolve(networks.map { network -> { network.getAllByName(host).toList() } }, emptyList())
    }
}
