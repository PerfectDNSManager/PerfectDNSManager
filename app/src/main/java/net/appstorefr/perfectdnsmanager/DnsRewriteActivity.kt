package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository
import net.appstorefr.perfectdnsmanager.data.DnsRewriteRule
import net.appstorefr.perfectdnsmanager.ui.DnsRewriteAdapter
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class DnsRewriteActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var repository: DnsRewriteRepository
    private lateinit var adapter: DnsRewriteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_rewrite)

        repository = DnsRewriteRepository(this)

        val rvRules: RecyclerView = findViewById(R.id.rvRewriteRules)
        rvRules.layoutManager = LinearLayoutManager(this)

        val allRules = repository.getAllRules()
        adapter = DnsRewriteAdapter(allRules, {
            repository.updateRule(it)
        }, {
            repository.deleteRule(it)
        })
        rvRules.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddRule).setOnClickListener { showAddRuleDialog() }
    }

    private fun showAddRuleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_rewrite_rule, null)
        val etFromDomain = dialogView.findViewById<EditText>(R.id.etFromDomain)
        val etToDomain = dialogView.findViewById<EditText>(R.id.etToDomain)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_rewrite_rule_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val fromDomain = etFromDomain.text.toString().trim()
                val toDomain = etToDomain.text.toString().trim()
                if (fromDomain.isNotEmpty() && toDomain.isNotEmpty()) {
                    val newRule = DnsRewriteRule(fromDomain = fromDomain, toDomain = toDomain)
                    repository.addRule(newRule)
                    adapter.addRule(newRule)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
