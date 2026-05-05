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
import net.appstorefr.perfectdnsmanager.util.pdmAccentSupport
import net.appstorefr.perfectdnsmanager.util.pdmDanger
import net.appstorefr.perfectdnsmanager.util.pdmWarning

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.data.ProfileManager
import net.appstorefr.perfectdnsmanager.service.AdbDnsManager
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var adbDnsManager: AdbDnsManager
    private var tvPermissionStatus: TextView? = null
    private var btnSelfGrant: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        adbDnsManager = AdbDnsManager(this)

        val btnBack: Button = findViewById(R.id.btnBack)
        val btnHowTo: Button = findViewById(R.id.btnHowTo)
        val tvAdbStatus: TextView = findViewById(R.id.tvAdbStatus)
        val switchAutoStart: Switch = findViewById(R.id.switchAutoStart)
        val switchAutoReconnect: Switch = findViewById(R.id.switchAutoReconnect)
        val switchDisableIpv6: Switch = findViewById(R.id.switchDisableIpv6)
        val rowAutoStart: LinearLayout = findViewById(R.id.rowAutoStart)
        val rowAutoReconnect: LinearLayout = findViewById(R.id.rowAutoReconnect)
        val rowDisableIpv6: LinearLayout = findViewById(R.id.rowDisableIpv6)
        rowAutoStart.setOnClickListener { switchAutoStart.toggle() }
        rowAutoReconnect.setOnClickListener { if (switchAutoReconnect.isEnabled) switchAutoReconnect.toggle() }
        rowDisableIpv6.setOnClickListener { switchDisableIpv6.toggle() }

        // ── Mises à jour bêta ──
        // Sur un build bêta on force l'option ON et on verrouille la rangée :
        // l'utilisateur est déjà sur prerelease, le désactiver le couperait
        // des mises à jour (UpdateManager ne verrait que les stables, donc
        // toujours antérieures à la version courante).
        val rowBetaUpdates: LinearLayout = findViewById(R.id.rowBetaUpdates)
        val switchBetaUpdates: Switch = findViewById(R.id.switchBetaUpdates)
        if (PdmApp.isBetaBuild()) {
            switchBetaUpdates.isChecked = true
            // Reste focusable (DPAD doit pouvoir s'arrêter dessus pour lire le
            // libellé) mais clickable=false → la rangée n'avale pas le clic et
            // le toggle reste verrouillé sur ON.
            rowBetaUpdates.isClickable = false
            rowBetaUpdates.alpha = 0.7f
            findViewById<TextView>(R.id.tvBetaUpdatesDescription)
                .setText(R.string.beta_updates_locked)
        } else {
            switchBetaUpdates.isChecked = prefs.getBoolean("beta_updates_enabled", false)
            rowBetaUpdates.setOnClickListener { switchBetaUpdates.toggle() }
            switchBetaUpdates.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("beta_updates_enabled", checked).apply()
            }
        }

        // ── Toggle fonctions avancées ──
        val switchAdvanced: Switch = findViewById(R.id.switchAdvanced)
        val layoutAdvanced: LinearLayout = findViewById(R.id.layoutAdvancedSection)
        val btnRestoreDns: Button = findViewById(R.id.btnRestoreDns)
        val btnUrlRewrite: Button = findViewById(R.id.btnUrlRewrite)
        val switchOperatorDns: Switch = findViewById(R.id.switchOperatorDns)

        // ── Toggles simplification profils DNS ──
        val switchStandardDns: Switch = findViewById(R.id.switchStandardDns)
        val rowStandardDns: LinearLayout = findViewById(R.id.rowStandardDns)
        val switchProfileVariants: Switch = findViewById(R.id.switchProfileVariants)
        val rowProfileVariants: LinearLayout = findViewById(R.id.rowProfileVariants)
        val switchDoqDns: Switch = findViewById(R.id.switchDoqDns)
        val rowDoqDns: LinearLayout = findViewById(R.id.rowDoqDns)

        // ── Toggle DNS DoT via ADB (dans la section avancée) ──
        val switchAdbDot: Switch = findViewById(R.id.switchAdbDot)
        val rowAdbDot: LinearLayout = findViewById(R.id.rowAdbDot)
        val layoutAdbDotSection: LinearLayout = findViewById(R.id.layoutAdbDotSection)

        // Statut ADB
        val adbEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        tvAdbStatus.text = if (adbEnabled) getString(R.string.adb_status_active) else getString(R.string.adb_status_inactive)
        tvAdbStatus.setTextColor(if (adbEnabled) getColor(android.R.color.holo_green_light) else getColor(android.R.color.holo_red_light))

        // Switches démarrage auto
        switchAutoStart.isChecked = prefs.getBoolean("auto_start_enabled", false)
        switchAutoReconnect.isChecked = prefs.getBoolean("auto_reconnect_dns", false)
        switchAutoReconnect.isEnabled = switchAutoStart.isChecked

        // ── Sélecteur de thème ──
        val rowTheme: LinearLayout = findViewById(R.id.rowTheme)
        val tvThemeValue: TextView = findViewById(R.id.tvThemeValue)
        fun updateThemeLabel() {
            val mode = prefs.getString("theme_mode", "system") ?: "system"
            tvThemeValue.text = when (mode) {
                "light" -> getString(R.string.theme_light)
                "dark" -> getString(R.string.theme_dark)
                else -> getString(R.string.theme_system)
            }
        }
        updateThemeLabel()
        rowTheme.setOnClickListener {
            val modes = arrayOf("system", "light", "dark")
            val labels = arrayOf(
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
            )
            val current = prefs.getString("theme_mode", "system") ?: "system"
            val currentIdx = modes.indexOf(current).coerceAtLeast(0)
            net.appstorefr.perfectdnsmanager.util.TvDialog.showRadioPicker(
                this,
                getString(R.string.theme_choose_title),
                labels,
                currentIdx
            ) { which ->
                val newMode = modes[which]
                prefs.edit().putString("theme_mode", newMode).apply()
                PdmApp.applyThemeMode(newMode)
                recreate()
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start_enabled", isChecked).apply()
            switchAutoReconnect.isEnabled = isChecked
            if (!isChecked) {
                switchAutoReconnect.isChecked = false
                prefs.edit().putBoolean("auto_reconnect_dns", false).apply()
            }
        }
        // Info DNS auto-reconnect
        val tvAutoReconnectDns: TextView = findViewById(R.id.tvAutoReconnectDns)
        fun updateAutoReconnectDnsInfo() {
            if (!switchAutoReconnect.isChecked) {
                tvAutoReconnectDns.visibility = View.GONE
                return
            }
            val defaultJson = prefs.getString("default_profile_json", null)
            val selectedJson = prefs.getString("selected_profile_json", null)
            val profileJson = defaultJson ?: selectedJson
            if (profileJson != null) {
                try {
                    val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                    val prefix = if (defaultJson != null) getString(R.string.auto_reconnect_default_prefix) else ""
                    val typeLabel = when (profile.type) {
                        net.appstorefr.perfectdnsmanager.data.DnsType.DOH -> "DoH"
                        net.appstorefr.perfectdnsmanager.data.DnsType.DOT -> "DoT"
                        net.appstorefr.perfectdnsmanager.data.DnsType.DOQ -> "DoQ"
                        net.appstorefr.perfectdnsmanager.data.DnsType.DEFAULT -> "Standard"
                    }
                    val methodLabel = if (profile.type == net.appstorefr.perfectdnsmanager.data.DnsType.DOT) "ADB" else "VPN"
                    tvAutoReconnectDns.text = getString(R.string.auto_reconnect_dns_info, "$prefix${profile.providerName} - ${profile.name} ($typeLabel / $methodLabel)")
                    tvAutoReconnectDns.visibility = View.VISIBLE
                } catch (_: Exception) {
                    tvAutoReconnectDns.visibility = View.GONE
                }
            } else {
                tvAutoReconnectDns.text = getString(R.string.auto_reconnect_no_dns)
                tvAutoReconnectDns.visibility = View.VISIBLE
            }
        }
        updateAutoReconnectDnsInfo()

        switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_reconnect_dns", isChecked).apply()
            updateAutoReconnectDnsInfo()
        }

        // IPv6
        switchDisableIpv6.isChecked = prefs.getBoolean("disable_ipv6", false)
        switchDisableIpv6.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("disable_ipv6", isChecked).apply()
            // Auto-reconnexion VPN si actif
            if (net.appstorefr.perfectdnsmanager.service.DnsVpnService.isVpnRunning) {
                val profileJson = prefs.getString("selected_profile_json", null)
                if (profileJson != null) {
                    try {
                        val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                        if (profile.type != net.appstorefr.perfectdnsmanager.data.DnsType.DOT) {
                            val intent = Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java).apply {
                                action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_START
                                putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
                                profile.secondary?.let { putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                            else startService(intent)
                            Toast.makeText(this, getString(R.string.vpn_reconnecting_ipv6), Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // ── DNS DoT via ADB : toggle show/hide ADB sub-section ──
        // Vérification SDK : Private DNS (DoT) nécessite Android 9+ (API 28)
        val isAdbCompatible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (!isAdbCompatible) {
            switchAdbDot.isChecked = false
            switchAdbDot.isEnabled = false
            rowAdbDot.alpha = 0.4f
            rowAdbDot.isClickable = false
            prefs.edit().putBoolean("adb_dot_enabled", false).apply()
            layoutAdbDotSection.visibility = View.GONE
            // Afficher un avertissement sous le toggle
            val tvAdbCompat = TextView(this).apply {
                text = getString(R.string.adb_requires_android9)
                setTextColor(pdmDanger())
                textSize = 12f
                setPadding(12, 4, 12, 8)
            }
            (rowAdbDot.parent as? LinearLayout)?.let { parent ->
                val idx = parent.indexOfChild(rowAdbDot) + 1
                parent.addView(tvAdbCompat, idx)
            }
        } else {
            val adbDotEnabled = prefs.getBoolean("adb_dot_enabled", false)
            switchAdbDot.isChecked = adbDotEnabled
            layoutAdbDotSection.visibility = if (adbDotEnabled) View.VISIBLE else View.GONE
        }

        rowAdbDot.setOnClickListener {
            if (isAdbCompatible) switchAdbDot.isChecked = !switchAdbDot.isChecked
        }

        switchAdbDot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("adb_dot_enabled", isChecked).apply()
            layoutAdbDotSection.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                val adbNow = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                tvAdbStatus.text = if (adbNow) getString(R.string.adb_status_active) else getString(R.string.adb_status_inactive)
                tvAdbStatus.setTextColor(if (adbNow) getColor(android.R.color.holo_green_light) else getColor(android.R.color.holo_red_light))
            }
        }

        // ── Self-grant ADB permission ──
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnSelfGrant = findViewById(R.id.btnSelfGrant)
        updatePermissionUI()

        btnSelfGrant?.setOnClickListener {
            btnSelfGrant?.isEnabled = false
            btnSelfGrant?.text = getString(R.string.self_grant_connecting)
            tvPermissionStatus?.text = getString(R.string.self_grant_progress)
            tvPermissionStatus?.setTextColor(pdmAccentGold())

            Thread {
                adbDnsManager.selfGrantPermission(object : AdbDnsManager.SelfGrantCallback {
                    override fun onProgress(step: String) {
                        runOnUiThread {
                            tvPermissionStatus?.text = when {
                                step == "crypto" -> getString(R.string.self_grant_step_crypto)
                                step == "connecting" -> getString(R.string.self_grant_step_connecting)
                                step.startsWith("port:") -> getString(R.string.self_grant_step_port, step.substringAfter(":"))
                                step == "granting" -> getString(R.string.self_grant_step_granting)
                                else -> step
                            }
                        }
                    }

                    override fun onSuccess() {
                        runOnUiThread {
                            updatePermissionUI()
                            Toast.makeText(this@SettingsActivity, getString(R.string.self_grant_success), Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            btnSelfGrant?.isEnabled = true
                            btnSelfGrant?.text = getString(R.string.self_grant_retry)
                            when {
                                error == "ADB_NOT_REACHABLE" -> {
                                    tvPermissionStatus?.text = getString(R.string.self_grant_adb_unreachable)
                                    tvPermissionStatus?.setTextColor(getColor(android.R.color.holo_red_light))
                                    showAdbUnreachableHelp()
                                }
                                error.startsWith("GRANT_FAILED:") -> {
                                    tvPermissionStatus?.text = getString(R.string.self_grant_failed)
                                    tvPermissionStatus?.setTextColor(getColor(android.R.color.holo_red_light))
                                }
                                error == "GRANT_NOT_EFFECTIVE" -> {
                                    tvPermissionStatus?.text = getString(R.string.self_grant_not_effective)
                                    tvPermissionStatus?.setTextColor(pdmAccentSupport())
                                }
                                else -> {
                                    tvPermissionStatus?.text = getString(R.string.self_grant_error, error)
                                    tvPermissionStatus?.setTextColor(getColor(android.R.color.holo_red_light))
                                }
                            }
                        }
                    }
                })
            }.start()
        }

        // ── Fonctions avancées : toggle cache/montre la section ──
        val advancedEnabled = prefs.getBoolean("advanced_features_enabled", false)
        switchAdvanced.isChecked = advancedEnabled
        layoutAdvanced.visibility = if (advancedEnabled) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.rowAdvanced).setOnClickListener { switchAdvanced.toggle() }

        switchAdvanced.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("advanced_features_enabled", isChecked).apply()
            layoutAdvanced.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateFocusChain()
            // Cascade : si on désactive les fonctions avancées, tout ce qui est dedans se désactive
            if (!isChecked) {
                switchOperatorDns.isChecked = false
                prefs.edit().putBoolean("operator_dns_enabled", false).apply()

                switchStandardDns.isChecked = false
                prefs.edit().putBoolean("show_standard_dns", false).apply()

                switchProfileVariants.isChecked = false
                prefs.edit().putBoolean("show_profile_variants", false).apply()

                switchDoqDns.isChecked = false
                prefs.edit().putBoolean("show_doq_dns", false).apply()

                switchAdbDot.isChecked = false
                prefs.edit().putBoolean("adb_dot_enabled", false).apply()
                layoutAdbDotSection.visibility = View.GONE

                // Désactiver les rewrite rules
                val repo = net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository(this)
                val rules = repo.getAllRules()
                rules.filter { it.isEnabled }.forEach { repo.updateRule(it.copy(isEnabled = false)) }
                reloadVpnRewriteRules()
            }
        }

        // Sous-toggle DNS opérateur (dans la section avancée)
        val rowOperatorDns = findViewById<LinearLayout>(R.id.rowOperatorDns)
        switchOperatorDns.isChecked = prefs.getBoolean("operator_dns_enabled", false)
        rowOperatorDns.setOnClickListener { switchOperatorDns.isChecked = !switchOperatorDns.isChecked }
        switchOperatorDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("operator_dns_enabled", isChecked).apply()
        }

        // ── Standard DNS toggle ──
        switchStandardDns.isChecked = prefs.getBoolean("show_standard_dns", false)
        rowStandardDns.setOnClickListener { switchStandardDns.isChecked = !switchStandardDns.isChecked }
        switchStandardDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_standard_dns", isChecked).apply()
        }

        // ── Profile variants toggle ──
        switchProfileVariants.isChecked = prefs.getBoolean("show_profile_variants", false)
        rowProfileVariants.setOnClickListener { switchProfileVariants.isChecked = !switchProfileVariants.isChecked }
        switchProfileVariants.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_profile_variants", isChecked).apply()
        }

        // DNS over QUIC toggle (hidden by default)
        switchDoqDns.isChecked = prefs.getBoolean("show_doq_dns", false)
        rowDoqDns.setOnClickListener { switchDoqDns.isChecked = !switchDoqDns.isChecked }
        switchDoqDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_doq_dns", isChecked).apply()
        }

        // URL Rewrite (fonction avancée)
        btnUrlRewrite.setOnClickListener { showUrlRewriteDialog() }

        // Split tunneling (bypass VPN per-app)
        findViewById<Button>(R.id.btnSplitTunnel).setOnClickListener { showSplitTunnelDialog() }

        // Import / Export configuration (collapsible)
        val layoutImportExportContent: LinearLayout = findViewById(R.id.layoutImportExportContent)
        val rowImportExport: LinearLayout = findViewById(R.id.rowImportExport)
        val tvArrow: TextView = findViewById(R.id.tvImportExportArrow)

        rowImportExport.setOnClickListener {
            val expanded = layoutImportExportContent.visibility == View.VISIBLE
            layoutImportExportContent.visibility = if (expanded) View.GONE else View.VISIBLE
            tvArrow.text = if (expanded) "▶" else "▼"
            updateFocusChain()
        }

        val btnExportConfig: Button = findViewById(R.id.btnExportConfig)
        val btnImportConfig: Button = findViewById(R.id.btnImportConfig)

        btnExportConfig.setOnClickListener { exportConfiguration() }
        btnImportConfig.setOnClickListener { showImportOptions() }

        // Restaurer les DNS par défaut (fonction avancée)
        btnRestoreDns.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_dns_title))
                .setMessage(getString(R.string.restore_dns_message))
                .setPositiveButton(getString(R.string.restore_defaults)) { _, _ ->
                    ProfileManager(this).restoreDefaults()
                    Toast.makeText(this, getString(R.string.defaults_restored), Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(getString(R.string.reset_all)) { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.confirm_title))
                        .setMessage(getString(R.string.reset_all_confirm))
                        .setPositiveButton(getString(R.string.yes_reset_all)) { _, _ ->
                            ProfileManager(this).resetAll()
                            getSharedPreferences("nextdns_profiles", MODE_PRIVATE).edit().clear().apply()
                            Toast.makeText(this, getString(R.string.all_reset), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        // Réinitialisation complète de l'application
        findViewById<Button>(R.id.btnResetApp).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_app_title))
                .setMessage(getString(R.string.reset_app_message))
                .setPositiveButton(getString(R.string.reset_app_confirm)) { _, _ ->
                    try {
                        val stopIntent = Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java)
                        stopIntent.action = "ACTION_STOP"
                        startService(stopIntent)
                    } catch (_: Exception) {}
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("nextdns_profiles", MODE_PRIVATE).edit().clear().apply()
                    ProfileManager(this).resetAll()
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finishAffinity()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        findViewById<LinearLayout>(R.id.rowAdvanced).requestFocus()
        btnBack.setOnClickListener { finish() }
        btnHowTo.setOnClickListener { startActivity(Intent(this, HowToActivity::class.java)) }

        // ── Section À propos (collapsible — contient maj bêta + check + support) ──
        val rowAbout = findViewById<LinearLayout>(R.id.rowAbout)
        val layoutAbout = findViewById<LinearLayout>(R.id.layoutAboutContent)
        val tvAboutArrow = findViewById<TextView>(R.id.tvAboutArrow)
        rowAbout.setOnClickListener {
            val expanded = layoutAbout.visibility == View.VISIBLE
            layoutAbout.visibility = if (expanded) View.GONE else View.VISIBLE
            tvAboutArrow.text = if (expanded) "▶" else "▼"
        }

        findViewById<Button>(R.id.btnSupportAbout).setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }
        val updateManager = net.appstorefr.perfectdnsmanager.service.UpdateManager(this)
        val btnCheck = findViewById<Button>(R.id.btnCheckForUpdate)
        // Le numéro de version courant est intégré directement dans le label
        // du bouton — pas d'élément version séparé qui faisait moche.
        btnCheck.text = "${getString(R.string.check_for_updates)}  ·  v${BuildConfig.VERSION_NAME}"
        btnCheck.setOnClickListener {
            Toast.makeText(this, getString(R.string.checking_for_updates), Toast.LENGTH_SHORT).show()
            updateManager.checkForUpdateGitHub("appstorefr/PerfectDNSManager", BuildConfig.VERSION_NAME)
        }

        updateFocusChain()
    }

    /**
     * Chaîne dynamique des nextFocus entre sections collapsibles. Appelée au
     * démarrage et à chaque toggle des sections avancées / import-export pour
     * garantir qu'un DPAD_DOWN depuis rowAdvanced (ou rowImportExport) atteint
     * la row suivante correctement, même si la section est dépliée.
     */
    private fun updateFocusChain() {
        val rowAdvanced = findViewById<LinearLayout>(R.id.rowAdvanced)
        val rowProfileVariants = findViewById<LinearLayout>(R.id.rowProfileVariants)
        val layoutAdvanced = findViewById<LinearLayout>(R.id.layoutAdvancedSection)
        val rowImportExport = findViewById<LinearLayout>(R.id.rowImportExport)
        val btnSplitTunnel = findViewById<Button>(R.id.btnSplitTunnel)
        val btnResetApp = findViewById<Button>(R.id.btnResetApp)
        val rowAbout = findViewById<LinearLayout>(R.id.rowAbout)

        val advancedOpen = layoutAdvanced.visibility == View.VISIBLE
        val importOpen = findViewById<LinearLayout>(R.id.layoutImportExportContent)
            .visibility == View.VISIBLE

        rowAdvanced.nextFocusDownId =
            if (advancedOpen) rowProfileVariants.id else rowImportExport.id
        btnSplitTunnel.nextFocusDownId = rowImportExport.id
        rowImportExport.nextFocusUpId =
            if (advancedOpen) btnSplitTunnel.id else rowAdvanced.id
        // rowImportExport ouvert : dernier bouton (btnResetApp) → rowAbout.
        // Fermé : rowImportExport → rowAbout. rowAbout up → varie selon état.
        rowImportExport.nextFocusDownId = if (importOpen) btnResetApp.id else rowAbout.id
        btnResetApp.nextFocusDownId = rowAbout.id
        rowAbout.nextFocusUpId = if (importOpen) btnResetApp.id else rowImportExport.id
    }

    // ── Split tunneling (bypass VPN per-app) ─────────────────────

    private fun loadExcludedApps(): MutableSet<String> {
        val json = prefs.getString("excluded_apps_json", null) ?: return mutableSetOf()
        return try {
            val arr = org.json.JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) { mutableSetOf() }
    }

    private fun saveExcludedApps(apps: Set<String>) {
        val arr = org.json.JSONArray()
        apps.sorted().forEach { arr.put(it) }
        prefs.edit().putString("excluded_apps_json", arr.toString()).apply()
    }

    private fun showSplitTunnelDialog() {
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.split_tunnel_title))
            .setMessage(getString(R.string.split_tunnel_loading))
            .setCancelable(false)
            .show()

        Thread {
            val pm = packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != packageName } // exclude ourselves
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

            val excluded = loadExcludedApps()
            val labels = installedApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
            val packages = installedApps.map { it.packageName }
            val checked = BooleanArray(installedApps.size) { packages[it] in excluded }

            runOnUiThread {
                loadingDialog.dismiss()
                showSplitTunnelListDialog(labels, packages, checked)
            }
        }.start()
    }

    private fun showSplitTunnelListDialog(
        labels: Array<String>,
        packages: List<String>,
        checked: BooleanArray
    ) {
        // Data model for filtered list
        data class AppItem(val index: Int, val label: String, val pkg: String)
        val allItems = labels.indices.map { AppItem(it, labels[it], packages[it]) }
        val filteredItems = mutableListOf<AppItem>().apply { addAll(allItems) }

        // Popular apps by category (multiple package variants for mobile/TV)
        data class PopularApp(val name: String, val pkgs: List<String>)
        val streamingApps = listOf(
            PopularApp("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
            PopularApp("Amazon Prime Video", listOf("com.amazon.avod", "com.amazon.avod.thirdpartyclient")),
            PopularApp("Disney+", listOf("com.disney.disneyplus")),
            PopularApp("YouTube", listOf("com.google.android.youtube", "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic")),
            PopularApp("Apple TV", listOf("com.apple.atve.androidtv.appletv")),
            PopularApp("Twitch", listOf("tv.twitch.android.app", "tv.twitch.android.viewer")),
            PopularApp("Kick", listOf("com.kick.mobile", "com.kick.app")),
            PopularApp("Paramount+", listOf("com.cbs.ca", "com.cbs.ott")),
            PopularApp("Crunchyroll", listOf("com.crunchyroll.crunchyroid")),
            PopularApp("Max (HBO)", listOf("com.wbd.stream", "com.hbo.hbonow")),
            PopularApp("Plex", listOf("com.plexapp.android")),
            PopularApp("SmartTube", listOf("org.smarttube.stable", "com.liskovsoft.smarttubetv.beta")),
        )
        val vodFrApps = listOf(
            PopularApp("Canal+ / myCANAL", listOf("com.mycanal", "com.canal.android.canal")),
            PopularApp("Molotov", listOf("tv.molotov.app")),
            PopularApp("France TV", listOf("fr.francetv.pluzz", "fr.francetv.ftvinforeplay")),
            PopularApp("Arte", listOf("tv.arte.plus7")),
            PopularApp("TF1+", listOf("fr.tf1.mytf1")),
            PopularApp("6play (M6)", listOf("fr.m6.m6replay")),
            PopularApp("OCS / Max", listOf("com.orange.ocsgo")),
            PopularApp("Orange TV", listOf("com.orange.owtv.tv", "com.orange.owtv")),
            PopularApp("Free TV (Oqee)", listOf("net.oqee.androidtv.store", "net.oqee.androidtv")),
            PopularApp("SFR Play", listOf("fr.sfr.tv", "com.sfr.tv")),
        )
        val speedtestApps = listOf(
            PopularApp("Analiti", listOf("com.analiti.fastest.android", "com.analiti.fastest.speedtest")),
            PopularApp("Fast.com", listOf("com.netflix.Speedtest")),
            PopularApp("Speedtest (Ookla)", listOf("org.zwanoo.android.speedtest")),
            PopularApp("nPerf", listOf("com.nperf.tester")),
        )

        // Flatten all popular packages
        val allPopularPkgs = (streamingApps + vodFrApps + speedtestApps).flatMap { it.pkgs }.toSet()

        // Build index: packageName → index in the main list
        val pkgIndex = mutableMapOf<String, Int>()
        for (i in packages.indices) pkgIndex[packages[i]] = i

        // Track popular apps not in installed list
        val excluded = loadExcludedApps()
        val extraPopularChecked = mutableMapOf<String, Boolean>()
        for (pkg in allPopularPkgs) {
            if (!pkgIndex.containsKey(pkg)) {
                extraPopularChecked[pkg] = pkg in excluded
            }
        }

        // Resolve best package for a PopularApp (first installed, or first in list)
        fun resolveApp(app: PopularApp): Pair<String, Boolean> {
            for (pkg in app.pkgs) {
                if (pkgIndex.containsKey(pkg)) return pkg to true
            }
            return app.pkgs.first() to false
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        // Description
        rootLayout.addView(TextView(this).apply {
            text = getString(R.string.split_tunnel_desc)
            setTextColor(pdmTextSecondary()); textSize = 12f
            setPadding(0, 0, 0, 12)
        })

        // Counter function
        fun countChecked(): Int = checked.count { it } + extraPopularChecked.values.count { it }

        val tvCount = TextView(this).apply {
            text = getString(R.string.split_tunnel_apps_count, countChecked())
            setTextColor(pdmWarning()); textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        rootLayout.addView(tvCount)

        // ── Collapsible category builder ──
        fun makeFocusBg(normalColor: Int): android.graphics.drawable.StateListDrawable {
            val focused = android.graphics.drawable.GradientDrawable().apply {
                setColor(pdmSurfaceElevated()); cornerRadius = 8f
                setStroke(2, pdmWarning())
            }
            val normal = android.graphics.drawable.GradientDrawable().apply {
                setColor(normalColor); cornerRadius = 8f
            }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
        }

        // Categories container (in its own ScrollView)
        val categoriesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Temporarily redirect addCollapsibleCategory to categoriesLayout
        val catTarget = categoriesLayout
        fun addCat(title: String, unsortedApps: List<PopularApp>) {
            // Installed apps first
            val apps = unsortedApps.sortedBy { app -> if (app.pkgs.any { pkgIndex.containsKey(it) }) 0 else 1 }
            val headerRef = arrayOfNulls<TextView>(1)
            val contentRef = arrayOfNulls<LinearLayout>(1)

            val header = TextView(this).apply {
                text = "\u25B6 $title"
                setTextColor(pdmWarning()); textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 16, 16, 16)
                isFocusable = true
                isClickable = true
                background = makeFocusBg(pdmBackground())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }
            headerRef[0] = header

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(16, 4, 8, 8)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(pdmSurface())
                    cornerRadius = 0f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            contentRef[0] = content

            for (app in apps) {
                val (pkg, isInstalled) = resolveApp(app)
                val installedIdx = pkgIndex[pkg]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(12, 6, 12, 6)
                    isFocusable = true
                    isClickable = true
                    background = makeFocusBg(pdmSurface())
                }
                val cb = android.widget.CheckBox(this).apply {
                    isFocusable = false
                    isChecked = if (isInstalled && installedIdx != null) checked[installedIdx] else (extraPopularChecked[pkg] == true)
                    setOnCheckedChangeListener { _, isChk ->
                        if (isInstalled && installedIdx != null) {
                            checked[installedIdx] = isChk
                        } else {
                            extraPopularChecked[pkg] = isChk
                        }
                        tvCount.text = getString(R.string.split_tunnel_apps_count, countChecked())
                    }
                }
                row.addView(cb)
                val label = if (isInstalled) app.name else "${app.name} (non install\u00e9)"
                row.addView(TextView(this).apply {
                    text = label
                    setTextColor(if (isInstalled) pdmBorder() else pdmTextDisabled())
                    textSize = 13f
                    setPadding(8, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                content.addView(row)
            }

            header.setOnClickListener {
                if (content.visibility == View.GONE) {
                    content.visibility = View.VISIBLE
                    header.text = "\u25BC $title"
                } else {
                    content.visibility = View.GONE
                    header.text = "\u25B6 $title"
                }
            }

            catTarget.addView(header)
            catTarget.addView(content)
        }

        addCat("\uD83C\uDFAC Streaming", streamingApps)
        addCat("\uD83C\uDDEB\uD83C\uDDF7 VOD France", vodFrApps)
        addCat("\uD83D\uDCF6 Speedtest", speedtestApps)

        rootLayout.addView(categoriesLayout)

        // ── Collapsible "All apps" section ──
        val allAppsHeader = TextView(this).apply {
            text = "\u25B6 \uD83D\uDCF1 Toutes les applications"
            setTextColor(pdmWarning()); textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 16, 16)
            isFocusable = true
            isClickable = true
            background = makeFocusBg(pdmBackground())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        rootLayout.addView(allAppsHeader)

        val allAppsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }

        // Search bar
        val searchInput = android.widget.EditText(this).apply {
            hint = getString(R.string.split_tunnel_search)
            setTextColor(pdmTextPrimary()); setHintTextColor(pdmTextDisabled())
            textSize = 14f; setPadding(20, 12, 20, 12)
            isFocusable = true
            isFocusableInTouchMode = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pdmSurfaceInput())
                cornerRadius = 8f
                setStroke(1, pdmBorder())
            }
        }
        allAppsContent.addView(searchInput)

        // Hint text
        val searchHint = TextView(this).apply {
            text = "Tapez pour rechercher parmi ${allItems.size} applications..."
            setTextColor(pdmTextDisabled()); textSize = 12f
            setPadding(8, 8, 0, 8)
        }
        allAppsContent.addView(searchHint)

        // Dynamic results container (no ListView = no scroll conflict)
        val resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        allAppsContent.addView(resultsContainer)

        rootLayout.addView(allAppsContent)

        // Build search result row
        fun makeAppRow(item: AppItem): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(12, 6, 12, 6)
                isFocusable = true
                isClickable = true
                background = makeFocusBg(pdmSurface())
            }
            val cb = android.widget.CheckBox(this).apply {
                isFocusable = false
                isChecked = checked[item.index]
                setOnCheckedChangeListener { _, isChk ->
                    checked[item.index] = isChk
                    tvCount.text = getString(R.string.split_tunnel_apps_count, countChecked())
                }
            }
            row.addView(cb)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = item.label
                setTextColor(pdmBorder()); textSize = 13f
                setPadding(12, 0, 0, 0)
            })
            col.addView(TextView(this).apply {
                text = item.pkg
                setTextColor(pdmTextDisabled()); textSize = 10f
                setPadding(12, 0, 0, 0)
            })
            row.addView(col)
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            return row
        }

        allAppsHeader.setOnClickListener {
            if (allAppsContent.visibility == View.GONE) {
                allAppsContent.visibility = View.VISIBLE
                allAppsHeader.text = "\u25BC \uD83D\uDCF1 Toutes les applications"
            } else {
                allAppsContent.visibility = View.GONE
                allAppsHeader.text = "\u25B6 \uD83D\uDCF1 Toutes les applications"
            }
        }

        // Search filter — populates results dynamically (max 30 results)
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.lowercase()?.trim() ?: ""
                resultsContainer.removeAllViews()
                if (query.length < 2) {
                    searchHint.visibility = View.VISIBLE
                    searchHint.text = if (query.isEmpty()) "Tapez pour rechercher parmi ${allItems.size} applications..."
                                      else "Tapez au moins 2 caract\u00e8res..."
                    return
                }
                searchHint.visibility = View.GONE
                val results = allItems.filter {
                    it.label.lowercase().contains(query) || it.pkg.lowercase().contains(query)
                }.take(30)
                if (results.isEmpty()) {
                    searchHint.visibility = View.VISIBLE
                    searchHint.text = "Aucun r\u00e9sultat pour \"$query\""
                } else {
                    for (item in results) {
                        resultsContainer.addView(makeAppRow(item))
                    }
                    if (results.size == 30) {
                        resultsContainer.addView(TextView(this@SettingsActivity).apply {
                            text = "... affinez votre recherche"
                            setTextColor(pdmTextDisabled()); textSize = 11f
                            setPadding(12, 8, 0, 4)
                        })
                    }
                }
            }
        })

        // Wrap everything in NestedScrollView — single scrollable container, no conflicts
        val scrollWrapper = androidx.core.widget.NestedScrollView(this).apply {
            addView(rootLayout)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.split_tunnel_title))
            .setView(scrollWrapper)
            .setPositiveButton(getString(R.string.split_tunnel_save)) { _, _ ->
                val selected = mutableSetOf<String>()
                for (i in packages.indices) {
                    if (checked[i]) selected.add(packages[i])
                }
                for ((pkg, isChk) in extraPopularChecked) {
                    if (isChk) selected.add(pkg)
                }
                saveExcludedApps(selected)
                Toast.makeText(this, getString(R.string.split_tunnel_saved), Toast.LENGTH_SHORT).show()

                if (net.appstorefr.perfectdnsmanager.service.DnsVpnService.isVpnRunning) {
                    val profileJson = prefs.getString("selected_profile_json", null)
                    if (profileJson != null) {
                        try {
                            val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                            if (profile.type != net.appstorefr.perfectdnsmanager.data.DnsType.DOT) {
                                val intent = Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java).apply {
                                    action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_START
                                    putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
                                    profile.secondary?.let { putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                                else startService(intent)
                                Toast.makeText(this, getString(R.string.split_tunnel_restart_vpn), Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton(getString(R.string.uncheck_all)) { _, _ ->
                for (i in checked.indices) checked[i] = false
                for (key in extraPopularChecked.keys) extraPopularChecked[key] = false
                saveExcludedApps(emptySet())
                Toast.makeText(this, getString(R.string.all_apps_unchecked), Toast.LENGTH_SHORT).show()

                if (net.appstorefr.perfectdnsmanager.service.DnsVpnService.isVpnRunning) {
                    val profileJson = prefs.getString("selected_profile_json", null)
                    if (profileJson != null) {
                        try {
                            val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                            if (profile.type != net.appstorefr.perfectdnsmanager.data.DnsType.DOT) {
                                val intent = Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java).apply {
                                    action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_START
                                    putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
                                    profile.secondary?.let { putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                                else startService(intent)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            .create()

        dlg.show()

        dlg.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.9).toInt()
        )
    }

    private fun showUrlRewriteDialog() {
        val repo = net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository(this)
        val rules = repo.getAllRules()

        val items = mutableListOf<String>()
        items.add(getString(R.string.add_new_rule))
        rules.forEach { r ->
            val status = if (r.isEnabled) "✅" else "❌"
            items.add("$status ${r.fromDomain} → ${r.toDomain}")
        }

        net.appstorefr.perfectdnsmanager.util.TvDialog.showMenuPicker(
            this,
            getString(R.string.url_rewrite_title),
            items.toTypedArray()
        ) { which ->
            if (which == 0) {
                showAddRewriteRuleDialog(repo)
            } else {
                val rule = rules[which - 1]
                showEditRewriteRuleDialog(repo, rule)
            }
        }
    }

    private fun showAddRewriteRuleDialog(repo: net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvExplain = TextView(this).apply {
            text = getString(R.string.rewrite_explain)
            setTextColor(pdmTextSecondary())
            textSize = 12f
        }
        layout.addView(tvExplain)

        val lbl = { text: String ->
            TextView(this).apply {
                this.text = text
                setTextColor(pdmTextSecondary())
                setPadding(0, 16, 0, 4)
                textSize = 13f
            }
        }

        val etDomain = android.widget.EditText(this).apply {
            setTextColor(pdmTextPrimary())
            setHintTextColor(pdmTextDisabled())
            hint = getString(R.string.source_domain_hint)
            isSingleLine = true
            setBackgroundColor(pdmSurfaceInput())
            setPadding(20, 15, 20, 15)
        }
        val etTarget = android.widget.EditText(this).apply {
            setTextColor(pdmTextPrimary())
            setHintTextColor(pdmTextDisabled())
            hint = getString(R.string.dest_domain_hint)
            isSingleLine = true
            setBackgroundColor(pdmSurfaceInput())
            setPadding(20, 15, 20, 15)
        }

        layout.addView(lbl(getString(R.string.source_domain)))
        layout.addView(etDomain)
        layout.addView(lbl(getString(R.string.dest_domain)))
        layout.addView(etTarget)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_rewrite_rule_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val domain = etDomain.text.toString().trim()
                val target = etTarget.text.toString().trim()
                if (domain.isNotEmpty() && target.isNotEmpty()) {
                    val rule = net.appstorefr.perfectdnsmanager.data.DnsRewriteRule(
                        id = System.currentTimeMillis(),
                        fromDomain = domain,
                        toDomain = target,
                        isEnabled = true
                    )
                    repo.addRule(rule)
                    reloadVpnRewriteRules()
                    Toast.makeText(this, getString(R.string.rewrite_added, domain, target), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditRewriteRuleDialog(repo: net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository, rule: net.appstorefr.perfectdnsmanager.data.DnsRewriteRule) {
        val statusText = if (rule.isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
        AlertDialog.Builder(this)
            .setTitle("${rule.fromDomain} → ${rule.toDomain}")
            .setMessage(getString(R.string.status_label, statusText))
            .setPositiveButton(if (rule.isEnabled) getString(R.string.disable_button) else getString(R.string.enable_button)) { _, _ ->
                repo.updateRule(rule.copy(isEnabled = !rule.isEnabled))
                reloadVpnRewriteRules()
                val newStatus = if (!rule.isEnabled) getString(R.string.rule_enabled) else getString(R.string.rule_disabled)
                Toast.makeText(this, newStatus, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.delete)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_title))
                    .setMessage(getString(R.string.delete_rule_confirm, rule.fromDomain, rule.toDomain))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        repo.deleteRule(rule)
                        reloadVpnRewriteRules()
                        Toast.makeText(this, getString(R.string.rule_deleted), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun reloadVpnRewriteRules() {
        try {
            val intent = android.content.Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java)
            intent.action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_RELOAD_RULES
            startService(intent)
        } catch (_: Exception) { }
    }

    private fun exportConfiguration() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val cbProfiles = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_profiles)
            isChecked = true; isEnabled = false
            setTextColor(pdmTextSecondary())
        }
        val cbDefault = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_default_dns); isChecked = true
            setTextColor(pdmTextPrimary())
        }
        val cbNextDns = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_nextdns); isChecked = true
            setTextColor(pdmTextPrimary())
        }
        val cbRewrite = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_rewrite); isChecked = true
            setTextColor(pdmTextPrimary())
        }
        val cbSettings = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_settings); isChecked = true
            setTextColor(pdmTextPrimary())
        }

        layout.addView(cbProfiles)
        layout.addView(cbDefault)
        layout.addView(cbNextDns)
        layout.addView(cbRewrite)
        layout.addView(cbSettings)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_select_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.export_button)) { _, _ ->
                performExport(
                    includeProfiles = true,
                    includeDefaultProfile = cbDefault.isChecked,
                    includeNextDnsProfiles = cbNextDns.isChecked,
                    includeRewriteRules = cbRewrite.isChecked,
                    includeSettings = cbSettings.isChecked
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performExport(
        includeProfiles: Boolean,
        includeDefaultProfile: Boolean,
        includeNextDnsProfiles: Boolean,
        includeRewriteRules: Boolean,
        includeSettings: Boolean
    ) {
        try {
            val configManager = net.appstorefr.perfectdnsmanager.data.ConfigManager(this)
            val json = configManager.exportConfigSelective(
                includeProfiles, includeDefaultProfile,
                includeNextDnsProfiles, includeRewriteRules, includeSettings
            )
            // Directly upload encrypted
            showExpirationAndUpload(json)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showExpirationAndUpload(content: String) {
        encryptAndUpload(content)
    }

    private fun encryptAndUpload(content: String, expiresIn: String = "1h") {
        Toast.makeText(this, getString(R.string.uploading), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val result = net.appstorefr.perfectdnsmanager.util.EncryptedSharer.encryptAndUpload(
                    content, "PerfectDNS-config.enc", expiresIn
                )
                runOnUiThread {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PDM Share", "${result.fullUrl}\nMot de passe : ${result.password}"))
                    val msg = android.text.SpannableString(
                        "Lien (chiffré de bout en bout) :\n${result.fullUrl}\n\nMot de passe :\n${result.password}\n\nCopié au presse-papier. Partagez lien + mot de passe séparément. La clé n'est jamais envoyée au serveur."
                    )
                    val linkColor = pdmAccentAlt()
                    val accentColor = pdmAccent()
                    val urlStart = msg.indexOf(result.fullUrl)
                    if (urlStart >= 0) msg.setSpan(android.text.style.ForegroundColorSpan(linkColor), urlStart, urlStart + result.fullUrl.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val pwdStart = msg.indexOf(result.password)
                    if (pwdStart >= 0) {
                        msg.setSpan(android.text.style.ForegroundColorSpan(accentColor), pwdStart, pwdStart + result.password.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        msg.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), pwdStart, pwdStart + result.password.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.upload_success_title))
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.upload_error, e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showImportOptions() {
        // Directly open encrypted code import dialog
        showImportFromCodeDialog()
    }

    private fun showImportFromCodeDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val tvExplain = TextView(this).apply {
            text = "Lien court (ou code 8 caractères) :"
            setTextColor(pdmTextSecondary()); textSize = 13f
        }
        layout.addView(tvExplain)
        val editCode = android.widget.EditText(this).apply {
            hint = "pdm.appstorefr.net/d/… ou abcd1234"
            setTextColor(pdmTextPrimary()); setHintTextColor(pdmTextDisabled())
            setBackgroundColor(pdmSurfaceInput()); setPadding(20, 15, 20, 15)
            isSingleLine = true
        }
        layout.addView(editCode)

        val tvPwdLabel = TextView(this).apply {
            text = "Mot de passe (6 caractères) :"
            setTextColor(pdmTextSecondary()); textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(tvPwdLabel)
        val editPwd = android.widget.EditText(this).apply {
            hint = "a2z5s1"
            setTextColor(pdmTextPrimary()); setHintTextColor(pdmTextDisabled())
            setBackgroundColor(pdmSurfaceInput()); setPadding(20, 15, 20, 15)
            isSingleLine = true
        }
        layout.addView(editPwd)

        val tvInfo = TextView(this).apply {
            text = getString(R.string.import_code_explain)
            setTextColor(pdmTextDisabled()); textSize = 11f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(tvInfo)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_from_code_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.download_button)) { _, _ ->
                val code = editCode.text.toString().trim()
                val pwd = editPwd.text.toString().trim()
                if (code.isEmpty() || pwd.isEmpty()) {
                    Toast.makeText(this, getString(R.string.link_and_password_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val json = net.appstorefr.perfectdnsmanager.util.EncryptedSharer.downloadAndDecrypt(code, pwd)
                        if (json.isNotBlank()) {
                            runOnUiThread { confirmAndImport(json) }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.read_error, "Empty response"), Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.read_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmAndImport(json: String) {
        try {
            val configManager = net.appstorefr.perfectdnsmanager.data.ConfigManager(this)
            val summary = configManager.getConfigSummary(json)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_import_title))
                .setMessage(getString(R.string.confirm_import_message, summary))
                .setPositiveButton(getString(R.string.import_with_settings)) { _, _ ->
                    val result = configManager.importConfig(json, importSettings = true)
                    showImportResult(result)
                }
                .setNeutralButton(getString(R.string.import_without_settings)) { _, _ ->
                    val result = configManager.importConfig(json, importSettings = false)
                    showImportResult(result)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.invalid_json, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePermissionUI() {
        val granted = adbDnsManager.isPermissionGranted()
        if (granted) {
            tvPermissionStatus?.text = getString(R.string.permission_granted)
            tvPermissionStatus?.setTextColor(getColor(android.R.color.holo_green_light))
            btnSelfGrant?.text = getString(R.string.permission_already_granted)
            btnSelfGrant?.isEnabled = false
            btnSelfGrant?.alpha = 0.5f
        } else {
            tvPermissionStatus?.text = getString(R.string.permission_not_granted)
            tvPermissionStatus?.setTextColor(getColor(android.R.color.holo_red_light))
            btnSelfGrant?.text = getString(R.string.self_grant_button)
            btnSelfGrant?.isEnabled = true
            btnSelfGrant?.alpha = 1f
        }
    }

    private fun showAdbUnreachableHelp() {
        val isFireTv = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        val msg = if (isFireTv) {
            getString(R.string.self_grant_help_firetv)
        } else {
            getString(R.string.self_grant_help_phone)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.self_grant_help_title))
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    private fun showImportResult(result: net.appstorefr.perfectdnsmanager.data.ConfigManager.ImportResult) {
        val msg = StringBuilder()
        msg.appendLine(getString(R.string.import_done))
        msg.appendLine()
        msg.appendLine(getString(R.string.import_profiles_count, result.profileCount))
        msg.appendLine(getString(R.string.import_rewrite_count, result.rewriteRuleCount))
        msg.appendLine(getString(R.string.import_nextdns_count, result.nextDnsProfileCount))
        if (result.hasDefaultProfile) msg.appendLine(getString(R.string.import_default_restored))
        if (result.settingsRestored) msg.appendLine(getString(R.string.import_settings_restored))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_done_title))
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show()
    }
}
