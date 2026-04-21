package net.appstorefr.perfectdnsmanager.data

enum class DnsType {
    DEFAULT,
    DOH, // DNS over HTTPS
    DOT, // DNS over TLS
    DOQ  // DNS over QUIC
}