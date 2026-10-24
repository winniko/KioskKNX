package it.kioskknx.app

import android.app.Application
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.history.CallHistory
import it.kioskknx.app.kiosk.Launcher
import it.kioskknx.app.media.SoundManager
import it.kioskknx.app.media.TtsManager
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.util.L
import kotlin.system.exitProcess

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Il motore Gecko gira in processi separati (":tab", ":gpu", ...): lì non va avviato nulla di nostro.
        if (!isMainProcess()) return
        ConfigRepository.init(this)
        CallHistory.init(this)
        SoundManager.init(this)
        TtsManager.init(this)
        SipManager.init(this)
        installCrashHandler()
        L.i("App", "KioskKNX ${BuildConfig.VERSION_NAME} avviata")
    }

    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
        return name == packageName
    }

    /** In caso di crash l'app si riavvia da sola dopo 3 secondi. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            L.e("Crash", "Errore non gestito su ${t.name}", e)
            try {
                if (!ConfigRepository.exitRequested) Launcher.scheduleRestart(this, 3000)
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(t, e) else exitProcess(2)
        }
    }
}
