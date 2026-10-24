package it.kioskknx.app.display

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import it.kioskknx.app.kiosk.KioskManager
import it.kioskknx.app.util.L

/** Accensione/spegnimento fisico del display. */
object ScreenWake {
    fun isInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** Accende il display anche se spento (wake lock con ACQUIRE_CAUSES_WAKEUP). */
    @Suppress("DEPRECATION")
    fun wake(context: Context) {
        try {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "kioskknx:wake"
            )
            wl.acquire(5_000)
        } catch (e: Exception) {
            L.w("Display", "Wake fallito", e)
        }
    }

    /** Spegne il display (serve l'app come amministratore/Device Owner). */
    fun lockNow(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return if (dpm.isAdminActive(KioskManager.admin(context))) {
            dpm.lockNow(); true
        } else false
    }
}
