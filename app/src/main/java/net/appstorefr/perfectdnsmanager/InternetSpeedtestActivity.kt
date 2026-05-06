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
import net.appstorefr.perfectdnsmanager.util.pdmDanger
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
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Enum for speed test backends.
 *
 * Ookla a été retiré (beta.101) : l'API publique speedtest.net/api/js/servers
 * a été dépréciée/restreinte par CAPTCHA, les serveurs eux-mêmes refusent les
 * UA non-officiels. Cloudflare (sans inscription, infra mondiale CF) et
 * Fast.com (Netflix CDN) couvrent largement le besoin.
 */
enum class SpeedBackend(val label: String) {
    CLOUDFLARE("Cloudflare"),
    NETFLIX("Fast.com")
}

/**
 * Multi-backend speed test activity.
 *
 * Supports 2 backends : Cloudflare et Netflix (Fast.com).
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

        // Netflix (Fast.com) endpoints
        private const val NETFLIX_API_URL =
            "https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5"
        private const val NETFLIX_DL_DURATION_SEC = 10
        private const val NETFLIX_PING_COUNT = 10

    }

    // ── UI widgets ───────────────────────────────────────────────────────────
    private lateinit var backendSelectorRow: HorizontalScrollView
    private lateinit var backendButtonsLayout: LinearLayout
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

    override fun onResume() {
        super.onResume()
        btnStartStop.post { btnStartStop.requestFocus() }
    }

    /**
     * Filet de sécurité pour le focus initial : à chaque fois que la fenêtre
     * gagne le focus (cold start ou retour de sub-activity), on s'assure que
     * btnStartStop a bien le focus. onResume() seul ne suffit pas toujours :
     * sur certaines TV box, le système attend d'avoir le window focus avant
     * d'appliquer requestFocus(), et un autre élément peut grappiller entre
     * les deux. Cette callback est garantie d'arriver après la stabilisation.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !btnStartStop.isFocused) {
            btnStartStop.requestFocus()
        }
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
            // ScrollView est focusable par défaut → il pouvait capter le focus
            // initial à la place de btnStartStop, et DPAD DOWN depuis le
            // bouton tombait dans une zone "vide" focusable. On désactive
            // sa focusabilité directe et on laisse le focus aller aux enfants.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
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
            id = View.generateViewId()
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
            text = getString(R.string.speedtest_adv_title)
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
                id = View.generateViewId()
                text = backend.label
                setTextColor(COLOR_WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                isFocusable = true
                // Pas de foreground btn_focus_foreground : le bord vert se mélange
                // avec le cyan de la sélection. À la place on swap le background
                // sur focus pour un cadre jaune/vif visible quel que soit l'état.
                background = chipBackground(dp(20), COLOR_CYAN, false)
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
            // Focus visuel fort : jaune épais + agrandissement léger.
            chipBtn.setOnFocusChangeListener { v, hasFocus ->
                val active = backendButtons.entries.firstOrNull { it.value === v }?.key == currentBackend
                v.background = if (hasFocus) {
                    chipFocusedBackground(dp(20), if (active) COLOR_CYAN else pdmBorder())
                } else {
                    chipBackground(dp(20), COLOR_CYAN, active)
                }
                v.scaleX = if (hasFocus) 1.06f else 1f
                v.scaleY = if (hasFocus) 1.06f else 1f
            }
            backendButtons[backend] = chipBtn
            backendButtonsLayout.addView(chipBtn)
        }

        // Chaîne D-pad horizontale entre les chips (LEFT/RIGHT). Sans ça,
        // dans un HorizontalScrollView le focus search default ne traverse
        // pas systématiquement les frères.
        val chips = backendButtons.values.toList()
        chips.forEachIndexed { idx, chip ->
            chip.nextFocusLeftId = chips.getOrNull(idx - 1)?.id ?: chip.id
            chip.nextFocusRightId = chips.getOrNull(idx + 1)?.id ?: chip.id
        }

        backendSelectorRow.addView(backendButtonsLayout)
        mainColumn.addView(backendSelectorRow)

        // ── Start / Stop button ──────────────────────────────────────────
        btnStartStop = Button(this).apply {
            id = View.generateViewId()
            text = getString(R.string.speedtest_start)
            setTextColor(COLOR_GREEN)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            // Pas de foreground btn_focus_foreground : sa stroke verte se confond
            // avec la stroke verte du greenPill → curseur invisible. À la place,
            // on swap le background sur focus pour un cadre ambre vif et un
            // léger zoom (cohérent avec les chips backend).
            background = greenPill(dp(8))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(8) }
            setOnClickListener { toggleTest() }
        }
        btnStartStop.setOnFocusChangeListener { v, hasFocus ->
            // Pas de scaleX/Y ici : btnStartStop est match_parent, un zoom 1.04
            // le ferait déborder de l'écran horizontalement. Le cadre ambre 4dp
            // suffit pour la visibilité du focus.
            v.background = if (hasFocus) {
                pillFocusedBackground(dp(8))
            } else if (running.get()) {
                redPill(dp(8))
            } else {
                greenPill(dp(8))
            }
        }
        // Chaîne D-pad verticale explicite : btnBack ↓ premier chip ·
        // chips ↓ btnStartStop · btnStartStop ↔ scrollConsole.
        // scrollConsole reçoit son id assigné après son creation plus bas,
        // on update sa nextFocusDownId là-bas. Ici on prépare juste le côté
        // btnStartStop ↑ chips.
        val firstChipId = backendButtons.values.firstOrNull()?.id
        if (firstChipId != null) {
            btnBack.nextFocusDownId = firstChipId
            backendButtons.values.forEach { chip ->
                chip.nextFocusUpId = btnBack.id
                chip.nextFocusDownId = btnStartStop.id
            }
            btnStartStop.nextFocusUpId = firstChipId
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
            text = getString(R.string.speedtest_ip_none); setTextColor(COLOR_LIGHT_GREY); textSize = 13f
            gravity = Gravity.CENTER
        }
        resultsCard.addView(tvClientIp)
        mainColumn.addView(resultsCard)

        // ── Console ──────────────────────────────────────────────────────
        tvConsoleLabel = TextView(this).apply {
            text = getString(R.string.speedtest_log); setTextColor(COLOR_DIM); textSize = 12f
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        mainColumn.addView(tvConsoleLabel)

        // Console : encart focusable + scrollable, plus grand qu'avant pour
        // afficher 15+ lignes. Le user peut DPAD-DOWN dedans depuis Démarrer
        // pour scroller le log (comme les encarts rapport/info système de
        // l'écran principal). Cadre vert au focus via btn_focus_foreground.
        scrollConsole = ScrollView(this).apply {
            id = View.generateViewId()
            layoutParams = lp(matchParent, dp(220))
            background = GradientDrawable().apply {
                setColor(pdmSurfaceInput()); cornerRadius = dp(8).toFloat()
            }
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isScrollbarFadingEnabled = false
            scrollBarStyle = ScrollView.SCROLLBARS_INSIDE_OVERLAY
        }
        tvConsole = TextView(this).apply {
            setTextColor(COLOR_LIGHT_GREY); textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = getString(R.string.speedtest_waiting)
            isFocusable = false
        }
        scrollConsole.addView(tvConsole)
        mainColumn.addView(scrollConsole)

        // Maintenant que scrollConsole a son id, on ferme la chaîne DPAD :
        // btnStartStop ↓ scrollConsole · scrollConsole ↑ btnStartStop ·
        // scrollConsole ↓ lui-même (rien de focusable en dessous).
        btnStartStop.nextFocusDownId = scrollConsole.id
        scrollConsole.nextFocusUpId = btnStartStop.id
        scrollConsole.nextFocusDownId = scrollConsole.id

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
        GradientDrawable().apply {
            setColor(pdmSurface())
            setStroke(dp(3), COLOR_GREEN)
            cornerRadius = r.toFloat()
        }

    private fun redPill(r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(pdmSurface())
            setStroke(dp(3), COLOR_RED)
            cornerRadius = r.toFloat()
        }

    private fun chipBackground(r: Int, accentColor: Int, active: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(pdmSurface())
            setStroke(dp(if (active) 2 else 1), if (active) accentColor else pdmBorder())
            cornerRadius = r.toFloat()
        }

    /** Background pour chip focusé : cadre jaune épais visible quel que soit
     *  l'état actif/inactif (le focus_foreground vert se confondait sinon
     *  avec le cyan de la sélection). */
    private fun chipFocusedBackground(r: Int, baseColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(pdmSurface())
            setStroke(dp(3), 0xFFFFD740.toInt()) // amber 400 — fort contraste
            cornerRadius = r.toFloat()
        }

    /** Background pour pilule (Start/Stop) focusée : cadre ambre visible peu
     *  importe la couleur de la pilule courante (verte au repos, rouge en
     *  cours de test). Garde un fill légèrement teinté pour matcher l'état. */
    private fun pillFocusedBackground(r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(pdmSurface())
            setStroke(dp(4), 0xFFFFD740.toInt()) // amber 400 — fort contraste
            cornerRadius = r.toFloat()
        }

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

        // Update chip colors — active = dark surface + cyan stroke; inactive = dark surface + subtle border
        for ((b, btn) in backendButtons) {
            val active = b == backend
            btn.background = chipBackground(dp(20), COLOR_CYAN, active)
            btn.setTextColor(if (active) COLOR_CYAN else COLOR_LIGHT_GREY)
        }

        when (backend) {
            SpeedBackend.CLOUDFLARE -> {
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvConsole.text = getString(R.string.speedtest_waiting)
                logConsole(getString(R.string.speedtest_backend_cf))
            }
            SpeedBackend.NETFLIX -> {
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvUpload.text = "N/A"
                tvConsole.text = getString(R.string.speedtest_waiting)
                logConsole(getString(R.string.speedtest_backend_netflix))
            }
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
            SpeedBackend.NETFLIX -> startNetflixTest()
        }
    }

    private fun stopTest() {
        logConsole(getString(R.string.speedtest_stopping_msg))
        cancelled.set(true)
        testThread?.interrupt()
    }

    private fun updateButton(isRunning: Boolean) {
        val dp8 = dp(8)
        btnStartStop.text = getString(if (isRunning) R.string.speedtest_stop else R.string.speedtest_start)
        // Si focus en cours, on garde le cadre ambre \u2014 le focus listener
        // restaurera la pilule color\u00e9e \u00e0 la perte de focus.
        btnStartStop.background = when {
            btnStartStop.isFocused -> pillFocusedBackground(dp8)
            isRunning -> redPill(dp8)
            else -> greenPill(dp8)
        }
        btnStartStop.setTextColor(if (isRunning) COLOR_RED else COLOR_GREEN)
    }

    private fun resetResults() {
        tvPing.text = "-- ms"; tvJitter.text = "-- ms"
        tvDownload.text = "-- Mbps"
        tvUpload.text = if (currentBackend == SpeedBackend.NETFLIX) "N/A" else "-- Mbps"
        tvClientIp.text = getString(R.string.speedtest_ip_none)
        pbDownload.progress = 0; pbUpload.progress = 0
        tvConsole.text = ""
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CLOUDFLARE TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startCloudflareTest() {
        logConsole(getString(R.string.speedtest_start_msg, getString(R.string.speedtest_cloudflare_title)))

        testThread = Thread {
            try {
                if (!cancelled.get()) runCloudflarePing()
                if (!cancelled.get()) runCloudflareDownload()
                if (!cancelled.get()) runCloudflareUpload()
                if (!cancelled.get()) ui { logConsole(getString(R.string.speedtest_done_msg)) }
            } catch (_: InterruptedException) {
                ui { logConsole(getString(R.string.speedtest_interrupted)) }
            } catch (e: Exception) {
                Log.e(TAG, "Cloudflare test error", e)
                ui { logConsole(getString(R.string.speedtest_error_fmt, e.message ?: "")) }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    private fun runCloudflarePing() {
        ui { logConsole(getString(R.string.speedtest_ping_section_fmt, getString(R.string.speedtest_cloudflare_title), CF_PING_COUNT)) }
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
                ui { logConsole(getString(R.string.speedtest_ping_attempt_fmt, i, ms)) }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole(getString(R.string.speedtest_ping_fail_fmt, i)) }
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
                logConsole(getString(R.string.speedtest_ping_avg_fmt, avg, jitter))
            }
        }
    }

    private fun runCloudflareDownload() {
        ui { logConsole(getString(R.string.speedtest_dl_section_fmt, getString(R.string.speedtest_cloudflare_title), CF_DL_CONNECTIONS, CF_DL_DURATION_SEC)) }

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
                logConsole(getString(R.string.speedtest_mbps_fmt, mbps, bytes / 1_048_576.0, elapsed))
            }
        }
    }

    private fun runCloudflareUpload() {
        ui { logConsole(getString(R.string.speedtest_ul_section_fmt, getString(R.string.speedtest_cloudflare_title), CF_UL_CONNECTIONS, CF_UL_DURATION_SEC)) }

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
                logConsole(getString(R.string.speedtest_mbps_fmt, mbps, bytes / 1_048_576.0, elapsed))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETFLIX (FAST.COM) TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startNetflixTest() {
        logConsole(getString(R.string.speedtest_start_msg, getString(R.string.speedtest_netflix_title)))
        logConsole(getString(R.string.speedtest_upload_na))

        testThread = Thread {
            try {
                // Fetch test URLs from Fast.com API
                val testUrls = fetchNetflixTestUrls()
                if (testUrls.isEmpty()) {
                    ui { logConsole(getString(R.string.speedtest_error_fmt, "Fast.com URLs unavailable")) }
                    return@Thread
                }
                ui { logConsole(getString(R.string.speedtest_fast_urls_loaded_fmt, testUrls.size)) }

                if (!cancelled.get()) runNetflixPing(testUrls)
                if (!cancelled.get()) runNetflixDownload(testUrls)
                if (!cancelled.get()) ui {
                    tvUpload.text = "N/A"
                    pbUpload.progress = 1000
                    logConsole("\n${getString(R.string.speedtest_upload_unsupported)}")
                    logConsole(getString(R.string.speedtest_done_msg))
                }
            } catch (_: InterruptedException) {
                ui { logConsole(getString(R.string.speedtest_interrupted)) }
            } catch (e: Exception) {
                Log.e(TAG, "Netflix test error", e)
                ui { logConsole(getString(R.string.speedtest_error_fmt, e.message ?: "")) }
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
                ui { logConsole(getString(R.string.speedtest_fast_api_error_fmt, code.toString())) }
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Netflix API fetch failed", e)
            ui { logConsole(getString(R.string.speedtest_fast_error_fmt, e.message ?: "")) }
            emptyList()
        } finally {
            shutdown(client)
        }
    }

    private fun runNetflixPing(testUrls: List<String>) {
        ui { logConsole("\n${getString(R.string.speedtest_ping_section_fmt, getString(R.string.speedtest_netflix_title), NETFLIX_PING_COUNT)}") }
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
                ui { logConsole(getString(R.string.speedtest_ping_attempt_fmt, i, ms)) }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole(getString(R.string.speedtest_ping_fail_fmt, i)) }
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
                logConsole(getString(R.string.speedtest_ping_avg_fmt, avg, jitter))
            }
        }
    }

    private fun runNetflixDownload(testUrls: List<String>) {
        val connections = testUrls.size
        ui { logConsole("\n${getString(R.string.speedtest_dl_section_fmt, getString(R.string.speedtest_netflix_title), connections, NETFLIX_DL_DURATION_SEC)}") }

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
                logConsole(getString(R.string.speedtest_mbps_fmt, mbps, bytes / 1_048_576.0, elapsed))
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

}
