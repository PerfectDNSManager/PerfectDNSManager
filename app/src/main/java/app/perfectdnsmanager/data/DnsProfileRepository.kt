package app.perfectdnsmanager.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DnsProfileRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("custom_dns_profiles", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<DnsProfile>>() {}.type

    fun getAllProfiles(): List<DnsProfile> {
        return DnsProfile.getDefaultPresets() + getCustomProfiles()
    }

    fun getProfileById(id: Long): DnsProfile? {
        return getAllProfiles().find { it.id == id }
    }

    fun getCustomProfiles(): List<DnsProfile> {
        val json = prefs.getString("profiles", null)
        return if (json != null) {
            gson.fromJson(json, listType)
        } else {
            emptyList()
        }
    }

    fun saveCustomProfile(profile: DnsProfile) {
        val customProfiles = getCustomProfiles().toMutableList()
        customProfiles.add(0, profile)
        val json = gson.toJson(customProfiles)
        prefs.edit().putString("profiles", json).apply()
    }

    fun deleteCustomProfile(profile: DnsProfile) {
        val customProfiles = getCustomProfiles().toMutableList()
        customProfiles.removeAll { it.id == profile.id }
        val json = gson.toJson(customProfiles)
        prefs.edit().putString("profiles", json).apply()
    }
}
