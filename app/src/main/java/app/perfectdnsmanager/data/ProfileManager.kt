package app.perfectdnsmanager.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProfileManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dns_profiles_v2", Context.MODE_PRIVATE)

    // Cache mémoire de la liste parsée (voir companion `cache`). Process-wide :
    // toutes les instances lisent/écrivent le MÊME fichier prefs, donc une
    // écriture via n'importe quelle instance invalide le cache partagé — sinon
    // une instance long-vécue (ex. DnsSelectionActivity) servirait des profils
    // périmés après une modif faite ailleurs.

    private val listType by lazy { object : TypeToken<List<DnsProfile>>() {}.type }

    fun saveProfiles(profiles: List<DnsProfile>) {
        val json = GSON.toJson(profiles)
        prefs.edit().putString("profiles", json).apply()
        cache = profiles
    }

    fun loadProfiles(): List<DnsProfile> {
        cache?.let { return it }
        val merged = computeProfiles()
        cache = merged
        return merged
    }

    private fun computeProfiles(): List<DnsProfile> {
        val json = prefs.getString("profiles", null)
        if (json == null) {
            // Première utilisation : charger les presets
            val defaults = DnsProfile.getDefaultPresets()
            saveProfiles(defaults)
            return defaults
        }
        // Normaliser AVANT de valider : une URL historique (quic://…/p0 en 2.3.2)
        // doit être migrée, pas écartée.
        val raw = runCatching { GSON.fromJson<List<DnsProfile?>>(json, listType) }
            .getOrNull().orEmpty().filterNotNull()
            .map { p ->
                @Suppress("SENSELESS_COMPARISON")
                if (p.primary == null) p
                else p.copy(primary = app.perfectdnsmanager.util.ProfileValidation.normalizeEndpoint(p.primary))
            }
        val saved = raw.filter(app.perfectdnsmanager.util.ProfileValidation::isUsable)
        // Les profils perso qui ne passent pas la nouvelle validation sont masqués
        // mais CONSERVÉS sur disque : la bêta les supprimait définitivement et en
        // silence au premier lancement.
        val unusable = raw.filterNot(app.perfectdnsmanager.util.ProfileValidation::isUsable)
            .filter { it.isCustom }

        // Auto-sync presets : mettre à jour les existants + injecter les manquants
        val defaults = DnsProfile.getDefaultPresets()
        val defaultsById = defaults.associateBy { it.id }
        val savedIds = saved.map { it.id }.toSet()

        // Mettre à jour les presets non-custom existants avec les valeurs actuelles
        val updated = saved.map { profile ->
            if (!profile.isCustom && profile.id in defaultsById) {
                defaultsById[profile.id]!!
            } else {
                profile
            }
        }
        // Ajouter les presets manquants
        val missing = defaults.filter { it.id !in savedIds }
        val merged = updated + missing

        // Réécrire si la fusion a changé quelque chose, ou si des presets obsolètes
        // ont été écartés (les profils perso masqués sont conservés tels quels).
        if (merged != saved || raw.size - saved.size != unusable.size) {
            saveProfiles(merged + unusable)
        }
        return merged
    }

    /** Profils perso présents sur disque mais masqués car non valides (cf. loadProfiles). */
    private fun hiddenCustom(): List<DnsProfile> {
        val json = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching { GSON.fromJson<List<DnsProfile?>>(json, listType) }
            .getOrNull().orEmpty().filterNotNull()
            .filter { it.isCustom && !app.perfectdnsmanager.util.ProfileValidation.isUsable(it) }
    }

    fun addProfile(profile: DnsProfile) {
        val hidden = hiddenCustom()
        val all = loadProfiles().toMutableList()
        all.add(profile)
        saveProfiles(all + hidden)
    }

    fun deleteProfile(profileId: Long) {
        val hidden = hiddenCustom().filter { it.id != profileId }
        val all = loadProfiles().toMutableList()
        all.removeAll { it.id == profileId }
        saveProfiles(all + hidden)
    }

    fun updateProfile(updated: DnsProfile) {
        val hidden = hiddenCustom()
        val all = loadProfiles().toMutableList()
        val index = all.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            all[index] = updated
            saveProfiles(all + hidden)
        }
    }

    fun restoreDefaults() {
        val hidden = hiddenCustom()
        val currentCustom = loadProfiles().filter { it.isCustom }
        val defaults = DnsProfile.getDefaultPresets()
        saveProfiles(defaults + currentCustom + hidden)
    }

    fun resetAll() {
        val defaults = DnsProfile.getDefaultPresets()
        saveProfiles(defaults)
    }

    companion object {
        // Instance Gson partagée : évite d'en allouer une par ProfileManager.
        private val GSON = Gson()
        // Cache partagé process-wide, invalidé à chaque écriture (saveProfiles).
        @Volatile private var cache: List<DnsProfile>? = null
    }
}
