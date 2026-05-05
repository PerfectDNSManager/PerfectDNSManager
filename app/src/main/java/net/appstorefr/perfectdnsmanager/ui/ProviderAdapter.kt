package net.appstorefr.perfectdnsmanager.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.util.DnsColors

class ProviderAdapter(
    private val grouped: Map<String, List<DnsProfile>>,
    private val onProviderLongClick: ((String, List<DnsProfile>) -> Unit)? = null,
    private val onProviderClick: (String, List<DnsProfile>) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

    private val providers = grouped.keys.toList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutProviderButton: View = view.findViewById(R.id.layoutProviderButton)
        val ivProviderIcon: ImageView = view.findViewById(R.id.ivProviderIcon)
        val tvProviderName: TextView = view.findViewById(R.id.tvName)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvBestProfile: TextView = view.findViewById(R.id.tvDescription)
        val layoutRatings: View = view.findViewById(R.id.layoutRatings)
        val tvSpeedLabel: TextView = view.findViewById(R.id.tvSpeedLabel)
        val tvSpeedStars: TextView = view.findViewById(R.id.tvSpeedStars)
        val tvPrivacyLabel: TextView = view.findViewById(R.id.tvPrivacyLabel)
        val tvPrivacyStars: TextView = view.findViewById(R.id.tvPrivacyStars)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        view.isFocusable = false
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val providerName = providers[position]
        val profiles = grouped[providerName] ?: return

        holder.tvProviderName.text = providerName

        val firstProfile = profiles.firstOrNull()

        // Provider icon
        val actualProviderName = providerName.replace(" \u2605", "")
        val iconRes = DnsProfile.getProviderIcon(actualProviderName)
        if (iconRes != 0) {
            holder.ivProviderIcon.setImageResource(iconRes)
            holder.ivProviderIcon.visibility = View.VISIBLE
        } else {
            holder.ivProviderIcon.visibility = View.GONE
        }

        // Green button: 2 lines — profile count + colored type labels
        val types = profiles.map { it.type }.distinct()
        val count = profiles.size
        val countLine = "$count profil${if (count > 1) "s" else ""}\n"
        val typeParts = types.map { DnsColors.labelForType(it) to DnsColors.colorForType(it) }
        val typesStr = typeParts.joinToString(" · ") { it.first }
        val fullText = "$countLine$typesStr"
        val spannable = SpannableString(fullText)
        var searchFrom = countLine.length
        for ((label, color) in typeParts) {
            val idx = fullText.indexOf(label, searchFrom)
            if (idx >= 0) {
                spannable.setSpan(ForegroundColorSpan(color), idx, idx + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                searchFrom = idx + label.length
            }
        }
        holder.tvType.text = spannable

        holder.tvBestProfile.visibility = View.GONE

        // Ratings: 2 lines (speed + privacy)
        val actualProvider = firstProfile?.providerName ?: providerName
        val rating = DnsProfile.providerRatings[actualProvider]
        if (rating != null && firstProfile?.isOperatorDns != true && firstProfile?.isCustom != true) {
            holder.layoutRatings.visibility = View.VISIBLE
            val ctx = holder.itemView.context
            holder.tvSpeedLabel.text = ctx.getString(R.string.speed_label) + ": "
            holder.tvSpeedStars.text = starsText(rating.speed)
            holder.tvPrivacyLabel.text = ctx.getString(R.string.privacy_label) + ": "
            holder.tvPrivacyStars.text = starsText(rating.privacy)
        } else {
            holder.layoutRatings.visibility = View.GONE
        }

        // Blue button (icon + name) click -> quick connect (best profile)
        holder.layoutProviderButton.setOnClickListener {
            onProviderClick(providerName, profiles)
        }

        // Green button (types) click -> open provider detail page
        holder.tvType.setOnClickListener {
            onProviderLongClick?.invoke(providerName, profiles)
        }
    }

    private fun starsText(count: Int): String {
        return "★".repeat(count) + "☆".repeat(5 - count)
    }

    override fun getItemCount() = providers.size
}
