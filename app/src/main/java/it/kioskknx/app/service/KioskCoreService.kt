package it.kioskknx.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.kioskknx.app.KioskBus
import it.kioskknx.app.R
import it.kioskknx.app.UiCommand
import it.kioskknx.app.api.ApiServer
import it.kioskknx.app.config.ApiConfig
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.SipConfig
import it.kioskknx.app.display.NightMode
import it.kioskknx.app.kiosk.Watchdog
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.ui.MainActivity
import it.kioskknx.app.util.L
import it.kioskknx.app.util.NetRouter
import it.kioskknx.app.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Servizio in primo piano sempre attivo: mantiene vivi SIP, server API e i timer (reload notturno, notte).
 */
class KioskCoreService : Service() {
    companion object {
        private const val TAG = "Servizio"
        private const val CHANNEL = "kiosk_core"
        private const val NOTIF_ID = 1001
        const val ACTION_PROMOTE = "it.kioskknx.PROMOTE"
        const val ACTION_STOP = "it.kioskknx.STOP"

        @Volatile var running = false
            private set

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, KioskCoreService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                L.w(TAG, "Avvio servizio non consentito ora", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KioskCoreService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var api: ApiServer? = null
    private var lastApiCfg: ApiConfig? = null
    private var lastSipCfg: SipConfig? = null
    private var lastAudio: Any? = null
    private var lastNet: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        goForeground()
        NetworkMonitor.start(this)
        NetRouter.start(this)
        NetRouter.addListener {
            SipManager.onNetworkChanged(true)
            it.kioskknx.app.web.GeckoHolder.applyProxy()
        }
        NetworkMonitor.addListener { up ->
            SipManager.onNetworkChanged(up)
            if (up) {
                ensureApi(force = api?.isAlive != true)
                KioskBus.send(UiCommand.ApplyConfig)
            }
        }
        scope.launch {
            ConfigRepository.config.collect { cfg ->
                if (cfg.network != lastNet) {
                    lastNet = cfg.network
                    NetRouter.apply()
                }
                if (cfg.sip != lastSipCfg || cfg.audio != lastAudio) {
                    lastSipCfg = cfg.sip
                    lastAudio = cfg.audio
                    SipManager.start()
                }
                ensureApi(force = cfg.api != lastApiCfg)
            }
        }
        scope.launch { tickerLoop() }
        Watchdog.schedule(this)
        L.i(TAG, "Servizio avviato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROMOTE -> goForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        api?.stop()
        api = null
        SipManager.stop()
        L.i(TAG, "Servizio fermato")
        super.onDestroy()
    }

    private fun ensureApi(force: Boolean) {
        val cfg = ConfigRepository.current.api
        if (!force && api?.isAlive == true) return
        api?.stop()
        api = null
        lastApiCfg = cfg
        if (!cfg.enabled) return
        try {
            api = ApiServer(applicationContext, cfg.port).also { it.start(5000, true) }
            L.i(TAG, "Server API in ascolto sulla porta ${cfg.port}")
        } catch (e: Exception) {
            L.e(TAG, "Server API non avviato sulla porta ${cfg.port}", e)
        }
    }

    /** Ogni 30 s: reload notturno, reload periodico, transizioni modalità notte, salute server API. */
    private suspend fun tickerLoop() {
        var lastNightlyDay = -1
        var lastPeriodic = System.currentTimeMillis()
        var wasNight = NightMode.isActive()
        while (true) {
            delay(30_000)
            val w = ConfigRepository.current.watchdog
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val today = cal.get(Calendar.DAY_OF_YEAR)
            if (w.nightlyReloadEnabled && NightMode.minutes(w.nightlyReloadTime) == nowMin && lastNightlyDay != today) {
                lastNightlyDay = today
                L.i(TAG, "Reload notturno della pagina")
                KioskBus.send(UiCommand.Reload)
            }
            if (w.periodicReloadMin > 0 && System.currentTimeMillis() - lastPeriodic > w.periodicReloadMin * 60_000L) {
                lastPeriodic = System.currentTimeMillis()
                if (!SipManager.hasCall) KioskBus.send(UiCommand.Reload)
            }
            val night = NightMode.isActive()
            if (night != wasNight) {
                wasNight = night
                L.i(TAG, if (night) "Modalità notte attiva" else "Modalità notte terminata")
                KioskBus.send(UiCommand.ApplyConfig)
            }
            if (ConfigRepository.current.api.enabled && api?.isAlive != true) ensureApi(force = true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    /**
     * Avvio in primo piano. Il tipo "microphone" è consentito solo con l'app visibile:
     * se non è possibile (es. al boot) si parte solo con "specialUse" e si promuove dopo.
     */
    private fun goForeground() {
        val notif = buildNotification()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val mic = if (Build.VERSION.SDK_INT >= 30 && micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, base or mic)
        } catch (e: Exception) {
            L.w(TAG, "Foreground con microfono non consentito ora, riprovo senza", e)
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, base)
            } catch (e2: Exception) {
                L.e(TAG, "Impossibile avviare il servizio in primo piano", e2)
            }
        }
    }
}
