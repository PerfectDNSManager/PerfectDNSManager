package app.perfectdnsmanager.data

import android.content.SharedPreferences

/** IPv6 blocking is opt-in, including installations seeded by older releases. */
object Ipv6Settings {
    private const val MIGRATED = "ipv6_blocking_opt_in_v1"

    fun initialize(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATED, false)) return
        // Older versions stored true automatically, with no record of whether
        // the user chose it. Reset once; subsequent explicit choices/imports
        // remain untouched. Application initialization also covers boot services.
        prefs.edit().putBoolean("disable_ipv6", false)
            .putBoolean(MIGRATED, true).apply()
    }
}
