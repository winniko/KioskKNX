package it.kioskknx.app.web

import android.content.Context
import android.graphics.Color
import android.view.View
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.WebConfig
import it.kioskknx.app.util.L
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * Runtime Gecko: uno solo per processo. Zoom pagina e dimensione testo vengono letti alla creazione,
 * quindi per cambiarli serve riavviare l'app (lo fa il menu impostazioni).
 */
object GeckoHolder {
    private var runtime: GeckoRuntime? = null
    var createdZoom = 0
        private set
    var createdTextZoom = 0
        private set

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val web = ConfigRepository.current.web
        val density = context.resources.displayMetrics.density
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(false)
            .remoteDebuggingEnabled(false)
            .aboutConfigEnabled(false)
            .inputAutoZoomEnabled(false)
            .doubleTapZoomingEnabled(web.allowZoom)
            .fontSizeFactor(web.textZoom.coerceIn(30, 300) / 100f)
            .displayDensityOverride(density * web.pageZoom.coerceIn(30, 300) / 100f)
            .build()
        createdZoom = web.pageZoom
        createdTextZoom = web.textZoom
        L.i("Gecko", "Avvio motore Gecko (zoom ${web.pageZoom}%)")
        return GeckoRuntime.create(context.applicationContext, settings).also { runtime = it }
    }

    /** True se zoom/testo sono cambiati rispetto al runtime già creato (serve riavvio app). */
    fun needsRestart(web: WebConfig) = runtime != null && (web.pageZoom != createdZoom || web.textZoom != createdTextZoom)
}

/** Motore Gecko (Firefox) integrato nell'app: moderno e indipendente dalla WebView del dispositivo. */
class GeckoWebEngine(context: Context, private val listener: WebEngineListener) : WebEngine {
    private val geckoView = GeckoView(context)
    override val view: View get() = geckoView
    override val kind = "GECKO"
    private val session: GeckoSession
    private var canGoBack = false
    private var currentUrl = ""
    private var destroyed = false

    init {
        val web = ConfigRepository.current.web
        val runtime = GeckoHolder.get(context)
        val sb = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        if (web.userAgentSuffix.isNotBlank()) sb.userAgentOverride("${GeckoSession.getDefaultUserAgent()} ${web.userAgentSuffix}")
        session = GeckoSession(sb.build())

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(s: GeckoSession, value: Boolean) {
                canGoBack = value
            }

            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) {
                if (url != null) currentUrl = url
            }

            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                val msg = describe(error)
                L.w("Gecko", "Errore caricamento $uri: $msg")
                listener.onMainFrameError(msg)
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                currentUrl = url
                listener.onPageStarted(url)
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (success) listener.onPageFinished(currentUrl)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(s: GeckoSession) {
                L.w("Gecko", "Processo Gecko in crash")
                listener.onEngineDied()
            }

            override fun onKill(s: GeckoSession) {
                L.w("Gecko", "Processo Gecko terminato dal sistema")
                listener.onEngineDied()
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            // Autoplay audio/video, notifiche, ecc.: tutto consentito (pagina locale di fiducia).
            override fun onContentPermissionRequest(s: GeckoSession, perm: ContentPermission): GeckoResult<Int> =
                GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)

            override fun onMediaPermissionRequest(
                s: GeckoSession, uri: String, video: Array<out MediaSource>?, audio: Array<out MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            }

            override fun onAndroidPermissionsRequest(s: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) {
                callback.grant()
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAuthPrompt(s: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse>? {
                val w = ConfigRepository.current.web
                return if (w.httpAuthUser.isNotBlank()) GeckoResult.fromValue(prompt.confirm(w.httpAuthUser, w.httpAuthPassword))
                else GeckoResult.fromValue(prompt.dismiss())
            }
        }
        session.open(runtime)
        geckoView.setSession(session)
        geckoView.setBackgroundColor(Color.BLACK)
    }

    private fun describe(e: WebRequestError): String = when (e.code) {
        WebRequestError.ERROR_CONNECTION_REFUSED -> "Connessione rifiutata dal server"
        WebRequestError.ERROR_UNKNOWN_HOST -> "Indirizzo del server sconosciuto"
        WebRequestError.ERROR_NET_TIMEOUT -> "Il server non risponde (timeout)"
        WebRequestError.ERROR_OFFLINE -> "Rete non disponibile"
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "Connessione interrotta"
        WebRequestError.ERROR_PORT_BLOCKED -> "Porta bloccata per sicurezza dal motore Gecko: usa un'altra porta oppure il motore \"WebView di sistema\""
        WebRequestError.ERROR_REDIRECT_LOOP -> "Il server rimanda la pagina all'infinito (redirect loop)"
        WebRequestError.ERROR_MALFORMED_URI, WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "Indirizzo della pagina non valido"
        WebRequestError.ERROR_CONTENT_CRASHED -> "La pagina si è chiusa per un errore"
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL ->
            "Certificato HTTPS non valido (self-signed).\nCon il motore Gecko usa l'indirizzo http:// del server, oppure scegli il motore \"WebView di sistema\"."
        else -> "Errore di caricamento (codice ${e.code})"
    }

    override fun load(url: String) = session.loadUri(url)

    override fun reload() {
        if (currentUrl.isBlank() || currentUrl == "about:blank") load(ConfigRepository.current.web.url) else session.reload()
    }

    override fun goBack(): Boolean {
        if (!canGoBack) return false
        session.goBack()
        return true
    }

    override fun applySettings(web: WebConfig) {
        // Zoom e testo richiedono il riavvio dell'app (vedi GeckoHolder.needsRestart).
    }

    /** Gecko gestisce da solo i blocchi degli script; i crash arrivano da onCrash/onKill. */
    override fun ping(onAlive: () -> Unit) = onAlive()

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
    }
}
