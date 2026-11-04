package app.perfectdnsmanager.data

import java.util.Locale

/** Immutable snapshot swapped atomically by the VPN; first enabled duplicate wins. */
class DnsRewriteIndex private constructor(private val targets: Map<String, String>) {
    val size: Int get() = targets.size
    fun isNotEmpty(): Boolean = targets.isNotEmpty()
    fun target(domain: String): String? = targets[domain.lowercase(Locale.ROOT)]
    companion object {
        fun empty() = DnsRewriteIndex(emptyMap())
        fun from(rules: List<DnsRewriteRule>): DnsRewriteIndex {
            val targets = HashMap<String, String>()
            for (rule in rules) if (rule.isEnabled) {
                val key = rule.fromDomain.lowercase(Locale.ROOT)
                if (!targets.containsKey(key)) targets[key] = rule.toDomain
            }
            return DnsRewriteIndex(targets)
        }
    }
}
