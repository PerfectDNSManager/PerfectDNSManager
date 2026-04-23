package net.appstorefr.perfectdnsmanager

import net.appstorefr.perfectdnsmanager.util.pdmBackground
import net.appstorefr.perfectdnsmanager.util.pdmBorder
import net.appstorefr.perfectdnsmanager.util.pdmSurface
import net.appstorefr.perfectdnsmanager.util.pdmSurfaceInput
import net.appstorefr.perfectdnsmanager.util.pdmSurfaceElevated
import net.appstorefr.perfectdnsmanager.util.pdmTextPrimary
import net.appstorefr.perfectdnsmanager.util.pdmTextSecondary
import net.appstorefr.perfectdnsmanager.util.pdmTextDisabled
import net.appstorefr.perfectdnsmanager.util.pdmAccent
import net.appstorefr.perfectdnsmanager.util.pdmAccentAlt
import net.appstorefr.perfectdnsmanager.util.pdmAccentInfo
import net.appstorefr.perfectdnsmanager.util.pdmDanger
import net.appstorefr.perfectdnsmanager.util.pdmWarning

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var dnsChipsContainer: LinearLayout
    private var testThread: Thread? = null
    private var isTesting = false

    /** Selected DNS options (empty = "All" mode = ISP + active DNS only) */
    private val selectedDnsOptions = mutableSetOf<DnsOption>()
    private var allDnsOptions = listOf<DnsOption>()
    private var isAllSelected = true
    private val chipButtons = mutableListOf<Button>()
    private var btnAll: Button? = null

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
                val label = if (providerLabel != null) "\uD83D\uDFE2 VPN actif ($providerLabel)" else "\uD83D\uDFE2 VPN actif"
                text = label
                setTextColor(pdmAccent())
            } else if (isDotActive) {
                text = "\uD83D\uDFE2 DNS priv\u00E9 actif"
                setTextColor(pdmAccent())
            } else {
                text = "\uD83D\uDD34 Aucun DNS actif (r\u00E9solution FAI)"
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

        // DNS selector section
        val tvDnsLabel = TextView(this).apply {
            text = getString(R.string.domain_tester_dns_select)
            setTextColor(pdmTextSecondary())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8
            }
        }
        mainLayout.addView(tvDnsLabel)

        // Horizontal scrollable chips container
        val scrollChips = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            isHorizontalScrollBarEnabled = false
        }
        dnsChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        scrollChips.addView(dnsChipsContainer)
        mainLayout.addView(scrollChips)

        buildDnsChips()

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

    private fun buildDnsChips() {
        dnsChipsContainer.removeAllViews()
        chipButtons.clear()

        // "All" button
        val allBtn = Button(this).apply {
            text = getString(R.string.domain_tester_dns_all)
            textSize = 12f
            setPadding(24, 8, 24, 8)
            isFocusable = true
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8
            }
            setOnClickListener { selectAll() }
        }
        btnAll = allBtn
        dnsChipsContainer.addView(allBtn)

        // One chip per DNS provider
        for (option in allDnsOptions) {
            val btn = Button(this).apply {
                text = option.label
                textSize = 12f
                setPadding(24, 8, 24, 8)
                isFocusable = true
                setBackgroundResource(R.drawable.focusable_item_background)
                foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 8
                }
                tag = option
                setOnClickListener { toggleDnsOption(option) }
            }
            chipButtons.add(btn)
            dnsChipsContainer.addView(btn)
        }

        updateChipColors()
    }

    private fun selectAll() {
        isAllSelected = true
        selectedDnsOptions.clear()
        updateChipColors()
    }

    private fun toggleDnsOption(option: DnsOption) {
        isAllSelected = false
        if (option in selectedDnsOptions) {
            selectedDnsOptions.remove(option)
            if (selectedDnsOptions.isEmpty()) {
                isAllSelected = true
            }
        } else {
            selectedDnsOptions.add(option)
        }
        updateChipColors()
    }

    private fun updateChipColors() {
        val accent = pdmAccentAlt()
        val dimText = pdmTextSecondary()

        fun chipBg(active: Boolean): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(pdmSurface())
                cornerRadius = (8 * resources.displayMetrics.density)
                setStroke(
                    ((if (active) 2 else 1) * resources.displayMetrics.density).toInt(),
                    if (active) accent else pdmBorder()
                )
            }

        btnAll?.let { btn ->
            btn.background = chipBg(isAllSelected)
            btn.setTextColor(if (isAllSelected) accent else dimText)
        }

        for (btn in chipButtons) {
            val option = btn.tag as DnsOption
            val active = !isAllSelected && option in selectedDnsOptions
            btn.background = chipBg(active)
            btn.setTextColor(if (active) accent else dimText)
        }
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

            val sw = Switch(this).apply {
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
        btnRunTest.text = "\u23F9 Stop"
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
                            resultIp = "Erreur"
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
