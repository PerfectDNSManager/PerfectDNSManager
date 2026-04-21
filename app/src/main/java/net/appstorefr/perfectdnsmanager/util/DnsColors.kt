package net.appstorefr.perfectdnsmanager.util

import net.appstorefr.perfectdnsmanager.data.DnsType

object DnsColors {
    fun colorForType(type: DnsType): Int = when (type) {
        DnsType.DOH -> 0xFF44FF44.toInt()  // Vert (légendaire)
        DnsType.DOQ -> 0xFF7B68EE.toInt()  // Bleu-violet (rare)
        DnsType.DOT -> 0xFFFFB700.toInt()  // Or (épique)
        DnsType.DEFAULT -> 0xFF888888.toInt()  // Gris (commun)
    }

    fun labelForType(type: DnsType): String = when (type) {
        DnsType.DOH -> "DoH"
        DnsType.DOQ -> "DoQ"
        DnsType.DOT -> "DoT"
        DnsType.DEFAULT -> "Standard"
    }
}
