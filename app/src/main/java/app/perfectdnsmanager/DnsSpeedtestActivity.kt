package app.perfectdnsmanager

import app.perfectdnsmanager.util.pdmTextPrimary
import app.perfectdnsmanager.util.pdmTextDisabled
import app.perfectdnsmanager.util.pdmAccent
import app.perfectdnsmanager.util.pdmDanger
import app.perfectdnsmanager.util.pdmWarning

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.util.DnsColors
import app.perfectdnsmanager.util.DnsTester
import app.perfectdnsmanager.util.LocaleHelper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DnsSpeedtestActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var tvCurrentTest: TextView
    private lateinit var layoutProviders: LinearLayout
    private lateinit var tvTopRanking: TextView
    private lateinit var tvFullRanking: TextView
    private lateinit var wrapTopRanking: android.widget.FrameLayout
    private lateinit var wrapFullRanking: android.widget.FrameLayout
    private lateinit var scrollProviders: ScrollView
    private lateinit var scrollTopRanking: ScrollView
    private lateinit var scrollFullRanking: ScrollView
    private lateinit var btnStartStop: Button
    private lateinit var btnBack: Button

    @Volatile
    private var cancelled = false
    @Volatile private var running = false
    private var testThread: Thread? = null

    // Theme-aware colors (instance-level)
    private val COLOR_GREEN: Int get() = pdmAccent()
    private val COLOR_RED: Int get() = pdmDanger()
    private val COLOR_GREY: Int get() = pdmTextDisabled()
    private val COLOR_GOLD: Int get() = pdmWarning()
    private val COLOR_WHITE: Int get() = pdmTextPrimary() // Theme-aware: blanc en dark, noir en light

    companion object {
        private const val COLOR_YELLOW = 0xFFFFEB3B.toInt()
        private const val COLOR_ORANGE = 0xFFFF9800.toInt()
        private const val COLOR_CYAN = 0xFF00BCD4.toInt()
    }

    private data class SpeedResult(
        val provider: String,
        val address: String,
        val latency: Long?,
        val type: String
    )

    /** Recent progress lines (keep last 3 for the compact tvCurrentTest) */
    private val recentProgressLines = mutableListOf<Pair<String, Int>>()

    /** Track which providers are expanded in the left panel */
    private val expandedProviders = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_speedtest)

        tvCurrentTest = findViewById(R.id.tvCurrentTest)
        layoutProviders = findViewById(R.id.layoutProviders)
        tvTopRanking = findViewById(R.id.tvTopRanking)
        tvFullRanking = findViewById(R.id.tvFullRanking)
        wrapTopRanking = findViewById(R.id.wrapTopRanking)
        wrapFullRanking = findViewById(R.id.wrapFullRanking)
        scrollProviders = findViewById(R.id.scrollProviders)
        scrollTopRanking = findViewById(R.id.scrollTopRanking)
        scrollFullRanking = findViewById(R.id.scrollFullRanking)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }
        btnStartStop.setOnClickListener { toggleStartStop() }

        btnStartStop.isFocusable = true
        btnStartStop.isFocusableInTouchMode = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            btnStartStop.focusable = View.FOCUSABLE
        }

        tvCurrentTest.text = getString(R.string.dns_speedtest_title)

        // DPAD UP/DOWN sur les wrappers focusables : scroll le ScrollView
        // interne (pattern wrapStatus/wrapReport de l'écran principal).
        wrapTopRanking.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scrollTopRanking.smoothScrollBy(0, 100); true }
                KeyEvent.KEYCODE_DPAD_UP -> { scrollTopRanking.smoothScrollBy(0, -100); true }
                else -> false
            } else false
        }
        wrapFullRanking.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { scrollFullRanking.smoothScrollBy(0, 100); true }
                KeyEvent.KEYCODE_DPAD_UP -> { scrollFullRanking.smoothScrollBy(0, -100); true }
                else -> false
            } else false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Do not steal focus — user may be browsing results
    }

    private fun toggleStartStop() {
        if (running) {
            cancelled = true
            btnStartStop.text = getString(R.string.cancelling)
            btnStartStop.isEnabled = false
        } else {
            startSpeedtest()
        }
    }

    private fun setButtonStart() {
        runOnUiThread {
            btnStartStop.text = getString(R.string.dns_speedtest_button)
            btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(pdmAccent())
            btnStartStop.isEnabled = true
            running = false
            lockFocusToStartButton(false)
        }
    }

    private fun setButtonStop() {
        runOnUiThread {
            btnStartStop.text = getString(R.string.btn_stop)
            btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(pdmDanger())
            btnStartStop.isEnabled = true
            running = true
            lockFocusToStartButton(true)
        }
    }

    /**
     * Pendant un test, on verrouille le curseur sur btnStartStop : tous les
     * nextFocusXxx pointent sur lui-même pour empêcher le DPAD de partir.
     * Le user peut toujours appuyer ENTER pour stopper, ou BACK système pour
     * sortir de l'activité. Désactivé en fin de test pour rendre le DPAD libre.
     */
    private fun lockFocusToStartButton(lock: Boolean) {
        val target = if (lock) btnStartStop.id else View.NO_ID
        btnStartStop.nextFocusUpId = target
        btnStartStop.nextFocusDownId = target
        btnStartStop.nextFocusLeftId = target
        btnStartStop.nextFocusRightId = target
        if (lock) btnStartStop.requestFocus()
    }

    private fun startSpeedtest() {
        recentProgressLines.clear()
        expandedProviders.clear()
        tvCurrentTest.text = ""
        tvTopRanking.text = ""
        tvFullRanking.text = ""
        layoutProviders.removeAllViews()
        cancelled = false

        setButtonStop()

        appendProgress("DNS Speedtest", COLOR_CYAN)

        testThread = Thread {
            try {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val advancedEnabled = prefs.getBoolean("advanced_features_enabled", false)
                val operatorEnabled = prefs.getBoolean("operator_dns_enabled", false)

                val presets = DnsProfile.getDefaultPresets()
                val seenKeys = mutableSetOf<String>()
                val testProfiles = presets.filter { p ->
                    if (p.isOperatorDns && !(advancedEnabled && operatorEnabled)) return@filter false
                    if (p.type == DnsType.DOT && !advancedEnabled) return@filter false
                    val key = "${p.providerName}|${p.type}"
                    if (key in seenKeys) return@filter false
                    seenKeys.add(key)
                    true
                }

                appendProgress(getString(R.string.speedtest_providers_count_fmt, testProfiles.size), COLOR_WHITE)

                val sharedClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()

                val results = mutableListOf<SpeedResult>()

                for ((index, profile) in testProfiles.withIndex()) {
                    if (cancelled) {
                        appendProgress(getString(R.string.speedtest_cancelled), COLOR_RED)
                        break
                    }

                    val typeLabel = DnsColors.labelForType(profile.type)

                    appendProgress("[${index + 1}/${testProfiles.size}] ${profile.providerName} ($typeLabel)...", COLOR_WHITE)

                    val latency = try {
                        when (profile.type) {
                            DnsType.DOH -> {
                                DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                                DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                            }
                            DnsType.DOT -> {
                                DnsTester.measureDotLatency(profile.primary)
                                DnsTester.measureDotLatency(profile.primary)
                            }
                            DnsType.DOQ -> DnsTester.measureDoqLatency(profile.primary)
                            else -> DnsTester.measureLatency(profile.primary)
                        }
                    } catch (_: Exception) { null }

                    if (latency != null) {
                        val color = when {
                            latency < 50 -> COLOR_GREEN
                            latency < 100 -> COLOR_YELLOW
                            latency < 200 -> COLOR_ORANGE
                            else -> COLOR_RED
                        }
                        appendProgress("   -> ${latency} ms", color)
                    } else {
                        appendProgress("   ${getString(R.string.speedtest_error_timeout)}", COLOR_GREY)
                    }

                    results.add(SpeedResult(profile.providerName, profile.primary, latency, typeLabel))
                    updatePanels(results, final = false)
                }

                try { sharedClient.dispatcher.executorService.shutdown() } catch (_: Exception) {}
                try { sharedClient.connectionPool.evictAll() } catch (_: Exception) {}

                if (!cancelled) {
                    appendProgress(getString(R.string.speedtest_finished), COLOR_GREEN)
                    updatePanels(results, final = true)
                } else {
                    appendProgress(getString(R.string.speedtest_partial_count_fmt, results.size, testProfiles.size), COLOR_ORANGE)
                    updatePanels(results, final = true)
                }

            } catch (e: Exception) {
                appendProgress("ERREUR : ${e.message}", COLOR_RED)
            } finally {
                setButtonStart()
            }
        }.also { it.start() }
    }

    private fun updatePanels(results: List<SpeedResult>, final: Boolean) {
        runOnUiThread {
            val sorted = results.sortedWith(compareBy(nullsLast()) { it.latency })

            // --- LEFT panel: Providers grouped ---
            buildProviderPanel(results, final)

            // Only restore focus to btnStartStop when test finishes
            // (do NOT steal focus during test — user may be browsing results)

            // --- RIGHT panels : 2 sub-columns (Top5+Recommand\u00E9 | Classement) ---
            val topBuf = SpannableStringBuilder()
            val fullBuf = SpannableStringBuilder()

            // \u2500\u2500 Top 5 with medals + Recommand\u00E9 \u2500\u2500
            val podiumEmojis = listOf("\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "4.", "5.")
            val top5 = sorted.filter { it.latency != null }.take(5)

            if (top5.isNotEmpty()) {
                appendToBuf(topBuf, getString(R.string.speedtest_best_dns), COLOR_GOLD)
                appendToBuf(topBuf, "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", COLOR_GOLD)
                appendToBuf(topBuf, "", COLOR_WHITE)

                for ((i, r) in top5.withIndex()) {
                    val medal = podiumEmojis.getOrElse(i) { "${i + 1}." }
                    val color = when (i) {
                        0 -> COLOR_GOLD
                        1 -> 0xFFC0C0C0.toInt() // silver
                        2 -> 0xFFCD7F32.toInt() // bronze
                        else -> COLOR_WHITE
                    }
                    val protoColor = protocolColorForLabel(r.type)
                    appendToBuf(topBuf, "$medal ${r.provider}", color)
                    appendTwoPart(topBuf, "   (", r.type, protoColor, ") ${r.latency} ms", color)
                    appendToBuf(topBuf, "", COLOR_WHITE)
                }

                if (final) {
                    appendToBuf(topBuf, "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", COLOR_GREEN)
                    appendToBuf(topBuf, getString(R.string.speedtest_recommended), COLOR_GREEN)
                    val recProtoColor = protocolColorForLabel(top5[0].type)
                    appendToBuf(topBuf, top5[0].provider, COLOR_GOLD)
                    appendTwoPart(topBuf, "(", top5[0].type, recProtoColor, ") ${top5[0].latency} ms", COLOR_GOLD)
                }
            }

            // \u2500\u2500 Full ranking (sub-column droite) \u2500\u2500
            appendToBuf(fullBuf, if (final) getString(R.string.speedtest_full_ranking) else getString(R.string.speedtest_partial_ranking), COLOR_CYAN)
            appendToBuf(fullBuf, "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", COLOR_CYAN)
            for ((i, r) in sorted.withIndex()) {
                val latencyStr = if (r.latency != null) "${r.latency} ms" else getString(R.string.result_error)
                val color = if (r.latency != null) {
                    when {
                        r.latency < 50 -> COLOR_GREEN
                        r.latency < 100 -> COLOR_YELLOW
                        r.latency < 200 -> COLOR_ORANGE
                        else -> COLOR_RED
                    }
                } else COLOR_GREY
                val protoColor = protocolColorForLabel(r.type)
                appendTwoPart(fullBuf, "${String.format("%2d", i + 1)}. ${r.provider} (", r.type, protoColor, ")", color)
                appendToBuf(fullBuf, "    $latencyStr", color)
            }

            tvTopRanking.text = topBuf
            tvFullRanking.text = fullBuf
            scrollTopRanking.post { scrollTopRanking.scrollTo(0, 0) }
            scrollFullRanking.post { scrollFullRanking.scrollTo(0, 0) }
        }
    }

    /**
     * Build the left panel with expandable provider groups.
     * Each provider is a header; clicking it expands/collapses the results underneath.
     */
    private fun buildProviderPanel(results: List<SpeedResult>, final: Boolean) {
        // Save current focus state so we don't steal focus during rebuild
        val focusedView = currentFocus
        val focusInPanel = focusedView != null &&
                (isDescendantOf(focusedView, layoutProviders) || isDescendantOf(focusedView, scrollProviders))
        val savedFocusIndex = if (focusInPanel) {
            (0 until layoutProviders.childCount).firstOrNull {
                layoutProviders.getChildAt(it) == focusedView || layoutProviders.getChildAt(it).findFocus() != null
            }
        } else null

        // Block descendant focus during rebuild to prevent random focus jumps
        val prevFocusability = layoutProviders.descendantFocusability
        layoutProviders.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        layoutProviders.removeAllViews()

        // Group results by provider name (preserve order of first appearance)
        val grouped = linkedMapOf<String, MutableList<SpeedResult>>()
        for (r in results) {
            grouped.getOrPut(r.provider) { mutableListOf() }.add(r)
        }

        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val dp6 = (6 * resources.displayMetrics.density).toInt()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp2 = (2 * resources.displayMetrics.density).toInt()

        for ((provider, providerResults) in grouped) {
            val isExpanded = provider in expandedProviders

            // Count successes and find best latency
            val successCount = providerResults.count { it.latency != null }
            val bestLatency = providerResults.mapNotNull { it.latency }.minOrNull()
            val bestColor = if (bestLatency != null) {
                when {
                    bestLatency < 50 -> COLOR_GREEN
                    bestLatency < 100 -> COLOR_YELLOW
                    bestLatency < 200 -> COLOR_ORANGE
                    else -> COLOR_RED
                }
            } else COLOR_GREY

            // Header row
            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp2
                    bottomMargin = dp2
                }
                setPadding(dp6, dp4, dp6, dp4)
                setBackgroundResource(R.drawable.focusable_item_background)
                isClickable = true
                isFocusable = true
            }

            val arrow = if (isExpanded) "\u25BC " else "\u25B6 "
            val headerText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "$arrow$provider"
                setTextColor(COLOR_WHITE)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }

            val summaryText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val latencyLabel = if (bestLatency != null) "${bestLatency}ms" else "---"
                text = "($successCount/${providerResults.size}) $latencyLabel"
                setTextColor(bestColor)
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }

            headerLayout.addView(headerText)
            headerLayout.addView(summaryText)

            headerLayout.setOnClickListener {
                if (provider in expandedProviders) {
                    expandedProviders.remove(provider)
                } else {
                    expandedProviders.add(provider)
                }
                buildProviderPanel(results, final)
            }

            layoutProviders.addView(headerLayout)

            // Detail rows (only if expanded)
            if (isExpanded) {
                for (r in providerResults) {
                    val detailLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(dp8 + dp6, dp2, dp6, dp2)
                        setBackgroundResource(R.drawable.focusable_item_background)
                        isFocusable = true
                    }

                    val typeColor = when (r.type) {
                        "DoH" -> DnsColors.colorForType(DnsType.DOH)
                        "DoQ" -> DnsColors.colorForType(DnsType.DOQ)
                        "DoT" -> DnsColors.colorForType(DnsType.DOT)
                        else -> DnsColors.colorForType(DnsType.DEFAULT)
                    }

                    val typeLabel = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp8 }
                        text = r.type
                        setTextColor(typeColor)
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTypeface(typeface, Typeface.BOLD)
                    }

                    val latencyColor = if (r.latency != null) {
                        when {
                            r.latency < 50 -> COLOR_GREEN
                            r.latency < 100 -> COLOR_YELLOW
                            r.latency < 200 -> COLOR_ORANGE
                            else -> COLOR_RED
                        }
                    } else COLOR_GREY

                    val latencyLabel = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = if (r.latency != null) "${r.latency} ms" else getString(R.string.result_error)
                        setTextColor(latencyColor)
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                    }

                    detailLayout.addView(typeLabel)
                    detailLayout.addView(latencyLabel)
                    layoutProviders.addView(detailLayout)
                }
            }
        }

        // Title at the top
        if (grouped.isNotEmpty()) {
            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                val title = if (final) "Fournisseurs (final)" else "Fournisseurs..."
                text = title
                setTextColor(COLOR_CYAN)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }
            layoutProviders.addView(titleView, 0)
        }

        // Restore descendant focusability
        layoutProviders.descendantFocusability = prevFocusability

        // Restore focus: if it was inside the panel, go back to the same index
        // Otherwise do NOT touch focus at all — user may be on btnStartStop or btnBack
        if (focusInPanel && savedFocusIndex != null) {
            val targetIndex = savedFocusIndex.coerceAtMost(layoutProviders.childCount - 1)
            if (targetIndex >= 0) {
                layoutProviders.getChildAt(targetIndex)?.post {
                    layoutProviders.getChildAt(targetIndex)?.requestFocus()
                }
            }
        }
    }

    private fun isDescendantOf(view: View, parent: View): Boolean {
        var v: android.view.ViewParent? = view.parent
        while (v != null) {
            if (v === parent) return true
            v = v.parent
        }
        return false
    }

    private fun appendToBuf(buf: SpannableStringBuilder, text: String, color: Int) {
        val start = buf.length
        buf.append(text)
        buf.append("\n")
        buf.setSpan(
            ForegroundColorSpan(color),
            start,
            start + text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    /**
     * Append a line with a colored portion in the middle:
     * [prefix][colored middle][suffix] all on one line with newline at end.
     * prefix and suffix use baseColor; middle uses middleColor.
     */
    private fun appendTwoPart(buf: SpannableStringBuilder, prefix: String, middle: String, middleColor: Int, suffix: String, baseColor: Int) {
        val start = buf.length
        buf.append(prefix)
        buf.setSpan(ForegroundColorSpan(baseColor), start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val midStart = buf.length
        buf.append(middle)
        buf.setSpan(ForegroundColorSpan(middleColor), midStart, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val sufStart = buf.length
        buf.append(suffix)
        buf.append("\n")
        buf.setSpan(ForegroundColorSpan(baseColor), sufStart, buf.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun protocolColorForLabel(typeLabel: String): Int = when (typeLabel) {
        "DoH" -> DnsColors.colorForType(DnsType.DOH)
        "DoQ" -> DnsColors.colorForType(DnsType.DOQ)
        "DoT" -> DnsColors.colorForType(DnsType.DOT)
        else -> DnsColors.colorForType(DnsType.DEFAULT)
    }

    /**
     * Update the compact progress area (tvCurrentTest) with the last 3 lines.
     */
    private fun appendProgress(text: String, color: Int) {
        runOnUiThread {
            recentProgressLines.add(Pair(text, color))
            // Keep only the last 3 lines
            while (recentProgressLines.size > 3) {
                recentProgressLines.removeAt(0)
            }
            // Build a SpannableStringBuilder with the last 3 lines
            val ssb = SpannableStringBuilder()
            for ((i, pair) in recentProgressLines.withIndex()) {
                val start = ssb.length
                ssb.append(pair.first)
                ssb.setSpan(
                    ForegroundColorSpan(pair.second),
                    start,
                    ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (i < recentProgressLines.size - 1) {
                    ssb.append("\n")
                }
            }
            tvCurrentTest.text = ssb
        }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }
}
