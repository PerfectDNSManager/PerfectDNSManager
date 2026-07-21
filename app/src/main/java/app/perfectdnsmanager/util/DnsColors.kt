package app.perfectdnsmanager.util

import android.content.Context
import androidx.core.content.ContextCompat
import app.perfectdnsmanager.R
import app.perfectdnsmanager.data.DnsType

object DnsColors {

    /**
     * Résolution de couleur adaptée au thème (clair/sombre).
     * À privilégier partout où un [Context] est disponible : les couleurs sont
     * lues depuis res/values/colors.xml (versions foncées lisibles sur fond clair)
     * et res/values-night/colors.xml (versions claires lisibles sur fond sombre).
     */
    fun colorForType(context: Context, type: DnsType): Int =
        ContextCompat.getColor(context, colorResForType(type))

    fun colorResForType(type: DnsType): Int = when (type) {
        DnsType.DOH -> R.color.dns_type_doh
        DnsType.DOQ -> R.color.dns_type_doq
        DnsType.DOT -> R.color.dns_type_dot
        DnsType.DEFAULT -> R.color.dns_type_default
    }

    /**
     * Variante sans [Context] conservée pour les appelants qui n'ont pas d'accès
     * au thème. Les valeurs sont un compromis choisi pour rester lisibles À LA FOIS
     * sur fond clair (#F7F8FA) et sur fond sombre (#0E0F12) — contraste >= ~4:1.
     * Les anciennes valeurs (vert fluo 0xFF44FF44, or 0xFFFFB700) étaient
     * illisibles sur fond blanc (~1.4:1). Préférer l'overload avec Context.
     */
    fun colorForType(type: DnsType): Int = when (type) {
        DnsType.DOH -> 0xFF2E9E44.toInt()  // Vert (compromis clair/sombre)
        DnsType.DOQ -> 0xFF7B68EE.toInt()  // Bleu-violet (rare)
        DnsType.DOT -> 0xFFC08A00.toInt()  // Or foncé (lisible sur blanc)
        DnsType.DEFAULT -> 0xFF888888.toInt()  // Gris (commun)
    }

    fun labelForType(context: Context, type: DnsType): String = when (type) {
        DnsType.DEFAULT -> context.getString(R.string.dns_type_standard)
        else -> labelForType(type)
    }

    fun labelForType(type: DnsType): String = when (type) {
        DnsType.DOH -> "DoH"
        DnsType.DOQ -> "DoQ"
        DnsType.DOT -> "DoT"
        DnsType.DEFAULT -> "Standard"
    }
}
