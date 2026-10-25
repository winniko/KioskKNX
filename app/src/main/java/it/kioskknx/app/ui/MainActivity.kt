package it.kioskknx.app.ui

import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import it.kioskknx.app.CallUiState
import it.kioskknx.app.KioskBus
import it.kioskknx.app.UiCommand
import it.kioskknx.app.config.AppConfig
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.display.NightMode
import it.kioskknx.app.display.ScreenController
import it.kioskknx.app.display.ScreenWake
import it.kioskknx.app.kiosk.KioskManager
import it.kioskknx.app.kiosk.Watchdog
import it.kioskknx.app.motion.MotionDetector
import it.kioskknx.app.service.KioskCoreService
import it.kioskknx.app.util.HttpActions
import it.kioskknx.app.util.L
import it.kioskknx.app.web.GeckoHolder
import it.kioskknx.app.web.WebController
import it.kioskknx.app.kiosk.Launcher
import it.kioskknx.app.sip.SipManager
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

/** Activity unica del pannello: WebView KNX + overlay chiamata + popup telecamera + salvaschermo. */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var web: WebController? = null
    private var callOverlay: CallOverlay? = null
    private var cameraPopup: CameraPopup? = null
    private var screen: ScreenController? = null
    private var motion: MotionDetector? = null
    private var pausedView: View? = null
    private var initialized = false
    private var lastCall: CallUiState = CallUiState.Idle
    private var dialer: DialerOverlay? = null
    private var pendingPopup: UiCommand.CameraPopup? = null
    private var pendingPopupUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { web?.goBack() }
    }

    override fun onResume() {
        super.onResume()
        KioskBus.activityVisible.value = true
        Watchdog.maintenanceUntil = 0
        if (!ConfigRepository.disclaimerAccepted) {
            startActivity(Intent(this, DisclaimerActivity::class.java))
            return
        }
        if (!ConfigRepository.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        if (ConfigRepository.exitRequested) {
            showPausedScreen()
            return
        }
        if (!initialized) initApp()
        KioskManager.enterKiosk(this)
        hideSystemBars()
        KioskCoreService.start(this, KioskCoreService.ACTION_PROMOTE)
        screen?.userActivity()
        updateMotion(ConfigRepository.current)
    }

    override fun onPause() {
        super.onPause()
        KioskBus.activityVisible.value = false
    }

    override fun onDestroy() {
        motion?.release()
        cameraPopup?.close()
        callOverlay?.release()
        screen?.release()
        web?.destroy()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            L.w("Main", "Memoria critica: ricarico la pagina")
            web?.reload()
        }
    }

    // ------------------------------------------------------------------ inizializzazione

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun initApp() {
        initialized = true
        pausedView?.let { root.removeView(it) }
        pausedView = null

        val webContainer = FrameLayout(this)
        root.addView(webContainer, FrameLayout.LayoutParams(MATCH, MATCH))
        web = WebController(this, webContainer).also { it.create(); it.loadHome() }
        callOverlay = CallOverlay(this, root, onKeypad = { dialer?.open(duringCall = true) })
        cameraPopup = CameraPopup(this, root)
        dialer = DialerOverlay(this, root, beforeCall = { motion?.stop() }, openSettings = { openSettings() })
        screen = ScreenController(this, root) // ultimo: deve stare sopra a tutto

        lifecycleScope.launch { KioskBus.commands.collect { handle(it) } }
        lifecycleScope.launch {
            KioskBus.callState.collect { s ->
                if (s !is CallUiState.Idle) {
                    cameraPopup?.close()
                    dialer?.close()
                    screen?.wake()
                    // Durante le chiamate la fotocamera serve a linphone (videochiamate): fermo il sensore.
                    motion?.stop()
                } else if (lastCall !is CallUiState.Idle) {
                    updateMotion(ConfigRepository.current)
                }
                callOverlay?.render(s)
                if (s is CallUiState.Idle && lastCall !is CallUiState.Idle) {
                    if (ConfigRepository.current.web.reloadAfterCall) web?.reload()
                    screen?.userActivity()
                    hideSystemBars()
                    showPendingPopup()
                }
                lastCall = s
            }
        }
        lifecycleScope.launch { ConfigRepository.config.collect { applyConfig(it) } }
        KioskCoreService.start(this)
        L.i("Main", "Interfaccia avviata")
    }

    private fun applyConfig(cfg: AppConfig) {
        if (KioskBus.webEngine.value == "GECKO" && GeckoHolder.needsRestart(cfg.web) && !SipManager.hasCall) {
            // Gecko legge zoom e dimensione testo solo all'avvio: riavvio l'app.
            L.i("Main", "Zoom pagina cambiato: riavvio per applicarlo al motore Gecko")
            Launcher.scheduleRestart(this, 1500)
            finishAffinity()
            handler.postDelayed({ exitProcess(0) }, 300)
            return
        }
        web?.applySettings(cfg.web)
        if (cfg.display.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen?.applyBrightness()
        updateMotion(cfg)
    }

    private var permissionsAsked = false

    /**
     * Chiede (una volta per avvio) i permessi che servono alle funzioni attive: microfono per il
     * citofono SIP (senza, l'audio della chiamata non parte) e fotocamera per il sensore di movimento.
     * Se l'app è stata installata dal file manager, Android non li concede da solo.
     */
    private fun ensurePermissions(cfg: AppConfig) {
        if (permissionsAsked || !initialized) return
        val missing = mutableListOf<String>()
        fun need(p: String) { if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing += p }
        if (cfg.sip.enabled) need(android.Manifest.permission.RECORD_AUDIO)
        if (cfg.motion.enabled) need(android.Manifest.permission.CAMERA)
        if (missing.isEmpty()) return
        permissionsAsked = true
        L.w("Main", "Permessi mancanti, li richiedo: ${missing.joinToString { it.substringAfterLast('.') }}")
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun updateMotion(cfg: AppConfig) {
        if (!initialized) return
        ensurePermissions(cfg)
        if (cfg.motion.enabled && KioskBus.callState.value !is CallUiState.Idle) return
        if (cfg.motion.enabled && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (cfg.motion.enabled) {
            val m = motion ?: MotionDetector(this) { onMotion() }.also { motion = it }
            m.update(cfg.motion)
            if (!m.running) m.start(cfg.motion)
        } else {
            motion?.stop()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            permissions.forEachIndexed { i, p ->
                L.i("Main", "Permesso ${p.substringAfterLast('.')}: ${if (grantResults.getOrNull(i) == 0) "concesso" else "NEGATO"}")
            }
            updateMotion(ConfigRepository.current)
            // Il servizio può ora dichiarare l'uso del microfono (chiamate a schermo spento).
            KioskCoreService.start(this, KioskCoreService.ACTION_PROMOTE)
        }
    }

    private fun showPendingPopup() {
        val p = pendingPopup ?: return
        pendingPopup = null
        val left = pendingPopupUntil - System.currentTimeMillis()
        if (left <= 0) {
            L.i("Main", "Popup telecamera in coda scaduto durante la chiamata: non mostrato")
            return
        }
        val secs = if (p.seconds > 0) ((left + 999) / 1000).toInt() else 0
        L.i("Main", "Mostro il popup telecamera rimasto in coda (${if (secs > 0) "$secs s" else "senza scadenza"})")
        cameraPopup?.open(p.url, secs, p.title, p.transport)
    }

    private fun onMotion() {
        KioskBus.lastMotionAt.value = System.currentTimeMillis()
        HttpActions.webhook("motion")
        val cfg = ConfigRepository.current
        val allowed = cfg.motion.wakeScreen && (!NightMode.isActive() || cfg.night.motionWake)
        if (allowed) screen?.userActivity()
    }

    private fun handle(cmd: UiCommand) {
        when (cmd) {
            is UiCommand.Reload -> web?.reload()
            is UiCommand.Navigate -> web?.navigate(cmd.url)
            is UiCommand.GoHome -> web?.loadHome()
            is UiCommand.ScreenOn -> { ScreenWake.wake(this); screen?.wake() }
            is UiCommand.Screensaver -> screen?.enterSaver("BLACK")
            is UiCommand.ScreenOff -> screen?.enterSaver("OFF")
            is UiCommand.Brightness -> screen?.setBrightness(cmd.percent)
            is UiCommand.CameraPopup -> {
                if (KioskBus.callState.value is CallUiState.Idle) {
                    ScreenWake.wake(this)
                    screen?.wake()
                    cameraPopup?.open(cmd.url, cmd.seconds, cmd.title, cmd.transport)
                } else {
                    // Chiamata del citofono in corso: il popup va in coda e appare a fine chiamata
                    // (se il suo tempo non è ancora scaduto). La chiamata ha sempre la precedenza.
                    pendingPopup = cmd
                    pendingPopupUntil = if (cmd.seconds > 0) System.currentTimeMillis() + cmd.seconds * 1000L else Long.MAX_VALUE
                    L.i("Main", "Popup telecamera in coda: chiamata SIP in corso")
                }
            }
            is UiCommand.CloseCameraPopup -> cameraPopup?.close()
            is UiCommand.UserActivity -> screen?.userActivity()
            is UiCommand.ApplyConfig -> { applyConfig(ConfigRepository.current); web?.retryNowIfOffline() }
            is UiCommand.BringToFront -> {}
        }
    }

    // ------------------------------------------------------------------ tocco: salvaschermo + angolo segreto

    private val secretRunnable = Runnable {
        cancelSecretHold()
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        askPinAndOpenSettings()
    }

    // ---- conto alla rovescia negli ultimi secondi della pressione sull'angolo segreto
    private var holdEndsAt = 0L
    private var countdownView: TextView? = null

    private fun startSecretHold() {
        val holdMs = ConfigRepository.current.kiosk.settingsHoldSec.coerceAtLeast(2) * 1000L
        holdEndsAt = System.currentTimeMillis() + holdMs
        handler.postDelayed(secretRunnable, holdMs)
        handler.postDelayed(countdownTick, (holdMs - COUNTDOWN_MS).coerceAtLeast(0))
    }

    private fun cancelSecretHold() {
        handler.removeCallbacks(secretRunnable)
        handler.removeCallbacks(countdownTick)
        countdownView?.show(false)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            val left = holdEndsAt - System.currentTimeMillis()
            if (left <= 0) { countdownView?.show(false); return }
            val v = countdownView ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 34f
                gravity = Gravity.CENTER
                setPadding(dp(36), dp(24), dp(36), dp(24))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat(); setColor(Color.argb(220, 21, 101, 192))
                }
                root.addView(this, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                countdownView = this
            }
            v.text = "Impostazioni tra\n${(left + 999) / 1000}"
            v.show(true)
            v.bringToFront()
            handler.postDelayed(this, 200)
        }
    }

    private fun inSecretCorner(x: Float, y: Float): Boolean {
        val size = dp(90)
        val w = root.width
        val h = root.height
        return when (ConfigRepository.current.kiosk.settingsCorner) {
            "TOP_LEFT" -> x < size && y < size
            "TOP_RIGHT" -> x > w - size && y < size
            "BOTTOM_LEFT" -> x < size && y > h - size
            else -> x > w - size && y > h - size
        }
    }

    // ---- apertura del dialer strisciando dal bordo scelto
    private var edgeTracking = false
    private var edgeConsumed = false
    private var edgeStartX = 0f
    private var edgeStartY = 0f

    private fun inEdgeBand(x: Float, y: Float): Boolean {
        val band = dp(40)
        return when (ConfigRepository.current.dialer.edge) {
            "LEFT" -> x < band
            "TOP" -> y < band
            "BOTTOM" -> y > root.height - band
            else -> x > root.width - band
        }
    }

    /** True se il gesto dal bordo verso il centro è abbastanza lungo e nella direzione giusta. */
    private fun edgeSwipeDone(x: Float, y: Float): Boolean {
        val dx = x - edgeStartX
        val dy = y - edgeStartY
        val min = dp(110)
        return when (ConfigRepository.current.dialer.edge) {
            "LEFT" -> dx > min && kotlin.math.abs(dy) < dx
            "TOP" -> dy > min && kotlin.math.abs(dx) < dy
            "BOTTOM" -> -dy > min && kotlin.math.abs(dx) < -dy
            else -> -dx > min && kotlin.math.abs(dy) < -dx
        }
    }

    private fun handleEdgeSwipe(ev: MotionEvent): Boolean {
        val enabled = ConfigRepository.current.dialer.enabled && dialer?.isOpen == false &&
            KioskBus.callState.value is CallUiState.Idle && screen?.isSaverActive != true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeConsumed = false
                edgeTracking = enabled && inEdgeBand(ev.x, ev.y)
                edgeStartX = ev.x
                edgeStartY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (edgeTracking && edgeSwipeDone(ev.x, ev.y)) {
                edgeTracking = false
                edgeConsumed = true
                cancelSecretHold()
                // Annullo il tocco per la pagina sotto, poi apro il dialer.
                super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                dialer?.open()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeTracking = false
        }
        return edgeConsumed
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleEdgeSwipe(ev)) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) edgeConsumed = false
            return true
        }
        if (dialer?.isOpen == true) dialer?.touched()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSecretHold()
                if (inSecretCorner(ev.x, ev.y)) startSecretHold()
            }
            MotionEvent.ACTION_MOVE -> if (!inSecretCorner(ev.x, ev.y)) cancelSecretHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelSecretHold()
        }
        val s = screen
        if (s != null && s.isSaverActive) {
            // Il primo tocco sveglia soltanto lo schermo, senza premere nulla sotto.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) s.wake()
            return true
        }
        s?.userActivity()
        return super.dispatchTouchEvent(ev)
    }

    private fun askPinAndOpenSettings() {
        val pin = ConfigRepository.current.kiosk.pin
        if (pin.isBlank()) return openSettings()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle("Impostazioni protette")
            .setView(input)
            .setPositiveButton("Entra") { _, _ ->
                if (input.text.toString() == pin) openSettings() else toast("PIN errato")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openSettings() {
        Watchdog.maintenanceUntil = System.currentTimeMillis() + 30 * 60_000L
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------------ app "in pausa" dopo Esci

    private fun showPausedScreen() {
        if (pausedView != null) return
        KioskManager.exitKiosk(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 22, 28))
            isClickable = true
        }
        box.addView(label("KioskKNX è in pausa", 30f, bold = true))
        box.addView(label("L'app è stata chiusa dal menu impostazioni.", 18f, Color.LTGRAY).apply {
            setPadding(0, dp(12), 0, dp(24))
        })
        val lp = { LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        box.addView(pillButton("Riavvia KioskKNX", Color.rgb(46, 160, 67)) {
            ConfigRepository.exitRequested = false
            root.removeView(box)
            pausedView = null
            recreate()
        }, lp())
        box.addView(pillButton("Scegli un altro launcher", Color.rgb(21, 101, 192)) {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, lp())
        box.addView(pillButton("Impostazioni Android", Color.rgb(84, 110, 122)) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }, lp())
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        pausedView = box
    }

    companion object {
        /** Il conto alla rovescia compare negli ultimi 5 secondi. */
        private const val COUNTDOWN_MS = 5_000L
        private const val REQ_PERMS = 41
    }
}
