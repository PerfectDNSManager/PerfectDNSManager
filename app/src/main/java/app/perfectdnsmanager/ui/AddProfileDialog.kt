package app.perfectdnsmanager.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import app.perfectdnsmanager.R
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.util.DnsColors

class AddProfileDialog(
    context: Context,
    private val advancedEnabled: Boolean = true,
    private val onProfileCreated: (DnsProfile) -> Unit
) : Dialog(context) {

    private lateinit var etName: EditText
    private lateinit var rgType: RadioGroup
    private lateinit var rbDoh: RadioButton
    private lateinit var rbDot: RadioButton
    private lateinit var rbDoq: RadioButton
    private lateinit var rbStandard: RadioButton
    private lateinit var etPrimary: EditText
    private lateinit var etSecondary: EditText
    private lateinit var tvSecondaryLabel: TextView
    private lateinit var etPrimaryV6: EditText
    private lateinit var etSecondaryV6: EditText
    private lateinit var tvPrimaryV6Label: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_add_profile)
        window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Background opaque pour que le dialog soit un vrai modal — sans ça
            // le contenu de l'activité derrière transparaît à travers le dialog
            // (constaté visuellement : "Choisir un serveur DNS" + Mullvad row
            // visibles à travers la zone Nom du profil / Type de DNS).
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    context.getColor(R.color.pdm_surface)
                )
            )
        }

        initViews()
        setupTypeSelection()
        setupButtons()
    }

    private fun initViews() {
        etName = findViewById(R.id.etName)
        rgType = findViewById(R.id.rgType)
        rbDoh = findViewById(R.id.rbDoh)
        rbDot = findViewById(R.id.rbDot)
        rbDoq = findViewById(R.id.rbDoq)
        rbStandard = findViewById(R.id.rbStandard)
        etPrimary = findViewById(R.id.etPrimary)
        etSecondary = findViewById(R.id.etSecondary)
        tvSecondaryLabel = findViewById(R.id.tvSecondaryLabel)
        etPrimaryV6 = findViewById(R.id.etPrimaryV6)
        etSecondaryV6 = findViewById(R.id.etSecondaryV6)
        tvPrimaryV6Label = findViewById(R.id.tvPrimaryV6Label)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

        // Apply protocol colors to radio buttons
        rbDoh.buttonTintList = ColorStateList.valueOf(DnsColors.colorForType(DnsType.DOH))
        rbDot.buttonTintList = ColorStateList.valueOf(DnsColors.colorForType(DnsType.DOT))
        rbDoq.buttonTintList = ColorStateList.valueOf(DnsColors.colorForType(DnsType.DOQ))
        rbStandard.buttonTintList = ColorStateList.valueOf(DnsColors.colorForType(DnsType.DEFAULT))

        rbDoh.isChecked = true
        etSecondary.visibility = View.GONE
        tvSecondaryLabel.visibility = View.GONE

        // Masquer DoT, DoQ et Standard si mode avancé désactivé
        if (!advancedEnabled) {
            rbDot.visibility = View.GONE
            rbDoq.visibility = View.GONE
            rbStandard.visibility = View.GONE
        }
    }

    private fun setupTypeSelection() {
        rgType.setOnCheckedChangeListener { _, checkedId ->
            val showSecondary = checkedId == R.id.rbStandard
            etSecondary.visibility = if (showSecondary) View.VISIBLE else View.GONE
            tvSecondaryLabel.visibility = if (showSecondary) View.VISIBLE else View.GONE
            etPrimaryV6.visibility = if (showSecondary) View.VISIBLE else View.GONE
            etSecondaryV6.visibility = if (showSecondary) View.VISIBLE else View.GONE
            tvPrimaryV6Label.visibility = if (showSecondary) View.VISIBLE else View.GONE
            if (!showSecondary) {
                etSecondary.text.clear()
                etPrimaryV6.text.clear()
                etSecondaryV6.text.clear()
            }

            etPrimary.hint = when (checkedId) {
                R.id.rbDoh -> "Ex: https://dns.adguard-dns.com/dns-query"
                R.id.rbDoq -> "Ex: quic://dns.adguard-dns.com"
                R.id.rbDot -> "Ex: dns.adguard-dns.com"
                else -> "Ex: 94.140.14.14"
            }
        }
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            if (validateInput()) {
                val selectedType = when (rgType.checkedRadioButtonId) {
                    R.id.rbDoh -> DnsType.DOH
                    R.id.rbDot -> DnsType.DOT
                    R.id.rbDoq -> DnsType.DOQ
                    else -> DnsType.DEFAULT
                }

                val profile = DnsProfile(
                    providerName = etName.text.toString().trim(),
                    name = "Custom",
                    type = selectedType,
                    primary = etPrimary.text.toString().trim(),
                    secondary = etSecondary.text.toString().trim().takeIf { it.isNotBlank() },
                    primaryV6 = etPrimaryV6.text.toString().trim().takeIf { it.isNotBlank() },
                    secondaryV6 = etSecondaryV6.text.toString().trim().takeIf { it.isNotBlank() },
                    descResId = R.string.dns_desc_custom,
                    isCustom = true
                )
                onProfileCreated(profile)
                dismiss()
            }
        }
        btnCancel.setOnClickListener { dismiss() }
    }

    private fun validateInput(): Boolean {
        if (etName.text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.name_required), Toast.LENGTH_SHORT).show()
            return false
        }
        val primary = etPrimary.text.toString().trim()
        if (primary.isBlank()) {
            Toast.makeText(context, context.getString(R.string.primary_dns_required), Toast.LENGTH_SHORT).show()
            return false
        }
        val type = when (rgType.checkedRadioButtonId) {
            R.id.rbDoh -> DnsType.DOH
            R.id.rbDot -> DnsType.DOT
            R.id.rbDoq -> DnsType.DOQ
            else -> DnsType.DEFAULT
        }
        if (!isValidPrimaryFor(type, primary)) {
            Toast.makeText(context, context.getString(R.string.primary_dns_invalid), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    /**
     * Validation par type :
     *  - DoH : `https://<host>[/path]` avec hostname valide
     *  - DoQ : `quic://<host>[:port]` avec hostname valide
     *  - DoT : hostname brut sans scheme
     *  - DEFAULT : IPv4 littérale
     * Empêche un user de coller `javascript:` ou un host bizarre dans un champ
     * qui sera ensuite passé à OkHttp / kwik / VpnService.
     */
    private fun isValidPrimaryFor(type: DnsType, raw: String): Boolean {
        val hostnameRe = Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
        val ipv4Re = Regex("^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)){3}$")
        return when (type) {
            DnsType.DOH -> {
                if (!raw.startsWith("https://", ignoreCase = true)) return false
                val rest = raw.removePrefix("https://").removePrefix("HTTPS://")
                val host = rest.substringBefore('/').substringBefore(':').substringBefore('?')
                hostnameRe.matches(host)
            }
            DnsType.DOQ -> {
                if (!raw.startsWith("quic://", ignoreCase = true)) return false
                val rest = raw.removePrefix("quic://").removePrefix("QUIC://")
                val host = rest.substringBefore('/').substringBefore(':')
                hostnameRe.matches(host)
            }
            DnsType.DOT -> hostnameRe.matches(raw)
            DnsType.DEFAULT -> ipv4Re.matches(raw)
        }
    }
}
