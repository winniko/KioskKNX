package it.kioskknx.app.display

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import it.kioskknx.app.CallUiState
import it.kioskknx.app.KioskBus
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.ui.MATCH
import it.kioskknx.app.ui.WRAP
import it.kioskknx.app.ui.show
import kotlin.random.Random

/** Inattività, salvaschermo (nero con orologio anti burn-in), luminosità e spegnimento. */
class ScreenController(private val activity: Activity, root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private val saver = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        visibility = View.GONE
    }
    private val clockBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private var lastActivity = System.currentTimeMillis()
    private var lastClockMove = 0L
    private var forcedBrightness: Int? = null

    /** "on" | "dim" | "screensaver" | "off" */
    var state = "on"
        private set
    val isSaverActive: Boolean get() = state != "on"

    init {
        clockBox.addView(TextClock(activity).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 96f
            setTextColor(Color.rgb(90, 90, 90))
        })
        clockBox.addView(TextClock(activity).apply {
            format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"
            textSize = 26f
            setTextColor(Color.rgb(70, 70, 70))
            gravity = Gravity.CENTER
        })
        saver.addView(clockBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(saver, FrameLayout.LayoutParams(MATCH, MATCH))
        main.post { tick.run() }
    }

    private val tick = object : Runnable {
        override fun run() {
            check()
            main.postDelayed(this, 1000)
        }
    }

    private fun blocked() = KioskBus.callState.value !is CallUiState.Idle || KioskBus.cameraPopupOpen.value

    private fun check() {
        val d = ConfigRepository.current.display
        val now = System.currentTimeMillis()
        if (blocked()) {
            lastActivity = now
            if (state != "on") wake()
            return
        }
        if (state == "on" && d.screensaverEnabled && now - lastActivity > d.timeoutSec.coerceAtLeast(10) * 1000L) {
            enterSaver(d.mode)
        }
        if (state == "screensaver" && now - lastClockMove > 60_000) moveClock()
    }

    fun userActivity() {
        lastActivity = System.currentTimeMillis()
        if (state != "on") wake()
    }

    fun wake() {
        lastActivity = System.currentTimeMillis()
        saver.show(false)
        state = "on"
        KioskBus.screenState.value = "on"
        applyBrightness()
    }

    fun enterSaver(mode: String) {
        val d = ConfigRepository.current.display
        when (mode.uppercase()) {
            "DIM" -> {
                saver.show(false)
                state = "dim"
                setWindowBrightness(d.dimLevel)
            }
            "OFF" -> {
                if (ScreenWake.lockNow(activity)) {
                    state = "off"
                } else {
                    enterSaver("BLACK"); return
                }
            }
            else -> {
                clockBox.show(d.showClock)
                saver.show(true)
                moveClock()
                state = "screensaver"
                setWindowBrightness(if (d.showClock) 3 else 0)
            }
        }
        KioskBus.screenState.value = state
    }

    /** Luminosità impostata via API (0-100); rimane finché non viene cambiata. */
    fun setBrightness(percent: Int) {
        forcedBrightness = percent
        if (state != "on") wake() else applyBrightness()
    }

    fun applyBrightness() {
        val d = ConfigRepository.current.display
        var level = forcedBrightness ?: d.activeBrightness
        if (NightMode.isActive()) {
            val cap = ConfigRepository.current.night.maxBrightness
            level = if (level < 0) cap else minOf(level, cap)
        }
        KioskBus.brightness.value = level
        setWindowBrightness(level)
    }

    private fun setWindowBrightness(percent: Int) {
        val lp = activity.window.attributes
        lp.screenBrightness = if (percent < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else (percent / 100f).coerceIn(0.01f, 1f)
        activity.window.attributes = lp
    }

    /** Sposta l'orologio in una posizione casuale (anti burn-in). */
    private fun moveClock() {
        lastClockMove = System.currentTimeMillis()
        saver.post {
            val maxX = (saver.width - clockBox.width).coerceAtLeast(0)
            val maxY = (saver.height - clockBox.height).coerceAtLeast(0)
            clockBox.translationX = (Random.nextInt(maxX + 1) - maxX / 2).toFloat()
            clockBox.translationY = (Random.nextInt(maxY + 1) - maxY / 2).toFloat()
        }
    }

    fun release() = main.removeCallbacksAndMessages(null)
}
