package it.kioskknx.app.web

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.kioskknx.app.KioskBus
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.WebConfig
import it.kioskknx.app.ui.MATCH
import it.kioskknx.app.ui.WRAP
import it.kioskknx.app.ui.dp
import it.kioskknx.app.ui.label
import it.kioskknx.app.ui.pillButton
import it.kioskknx.app.ui.show
import it.kioskknx.app.util.DeviceInfo
import it.kioskknx.app.util.L
import it.kioskknx.app.util.NetworkMonitor

/**
 * Pagina del supervisore KNX, indipendente dal motore ([WebEngine]): pagina offline con ritentativi
 * automatici, watchdog di blocco e ricreazione del motore in caso di crash.
 */
class WebController(private val activity: AppCompatActivity, private val container: FrameLayout) : WebEngineListener {
    private val main = Handler(Looper.getMainLooper())
    private var engine: WebEngine? = null
    private val offline: LinearLayout
    private val offlineDetail: TextView
    private var mainFrameError = false
    private var retryDelaySec = 10
    private var lastUrl = ""
    private var appliedWeb: WebConfig? = null
    private var freezeMisses = 0

    init {
        offline = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 22, 28))
            isClickable = true
            visibility = View.GONE
        }
        offline.addView(activity.label("Server KNX non raggiungibile", 28f, bold = true).apply { gravity = Gravity.CENTER })
        offlineDetail = activity.label("", 17f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(24), activity.dp(16), activity.dp(24), activity.dp(24))
        }
        offline.addView(offlineDetail)
        offline.addView(activity.pillButton("Riprova ora", Color.rgb(21, 101, 192)) { retryNow() }, LinearLayout.LayoutParams(WRAP, WRAP))
        container.addView(offline, FrameLayout.LayoutParams(MATCH, MATCH))
        main.postDelayed({ freezeCheck.run() }, 60_000)
    }

    // ------------------------------------------------------------------ creazione

    fun create() {
        destroyEngine()
        val web = ConfigRepository.current.web
        val kind = WebEngines.resolve(activity, web)
        val e = try {
            WebEngines.create(activity, kind, this)
        } catch (ex: Throwable) {
            L.e(TAG, "Motore web $kind non disponibile", ex)
            if (kind == "SYSTEM") {
                // WebView di sistema assente o in aggiornamento: ripiego su Gecko integrato.
                runCatching { WebEngines.create(activity, "GECKO", this) }.getOrNull()
            } else null
        }
        if (e == null) {
            showOffline("Motore web non disponibile.\nNuovo tentativo tra poco.")
            main.postDelayed({ create(); loadHome() }, 15_000)
            return
        }
        engine = e
        appliedWeb = web
        KioskBus.webEngine.value = e.kind
        container.addView(e.view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        L.i(TAG, "Motore web: ${if (e.kind == "GECKO") "Gecko (integrato)" else "WebView di sistema"}")
    }

    fun applySettings(web: WebConfig) {
        val prev = appliedWeb ?: return
        if (prev == web) return
        appliedWeb = web
        if (WebEngines.resolve(activity, web) != engine?.kind) {
            create()
            loadHome()
            return
        }
        engine?.applySettings(web)
        if (prev.url != web.url) loadHome()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        destroyEngine()
    }

    private fun destroyEngine() {
        engine?.let {
            container.removeView(it.view)
            it.destroy()
        }
        engine = null
    }

    // ------------------------------------------------------------------ navigazione

    fun loadHome() = navigate(ConfigRepository.current.web.url)

    fun navigate(url: String) {
        if (url.isBlank()) {
            showOffline("Nessun indirizzo configurato.\nTieni premuto ${ConfigRepository.current.kiosk.settingsHoldSec} secondi nell'angolo per aprire le impostazioni.")
            return
        }
        lastUrl = url
        mainFrameError = false
        engine?.load(url)
    }

    fun reload() {
        val e = engine ?: return create().also { loadHome() }
        mainFrameError = false
        e.reload()
    }

    fun goBack(): Boolean = engine?.goBack() ?: false

    fun retryNowIfOffline() {
        if (offline.visibility == View.VISIBLE) retryNow()
    }

    private fun retryNow() {
        main.removeCallbacks(retryRunnable)
        if (engine == null) create()
        navigate(lastUrl.ifBlank { ConfigRepository.current.web.url })
    }

    private val retryRunnable = Runnable { retryNow() }

    private fun scheduleRetry() {
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, retryDelaySec * 1000L)
        retryDelaySec = (retryDelaySec * 2).coerceAtMost(60)
    }

    private fun showOffline(reason: String) {
        val net = if (NetworkMonitor.connected.value) "Rete: ${DeviceInfo.networkType(activity)} - IP ${DeviceInfo.ipAddress()}" else "Rete: NON CONNESSA"
        offlineDetail.text = "$reason\n\nIndirizzo: ${lastUrl.ifBlank { ConfigRepository.current.web.url }}\n$net\n\nNuovo tentativo automatico tra $retryDelaySec s"
        offline.show(true)
        KioskBus.pageLoaded.value = false
    }

    private fun hideOffline() {
        offline.show(false)
        retryDelaySec = ConfigRepository.current.watchdog.offlineRetrySec.coerceIn(3, 60)
    }

    // ------------------------------------------------------------------ eventi motore

    override fun onPageStarted(url: String) {
        KioskBus.currentUrl.value = url
        KioskBus.pageLoaded.value = false
    }

    override fun onPageFinished(url: String) {
        if (!mainFrameError) {
            hideOffline()
            KioskBus.pageLoaded.value = true
        }
    }

    override fun onMainFrameError(message: String) {
        mainFrameError = true
        showOffline(message)
        scheduleRetry()
    }

    override fun onEngineDied() {
        main.post {
            L.w(TAG, "Motore web terminato: lo ricreo")
            create()
            navigate(lastUrl.ifBlank { ConfigRepository.current.web.url })
        }
    }

    // ------------------------------------------------------------------ watchdog blocco

    private val freezeCheck = object : Runnable {
        override fun run() {
            main.postDelayed(this, 60_000)
            if (!ConfigRepository.current.watchdog.freezeCheckEnabled) return
            val e = engine ?: return
            if (!KioskBus.activityVisible.value) return
            freezeMisses++
            e.ping { freezeMisses = 0 }
            if (freezeMisses >= 3) {
                L.w(TAG, "Pagina bloccata: ricreo il motore web")
                freezeMisses = 0
                create()
                navigate(lastUrl.ifBlank { ConfigRepository.current.web.url })
            }
        }
    }

    companion object {
        private const val TAG = "Web"
    }
}
