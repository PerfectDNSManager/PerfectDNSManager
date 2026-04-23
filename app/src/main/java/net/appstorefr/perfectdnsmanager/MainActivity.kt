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
import net.appstorefr.perfectdnsmanager.util.pdmAccentGold
import net.appstorefr.perfectdnsmanager.util.pdmDanger
import net.appstorefr.perfectdnsmanager.util.pdmWarning

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.service.AdbDnsManager
import net.appstorefr.perfectdnsmanager.service.DnsVpnService
import net.appstorefr.perfectdnsmanager.service.UpdateManager
import net.appstorefr.perfectdnsmanager.util.DnsLeakTester
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import net.appstorefr.perfectdnsmanager.util.SpeedTester
import net.appstorefr.perfectdnsmanager.util.UrlBlockingTester
import com.google.gson.Gson

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var btnToggle: Button
    private lateinit var btnLanguage: Button
    private lateinit var layoutSelectDns: LinearLayout
    private lateinit var ivSelectedDnsIcon: ImageView
    private lateinit var tvSelectDns: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var tvStatusInfo: TextView
    private lateinit var btnDomainTester: Button
    private lateinit var btnSpeedtest: Button
    private lateinit var btnGenerateReport: Button
    private lateinit var tvReportContent: TextView
    private lateinit var btnShareReport: Button
    private lateinit var adbManager: AdbDnsManager
    private lateinit var prefs: SharedPreferences

    private var selectedProfile: DnsProfile? = null
    private var pendingVpnProfile: DnsProfile? = null
    private var isActive = false
    private var isActivating = false
    private var isGenerating = false
    private var generatingThread: Thread? = null
    private var lastSpeedResult: SpeedTester.SpeedResult? = null
    private var lastLeakResult: DnsLeakTester.LeakResult? = null
    private var lastLeakIspResult: DnsLeakTester.LeakResult? = null
    private var lastBlockingResult: UrlBlockingTester.BlockingResult? = null
    private var lastIpv4: String? = null
    private var lastIpv6: String? = null
    private var lastCarrierName: String? = null
    private var reportGenerated = false

    /** Détermine la méthode d'application depuis le type de profil. DoT → ADB, sinon → VPN */
    private fun methodForProfile(profile: DnsProfile?): String {
        return if (profile?.type == DnsType.DOT) "ADB" else "VPN"
    }

    private fun typeLabelFor(type: DnsType) = when (type) {
        DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; DnsType.DOQ -> "DoQ"; DnsType.DEFAULT -> "Standard"
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val profile = pendingVpnProfile ?: selectedProfile
        if (profile != null && VpnService.prepare(this) == null) {
            startVpnService(profile)
        } else if (profile != null) {
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        } else {
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
        }
        pendingVpnProfile = null
    }

    private val dnsSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val profileJson = result.data?.getStringExtra("SELECTED_PROFILE_JSON")
            if (profileJson != null) {
                val newProfile = Gson().fromJson(profileJson, DnsProfile::class.java)
                val wasActive = isActive
                selectedProfile = newProfile
                prefs.edit().putString("selected_profile_json", profileJson).apply()
                updateSelectButtonText()
                // Auto-reconnexion : si DNS était actif, reconnecter avec le nouveau profil
                if (wasActive) {
                    val adbWasActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
                    val vpnWasActive = DnsVpnService.isVpnRunning || prefs.getBoolean("vpn_active", false)
                    val newMethod = methodForProfile(newProfile)  // "VPN" ou "ADB"

                    // D'abord, stopper l'ancienne méthode proprement
                    if (adbWasActive) {
                        Thread {
                            adbManager.disablePrivateDns()
                            runOnUiThread { setInactiveStatus(); applyDns() }
                        }.start()
                    } else if (vpnWasActive && newMethod == "ADB") {
                        // VPN→DoT : stopper le VPN, puis appliquer en ADB
                        startService(Intent(this@MainActivity, DnsVpnService::class.java).apply {
                            action = DnsVpnService.ACTION_STOP
                        })
                        prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
                        setInactiveStatus()
                        applyDns()
                    } else if (vpnWasActive && newMethod == "VPN") {
                        // VPN→VPN : restart le VPN avec le nouveau profil
                        val svcIntent = Intent(this@MainActivity, DnsVpnService::class.java).apply {
                            action = DnsVpnService.ACTION_RESTART
                            putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, newProfile.primary)
                            newProfile.secondary?.let { putExtra(DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            startForegroundService(svcIntent) else startService(svcIntent)
                        val label = "DNS via VPN: ${newProfile.providerName}\n${newProfile.primary}"
                        prefs.edit()
                            .putBoolean("vpn_active", true)
                            .putString("vpn_label", label)
                            .putString("last_method", "VPN")
                            .apply()
                        setActiveStatus(true, label)
                    } else {
                        // Aucune méthode active détectée, appliquer normalement
                        applyDns()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        adbManager = AdbDnsManager(this)

        // Premier lancement : si pas de langue choisie, définir la langue du système
        if (prefs.getString("language", null) == null) {
            val sysLang = java.util.Locale.getDefault().language
            val supported = listOf("fr", "en", "es", "it", "pt", "ru", "zh", "ar", "hi", "bn", "ja", "de")
            val lang = if (sysLang in supported) sysLang else "en"
            prefs.edit().putString("language", lang).apply()
        }

        // Migration de version : rafraîchir les presets DNS si la version a changé
        checkVersionMigration()

        initViews()
        applyGoldenIndicators()
        restoreState()
        setupUI()

        // Vérification auto des mises à jour au lancement
        checkForAppUpdate()

        // Récupère le message broadcast du Worker (pdm.appstorefr.net/api/message)
        net.appstorefr.perfectdnsmanager.util.MessageManager.fetchAndShow(this)
    }

    private fun checkForAppUpdate() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        UpdateManager(this).checkOnLaunch(currentVersion)
        // Sync blocking authorities list in background
        Thread {
            net.appstorefr.perfectdnsmanager.util.BlockingAuthoritiesManager.syncFromRemote(this)
        }.start()
    }

    /**
     * Affiche en permanence les infos réseau + statut DNS dans le panneau droit (tvStatusInfo).
     * Contient : type connexion, opérateur, IP locale, IPv4, IPv6, ISP, statut DNS (vert/rouge).
     */
    private fun refreshIpDisplay() {
        Thread {
            val httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            fun quickGet(url: String): String? = try {
                val req = okhttp3.Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string()?.trim()
                resp.close()
                if (resp.isSuccessful && !body.isNullOrEmpty()) body else null
            } catch (_: Exception) { null }

            // IPv4
            val ipv4 = quickGet("https://api4.ipify.org")
                ?: quickGet("https://ipv4.icanhazip.com")

            // IPv6
            val ipv6 = run {
                val v6 = quickGet("https://api6.ipify.org")
                    ?: quickGet("https://ipv6.icanhazip.com")
                if (v6 != null && v6.contains(":")) v6 else null
            }

            // IP locale
            val localIp = try {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.hostAddress ?: "N/A"
            } catch (_: Exception) { "N/A" }

            // Type de connexion
            val connType = try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val nc = cm.getNetworkCapabilities(cm.activeNetwork)
                    when {
                        nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                        nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "4G/5G"
                        else -> getString(R.string.report_conn_unknown)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when (cm.activeNetworkInfo?.type) {
                        android.net.ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                        android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                        android.net.ConnectivityManager.TYPE_MOBILE -> "4G/5G"
                        else -> getString(R.string.report_conn_unknown)
                    }
                }
            } catch (_: Exception) { getString(R.string.report_conn_unknown) }

            // Op\u00e9rateur
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val carrierName = telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: telephonyManager?.simOperatorName?.takeIf { it.isNotBlank() }
                ?: ""

            // ISP info
            val ispInfo = try {
                val ispJson = quickGet("https://ipinfo.io/${ipv4 ?: ""}/json")
                if (ispJson != null) {
                    val obj = org.json.JSONObject(ispJson)
                    obj.optString("org", "")
                } else ""
            } catch (_: Exception) { "" }

            // DNS status check (VPN + DoT)
            val vpnRunning = DnsVpnService.isVpnRunning
            val dotActive = try {
                val mode = android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
                mode == "hostname"
            } catch (_: Exception) { false }

            val dnsStatusText: String
            val dnsActive: Boolean
            when {
                vpnRunning -> {
                    val profile = selectedProfile
                    dnsStatusText = if (profile != null) {
                        "DNS actif: VPN (${profile.providerName})"
                    } else {
                        "DNS actif: VPN"
                    }
                    dnsActive = true
                }
                dotActive -> {
                    val host = try {
                        android.provider.Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""
                    } catch (_: Exception) { "" }
                    val profile = selectedProfile
                    dnsStatusText = if (profile != null) {
                        "DNS actif: DoT (${profile.providerName})"
                    } else if (host.isNotEmpty()) {
                        "DNS actif: DoT ($host)"
                    } else {
                        "DNS actif: DoT"
                    }
                    dnsActive = true
                }
                else -> {
                    dnsStatusText = getString(R.string.no_active_dns)
                    dnsActive = false
                }
            }

            // Sauvegarder les IPs pour le rapport
            lastIpv4 = ipv4
            lastIpv6 = ipv6
            lastCarrierName = carrierName.ifEmpty { null }

            val display = StringBuilder()
            display.appendLine("\u2501\u2501\u2501 ${getString(R.string.share_toggle_network)} \u2501\u2501\u2501")
            display.appendLine("Connexion : $connType")
            if (carrierName.isNotEmpty()) {
                display.appendLine("Op\u00e9rateur : $carrierName")
            }
            if (ispInfo.isNotEmpty()) {
                display.appendLine("FAI : $ispInfo")
            }
            display.appendLine("${getString(R.string.md_local_ip)} : $localIp")
            display.appendLine("IPv4 : ${ipv4 ?: getString(R.string.wan_ip_error)}")
            val ipv6Display = ipv6 ?: getString(R.string.wan_ipv6_blocked)
            display.appendLine("IPv6 : $ipv6Display")
            display.appendLine()
            display.appendLine(dnsStatusText)
            selectedProfile?.let {
                display.appendLine("${getString(R.string.report_label_profile)} : ${it.providerName} - ${it.name}")
            }

            runOnUiThread {
                val spannable = android.text.SpannableStringBuilder(display.toString().trimEnd())
                // Color the DNS status line
                val fullText = spannable.toString()
                val dnsLineStart = fullText.indexOf(dnsStatusText)
                if (dnsLineStart >= 0) {
                    val color = if (dnsActive) pdmAccent() else pdmDanger()
                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        dnsLineStart,
                        dnsLineStart + dnsStatusText.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        dnsLineStart,
                        dnsLineStart + dnsStatusText.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tvStatusInfo.setTextColor(pdmTextSecondary())
                tvStatusInfo.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvStatusInfo.text = spannable
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (!isActivating) {
            // Gestion AUTO_RECONNECT (notification boot)
            // Priorité : DNS par défaut > dernier DNS sélectionné
            if (intent?.getBooleanExtra("AUTO_RECONNECT", false) == true) {
                intent?.removeExtra("AUTO_RECONNECT")
                val defaultJson = prefs.getString("default_profile_json", null)
                val selectedJson = prefs.getString("selected_profile_json", null)
                val profileJson = defaultJson ?: selectedJson
                if (profileJson != null && !DnsVpnService.isVpnRunning) {
                    try {
                        val profile = Gson().fromJson(profileJson, DnsProfile::class.java)
                        selectedProfile = profile
                        prefs.edit().putString("selected_profile_json", profileJson).apply()
                        updateSelectButtonText()
                        if (methodForProfile(profile) == "VPN") applyDnsViaVpn(profile)
                    } catch (_: Exception) {}
                }
            }
            checkStatus()
        }
        // Afficher les infos IP automatiquement
        refreshIpDisplay()
    }

    private fun applyGoldenIndicators() {
        val gold = pdmAccentGold()
        val tvProvider: TextView = findViewById(R.id.tvDnsProviderLabel)
        val tvActivation: TextView = findViewById(R.id.tvActivationLabel)
        val tvTools: TextView = findViewById(R.id.tvToolsLabel)
        for (tv in listOf(tvProvider, tvActivation, tvTools)) {
            val text = tv.text.toString()
            val span = SpannableString(text)
            span.setSpan(ForegroundColorSpan(gold), 0, 2, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = span
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        btnLanguage = findViewById(R.id.btnLanguage)
        layoutSelectDns = findViewById(R.id.layoutSelectDns)
        ivSelectedDnsIcon = findViewById(R.id.ivSelectedDnsIcon)
        tvSelectDns = findViewById(R.id.tvSelectDns)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatusInfo = findViewById(R.id.tvStatusInfo)
        btnDomainTester = findViewById(R.id.btnDomainTester)
        btnSpeedtest = findViewById(R.id.btnSpeedtest)
        btnGenerateReport = findViewById(R.id.btnGenerateReport)
        tvReportContent = findViewById(R.id.tvReportContent)
        btnShareReport = findViewById(R.id.btnShareReport)

        // Toggle outils de test
        val layoutToolsPanel: LinearLayout = findViewById(R.id.layoutToolsPanel)
        val swToolsToggle: Switch = findViewById(R.id.swToolsToggle)
        val toolsVisible = prefs.getBoolean("tools_panel_visible", true)
        swToolsToggle.isChecked = toolsVisible
        layoutToolsPanel.visibility = if (toolsVisible) View.VISIBLE else View.GONE
        swToolsToggle.setOnCheckedChangeListener { _, isChecked ->
            layoutToolsPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("tools_panel_visible", isChecked).apply()
        }

        btnDomainTester.setOnClickListener {
            startActivity(Intent(this, DomainTesterActivity::class.java))
        }
        btnSpeedtest.setOnClickListener {
            startActivity(Intent(this, InternetSpeedtestActivity::class.java))
        }
        btnGenerateReport.setOnClickListener { generateReport() }
        btnShareReport.setOnClickListener { shareReport() }

        // Focus initial sur le CTA principal pour navigation D-pad prévisible
        btnToggle.post { btnToggle.requestFocus() }

        // Report scroll zone - click to enter, back to exit
        val scrollReport: ScrollView = findViewById(R.id.scrollReport)
        scrollReport.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                tvReportContent.isFocusable = true
                tvReportContent.requestFocus()
                true
            } else false
        }
        tvReportContent.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                tvReportContent.isFocusable = false
                scrollReport.requestFocus()
                true
            } else false
        }

        // Status scroll zone - click to enter, back to exit
        val scrollStatus: ScrollView = findViewById(R.id.scrollStatus)
        scrollStatus.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                tvStatusInfo.isFocusable = true
                tvStatusInfo.requestFocus()
                true
            } else false
        }
        tvStatusInfo.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                tvStatusInfo.isFocusable = false
                scrollStatus.requestFocus()
                true
            } else false
        }
    }

    /** Charge la liste des domaines de test activés depuis les prefs */
    private fun loadTestDomains(): List<String> {
        val json = prefs.getString("test_domains_json", null)
        if (json != null) {
            try {
                val arr = org.json.JSONArray(json)
                val result = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optBoolean("enabled", true)) {
                        result.add(obj.getString("domain"))
                    }
                }
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {}
        }
        // Fallback : ancien format mono-domaine ou défaut
        val single = prefs.getString("test_dns_domain", "ygg.re") ?: "ygg.re"
        return listOf(single)
    }

    private fun cancelGeneration() {
        generatingThread?.interrupt()
        generatingThread = null
        isGenerating = false
        btnGenerateReport.text = getString(R.string.generate_report_button)
        btnGenerateReport.setBackgroundResource(R.drawable.pdm_btn_primary)
        tvReportContent.setTextColor(pdmTextSecondary())
        tvReportContent.text = getString(R.string.no_report_yet)
        btnShareReport.isEnabled = reportGenerated
        btnShareReport.setBackgroundResource(if (reportGenerated) R.drawable.pdm_btn_primary else R.drawable.pdm_btn_danger)
    }

    private fun generateReport() {
        if (isGenerating) {
            cancelGeneration()
            return
        }
        // Vérifier qu'un DNS est actif (VPN ou DoT Private DNS)
        val isDotActive = try {
            val mode = android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
            mode == "hostname"
        } catch (_: Exception) { false }

        if (!DnsVpnService.isVpnRunning && !isDotActive) {
            Toast.makeText(this, getString(R.string.vpn_required_for_report), Toast.LENGTH_LONG).show()
            return
        }
        isGenerating = true
        reportGenerated = false
        btnGenerateReport.text = "\u23F9 Stop"
        btnGenerateReport.setBackgroundResource(R.drawable.pdm_btn_danger)
        btnShareReport.isEnabled = false
        btnShareReport.setBackgroundResource(R.drawable.pdm_btn_danger)
        lastSpeedResult = null
        lastLeakResult = null
        lastLeakIspResult = null
        lastBlockingResult = null
        lastIpv4 = null
        lastIpv6 = null

        val display = StringBuilder()
        tvReportContent.setTextColor(pdmTextSecondary())
        tvReportContent.text = getString(R.string.report_progress_blocking)

        generatingThread = Thread {
            val t = Thread.currentThread()

            // Refresh right panel in background too
            refreshIpDisplay()

            // === 1. URL Blocking test (multi-domain) ===
            if (t.isInterrupted) return@Thread
            runOnUiThread {
                tvReportContent.text = display.toString() + getString(R.string.report_progress_blocking)
            }

            val testDomains = loadTestDomains()
            val allBlockingResults = mutableListOf<UrlBlockingTester.BlockingResult>()
            display.appendLine("\u2501\u2501\u2501 ${getString(R.string.share_toggle_blocking)} \u2501\u2501\u2501")
            for (domain in testDomains) {
                if (t.isInterrupted) break
                try {
                    val blocking = UrlBlockingTester.testBeforeAfter(this@MainActivity, domain)
                    allBlockingResults.add(blocking)
                    display.appendLine("  ${blocking.domain} :")
                    val ispIcon = if (blocking.ispDns.isBlocked) "\u274c" else "\u2705"
                    val ispIp = blocking.ispDns.ip ?: blocking.ispDns.error ?: "N/A"
                    val ispAuth = blocking.ispDns.authorityLabel?.let { " \u2014 $it" } ?: ""
                    display.appendLine("    ${getString(R.string.report_isp_dns_label)}   : $ispIcon $ispIp$ispAuth")
                    val activeIcon = if (blocking.activeDns.isBlocked) "\u274c" else "\u2705"
                    val activeIp = blocking.activeDns.ip ?: blocking.activeDns.error ?: "N/A"
                    val activeAuth = blocking.activeDns.authorityLabel?.let { " \u2014 $it" } ?: ""
                    display.appendLine("    ${getString(R.string.report_active_dns_label)} : $activeIcon $activeIp$activeAuth")
                    if (blocking.ispDns.isBlocked && !blocking.activeDns.isBlocked) {
                        display.appendLine("    \u2192 ${getString(R.string.report_dns_unblocks)}")
                    }
                } catch (e: Exception) {
                    display.appendLine("  $domain : \u274c ${e.message}")
                }
                runOnUiThread {
                    tvReportContent.text = display.toString() + "\n\u23F3 ${getString(R.string.report_testing_blocking)}"
                }
            }
            lastBlockingResult = allBlockingResults.firstOrNull()
            display.appendLine()

            // === 2. DNS Leak test -- comparaison avant/apr\u00e8s ===
            if (t.isInterrupted) return@Thread
            runOnUiThread {
                tvReportContent.text = display.toString() + getString(R.string.report_progress_leak)
            }

            try {
                val leakComparison = DnsLeakTester.runLeakTestComparison(this@MainActivity)
                lastLeakIspResult = leakComparison.ispResult
                lastLeakResult = leakComparison.vpnResult
                display.appendLine("\u2501\u2501\u2501 DNS Leak Test \u2501\u2501\u2501")

                // ISP DNS (sans VPN)
                display.appendLine("  ${getString(R.string.dns_leak_isp_label)} :")
                if (leakComparison.ispResult.error != null) {
                    display.appendLine("    \u274c ${leakComparison.ispResult.error}")
                } else {
                    for (r in leakComparison.ispResult.resolverIps) {
                        val info = buildString {
                            append("    \u2022 ${r.ip}")
                            if (r.country != null) append(" \u2014 ${r.country}")
                            if (r.isp != null) append(" (${r.isp})")
                        }
                        display.appendLine(info)
                    }
                }

                // VPN DNS (avec VPN)
                display.appendLine("  ${getString(R.string.dns_leak_vpn_label)} :")
                if (leakComparison.vpnResult.error != null) {
                    display.appendLine("    \u274c ${leakComparison.vpnResult.error}")
                } else {
                    for (r in leakComparison.vpnResult.resolverIps) {
                        val info = buildString {
                            append("    \u2022 ${r.ip}")
                            if (r.country != null) append(" \u2014 ${r.country}")
                            if (r.isp != null) append(" (${r.isp})")
                        }
                        display.appendLine(info)
                    }
                }

                // Comparaison
                val ispIps = leakComparison.ispResult.resolverIps.map { it.ip }.toSet()
                val vpnIps = leakComparison.vpnResult.resolverIps.map { it.ip }.toSet()
                if (ispIps.isNotEmpty() && vpnIps.isNotEmpty() && ispIps != vpnIps) {
                    display.appendLine("  \u2705 ${getString(R.string.report_no_leak)}")
                } else if (ispIps.isNotEmpty() && ispIps == vpnIps) {
                    display.appendLine("  \u26a0\ufe0f ${getString(R.string.report_leak_detected)}")
                }

                display.appendLine()
            } catch (e: Exception) {
                display.appendLine("\u2501\u2501\u2501 DNS Leak Test \u2501\u2501\u2501")
                display.appendLine("\u274c ${e.message}")
                display.appendLine()
            }

            // === 3. Speedtest (basique) ===
            if (t.isInterrupted) return@Thread
            runOnUiThread {
                tvReportContent.text = display.toString() + getString(R.string.report_progress_speedtest)
            }

            try {
                val speed = SpeedTester.runFullTest { progress ->
                    runOnUiThread {
                        tvReportContent.text = display.toString() + "\u23F3 $progress"
                    }
                }
                lastSpeedResult = speed
                display.appendLine("\u2501\u2501\u2501 Speedtest (basique) \u2501\u2501\u2501")
                if (speed.pingMs >= 0) display.appendLine("Ping : ${speed.pingMs} ms")
                display.appendLine("\u2193 Download : ${String.format("%.1f", speed.downloadMbps)} Mbps")
                display.appendLine("\u2191 Upload : ${String.format("%.1f", speed.uploadMbps)} Mbps")
                display.appendLine()
            } catch (e: Exception) {
                display.appendLine("\u2501\u2501\u2501 Speedtest (basique) \u2501\u2501\u2501")
                display.appendLine("\u274c ${e.message}")
                display.appendLine()
            }

            // === 4. Device / Hardware info (dernier) ===
            val deviceType = if (packageManager.hasSystemFeature("android.software.leanback")) {
                getString(R.string.device_type_tv)
            } else if (resources.configuration.smallestScreenWidthDp >= 600) {
                getString(R.string.device_type_tablet)
            } else {
                getString(R.string.device_type_phone)
            }
            val appVer = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
            display.appendLine("\u2501\u2501\u2501 ${getString(R.string.share_toggle_device)} \u2501\u2501\u2501")
            display.appendLine("${getString(R.string.md_device_name)} : ${android.os.Build.DEVICE}")
            display.appendLine("${getString(R.string.md_device_model)} : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            display.appendLine("${getString(R.string.md_device_type)} : $deviceType")
            display.appendLine("Android : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            display.appendLine("${getString(R.string.md_app_version)} : $appVer")
            display.appendLine()

            if (t.isInterrupted) return@Thread
            reportGenerated = true

            runOnUiThread {
                isGenerating = false
                generatingThread = null
                btnGenerateReport.text = getString(R.string.generate_report_button)
                btnGenerateReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                btnShareReport.isEnabled = true
                btnShareReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                tvReportContent.setTextColor(pdmTextSecondary())
                tvReportContent.text = display.toString().trimEnd()
                Toast.makeText(this, getString(R.string.report_complete), Toast.LENGTH_SHORT).show()
            }
        }
        generatingThread!!.start()
    }

    private fun shareReport() {
        if (!reportGenerated) {
            Toast.makeText(this, getString(R.string.no_report_to_share), Toast.LENGTH_SHORT).show()
            return
        }

        // Dialog avec toggles pour choisir les sections à partager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }
        val cbNetwork = android.widget.CheckBox(this).apply {
            text = getString(R.string.share_toggle_network); isChecked = true
            setTextColor(pdmTextSecondary()); textSize = 14f
        }
        val cbSpeedtest = android.widget.CheckBox(this).apply {
            text = getString(R.string.share_toggle_speedtest); isChecked = lastSpeedResult != null
            isEnabled = lastSpeedResult != null
            setTextColor(pdmTextSecondary()); textSize = 14f
        }
        val cbLeak = android.widget.CheckBox(this).apply {
            text = getString(R.string.share_toggle_leak); isChecked = lastLeakResult != null
            isEnabled = lastLeakResult != null
            setTextColor(pdmTextSecondary()); textSize = 14f
        }
        val cbBlocking = android.widget.CheckBox(this).apply {
            text = getString(R.string.share_toggle_blocking); isChecked = lastBlockingResult != null
            isEnabled = lastBlockingResult != null
            setTextColor(pdmTextSecondary()); textSize = 14f
        }
        val cbDevice = android.widget.CheckBox(this).apply {
            text = getString(R.string.share_toggle_device); isChecked = true
            setTextColor(pdmTextSecondary()); textSize = 14f
        }
        layout.addView(cbNetwork)
        layout.addView(cbBlocking)
        layout.addView(cbLeak)
        layout.addView(cbSpeedtest)
        layout.addView(cbDevice)

        // Sélecteur d'expiration
        val tvExpLabel = TextView(this).apply {
            text = getString(R.string.share_expires_label)
            setTextColor(pdmTextSecondary()); textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        layout.addView(tvExpLabel)

        val expValues = arrayOf("1h", "6h", "24h", "72h")
        val expLabels = arrayOf(
            getString(R.string.share_exp_1h),
            getString(R.string.share_exp_6h),
            getString(R.string.share_exp_24h),
            getString(R.string.share_exp_72h)
        )
        val rgExpiry = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val defaultIdx = 2 // 24h
        val rbButtons = expLabels.mapIndexed { i, label ->
            android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(pdmTextPrimary())
                buttonTintList = android.content.res.ColorStateList.valueOf(pdmAccent())
                textSize = 14f
                isChecked = i == defaultIdx
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    0,
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        }
        rbButtons.forEach { rgExpiry.addView(it) }
        layout.addView(rgExpiry)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_report_button))
            .setView(layout)
            .setPositiveButton(getString(R.string.share_report_button)) { _, _ ->
                val checkedIdx = rbButtons.indexOfFirst { it.isChecked }.let { if (it >= 0) it else defaultIdx }
                val expiresIn = expValues[checkedIdx]
                uploadReport(
                    cbNetwork.isChecked,
                    cbSpeedtest.isChecked,
                    cbLeak.isChecked,
                    cbBlocking.isChecked,
                    cbDevice.isChecked,
                    expiresIn
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun uploadReport(includeNetwork: Boolean, includeSpeed: Boolean, includeLeak: Boolean, includeBlocking: Boolean, includeDevice: Boolean = true, expiresIn: String = "24h") {
        Toast.makeText(this, getString(R.string.report_generating), Toast.LENGTH_SHORT).show()
        btnShareReport.isEnabled = false
        btnShareReport.setBackgroundResource(R.drawable.pdm_btn_danger)
        Thread {
            try {
                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }

                val content = buildString {
                    appendLine("# Perfect DNS Manager \u2014 ${getString(R.string.md_report_title)}")
                    appendLine("*Version : $appVersion*")
                    appendLine()

                    // Always include right panel (network info) from tvStatusInfo
                    if (includeNetwork) {
                        val localIp = try {
                            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                                ?.flatMap { it.inetAddresses.toList() }
                                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                                ?.hostAddress ?: "N/A"
                        } catch (_: Exception) { "N/A" }

                        val connType = try {
                            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                val nc = cm.getNetworkCapabilities(cm.activeNetwork)
                                when {
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "4G/5G"
                                    else -> getString(R.string.report_conn_unknown)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                when (cm.activeNetworkInfo?.type) {
                                    android.net.ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                                    android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                                    android.net.ConnectivityManager.TYPE_MOBILE -> "4G/5G"
                                    else -> getString(R.string.report_conn_unknown)
                                }
                            }
                        } catch (_: Exception) { getString(R.string.report_conn_unknown) }

                        appendLine("## ${getString(R.string.md_network_info)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_field)} | ${getString(R.string.md_value)} |")
                        appendLine("|-------|--------|")
                        appendLine("| **Connexion** | $connType |")
                        if (!lastCarrierName.isNullOrEmpty()) {
                            appendLine("| **Op\u00e9rateur** | `$lastCarrierName` |")
                        }
                        appendLine("| **${getString(R.string.md_local_ip)}** | `$localIp` |")
                        appendLine("| **IPv4** | `${lastIpv4 ?: "N/A"}` |")
                        val ipv6Status = lastIpv6 ?: "${getString(R.string.wan_ipv6_blocked)}"
                        appendLine("| **IPv6** | `$ipv6Status` |")
                        val dnsStatusClean = (tvStatusInfo.text?.toString() ?: getString(R.string.no_active_dns)).replace("\n", " \u2014 ")
                        appendLine("| **${getString(R.string.md_active_dns)}** | `$dnsStatusClean` |")
                        appendLine("| **${getString(R.string.report_label_profile)}** | ${selectedProfile?.let { "${it.providerName} - ${it.name}" } ?: getString(R.string.report_label_none)} |")
                        appendLine("| **${getString(R.string.md_date)}** | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} |")
                        appendLine()
                    }

                    // 1. Blocking test
                    if (includeBlocking && lastBlockingResult != null) {
                        val b = lastBlockingResult!!
                        appendLine("## ${getString(R.string.md_url_blocking_test)}")
                        appendLine()
                        appendLine("${getString(R.string.md_tested_domain)} : `${b.domain}`")
                        appendLine()
                        appendLine("| ${getString(R.string.md_step)} | ${getString(R.string.md_result)} | IP | ${getString(R.string.md_authority)} |")
                        appendLine("|-------|----------|----|----|")
                        val beforeIcon = if (b.ispDns.isBlocked) "\u274c ${getString(R.string.report_blocked)}" else "\u2705 ${getString(R.string.report_accessible)}"
                        val beforeIp = b.ispDns.ip ?: b.ispDns.error ?: "N/A"
                        val beforeAuth = b.ispDns.authorityLabel ?: ""
                        appendLine("| **${getString(R.string.md_isp_dns_no_vpn)}** | $beforeIcon | `$beforeIp` | $beforeAuth |")
                        val afterIcon = if (b.activeDns.isBlocked) "\u274c ${getString(R.string.report_blocked)}" else "\u2705 ${getString(R.string.report_accessible)}"
                        val afterIp = b.activeDns.ip ?: b.activeDns.error ?: "N/A"
                        val afterAuth = b.activeDns.authorityLabel ?: ""
                        appendLine("| **${getString(R.string.md_active_dns_with_vpn)}** | $afterIcon | `$afterIp` | $afterAuth |")
                        appendLine()
                        if (b.ispDns.isBlocked && !b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_dns_unblocks_success)}")
                        } else if (!b.ispDns.isBlocked && !b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_domain_accessible_both)}")
                        } else if (b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_domain_still_blocked)}")
                        }
                        appendLine()
                    }

                    // 2. DNS Leak test
                    if (includeLeak && lastLeakResult != null) {
                        appendLine("## DNS Leak Test")
                        appendLine()

                        // ISP DNS (sans VPN)
                        if (lastLeakIspResult != null) {
                            appendLine("### ${getString(R.string.dns_leak_isp_label)}")
                            appendLine()
                            appendLine("| ${getString(R.string.md_resolver_ip)} | ${getString(R.string.md_country)} | ${getString(R.string.md_isp)} |")
                            appendLine("|--------------|------|-----|")
                            if (lastLeakIspResult!!.error != null) {
                                appendLine("| *${lastLeakIspResult!!.error}* | - | - |")
                            } else if (lastLeakIspResult!!.resolverIps.isEmpty()) {
                                appendLine("| *\u2014* | - | - |")
                            } else {
                                for (r in lastLeakIspResult!!.resolverIps) {
                                    appendLine("| `${r.ip}` | ${r.country ?: "-"} | ${r.isp ?: "-"} |")
                                }
                            }
                            appendLine()
                        }

                        // VPN DNS (avec VPN)
                        val leak = lastLeakResult!!
                        appendLine("### ${getString(R.string.dns_leak_vpn_label)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_resolver_ip)} | ${getString(R.string.md_country)} | ${getString(R.string.md_isp)} |")
                        appendLine("|--------------|------|-----|")
                        if (leak.error != null) {
                            appendLine("| *${leak.error}* | - | - |")
                        } else if (leak.resolverIps.isEmpty()) {
                            appendLine("| *\u2014* | - | - |")
                        } else {
                            for (r in leak.resolverIps) {
                                appendLine("| `${r.ip}` | ${r.country ?: "-"} | ${r.isp ?: "-"} |")
                            }
                        }
                        appendLine()

                        // Comparaison
                        if (lastLeakIspResult != null) {
                            val ispIps = lastLeakIspResult!!.resolverIps.map { it.ip }.toSet()
                            val vpnIps = leak.resolverIps.map { it.ip }.toSet()
                            if (ispIps.isNotEmpty() && vpnIps.isNotEmpty() && ispIps != vpnIps) {
                                appendLine("> ${getString(R.string.report_no_leak)}")
                            } else if (ispIps.isNotEmpty() && ispIps == vpnIps) {
                                appendLine("> ${getString(R.string.report_leak_detected)}")
                            }
                            appendLine()
                        }
                    }

                    // 3. Speedtest (basique)
                    if (includeSpeed && lastSpeedResult != null) {
                        val speed = lastSpeedResult!!
                        appendLine("## Speedtest (basique)")
                        appendLine()
                        appendLine("| ${getString(R.string.md_measure)} | ${getString(R.string.md_result)} |")
                        appendLine("|--------|----------|")
                        if (speed.pingMs >= 0) appendLine("| **Ping** | ${speed.pingMs} ms |")
                        appendLine("| **Download** | ${String.format("%.1f", speed.downloadMbps)} Mbps |")
                        appendLine("| **Upload** | ${String.format("%.1f", speed.uploadMbps)} Mbps |")
                        appendLine()
                    }

                    // 4. Device info (last)
                    if (includeDevice) {
                        val devType = if (packageManager.hasSystemFeature("android.software.leanback")) {
                            getString(R.string.device_type_tv)
                        } else if (resources.configuration.smallestScreenWidthDp >= 600) {
                            getString(R.string.device_type_tablet)
                        } else {
                            getString(R.string.device_type_phone)
                        }
                        appendLine("## ${getString(R.string.md_device_info)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_field)} | ${getString(R.string.md_value)} |")
                        appendLine("|-------|--------|")
                        appendLine("| **${getString(R.string.md_device_name)}** | ${android.os.Build.DEVICE} |")
                        appendLine("| **${getString(R.string.md_device_model)}** | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} |")
                        appendLine("| **${getString(R.string.md_device_type)}** | $devType |")
                        appendLine("| **${getString(R.string.md_android_version)}** | ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) |")
                        appendLine("| **${getString(R.string.md_app_version)}** | $appVersion |")
                        appendLine()
                    }
                    appendLine("---")
                    appendLine("*${getString(R.string.md_generated_by)} [Perfect DNS Manager](https://appstorefr.github.io/PerfectDNSManager/)*")
                }

                val result = net.appstorefr.perfectdnsmanager.util.EncryptedSharer.encryptAndUpload(
                    content, "PerfectDNS-report.enc", expiresIn
                )
                runOnUiThread {
                    btnShareReport.isEnabled = true
                    btnShareReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Report Code", result.shortCode))
                    val url1 = result.fullUrl
                    val text = "Lien de partage (chiffré de bout en bout) :\n$url1\n\nCode : ${result.shortCode} (copié dans le presse-papier)\n\nExpire dans $expiresIn."
                    val msg = android.text.SpannableString(text)
                    val code = result.shortCode
                    val greenColor = pdmAccent()
                    val linkColor = pdmAccentAlt()
                    // Color code occurrences in green
                    val idx1 = msg.indexOf(code)
                    if (idx1 >= 0) msg.setSpan(android.text.style.ForegroundColorSpan(greenColor), idx1, idx1 + code.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // Make first URL clickable
                    val urlStart1 = text.indexOf(url1)
                    if (urlStart1 >= 0) {
                        msg.setSpan(android.text.style.URLSpan(url1), urlStart1, urlStart1 + url1.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        msg.setSpan(android.text.style.ForegroundColorSpan(linkColor), urlStart1, urlStart1 + url1.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val dialog = AlertDialog.Builder(this)
                        .setTitle(getString(R.string.share_ip_success_title))
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                    dialog.findViewById<android.widget.TextView>(android.R.id.message)?.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnShareReport.isEnabled = true
                    btnShareReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                    Toast.makeText(this, getString(R.string.share_ip_error) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun restoreState() {
        // Détecter si c'est un recreate dû à un changement de langue
        val isLanguageChange = prefs.getBoolean("language_change_pending", false)
        if (isLanguageChange) {
            prefs.edit().putBoolean("language_change_pending", false).apply()
        }

        val vpnReallyActive = DnsVpnService.isVpnRunning
        val vpnSavedActive = prefs.getBoolean("vpn_active", false)
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true

        if ((vpnReallyActive && vpnSavedActive) || adbIsActive) {
            val profileJson = prefs.getString("selected_profile_json", null)
            if (profileJson != null) {
                try { selectedProfile = Gson().fromJson(profileJson, DnsProfile::class.java) }
                catch (_: Exception) {}
            }
        } else if (vpnSavedActive && !vpnReallyActive && !isLanguageChange) {
            // VPN était actif mais le service a été tué (mise à jour, kill…)
            // → auto-reconnexion (sauf si c'est juste un changement de langue)
            val selectedJson = prefs.getString("selected_profile_json", null)
            if (selectedJson != null) {
                try {
                    val profile = Gson().fromJson(selectedJson, DnsProfile::class.java)
                    selectedProfile = profile
                    updateSelectButtonText()
                    if (methodForProfile(profile) == "VPN") {
                        applyDnsViaVpn(profile)
                    }
                    return
                } catch (_: Exception) {
                    selectedProfile = null
                }
            }
            prefs.edit().putBoolean("vpn_active", false).apply()
        } else {
            // Pré-sélectionner le DNS par défaut, sinon le dernier sélectionné
            val defaultJson = prefs.getString("default_profile_json", null)
            val selectedJson = prefs.getString("selected_profile_json", null)
            val profileJson = defaultJson ?: selectedJson
            if (profileJson != null) {
                try {
                    selectedProfile = Gson().fromJson(profileJson, DnsProfile::class.java)
                    prefs.edit().putString("selected_profile_json", profileJson).apply()
                } catch (_: Exception) {
                    selectedProfile = null
                }
            } else {
                selectedProfile = null
            }
        }
        updateSelectButtonText()
    }

    private fun checkStatus() {
        val adbMode = adbManager.getCurrentPrivateDnsMode()
        if (adbMode?.contains("hostname") == true) {
            val host = adbManager.getCurrentPrivateDnsHost()
            if (host.isNotEmpty()) {
                val savedMethod = prefs.getString("last_method", "ADB") ?: "ADB"
                val displayMethod = if (savedMethod == "Shizuku" || savedMethod == "Settings") savedMethod else "ADB"
                prefs.edit().putString("last_method", displayMethod).apply()
                setActiveStatus(true, "DNS via DoT ($displayMethod): $host")
                return
            }
        }

        val vpnReallyActive = DnsVpnService.isVpnRunning
        val vpnSavedActive = prefs.getBoolean("vpn_active", false)

        if (vpnReallyActive && vpnSavedActive) {
            val label = prefs.getString("vpn_label", "") ?: ""
            setActiveStatus(true, label)
        } else {
            if (!vpnReallyActive && vpnSavedActive) {
                prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
            }
            setInactiveStatus()
        }
    }

    private fun updateSelectButtonText() {
        if (selectedProfile != null) {
            val typeLabel = typeLabelFor(selectedProfile!!.type)
            val methodLabel = if (selectedProfile!!.type == DnsType.DOT) "ADB" else "VPN"
            tvSelectDns.text = "${selectedProfile!!.providerName} - ${selectedProfile!!.name} ($typeLabel / $methodLabel)"
            ivSelectedDnsIcon.setImageResource(DnsProfile.getProviderIcon(selectedProfile!!.providerName))
            ivSelectedDnsIcon.visibility = View.VISIBLE
        } else {
            tvSelectDns.text = getString(R.string.dns_select_button)
            ivSelectedDnsIcon.visibility = View.GONE
        }
    }

    private fun clearSelectedProfile() {
        selectedProfile = null
        prefs.edit().remove("selected_profile_json").apply()
        updateSelectButtonText()
    }

    private fun setupUI() {
        layoutSelectDns.requestFocus()

        btnToggle.setOnClickListener {
            if (isActivating) return@setOnClickListener
            if (isActive) disableDns() else applyDns()
        }

        layoutSelectDns.setOnClickListener {
            dnsSelectionLauncher.launch(Intent(this, DnsSelectionActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Bouton langue
        updateLanguageButton()
        btnLanguage.setOnClickListener {
            startActivity(Intent(this, LanguageSelectionActivity::class.java)
                .putExtra("FORCE_SHOW", true))
        }
    }

    private fun updateLanguageButton() {
        val lang = prefs.getString("language", "fr") ?: "fr"
        val flag = when (lang) {
            "fr" -> "\uD83C\uDDEB\uD83C\uDDF7"
            "en" -> "\uD83C\uDDEC\uD83C\uDDE7"
            "es" -> "\uD83C\uDDEA\uD83C\uDDF8"
            "it" -> "\uD83C\uDDEE\uD83C\uDDF9"
            "pt" -> "\uD83C\uDDE7\uD83C\uDDF7"
            "ru" -> "\uD83C\uDDF7\uD83C\uDDFA"
            "zh" -> "\uD83C\uDDE8\uD83C\uDDF3"
            "ar" -> "\uD83C\uDDF8\uD83C\uDDE6"
            "hi" -> "\uD83C\uDDEE\uD83C\uDDF3"
            "bn" -> "\uD83C\uDDE7\uD83C\uDDE9"
            "ja" -> "\uD83C\uDDEF\uD83C\uDDF5"
            "de" -> "\uD83C\uDDE9\uD83C\uDDEA"
            else -> "\uD83C\uDDEC\uD83C\uDDE7"
        }
        btnLanguage.text = flag
    }

    private fun checkVersionMigration() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
            val savedVersionCode = prefs.getInt("last_version_code", 0)
            if (savedVersionCode != 0 && savedVersionCode != currentVersionCode) {
                // Version changed → refresh presets (keeps custom profiles)
                val profileManager = net.appstorefr.perfectdnsmanager.data.ProfileManager(this)
                profileManager.restoreDefaults()
                Toast.makeText(this, getString(R.string.presets_updated_toast, info.versionName ?: ""), Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putInt("last_version_code", currentVersionCode).apply()
        } catch (_: Exception) {}
    }

    private fun applyDns() {
        val profile = selectedProfile ?: run {
            Toast.makeText(this, getString(R.string.select_a_profile), Toast.LENGTH_SHORT).show()
            return
        }
        if (methodForProfile(profile) == "ADB") applyDnsViaAdb(profile) else applyDnsViaVpn(profile)
    }

    private fun applyDnsViaAdb(profile: DnsProfile) {
        if (profile.type != DnsType.DOT) {
            Toast.makeText(this, getString(R.string.private_dns_supports_only_dot), Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.MODEL.contains("AFT")) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.firestick_warning_title))
                .setMessage(getString(R.string.firestick_warning_message))
                .setPositiveButton(getString(R.string.continue_anyway)) { _, _ -> proceedWithAdb(profile) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            proceedWithAdb(profile)
        }
    }

    private fun proceedWithAdb(profile: DnsProfile) {
        // Stopper le VPN s'il tourne avant d'activer ADB (évite conflit DoH+DoT)
        if (DnsVpnService.isVpnRunning) {
            startService(Intent(this, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_STOP })
            prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
        }
        Thread {
            val success = adbManager.enablePrivateDns(profile.primary)
            runOnUiThread {
                if (success) {
                    val method = adbManager.lastMethod.ifEmpty { "ADB" }
                    prefs.edit().putString("last_method", method).apply()
                    setActiveStatus(true, "DNS via DoT ($method): ${profile.providerName}\n${profile.primary}")
                    // Auto-refresh IP display after ADB activation
                    btnToggle.postDelayed({ refreshIpDisplay() }, 3000)
                } else showAdbErrorDialog()
            }
        }.start()
    }

    private fun applyDnsViaVpn(profile: DnsProfile) {
        // Stopper ADB/DoT s'il est actif avant d'activer VPN (évite conflit DoT+DoH)
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
        if (adbIsActive) {
            Thread { adbManager.disablePrivateDns() }.start()
        }
        isActivating = true
        btnToggle.text = "\u23F3"
        btnToggle.isEnabled = false
        pendingVpnProfile = profile
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService(profile)
    }

    private fun startVpnService(profile: DnsProfile) {
        // Première connexion VPN : activer auto-start, auto-reconnect et disable IPv6
        if (!prefs.getBoolean("first_vpn_done", false)) {
            prefs.edit()
                .putBoolean("auto_start_enabled", true)
                .putBoolean("auto_reconnect_dns", true)
                .putBoolean("disable_ipv6", true)
                .putBoolean("first_vpn_done", true)
                .apply()
        }

        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
            profile.secondary?.let { putExtra(DnsVpnService.EXTRA_DNS_SECONDARY, it) }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        val label = "DNS via VPN: ${profile.providerName}\n${profile.primary}"
        prefs.edit()
            .putBoolean("vpn_active", true)
            .putString("vpn_label", label)
            .putString("last_method", "VPN")
            .apply()
        setActiveStatus(true, label)

        btnToggle.postDelayed({
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
        }, 500)

        // Auto-generate report after VPN activation (3s delay for stabilization)
        btnToggle.postDelayed({
            if (!isGenerating) generateReport()
        }, 3000)
    }

    private fun disableDnsQuiet(onDone: () -> Unit) {
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
        val vpnIsActive = DnsVpnService.isVpnRunning || prefs.getBoolean("vpn_active", false)
        // Stopper le VPN si actif
        if (vpnIsActive) {
            startService(Intent(this, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_STOP })
            prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
        }
        // Stopper ADB si actif
        if (adbIsActive) {
            Thread {
                adbManager.disablePrivateDns()
                runOnUiThread { setInactiveStatus(); onDone() }
            }.start()
        } else {
            setInactiveStatus(); onDone()
        }
    }

    private fun disableDns() {
        disableDnsQuiet {
            // Auto-refresh IP display after VPN deactivation (3s delay for stabilization)
            btnToggle.postDelayed({
                refreshIpDisplay()
            }, 3000)
        }
    }

    private fun setActiveStatus(active: Boolean, statusText: String) {
        isActive = active
        btnToggle.text = getString(R.string.deactivate)
        btnToggle.setTextColor(pdmDanger()) // DÉSACTIVER = action destructrice = rouge
        btnToggle.setBackgroundResource(R.drawable.btn_activate_background)
        btnToggle.requestFocus()
        refreshIpDisplay()
    }

    private fun setInactiveStatus() {
        isActive = false
        btnToggle.text = getString(R.string.activate)
        btnToggle.setTextColor(pdmAccent()) // ACTIVER = action positive = vert
        btnToggle.setBackgroundResource(R.drawable.btn_deactivate_background)
        btnToggle.requestFocus()
        refreshIpDisplay()
    }

    private fun showDnsReport() {
        val vpnActive = prefs.getBoolean("vpn_active", false)
        val vpnLabel = prefs.getString("vpn_label", "") ?: ""
        val adbReport = adbManager.getFullDnsReport()
        val report = StringBuilder()
        report.appendLine(getString(R.string.report_private_dns_header)); report.appendLine(adbReport); report.appendLine()
        report.appendLine(getString(R.string.report_vpn_header))
        if (vpnActive && vpnLabel.isNotEmpty()) { report.appendLine(getString(R.string.report_vpn_active)); report.appendLine(getString(R.string.report_vpn_server, vpnLabel.replace("DNS via VPN: ", ""))) }
        else report.appendLine(getString(R.string.report_vpn_inactive))
        selectedProfile?.let {
            report.appendLine(); report.appendLine(getString(R.string.report_selected_header))
            report.appendLine("${it.providerName} - ${it.name}"); report.appendLine("Type: ${it.type}")
            report.appendLine(getString(R.string.report_method, methodForProfile(it)))
            report.appendLine(getString(R.string.report_primary, it.primary)); it.secondary?.let { s -> report.appendLine(getString(R.string.report_secondary, s)) }
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.dns_report_title)).setMessage(report.toString()).setPositiveButton("OK", null).show()
    }

    private fun showAdbErrorDialog() {
        val msg = adbManager.lastError.ifEmpty { getString(R.string.adb_requires_root_or_pc, packageName) }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.adb_error_title))
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton(getString(R.string.reset_adb_keys)) { _, _ ->
                adbManager.resetAdbKeys(); Toast.makeText(this, getString(R.string.adb_keys_reset), Toast.LENGTH_LONG).show()
            }

        // Sur Android 11+, proposer d'installer/ouvrir/autoriser Shizuku
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !adbManager.shizuku.isShizukuAvailable()) {
            val mgr = adbManager.shizuku
            val shizukuLabel = when {
                !mgr.isShizukuInstalled() -> getString(R.string.shizuku_install)
                !mgr.isShizukuRunning() -> getString(R.string.shizuku_open)
                !mgr.isShizukuPermissionGranted() -> getString(R.string.shizuku_grant_permission)
                else -> getString(R.string.shizuku_open)
            }
            builder.setNegativeButton(shizukuLabel) { _, _ ->
                when {
                    !mgr.isShizukuInstalled() -> {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")
                        ))
                    }
                    !mgr.isShizukuRunning() -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (launchIntent != null) startActivity(launchIntent)
                        else Toast.makeText(this, getString(R.string.shizuku_cannot_open), Toast.LENGTH_SHORT).show()
                    }
                    !mgr.isShizukuPermissionGranted() -> {
                        mgr.requestPermission()
                    }
                }
            }
        }

        builder.show()
    }

}
