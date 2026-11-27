package app.perfectdnsmanager.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Each measurement opens a fresh connection: an old protected/bound socket
 * must not survive a VPN or IPv6 setting change and misreport connectivity. */
object WhoamiClients {
    private fun create(ipv4Only: Boolean): OkHttpClient = Http.withTimeouts(6, 6).newBuilder()
        .connectionPool(ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter {
                if (ipv4Only) it is Inet4Address else it is Inet6Address
            }.ifEmpty { throw UnknownHostException("Requested IP family unavailable") }
        }).build()
    private val ipv4 by lazy { create(true) }
    private val ipv6 by lazy { create(false) }
    fun forFamily(ipv4Only: Boolean) = if (ipv4Only) ipv4 else ipv6
}
