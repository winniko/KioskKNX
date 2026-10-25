package it.kioskknx.app.web

import android.content.Context
import android.view.View
import it.kioskknx.app.config.WebConfig

/** Motore di rendering della pagina: WebView di sistema oppure Gecko (Firefox) integrato. */
interface WebEngine {
    val view: View
    /** "SYSTEM" | "GECKO" */
    val kind: String

    fun load(url: String)
    fun reload()
    fun goBack(): Boolean
    fun applySettings(web: WebConfig)
    /** Watchdog: chiama [onAlive] se il motore risponde. */
    fun ping(onAlive: () -> Unit)
    fun destroy()
}

/** Eventi del motore verso [WebController]. */
interface WebEngineListener {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onMainFrameError(message: String)
    /** Processo di rendering terminato o in crash: il motore va ricreato. */
    fun onEngineDied()
}

object WebEngines {
    /** Motore effettivo per la configurazione: in AUTO usa Gecko se la WebView di sistema è vecchia. */
    fun resolve(context: Context, web: WebConfig): String = when {
        it.kioskknx.app.config.ConfigRepository.current.network.let { n -> n.dualMode && n.webNetwork != n.sipNetwork } -> "GECKO"
        else -> resolveEngine(context, web)
    }

    private fun resolveEngine(context: Context, web: WebConfig): String = when (web.engine) {
        "GECKO" -> "GECKO"
        "SYSTEM" -> "SYSTEM"
        else -> if (WebViewInfo.get(context).major < WebViewInfo.MIN_RECOMMENDED) "GECKO" else "SYSTEM"
    }

    fun create(context: Context, kind: String, listener: WebEngineListener): WebEngine =
        if (kind == "GECKO") GeckoWebEngine(context, listener) else SystemWebEngine(context, listener)
}
