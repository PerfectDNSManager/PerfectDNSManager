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

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Ookla server data class.
 */
data class OoklaServer(
    val id: Int,
    val sponsor: String?,
    val name: String?,
    val host: String,
    val url: String,
    val lat: String?,
    val lon: String?,
    val country: String?,
    val cc: String?
) {
    val displayName: String get() = sponsor ?: name ?: host
    override fun toString(): String = displayName
}

/**
 * Enum for speed test backends.
 */
enum class SpeedBackend(val label: String) {
    CLOUDFLARE("Cloudflare"),
    OOKLA("Ookla"),
    NETFLIX("Fast.com")
}

/**
 * Multi-backend speed test activity.
 *
 * Supports 5 backends: LibreSpeed, Cloudflare, Ookla, Netflix (Fast.com), nPerf.
 * Uses OkHttp for HTTP, fully programmatic UI (no XML layout).
 */
class InternetSpeedtestActivity : AppCompatActivity() {

    // Theme-aware colors (instance-level, resolves via Context)
    private val COLOR_BG: Int get() = pdmBackground()
    private val COLOR_WHITE: Int get() = pdmTextPrimary()
    private val COLOR_LIGHT_GREY: Int get() = pdmTextSecondary()
    private val COLOR_GREEN: Int get() = pdmAccent()
    private val COLOR_RED: Int get() = pdmDanger()
    private val COLOR_DIM: Int get() = pdmTextDisabled()
    private val COLOR_BG_CARD: Int get() = pdmSurfaceElevated()
    private val COLOR_CHIP_INACTIVE: Int get() = pdmSurface()

    companion object {
        private const val TAG = "InternetSpeedtest"

        // Data-viz palette (download/upload chart — contraste volontaire)
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_VIOLET = 0xFFBB86FC.toInt()

        // Cloudflare endpoints
        private const val CF_BASE = "https://speed.cloudflare.com"
        private const val CF_DL_URL = "$CF_BASE/__down?bytes="
        private const val CF_UL_URL = "$CF_BASE/__up"
        private const val CF_DL_BYTES = 25_000_000L
        private const val CF_UL_PAYLOAD_SIZE = 10 * 1024 * 1024 // 10 MB
        private const val CF_PING_COUNT = 10
        private const val CF_DL_CONNECTIONS = 4
        private const val CF_DL_DURATION_SEC = 10
        private const val CF_UL_CONNECTIONS = 3
        private const val CF_UL_DURATION_SEC = 10

        // Ookla endpoints
        private const val OOKLA_SERVER_LIST_URL =
            "https://www.speedtest.net/api/js/servers?engine=js&limit=10&search="
        private const val OOKLA_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val OOKLA_DL_CONNECTIONS = 4
        private const val OOKLA_DL_DURATION_SEC = 10
        private const val OOKLA_UL_PAYLOAD_SIZE = 10 * 1024 * 1024
        private const val OOKLA_UL_CONNECTIONS = 3
        private const val OOKLA_UL_DURATION_SEC = 10
        private const val OOKLA_PING_COUNT = 10
        private const val OOKLA_LATENCY_CANDIDATES = 5

        // Netflix (Fast.com) endpoints
        private const val NETFLIX_API_URL =
            "https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5"
        private const val NETFLIX_DL_DURATION_SEC = 10
        private const val NETFLIX_PING_COUNT = 10

    }

    // ── UI widgets ───────────────────────────────────────────────────────────
    private lateinit var backendSelectorRow: HorizontalScrollView
    private lateinit var backendButtonsLayout: LinearLayout
    private lateinit var serverPickerRow: LinearLayout
    private lateinit var btnServerPicker: Button
    private lateinit var btnStartStop: Button
    private lateinit var resultsCard: LinearLayout
    private lateinit var tvPing: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvClientIp: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var pbUpload: ProgressBar
    private lateinit var tvConsoleLabel: TextView
    private lateinit var tvConsole: TextView
    private lateinit var scrollConsole: ScrollView
    private lateinit var mainColumn: LinearLayout
    private lateinit var rootScroll: ScrollView

    // ── State ────────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var testThread: Thread? = null
    private val ooklaServers = mutableListOf<OoklaServer>()
    private var selectedOoklaServer: OoklaServer? = null
    private var currentBackend: SpeedBackend = SpeedBackend.CLOUDFLARE
    private val backendButtons = mutableMapOf<SpeedBackend, Button>()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        switchBackend(SpeedBackend.CLOUDFLARE)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled.set(true)
        testThread?.interrupt()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION (fully programmatic)
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildUI(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
        }

        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // ── Header ───────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(6) }
        }

        val btnBack = Button(this).apply {
            text = getString(R.string.back_arrow)
            setTextColor(COLOR_WHITE)
            textSize = 18f
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        val tvTitle = TextView(this).apply {
            text = "Testeur de d\u00e9bit avanc\u00e9"
            setTextColor(COLOR_WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }
        header.addView(tvTitle)
        mainColumn.addView(header)

        // ── Backend selector (chips row) ─────────────────────────────────
        backendSelectorRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(6) }
        }

        backendButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(matchParent, wrapContent)
        }

        for (backend in SpeedBackend.entries) {
            val chipBtn = Button(this).apply {
                text = backend.label
                setTextColor(COLOR_WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                isFocusable = true
                foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
                background = chipBackground(dp(20), COLOR_CHIP_INACTIVE)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    if (!running.get()) {
                        switchBackend(backend)
                    }
                }
            }
            backendButtons[backend] = chipBtn
            backendButtonsLayout.addView(chipBtn)
        }

        backendSelectorRow.addView(backendButtonsLayout)
        mainColumn.addView(backendSelectorRow)

        // ── Server selector ──────────────────────────────────────────────
        serverPickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(6) }
        }

        btnServerPicker = Button(this).apply {
            text = "\uD83C\uDF10  Serveur : chargement..."
            setTextColor(COLOR_WHITE)
            textSize = 14f
            background = chipBackground(dp(12), COLOR_BG_CARD)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = lp(wrapContent, wrapContent)
            gravity = Gravity.CENTER
            setOnClickListener { showServerPickerDialog() }
        }
        serverPickerRow.addView(btnServerPicker)
        mainColumn.addView(serverPickerRow)

        // ── Start / Stop button ──────────────────────────────────────────
        btnStartStop = Button(this).apply {
            text = "D\u00e9marrer le test"
            setTextColor(COLOR_WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            background = greenPill(dp(8))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(8) }
            setOnClickListener { toggleTest() }
        }
        mainColumn.addView(btnStartStop)

        // ── Results card ─────────────────────────────────────────────────
        resultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(COLOR_BG_CARD); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(8) }
        }

        // Ping / Jitter
        val pingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(8) }
        }
        tvPing = metricBlock(pingRow, "PING", "-- ms", COLOR_CYAN, dp(0))
        tvJitter = metricBlock(pingRow, "JITTER", "-- ms", COLOR_CYAN, dp(0))
        resultsCard.addView(pingRow)

        // Download
        val dlSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }
        val dlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        dlRow.addView(label("\u2B07 DOWNLOAD", COLOR_GREEN, 13f, dp(0)))
        tvDownload = TextView(this).apply {
            text = "-- Mbps"; setTextColor(COLOR_WHITE); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END
        }
        dlRow.addView(tvDownload)
        dlSection.addView(dlRow)
        pbDownload = horizontalBar(COLOR_GREEN, dp(6))
        dlSection.addView(pbDownload)
        resultsCard.addView(dlSection)

        // Upload
        val ulSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }
        val ulRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        ulRow.addView(label("\u2B06 UPLOAD", COLOR_VIOLET, 13f, dp(0)))
        tvUpload = TextView(this).apply {
            text = "-- Mbps"; setTextColor(COLOR_WHITE); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END
        }
        ulRow.addView(tvUpload)
        ulSection.addView(ulRow)
        pbUpload = horizontalBar(COLOR_VIOLET, dp(6))
        ulSection.addView(pbUpload)
        resultsCard.addView(ulSection)

        // Client IP
        tvClientIp = TextView(this).apply {
            text = "IP : --"; setTextColor(COLOR_LIGHT_GREY); textSize = 13f
            gravity = Gravity.CENTER
        }
        resultsCard.addView(tvClientIp)
        mainColumn.addView(resultsCard)

        // ── Console ──────────────────────────────────────────────────────
        tvConsoleLabel = TextView(this).apply {
            text = "Journal du test"; setTextColor(COLOR_DIM); textSize = 12f
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        mainColumn.addView(tvConsoleLabel)

        scrollConsole = ScrollView(this).apply {
            layoutParams = lp(matchParent, dp(120))
            background = GradientDrawable().apply {
                setColor(0xFF0D0D1A.toInt()); cornerRadius = dp(8).toFloat()
            }
        }
        tvConsole = TextView(this).apply {
            setTextColor(COLOR_LIGHT_GREY); textSize = 11f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "En attente...\n"
        }
        scrollConsole.addView(tvConsole)
        mainColumn.addView(scrollConsole)

        rootScroll.addView(mainColumn)
        // Focus on Start button, scroll to top
        rootScroll.post {
            rootScroll.scrollTo(0, 0)
            btnStartStop.requestFocus()
        }
        return rootScroll
    }

    /* ── tiny layout helpers ────────────────────────────────────────────── */

    private val matchParent get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent get() = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun greenPill(r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(COLOR_GREEN); cornerRadius = r.toFloat() }

    private fun redPill(r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(COLOR_RED); cornerRadius = r.toFloat() }

    private fun chipBackground(r: Int, color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }

    /** Create a "label + big value" column inside [parent] and return the value TextView. */
    private fun metricBlock(
        parent: LinearLayout, title: String, initial: String, color: Int, margin: Int
    ): TextView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }
        col.addView(TextView(this).apply {
            text = title; setTextColor(COLOR_DIM); textSize = 12f; gravity = Gravity.CENTER
        })
        val tv = TextView(this).apply {
            text = initial; setTextColor(color); textSize = 22f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        col.addView(tv)
        parent.addView(col)
        return tv
    }

    private fun label(text: String, color: Int, size: Float, margin: Int): TextView =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = size
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }

    @Suppress("DEPRECATION")
    private fun horizontalBar(color: Int, height: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            layoutParams = lp(matchParent, height)
            progressDrawable.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }

    // ═════════════════════════════════════════════════════════════════════════
    //  BACKEND SWITCHING
    // ═════════════════════════════════════════════════════════════════════════

    private fun switchBackend(backend: SpeedBackend) {
        currentBackend = backend

        // Update chip colors
        for ((b, btn) in backendButtons) {
            val color = if (b == backend) COLOR_CYAN else COLOR_CHIP_INACTIVE
            btn.background = chipBackground(dp(20), color)
            btn.setTextColor(if (b == backend) 0xFF000000.toInt() else COLOR_WHITE)
        }

        when (backend) {
            SpeedBackend.CLOUDFLARE -> {
                serverPickerRow.visibility = View.GONE
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvConsole.text = "En attente...\n"
                logConsole("Backend : Cloudflare (speed.cloudflare.com)")
            }
            SpeedBackend.OOKLA -> {
                serverPickerRow.visibility = View.VISIBLE
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvConsole.text = "En attente...\n"
                loadOoklaServerList()
            }
            SpeedBackend.NETFLIX -> {
                serverPickerRow.visibility = View.GONE
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvUpload.text = "N/A"
                tvConsole.text = "En attente...\n"
                logConsole("Backend : Netflix / Fast.com (upload non support\u00e9)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SERVER LISTS
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadOoklaServerList() {
        btnServerPicker.text = "\uD83C\uDF10  Serveur : chargement..."
        logConsole("Chargement de la liste des serveurs Ookla...")
        Thread {
            val fetched = mutableListOf<OoklaServer>()
            try {
                val client = plainClient(10)
                val req = Request.Builder()
                    .url(OOKLA_SERVER_LIST_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", OOKLA_USER_AGENT)
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = resp.body?.string() ?: "[]"
                    resp.close()
                    val type = object : TypeToken<List<OoklaServer>>() {}.type
                    fetched.addAll(Gson().fromJson<List<OoklaServer>>(json, type))
                } else {
                    val code = resp.code
                    resp.close()
                    ui { logConsole("Erreur serveur Ookla : $code") }
                }
                shutdown(client)
            } catch (e: Exception) {
                Log.w(TAG, "Ookla server list fetch failed", e)
                ui { logConsole("Erreur chargement Ookla : ${e.message}") }
            }

            // Ping top candidates to find fastest
            if (fetched.isNotEmpty()) {
                ui { logConsole("Test de latence des serveurs Ookla...") }
                val latencies = mutableListOf<Pair<OoklaServer, Double>>()
                val candidates = fetched.take(OOKLA_LATENCY_CANDIDATES)
                for (server in candidates) {
                    if (cancelled.get()) break
                    try {
                        val client = plainClient(5)
                        val baseUrl = ooklaBaseUrl(server.url)
                        val t0 = System.nanoTime()
                        val pingReq = Request.Builder()
                            .url("${baseUrl}latency.txt?r=${System.nanoTime()}")
                            .header("User-Agent", OOKLA_USER_AGENT)
                            .build()
                        val pingResp = client.newCall(pingReq).execute()
                        val ms = (System.nanoTime() - t0) / 1_000_000.0
                        pingResp.close()
                        shutdown(client)
                        if (pingResp.isSuccessful || pingResp.code in 200..499) {
                            latencies.add(server to ms)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ookla ping failed for ${server.displayName}", e)
                    }
                }
                // Sort by latency, best first
                latencies.sortBy { it.second }
                if (latencies.isNotEmpty()) {
                    fetched.clear()
                    fetched.addAll(latencies.map { it.first })
                    // Add remaining servers that weren't pinged
                    val pingedIds = latencies.map { it.first.id }.toSet()
                    fetched.addAll(candidates.filter { it.id !in pingedIds })
                }
            }

            ui {
                ooklaServers.clear()
                ooklaServers.addAll(fetched)

                if (ooklaServers.isEmpty()) {
                    logConsole("Aucun serveur Ookla disponible.")
                    btnServerPicker.text = "\uD83C\uDF10  Serveur : aucun"
                    return@ui
                }
                logConsole("${ooklaServers.size} serveur(s) Ookla charg\u00e9(s).")

                selectedOoklaServer = ooklaServers[0]
                btnServerPicker.text = "\uD83C\uDF10  ${ooklaServers[0].displayName}"
            }
        }.start()
    }

    private fun showServerPickerDialog() {
        when (currentBackend) {
            SpeedBackend.OOKLA -> showOoklaPickerDialog()
            else -> { /* no server picker for other backends */ }
        }
    }

    private fun showOoklaPickerDialog() {
        if (ooklaServers.isEmpty()) {
            logConsole("Aucun serveur Ookla disponible.")
            return
        }
        val names = ooklaServers.map { it.displayName }.toTypedArray()
        val currentIndex = ooklaServers.indexOf(selectedOoklaServer).coerceAtLeast(0)
        net.appstorefr.perfectdnsmanager.util.TvDialog.showRadioPicker(
            this,
            "Choisir un serveur Ookla",
            names,
            currentIndex
        ) { which ->
            selectedOoklaServer = ooklaServers[which]
            btnServerPicker.text = "\uD83C\uDF10  ${ooklaServers[which].displayName}"
            logConsole("Serveur : ${ooklaServers[which].displayName}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEST CONTROL
    // ═════════════════════════════════════════════════════════════════════════

    private fun toggleTest() {
        if (running.get()) stopTest() else startTest()
    }

    private fun startTest() {
        running.set(true); cancelled.set(false)
        updateButton(true); resetResults()

        when (currentBackend) {
            SpeedBackend.CLOUDFLARE -> startCloudflareTest()
            SpeedBackend.OOKLA -> startOoklaTest()
            SpeedBackend.NETFLIX -> startNetflixTest()
            else -> { /* nPerf handled by WebView */ }
        }
    }

    private fun stopTest() {
        logConsole("Arr\u00eat du test...")
        cancelled.set(true)
        testThread?.interrupt()
    }

    private fun updateButton(isRunning: Boolean) {
        val dp8 = dp(8)
        btnStartStop.text = if (isRunning) "Arr\u00eater" else "D\u00e9marrer le test"
        btnStartStop.background = if (isRunning) redPill(dp8) else greenPill(dp8)
        btnStartStop.foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
    }

    private fun resetResults() {
        tvPing.text = "-- ms"; tvJitter.text = "-- ms"
        tvDownload.text = "-- Mbps"
        tvUpload.text = if (currentBackend == SpeedBackend.NETFLIX) "N/A" else "-- Mbps"
        tvClientIp.text = "IP : --"
        pbDownload.progress = 0; pbUpload.progress = 0
        tvConsole.text = ""
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CLOUDFLARE TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startCloudflareTest() {
        logConsole("=== D\u00e9but du test Cloudflare ===")

        testThread = Thread {
            try {
                if (!cancelled.get()) runCloudflarePing()
                if (!cancelled.get()) runCloudflareDownload()
                if (!cancelled.get()) runCloudflareUpload()
                if (!cancelled.get()) ui { logConsole("=== Test termin\u00e9 ===") }
            } catch (_: InterruptedException) {
                ui { logConsole("Test interrompu.") }
            } catch (e: Exception) {
                Log.e(TAG, "Cloudflare test error", e)
                ui { logConsole("Erreur : ${e.message}") }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    private fun runCloudflarePing() {
        ui { logConsole("\n--- Ping Cloudflare ($CF_PING_COUNT requ\u00eates) ---") }
        val client = plainClient(5)
        val pings = mutableListOf<Double>()

        for (i in 1..CF_PING_COUNT) {
            if (cancelled.get()) break
            try {
                val req = Request.Builder()
                    .url("${CF_DL_URL}0&r=${System.nanoTime()}")
                    .head()
                    .header("Cache-Control", "no-cache")
                    .build()
                val t0 = System.nanoTime()
                val resp = client.newCall(req).execute()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                resp.body?.close(); resp.close()
                pings.add(ms)
                ui { logConsole("  #$i : ${"%.1f".format(ms)} ms") }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole("  #$i : \u00e9chec") }
            }
        }
        shutdown(client)

        if (pings.isNotEmpty() && !cancelled.get()) {
            val avg = pings.average()
            val jitter = if (pings.size > 1)
                pings.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ui {
                tvPing.text = "${"%.1f".format(avg)} ms"
                tvJitter.text = "${"%.1f".format(jitter)} ms"
                logConsole("  Moy : ${"%.1f".format(avg)} ms | Jitter : ${"%.1f".format(jitter)} ms")
            }
        }
    }

    private fun runCloudflareDownload() {
        ui { logConsole("\n--- Download Cloudflare ($CF_DL_CONNECTIONS conn., ${CF_DL_DURATION_SEC}s) ---") }

        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + CF_DL_DURATION_SEC * 1_000_000_000L

        val workers = (0 until CF_DL_CONNECTIONS).map {
            Thread {
                val c = plainClient(CF_DL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val req = Request.Builder()
                            .url("${CF_DL_URL}${CF_DL_BYTES}&r=${System.nanoTime()}")
                            .header("Cache-Control", "no-store, no-cache")
                            .build()
                        val resp = c.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { stream ->
                                val buf = ByteArray(65536)
                                while (!cancelled.get() && System.nanoTime() < deadline) {
                                    val n = stream.read(buf)
                                    if (n == -1) break
                                    totalBytes.addAndGet(n.toLong())
                                }
                            }
                        }
                        resp.close()
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, CF_DL_DURATION_SEC) { mbps, progress ->
            tvDownload.text = "${"%.2f".format(mbps)} Mbps"
            pbDownload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvDownload.text = "${"%.2f".format(mbps)} Mbps"; pbDownload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    private fun runCloudflareUpload() {
        ui { logConsole("\n--- Upload Cloudflare ($CF_UL_CONNECTIONS conn., ${CF_UL_DURATION_SEC}s) ---") }

        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + CF_UL_DURATION_SEC * 1_000_000_000L
        val payload = ByteArray(CF_UL_PAYLOAD_SIZE) { (it % 256).toByte() }

        val workers = (0 until CF_UL_CONNECTIONS).map {
            Thread {
                val c = plainClient(CF_UL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val body = object : okhttp3.RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaType()
                            override fun contentLength() = payload.size.toLong()
                            override fun writeTo(sink: okio.BufferedSink) {
                                var off = 0; val chunk = 65536
                                while (off < payload.size && !cancelled.get() && System.nanoTime() < deadline) {
                                    val len = minOf(chunk, payload.size - off)
                                    sink.write(payload, off, len)
                                    sink.flush()
                                    totalBytes.addAndGet(len.toLong())
                                    off += len
                                }
                            }
                        }
                        val req = Request.Builder()
                            .url("$CF_UL_URL?r=${System.nanoTime()}")
                            .post(body)
                            .build()
                        try {
                            c.newCall(req).execute().close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, CF_UL_DURATION_SEC) { mbps, progress ->
            tvUpload.text = "${"%.2f".format(mbps)} Mbps"
            pbUpload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvUpload.text = "${"%.2f".format(mbps)} Mbps"; pbUpload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  OOKLA TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startOoklaTest() {
        val server = selectedOoklaServer ?: run {
            logConsole("Aucun serveur Ookla s\u00e9lectionn\u00e9."); running.set(false); updateButton(false); return
        }

        logConsole("=== D\u00e9but du test Ookla ===")
        logConsole("Serveur : ${server.displayName}")
        logConsole("URL : ${server.url}")

        testThread = Thread {
            try {
                if (!cancelled.get()) runOoklaPing(server)
                if (!cancelled.get()) runOoklaDownload(server)
                if (!cancelled.get()) runOoklaUpload(server)
                if (!cancelled.get()) ui { logConsole("=== Test termin\u00e9 ===") }
            } catch (_: InterruptedException) {
                ui { logConsole("Test interrompu.") }
            } catch (e: Exception) {
                Log.e(TAG, "Ookla test error", e)
                ui { logConsole("Erreur : ${e.message}") }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    private fun runOoklaPing(server: OoklaServer) {
        ui { logConsole("\n--- Ping Ookla ($OOKLA_PING_COUNT requ\u00eates) ---") }
        val client = plainClient(5)
        val baseUrl = ooklaBaseUrl(server.url)
        val pings = mutableListOf<Double>()

        for (i in 1..OOKLA_PING_COUNT) {
            if (cancelled.get()) break
            try {
                val req = Request.Builder()
                    .url("${baseUrl}latency.txt?r=${System.nanoTime()}")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", OOKLA_USER_AGENT)
                    .build()
                val t0 = System.nanoTime()
                val resp = client.newCall(req).execute()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                resp.body?.close(); resp.close()
                pings.add(ms)
                ui { logConsole("  #$i : ${"%.1f".format(ms)} ms") }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole("  #$i : \u00e9chec") }
            }
        }
        shutdown(client)

        if (pings.isNotEmpty() && !cancelled.get()) {
            val avg = pings.average()
            val jitter = if (pings.size > 1)
                pings.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ui {
                tvPing.text = "${"%.1f".format(avg)} ms"
                tvJitter.text = "${"%.1f".format(jitter)} ms"
                logConsole("  Moy : ${"%.1f".format(avg)} ms | Jitter : ${"%.1f".format(jitter)} ms")
            }
        }
    }

    private fun runOoklaDownload(server: OoklaServer) {
        ui { logConsole("\n--- Download Ookla ($OOKLA_DL_CONNECTIONS conn., ${OOKLA_DL_DURATION_SEC}s) ---") }

        val baseUrl = ooklaBaseUrl(server.url)
        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + OOKLA_DL_DURATION_SEC * 1_000_000_000L

        val workers = (0 until OOKLA_DL_CONNECTIONS).map {
            Thread {
                val c = plainClient(OOKLA_DL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val req = Request.Builder()
                            .url("${baseUrl}random4000x4000.jpg?r=${System.nanoTime()}")
                            .header("Cache-Control", "no-store, no-cache")
                            .header("User-Agent", OOKLA_USER_AGENT)
                            .build()
                        val resp = c.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { stream ->
                                val buf = ByteArray(65536)
                                while (!cancelled.get() && System.nanoTime() < deadline) {
                                    val n = stream.read(buf)
                                    if (n == -1) break
                                    totalBytes.addAndGet(n.toLong())
                                }
                            }
                        }
                        resp.close()
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, OOKLA_DL_DURATION_SEC) { mbps, progress ->
            tvDownload.text = "${"%.2f".format(mbps)} Mbps"
            pbDownload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvDownload.text = "${"%.2f".format(mbps)} Mbps"; pbDownload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    private fun runOoklaUpload(server: OoklaServer) {
        ui { logConsole("\n--- Upload Ookla ($OOKLA_UL_CONNECTIONS conn., ${OOKLA_UL_DURATION_SEC}s) ---") }

        val ulUrl = server.url  // server.url is already the upload.php endpoint
        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + OOKLA_UL_DURATION_SEC * 1_000_000_000L
        val payload = ByteArray(OOKLA_UL_PAYLOAD_SIZE) { (it % 256).toByte() }

        val workers = (0 until OOKLA_UL_CONNECTIONS).map {
            Thread {
                val c = plainClient(OOKLA_UL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val body = object : okhttp3.RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaType()
                            override fun contentLength() = payload.size.toLong()
                            override fun writeTo(sink: okio.BufferedSink) {
                                var off = 0; val chunk = 65536
                                while (off < payload.size && !cancelled.get() && System.nanoTime() < deadline) {
                                    val len = minOf(chunk, payload.size - off)
                                    sink.write(payload, off, len)
                                    sink.flush()
                                    totalBytes.addAndGet(len.toLong())
                                    off += len
                                }
                            }
                        }
                        val req = Request.Builder()
                            .url("$ulUrl?r=${System.nanoTime()}")
                            .post(body)
                            .header("User-Agent", OOKLA_USER_AGENT)
                            .build()
                        try {
                            c.newCall(req).execute().close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, OOKLA_UL_DURATION_SEC) { mbps, progress ->
            tvUpload.text = "${"%.2f".format(mbps)} Mbps"
            pbUpload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvUpload.text = "${"%.2f".format(mbps)} Mbps"; pbUpload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETFLIX (FAST.COM) TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startNetflixTest() {
        logConsole("=== D\u00e9but du test Netflix / Fast.com ===")
        logConsole("Upload non support\u00e9 (N/A)")

        testThread = Thread {
            try {
                // Fetch test URLs from Fast.com API
                val testUrls = fetchNetflixTestUrls()
                if (testUrls.isEmpty()) {
                    ui { logConsole("Erreur : impossible d'obtenir les URLs de test Fast.com") }
                    return@Thread
                }
                ui { logConsole("${testUrls.size} URL(s) de test obtenue(s).") }

                if (!cancelled.get()) runNetflixPing(testUrls)
                if (!cancelled.get()) runNetflixDownload(testUrls)
                if (!cancelled.get()) ui {
                    tvUpload.text = "N/A"
                    pbUpload.progress = 1000
                    logConsole("\n--- Upload : non support\u00e9 (N/A) ---")
                    logConsole("=== Test termin\u00e9 ===")
                }
            } catch (_: InterruptedException) {
                ui { logConsole("Test interrompu.") }
            } catch (e: Exception) {
                Log.e(TAG, "Netflix test error", e)
                ui { logConsole("Erreur : ${e.message}") }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    /**
     * Fetches download test URLs from the Fast.com API.
     * Returns a list of URL strings.
     */
    private fun fetchNetflixTestUrls(): List<String> {
        val client = plainClient(10)
        return try {
            val req = Request.Builder()
                .url(NETFLIX_API_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = resp.body?.string() ?: "{}"
                resp.close()
                // Parse the response: { "targets": [{"url": "..."}, ...], ... }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val map = Gson().fromJson(json, Map::class.java) as Map<String, Any>
                    val targets = map["targets"] as? List<*> ?: emptyList<Any>()
                    targets.mapNotNull { target ->
                        (target as? Map<*, *>)?.get("url") as? String
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Netflix JSON parse error", e)
                    emptyList()
                }
            } else {
                val code = resp.code
                resp.close()
                ui { logConsole("Erreur Fast.com API : $code") }
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Netflix API fetch failed", e)
            ui { logConsole("Erreur Fast.com : ${e.message}") }
            emptyList()
        } finally {
            shutdown(client)
        }
    }

    private fun runNetflixPing(testUrls: List<String>) {
        ui { logConsole("\n--- Ping Fast.com ($NETFLIX_PING_COUNT requ\u00eates) ---") }
        val client = plainClient(5)
        val pings = mutableListOf<Double>()
        val pingUrl = testUrls.first()

        for (i in 1..NETFLIX_PING_COUNT) {
            if (cancelled.get()) break
            try {
                val req = Request.Builder()
                    .url(pingUrl)
                    .head()
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val t0 = System.nanoTime()
                val resp = client.newCall(req).execute()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                resp.body?.close(); resp.close()
                pings.add(ms)
                ui { logConsole("  #$i : ${"%.1f".format(ms)} ms") }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole("  #$i : \u00e9chec") }
            }
        }
        shutdown(client)

        if (pings.isNotEmpty() && !cancelled.get()) {
            val avg = pings.average()
            val jitter = if (pings.size > 1)
                pings.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ui {
                tvPing.text = "${"%.1f".format(avg)} ms"
                tvJitter.text = "${"%.1f".format(jitter)} ms"
                logConsole("  Moy : ${"%.1f".format(avg)} ms | Jitter : ${"%.1f".format(jitter)} ms")
            }
        }
    }

    private fun runNetflixDownload(testUrls: List<String>) {
        val connections = testUrls.size
        ui { logConsole("\n--- Download Fast.com ($connections conn., ${NETFLIX_DL_DURATION_SEC}s) ---") }

        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + NETFLIX_DL_DURATION_SEC * 1_000_000_000L

        val workers = testUrls.map { url ->
            Thread {
                val c = plainClient(NETFLIX_DL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val req = Request.Builder()
                            .url(url)
                            .header("Cache-Control", "no-store, no-cache")
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        val resp = c.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { stream ->
                                val buf = ByteArray(65536)
                                while (!cancelled.get() && System.nanoTime() < deadline) {
                                    val n = stream.read(buf)
                                    if (n == -1) break
                                    totalBytes.addAndGet(n.toLong())
                                }
                            }
                        }
                        resp.close()
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, NETFLIX_DL_DURATION_SEC) { mbps, progress ->
            tvDownload.text = "${"%.2f".format(mbps)} Mbps"
            pbDownload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvDownload.text = "${"%.2f".format(mbps)} Mbps"; pbDownload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Polls [totalBytes] every 500 ms until [deadline] and posts UI updates
     * through [onTick] (called on UI thread with current Mbps and progress 0-1000).
     */
    private fun monitorProgress(
        totalBytes: AtomicLong,
        t0: Long,
        deadline: Long,
        durationSec: Int,
        onTick: (mbps: Double, progress: Int) -> Unit
    ) {
        while (!cancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(500)
            val now = System.nanoTime()
            val elapsed = (now - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            val pct = ((elapsed / durationSec) * 1000).toInt().coerceAtMost(1000)
            ui { onTick(mbps, pct) }
        }
    }

    /** Post to UI thread. */
    private fun ui(block: () -> Unit) = runOnUiThread(block)

    /** Append a line to the console TextView (must be called on UI thread). */
    private fun logConsole(msg: String) {
        if (Thread.currentThread() != mainLooper.thread) {
            ui { logConsole(msg) }; return
        }
        tvConsole.append(msg + "\n")
        scrollConsole.post { scrollConsole.fullScroll(View.FOCUS_DOWN) }
    }

    /** Fresh OkHttpClient that does NOT use the app's VPN/DNS tunnel. */
    private fun plainClient(timeoutSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun shutdown(c: OkHttpClient) {
        try { c.dispatcher.executorService.shutdown(); c.connectionPool.evictAll() }
        catch (_: Exception) {}
    }

    /** Extract Ookla base URL from upload.php URL (e.g. "http://host:8080/speedtest/upload.php" → "http://host:8080/speedtest/"). */
    private fun ooklaBaseUrl(url: String): String {
        // Ookla url field is like "http://host:port/speedtest/upload.php"
        // We need the directory: "http://host:port/speedtest/"
        val idx = url.lastIndexOf('/')
        return if (idx > 8) url.substring(0, idx + 1) else "$url/"
    }

    /** Ensure a URL starts with https://. */
    private fun ensureHttps(url: String): String {
        val trimmed = url.trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
            else -> "https://$trimmed"
        }
    }
}
