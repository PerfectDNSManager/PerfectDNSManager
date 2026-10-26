package app.perfectdnsmanager.data

import android.content.SharedPreferences

object UpdateChannel {
    fun initialize(prefs: SharedPreferences, betaBuild: Boolean) {
        // Only initialize an absent preference; never override an explicit opt-out.
        val edit = prefs.edit()
        if (!prefs.contains("beta_updates_enabled")) edit.putBoolean("beta_updates_enabled", betaBuild)
        if (!betaBuild && prefs.getBoolean("beta_channel_forced", false)) edit.putBoolean("beta_updates_enabled", false)
        edit.remove("beta_channel_forced").apply()
    }
}
