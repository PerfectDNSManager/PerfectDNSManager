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
     * Client pour les mesures de débit.
     *
     * - Pool de connexions DÉDIÉ : avec le pool commun, les N flux parallèles
     *   d'un test multi-connexions vers un serveur HTTP/2 étaient multiplexés
     *   dans UNE seule connexion TCP — débit mesuré faussé à la baisse alors que
     *   la console annonçait « 4 connexions ». Chaque appel crée son pool ; ses
     *   connexions oisives se ferment d'elles-mêmes (le dispatcher reste partagé,
     *   ne jamais l'arrêter).
     * - `callTimeout(0)` : le plafond global de 60 s du client de base coupait un
     *   téléchargement de 5 Mo sous ~0,7 Mbps. Les délais connect/read suffisent.
     * - `retryOnConnectionFailure(false)` pour ne pas fausser le chronomètre.
     */
    fun forSpeedTest(timeoutSec: Long = 30): OkHttpClient = base.newBuilder()
        .connectionPool(okhttp3.ConnectionPool())
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Le client de base, pour les usages sans exigence particulière. */
    fun shared(): OkHttpClient = base
    /** Bounded text for small remote metadata, never APKs or speed-test payloads. */
    fun readText(body: okhttp3.ResponseBody?, maxBytes: Int = 1024 * 1024): String {
        if (body == null) return ""
        require(body.contentLength() <= maxBytes) { "Response too large" }
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= maxBytes) { "Response too large" }
                out.write(buffer, 0, count)
            }
        }
        return out.toString("UTF-8")
    }
}
