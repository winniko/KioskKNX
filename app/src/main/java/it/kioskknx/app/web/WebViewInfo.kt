package it.kioskknx.app.web

import android.content.Context
import android.os.Build
import android.webkit.WebView

/** Versione del componente WebView di sistema (le dashboard moderne, es. Home Assistant + HACS, richiedono Chromium recente). */
object WebViewInfo {
    /** Versione minima consigliata del motore Chromium. */
    const val MIN_RECOMMENDED = 110

    data class Info(val packageName: String, val version: String, val major: Int)

    fun get(context: Context): Info {
        val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        val name = pkg?.packageName ?: "sconosciuto"
        val version = pkg?.versionName ?: userAgentVersion(context)
        val major = version.substringBefore('.').toIntOrNull() ?: 0
        return Info(name, version, major)
    }

    private fun userAgentVersion(context: Context): String = try {
        Regex("Chrome/([0-9.]+)").find(android.webkit.WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1) ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun isOutdated(context: Context) = get(context).major in 1 until MIN_RECOMMENDED
}
