package it.kioskknx.app.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import it.kioskknx.app.BuildConfig
import it.kioskknx.app.CallUiState
import it.kioskknx.app.KioskBus
import it.kioskknx.app.UiCommand
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.display.NightMode
import it.kioskknx.app.display.ScreenWake
import it.kioskknx.app.history.CallHistory
import it.kioskknx.app.kiosk.KioskManager
import it.kioskknx.app.kiosk.Launcher
import it.kioskknx.app.media.SoundManager
import it.kioskknx.app.media.TtsManager
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.update.AppUpdater
import it.kioskknx.app.util.DeviceInfo
import it.kioskknx.app.util.L
import it.kioskknx.app.util.NetRouter
import it.kioskknx.app.web.WebViewInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Server REST locale per il controllo da domotica (KNX, Shelly, Home Assistant, ...). */
class ApiServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {
    private val main = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()

    private class Req(val params: Map<String, String>, val body: String)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        return try {
            val req = parse(session)
            val uri = session.uri.trimEnd('/').lowercase(Locale.ROOT).ifEmpty { "/" }
            if (uri != "/" && !authorized(session, req)) {
                return cors(json(Response.Status.UNAUTHORIZED, err("API key mancante o errata")))
            }
            cors(route(uri, req))
        } catch (e: Exception) {
            L.e("API", "Errore su ${session.uri}", e)
            cors(json(Response.Status.INTERNAL_ERROR, err(e.message ?: "errore")))
        }
    }

    private fun route(uri: String, r: Req): Response = when (uri) {
        "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", HELP_HTML.replace("SOURCE_URL", BuildConfig.SOURCE_URL))
        "/api/status" -> json(status())
        "/api/screen" -> screen(r)
        "/api/webview" -> webview(r)
        "/api/tts" -> tts(r)
        "/api/sound" -> sound(r)
        "/api/camera-popup" -> cameraPopup(r)
        "/api/call" -> call(r)
        "/api/night" -> night(r)
        "/api/history" -> json(history())
        "/api/snapshot" -> snapshot(r)
        "/api/config" -> config(r)
        "/api/update" -> update(r)
        "/api/logs" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", L.dump())
        "/api/restart" -> restart()
        "/api/reboot" -> if (KioskManager.reboot(ctx)) json(ok("riavvio in corso")) else json(Response.Status.BAD_REQUEST, err("serve Device Owner"))
        else -> json(Response.Status.NOT_FOUND, err("endpoint sconosciuto: $uri"))
    }

    // ------------------------------------------------------------------ endpoint

    private fun screen(r: Req): Response {
        val p = r.params
        val level = p["level"]?.toIntOrNull() ?: p["brightness"]?.toIntOrNull()
        val action = (p["action"] ?: p["state"] ?: if (level != null) "brightness" else "on").lowercase(Locale.ROOT)
        when (action) {
            "on", "wake" -> { ScreenWake.wake(ctx); KioskBus.send(UiCommand.ScreenOn) }
            "off" -> KioskBus.send(UiCommand.ScreenOff)
            "screensaver", "black" -> KioskBus.send(UiCommand.Screensaver)
            "brightness", "dim" -> {
                val l = level ?: if (action == "dim") ConfigRepository.current.display.dimLevel else 100
                KioskBus.send(UiCommand.Brightness(l.coerceIn(0, 100)))
            }
            else -> return json(Response.Status.BAD_REQUEST, err("action non valida: on|off|screensaver|brightness"))
        }
        return json(ok("screen $action"))
    }

    private fun webview(r: Req): Response {
        val url = r.params["url"].orEmpty()
        val action = (r.params["action"] ?: if (url.isNotBlank()) "navigate" else "reload").lowercase(Locale.ROOT)
        when (action) {
            "reload" -> KioskBus.send(UiCommand.Reload)
            "navigate", "url" -> {
                if (url.isBlank()) return json(Response.Status.BAD_REQUEST, err("parametro url mancante"))
                KioskBus.send(UiCommand.Navigate(url))
            }
            "home" -> KioskBus.send(UiCommand.GoHome)
            else -> return json(Response.Status.BAD_REQUEST, err("action non valida: reload|navigate|home"))
        }
        return json(ok("webview $action"))
    }

    private fun tts(r: Req): Response {
        val text = r.params["text"] ?: r.params["message"] ?: r.params["msg"]
        if (text.isNullOrBlank()) return json(Response.Status.BAD_REQUEST, err("parametro text mancante"))
        val vol = r.params["volume"]?.toIntOrNull() ?: -1
        val rate = r.params["rate"]?.toFloatOrNull() ?: -1f
        val force = r.params["force"].isTrue()
        if (r.params["wake"].isTrue()) { ScreenWake.wake(ctx); KioskBus.send(UiCommand.ScreenOn) }
        val spoken = TtsManager.speak(text, vol, rate, force)
        return json(buildJsonObject {
            put("ok", true); put("spoken", spoken); put("ttsReady", TtsManager.ready); put("italian", TtsManager.italianAvailable)
        })
    }

    private fun sound(r: Req): Response {
        val action = (r.params["action"] ?: "play").lowercase(Locale.ROOT)
        if (action == "stop") {
            onMain { SoundManager.stopAll(); TtsManager.stop() }
            return json(ok("suoni fermati"))
        }
        val name = (r.params["name"] ?: r.params["sound"] ?: ConfigRepository.current.audio.apiDefaultSound).lowercase(Locale.ROOT)
        val url = r.params["url"].orEmpty()
        val loop = r.params["loop"].isTrue()
        val vol = r.params["volume"]?.toIntOrNull() ?: -1
        // force=true: suona anche di notte con suoneria silenziata (es. allarmi)
        val force = r.params["force"].isTrue()
        val night = NightMode.isActive()
        if (night && ConfigRepository.current.night.apiSilent && !force) {
            L.i("Api", "Suono $name non riprodotto: modalità notte con suoneria silenziata")
            return json(ok("modalità notte: suono silenziato (usa force=true per suonare comunque)"))
        }
        var played = false
        onMain { played = SoundManager.play(name, url, loop, vol, nightVolume = night && !force) }
        return if (played) json(ok("riproduco $name"))
        else json(Response.Status.BAD_REQUEST, err("suono sconosciuto. Disponibili: ${SoundManager.names.joinToString()} oppure url="))
    }

    private fun cameraPopup(r: Req): Response {
        val action = (r.params["action"] ?: "open").lowercase(Locale.ROOT)
        if (action == "close") {
            KioskBus.send(UiCommand.CloseCameraPopup)
            return json(ok("popup chiuso"))
        }
        val url = r.params["url"].orEmpty().ifBlank { ConfigRepository.current.video.fallbackUrl }
        if (url.isBlank()) return json(Response.Status.BAD_REQUEST, err("parametro url mancante"))
        val secs = r.params["seconds"]?.toIntOrNull() ?: r.params["duration"]?.toIntOrNull() ?: 30
        val title = r.params["title"] ?: "Telecamera"
        val transport = (r.params["transport"] ?: "").lowercase(Locale.ROOT)
        if (transport !in setOf("", "auto", "tcp", "udp")) return json(Response.Status.BAD_REQUEST, err("transport: auto|tcp|udp"))
        ScreenWake.wake(ctx)
        KioskBus.send(UiCommand.CameraPopup(url, secs, title, transport))
        return if (KioskBus.callState.value is CallUiState.Idle) json(ok("popup aperto per $secs s"))
        else json(buildJsonObject {
            put("ok", true); put("queued", true)
            put("message", "chiamata del citofono in corso: popup in coda, verrà mostrato a fine chiamata se non è scaduto")
        })
    }

    private fun call(r: Req): Response {
        val action = (r.params["action"] ?: "").lowercase(Locale.ROOT)
        var msg = ""
        val latch = CountDownLatch(1)
        main.post {
            when (action) {
                "answer" -> { SipManager.accept(); msg = "risposto"; latch.countDown() }
                "hangup", "decline" -> { SipManager.hangup(); msg = "chiamata chiusa"; latch.countDown() }
                "dtmf" -> {
                    val digits = r.params["digits"].orEmpty()
                    msg = when {
                        digits.isBlank() -> "parametro digits mancante"
                        !SipManager.hasCall -> "nessuna chiamata attiva"
                        SipManager.sendDtmf(digits) -> "dtmf inviato"
                        else -> "invio dtmf non riuscito"
                    }
                    latch.countDown()
                }
                "dial" -> {
                    val target = r.params["number"] ?: r.params["to"] ?: ""
                    msg = SipManager.dial(target, r.params["video"].isTrue()) ?: "chiamata avviata verso $target"
                    latch.countDown()
                }
                "open", "open1", "open2" -> {
                    val idx = when (action) { "open2" -> 1; "open1" -> 0; else -> (r.params["lock"]?.toIntOrNull() ?: 1) - 1 }
                    SipManager.openLock(idx) { _, m -> msg = m; latch.countDown() }
                }
                else -> { msg = "action non valida: answer|hangup|dtmf|open|dial"; latch.countDown() }
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return json(buildJsonObject { put("ok", true); put("message", msg) })
    }

    private fun night(r: Req): Response {
        r.params["state"]?.lowercase(Locale.ROOT)?.let {
            if (it !in setOf("on", "off", "auto")) return json(Response.Status.BAD_REQUEST, err("state: on|off|auto"))
            ConfigRepository.nightOverride = it
            KioskBus.send(UiCommand.ApplyConfig)
        }
        return json(buildJsonObject { put("ok", true); put("override", ConfigRepository.nightOverride); put("active", NightMode.isActive()) })
    }

    private fun history(): JsonElement = buildJsonArray {
        CallHistory.list().forEach { e ->
            add(buildJsonObject {
                put("id", e.id); put("time", iso(e.time)); put("remote", e.remote); put("answered", e.answered)
                put("durationSec", e.durationSec); put("doorOpened", e.doorOpened); put("hasSnapshot", e.snapshot.isNotEmpty())
            })
        }
    }

    private fun snapshot(r: Req): Response {
        val f = CallHistory.snapshotFor(r.params["id"].orEmpty()) ?: return json(Response.Status.NOT_FOUND, err("nessuna foto"))
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(f), f.length())
    }

    private fun config(r: Req): Response {
        if (r.body.isNotBlank() && r.body.trimStart().startsWith("{")) {
            if (!ConfigRepository.current.api.allowConfigWrite) return json(Response.Status.FORBIDDEN, err("scrittura configurazione disabilitata"))
            val e = ConfigRepository.importJson(r.body)
            return if (e == null) json(ok("configurazione importata")) else json(Response.Status.BAD_REQUEST, err(e))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", ConfigRepository.exportJson())
    }

    private fun update(r: Req): Response {
        if (!ConfigRepository.current.api.allowRemoteUpdate) return json(Response.Status.FORBIDDEN, err("aggiornamento remoto disabilitato"))
        val url = r.params["url"].orEmpty()
        if (url.isBlank()) return json(Response.Status.BAD_REQUEST, err("parametro url mancante"))
        AppUpdater.installFromUrlAsync(ctx, url)
        return json(ok("download avviato, l'app si riavvierà dopo l'installazione"))
    }

    private fun restart(): Response {
        Launcher.scheduleRestart(ctx, 1500)
        main.postDelayed({ exitProcess(0) }, 500)
        return json(ok("riavvio app"))
    }

    private fun status(): JsonObject {
        val bat = DeviceInfo.battery(ctx)
        val sip = SipManager.statusMap()
        val lastMotion = KioskBus.lastMotionAt.value
        return buildJsonObject {
            put("ok", true)
            put("app", "KioskKNX"); put("version", BuildConfig.VERSION_NAME)
            put("license", "GNU AGPL v3"); put("source", BuildConfig.SOURCE_URL)
            put("uptimeSec", (SystemClock.elapsedRealtime() - startedAt) / 1000)
            put("deviceOwner", KioskManager.isDeviceOwner(ctx)); put("lockTask", KioskBus.lockTaskActive.value)
            putJsonObject("sip") { sip.forEach { (k, v) -> put(k, v) } }
            putJsonObject("screen") {
                put("state", KioskBus.screenState.value); put("interactive", ScreenWake.isInteractive(ctx))
                put("brightness", KioskBus.brightness.value)
            }
            putJsonObject("battery") { put("level", bat.level); put("charging", bat.charging); put("power", bat.plugged) }
            putJsonObject("network") {
                put("type", DeviceInfo.networkType(ctx)); put("ip", DeviceInfo.ipAddress()); put("apiPort", ConfigRepository.current.api.port)
                put("lanIp", NetRouter.ipOf(NetRouter.ethernet)); put("wifiIp", NetRouter.ipOf(NetRouter.wifi))
                put("dualMode", ConfigRepository.current.network.dualMode)
                put("sipVia", NetRouter.describe(NetRouter.networkFor(NetRouter.Use.SIP)))
                put("webVia", NetRouter.describe(NetRouter.networkFor(NetRouter.Use.WEB)))
            }
            putJsonObject("motion") {
                put("enabled", ConfigRepository.current.motion.enabled)
                put("lastMotion", if (lastMotion > 0) iso(lastMotion) else "")
                put("lastMotionEpochMs", lastMotion)
                put("secondsAgo", if (lastMotion > 0) (System.currentTimeMillis() - lastMotion) / 1000 else -1)
            }
            putJsonObject("webview") { put("url", KioskBus.currentUrl.value); put("loaded", KioskBus.pageLoaded.value) }
            put("night", NightMode.isActive())
            put("cameraPopup", KioskBus.cameraPopupOpen.value)
            put("tts", TtsManager.ready)
            putJsonObject("permissions") {
                fun g(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
                put("microphone", g(android.Manifest.permission.RECORD_AUDIO))
                put("camera", g(android.Manifest.permission.CAMERA))
                put("overlay", android.provider.Settings.canDrawOverlays(ctx))
            }
            val wv = WebViewInfo.get(ctx)
            putJsonObject("webviewEngine") {
                put("inUse", KioskBus.webEngine.value)
                put("package", wv.packageName); put("version", wv.version)
                put("outdated", wv.major in 1 until WebViewInfo.MIN_RECOMMENDED)
            }
        }
    }

    // ------------------------------------------------------------------ util

    private fun parse(s: IHTTPSession): Req {
        val files = HashMap<String, String>()
        if (s.method == Method.POST || s.method == Method.PUT) {
            try { s.parseBody(files) } catch (e: Exception) { L.w("API", "Body non leggibile", e) }
        }
        val params = HashMap<String, String>()
        s.parameters.forEach { (k, v) -> v.firstOrNull()?.let { params[k] = it } }
        var body = files["postData"] ?: ""
        if (body.isEmpty()) files["content"]?.let { path -> body = runCatching { java.io.File(path).readText() }.getOrDefault("") }
        if (body.trimStart().startsWith("{")) {
            runCatching {
                val obj = ConfigRepository.json.parseToJsonElement(body) as JsonObject
                obj.forEach { (k, v) -> if (v is JsonPrimitive && k !in params) params[k] = v.jsonPrimitive.content }
            }
        }
        return Req(params, body)
    }

    private fun authorized(s: IHTTPSession, r: Req): Boolean {
        val key = ConfigRepository.current.api.apiKey
        if (key.isBlank()) return true
        val h = s.headers
        return h["x-api-key"] == key || r.params["key"] == key || r.params["apikey"] == key ||
            h["authorization"]?.removePrefix("Bearer ")?.trim() == key
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun String?.isTrue() = this != null && this.lowercase(Locale.ROOT) in setOf("1", "true", "yes", "si", "on")

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date(t))
    private fun ok(msg: String) = buildJsonObject { put("ok", true); put("message", msg) }
    private fun err(msg: String) = buildJsonObject { put("ok", false); put("error", msg) }

    private fun json(el: JsonElement) = json(Response.Status.OK, el)
    private fun json(status: Response.Status, el: JsonElement): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", el.toString())

    private fun cors(r: Response): Response = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Content-Type, X-Api-Key, Authorization")
        addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
    }

    companion object {
        private val HELP_HTML = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>KioskKNX API</title><style>body{font-family:sans-serif;margin:24px;max-width:820px}code{background:#eee;padding:2px 5px;border-radius:4px}li{margin:6px 0}</style></head>
            <body><h1>KioskKNX - API locale</h1><p>Se è impostata una API key aggiungere <code>?key=LA_CHIAVE</code> oppure l'header <code>X-Api-Key</code>.</p><ul>
            <li><code>/api/status</code> stato del pannello</li>
            <li><code>/api/screen?action=on|off|screensaver|brightness&amp;level=0-100</code></li>
            <li><code>/api/webview?action=reload|navigate|home&amp;url=...</code></li>
            <li><code>/api/tts?text=Ciao&amp;volume=80</code></li>
            <li><code>/api/sound?name=doorbell|chime|beep|alarm|ring|notification&amp;loop=false&amp;volume=80</code> / <code>?action=stop</code></li>
            <li><code>/api/camera-popup?url=rtsp://...&amp;seconds=30&amp;title=Ingresso&amp;transport=tcp|udp|auto</code> / <code>?action=close</code></li>
            <li><code>/api/call?action=answer|hangup|open&amp;lock=1|dtmf&amp;digits=#1|dial&amp;number=201&amp;video=true</code></li>
            <li><code>/api/night?state=on|off|auto</code></li>
            <li><code>/api/history</code>, <code>/api/snapshot?id=latest</code></li>
            <li><code>/api/config</code> (GET esporta, POST JSON importa)</li>
            <li><code>/api/update?url=http://.../KioskKNX.apk</code></li>
            <li><code>/api/logs</code>, <code>/api/restart</code>, <code>/api/reboot</code></li>
            </ul><p>KioskKNX è software libero (GNU AGPL v3). Codice sorgente: <a href="SOURCE_URL">SOURCE_URL</a></p></body></html>
        """.trimIndent()
    }
}
