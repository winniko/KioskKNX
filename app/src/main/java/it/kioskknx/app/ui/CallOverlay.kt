package it.kioskknx.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.kioskknx.app.CallUiState
import it.kioskknx.app.KioskBus
import it.kioskknx.app.R
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.history.CallHistory
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.util.HttpActions
import it.kioskknx.app.video.StreamViewer
import kotlinx.coroutines.launch

/** Overlay a schermo intero della chiamata del videocitofono. */
@SuppressLint("ClickableViewAccessibility")
class CallOverlay(private val activity: AppCompatActivity, root: FrameLayout, private val onKeypad: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.rgb(12, 14, 18))
        isClickable = true
        visibility = View.GONE
    }
    private val title: TextView = activity.label("Chiamata in arrivo", 28f, bold = true)
    private val status: TextView = activity.label("", 20f, Color.LTGRAY)
    private val videoBox = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val sipTexture = TextureView(activity)
    private val fallbackBox = FrameLayout(activity)
    /** Anteprima della propria fotocamera, solo nelle videochiamate fatte dal dialer. */
    private val preview = org.linphone.mediastream.video.capture.CaptureTextureView(activity).apply { visibility = View.GONE }
    private val videoMsg: TextView = activity.label("In attesa del video...", 20f, Color.GRAY).apply { gravity = Gravity.CENTER }
    private val customRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val fallback = StreamViewer(activity, fallbackBox)

    private val btnLock1 = RoundButton(activity, R.drawable.ic_lock_open, Color.rgb(21, 101, 192), "Serratura 1")
    private val btnLock2 = RoundButton(activity, R.drawable.ic_lock_open, Color.rgb(21, 101, 192), "Serratura 2")
    private val btnPtt = RoundButton(activity, R.drawable.ic_mic, Color.rgb(96, 96, 96), "Tieni per parlare")
    private val btnKeypad = RoundButton(activity, R.drawable.ic_dialpad, Color.rgb(84, 110, 122), "Tastiera")
    private val btnAnswer = RoundButton(activity, R.drawable.ic_call, Color.rgb(46, 160, 67), "Rispondi", 96)
    private val btnHangup = RoundButton(activity, R.drawable.ic_call_end, Color.rgb(211, 47, 47), "Chiudi", 96)

    private var state: CallUiState = CallUiState.Idle
    private var activeSince = 0L
    private var usingFallback = false
    private var outgoingCall = false

    init {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        }
        val header = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        header.addView(title)
        header.addView(status)
        col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        videoBox.addView(sipTexture, FrameLayout.LayoutParams(MATCH, MATCH))
        videoBox.addView(fallbackBox, FrameLayout.LayoutParams(MATCH, MATCH))
        videoBox.addView(videoMsg, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        videoBox.addView(preview, FrameLayout.LayoutParams(activity.dp(200), activity.dp(150), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, activity.dp(12), activity.dp(12))
        })
        col.addView(videoBox, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })

        val scroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(customRow) }
        col.addView(scroll, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val controls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(btnLock1, btnLock2, btnPtt, btnKeypad, btnAnswer, btnHangup).forEach { controls.addView(it) }
        col.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(8) })
        view.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        btnAnswer.onClick { SipManager.accept() }
        btnKeypad.onClick { onKeypad() }
        btnHangup.onClick { SipManager.hangup() }
        btnLock1.onClick { openLock(0) }
        btnLock2.onClick { openLock(1) }
        btnPtt.circle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { SipManager.setMicMuted(false); btnPtt.setColor(Color.rgb(46, 160, 67)); btnPtt.label.text = "Parla..." }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { SipManager.setMicMuted(true); btnPtt.setColor(Color.rgb(96, 96, 96)); btnPtt.label.text = "Tieni per parlare" }
            }
            true
        }

        activity.lifecycleScope.launch {
            KioskBus.sipVideoReceived.collect { received -> if (received) onSipVideo() }
        }
        fallback.netUse = it.kioskknx.app.util.NetRouter.Use.SIP
        fallback.onFirstFrame = {
            videoMsg.show(false)
            main.postDelayed({ fallback.snapshot()?.let { CallHistory.saveBitmap(it) } }, 800)
        }
    }

    fun render(s: CallUiState) {
        val prev = state
        state = s
        when (s) {
            is CallUiState.Idle -> hide()
            is CallUiState.Incoming -> {
                if (prev is CallUiState.Idle) showForNewCall()
                title.text = "Chiamata dal citofono"
                status.text = s.remote
                btnAnswer.show(true)
                btnPtt.show(false)
                btnKeypad.show(true)
                btnHangup.label.text = "Rifiuta"
                updateLocks(answered = false)
            }
            is CallUiState.Outgoing -> {
                if (prev is CallUiState.Idle) showForNewCall(outgoing = true)
                title.text = if (s.video) "Videochiamata in corso..." else "Chiamata in corso..."
                status.text = s.remote
                videoMsg.text = if (s.video) "In attesa di risposta..." else "Chiamata audio"
                if (s.video && preview.visibility != View.VISIBLE) {
                    preview.show(true)
                    SipManager.setPreviewView(preview)
                }
                btnAnswer.show(false)
                btnPtt.show(false)
                btnKeypad.show(false)
                btnHangup.label.text = "Chiudi"
                listOf(btnLock1, btnLock2).forEach { it.show(false) }
            }
            is CallUiState.Active -> {
                if (prev is CallUiState.Idle) showForNewCall()
                activeSince = s.startedAt
                title.text = "In conversazione"
                btnAnswer.show(false)
                btnPtt.show(ConfigRepository.current.audio.pushToTalk)
                btnKeypad.show(true)
                btnHangup.label.text = "Chiudi"
                // Serrature solo per le chiamate del citofono, non per quelle fatte dal dialer.
                if (outgoingCall) listOf(btnLock1, btnLock2).forEach { it.show(false) } else updateLocks(answered = true)
                if (!KioskBus.sipVideoReceived.value) {
                    videoMsg.text = if (outgoingCall && preview.visibility != View.VISIBLE) "Chiamata audio" else "In attesa del video..."
                    videoMsg.show(true)
                }
                main.removeCallbacks(timer)
                main.post(timer)
                if (!ConfigRepository.current.video.keepFallbackAfterAnswer && KioskBus.sipVideoReceived.value) stopFallback()
            }
        }
    }

    private val timer = object : Runnable {
        override fun run() {
            if (state !is CallUiState.Active) return
            val sec = (System.currentTimeMillis() - activeSince) / 1000
            status.text = String.format("%s  -  %02d:%02d", (state as CallUiState.Active).remote, sec / 60, sec % 60)
            main.postDelayed(this, 1000)
        }
    }

    private fun showForNewCall(outgoing: Boolean = false) {
        outgoingCall = outgoing
        view.show(true)
        view.bringToFront()
        buildCustomButtons()
        videoMsg.text = "In attesa del video..."
        videoMsg.show(true)
        usingFallback = false
        SipManager.setVideoView(sipTexture)
        val v = ConfigRepository.current.video
        if (!outgoing && v.fallbackUrl.isNotBlank()) {
            when (v.fallbackMode) {
                "ALWAYS" -> startFallback()
                "AUTO" -> main.postDelayed(fallbackCheck, v.fallbackDelayMs.toLong().coerceAtLeast(0))
            }
        }
    }

    private val fallbackCheck = Runnable {
        if (state !is CallUiState.Idle && !KioskBus.sipVideoReceived.value) startFallback()
    }

    private fun startFallback() {
        val v = ConfigRepository.current.video
        usingFallback = true
        fallbackBox.show(true)
        fallback.start(v.fallbackUrl, if (v.rtspOverTcp) "tcp" else "udp")
    }

    private fun stopFallback() {
        usingFallback = false
        fallback.stop()
        fallbackBox.show(false)
    }

    private fun onSipVideo() {
        if (state is CallUiState.Idle) return
        videoMsg.show(false)
        main.removeCallbacks(fallbackCheck)
        val keep = ConfigRepository.current.video.fallbackMode == "ALWAYS" ||
            (state is CallUiState.Active && ConfigRepository.current.video.keepFallbackAfterAnswer)
        if (usingFallback && !keep) stopFallback()
    }

    private fun hide() {
        main.removeCallbacks(fallbackCheck)
        main.removeCallbacks(timer)
        stopFallback()
        SipManager.setVideoView(null)
        if (preview.visibility == View.VISIBLE) {
            preview.show(false)
            SipManager.setPreviewView(null)
        }
        view.show(false)
    }

    private fun updateLocks(answered: Boolean) {
        val locks = ConfigRepository.current.locks
        listOf(btnLock1, btnLock2).forEachIndexed { i, b ->
            val l = locks.getOrNull(i)
            val visible = l != null && l.enabled && (answered || l.quickOpen || l.http.url.isNotBlank())
            b.show(visible)
            if (l != null) {
                b.label.text = l.label
                b.setColor(parseColor(l.color, Color.rgb(21, 101, 192)))
            }
        }
    }

    private fun openLock(i: Int) {
        SipManager.openLock(i) { _, msg -> activity.toast(msg) }
    }

    private fun buildCustomButtons() {
        customRow.removeAllViews()
        ConfigRepository.current.buttons.filter { it.showInCall && it.request.url.isNotBlank() }.forEach { b ->
            val text = listOf(b.icon, b.label).filter { it.isNotBlank() }.joinToString("  ")
            customRow.addView(activity.pillButton(text, parseColor(b.color, Color.DKGRAY)) {
                HttpActions.fire(b.request) { r -> activity.toast(if (r.ok) "${b.label}: OK" else "${b.label}: errore ${r.code}") }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = activity.dp(10) })
        }
    }

    fun release() {
        hide()
    }
}
