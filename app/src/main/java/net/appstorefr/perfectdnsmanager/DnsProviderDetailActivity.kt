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
import net.appstorefr.perfectdnsmanager.util.pdmDanger
import net.appstorefr.perfectdnsmanager.util.pdmWarning

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.util.DnsColors
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class DnsProviderDetailActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_provider_detail)

        val providerName = intent.getStringExtra("PROVIDER_NAME") ?: run { finish(); return }
        val profilesJson = intent.getStringExtra("PROFILES_JSON") ?: run { finish(); return }
        val profiles: List<DnsProfile> = Gson().fromJson(profilesJson, object : TypeToken<List<DnsProfile>>() {}.type)
        val isNextDns = intent.getBooleanExtra("IS_NEXTDNS", false)

        // Header
        val btnBack: Button = findViewById(R.id.btnBack)
        val tvProviderName: TextView = findViewById(R.id.tvProviderName)
        val ivProviderIcon: ImageView = findViewById(R.id.ivProviderIcon)

        btnBack.setOnClickListener { finish() }
        val actualName = providerName.removeSuffix(" ★")
        tvProviderName.text = actualName
        ivProviderIcon.setImageResource(DnsProfile.getProviderIcon(actualName))

        // Ratings
        val rating = DnsProfile.providerRatings[actualName]
        val layoutRatings: LinearLayout = findViewById(R.id.layoutRatings)
        if (rating != null) {
            layoutRatings.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSpeedLabel).text = getString(R.string.speed_label) + ": "
            findViewById<TextView>(R.id.tvSpeedStars).text = "★".repeat(rating.speed) + "☆".repeat(5 - rating.speed)
            findViewById<TextView>(R.id.tvPrivacyLabel).text = getString(R.string.privacy_label) + ": "
            findViewById<TextView>(R.id.tvPrivacyStars).text = "★".repeat(rating.privacy) + "☆".repeat(5 - rating.privacy)
        }

        // NextDNS: add profile button
        val btnAddProfile: Button = findViewById(R.id.btnAddProfile)
        if (isNextDns) {
            btnAddProfile.visibility = View.VISIBLE
            btnAddProfile.setOnClickListener { showAddNextDnsProfileDialog() }
        }

        // Group profiles by type, ordered: DoH > DoQ > DoT > Standard
        val typeOrder = listOf(DnsType.DOH, DnsType.DOQ, DnsType.DOT, DnsType.DEFAULT)
        val grouped = typeOrder.mapNotNull { type ->
            val typeProfiles = profiles.filter { it.type == type }
            if (typeProfiles.isNotEmpty()) type to typeProfiles else null
        }

        // Profile list with expandable categories
        val rvProfiles: RecyclerView = findViewById(R.id.rvProfiles)
        rvProfiles.layoutManager = LinearLayoutManager(this)
        rvProfiles.adapter = ExpandableProfileAdapter(
            rvProfiles, grouped,
            onProfileClick = { profile ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("SELECTED_PROFILE_JSON", Gson().toJson(profile))
                })
                finish()
            },
            onProfileLongClick = { profile ->
                showProfileActions(profile)
            }
        )
    }

    private fun showProfileActions(profile: DnsProfile) {
        val profileManager = net.appstorefr.perfectdnsmanager.data.ProfileManager(this)
        val isDefault = isCurrentDefault(profile)
        val defaultLabel = if (isDefault) getString(R.string.remove_default_dns) else getString(R.string.set_default_dns)
        val actions = arrayOf(defaultLabel, getString(R.string.edit_button), getString(R.string.delete_button))

        net.appstorefr.perfectdnsmanager.util.TvDialog.showMenuPicker(
            this,
            "${profile.providerName} \u2014 ${profile.name}",
            actions
        ) { which ->
            when (which) {
                0 -> toggleDefaultProfile(profile, isDefault)
                1 -> showEditProfileDialog(profile, profileManager)
                2 -> confirmDeleteProfile(profile, profileManager)
            }
        }
    }

    private fun isCurrentDefault(profile: DnsProfile): Boolean {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val defJson = prefs.getString("default_profile_json", null) ?: return false
        return try {
            Gson().fromJson(defJson, DnsProfile::class.java).id == profile.id
        } catch (_: Exception) { false }
    }

    private fun toggleDefaultProfile(profile: DnsProfile, wasDefault: Boolean) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (wasDefault) {
            prefs.edit().remove("default_profile_json").apply()
            Toast.makeText(this, getString(R.string.default_dns_removed), Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putString("default_profile_json", Gson().toJson(profile)).apply()
            Toast.makeText(this, getString(R.string.default_dns_set, "${profile.providerName} \u2014 ${profile.name}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditProfileDialog(profile: DnsProfile, profileManager: net.appstorefr.perfectdnsmanager.data.ProfileManager) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val etName = EditText(this).apply {
            setText(profile.name); setTextColor(pdmTextPrimary())
            setBackgroundColor(pdmSurfaceInput()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.profile_name); setHintTextColor(pdmTextDisabled())
        }
        val etPrimary = EditText(this).apply {
            setText(profile.primary); setTextColor(pdmTextPrimary())
            setBackgroundColor(pdmSurfaceInput()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.primary_dns); setHintTextColor(pdmTextDisabled())
        }
        val etSecondary = EditText(this).apply {
            setText(profile.secondary ?: ""); setTextColor(pdmTextPrimary())
            setBackgroundColor(pdmSurfaceInput()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.secondary_dns_optional); setHintTextColor(pdmTextDisabled())
        }

        val lbl = { text: String -> TextView(this).apply { this.text = text; setTextColor(pdmTextSecondary()); setPadding(0, 16, 0, 8) } }

        layout.addView(lbl(getString(R.string.name_label))); layout.addView(etName)
        layout.addView(lbl(getString(R.string.primary_dns_label))); layout.addView(etPrimary)
        if (profile.type == DnsType.DEFAULT) {
            layout.addView(lbl(getString(R.string.secondary_dns_label))); layout.addView(etSecondary)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_profile_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                val updated = profile.copy(
                    name = etName.text.toString().trim().ifEmpty { profile.name },
                    primary = etPrimary.text.toString().trim().ifEmpty { profile.primary },
                    secondary = etSecondary.text.toString().trim().takeIf { it.isNotEmpty() }
                )
                profileManager.updateProfile(updated)
                Toast.makeText(this, getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteProfile(profile: DnsProfile, profileManager: net.appstorefr.perfectdnsmanager.data.ProfileManager) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_profile_title))
            .setMessage("${profile.providerName} \u2014 ${profile.name}\n${profile.primary}")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                profileManager.deleteProfile(profile.id)
                if (isCurrentDefault(profile)) {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().remove("default_profile_json").apply()
                }
                if (profile.isCustom && profile.providerName == "NextDNS") {
                    val nextDnsId = extractNextDnsId(profile.primary)
                    if (nextDnsId != null) {
                        val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                        val savedIds = (customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()).toMutableSet()
                        if (savedIds.remove(nextDnsId)) {
                            customPrefs.edit().putStringSet("profile_ids", savedIds).apply()
                        }
                    }
                }
                Toast.makeText(this, getString(R.string.profile_deleted), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Extrait l'ID NextDNS depuis l'URL primaire d'un profil.
     * Formats : quic://dns.nextdns.io/ID, https://dns.nextdns.io/ID, ID.dns.nextdns.io.
     * Renvoie null pour le mode Standard (IP sans ID).
     */
    private fun extractNextDnsId(primary: String): String? {
        val trimmed = primary.trim()
        Regex("""(?:quic://|https://)dns\.nextdns\.io/([a-z0-9]+)""").find(trimmed)?.let {
            return it.groupValues[1]
        }
        Regex("""^([a-z0-9]+)\.dns\.nextdns\.io$""").find(trimmed)?.let {
            return it.groupValues[1]
        }
        return null
    }

    private fun showAddNextDnsProfileDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvProto = TextView(this).apply {
            text = "Protocole :"
            setTextColor(pdmTextSecondary())
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvProto)

        val protocols = arrayOf("DoH (recommand\u00e9)", "DoQ", "DoT", "Standard (DNS classique)")
        val protoCodes = arrayOf(DnsType.DOH, DnsType.DOQ, DnsType.DOT, DnsType.DEFAULT)
        val protoColors = arrayOf(
            DnsColors.colorForType(DnsType.DOH),
            DnsColors.colorForType(DnsType.DOQ),
            DnsColors.colorForType(DnsType.DOT),
            DnsColors.colorForType(DnsType.DEFAULT)
        )
        var selectedProto = 0
        val rgProto = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (i in protocols.indices) {
            val rb = RadioButton(this).apply {
                text = protocols[i]
                setTextColor(pdmTextPrimary())
                buttonTintList = ColorStateList.valueOf(protoColors[i])
                id = View.generateViewId()
                textSize = 14f
                focusable = View.FOCUSABLE
                if (i == 0) isChecked = true
            }
            rgProto.addView(rb)
        }

        // Warning note for Standard DNS (visible only when Standard is selected)
        val warningText = "\u26a0\ufe0f DNS classique : liez votre IP sur https://my.nextdns.io pour activer votre profil personnalis\u00e9."
        val spannableWarning = SpannableString(warningText)
        val urlStart = warningText.indexOf("https://my.nextdns.io")
        val urlEnd = urlStart + "https://my.nextdns.io".length
        spannableWarning.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.nextdns.io")))
            }
            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = this@DnsProviderDetailActivity.pdmAccentAlt()
                ds.isUnderlineText = true
            }
        }, urlStart, urlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tvWarning = TextView(this).apply {
            text = spannableWarning
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(pdmWarning())
            textSize = 12f
            setPadding(0, 12, 0, 8)
            visibility = View.GONE
        }
        layout.addView(tvWarning)

        rgProto.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                if (group.getChildAt(i).id == checkedId) { selectedProto = i; break }
            }
            tvWarning.visibility = if (protoCodes[selectedProto] == DnsType.DEFAULT) View.VISIBLE else View.GONE
        }
        layout.addView(rgProto)
        // Move warning after radio group visually (remove and re-add)
        layout.removeView(tvWarning)
        layout.addView(tvWarning)

        val tvId = TextView(this).apply {
            text = "\nProfile ID NextDNS :"
            setTextColor(pdmTextSecondary())
            textSize = 13f
        }
        layout.addView(tvId)

        val etProfileId = EditText(this).apply {
            hint = "ex: abc123"
            setTextColor(pdmTextPrimary())
            setHintTextColor(pdmTextDisabled())
            textSize = 14f
            focusable = View.FOCUSABLE
        }
        layout.addView(etProfileId)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_nextdns_profile))
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val profileId = etProfileId.text.toString().trim()
                if (profileId.isEmpty()) {
                    Toast.makeText(this, getString(R.string.nextdns_id_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = protoCodes[selectedProto]
                val typeLabel = when (type) {
                    DnsType.DOQ -> "DoQ"
                    DnsType.DOH -> "DoH"
                    DnsType.DOT -> "DoT"
                    DnsType.DEFAULT -> "Standard"
                }
                val primary = when (type) {
                    DnsType.DOQ -> "quic://dns.nextdns.io/$profileId"
                    DnsType.DOH -> "https://dns.nextdns.io/$profileId"
                    DnsType.DOT -> "$profileId.dns.nextdns.io"
                    DnsType.DEFAULT -> "45.90.28.0"
                }
                val secondary = if (type == DnsType.DEFAULT) "45.90.30.0" else null
                val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                val savedIds = (customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()).toMutableSet()
                savedIds.add(profileId)
                customPrefs.edit().putStringSet("profile_ids", savedIds).apply()

                val newProfile = DnsProfile(
                    providerName = "NextDNS", name = "Profil $profileId ($typeLabel)", type = type,
                    primary = primary, secondary = secondary,
                    description = "Profil personnalis\u00e9 NextDNS ($typeLabel)",
                    isCustom = true, testUrl = "https://test.nextdns.io/"
                )
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("SELECTED_PROFILE_JSON", Gson().toJson(newProfile))
                })
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // -- Sealed class for list items --
    private sealed class ListItem {
        data class Header(val type: DnsType, val count: Int, var expanded: Boolean = true) : ListItem()
        data class Profile(val profile: DnsProfile) : ListItem()
    }

    private class ExpandableProfileAdapter(
        private val recyclerView: RecyclerView,
        private val grouped: List<Pair<DnsType, List<DnsProfile>>>,
        private val onProfileClick: (DnsProfile) -> Unit,
        private val onProfileLongClick: ((DnsProfile) -> Unit)? = null
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_PROFILE = 1
        }

        private val items = mutableListOf<ListItem>()
        // Track expanded state per DnsType
        private val expandedState = mutableMapOf<DnsType, Boolean>()

        init {
            // Only DoH expanded by default, others collapsed
            for ((type, _) in grouped) {
                expandedState[type] = (type == DnsType.DOH)
            }
            rebuildItems()
        }

        private fun rebuildItems() {
            items.clear()
            for ((type, profiles) in grouped) {
                val expanded = expandedState[type] ?: true
                items.add(ListItem.Header(type, profiles.size, expanded))
                if (expanded) {
                    for (p in profiles) {
                        items.add(ListItem.Profile(p))
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Header -> TYPE_HEADER
                is ListItem.Profile -> TYPE_PROFILE
            }
        }

        // -- Header ViewHolder (dedicated layout) --
        class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvArrow: TextView = view.findViewById(R.id.tvArrow)
            val tvCategoryBadge: TextView = view.findViewById(R.id.tvCategoryBadge)
            val tvCategoryMethod: TextView = view.findViewById(R.id.tvCategoryMethod)
            val tvCategoryCount: TextView = view.findViewById(R.id.tvCategoryCount)
        }

        // -- Profile ViewHolder (reuses same layout) --
        class ProfileVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
            val tvProfileName: TextView = view.findViewById(R.id.tvProfileName)
            val tvMethod: TextView = view.findViewById(R.id.tvMethod)
            val tvAddress: TextView = view.findViewById(R.id.tvAddress)
            val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_dns_category_header, parent, false)
                HeaderVH(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_dns_profile_detail, parent, false)
                ProfileVH(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> bindHeader(holder as HeaderVH, item)
                is ListItem.Profile -> bindProfile(holder as ProfileVH, item.profile)
            }
        }

        private fun bindHeader(holder: HeaderVH, header: ListItem.Header) {
            val (typeLabel, badgeColor) = getTypeInfo(header.type)

            holder.tvArrow.text = if (header.expanded) "\u25bc" else "\u25b6"
            holder.tvArrow.setTextColor(badgeColor)

            holder.tvCategoryBadge.text = typeLabel
            holder.tvCategoryBadge.setTextColor(Color.WHITE)
            val badgeBg = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 12f
            }
            holder.tvCategoryBadge.background = badgeBg

            val methodLabel = if (header.type == DnsType.DOT) "ADB" else "VPN"
            holder.tvCategoryMethod.text = methodLabel
            holder.tvCategoryMethod.setTextColor(badgeColor)

            holder.tvCategoryCount.text = "${header.count} profil${if (header.count > 1) "s" else ""}"
            holder.tvCategoryCount.setTextColor(badgeColor)

            holder.itemView.setOnClickListener { clickedView ->
                val type = header.type
                expandedState[type] = !(expandedState[type] ?: true)
                rebuildItems()
                // Temporarily prevent focus from jumping to back button during rebind
                // by keeping focus on the clicked view until the new layout is ready
                clickedView.requestFocus()
                notifyDataSetChanged()
                val headerPos = items.indexOfFirst { it is ListItem.Header && (it as ListItem.Header).type == type }
                if (headerPos >= 0) {
                    recyclerView.post {
                        recyclerView.scrollToPosition(headerPos)
                        // Use addOnLayoutChangeListener for reliable post-layout focus restore
                        recyclerView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                                recyclerView.removeOnLayoutChangeListener(this)
                                val vh = recyclerView.findViewHolderForAdapterPosition(headerPos)
                                vh?.itemView?.requestFocus()
                            }
                        })
                        recyclerView.requestLayout()
                    }
                }
            }
        }

        private fun bindProfile(holder: ProfileVH, profile: DnsProfile) {
            val (typeLabel, badgeColor) = getTypeInfo(profile.type)

            holder.tvTypeBadge.text = typeLabel
            holder.tvTypeBadge.setTextColor(Color.WHITE)
            val badgeBg = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 8f
            }
            holder.tvTypeBadge.background = badgeBg

            holder.tvProfileName.text = profile.name
            holder.tvProfileName.textSize = 15f
            holder.tvProfileName.setTextColor(Color.WHITE)

            holder.tvMethod.text = if (profile.type == DnsType.DOT) "ADB" else "VPN"
            holder.tvMethod.setTextColor(badgeColor)

            holder.tvAddress.text = profile.primary
            holder.tvAddress.visibility = View.VISIBLE

            holder.tvDescription.text = profile.description
            holder.tvDescription.visibility = if (profile.description.isNullOrBlank()) View.GONE else View.VISIBLE

            // Reset background for profile items
            holder.itemView.setBackgroundResource(R.drawable.focusable_item_background)

            holder.itemView.setOnClickListener {
                onProfileClick(profile)
            }

            holder.itemView.setOnLongClickListener {
                onProfileLongClick?.invoke(profile)
                onProfileLongClick != null
            }
        }

        private fun getTypeInfo(type: DnsType): Pair<String, Int> {
            return DnsColors.labelForType(type) to DnsColors.colorForType(type)
        }

        override fun getItemCount() = items.size
    }
}
