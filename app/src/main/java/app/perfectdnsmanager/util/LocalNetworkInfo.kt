package app.perfectdnsmanager.util

import java.net.Inet4Address
import java.net.NetworkInterface

/** Avoid intermediate lists and legacy ART register merges in callers. */
object LocalNetworkInfo {
    fun ipv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "N/A"
                    }
                }
            }
        } catch (_: Exception) {
            // Interface enumeration can fail while connectivity changes.
        }
        return "N/A"
    }
}
