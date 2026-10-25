package app.perfectdnsmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
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
    private val selectedProtocols = linkedSetOf<DnsType>()
    private val protocolButtons = mutableMapOf<DnsType, CheckBox>()

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
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val restored = savedInstanceState?.getStringArrayList("protocols")
        if (restored != null) {
            selectedProtocols.addAll(DnsType.entries.filter { it.name in restored })
        } else {
            selectedProtocols.add(DnsType.DOH)
            if (prefs.getBoolean("show_doq_dns", false)) selectedProtocols.add(DnsType.DOQ)
            if (prefs.getBoolean("adb_dot_enabled", false)) selectedProtocols.add(DnsType.DOT)
            if (prefs.getBoolean("show_standard_dns", false) || prefs.getBoolean("operator_dns_enabled", false)) selectedProtocols.add(DnsType.DEFAULT)
        }
        val filters = findViewById<LinearLayout>(R.id.protocolFilters)
        for (type in listOf(DnsType.DOH, DnsType.DOT, DnsType.DOQ, DnsType.DEFAULT)) {
            val checkbox = CheckBox(this).apply {
                id = android.view.View.generateViewId()
                nextFocusUpId = R.id.btnBack
                nextFocusDownId = R.id.btnSpeedtest
                text = when(type) { DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; DnsType.DOQ -> "DoQ"; DnsType.DEFAULT -> "UDP" }
                minHeight = (48 * resources.displayMetrics.density).toInt()
                textSize = 13f
                isChecked = type in selectedProtocols
                isEnabled = type != DnsType.DOT || prefs.getBoolean("adb_dot_enabled", false)
                background = androidx.core.content.ContextCompat.getDrawable(this@DnsSelectionActivity, R.drawable.pdm_btn_primary)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedProtocols.add(type) else selectedProtocols.remove(type)
                    showProviders()
                }
            }
            protocolButtons[type] = checkbox
            filters.addView(checkbox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        btnBack.nextFocusDownId = protocolButtons.getValue(DnsType.DOH).id
        btnSpeedtest.nextFocusUpId = protocolButtons.getValue(DnsType.DOH).id
        btnAddProfile.nextFocusUpId = protocolButtons.getValue(DnsType.DEFAULT).id
        tvMethodInfo.setText(R.string.protocol_filter_hint)

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
        val dotEnabled = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("adb_dot_enabled", false)
        protocolButtons[DnsType.DOT]?.isEnabled = dotEnabled
        if (!dotEnabled) { selectedProtocols.remove(DnsType.DOT); protocolButtons[DnsType.DOT]?.isChecked = false }
        showProviders()
    }

    private val providerOrder = listOf(
        "ControlD", "NextDNS", "AdGuard", "Surfshark",
        "Mullvad", "Cloudflare", "Quad9", "xdp.es", "FDN", "dns.sb", "Yandex", "Google"
    )

    private fun showProviders() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val operatorEnabled = prefs.getBoolean("operator_dns_enabled", false)
        val adbDotEnabled = prefs.getBoolean("adb_dot_enabled", false)
        val showProfileVariants = prefs.getBoolean("show_profile_variants", false)

        // Protocol filters change the catalog, never the active connection or settings.
        val baseFiltered = allProfiles.filter { profile ->
            profile.type in selectedProtocols &&
                (!profile.isOperatorDns || operatorEnabled) &&
                (profile.type != DnsType.DOT || adbDotEnabled)
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
                    putExtra("PROTOCOL_FILTER", selectedProtocols.map { it.name }.toTypedArray())
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
        findViewById<TextView>(R.id.tvFilterResults).text = if (filtered.isEmpty()) getString(R.string.protocol_filter_empty)
            else getString(R.string.protocol_filter_count, grouped.size, filtered.size)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("protocols", ArrayList(selectedProtocols.map { it.name }))
        super.onSaveInstanceState(outState)
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
