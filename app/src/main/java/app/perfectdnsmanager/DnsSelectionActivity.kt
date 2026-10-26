package app.perfectdnsmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.data.ProfileManager
import app.perfectdnsmanager.ui.AddProfileDialog
import app.perfectdnsmanager.ui.ProviderAdapter
import app.perfectdnsmanager.util.LocaleHelper
import com.google.gson.Gson

class DnsSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var rvProviders: RecyclerView
    private lateinit var btnAddProfile: Button
    private lateinit var btnSpeedtest: Button
    private lateinit var btnBack: Button
    private lateinit var tvMethodInfo: TextView
    private lateinit var profileManager: ProfileManager
    private var allProfiles = listOf<DnsProfile>()

    private val providerDetailLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra("SELECTED_PROFILE_JSON")
            if (json != null) {
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("SELECTED_PROFILE_JSON", json)
                })
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_selection)

        profileManager = ProfileManager(this)

        rvProviders = findViewById(R.id.rvDnsList)
        btnAddProfile = findViewById(R.id.btnAddProfile)
        btnSpeedtest = findViewById(R.id.btnSpeedtest)
        btnBack = findViewById(R.id.btnBack)
        tvMethodInfo = findViewById(R.id.tvMethodInfo)

        rvProviders.layoutManager = LinearLayoutManager(this)
        tvMethodInfo.visibility = android.view.View.GONE

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }
        btnAddProfile.setOnClickListener { showAddProfileDialog() }
        btnSpeedtest.setOnClickListener { runDnsSpeedtest() }
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
    }

    private fun loadProfiles() {
        allProfiles = profileManager.loadProfiles()
        showProviders()
    }

    private val providerOrder = app.perfectdnsmanager.data.ProfileCatalog.providerOrder

    private fun showProviders() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val selectedProtocols = DnsProtocolVisibility.allowedTypes(prefs)
        val showProfileVariants = prefs.getBoolean("show_profile_variants", false)

        // Settings control visibility without removing entries from the loaded catalog.
        val baseFiltered = allProfiles.filter { profile ->
            DnsProtocolVisibility.isVisible(profile, prefs)
        }

        val filtered = app.perfectdnsmanager.data.ProfileCatalog.visible(
            baseFiltered, prefs.getBoolean("allow_adblock_profiles", false), showProfileVariants)

        val grouped = linkedMapOf<String, List<DnsProfile>>()

        // Fournisseurs dans l'ordre défini
        for (name in providerOrder) {
            val profiles = filtered.filter { it.providerName == name }
            if (profiles.isNotEmpty()) {
                grouped[name] = profiles
            }
        }

        // Fournisseurs hors classement (hors opérateurs)
        for ((pName, value) in filtered.groupBy { it.providerName }.toSortedMap()) {
            if (pName !in providerOrder && !value.any { it.isOperatorDns }) {
                grouped[pName] = value
            }
        }

        // DNS Opérateurs en dernier
        for ((pName, value) in filtered.groupBy { it.providerName }.toSortedMap()) {
            if (value.any { it.isOperatorDns }) {
                grouped[pName] = value
            }
        }

        val adapter = ProviderAdapter(
            grouped,
            onProviderLongClick = { providerName, profiles ->
                val allProfiles = if (providerName == "NextDNS") {
                    val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                    val savedIds = customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()
                    val prefsMain = getSharedPreferences("prefs", MODE_PRIVATE)
                    val adbDotEnabled = prefsMain.getBoolean("adb_dot_enabled", false)
                    val customProfiles = mutableListOf<DnsProfile>()
                    for (pid in savedIds.sorted()) {
                        customProfiles.add(DnsProfile(
                            providerName = "NextDNS", name = getString(R.string.nextdns_profile_name_fmt, pid), type = DnsType.DOH,
                            primary = "https://dns.nextdns.io/$pid", descResId = R.string.dns_desc_nextdns_custom,
                            isCustom = true, testUrl = "https://test.nextdns.io/"
                        ))
                        customProfiles.add(DnsProfile(
                            providerName = "NextDNS", name = getString(R.string.nextdns_profile_name_fmt, pid), type = DnsType.DOQ,
                            primary = "quic://$pid.dns.nextdns.io", descResId = R.string.dns_desc_nextdns_custom,
                            isCustom = true, testUrl = "https://test.nextdns.io/"
                        ))
                        if (adbDotEnabled) {
                            customProfiles.add(DnsProfile(
                                providerName = "NextDNS", name = getString(R.string.nextdns_profile_name_fmt, pid), type = DnsType.DOT,
                                primary = "$pid.dns.nextdns.io", descResId = R.string.dns_desc_nextdns_custom,
                                isCustom = true, testUrl = "https://test.nextdns.io/"
                            ))
                        }
                    }
                    (profiles + customProfiles).filter { it.type in selectedProtocols }.distinctBy { Triple(it.type, it.primary, it.name) }
                } else {
                    profiles
                }
                val isNextDns = providerName == "NextDNS" || providerName == "NextDNS ★"
                val intent = Intent(this, DnsProviderDetailActivity::class.java).apply {
                    putExtra("PROVIDER_NAME", providerName)
                    putExtra("PROFILES_JSON", Gson().toJson(allProfiles))
                    putExtra("IS_NEXTDNS", isNextDns)
                }
                providerDetailLauncher.launch(intent)
            }
        ) { _, profiles ->
            val best = profiles.minByOrNull {
                when (it.type) {
                    DnsType.DOH -> 0; DnsType.DOQ -> 1; DnsType.DOT -> 2; DnsType.DEFAULT -> 3
                }
            }
            if (best != null) returnProfileToMain(best)
        }

        rvProviders.adapter = adapter
    }

    private fun returnProfileToMain(profile: DnsProfile) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("SELECTED_PROFILE_JSON", Gson().toJson(profile))
        })
        finish()
    }

    private fun runDnsSpeedtest() {
        startActivity(Intent(this, DnsSpeedtestActivity::class.java))
    }

    private fun showAddProfileDialog() {
        val advancedEnabled = getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("advanced_features_enabled", false)
        AddProfileDialog(this, advancedEnabled) { newProfile ->
            profileManager.addProfile(newProfile)
            loadProfiles()
        }.show()
    }
}
