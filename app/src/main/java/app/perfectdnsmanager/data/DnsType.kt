package app.perfectdnsmanager.data

enum class DnsType {
    DEFAULT,
    DOH, // DNS over HTTPS
    DOT, // Android Private DNS over TLS
    DOQ, // DNS over QUIC
    UNBOUND // On-demand local resolver: explicit recursion or authenticated DoT forwarding
}
