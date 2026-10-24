package it.kioskknx.app.config

import kotlinx.serialization.Serializable

/** Configurazione completa dell'app. Viene salvata come JSON ed è esportabile/importabile. */
@Serializable
data class AppConfig(
    val web: WebConfig = WebConfig(),
    val sip: SipConfig = SipConfig(),
    val video: VideoConfig = VideoConfig(),
    val locks: List<LockConfig> = listOf(
        LockConfig(label = "Serratura 1", dtmf = "#1"),
        LockConfig(label = "Serratura 2", dtmf = "#2"),
    ),
    val buttons: List<CustomButton> = emptyList(),
    val api: ApiConfig = ApiConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val motion: MotionConfig = MotionConfig(),
    val night: NightConfig = NightConfig(),
    val webhooks: WebhookConfig = WebhookConfig(),
    val watchdog: WatchdogConfig = WatchdogConfig(),
    val kiosk: KioskConfig = KioskConfig(),
    val audio: AudioConfig = AudioConfig(),
    val dialer: DialerConfig = DialerConfig(),
)

@Serializable
data class SpeedDial(
    val name: String = "",
    /** Interno del centralino (es. 201), IP (es. 192.168.2.50) oppure indirizzo sip:... */
    val number: String = "",
    val video: Boolean = false,
)

@Serializable
data class DialerConfig(
    val enabled: Boolean = false,
    /** Bordo da cui si apre strisciando: RIGHT | LEFT | TOP | BOTTOM */
    val edge: String = "RIGHT",
    /** Codice per modificare i numeri rapidi (vuoto = usa il PIN delle impostazioni). */
    val installerCode: String = "",
    val speedDials: List<SpeedDial> = emptyList(),
)

@Serializable
data class WebConfig(
    val url: String = "http://192.168.1.10",
    val reloadAfterCall: Boolean = true,
    val ignoreSslErrors: Boolean = true,
    val httpAuthUser: String = "",
    val httpAuthPassword: String = "",
    val textZoom: Int = 100,
    val allowZoom: Boolean = false,
    val userAgentSuffix: String = "KioskKNX",
    val clearCacheOnStart: Boolean = false,
    /** Zoom dell'intera pagina (%): la pagina si riadatta come se lo schermo fosse più piccolo/grande. */
    val pageZoom: Int = 100,
    /** AUTO = Gecko se la WebView di sistema è vecchia; GECKO = Firefox integrato; SYSTEM = WebView di sistema. */
    val engine: String = "AUTO",
)

@Serializable
data class SipConfig(
    val enabled: Boolean = true,
    /** Registrazione su centralino (PBX). Se false si usano solo chiamate dirette IP. */
    val registerEnabled: Boolean = false,
    val server: String = "",
    val serverPort: Int = 5060,
    val username: String = "",
    val password: String = "",
    val authUsername: String = "",
    val displayName: String = "Pannello",
    val transport: String = "UDP", // UDP | TCP
    /** Porta SIP locale del tablet (5062 per non scontrarsi con app citofono già presenti). */
    val localPort: Int = 5062,
    val directIpCallsEnabled: Boolean = true,
    /** IP dei citofoni autorizzati per le chiamate dirette (vuoto = tutti). Separati da virgola. */
    val allowedIps: String = "",
    val earlyMediaEnabled: Boolean = true,
    val autoAnswer: Boolean = false,
    val ringTimeoutSec: Int = 45,
    val maxCallDurationSec: Int = 180,
    val dtmfMode: String = "RFC2833", // RFC2833 | SIP_INFO | INBAND | RFC2833_INFO
    val sendVideo: Boolean = false,
    val registerExpiresSec: Int = 600,
    val keepAlive: Boolean = true,
    val debugLogs: Boolean = false,
)

@Serializable
data class VideoConfig(
    /** URL RTSP o MJPEG del citofono, mostrato prima della risposta se il video SIP non arriva. */
    val fallbackUrl: String = "",
    /** AUTO = solo se il video SIP non arriva entro [fallbackDelayMs]; ALWAYS = sempre; NEVER = mai. */
    val fallbackMode: String = "AUTO",
    val fallbackDelayMs: Int = 2500,
    val rtspOverTcp: Boolean = true,
    /** Durante la chiamata risposta continua a mostrare il fallback anche se arriva il video SIP. */
    val keepFallbackAfterAnswer: Boolean = false,
)

@Serializable
data class HttpRequestConfig(
    val method: String = "GET", // GET | POST | PUT
    val url: String = "",
    val body: String = "",
    val contentType: String = "application/json",
    /** Un header per riga, formato "Nome: valore". */
    val headers: String = "",
    val user: String = "",
    val password: String = "",
)

@Serializable
data class LockConfig(
    val label: String = "Serratura",
    val enabled: Boolean = true,
    val dtmf: String = "#1",
    /** Se valorizzato, la serratura viene aperta con questa richiesta HTTP. */
    val http: HttpRequestConfig = HttpRequestConfig(),
    /** Se true usa HTTP invece del DTMF anche durante la chiamata. */
    val preferHttp: Boolean = false,
    /** Consente di aprire anche senza aver risposto. */
    val quickOpen: Boolean = true,
    /** Dopo l'apertura chiude la chiamata. */
    val hangupAfterOpen: Boolean = false,
    val hangupDelaySec: Int = 3,
    val color: String = "#1565C0",
)

@Serializable
data class CustomButton(
    val label: String = "Pulsante",
    val color: String = "#455A64",
    val icon: String = "", // emoji o testo breve, es. "💡"
    val request: HttpRequestConfig = HttpRequestConfig(),
    val showInCall: Boolean = true,
    val showInCameraPopup: Boolean = true,
)

@Serializable
data class ApiConfig(
    val enabled: Boolean = true,
    val port: Int = 8080,
    val apiKey: String = "",
    val allowConfigWrite: Boolean = true,
    val allowRemoteUpdate: Boolean = true,
)

@Serializable
data class DisplayConfig(
    val screensaverEnabled: Boolean = true,
    val timeoutSec: Int = 300,
    /** DIM = abbassa luminosità; BLACK = schermo nero; OFF = spegne il display (serve Device Owner). */
    val mode: String = "BLACK",
    val showClock: Boolean = true,
    val dimLevel: Int = 5,
    /** -1 = luminosità di sistema. */
    val activeBrightness: Int = -1,
    val keepScreenOn: Boolean = true,
)

@Serializable
data class MotionConfig(
    val enabled: Boolean = false,
    val sensitivity: Int = 50, // 1-100
    val wakeScreen: Boolean = true,
    val analysisFps: Int = 4,
    val cooldownSec: Int = 5,
)

@Serializable
data class NightConfig(
    val enabled: Boolean = false,
    val start: String = "22:30",
    val end: String = "07:00",
    val ringVolumePercent: Int = 30,
    val silentRing: Boolean = false,
    val maxBrightness: Int = 30,
    val motionWake: Boolean = false,
    val ttsEnabled: Boolean = false,
)

@Serializable
data class WebhookConfig(
    val method: String = "GET", // GET con segnaposto {event} {remote} {lock} {time} {ip}; POST invia JSON
    val onMotion: String = "",
    val motionMinIntervalSec: Int = 30,
    val onCallIncoming: String = "",
    val onCallAnswered: String = "",
    val onCallMissed: String = "",
    val onCallEnded: String = "",
    val onDoorOpened: String = "",
)

@Serializable
data class WatchdogConfig(
    val nightlyReloadEnabled: Boolean = true,
    val nightlyReloadTime: String = "04:00",
    val periodicReloadMin: Int = 0,
    val freezeCheckEnabled: Boolean = true,
    val offlineRetrySec: Int = 10,
)

@Serializable
data class KioskConfig(
    /** Usa il blocco totale (Lock Task) quando l'app è Device Owner. */
    val lockTaskEnabled: Boolean = true,
    val settingsHoldSec: Int = 10,
    val settingsCorner: String = "BOTTOM_RIGHT", // BOTTOM_RIGHT | BOTTOM_LEFT | TOP_RIGHT | TOP_LEFT
    val pin: String = "",
    val autoStartOnBoot: Boolean = true,
)

@Serializable
data class AudioConfig(
    val echoCancellation: Boolean = true,
    val echoLimiter: Boolean = false,
    val micGainDb: Float = 0f,
    val playbackGainDb: Float = 0f,
    /** Premi per parlare (half-duplex) per pannelli con eco forte. */
    val pushToTalk: Boolean = false,
    val ringVolumePercent: Int = 80,
    val ringtone: String = "DEFAULT", // DEFAULT | DOORBELL | BEEP
    val ttsVolumePercent: Int = 90,
    val ttsSpeechRate: Float = 1.0f,
)
