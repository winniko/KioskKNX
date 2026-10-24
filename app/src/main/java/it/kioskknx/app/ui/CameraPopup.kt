package it.kioskknx.app.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.kioskknx.app.KioskBus
import it.kioskknx.app.R
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.util.HttpActions
import it.kioskknx.app.video.StreamViewer

/** Popup telecamera aperto da /api/camera-popup (senza chiamata SIP). */
class CameraPopup(private val activity: AppCompatActivity, root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.rgb(20, 20, 20))
        isClickable = true
        visibility = View.GONE
    }
    private val title: TextView = activity.label("Telecamera", 24f, bold = true)
    private val videoBox = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val viewer = StreamViewer(activity, videoBox)
    private val closeRunnable = Runnable { close() }
    private val status: TextView = activity.label("", 20f, Color.GRAY).apply { gravity = Gravity.CENTER }

    init {
        viewer.onFirstFrame = { status.show(false) }
        viewer.onError = { e -> status.text = "Video non disponibile: $e"; status.show(true) }
        // Errore definitivo: messaggio fisso; il popup si chiude comunque alla scadenza di "seconds".
        viewer.onGiveUp = { e -> status.text = e; status.show(true) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(RoundButton(activity, R.drawable.ic_close, Color.rgb(211, 47, 47), "Chiudi", 64).apply { onClick { close() } })
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        val videoFrame = FrameLayout(activity)
        videoFrame.addView(videoBox, FrameLayout.LayoutParams(MATCH, MATCH))
        videoFrame.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        col.addView(videoFrame, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })
        col.addView(HorizontalScrollView(activity).apply { addView(buttons) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        view.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** [transport]: "tcp" | "udp" | "auto"; vuoto = impostazione dell'app. */
    fun open(url: String, seconds: Int, caption: String, transport: String = "") {
        main.removeCallbacks(closeRunnable)
        title.text = caption
        status.text = "Connessione al video..."
        status.show(true)
        buildButtons()
        view.show(true)
        view.bringToFront()
        KioskBus.cameraPopupOpen.value = true
        val tr = transport.ifBlank { if (ConfigRepository.current.video.rtspOverTcp) "tcp" else "udp" }
        viewer.start(url, tr)
        if (seconds > 0) main.postDelayed(closeRunnable, seconds * 1000L)
    }

    fun close() {
        main.removeCallbacks(closeRunnable)
        viewer.stop()
        view.show(false)
        KioskBus.cameraPopupOpen.value = false
    }

    private fun buildButtons() {
        buttons.removeAllViews()
        val lp = { LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = activity.dp(10) } }
        ConfigRepository.current.locks.forEachIndexed { i, l ->
            if (l.enabled && l.http.url.isNotBlank()) {
                buttons.addView(activity.pillButton("🔓  ${l.label}", parseColor(l.color, Color.rgb(21, 101, 192))) {
                    SipManager.openLock(i) { _, msg -> activity.toast(msg) }
                }, lp())
            }
        }
        ConfigRepository.current.buttons.filter { it.showInCameraPopup && it.request.url.isNotBlank() }.forEach { b ->
            val text = listOf(b.icon, b.label).filter { it.isNotBlank() }.joinToString("  ")
            buttons.addView(activity.pillButton(text, parseColor(b.color, Color.DKGRAY)) {
                HttpActions.fire(b.request) { r -> activity.toast(if (r.ok) "${b.label}: OK" else "${b.label}: errore ${r.code}") }
            }, lp())
        }
    }
}
