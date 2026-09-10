package app.perfectdnsmanager.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP partagé par l'app.
 *
 * Il existait dix `OkHttpClient` construits séparément, chacun avec son propre
 * dispatcher (un pool de threads) et son propre pool de connexions — et
 * plusieurs n'étaient jamais fermés. Sur une box TV c'est de la mémoire et des
 * threads perdus en pure perte.
 *
 * [withTimeouts] dérive le client de base par `newBuilder()`, ce qui **partage**
 * le dispatcher et le pool de connexions tout en laissant chaque usage régler
 * ses délais. Corollaire important : il ne faut JAMAIS appeler
 * `dispatcher.executorService.shutdown()` sur un client dérivé — cela tuerait le
 * pool commun. Les connexions oisives sont recyclées automatiquement.
 */
object Http {

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    /** Client dérivé avec des délais spécifiques (pool et dispatcher partagés). */
    fun withTimeouts(
        connectSec: Long = 15,
        readSec: Long = 15,
        writeSec: Long = 15
    ): OkHttpClient = base.newBuilder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .writeTimeout(writeSec, TimeUnit.SECONDS)
        .build()

    /**
     * Client pour les mesures de débit : `retryOnConnectionFailure(false)` pour
     * ne pas fausser un chronomètre par une reprise silencieuse.
     */
    fun forSpeedTest(timeoutSec: Long = 30): OkHttpClient = base.newBuilder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Le client de base, pour les usages sans exigence particulière. */
    fun shared(): OkHttpClient = base
}
