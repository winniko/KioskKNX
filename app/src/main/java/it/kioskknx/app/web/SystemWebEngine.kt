package it.kioskknx.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.WebConfig
import it.kioskknx.app.util.L

/** Motore basato sulla WebView di sistema (Chromium del dispositivo). */
@SuppressLint("SetJavaScriptEnabled")
class SystemWebEngine(private val context: Context, private val listener: WebEngineListener) : WebEngine {
    private val wv = WebView(context) // può lanciare eccezione se la WebView di sistema non è disponibile
    override val view: View get() = wv
    override val kind = "SYSTEM"
    private var destroyed = false

    init {
        wv.setBackgroundColor(Color.BLACK)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        // Niente menu "Copia/Condividi" da pressione lunga (kiosk e angolo segreto).
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isHapticFeedbackEnabled = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = client()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                wv.post { request.grant(request.resources) }
            }
        }
        applySettings(ConfigRepository.current.web)
        if (ConfigRepository.current.web.clearCacheOnStart) wv.clearCache(true)
    }

    @Suppress("DEPRECATION")
    override fun applySettings(web: WebConfig) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(web.allowZoom)
            builtInZoomControls = web.allowZoom
            displayZoomControls = false
            textZoom = web.textZoom.coerceIn(30, 300)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            val base = WebSettings.getDefaultUserAgent(context)
            userAgentString = if (web.userAgentSuffix.isBlank()) base else "$base ${web.userAgentSuffix}"
        }
        applyPageZoom()
    }

    /** Zoom dell'intera pagina via CSS: le pagine responsive (es. Home Assistant) si riadattano. */
    private fun applyPageZoom() {
        val z = ConfigRepository.current.web.pageZoom.coerceIn(30, 300) / 100f
        wv.evaluateJavascript("document.documentElement.style.zoom='$z';", null)
    }

    override fun load(url: String) = wv.loadUrl(url)

    override fun reload() {
        if (wv.url.isNullOrBlank() || wv.url == "about:blank") load(ConfigRepository.current.web.url) else wv.reload()
    }

    override fun goBack(): Boolean {
        if (!wv.canGoBack()) return false
        wv.goBack()
        return true
    }

    override fun ping(onAlive: () -> Unit) = wv.evaluateJavascript("1") { onAlive() }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { wv.stopLoading(); wv.destroy() }
    }

    private fun client() = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = listener.onPageStarted(url)

        override fun onPageFinished(view: WebView, url: String) {
            applyPageZoom()
            listener.onPageFinished(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            L.w(TAG, "Errore caricamento: ${error.description} (${error.errorCode})")
            listener.onMainFrameError("Errore: ${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame || response.statusCode < 500) return
            listener.onMainFrameError("Il server ha risposto con errore HTTP ${response.statusCode}")
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (ConfigRepository.current.web.ignoreSslErrors) handler.proceed() else handler.cancel()
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            val w = ConfigRepository.current.web
            if (w.httpAuthUser.isNotBlank()) handler.proceed(w.httpAuthUser, w.httpAuthPassword) else handler.cancel()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return true
            return scheme !in setOf("http", "https", "about", "data", "blob", "javascript")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val crashed = if (Build.VERSION.SDK_INT >= 26) detail.didCrash() else true
            L.w(TAG, "Processo di rendering terminato (crash=$crashed)")
            listener.onEngineDied()
            return true
        }
    }

    companion object {
        private const val TAG = "Web"
    }
}
