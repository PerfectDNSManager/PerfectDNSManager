package net.appstorefr.perfectdnsmanager.util

/**
 * Redact path/query of DoH/DoQ URLs for log output. NextDNS profiles
 * embed the user's account ID in the path (`https://dns.nextdns.io/abc123`)
 * which would leak via `adb logcat` or root extraction.
 *
 * Keeps scheme + host visible (useful for diagnostics), strips the rest.
 */
fun redactDnsUrl(value: String?): String {
    if (value.isNullOrBlank()) return value ?: ""
    val schemeIdx = value.indexOf("://")
    if (schemeIdx <= 0) return value
    val afterScheme = schemeIdx + 3
    val pathIdx = value.indexOf('/', afterScheme).let { if (it < 0) value.length else it }
    val queryIdx = value.indexOf('?', afterScheme).let { if (it < 0) value.length else it }
    val cut = minOf(pathIdx, queryIdx)
    return if (cut < value.length) "${value.substring(0, cut)}/…" else value
}
