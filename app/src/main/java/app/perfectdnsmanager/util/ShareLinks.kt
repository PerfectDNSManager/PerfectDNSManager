package app.perfectdnsmanager.util

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Strict, shared URL grammar matching the website decrypt form. */
object ShareLinks {
    data class Link(val slug: String, val origin: String? = null)
    private val slug = Regex("^[a-z0-9]{4,16}$")
    private val path = Regex("^/(?:d|r|decrypt)/([a-z0-9]{4,16})/?$", RegexOption.IGNORE_CASE)
    private val hosts = setOf("perfectdnsmanager.app", "beta.perfectdnsmanager.app", "pdm.appstorefr.net")
    fun parse(input: String): Link? = runCatching {
        val text = input.trim()
        require(text.length <= 2048)
        val bare = text.lowercase(Locale.ROOT)
        if (slug.matches(bare)) return Link(bare)
        val uri = URI(text)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(uri.scheme.equals("https", true) && host in hosts && uri.port == -1 && uri.rawUserInfo == null)
        val code = if (uri.rawPath == "/decrypt.html") {
            uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "s" }
                ?.substringAfter('=', "")?.let { URLDecoder.decode(it, "UTF-8") }
        } else path.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
        val normalized = code?.lowercase(Locale.ROOT)
        require(normalized != null && slug.matches(normalized))
        Link(normalized, "https://$host")
    }.getOrNull()
}
