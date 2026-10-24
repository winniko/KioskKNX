package it.kioskknx.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import it.kioskknx.app.BuildConfig
import it.kioskknx.app.KioskBus
import it.kioskknx.app.UiCommand
import it.kioskknx.app.config.ApiConfig
import it.kioskknx.app.config.AppConfig
import it.kioskknx.app.config.AudioConfig
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.CustomButton
import it.kioskknx.app.config.DisplayConfig
import it.kioskknx.app.config.HttpRequestConfig
import it.kioskknx.app.config.KioskConfig
import it.kioskknx.app.config.LockConfig
import it.kioskknx.app.config.MotionConfig
import it.kioskknx.app.config.NightConfig
import it.kioskknx.app.config.SipConfig
import it.kioskknx.app.config.VideoConfig
import it.kioskknx.app.config.WatchdogConfig
import it.kioskknx.app.config.WebConfig
import it.kioskknx.app.config.WebhookConfig
import it.kioskknx.app.kiosk.KioskManager
import it.kioskknx.app.kiosk.Launcher
import it.kioskknx.app.kiosk.Watchdog
import it.kioskknx.app.media.SoundManager
import it.kioskknx.app.media.TtsManager
import it.kioskknx.app.service.KioskCoreService
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.update.AppUpdater
import it.kioskknx.app.util.DeviceInfo
import it.kioskknx.app.util.L
import it.kioskknx.app.web.GeckoHolder
import it.kioskknx.app.web.WebEngines
import it.kioskknx.app.web.WebViewInfo
import kotlin.system.exitProcess

/** Menu impostazioni segreto. */
class SettingsActivity : KioskActivity() {
    private lateinit var content: LinearLayout
    private lateinit var collect: () -> AppConfig
    private var buttonsDraft = mutableListOf<CustomButton>()
    private var buttonGetters = mutableListOf<() -> CustomButton>()
    private lateinit var buttonsBox: LinearLayout

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(ConfigRepository.exportJson().toByteArray()) }
            toast("Configurazione esportata")
        } catch (e: Exception) {
            toast("Errore esportazione: ${e.message}")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
        val err = if (text == null) "file illeggibile" else ConfigRepository.importJson(text)
        if (err == null) { toast("Configurazione importata"); recreate() } else toast("Errore importazione: $err")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setBackgroundColor(Color.rgb(30, 38, 48))
        }
        bar.addView(label("Impostazioni KioskKNX", 22f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(pillButton("Annulla", Color.rgb(84, 110, 122)) { finish() })
        bar.addView(pillButton("Salva", Color.rgb(46, 160, 67)) { save() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        outer.addView(bar)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(48))
        }
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(outer)
        build()
    }

    override fun onResume() {
        super.onResume()
        Watchdog.maintenanceUntil = System.currentTimeMillis() + 30 * 60_000L
    }

    private fun save() {
        val cfg = collect()
        ConfigRepository.save(cfg)
        if (WebEngines.resolve(this, cfg.web) == "GECKO" && GeckoHolder.needsRestart(cfg.web)) {
            // Il motore Gecko legge zoom e dimensione testo solo all'avvio.
            toast("Impostazioni salvate: riavvio l'app per applicare lo zoom")
            Launcher.scheduleRestart(this, 1500)
            finishAffinity()
            window.decorView.postDelayed({ exitProcess(0) }, 300)
            return
        }
        KioskBus.send(UiCommand.ApplyConfig)
        toast("Impostazioni salvate")
        finish()
    }

    // ------------------------------------------------------------------ costruzione

    private fun build() {
        content.removeAllViews()
        val c = ConfigRepository.current
        val f = Form(this, content)

        // ---- Info
        f.section("Stato del pannello", expanded = true)
        val ip = DeviceInfo.ipAddress()
        val wv = WebViewInfo.get(this)
        val dm = resources.displayMetrics
        val cssW = (maxOf(dm.widthPixels, dm.heightPixels) / dm.density / (c.web.pageZoom / 100f)).toInt()
        val cssH = (minOf(dm.widthPixels, dm.heightPixels) / dm.density / (c.web.pageZoom / 100f)).toInt()
        f.note(
            "Versione ${BuildConfig.VERSION_NAME}  -  IP $ip (${DeviceInfo.networkType(this)})\n" +
                "Software libero (GNU AGPL v3) - codice sorgente: ${BuildConfig.SOURCE_URL}\n" +
                "API: http://$ip:${c.api.port}/\n" +
                "Device Owner: ${if (KioskManager.isDeviceOwner(this)) "SÌ" else "no"}  -  Blocco kiosk: ${if (KioskBus.lockTaskActive.value) "attivo" else "non attivo"}\n" +
                "SIP: ${KioskBus.sipRegistration.value}  -  Sintesi vocale: ${if (TtsManager.ready) (if (TtsManager.italianAvailable) "OK" else "manca la voce italiana") else "non disponibile"}\n" +
                "Schermo: ${maxOf(dm.widthPixels, dm.heightPixels)}x${minOf(dm.widthPixels, dm.heightPixels)} pixel, ${dm.densityDpi} dpi -> la pagina web vede ${cssW}x${cssH} (con lo zoom attuale)\n" +
                (if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    "⚠️ Permesso FOTOCAMERA mancante: sensore di movimento e videochiamate non funzionano (Sistema → Permessi)\n" else "") +
                "Motore web in uso: ${if (KioskBus.webEngine.value == "GECKO") "Gecko / Firefox (integrato nell'app)" else "WebView di sistema"}\n" +
                "WebView di sistema: ${wv.packageName} versione ${wv.version}" +
                if (wv.major in 1 until WebViewInfo.MIN_RECOMMENDED) "  (vecchia: con motore \"Automatico\" si usa Gecko)" else "  (OK)"
        )

        // ---- Web
        f.section("Pagina web (supervisore KNX)")
        val wUrl = f.text("Indirizzo della pagina", c.web.url, "http://192.168.1.10/visu")
        val wEngine = f.choice("Motore web", listOf(
            "AUTO" to "Automatico (Gecko se la WebView del tablet è vecchia)",
            "GECKO" to "Gecko / Firefox integrato (sempre moderno)",
            "SYSTEM" to "WebView di sistema del tablet",
        ), c.web.engine)
        f.note("Gecko non accetta certificati HTTPS self-signed: per i server in rete locale usa l'indirizzo http://. " +
            "Con Gecko, le modifiche a zoom e testo riavviano l'app.")
        val wReload = f.switch("Ricarica la pagina dopo ogni chiamata", c.web.reloadAfterCall)
        val wSsl = f.switch("Ignora errori certificato HTTPS (rete locale)", c.web.ignoreSslErrors)
        val wUser = f.text("Utente autenticazione HTTP (facoltativo)", c.web.httpAuthUser)
        val wPass = f.text("Password autenticazione HTTP", c.web.httpAuthPassword, password = true)
        val wPageZoom = f.int("Zoom pagina (%) - ingrandisce tutta la pagina, es. 125 o 150", c.web.pageZoom)
        val wZoom = f.int("Dimensione solo testo (%)", c.web.textZoom)
        val wAllowZoom = f.switch("Consenti zoom con le dita", c.web.allowZoom)
        val wClear = f.switch("Svuota la cache a ogni avvio", c.web.clearCacheOnStart)
        val wUa = f.text("Suffisso User-Agent", c.web.userAgentSuffix)
        f.buttons("Ricarica ora" to { KioskBus.send(UiCommand.Reload); toast("Ricarico la pagina") })

        // ---- SIP
        f.section("Videocitofono SIP")
        val sEn = f.switch("SIP attivo", c.sip.enabled)
        f.note("Chiamata diretta IP: sul citofono imposta come destinazione sip:pannello@IP_DEL_TABLET:${c.sip.localPort}. " +
            "Con centralino: attiva la registrazione e inserisci i dati dell'interno.")
        val sDirect = f.switch("Accetta chiamate dirette IP (senza centralino)", c.sip.directIpCallsEnabled)
        val sAllowed = f.text("IP dei citofoni autorizzati (vuoto = tutti)", c.sip.allowedIps, "192.168.1.50, 192.168.1.51")
        val sLocalPort = f.int("Porta SIP locale del tablet", c.sip.localPort, "5062")
        val sReg = f.switch("Registra su centralino (PBX)", c.sip.registerEnabled)
        val sServer = f.text("Server / IP centralino", c.sip.server)
        val sPort = f.int("Porta centralino", c.sip.serverPort)
        val sUser = f.text("Utente / interno", c.sip.username)
        val sPass = f.text("Password", c.sip.password, password = true)
        val sAuth = f.text("ID autenticazione (se diverso dall'utente)", c.sip.authUsername)
        val sName = f.text("Nome visualizzato", c.sip.displayName)
        val sTr = f.choice("Trasporto", listOf("UDP" to "UDP", "TCP" to "TCP"), c.sip.transport)
        val sEarly = f.switch("Video prima di rispondere (early media SIP)", c.sip.earlyMediaEnabled)
        val sAuto = f.switch("Risposta automatica", c.sip.autoAnswer)
        val sRingT = f.int("Durata massima squillo (s)", c.sip.ringTimeoutSec)
        val sMaxDur = f.int("Durata massima conversazione (s)", c.sip.maxCallDurationSec)
        val sDtmf = f.choice("Modalità DTMF", listOf(
            "RFC2833" to "RFC 2833 (consigliato)", "SIP_INFO" to "SIP INFO", "INBAND" to "In banda (audio)", "RFC2833_INFO" to "RFC 2833 + SIP INFO"
        ), c.sip.dtmfMode)
        val sVideoOut = f.switch("Invia il video della fotocamera del tablet", c.sip.sendVideo)
        val sKeep = f.switch("Keep-alive di rete", c.sip.keepAlive)
        val sExp = f.int("Scadenza registrazione (s)", c.sip.registerExpiresSec)
        val sDebug = f.switch("Log dettagliati SIP (diagnostica)", c.sip.debugLogs)

        // ---- Video fallback
        f.section("Video pre-risposta (RTSP / MJPEG)")
        f.note("Mostrato subito quando suona il citofono, se il video SIP non arriva.\n" +
            "Hikvision: rtsp://admin:password@IP:554/Streaming/Channels/101  oppure  rtsp://admin:password@IP:554/ch1/main/av_stream\n" +
            "2N: rtsp://IP:554/h264_stream  oppure MJPEG  http://utente:password@IP/api/camera/snapshot?width=640&height=480&fps=10")
        val vUrl = f.text("URL video", c.video.fallbackUrl, "rtsp://...")
        val vMode = f.choice("Quando usarlo", listOf(
            "AUTO" to "Automatico (se il video SIP non arriva)", "ALWAYS" to "Sempre", "NEVER" to "Mai"
        ), c.video.fallbackMode)
        val vDelay = f.int("Attesa video SIP prima del fallback (ms)", c.video.fallbackDelayMs)
        val vTcp = f.switch("RTSP su TCP (più affidabile)", c.video.rtspOverTcp)
        val vKeep = f.switch("Mantieni il video RTSP anche dopo la risposta", c.video.keepFallbackAfterAnswer)
        f.buttons("Prova video (20 s)" to {
            val u = vUrl()
            if (u.isBlank()) toast("Inserisci prima un URL") else { KioskBus.send(UiCommand.CameraPopup(u, 20, "Prova video")); finish() }
        })

        // ---- Audio
        f.section("Audio e suoneria")
        val aEc = f.switch("Cancellazione dell'eco", c.audio.echoCancellation)
        val aEl = f.switch("Limitatore d'eco (per eco molto forte)", c.audio.echoLimiter)
        val aMic = f.float("Guadagno microfono (dB, es. -6 / 0 / 6)", c.audio.micGainDb)
        val aPlay = f.float("Guadagno altoparlante (dB)", c.audio.playbackGainDb)
        val aPtt = f.switch("Premi per parlare (half-duplex anti-eco)", c.audio.pushToTalk)
        val aRingVol = f.int("Volume suoneria (%)", c.audio.ringVolumePercent)
        val aRing = f.choice("Suoneria", listOf("DEFAULT" to "Suoneria di sistema", "DOORBELL" to "Campanello din-don", "BEEP" to "Bip"), c.audio.ringtone)
        val aTtsVol = f.int("Volume sintesi vocale (%)", c.audio.ttsVolumePercent)
        val aTtsRate = f.float("Velocità voce (1.0 = normale)", c.audio.ttsSpeechRate)
        f.buttons(
            "Prova suoneria" to { SoundManager.play(if (aRing() == "DOORBELL") "doorbell" else if (aRing() == "BEEP") "beep" else "ring", volumePercent = aRingVol()) },
            "Stop" to { SoundManager.stopAll() },
            "Prova voce" to { TtsManager.speak("Prova di sintesi vocale del pannello KNX", aTtsVol(), aTtsRate(), force = true) },
            "Calibra eco" to { toast("Calibrazione: fai silenzio..."); SipManager.calibrateEcho { toast(it) } },
        )

        // ---- Serrature
        f.section("Serrature (apriporta)")
        f.note("DTMF: sequenza inviata al citofono (es. #1, *1, 1234#). In alternativa un comando HTTP " +
            "(es. Shelly http://IP/relay/0?turn=on&timer=2 oppure Hikvision ISAPI PUT con autenticazione Digest).")
        val lockGetters = (0..1).map { i -> lockForm(f, i, c.locks.getOrNull(i) ?: LockConfig(label = "Serratura ${i + 1}", dtmf = "#${i + 1}")) }

        // ---- Pulsanti custom
        f.section("Pulsanti personalizzati (HTTP)")
        f.note("Pulsanti extra nella schermata di chiamata e nel popup telecamera (luci, cancello, scenari KNX, webhook).")
        buttonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.add(buttonsBox)
        buttonsDraft = c.buttons.toMutableList()
        renderButtons()
        f.buttons("+ Aggiungi pulsante" to {
            buttonsDraft = buttonGetters.map { it() }.toMutableList()
            buttonsDraft += CustomButton()
            renderButtons()
        }, color = Color.rgb(21, 101, 192))

        // ---- API
        f.section("Server API locale")
        val apiEn = f.switch("API attive", c.api.enabled)
        val apiPort = f.int("Porta", c.api.port)
        val apiKey = f.text("API key (vuota = nessuna protezione)", c.api.apiKey)
        val apiCfgW = f.switch("Consenti importazione configurazione via API", c.api.allowConfigWrite)
        val apiUpd = f.switch("Consenti aggiornamento app via API", c.api.allowRemoteUpdate)

        // ---- Display
        f.section("Schermo e salvaschermo")
        val dEn = f.switch("Salvaschermo attivo", c.display.screensaverEnabled)
        val dTo = f.int("Dopo quanti secondi di inattività", c.display.timeoutSec)
        val dMode = f.choice("Tipo", listOf(
            "BLACK" to "Schermo nero (con orologio opzionale)", "DIM" to "Abbassa luminosità", "OFF" to "Spegni display (serve Device Owner)"
        ), c.display.mode)
        f.note("Con \"Spegni display\" il sensore di movimento non funziona: usa \"Schermo nero\" se vuoi la riaccensione automatica.")
        val dClock = f.switch("Mostra orologio sul salvaschermo", c.display.showClock)
        val dDim = f.int("Luminosità in modalità attenuata (%)", c.display.dimLevel)
        val dBright = f.int("Luminosità normale (% , -1 = di sistema)", c.display.activeBrightness)
        val dOn = f.switch("Mantieni lo schermo acceso", c.display.keepScreenOn)

        // ---- Motion
        f.section("Sensore di movimento (fotocamera frontale)")
        val mEn = f.switch("Attivo", c.motion.enabled)
        val mSens = f.int("Sensibilità (1-100)", c.motion.sensitivity)
        val mWake = f.switch("Accendi lo schermo quando rileva movimento", c.motion.wakeScreen)
        val mFps = f.int("Analisi al secondo (1-15)", c.motion.analysisFps)
        val mCool = f.int("Pausa tra due rilevamenti (s)", c.motion.cooldownSec)

        // ---- Notte
        f.section("Modalità notte / Non disturbare")
        val nEn = f.switch("Attiva a orario", c.night.enabled)
        val nStart = f.text("Inizio (HH:mm)", c.night.start)
        val nEnd = f.text("Fine (HH:mm)", c.night.end)
        val nVol = f.int("Volume suoneria di notte (%)", c.night.ringVolumePercent)
        val nSilent = f.switch("Suoneria silenziata di notte", c.night.silentRing)
        val nBright = f.int("Luminosità massima di notte (%)", c.night.maxBrightness)
        val nMotion = f.switch("Il movimento accende lo schermo anche di notte", c.night.motionWake)
        val nTts = f.switch("Annunci vocali anche di notte", c.night.ttsEnabled)
        val nOverride = f.choice("Stato attuale", listOf("auto" to "Automatico (orario)", "on" to "Forza notte", "off" to "Forza giorno"), ConfigRepository.nightOverride)

        // ---- Webhook
        f.section("Webhook in uscita (eventi verso la domotica)")
        f.note("Segnaposto disponibili nell'URL: {event} {remote} {lock} {time} {ip}. Con POST viene inviato un JSON con gli stessi campi.")
        val whMethod = f.choice("Metodo", listOf("GET" to "GET", "POST" to "POST (JSON)"), c.webhooks.method)
        val whMotion = f.text("Movimento davanti al pannello", c.webhooks.onMotion)
        val whMotionInt = f.int("Intervallo minimo webhook movimento (s)", c.webhooks.motionMinIntervalSec)
        val whIn = f.text("Chiamata in arrivo", c.webhooks.onCallIncoming)
        val whAns = f.text("Chiamata risposta", c.webhooks.onCallAnswered)
        val whMiss = f.text("Chiamata persa", c.webhooks.onCallMissed)
        val whEnd = f.text("Chiamata terminata", c.webhooks.onCallEnded)
        val whDoor = f.text("Porta aperta", c.webhooks.onDoorOpened)

        // ---- Watchdog
        f.section("Affidabilità (watchdog)")
        val wdNight = f.switch("Ricarica la pagina ogni notte", c.watchdog.nightlyReloadEnabled)
        val wdTime = f.text("Orario ricarica notturna (HH:mm)", c.watchdog.nightlyReloadTime)
        val wdPer = f.int("Ricarica periodica ogni N minuti (0 = mai)", c.watchdog.periodicReloadMin)
        val wdFreeze = f.switch("Ricrea la pagina se si blocca", c.watchdog.freezeCheckEnabled)
        val wdRetry = f.int("Primo ritentativo se il server non risponde (s)", c.watchdog.offlineRetrySec)

        // ---- Kiosk
        f.section("Chiamate in uscita (dialer)")
        f.note("Strisciando il dito dal bordo scelto verso il centro si apre una tastiera per chiamare interni del centralino, " +
            "indirizzi IP (altri pannelli, citofoni) o indirizzi sip:. I numeri rapidi si programmano direttamente dal dialer con " +
            "\"Modifica\" e il codice installatore. Digitando *#CODICE# e premendo Chiama si aprono queste impostazioni.")
        val dEnabled = f.switch("Dialer attivo", c.dialer.enabled)
        val dEdge = f.choice("Bordo da cui aprirlo", listOf(
            "RIGHT" to "Da destra", "LEFT" to "Da sinistra", "TOP" to "Dall'alto", "BOTTOM" to "Dal basso"
        ), c.dialer.edge)
        val dCode = f.text("Codice installatore (vuoto = usa il PIN impostazioni; se vuoti entrambi, nessuna protezione)", c.dialer.installerCode, numeric = true)
        f.note("Numeri rapidi programmati: ${c.dialer.speedDials.size}")

        f.section("Kiosk e sicurezza")
        val kLock = f.switch("Blocco totale (Lock Task) se Device Owner", c.kiosk.lockTaskEnabled)
        val kHold = f.int("Secondi di pressione per aprire le impostazioni", c.kiosk.settingsHoldSec)
        val kCorner = f.choice("Angolo segreto", listOf(
            "BOTTOM_RIGHT" to "In basso a destra", "BOTTOM_LEFT" to "In basso a sinistra", "TOP_RIGHT" to "In alto a destra", "TOP_LEFT" to "In alto a sinistra"
        ), c.kiosk.settingsCorner)
        val kPin = f.text("PIN impostazioni (vuoto = nessuno)", c.kiosk.pin, numeric = true)
        val kBoot = f.switch("Avvio automatico all'accensione", c.kiosk.autoStartOnBoot)

        // ---- Backup
        f.section("Backup configurazione")
        f.buttons(
            "Esporta su file" to { KioskManager.exitKiosk(this); exportLauncher.launch("KioskKNX_config.json") },
            "Importa da file" to { KioskManager.exitKiosk(this); importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            "Mostra / incolla JSON" to { jsonDialog() },
        )

        // ---- Sistema
        f.section("Sistema")
        f.note("\"Disattiva Device Owner\" toglie il blocco kiosk totale e permette di disinstallare l'app senza PC.")
        f.buttons(
            "Storico chiamate" to { startActivity(Intent(this, HistoryActivity::class.java)) },
            "Permessi" to { startActivity(Intent(this, SetupActivity::class.java)) },
            "Log" to { logDialog() },
            "Aggiorna app da URL" to { updateDialog() },
        )
        f.buttons(
            "Impostazioni Wi-Fi / Rete" to { openAndroidSettings(Settings.ACTION_WIFI_SETTINGS) },
            "Impostazioni Android" to { openAndroidSettings(Settings.ACTION_SETTINGS) },
            "Riavvia app" to { Launcher.scheduleRestart(this, 1500); finishAffinity(); exitProcess(0) },
            color = Color.rgb(21, 101, 192),
        )
        f.buttons(
            "Esci dall'App" to { confirm("Chiudere KioskKNX e fermare tutti i servizi?") { exitApp() } },
            "Disattiva Device Owner" to {
                confirm("Rimuovere il Device Owner? Il blocco kiosk verrà disattivato e l'app potrà essere disinstallata.") { disableDeviceOwner() }
            },
            color = Color.rgb(211, 47, 47),
        )

        collect = {
            AppConfig(
                web = WebConfig(wUrl(), wReload(), wSsl(), wUser(), wPass(), wZoom(), wAllowZoom(), wUa(), wClear(), wPageZoom().coerceIn(30, 300), wEngine()),
                sip = SipConfig(
                    enabled = sEn(), registerEnabled = sReg(), server = sServer(), serverPort = sPort(), username = sUser(),
                    password = sPass(), authUsername = sAuth(), displayName = sName(), transport = sTr(), localPort = sLocalPort(),
                    directIpCallsEnabled = sDirect(), allowedIps = sAllowed(), earlyMediaEnabled = sEarly(), autoAnswer = sAuto(),
                    ringTimeoutSec = sRingT(), maxCallDurationSec = sMaxDur(), dtmfMode = sDtmf(), sendVideo = sVideoOut(),
                    registerExpiresSec = sExp(), keepAlive = sKeep(), debugLogs = sDebug(),
                ),
                video = VideoConfig(vUrl(), vMode(), vDelay(), vTcp(), vKeep()),
                locks = lockGetters.map { it() },
                buttons = buttonGetters.map { it() },
                api = ApiConfig(apiEn(), apiPort(), apiKey(), apiCfgW(), apiUpd()),
                display = DisplayConfig(dEn(), dTo(), dMode(), dClock(), dDim(), dBright(), dOn()),
                motion = MotionConfig(mEn(), mSens().coerceIn(1, 100), mWake(), mFps().coerceIn(1, 15), mCool()),
                night = NightConfig(nEn(), nStart(), nEnd(), nVol(), nSilent(), nBright(), nMotion(), nTts()),
                webhooks = WebhookConfig(whMethod(), whMotion(), whMotionInt(), whIn(), whAns(), whMiss(), whEnd(), whDoor()),
                watchdog = WatchdogConfig(wdNight(), wdTime(), wdPer(), wdFreeze(), wdRetry()),
                kiosk = KioskConfig(kLock(), kHold().coerceIn(2, 60), kCorner(), kPin(), kBoot()),
                audio = AudioConfig(aEc(), aEl(), aMic(), aPlay(), aPtt(), aRingVol(), aRing(), aTtsVol(), aTtsRate()),
                // I numeri rapidi si modificano dal dialer: qui conservo quelli attuali.
                dialer = ConfigRepository.current.dialer.copy(enabled = dEnabled(), edge = dEdge(), installerCode = dCode()),
            ).also { ConfigRepository.nightOverride = nOverride() }
        }
    }

    private fun httpForm(f: Form, r: HttpRequestConfig): () -> HttpRequestConfig {
        val m = f.choice("Metodo HTTP", listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT"), r.method)
        val u = f.text("URL", r.url, "http://192.168.1.20/relay/0?turn=on")
        val b = f.text("Body (per POST/PUT)", r.body, multiline = true)
        val ct = f.text("Content-Type", r.contentType)
        val h = f.text("Header aggiuntivi (uno per riga, Nome: valore)", r.headers, multiline = true)
        val us = f.text("Utente (Basic/Digest)", r.user)
        val pw = f.text("Password", r.password, password = true)
        return { HttpRequestConfig(m(), u(), b(), ct(), h(), us(), pw()) }
    }

    private fun lockForm(parent: Form, i: Int, l: LockConfig): () -> LockConfig {
        val f = parent.sub()
        f.parent.addView(label("Serratura ${i + 1}", 18f, bold = true))
        val en = f.switch("Attiva", l.enabled)
        val lab = f.text("Etichetta", l.label)
        val dtmf = f.text("Sequenza DTMF", l.dtmf, "#1")
        val col = f.text("Colore pulsante (#RRGGBB)", l.color)
        val quick = f.switch("Apertura rapida senza rispondere", l.quickOpen)
        val hang = f.switch("Riaggancia dopo l'apertura", l.hangupAfterOpen)
        val hangD = f.int("Secondi prima di riagganciare", l.hangupDelaySec)
        val pref = f.switch("Usa sempre il comando HTTP (anche in chiamata)", l.preferHttp)
        f.note("Comando HTTP alternativo (facoltativo): se presente viene usato per l'apertura rapida senza rispondere e nel popup telecamera.")
        val http = httpForm(f, l.http)
        return { LockConfig(lab(), en(), dtmf(), http(), pref(), quick(), hang(), hangD(), col()) }
    }

    private fun renderButtons() {
        buttonsBox.removeAllViews()
        buttonGetters.clear()
        val f = Form(this, buttonsBox)
        buttonsDraft.forEachIndexed { idx, b ->
            val s = f.sub()
            s.parent.addView(label("Pulsante ${idx + 1}", 18f, bold = true))
            val lab = s.text("Etichetta", b.label)
            val icon = s.text("Icona (emoji o testo breve, es. 💡 🚪 🔔)", b.icon)
            val col = s.text("Colore (#RRGGBB)", b.color)
            val inCall = s.switch("Mostra nella chiamata", b.showInCall)
            val inCam = s.switch("Mostra nel popup telecamera", b.showInCameraPopup)
            val http = httpForm(s, b.request)
            val getter = { CustomButton(lab(), col(), icon(), http(), inCall(), inCam()) }
            buttonGetters += getter
            s.buttons("Elimina pulsante" to {
                buttonsDraft = buttonGetters.map { it() }.toMutableList()
                buttonsDraft.removeAt(idx)
                renderButtons()
            }, color = Color.rgb(183, 28, 28))
        }
    }

    // ------------------------------------------------------------------ azioni sistema

    private fun openAndroidSettings(action: String) {
        KioskManager.exitKiosk(this)
        Watchdog.maintenanceUntil = System.currentTimeMillis() + 30 * 60_000L
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun exitApp() {
        L.i("Settings", "Uscita richiesta dall'utente")
        ConfigRepository.exitRequested = true
        KioskManager.exitKiosk(this)
        KioskManager.releaseHome(this)
        SoundManager.stopAll()
        SipManager.stop()
        KioskCoreService.stop(this)
        finishAffinity()
        window.decorView.postDelayed({ exitProcess(0) }, 400)
    }

    private fun disableDeviceOwner() {
        if (!KioskManager.isDeviceOwner(this)) {
            toast("L'app non è Device Owner: puoi già disinstallarla normalmente")
            return
        }
        if (KioskManager.clearDeviceOwner(this)) {
            AlertDialog.Builder(this)
                .setTitle("Device Owner rimosso")
                .setMessage("Ora l'app può essere disinstallata. Vuoi disinstallarla adesso?")
                .setPositiveButton("Disinstalla") { _, _ ->
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Più tardi", null)
                .show()
        } else toast("Operazione non riuscita (vedi Log)")
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Conferma") { _, _ -> action() }.setNegativeButton("Annulla", null).show()
    }

    private fun logDialog() {
        val tv = TextView(this).apply {
            text = L.dump()
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Log").setView(ScrollView(this).apply { addView(tv); post { fullScroll(ScrollView.FOCUS_DOWN) } })
            .setPositiveButton("Chiudi", null).show()
    }

    private fun jsonDialog() {
        val e = EditText(this).apply {
            setText(ConfigRepository.exportJson())
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        AlertDialog.Builder(this).setTitle("Configurazione JSON").setView(ScrollView(this).apply { addView(e) })
            .setPositiveButton("Importa questo testo") { _, _ ->
                val err = ConfigRepository.importJson(e.text.toString())
                if (err == null) { toast("Configurazione importata"); recreate() } else toast("Errore: $err")
            }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun updateDialog() {
        val e = EditText(this).apply { hint = "http://192.168.1.10/KioskKNX.apk"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Aggiorna da URL").setView(e)
            .setPositiveButton("Scarica e installa") { _, _ ->
                AppUpdater.installFromUrlAsync(this, e.text.toString().trim()) { toast(it) }
                toast("Download in corso...")
            }
            .setNegativeButton("Annulla", null).show()
    }
}
