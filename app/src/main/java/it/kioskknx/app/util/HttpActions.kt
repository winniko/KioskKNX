package it.kioskknx.app.util

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.HttpRequestConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Client HTTP per serrature, pulsanti custom e webhook. Accetta certificati self-signed (rete locale). */
object HttpActions {
    private const val TAG = "Http"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Client base senza autenticazione, condiviso. */
    val baseClient: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Client con autenticazione Basic/Digest automatica (Hikvision ISAPI usa Digest). */
    fun clientFor(user: String, password: String, streaming: Boolean = false, use: NetRouter.Use = NetRouter.Use.WEB): OkHttpClient {
        val b = baseClient.newBuilder()
            .socketFactory(NetRouter.socketFactory(use))
            .dns(NetRouter.dns(use))
        if (streaming) b.readTimeout(0, TimeUnit.SECONDS)
        if (user.isNotEmpty()) {
            val creds = Credentials(user, password)
            val authenticator = DispatchingAuthenticator.Builder()
                .with("digest", DigestAuthenticator(creds))
                .with("basic", BasicAuthenticator(creds))
                .build()
            val cache: MutableMap<String, CachingAuthenticator> = ConcurrentHashMap()
            b.authenticator(CachingAuthenticatorDecorator(authenticator, cache))
                .addInterceptor(AuthenticationCacheInterceptor(cache))
        }
        return b.build()
    }

    data class Result(val ok: Boolean, val code: Int, val body: String)

    suspend fun execute(req: HttpRequestConfig, use: NetRouter.Use = NetRouter.Use.WEB): Result = withContext(Dispatchers.IO) {
        if (req.url.isBlank()) return@withContext Result(false, 0, "URL vuoto")
        try {
            val (url, user, pass) = splitUserInfo(req.url, req.user, req.password)
            val method = req.method.uppercase(Locale.ROOT)
            val body = if (method == "GET" || method == "DELETE") null
            else req.body.toRequestBody(req.contentType.ifBlank { "text/plain" }.toMediaTypeOrNull())
            val builder = Request.Builder().url(url).method(method, body)
            req.headers.lines().map { it.trim() }.filter { it.contains(":") }.forEach {
                builder.addHeader(it.substringBefore(":").trim(), it.substringAfter(":").trim())
            }
            clientFor(user, pass, use = use).newCall(builder.build()).execute().use { r ->
                val text = r.body?.string()?.take(2000) ?: ""
                L.i(TAG, "$method $url -> ${r.code}")
                Result(r.isSuccessful, r.code, text)
            }
        } catch (e: Exception) {
            L.w(TAG, "Richiesta fallita ${req.url}", e)
            Result(false, 0, e.message ?: "errore")
        }
    }

    fun fire(req: HttpRequestConfig, use: NetRouter.Use = NetRouter.Use.WEB, onDone: ((Result) -> Unit)? = null) {
        scope.launch {
            val r = execute(req, use)
            if (onDone != null) withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    /** Estrae eventuali credenziali scritte nell'URL (http://user:pass@host/...). */
    fun splitUserInfo(url: String, user: String, pass: String): Triple<String, String, String> {
        val m = Regex("^(\\w+://)([^/@:]+)(?::([^/@]*))?@(.*)$").find(url) ?: return Triple(url, user, pass)
        val (scheme, u, p, rest) = m.destructured
        return Triple(scheme + rest, user.ifEmpty { java.net.URLDecoder.decode(u, "UTF-8") },
            pass.ifEmpty { java.net.URLDecoder.decode(p, "UTF-8") })
    }

    // ---------------- Webhook in uscita ----------------
    private val lastMotionHook = java.util.concurrent.atomic.AtomicLong(0)

    fun webhook(event: String, remote: String = "", lock: String = "") {
        val w = ConfigRepository.current.webhooks
        val url = when (event) {
            "motion" -> {
                val now = System.currentTimeMillis()
                if (now - lastMotionHook.get() < w.motionMinIntervalSec * 1000L) return
                lastMotionHook.set(now)
                w.onMotion
            }
            "call_incoming" -> w.onCallIncoming
            "call_answered" -> w.onCallAnswered
            "call_missed" -> w.onCallMissed
            "call_ended" -> w.onCallEnded
            "door_opened" -> w.onDoorOpened
            else -> ""
        }
        if (url.isBlank()) return
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())
        val ip = DeviceInfo.ipAddress()
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val finalUrl = url.replace("{event}", enc(event)).replace("{remote}", enc(remote))
            .replace("{lock}", enc(lock)).replace("{time}", enc(time)).replace("{ip}", enc(ip))
        val post = w.method.equals("POST", true)
        val body = if (post) buildJsonObject {
            put("event", event); put("remote", remote); put("lock", lock); put("time", time); put("ip", ip)
        }.toString() else ""
        fire(HttpRequestConfig(method = if (post) "POST" else "GET", url = finalUrl, body = body))
    }
}
