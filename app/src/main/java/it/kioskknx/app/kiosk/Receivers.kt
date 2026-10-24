package it.kioskknx.app.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import it.kioskknx.app.KioskBus
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.display.ScreenWake
import it.kioskknx.app.service.KioskCoreService
import it.kioskknx.app.ui.MainActivity
import it.kioskknx.app.util.L

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        L.i("Kiosk", "Amministratore dispositivo attivato")
    }
}

/** Avvio automatico al boot e dopo un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        L.i("Boot", "Evento ${intent.action}")
        val isUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (isUpdate) ConfigRepository.exitRequested = false
        if (ConfigRepository.exitRequested) return
        if (!isUpdate && !ConfigRepository.current.kiosk.autoStartOnBoot) return
        KioskCoreService.start(context)
        Launcher.launchMain(context)
        Watchdog.schedule(context)
    }
}

object Launcher {
    fun launchMain(context: Context) {
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            L.w("Boot", "Avvio Activity non consentito in background", e)
        }
    }

    /** Riavvia l'app dopo [delayMs] (usato dopo un crash o da /api/restart). */
    fun scheduleRestart(context: Context, delayMs: Long) {
        val pi = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        context.getSystemService(AlarmManager::class.java)
            ?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    }
}

/** Battito ogni 5 minuti: riavvia servizio e Activity se il sistema li ha chiusi. */
object Watchdog {
    private const val INTERVAL = 5 * 60_000L

    fun schedule(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, 2, Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pi)
    }

    /** Durante la manutenzione (impostazioni Android aperte) il watchdog non riporta in primo piano l'app. */
    @Volatile var maintenanceUntil = 0L
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConfigRepository.exitRequested) return
        Watchdog.schedule(context)
        KioskCoreService.start(context)
        val inMaintenance = System.currentTimeMillis() < Watchdog.maintenanceUntil
        // Solo a schermo acceso: a display spento (modalità OFF) non va riacceso.
        if (!KioskBus.activityVisible.value && !inMaintenance && ScreenWake.isInteractive(context)) {
            L.i("Watchdog", "Activity non visibile: la riporto in primo piano")
            Launcher.launchMain(context)
        }
    }
}
