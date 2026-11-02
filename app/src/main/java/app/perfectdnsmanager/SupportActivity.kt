package app.perfectdnsmanager

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.perfectdnsmanager.util.LocaleHelper

/** The website owns the donation form; the app only hosts it and links phones to it. */
class SupportActivity : AppCompatActivity() {
    private var web: WebView? = null
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }
    private fun openBrowser(uri: Uri) {
        if (uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)
        val base = Uri.parse(BuildConfig.PDM_BASE_URL)
        val page = base.buildUpon().path("/support.html")
            .appendQueryParameter("lang", (androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]?.language ?: "en")).build()
        findViewById<Button>(R.id.btnBack).apply { setOnClickListener { finish() }; requestFocus() }
        findViewById<TextView>(R.id.supportPageUrl).text = base.host + "/support.html"
        findViewById<ImageButton>(R.id.supportPageQr).apply {
            setImageResource(if (base.host == "beta.perfectdnsmanager.app") R.drawable.qr_support_beta else R.drawable.qr_support_stable)
            setOnClickListener { openBrowser(page) }
        }
        // Some TV firmware omits WebView. The QR/browser link remains available there.
        val browser = runCatching { WebView(this) }.getOrNull() ?: return
        web = browser
        browser.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = Uri.parse(url)
                if (uri.scheme == "https" && uri.host == base.host && uri.port == base.port && uri.userInfo == null) return false
                if (url == "https://sideshift.ai/legal") openBrowser(uri)
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val trusted = uri.scheme == "https" && uri.host == base.host && uri.port == base.port && uri.userInfo == null
                if (trusted) return false
                if (request.isForMainFrame && request.hasGesture()) openBrowser(uri)
                return true
            }
        }
        findViewById<FrameLayout>(R.id.supportWebContainer).addView(browser,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        browser.loadUrl(page.buildUpon().appendQueryParameter("embed", "1").build().toString())
    }
    override fun onDestroy() {
        web?.let { (it.parent as? FrameLayout)?.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }
}
