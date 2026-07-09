package app.perfectdnsmanager.util

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import app.perfectdnsmanager.R

/**
 * Pickers que le listView interne d'AlertDialog.setSingleChoiceItems/setItems ne
 * gère pas correctement en D-pad sur Android TV : les CheckedTextView internes
 * n'ont pas de drawable state_focused, donc aucun indicateur visuel. On remplace
 * par un layout inflaté où chaque item est un RadioButton focusable avec
 * focusable_item_background (selector qui réagit à state_focused).
 */
object TvDialog {

    /**
     * Picker radio avec sélection initiale. Utilise pour remplacer
     * setSingleChoiceItems(items, currentIdx, ...).
     */
    fun showRadioPicker(
        context: Context,
        title: String,
        items: Array<String>,
        currentIdx: Int,
        onSelect: (Int) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 12))
            setBackgroundColor(ContextCompat.getColor(context, R.color.pdm_surface_elevated))
        }

        root.addView(TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.pdm_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 12))
        })

        val rg = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val buttons = items.mapIndexed { i, label ->
            RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.pdm_text_primary))
                buttonTintList = ContextCompat.getColorStateList(context, R.color.pdm_accent)
                background = ContextCompat.getDrawable(context, R.drawable.focusable_item_background)
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                isFocusable = true
                isFocusableInTouchMode = false
                isChecked = i == currentIdx
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 48)
                )
            }
        }
        buttons.forEach { rg.addView(it) }
        root.addView(rg)

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()

        rg.setOnCheckedChangeListener { _, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) {
                dialog.dismiss()
                onSelect(idx)
            }
        }

        dialog.setOnShowListener {
            val target = buttons.getOrNull(currentIdx) ?: buttons.firstOrNull()
            target?.post {
                target.requestFocus()
            }
        }

        dialog.show()
    }

    /**
     * Picker simple (menu d'actions, sans sélection initiale). Utilise pour
     * remplacer setItems(items, ...).
     */
    fun showMenuPicker(
        context: Context,
        title: String,
        items: Array<String>,
        onSelect: (Int) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 12))
            setBackgroundColor(ContextCompat.getColor(context, R.color.pdm_surface_elevated))
        }

        root.addView(TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.pdm_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(context, 12))
        })

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val buttons = items.mapIndexed { i, label ->
            Button(context).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.pdm_text_primary))
                background = ContextCompat.getDrawable(context, R.drawable.focusable_item_background)
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                isFocusable = true
                isFocusableInTouchMode = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setAllCaps(false)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 48)
                ).apply { topMargin = if (i == 0) 0 else dp(context, 4) }
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(i)
                }
            }
        }
        buttons.forEach { root.addView(it) }

        dialog.setOnShowListener {
            buttons.firstOrNull()?.post { buttons.first().requestFocus() }
        }

        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
