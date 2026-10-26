package app.perfectdnsmanager

import android.content.SharedPreferences
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType

internal object DnsProtocolVisibility {
    fun allowedTypes(prefs: SharedPreferences): Set<DnsType> = buildSet {
        add(DnsType.DOH)
        if (prefs.getBoolean("adb_dot_enabled", false)) add(DnsType.DOT)
        if (prefs.getBoolean("show_doq_dns", false)) add(DnsType.DOQ)
        if (prefs.getBoolean("show_standard_dns", false) || prefs.getBoolean("operator_dns_enabled", false)) add(DnsType.DEFAULT)
    }

    fun isVisible(profile: DnsProfile, prefs: SharedPreferences): Boolean {
        if (profile.isOperatorDns && !prefs.getBoolean("operator_dns_enabled", false)) return false
        if (profile.type == DnsType.DEFAULT && !profile.isOperatorDns && !prefs.getBoolean("show_standard_dns", false)) return false
        return profile.type in allowedTypes(prefs)
    }
}
