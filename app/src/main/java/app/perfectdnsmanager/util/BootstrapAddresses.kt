package app.perfectdnsmanager.util

import java.net.InetAddress

/** Keep both IP families and alternative addresses, without using the VPN resolver. */
object BootstrapAddresses {
    fun resolve(networks: List<() -> List<InetAddress>>, fallbacks: List<() -> List<InetAddress>>): List<InetAddress> {
        for (lookup in networks + fallbacks) {
            val addresses = try { lookup().filter { !it.isAnyLocalAddress && !it.isMulticastAddress }.distinct() }
                catch (_: Exception) { emptyList() }
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    }
}
