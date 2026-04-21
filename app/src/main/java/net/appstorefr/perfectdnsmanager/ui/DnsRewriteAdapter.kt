package net.appstorefr.perfectdnsmanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsRewriteRule

class DnsRewriteAdapter(
    private val rules: MutableList<DnsRewriteRule>,
    private val onRuleUpdated: (DnsRewriteRule) -> Unit,
    private val onRuleDeleted: (DnsRewriteRule) -> Unit
) : RecyclerView.Adapter<DnsRewriteAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dns_rewrite_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = rules[position]
        holder.bind(rule)
    }

    override fun getItemCount() = rules.size

    fun addRule(rule: DnsRewriteRule) {
        rules.add(0, rule)
        notifyItemInserted(0)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFromDomain: TextView = itemView.findViewById(R.id.tvFromDomain)
        private val tvToDomain: TextView = itemView.findViewById(R.id.tvToDomain)
        private val switchEnable: Switch = itemView.findViewById(R.id.switchEnableRule)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteRule)

        fun bind(rule: DnsRewriteRule) {
            tvFromDomain.text = rule.fromDomain
            tvToDomain.text = "→ ${rule.toDomain}"
            switchEnable.isChecked = rule.isEnabled

            switchEnable.setOnCheckedChangeListener { _, isChecked ->
                rule.isEnabled = isChecked
                onRuleUpdated(rule)
            }

            btnDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onRuleDeleted(rules[position])
                    rules.removeAt(position)
                    notifyItemRemoved(position)
                }
            }
        }
    }
}