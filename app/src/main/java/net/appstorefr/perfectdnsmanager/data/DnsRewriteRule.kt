package net.appstorefr.perfectdnsmanager.data

data class DnsRewriteRule(
    val id: Long = System.currentTimeMillis(),
    val fromDomain: String,
    val toDomain: String,
    var isEnabled: Boolean = true
)
