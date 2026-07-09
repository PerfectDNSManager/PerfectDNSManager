package app.perfectdnsmanager

import app.perfectdnsmanager.util.pdmBackground
import app.perfectdnsmanager.util.pdmBorder
import app.perfectdnsmanager.util.pdmSurface
import app.perfectdnsmanager.util.pdmSurfaceInput
import app.perfectdnsmanager.util.pdmSurfaceElevated
import app.perfectdnsmanager.util.pdmTextPrimary
import app.perfectdnsmanager.util.pdmTextSecondary
import app.perfectdnsmanager.util.pdmTextDisabled
import app.perfectdnsmanager.util.pdmAccent
import app.perfectdnsmanager.util.pdmDanger
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
import app.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Ookla server (résultat de l'API c.speedtest.net).
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
 * Enum des backends de speedtest disponibles.
 *
 * - CLOUDFLARE : speed.cloudflare.com, infra CF mondiale, sans cookie/auth.
 * - OOKLA : c.speedtest.net, auto-pick du serveur le plus proche par latence.
 *   (Réintroduit en beta.105 : l'API marche bien, le bug venait du paramètre
 *   `&search=` vide qui déclenchait un 403.)
 * - NETFLIX : api.fast.com (Netflix CDN), download-only — l'API ne fournit
 *   pas d'endpoint upload officiel.
 */
enum class SpeedBackend(val label: String) {
    CLOUDFLARE("Cloudflare"),
    NETFLIX("Fast.com"),
    OOKLA("Ookla")
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

        // Ookla endpoints — c.speedtest.net (XML/JSON), pas de cookie/auth.
        // Le bug en beta.85 venait du paramètre `&search=` vide qui faisait
        // 403. Sans ce paramètre, l'endpoint répond bien JSON.
        private const val OOKLA_SERVER_LIST_URL =
            "https://www.speedtest.net/api/js/servers?engine=js&limit=10"
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
    private lateinit var btnBackend: Button
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
    // Ookla : on charge la liste à la sélection du backend dans le picker
    // dialog, on ping tous les serveurs en parallèle pour afficher la
    // latence à côté de chaque option dans la colonne droite du dialog.
    private val ooklaServers = mutableListOf<OoklaServer>()
    private var selectedOoklaServer: OoklaServer? = null
    private val ooklaLatencies = mutableMapOf<Int, Double>()

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

        // Uniforme avec PDMBackButton (13sp + paddings 10dp/6dp).
        val btnBack = Button(this).apply {
            id = View.generateViewId()
            text = getString(R.string.back_arrow)
            setTextColor(COLOR_WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            minWidth = 0
            minHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
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

        // ── Backend selector (single button + dialog picker) ─────────────
        // Remplace les chips horizontales en bandeau (qui débordaient à
        // droite quand focus sur Cloudflare car scaleX=1.06 + padding parent).
        // Pattern liste : 1 bouton "Backend : Cloudflare ▾" → dialog picker.
        btnBackend = Button(this).apply {
            id = View.generateViewId()
            text = backendButtonText(SpeedBackend.CLOUDFLARE)
            setTextColor(COLOR_CYAN)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            background = chipBackground(dp(12), COLOR_CYAN, false)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(6) }
            gravity = Gravity.CENTER
            setOnClickListener {
                if (!running.get()) showBackendPickerDialog()
            }
        }
        mainColumn.addView(btnBackend)


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
        // Chaîne DPAD : btnBack ↓ btnBackend ↓ btnStartStop ↓ consoleWrapper.
        btnBack.nextFocusDownId = btnBackend.id
        btnBackend.nextFocusUpId = btnBack.id
        btnBackend.nextFocusDownId = btnStartStop.id
        btnStartStop.nextFocusUpId = btnBackend.id
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

        // Console : pattern FrameLayout wrapper (focusable, foreground border)
        // + ScrollView interne (focusable=false, scrollbars visibles).
        // Identique au wrapStatus/wrapReport de l'écran principal.
        // Le foregroundGravity="fill" garantit que le cadre vert épouse les
        // bounds réelles du wrapper (le bug en beta.101 venait du foreground
        // posé sur le ScrollView qui ne tracait pas correctement le cadre).
        val consoleWrapper = android.widget.FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = lp(matchParent, dp(130))
            background = GradientDrawable().apply {
                setColor(pdmSurfaceInput()); cornerRadius = dp(8).toFloat()
            }
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            foregroundGravity = Gravity.FILL
            isFocusable = true
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        scrollConsole = ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = false
            isFocusable = false
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
        consoleWrapper.addView(scrollConsole)
        mainColumn.addView(consoleWrapper)

        // Chaîne DPAD : btnStartStop ↔ consoleWrapper · consoleWrapper ↓ lui.
        btnStartStop.nextFocusDownId = consoleWrapper.id
        consoleWrapper.nextFocusUpId = btnStartStop.id
        consoleWrapper.nextFocusDownId = consoleWrapper.id

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

    /**
     * Libellé du bouton "Serveur de test". Pour Ookla, on suffixe le nom du
     * serveur sélectionné + sa latence pour qu'on sache lequel sera utilisé.
     */
    private fun backendButtonText(backend: SpeedBackend): String {
        val label = if (backend == SpeedBackend.OOKLA) {
            val srv = selectedOoklaServer
            if (srv != null) "${backend.label} — ${ooklaServerLabel(srv)}" else backend.label
        } else backend.label
        return getString(R.string.speedtest_backend_label_fmt, label)
    }

    /** Libellé du picker Ookla : "Sponsor (Pays) — 12 ms". */
    private fun ooklaServerLabel(s: OoklaServer): String {
        val country = s.cc ?: s.country ?: ""
        val sponsor = s.displayName
        val latency = ooklaLatencies[s.id]
        val latStr = if (latency != null) " — ${"%.0f".format(latency)} ms" else ""
        return if (country.isNotEmpty()) "$sponsor ($country)$latStr" else "$sponsor$latStr"
    }

    /**
     * Dialog picker 2-colonnes : à gauche les backends (Cloudflare / Ookla /
     * Fast.com), à droite — UNIQUEMENT quand Ookla est sélectionné — la
     * liste des serveurs Ookla avec leur ping (loading puis valeur). Click
     * sur un backend non-Ookla confirme directement. Click sur un serveur
     * Ookla confirme aussi (sélectionne Ookla + ce serveur).
     */
    private fun showBackendPickerDialog() {
        val backends = SpeedBackend.entries.toList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            setBackgroundColor(pdmSurfaceElevated())
        }

        // ── Colonne gauche : titre + radio backends ──
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        leftCol.addView(TextView(this).apply {
            text = getString(R.string.speedtest_backend_picker_title)
            setTextColor(COLOR_WHITE); textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        // ── Colonne droite : liste serveurs Ookla (cachée si autre backend) ──
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(16)
            }
            visibility = if (currentBackend == SpeedBackend.OOKLA) View.VISIBLE else View.GONE
        }
        val rightTitle = TextView(this).apply {
            text = getString(R.string.speedtest_ookla_picker_title)
            setTextColor(COLOR_WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        rightCol.addView(rightTitle)
        val serverScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
            isFillViewport = false
        }
        val serverList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serverScroll.addView(serverList)
        rightCol.addView(serverScroll)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Backend radios — focusable, on-click adapte la colonne droite.
        val backendBtns = mutableMapOf<SpeedBackend, android.widget.RadioButton>()
        val rg = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        backends.forEach { be ->
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = be.label
                setTextColor(COLOR_WHITE); textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_CYAN)
                background = resources.getDrawable(R.drawable.focusable_item_background, theme)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isFocusable = true; isFocusableInTouchMode = false
                isChecked = be == currentBackend
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.MATCH_PARENT, dp(48)
                )
            }
            backendBtns[be] = rb
            rg.addView(rb)
        }
        leftCol.addView(rg)

        root.addView(leftCol)
        root.addView(rightCol)

        // Helpers locaux pour rendre la liste des serveurs.
        fun renderServerList(items: List<OoklaServer>, loading: Boolean) {
            serverList.removeAllViews()
            if (loading && items.isEmpty()) {
                serverList.addView(TextView(this).apply {
                    text = getString(R.string.speedtest_ookla_loading)
                    setTextColor(COLOR_LIGHT_GREY); textSize = 13f
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                })
                return
            }
            val srvRg = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
            items.forEachIndexed { i, srv ->
                val rb = android.widget.RadioButton(this).apply {
                    id = View.generateViewId()
                    text = ooklaServerLabel(srv)
                    setTextColor(COLOR_WHITE); textSize = 13f
                    buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_CYAN)
                    background = resources.getDrawable(R.drawable.focusable_item_background, theme)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isFocusable = true; isFocusableInTouchMode = false
                    isChecked = srv.id == selectedOoklaServer?.id
                    layoutParams = android.widget.RadioGroup.LayoutParams(
                        android.widget.RadioGroup.LayoutParams.MATCH_PARENT, dp(44)
                    )
                    setOnClickListener {
                        selectedOoklaServer = srv
                        currentBackend = SpeedBackend.OOKLA
                        btnBackend.text = backendButtonText(SpeedBackend.OOKLA)
                        logConsole("✓ ${srv.displayName}")
                        dialog.dismiss()
                    }
                }
                srvRg.addView(rb)
                if (i == 0) rb.post { rb.requestFocus() }
            }
            serverList.addView(srvRg)
        }

        // Charge la liste au moment où Ookla est sélectionné si on a rien.
        fun loadOoklaForDialog() {
            if (ooklaServers.isNotEmpty()) {
                renderServerList(ooklaServers, loading = false)
                return
            }
            renderServerList(emptyList(), loading = true)
            lifecycleScope.launch(Dispatchers.IO) {
                fetchAndPingOoklaServers()
                ui {
                    if (rightCol.visibility == View.VISIBLE)
                        renderServerList(ooklaServers, loading = false)
                }
            }
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            val picked = backendBtns.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            if (picked == SpeedBackend.OOKLA) {
                rightCol.visibility = View.VISIBLE
                loadOoklaForDialog()
            } else {
                rightCol.visibility = View.GONE
                if (picked != currentBackend) switchBackend(picked)
                dialog.dismiss()
            }
        }

        // Si on ouvre le dialog avec Ookla déjà actif → précharger la liste.
        if (currentBackend == SpeedBackend.OOKLA) loadOoklaForDialog()

        dialog.setOnShowListener {
            backendBtns[currentBackend]?.post { backendBtns[currentBackend]?.requestFocus() }
        }
        dialog.show()
    }

    private fun switchBackend(backend: SpeedBackend) {
        currentBackend = backend
        btnBackend.text = backendButtonText(backend)
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
            SpeedBackend.OOKLA -> {
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
                tvConsole.text = getString(R.string.speedtest_waiting)
                // Liste/ping serveurs déclenché par le picker dialog.
            }
            SpeedBackend.NETFLIX -> {
                btnStartStop.visibility = View.VISIBLE
                resultsCard.visibility = View.VISIBLE
                tvConsoleLabel.visibility = View.VISIBLE
                scrollConsole.visibility = View.VISIBLE
                rootScroll.visibility = View.VISIBLE
                resetResults()
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
            SpeedBackend.OOKLA -> startOoklaTest()
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
        tvUpload.text = "-- Mbps"
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
    //  OOKLA TEST
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fetch + ping de tous les serveurs Ookla. Appelé depuis le picker dialog
     * (lifecycleScope.launch IO). Met à jour ooklaServers + ooklaLatencies +
     * selectedOoklaServer (auto-pick = plus rapide).
     */
    private suspend fun fetchAndPingOoklaServers() {
        ooklaLatencies.clear()
        ui { logConsole(getString(R.string.speedtest_ookla_loading)) }
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
                ui { logConsole(getString(R.string.speedtest_ookla_err_code_fmt, code)) }
            }
            shutdown(client)
        } catch (e: Exception) {
            Log.w(TAG, "Ookla server list fetch failed", e)
            ui { logConsole(getString(R.string.speedtest_ookla_err_load_fmt, e.message ?: "")) }
        }

        // Ping TOUS les serveurs en parallèle (3s timeout chacun).
        if (fetched.isNotEmpty()) {
            ui { logConsole(getString(R.string.speedtest_ookla_latency)) }
            val pingJobs = kotlinx.coroutines.coroutineScope {
                fetched.map { server ->
                    async(Dispatchers.IO) {
                        if (cancelled.get()) return@async null
                        try {
                            val client = plainClient(3)
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
                            if (pingResp.isSuccessful || pingResp.code in 200..499)
                                server.id to ms
                            else null
                        } catch (_: Exception) { null }
                    }
                }
            }
            val measured = pingJobs.mapNotNull { it.await() }.toMap()
            fetched.sortWith(compareBy(nullsLast()) { measured[it.id] })
            ui { ooklaLatencies.putAll(measured) }
        }

        ui {
            ooklaServers.clear()
            ooklaServers.addAll(fetched)
            if (ooklaServers.isEmpty()) {
                logConsole(getString(R.string.speedtest_ookla_none))
                selectedOoklaServer = null
                return@ui
            }
            logConsole(getString(R.string.speedtest_ookla_loaded_fmt, ooklaServers.size))
            selectedOoklaServer = ooklaServers[0]
            // Reflète l'auto-pick dans le bouton "Serveur de test : ..." si
            // on est en mode Ookla actuellement.
            if (currentBackend == SpeedBackend.OOKLA) {
                btnBackend.text = backendButtonText(SpeedBackend.OOKLA)
            }
            logConsole("✓ ${ooklaServers[0].displayName}")
        }
    }

    private fun startOoklaTest() {
        val server = selectedOoklaServer ?: run {
            logConsole(getString(R.string.speedtest_ookla_not_selected))
            running.set(false); updateButton(false); return
        }

        logConsole(getString(R.string.speedtest_start_msg, getString(R.string.speedtest_ookla_title)))
        logConsole("→ ${server.displayName}")

        testThread = Thread {
            try {
                if (!cancelled.get()) runOoklaPing(server)
                if (!cancelled.get()) runOoklaDownload(server)
                if (!cancelled.get()) runOoklaUpload(server)
                if (!cancelled.get()) ui { logConsole(getString(R.string.speedtest_done_msg)) }
            } catch (_: InterruptedException) {
                ui { logConsole(getString(R.string.speedtest_interrupted)) }
            } catch (e: Exception) {
                Log.e(TAG, "Ookla test error", e)
                ui { logConsole(getString(R.string.speedtest_error_fmt, e.message ?: "")) }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    private fun runOoklaPing(server: OoklaServer) {
        ui { logConsole(getString(R.string.speedtest_ping_section_fmt, getString(R.string.speedtest_ookla_title), OOKLA_PING_COUNT)) }
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
                ui { logConsole(getString(R.string.speedtest_ping_attempt_fmt, i, ms)) }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole(getString(R.string.speedtest_ping_fail_fmt, i)) }
            }
        }
        shutdown(client)
        if (pings.isNotEmpty() && !cancelled.get()) {
            val avg = pings.average()
            val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ui {
                tvPing.text = "${"%.1f".format(avg)} ms"
                tvJitter.text = "${"%.1f".format(jitter)} ms"
                logConsole(getString(R.string.speedtest_ping_avg_fmt, avg, jitter))
            }
        }
    }

    private fun runOoklaDownload(server: OoklaServer) {
        ui { logConsole(getString(R.string.speedtest_dl_section_fmt, getString(R.string.speedtest_ookla_title), OOKLA_DL_CONNECTIONS, OOKLA_DL_DURATION_SEC)) }
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
                logConsole(getString(R.string.speedtest_mbps_fmt, mbps, bytes / 1_048_576.0, elapsed))
            }
        }
    }

    private fun runOoklaUpload(server: OoklaServer) {
        ui { logConsole(getString(R.string.speedtest_ul_section_fmt, getString(R.string.speedtest_ookla_title), OOKLA_UL_CONNECTIONS, OOKLA_UL_DURATION_SEC)) }
        val ulUrl = server.url
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
                        try { c.newCall(req).execute().close() } catch (_: Exception) {}
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
                logConsole(getString(R.string.speedtest_mbps_fmt, mbps, bytes / 1_048_576.0, elapsed))
            }
        }
    }

    /** Extrait la base URL Ookla depuis l'URL upload.php. */
    private fun ooklaBaseUrl(url: String): String {
        val idx = url.lastIndexOf('/')
        return if (idx > 8) url.substring(0, idx + 1) else "$url/"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETFLIX (FAST.COM) TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun startNetflixTest() {
        logConsole(getString(R.string.speedtest_start_msg, getString(R.string.speedtest_netflix_title)))

        testThread = Thread {
            try {
                val testUrls = fetchNetflixTestUrls()
                if (testUrls.isEmpty()) {
                    ui { logConsole(getString(R.string.speedtest_error_fmt, "Fast.com URLs unavailable")) }
                    return@Thread
                }
                ui { logConsole(getString(R.string.speedtest_fast_urls_loaded_fmt, testUrls.size)) }

                if (!cancelled.get()) runNetflixPing(testUrls)
                if (!cancelled.get()) runNetflixDownload(testUrls)
                if (!cancelled.get()) runNetflixUpload(testUrls)
                if (!cancelled.get()) ui {
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

    /**
     * Netflix upload : POST chunked vers les URLs CDN OCA Netflix.
     * L'API Fast.com publique ne fournit pas d'endpoint upload officiel,
     * mais l'app Fast.com officielle fait POST aux mêmes URLs de download
     * (Netflix CDN accepte le POST et discard le body, on mesure les bytes
     * écrits côté client). Reverse-engineered, mais ça correspond à ce que
     * fait l'app officielle.
     */
    private fun runNetflixUpload(testUrls: List<String>) {
        val connections = testUrls.size
        ui { logConsole("\n${getString(R.string.speedtest_ul_section_fmt, getString(R.string.speedtest_netflix_title), connections, NETFLIX_DL_DURATION_SEC)}") }
        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + NETFLIX_DL_DURATION_SEC * 1_000_000_000L
        val payload = ByteArray(2 * 1024 * 1024) { (it % 256).toByte() }  // 2 MB chunks

        val workers = testUrls.map { url ->
            Thread {
                val c = plainClient(NETFLIX_DL_DURATION_SEC.toLong() + 5)
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
                            .url(url)
                            .post(body)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        try { c.newCall(req).execute().close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }
        monitorProgress(totalBytes, t0, deadline, NETFLIX_DL_DURATION_SEC) { mbps, progress ->
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
