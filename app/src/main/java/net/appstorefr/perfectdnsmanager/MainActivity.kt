package net.appstorefr.perfectdnsmanager

import net.appstorefr.perfectdnsmanager.util.pdmTextPrimary
import net.appstorefr.perfectdnsmanager.util.pdmTextSecondary
import net.appstorefr.perfectdnsmanager.util.pdmAccent
import net.appstorefr.perfectdnsmanager.util.pdmAccentAlt
import net.appstorefr.perfectdnsmanager.util.pdmAccentGold
import net.appstorefr.perfectdnsmanager.util.pdmDanger
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var btnToggle: FrameLayout
    private lateinit var swActivationToggle: SwitchCompat
    private lateinit var tvActivationStatus: TextView
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
    // Garde anti-race : checkStatus() peut voir DnsVpnService.isVpnRunning encore true
    // pendant ~1-2s après stopService → ré-active à tort le bouton juste après un disable
    // manuel. Tant que ce timestamp est récent, checkStatus reste sur "Activer".
    private var lastManualDisableMs = 0L
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
            // Permission VPN refusée → on reset le Switch sur OFF + état Activer
            setInactiveStatus()
            btnToggle.requestFocus()
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        } else {
            isActivating = false
            btnToggle.isEnabled = true
            setInactiveStatus()
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
                        // L'utilisateur peut voir une demande d'autorisation ADB ici si
                        // WRITE_SECURE_SETTINGS n'est pas accordé (ex: après réinstall).
                        // Toast pour expliquer que c'est pour nettoyer le DoT précédent,
                        // pas pour appliquer le nouveau profil.
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.switching_disabling_dot),
                            Toast.LENGTH_SHORT
                        ).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            adbManager.disablePrivateDns()
                            runOnUiThread { setInactiveStatus(); applyDns() }
                        }
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
    }

    private fun checkForAppUpdate() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        UpdateManager(this).checkOnLaunch(currentVersion)
        // Sync blocking authorities list in background
        lifecycleScope.launch(Dispatchers.IO) {
            net.appstorefr.perfectdnsmanager.util.BlockingAuthoritiesManager.syncFromRemote(this@MainActivity)
        }
    }

    /**
     * Peuple les 3 cards du panneau status (gauche) : DNS actif + profil, connexion + IPs, hardware.
     * Contient : type connexion, opérateur, IP locale, IPv4, IPv6, ISP, statut DNS (vert/rouge).
     */
    private fun refreshIpDisplay() {
        // ── Phase 1 : données synchrones (rendu instantané, pas d'IO) ──
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

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val carrierName = telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: telephonyManager?.simOperatorName?.takeIf { it.isNotBlank() }
            ?: ""

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
                    getString(R.string.dns_active_vpn_fmt, typeLabelFor(profile.type), profile.providerName)
                } else getString(R.string.dns_active_vpn)
                dnsActive = true
            }
            dotActive -> {
                val host = try {
                    android.provider.Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""
                } catch (_: Exception) { "" }
                val profile = selectedProfile
                val profileMatchesHost = profile != null && host.isNotEmpty() &&
                    host.equals(profile.primary, ignoreCase = true)
                dnsStatusText = when {
                    profileMatchesHost -> getString(R.string.dns_active_dot_fmt, profile!!.providerName)
                    host.isNotEmpty() -> getString(R.string.dns_active_dot_host_fmt, host)
                    else -> getString(R.string.dns_active_dot)
                }
                dnsActive = true
            }
            else -> {
                dnsStatusText = getString(R.string.no_active_dns)
                dnsActive = false
            }
        }

        val devType = if (packageManager.hasSystemFeature("android.software.leanback")) {
            getString(R.string.device_type_tv)
        } else if (resources.configuration.smallestScreenWidthDp >= 600) {
            getString(R.string.device_type_tablet)
        } else getString(R.string.device_type_phone)

        // Rendu immédiat avec placeholders "…" pour ipv4/ipv6/ispInfo.
        // runOnUiThread défensif : si refreshIpDisplay() est appelé depuis un
        // worker (cf generateReport), l'accès direct à tvStatusInfo.text crash
        // en CalledFromWrongThreadException. runOnUiThread est instantané quand
        // le caller est déjà UI thread.
        runOnUiThread {
            renderStatus(
                dnsStatusText, dnsActive, connType, carrierName,
                ispInfo = "", localIp = localIp,
                ipv4Display = "…", ipv6Display = "…",
                devType = devType
            )
        }

        // ── Phase 2 : IO réseau (parallèle) puis update final ──
        lifecycleScope.launch(Dispatchers.IO) {
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

            val ipv4Job = async(Dispatchers.IO) {
                quickGet("https://api4.ipify.org") ?: quickGet("https://ipv4.icanhazip.com")
            }
            val ipv6Job = async(Dispatchers.IO) {
                val v6 = quickGet("https://api6.ipify.org") ?: quickGet("https://ipv6.icanhazip.com")
                if (v6 != null && v6.contains(":")) v6 else null
            }
            val ipv4 = ipv4Job.await()
            val ipv6 = ipv6Job.await()

            val ispInfo = try {
                if (ipv4 != null) {
                    val ispJson = quickGet("https://ipinfo.io/$ipv4/json")
                    if (ispJson != null) org.json.JSONObject(ispJson).optString("org", "") else ""
                } else ""
            } catch (_: Exception) { "" }

            lastIpv4 = ipv4
            lastIpv6 = ipv6
            lastCarrierName = carrierName.ifEmpty { null }

            runOnUiThread {
                renderStatus(
                    dnsStatusText, dnsActive, connType, carrierName,
                    ispInfo = ispInfo, localIp = localIp,
                    ipv4Display = ipv4 ?: getString(R.string.wan_ip_error),
                    ipv6Display = ipv6 ?: getString(R.string.wan_ipv6_blocked),
                    devType = devType
                )
            }
        }
    }

    /** Construit + affiche le bloc status complet dans tvStatusInfo. */
    private fun renderStatus(
        dnsStatusText: String, dnsActive: Boolean, connType: String, carrierName: String,
        ispInfo: String, localIp: String, ipv4Display: String, ipv6Display: String, devType: String
    ) {
        val sb = android.text.SpannableStringBuilder()
        val s1 = sb.length
        sb.append(dnsStatusText)
        val statusColor = if (dnsActive) pdmAccent() else pdmDanger()
        sb.setSpan(ForegroundColorSpan(statusColor), s1, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s1, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (dnsActive) {
            selectedProfile?.let {
                sb.append("\n").append(getString(R.string.report_profile_fmt, it.providerName, it.name))
            }
        }
        sb.append("\n\n")

        sb.append(getString(R.string.report_conn_fmt, connType)).append("\n")
        if (carrierName.isNotEmpty()) sb.append(getString(R.string.report_carrier_fmt, carrierName)).append("\n")
        if (ispInfo.isNotEmpty()) sb.append(getString(R.string.report_isp_fmt, ispInfo)).append("\n")
        sb.append(getString(R.string.report_local_ip_fmt, localIp)).append("\n")
        sb.append(getString(R.string.report_ipv4_fmt, ipv4Display)).append("\n")
        sb.append(getString(R.string.report_ipv6_fmt, ipv6Display))
        sb.append("\n\n")

        sb.append("${getString(R.string.md_device_model)}: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").append("\n")
        sb.append("${getString(R.string.md_device_type)}: $devType").append("\n")
        sb.append("${getString(R.string.md_android_version)}: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})").append("\n")
        sb.append("${getString(R.string.md_app_version)}: ${BuildConfig.VERSION_NAME}")

        tvStatusInfo.setTextColor(pdmTextSecondary())
        tvStatusInfo.text = sb
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
        for (tv in listOf(tvProvider, tvActivation)) {
            val text = tv.text.toString()
            val span = SpannableString(text)
            span.setSpan(ForegroundColorSpan(gold), 0, 2, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = span
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.rowActivationToggle)
        swActivationToggle = findViewById(R.id.swActivationToggle)
        tvActivationStatus = findViewById(R.id.tvActivationStatus)
        btnLanguage = findViewById(R.id.btnLanguage)
        layoutSelectDns = findViewById(R.id.layoutSelectDns)
        ivSelectedDnsIcon = findViewById(R.id.ivSelectedDnsIcon)
        tvSelectDns = findViewById(R.id.tvSelectDns)
        btnSettings = findViewById(R.id.btnSettings)
        // Titre + version dynamique (lue depuis BuildConfig, donc auto-cohérente
        // avec celle affichée dans la section À propos des Settings).
        findViewById<TextView>(R.id.tvTitle)?.text =
            "${getString(R.string.dns_switcher)}  v${BuildConfig.VERSION_NAME}"
        tvStatusInfo = findViewById(R.id.tvStatusInfo)
        btnDomainTester = findViewById(R.id.btnDomainTester)
        btnSpeedtest = findViewById(R.id.btnSpeedtest)
        btnGenerateReport = findViewById(R.id.btnGenerateReport)
        tvReportContent = findViewById(R.id.tvReportContent)
        btnShareReport = findViewById(R.id.btnShareReport)

        btnDomainTester.setOnClickListener {
            startActivity(Intent(this, DomainTesterActivity::class.java))
        }
        btnSpeedtest.setOnClickListener {
            startActivity(Intent(this, InternetSpeedtestActivity::class.java))
        }
        btnGenerateReport.setOnClickListener { generateReport() }
        btnShareReport.setOnClickListener { shareReport() }

        // D-pad scroll uniquement présent dans le layout TV (layout-television/) :
        // wrapStatus + wrapReport contiennent chacun un ScrollView dédié. Sur mobile,
        // le scroll est géré par le ScrollView parent global (touch-driven), no-op ici.
        val wrapStatusFl = findViewById<android.widget.FrameLayout>(R.id.wrapStatus)
        val scrollStatus = findViewById<android.widget.ScrollView>(R.id.scrollStatus)
        if (wrapStatusFl != null && scrollStatus != null) {
            attachDpadScroll(wrapStatusFl, scrollStatus)
        }
        val wrapReportFl = findViewById<android.widget.FrameLayout>(R.id.wrapReport)
        val scrollReport = findViewById<android.widget.ScrollView>(R.id.scrollReport)
        if (wrapReportFl != null && scrollReport != null) {
            attachDpadScroll(wrapReportFl, scrollReport)
        }

        // Focus initial sur le CTA principal pour navigation D-pad prévisible
        btnToggle.post { btnToggle.requestFocus() }
    }

    /**
     * Permet le scroll D-pad sur un panneau wrapper TV : UP/DOWN scrollent
     * le contenu interne tant qu'il y a de quoi scroller, sinon laissent la nav
     * D-pad opérer (nextFocusUp/Down). Mobile n'utilise pas cette fonction.
     */
    private fun attachDpadScroll(wrapper: View, sv: android.widget.ScrollView) {
        wrapper.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = (sv.height * 0.85f).toInt().coerceAtLeast(80)
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val maxScroll = (sv.getChildAt(0)?.height ?: 0) - sv.height
                    if (sv.scrollY < maxScroll) {
                        sv.smoothScrollBy(0, step); true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (sv.scrollY > 0) {
                        sv.smoothScrollBy(0, -step); true
                    } else false
                }
                else -> false
            }
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
        btnGenerateReport.text = "⏹ Stop"
        btnGenerateReport.setBackgroundResource(R.drawable.pdm_btn_danger)
        btnShareReport.isEnabled = false
        btnShareReport.setBackgroundResource(R.drawable.pdm_btn_danger)
        lastSpeedResult = null
        lastLeakResult = null
        lastLeakIspResult = null
        lastBlockingResult = null
        lastIpv4 = null
        lastIpv6 = null

        // Capture les titres ici (UI thread, getString safe) pour les passer au worker
        // qui les marquera bold via renderReport().
        val titleBlocking = getString(R.string.share_toggle_blocking)
        val titleLeak = getString(R.string.share_toggle_leak)
        val titleSpeedtest = getString(R.string.share_toggle_speedtest)

        tvReportContent.setTextColor(pdmTextSecondary())
        tvReportContent.text = renderReport("$titleBlocking\n⏳ ${getString(R.string.report_progress_blocking)}", titleBlocking, titleLeak, titleSpeedtest)

        generatingThread = Thread {
            val t = Thread.currentThread()

            // Refresh status panel — peuple lastIpv4/etc. utilisés par uploadReport()
            refreshIpDisplay()

            // display: StringBuilder est purement thread-local (créé ici dans le worker).
            // Pour les updates UI on snapshot via .toString() et on applique le bold via
            // renderReport() exécuté sur le UI thread → aucun partage de mutable state.
            val display = StringBuilder()
            display.append(titleBlocking)

            // ── Section 1 : URL Blocking test ──
            if (t.isInterrupted) return@Thread
            val testDomains = loadTestDomains()
            val allBlockingResults = mutableListOf<UrlBlockingTester.BlockingResult>()
            for (domain in testDomains) {
                if (t.isInterrupted) break
                try {
                    val blocking = UrlBlockingTester.testBeforeAfter(this@MainActivity, domain)
                    allBlockingResults.add(blocking)
                    display.append("\n${blocking.domain} :")
                    val ispIcon = if (blocking.ispDns.isBlocked) "❌" else "✅"
                    val ispIp = blocking.ispDns.ip ?: blocking.ispDns.error ?: "N/A"
                    val ispAuth = blocking.ispDns.authorityLabel?.let { " — $it" } ?: ""
                    display.append("\n  ${getString(R.string.report_isp_dns_label)} : $ispIcon $ispIp$ispAuth")
                    val activeIcon = if (blocking.activeDns.isBlocked) "❌" else "✅"
                    val activeIp = blocking.activeDns.ip ?: blocking.activeDns.error ?: "N/A"
                    val activeAuth = blocking.activeDns.authorityLabel?.let { " — $it" } ?: ""
                    display.append("\n  ${getString(R.string.report_active_dns_label)} : $activeIcon $activeIp$activeAuth")
                    if (blocking.ispDns.isBlocked && !blocking.activeDns.isBlocked) {
                        display.append("\n  → ${getString(R.string.report_dns_unblocks)}")
                    }
                } catch (e: Exception) {
                    display.append("\n$domain : ❌ ${e.message}")
                }
                val snapshot = display.toString() + "\n⏳ ${getString(R.string.report_testing_blocking)}"
                runOnUiThread { tvReportContent.text = renderReport(snapshot, titleBlocking, titleLeak, titleSpeedtest) }
            }
            lastBlockingResult = allBlockingResults.firstOrNull()

            // ── Section 2 : DNS Leak test ──
            if (t.isInterrupted) return@Thread
            display.append("\n\n").append(titleLeak)
            run {
                val snap = display.toString() + "\n⏳ ${getString(R.string.report_progress_leak)}"
                runOnUiThread { tvReportContent.text = renderReport(snap, titleBlocking, titleLeak, titleSpeedtest) }
            }
            try {
                val leakComparison = DnsLeakTester.runLeakTestComparison(this@MainActivity)
                lastLeakIspResult = leakComparison.ispResult
                lastLeakResult = leakComparison.vpnResult
                display.append("\n${getString(R.string.dns_leak_isp_label)} :")
                if (leakComparison.ispResult.error != null) {
                    display.append("\n  ❌ ${leakComparison.ispResult.error}")
                } else {
                    for (r in leakComparison.ispResult.resolverIps) {
                        display.append("\n  • ${r.ip}")
                        if (r.country != null) display.append(" — ${r.country}")
                        if (r.isp != null) display.append(" (${r.isp})")
                    }
                }
                display.append("\n${getString(R.string.dns_leak_vpn_label)} :")
                if (leakComparison.vpnResult.error != null) {
                    display.append("\n  ❌ ${leakComparison.vpnResult.error}")
                } else {
                    for (r in leakComparison.vpnResult.resolverIps) {
                        display.append("\n  • ${r.ip}")
                        if (r.country != null) display.append(" — ${r.country}")
                        if (r.isp != null) display.append(" (${r.isp})")
                    }
                }
                val ispIps = leakComparison.ispResult.resolverIps.map { it.ip }.toSet()
                val vpnIps = leakComparison.vpnResult.resolverIps.map { it.ip }.toSet()
                if (ispIps.isNotEmpty() && vpnIps.isNotEmpty() && ispIps != vpnIps) {
                    display.append("\n✅ ${getString(R.string.report_no_leak)}")
                } else if (ispIps.isNotEmpty() && ispIps == vpnIps) {
                    display.append("\n⚠️ ${getString(R.string.report_leak_detected)}")
                }
            } catch (e: Exception) {
                display.append("\n❌ ${e.message}")
            }

            // ── Section 3 : Speedtest (basique) ──
            if (t.isInterrupted) return@Thread
            display.append("\n\n").append(titleSpeedtest)
            run {
                val snap = display.toString() + "\n⏳ ${getString(R.string.report_progress_speedtest)}"
                runOnUiThread { tvReportContent.text = renderReport(snap, titleBlocking, titleLeak, titleSpeedtest) }
            }
            try {
                val speed = SpeedTester.runFullTest { progress ->
                    val snap = display.toString() + "\n⏳ $progress"
                    runOnUiThread { tvReportContent.text = renderReport(snap, titleBlocking, titleLeak, titleSpeedtest) }
                }
                lastSpeedResult = speed
                if (speed.pingMs >= 0) display.append("\nPing : ${speed.pingMs} ms")
                display.append("\n↓ Download : ${String.format("%.1f", speed.downloadMbps)} Mbps")
                display.append("\n↑ Upload : ${String.format("%.1f", speed.uploadMbps)} Mbps")
            } catch (e: Exception) {
                display.append("\n❌ ${e.message}")
            }

            if (t.isInterrupted) return@Thread
            reportGenerated = true

            val finalSnap = display.toString()
            runOnUiThread {
                isGenerating = false
                generatingThread = null
                btnGenerateReport.text = getString(R.string.generate_report_button)
                btnGenerateReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                btnShareReport.isEnabled = true
                btnShareReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                tvReportContent.text = renderReport(finalSnap, titleBlocking, titleLeak, titleSpeedtest)
                Toast.makeText(this, getString(R.string.report_complete), Toast.LENGTH_SHORT).show()
            }
        }
        generatingThread!!.start()
    }

    /**
     * Convertit une string brute en SpannableStringBuilder avec les 3 titres
     * de section (Blocking / Leak / Speedtest) en gras. Appelé uniquement sur
     * UI thread — pas de problème de concurrence avec le StringBuilder du worker.
     */
    private fun renderReport(raw: String, vararg titles: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(raw)
        for (title in titles) {
            var idx = raw.indexOf(title)
            while (idx >= 0) {
                sb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    idx, idx + title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = raw.indexOf(title, idx + title.length)
            }
        }
        return sb
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
        val defaultIdx = 0 // 1h (password 6 chars → TTL court pour limiter brute-force)
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }

                val content = buildString {
                    appendLine("# Perfect DNS Manager \u2014 ${getString(R.string.md_report_title)}")
                    appendLine("*Version : $appVersion*")
                    appendLine()

                    // Always include network info from saved fields (was tvStatusInfo, désormais peuplé via les 3 cards status)
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
                        // tvStatusInfo contient le bloc complet status (DNS + connexion + hardware) ;
                        // pour la cellule MD on extrait juste la 1re ligne (= ligne DNS actif).
                        val dnsStatusClean = (tvStatusInfo.text?.toString() ?: getString(R.string.no_active_dns)).lineSequence().firstOrNull() ?: getString(R.string.no_active_dns)
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
                    this@MainActivity, content, "PerfectDNS-report.enc", expiresIn
                )
                runOnUiThread {
                    btnShareReport.isEnabled = true
                    btnShareReport.setBackgroundResource(R.drawable.pdm_btn_primary)
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val url1 = result.fullUrl
                    val pwd = result.password
                    // Clipboard : URL + mot de passe formaté pour un partage complet en 1 collage.
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PDM Share", "$url1\n${getString(R.string.share_clip_password_fmt, pwd)}"))
                    val text = getString(R.string.share_text_format, url1, pwd, expiresIn)
                    val msg = android.text.SpannableString(text)
                    val linkColor = pdmAccentAlt()
                    val accentColor = pdmAccent()
                    val urlStart1 = text.indexOf(url1)
                    if (urlStart1 >= 0) {
                        msg.setSpan(android.text.style.URLSpan(url1), urlStart1, urlStart1 + url1.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        msg.setSpan(android.text.style.ForegroundColorSpan(linkColor), urlStart1, urlStart1 + url1.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val pwdStart = text.indexOf(pwd)
                    if (pwdStart >= 0) {
                        msg.setSpan(android.text.style.ForegroundColorSpan(accentColor), pwdStart, pwdStart + pwd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        msg.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), pwdStart, pwdStart + pwd.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val dialog = AlertDialog.Builder(this@MainActivity)
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
                    Toast.makeText(this@MainActivity, getString(R.string.share_ip_error) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        // Guard : juste après un disable manuel, ignorer l'état système le temps que
        // le service VPN s'arrête vraiment et que les prefs soient à jour.
        if (System.currentTimeMillis() - lastManualDisableMs < 5000L) {
            setInactiveStatus()
            return
        }
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

    private fun setupUI() {
        layoutSelectDns.requestFocus()

        // Click sur le row → toggle le Switch (qui appellera applyDns/disableDns
        // via le listener (re)posé par setActiveStatus / setInactiveStatus).
        btnToggle.setOnClickListener {
            if (isActivating) return@setOnClickListener
            swActivationToggle.toggle()
        }
        rebindActivationListener()

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
        // Chevron ▾ signale que c'est un dropdown de langue (sinon le flag seul est ambigu)
        btnLanguage.text = "$flag ▾"
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
        lifecycleScope.launch(Dispatchers.IO) {
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
        }
    }

    private fun applyDnsViaVpn(profile: DnsProfile) {
        // Stopper ADB/DoT s'il est actif avant d'activer VPN (évite conflit DoT+DoH)
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
        if (adbIsActive) {
            lifecycleScope.launch(Dispatchers.IO) { adbManager.disablePrivateDns() }
        }
        isActivating = true
        tvActivationStatus.text = "\u23F3"
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

        // Race : DnsVpnService.isVpnRunning ne passe à true qu'après l'init du
        // tunnel TUN (~1-3s), donc le refreshIpDisplay() de setActiveStatus()
        // ci-dessus voit encore "false" et affiche "Aucun DNS actif". On
        // re-refresh à 1s/3s/6s pour catcher dès que le service est prêt,
        // sinon le panneau peut rester sur "Aucun DNS actif" pendant des
        // minutes (jusqu'au prochain onResume) — symétrique au path ADB.
        for (delay in listOf(1000L, 3000L, 6000L)) {
            btnToggle.postDelayed({ refreshIpDisplay() }, delay)
        }
    }

    private fun disableDnsQuiet(onDone: () -> Unit) {
        lastManualDisableMs = System.currentTimeMillis()
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
        val vpnIsActive = DnsVpnService.isVpnRunning || prefs.getBoolean("vpn_active", false)
        // Stopper le VPN si actif
        if (vpnIsActive) {
            startService(Intent(this, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_STOP })
            prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
        }
        // Stopper ADB si actif
        if (adbIsActive) {
            lifecycleScope.launch(Dispatchers.IO) {
                adbManager.disablePrivateDns()
                runOnUiThread { setInactiveStatus(); onDone() }
            }
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
        // Switch sans listener pour éviter rebond — on est ici parce qu'on vient
        // soit d'activer manuellement, soit checkStatus a détecté un VPN/ADB déjà ON.
        swActivationToggle.setOnCheckedChangeListener(null)
        swActivationToggle.isChecked = true
        rebindActivationListener()
        tvActivationStatus.text = getString(R.string.deactivate)
        tvActivationStatus.setTextColor(pdmDanger())
        applyToggleTint(true)
        refreshIpDisplay()
    }

    private fun setInactiveStatus() {
        isActive = false
        swActivationToggle.setOnCheckedChangeListener(null)
        swActivationToggle.isChecked = false
        rebindActivationListener()
        tvActivationStatus.text = getString(R.string.activate)
        tvActivationStatus.setTextColor(pdmAccent())
        applyToggleTint(false)
        refreshIpDisplay()
    }

    /** Switch vert quand DNS ON, rouge quand DNS OFF. */
    private fun applyToggleTint(on: Boolean) {
        val color = if (on) pdmAccent() else pdmDanger()
        val list = android.content.res.ColorStateList.valueOf(color)
        swActivationToggle.thumbTintList = list
        swActivationToggle.trackTintList = list
    }

    /** (Re)pose le listener du Switch d'activation après un changement programmatique. */
    private fun rebindActivationListener() {
        swActivationToggle.setOnCheckedChangeListener { _, _ ->
            if (isActivating) return@setOnCheckedChangeListener
            if (isActive) disableDns() else applyDns()
        }
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

        // Sur Android 11+ phone (où le port ADB 5555 est fermé par défaut),
        // proposer le pairing Wireless Debugging avec code 6 chiffres — bootstrap
        // no-PC qui remplace l'ancienne dépendance externe Shizuku.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setNegativeButton(getString(R.string.adb_pair_wireless)) { _, _ ->
                showWirelessPairingDialog()
            }
        }

        builder.show()
    }

    /**
     * Dialog Android 11+ : demande à l'user d'activer Wireless Debugging +
     * "Coupler avec un code", puis saisit le code 6 chiffres pour bootstrap
     * WRITE_SECURE_SETTINGS via le pairing ADB embarqué (lib Shizuku vendored).
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun showWirelessPairingDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "123456"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.adb_pair_wireless))
            .setMessage(getString(R.string.adb_pair_wireless_instructions))
            .setView(input)
            .setPositiveButton(getString(R.string.adb_pair_submit)) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length != 6) {
                    Toast.makeText(this, getString(R.string.adb_pair_invalid_code), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runWirelessPairing(code)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun runWirelessPairing(code: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.adb_pair_in_progress))
            .setMessage(getString(R.string.adb_pair_step_discovering))
            .setCancelable(false)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            val mgr = net.appstorefr.perfectdnsmanager.service.AdbPairingManager(this@MainActivity)
            mgr.pairAndGrant(code, object : net.appstorefr.perfectdnsmanager.service.AdbPairingManager.Callback {
                override fun onProgress(step: String) {
                    runOnUiThread {
                        val txt = when (step) {
                            "DISCOVERING_PAIR_PORT" -> R.string.adb_pair_step_discovering
                            "PAIRING" -> R.string.adb_pair_step_pairing
                            "DISCOVERING_CONNECT_PORT" -> R.string.adb_pair_step_connecting
                            "GRANTING" -> R.string.adb_pair_step_granting
                            else -> R.string.adb_pair_step_discovering
                        }
                        progress.setMessage(getString(txt))
                    }
                }
                override fun onSuccess() {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this@MainActivity, getString(R.string.adb_pair_success), Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this@MainActivity, getString(R.string.adb_pair_failed, error), Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

}
