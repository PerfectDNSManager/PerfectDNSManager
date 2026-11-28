package app.perfectdnsmanager.util

/** Keep virtual DNS inside PDM, including unsupported protocols (never leak them). */
object AntiDpiPackets {
    fun isVirtualDns(packet: ByteArray): Boolean = packet.size >= 20 &&
        (packet[0].toInt() and 0xf0) == 0x40 &&
        packet[16] == 192.toByte() && packet[17] == 0.toByte() && packet[18] == 2.toByte() &&
        (packet[19] == 2.toByte() || packet[19] == 3.toByte())
    fun canForward(packet: ByteArray, ipv6: Boolean): Boolean {
        if (packet.isEmpty()) return false
        return when (packet[0].toInt() ushr 4 and 15) {
            4 -> packet.size >= 20 && (packet[0].toInt() and 15) >= 5 &&
                (packet[0].toInt() and 15) * 4 <= packet.size
            6 -> ipv6 && packet.size >= 40
            else -> false
        }
    }
}
