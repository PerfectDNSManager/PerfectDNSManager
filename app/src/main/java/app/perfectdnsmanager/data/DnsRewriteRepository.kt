package app.perfectdnsmanager.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DnsRewriteRepository(context: Context) {

    private val prefs = context.getSharedPreferences("dns_rewrite_rules", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<DnsRewriteRule>>() {}.type

    fun getAllRules(): MutableList<DnsRewriteRule> {
        val json = prefs.getString("rules", "[]")
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun saveRules(rules: List<DnsRewriteRule>) {
        val json = gson.toJson(rules)
        prefs.edit().putString("rules", json).apply()
    }

    fun addRule(rule: DnsRewriteRule) {
        val rules = getAllRules()
        rules.add(0, rule) // Add to the top
        saveRules(rules)
    }

    fun updateRule(rule: DnsRewriteRule) {
        val rules = getAllRules()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index != -1) {
            rules[index] = rule
            saveRules(rules)
        }
    }

    fun deleteRule(rule: DnsRewriteRule) {
        val rules = getAllRules()
        rules.removeAll { it.id == rule.id }
        saveRules(rules)
    }
}