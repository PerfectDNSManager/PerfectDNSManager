package app.perfectdnsmanager.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import app.perfectdnsmanager.service.AdbDnsManager

/**
 * Garde-fou « DNS privé (DoT) verrouillé au niveau système ».
 *
 * Le Private DNS strict d'Android (`private_dns_mode=hostname`) et notre VPN
 * DoH/DoQ ne peuvent PAS cohabiter :
 *   - le résolveur système reste en mode strict et continue de sortir en DoT
 *     sur le port 853 → le profil DoH choisi par l'utilisateur est ignoré ;
 *   - pire, si le serveur DoT n'est joignable QUE via le DNS d'origine (nom
 *     interne d'une box, DoT de l'opérateur, port 853 filtré), sa résolution
 *     échoue une fois le VPN en place et le mode strict n'a AUCUN repli →
 *     toute la résolution DNS meurt = « plus d'internet ».
 *
 * On sait normalement le couper nous-mêmes (WRITE_SECURE_SETTINGS ou ADB), mais
 * pas toujours : si le débogage ADB a été désactivé après coup — ou si le DNS
 * privé a été posé par le constructeur de la box — l'écriture échoue et le
 * réglage reste. Dans ce cas il faut REFUSER de démarrer le VPN et renvoyer
 * l'utilisateur vers l'écran système, au lieu de couper son internet.
 */
object PrivateDnsGuard {

    private const val T = "PrivateDnsGuard"

    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_SPECIFIER = "private_dns_specifier"

    /** Écrans Settings candidats, du plus précis au plus générique. */
    private val SETTINGS_ACTIONS = listOf(
        // Constante @hide d'AOSP (Settings.ACTION_PRIVATE_DNS_SETTINGS), présente
        // sur la plupart des ROMs mais absente de l'API publique → littéral.
        "android.settings.PRIVATE_DNS_SETTINGS",
        Settings.ACTION_WIRELESS_SETTINGS,
        Settings.ACTION_SETTINGS
    )

    fun mode(context: Context): String? =
        try { Settings.Global.getString(context.contentResolver, KEY_MODE) }
        catch (_: Exception) { null }

    /** true si le DNS privé est en mode « strict » (hostname imposé, sans repli). */
    fun isStrictActive(context: Context): Boolean = mode(context) == "hostname"

    fun specifier(context: Context): String =
        try { Settings.Global.getString(context.contentResolver, KEY_SPECIFIER) ?: "" }
        catch (_: Exception) { "" }

    /**
     * Tente de couper le DNS privé et **vérifie** le résultat.
     *
     * Bloquant (connexion ADB jusqu'à 20 s) → à appeler hors du main thread.
     *
     * On ne se fie pas au booléen de [AdbDnsManager.disablePrivateDns] seul : la
     * voie ADB écrit dans un shell sans lire le code retour, donc elle rend
     * `true` dès que les commandes sont parties. Seul l'état relu fait foi.
     *
     * @return true si, au retour, le DNS privé n'est plus en mode strict.
     */
    fun tryDisable(context: Context): Boolean {
        if (!isStrictActive(context)) return true

        val reported = try {
            AdbDnsManager(context).disablePrivateDns()
        } catch (e: Exception) {
            Log.w(T, "disablePrivateDns: ${e.javaClass.simpleName}")
            false
        }

        // Si les commandes sont parties, laisser le settings provider se mettre
        // à jour avant de conclure à l'échec (écriture shell asynchrone).
        var waited = 0L
        while (reported && waited < SETTLE_MAX_MS && isStrictActive(context)) {
            try { Thread.sleep(SETTLE_STEP_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
            waited += SETTLE_STEP_MS
        }

        val stillStrict = isStrictActive(context)
        Log.i(T, "tryDisable -> reported=$reported stillStrict=$stillStrict (waited=${waited}ms)")
        return !stillStrict
    }

    private const val SETTLE_STEP_MS = 250L
    private const val SETTLE_MAX_MS = 1500L

    /**
     * Ouvre l'écran système « DNS privé » (seul moyen de le couper quand on n'a
     * ni WRITE_SECURE_SETTINGS ni ADB).
     * @return true si un écran a pu être ouvert.
     */
    fun openSettings(context: Context): Boolean {
        for (action in SETTINGS_ACTIONS) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Action absente de cette ROM (fréquent sur les box TV) → suivante.
            }
        }
        Log.w(T, "Aucun écran Settings ouvrable")
        return false
    }

    /** Masque le label gauche du hostname (ID de compte NextDNS/ControlD). */
    fun redactHost(h: String): String {
        if (h.isBlank()) return h
        val dot = h.indexOf('.')
        return if (dot > 0) "***" + h.substring(dot) else "***"
    }
}
