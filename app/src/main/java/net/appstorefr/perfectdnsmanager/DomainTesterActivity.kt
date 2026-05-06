package net.appstorefr.perfectdnsmanager

import net.appstorefr.perfectdnsmanager.util.pdmBackground
import net.appstorefr.perfectdnsmanager.util.pdmSurface
import net.appstorefr.perfectdnsmanager.util.pdmSurfaceInput
import net.appstorefr.perfectdnsmanager.util.pdmTextPrimary
import net.appstorefr.perfectdnsmanager.util.pdmTextSecondary
import net.appstorefr.perfectdnsmanager.util.pdmTextDisabled
import net.appstorefr.perfectdnsmanager.util.pdmAccent
import net.appstorefr.perfectdnsmanager.util.pdmAccentAlt
import net.appstorefr.perfectdnsmanager.util.pdmDanger
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.gson.Gson
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.data.ProfileManager
import net.appstorefr.perfectdnsmanager.service.DnsVpnService
import net.appstorefr.perfectdnsmanager.util.DnsTester
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import net.appstorefr.perfectdnsmanager.util.UrlBlockingTester
import org.json.JSONArray
import org.json.JSONObject

class DomainTesterActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private data class TestDomainEntry(val domain: String, val enabled: Boolean)

    /** A DNS server option for the selector */
    private data class DnsOption(val label: String, val ip: String)

    private lateinit var listContainer: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var btnRunTest: Button
    private lateinit var btnDnsSelect: Button
    private var testThread: Thread? = null
    private var isTesting = false

    /** Selected DNS options (empty = "All" mode = ISP + active DNS only) */
    private val selectedDnsOptions = mutableSetOf<DnsOption>()
    private var allDnsOptions = listOf<DnsOption>()
    private var isAllSelected = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Load available DNS providers (DEFAULT/UDP only, for direct DNS queries)
        allDnsOptions = loadDnsOptions()

        val root = ScrollView(this).apply {
            setBackgroundColor(pdmBackground())
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(mainLayout)

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val btnBack = Button(this).apply {
            text = getString(R.string.back_arrow)
            setTextColor(pdmTextPrimary())
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            setPadding(20, 10, 20, 10)
            setOnClickListener { finish() }
        }
        header.addView(btnBack)
        val tvTitle = TextView(this).apply {
            text = getString(R.string.domain_tester_title)
            setTextColor(pdmTextPrimary())
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvTitle)
        mainLayout.addView(header)

        // Spacer
        mainLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })

        // DNS status indicator
        val tvStatus = TextView(this).apply {
            textSize = 13f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }

            val vpnRunning = DnsVpnService.isVpnRunning
            val dotMode = android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
            val isDotActive = dotMode == "hostname"

            if (vpnRunning) {
                val providerLabel = try {
                    val profileJson = getSharedPreferences("prefs", MODE_PRIVATE).getString("selected_profile_json", null)
                    if (profileJson != null) {
                        val profile = Gson().fromJson(profileJson, DnsProfile::class.java)
                        profile.providerName
                    } else null
                } catch (_: Exception) { null }
                text = if (providerLabel != null) getString(R.string.dt_vpn_active_fmt, providerLabel) else getString(R.string.dt_vpn_active)
                setTextColor(pdmAccent())
            } else if (isDotActive) {
                text = getString(R.string.dt_dot_active)
                setTextColor(pdmAccent())
            } else {
                text = getString(R.string.dt_no_dns)
                setTextColor(pdmDanger())
            }
        }
        mainLayout.addView(tvStatus)

        // Add domain button
        val btnAdd = Button(this).apply {
            text = getString(R.string.domain_tester_add)
            setTextColor(pdmAccent())
            setBackgroundResource(R.drawable.pdm_btn_primary)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener { showAddDomainDialog() }
        }
        mainLayout.addView(btnAdd)

        // Domain list container
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        // Spacer
        mainLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
        })

        // DNS selector — pattern bouton + dialog picker (idem InternetSpeedtest)
        btnDnsSelect = Button(this).apply {
            text = dnsButtonText()
            setTextColor(pdmAccentAlt())
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener { showDnsPickerDialog() }
        }
        mainLayout.addView(btnDnsSelect)

        // Spacer
        mainLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
        })

        // Run test button
        btnRunTest = Button(this).apply {
            text = getString(R.string.domain_tester_run)
            setTextColor(pdmAccentAlt())
            setBackgroundResource(R.drawable.pdm_btn_info)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener {
                if (isTesting) stopTest() else runTest()
            }
        }
        mainLayout.addView(btnRunTest)

        // Results area
        tvResult = TextView(this).apply {
            setTextColor(pdmTextSecondary())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(pdmSurfaceInput())
            setPadding(16, 16, 16, 16)
            text = getString(R.string.domain_tester_no_result)
        }
        mainLayout.addView(tvResult)

        setContentView(root)
        refreshList()
        btnBack.requestFocus()
    }

    /** Load DNS provider options: one entry per provider with DEFAULT (UDP) IPs */
    private fun loadDnsOptions(): List<DnsOption> {
        val profileManager = ProfileManager(this)
        val allProfiles = profileManager.loadProfiles()

        // Group DEFAULT (UDP) profiles by provider, take first one per provider
        val seen = mutableSetOf<String>()
        val options = mutableListOf<DnsOption>()
        for (profile in allProfiles) {
            if (profile.type != DnsType.DEFAULT) continue
            if (profile.isOperatorDns) continue
            val key = profile.providerName
            if (key in seen) continue
            seen.add(key)
            options.add(DnsOption(profile.providerName, profile.primary))
        }
        return options
    }

    /** Résumé pour le bouton : "Tous", "Cloudflare", "Cloudflare + Google", "3 DNS sélectionnés". */
    private fun dnsSelectionSummary(): String {
        if (isAllSelected) return getString(R.string.domain_tester_dns_all)
        val labels = selectedDnsOptions.map { it.label }
        return when {
            labels.isEmpty() -> getString(R.string.domain_tester_dns_all)
            labels.size <= 2 -> labels.joinToString(" + ")
            else -> getString(R.string.domain_tester_dns_count_fmt, labels.size)
        }
    }

    private fun dnsButtonText(): String =
        getString(R.string.domain_tester_dns_button_fmt, dnsSelectionSummary())

    /**
     * Dialog picker : "Tous" + un checkbox par DNS provider. Multi-select
     * préservé. "Tous" décoche les autres et inversement (cohérence : "Tous"
     * == aucun individuel).
     */
    private fun showDnsPickerDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 16)
            setBackgroundColor(pdmSurface())
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.domain_tester_dns_picker_title)
            setTextColor(pdmTextPrimary())
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 320).toInt()
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll)

        // État local du dialog (validé seulement au clic OK).
        var localAllSelected = isAllSelected
        val localSelected = selectedDnsOptions.toMutableSet()

        val cbProviders = mutableListOf<Pair<DnsOption, android.widget.CheckBox>>()
        val cbAll = android.widget.CheckBox(this).apply {
            text = getString(R.string.domain_tester_dns_all)
            setTextColor(pdmTextPrimary())
            textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(pdmAccentAlt())
            background = resources.getDrawable(R.drawable.focusable_item_background, theme)
            setPadding(16, 20, 16, 20)
            isFocusable = true
            isChecked = localAllSelected
        }
        cbAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                localAllSelected = true
                localSelected.clear()
                cbProviders.forEach { it.second.isChecked = false }
            } else if (localSelected.isEmpty()) {
                // Décocher "Tous" sans rien d'autre coché → re-cocher "Tous"
                cbAll.isChecked = true
            }
        }
        list.addView(cbAll)

        for (option in allDnsOptions) {
            val cb = android.widget.CheckBox(this).apply {
                text = option.label
                setTextColor(pdmTextPrimary())
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(pdmAccentAlt())
                background = resources.getDrawable(R.drawable.focusable_item_background, theme)
                setPadding(16, 20, 16, 20)
                isFocusable = true
                isChecked = !localAllSelected && option in localSelected
            }
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    localSelected.add(option)
                    if (localAllSelected) {
                        localAllSelected = false
                        cbAll.isChecked = false
                    }
                } else {
                    localSelected.remove(option)
                    if (localSelected.isEmpty()) {
                        localAllSelected = true
                        cbAll.isChecked = true
                    }
                }
            }
            cbProviders.add(option to cb)
            list.addView(cb)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                isAllSelected = localAllSelected
                selectedDnsOptions.clear()
                if (!localAllSelected) selectedDnsOptions.addAll(localSelected)
                btnDnsSelect.text = dnsButtonText()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadEntries(): MutableList<TestDomainEntry> {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val json = prefs.getString("test_domains_json", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                val list = mutableListOf<TestDomainEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(TestDomainEntry(obj.getString("domain"), obj.optBoolean("enabled", true)))
                }
                return list
            } catch (_: Exception) {}
        }
        return mutableListOf(TestDomainEntry("ygg.re", true))
    }

    private fun saveEntries(entries: List<TestDomainEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("domain", e.domain)
                put("enabled", e.enabled)
            })
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putString("test_domains_json", arr.toString())
            .apply()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val entries = loadEntries()
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.focusable_item_background)
                isFocusable = true
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8
                }
            }

            val tvDomain = TextView(this).apply {
                text = entry.domain
                setTextColor(pdmTextPrimary())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvDomain)

            val sw = SwitchCompat(this).apply {
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    val list = loadEntries()
                    if (index < list.size) {
                        list[index] = list[index].copy(enabled = isChecked)
                        saveEntries(list)
                    }
                }
            }
            row.addView(sw)
            row.setOnClickListener {
                sw.isChecked = !sw.isChecked
            }

            // Long-press -> edit/delete popup
            row.setOnLongClickListener {
                net.appstorefr.perfectdnsmanager.util.TvDialog.showMenuPicker(
                    this@DomainTesterActivity,
                    entry.domain,
                    arrayOf("Modifier", "Supprimer")
                ) { which ->
                    when (which) {
                        0 -> showEditDomainDialog(index, entry)
                        1 -> {
                            AlertDialog.Builder(this@DomainTesterActivity)
                                .setTitle(getString(R.string.domain_tester_delete_title))
                                .setMessage(entry.domain)
                                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                    val list = loadEntries()
                                    if (index < list.size) {
                                        list.removeAt(index)
                                        saveEntries(list)
                                        refreshList()
                                    }
                                }
                                .setNegativeButton(getString(R.string.cancel), null)
                                .show()
                        }
                    }
                }
                true
            }

            listContainer.addView(row)
        }
    }

    private fun showAddDomainDialog() {
        val et = EditText(this).apply {
            hint = "example.com"
            setTextColor(pdmTextPrimary())
            setHintTextColor(pdmTextDisabled())
            setBackgroundColor(pdmSurfaceInput())
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.domain_tester_add_title))
            .setView(et)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val domain = et.text.toString().trim()
                if (domain.isNotEmpty()) {
                    val list = loadEntries()
                    list.add(TestDomainEntry(domain, true))
                    saveEntries(list)
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDomainDialog(index: Int, entry: TestDomainEntry) {
        val et = EditText(this).apply {
            setText(entry.domain)
            setTextColor(pdmTextPrimary())
            setBackgroundColor(pdmSurfaceInput())
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.domain_tester_edit_title))
            .setView(et)
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                val domain = et.text.toString().trim()
                if (domain.isNotEmpty()) {
                    val list = loadEntries()
                    if (index < list.size) {
                        list[index] = list[index].copy(domain = domain)
                        saveEntries(list)
                        refreshList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun runTest() {
        isTesting = true
        btnRunTest.text = getString(R.string.btn_stop)
        btnRunTest.setTextColor(pdmDanger())
        btnRunTest.setBackgroundResource(R.drawable.pdm_btn_danger)
        tvResult.text = getString(R.string.domain_tester_testing)

        // Snapshot selected DNS options: "Tous" = all providers, otherwise only selected
        val dnsToTest = if (isAllSelected) allDnsOptions.toList() else selectedDnsOptions.toList()

        testThread = Thread {
            val entries = loadEntries().filter { it.enabled }
            val sb = StringBuilder()
            for (entry in entries) {
                if (Thread.currentThread().isInterrupted) break
                try {
                    sb.appendLine("${entry.domain} :")

                    // Always test ISP DNS first
                    val ispResult = UrlBlockingTester.resolveViaProtectedSocket(this, entry.domain)
                    val ispIcon = if (ispResult.isBlocked) "\u274C" else "\u2705"
                    val ispLabel = if (ispResult.authorityLabel != null)
                        "${ispResult.ip ?: "No A record"} (${ispResult.authorityLabel})"
                    else
                        ispResult.ip ?: "No A record"
                    sb.appendLine("  DNS FAI     : $ispIcon $ispLabel")

                    // Test each DNS provider
                    val unblocking = mutableListOf<String>()
                    for (dnsOpt in dnsToTest) {
                        if (Thread.currentThread().isInterrupted) break
                        val dnsResult = DnsTester.execute(dnsOpt.ip, entry.domain)
                        val icon: String
                        val resultIp: String
                        if (dnsResult != null) {
                            icon = if (dnsResult.isBlocked) "\u274C" else "\u2705"
                            resultIp = dnsResult.ip
                            if (!dnsResult.isBlocked && ispResult.isBlocked) {
                                unblocking.add(dnsOpt.label)
                            }
                        } else {
                            icon = "\u26A0\uFE0F"
                            resultIp = getString(R.string.result_error)
                        }
                        val padded = dnsOpt.label.padEnd(12)
                        sb.appendLine("  $padded: $icon $resultIp")
                    }

                    if (unblocking.isNotEmpty()) {
                        sb.appendLine("  \u2192 ${unblocking.joinToString(" et ")} d\u00E9bloque${if (unblocking.size > 1) "nt" else ""} le contenu")
                    } else if (!ispResult.isBlocked) {
                        sb.appendLine("  \u2192 Non bloqu\u00E9 par le FAI")
                    }
                    sb.appendLine()
                } catch (e: Exception) {
                    sb.appendLine("${entry.domain} : Erreur - ${e.message}")
                    sb.appendLine()
                }
                runOnUiThread { tvResult.text = sb.toString() }
            }
            runOnUiThread {
                isTesting = false
                btnRunTest.text = getString(R.string.domain_tester_run)
                btnRunTest.setTextColor(pdmAccentAlt())
                btnRunTest.setBackgroundResource(R.drawable.pdm_btn_info)
                if (sb.isEmpty()) tvResult.text = getString(R.string.domain_tester_no_enabled)
            }
        }
        testThread?.start()
    }

    private fun stopTest() {
        testThread?.interrupt()
        isTesting = false
        btnRunTest.text = getString(R.string.domain_tester_run)
        btnRunTest.setTextColor(pdmAccentAlt())
        btnRunTest.setBackgroundResource(R.drawable.pdm_btn_info)
    }
}
