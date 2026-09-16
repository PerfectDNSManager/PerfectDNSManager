package app.perfectdnsmanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Relais invisible des PendingIntent de notification (non exporté).
 *
 * Auparavant cette classe héritait de MainActivity : chaque tap sur une
 * notification empilait une SECONDE instance complète de l'écran principal et
 * rejouait onCreate (vérification de mise à jour, synchronisation, dialogues en
 * double). Désormais elle transmet uniquement les actions connues à l'instance
 * existante de MainActivity, avec un jeton propre au processus qui prouve que
 * l'intent vient bien de l'app — une app tierce peut lancer MainActivity
 * (exportée, c'est le lanceur) mais ne peut pas connaître ce jeton.
 */
class NotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN)
        for (key in listOf(MainActivity.EXTRA_OPEN_PRIVATE_DNS_SETTINGS, "AUTO_RECONNECT")) {
            if (intent?.getBooleanExtra(key, false) == true) forward.putExtra(key, true)
        }
        startActivity(forward)
        finish()
    }
}
