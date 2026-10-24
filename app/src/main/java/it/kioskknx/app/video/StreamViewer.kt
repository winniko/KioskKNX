package it.kioskknx.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import it.kioskknx.app.util.HttpActions
import it.kioskknx.app.util.L
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections

/**
 * Visualizza uno stream video in un contenitore:
 * - RTSP con Media3/ExoPlayer; se Media3 rifiuta il flusso (es. NVR Hikvision senza
 *   sprop-parameter-sets nell'SDP) passa al lettore H.264 integrato [RtspH264Client];
 * - MJPEG / snapshot JPEG via HTTP (decoder interno, con autenticazione Basic/Digest).
 * A ogni avvio e a ogni nuovo tentativo il lettore viene ricreato e quello precedente rilasciato.
 */
@OptIn(UnstableApi::class)
class StreamViewer(private val context: Context, private val container: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var native: RtspH264Client? = null
    private var nativeSurface: Surface? = null
    private var texture: TextureView? = null
    private var image: ImageView? = null
    private var mjpegThread: Thread? = null
    @Volatile private var running = false
    private var lastBitmap: Bitmap? = null
    private var url = ""
    /** "tcp" | "udp" | "auto" */
    private var transport = "tcp"
    private var useTcpNow = true
    private var attempts = 0
    private var firstFrameNotified = false
    private var startedAt = 0L

    var onFirstFrame: (() -> Unit)? = null
    /** Errore temporaneo (seguirà un nuovo tentativo). */
    var onError: ((String) -> Unit)? = null
    /** Errore definitivo: niente più tentativi. */
    var onGiveUp: ((String) -> Unit)? = null

    val isRunning: Boolean get() = running

    /** @param transport "tcp" (predefinito), "udp" oppure "auto" (UDP e, al primo errore, TCP). */
    fun start(url: String, transport: String = "tcp") {
        stop()
        if (url.isBlank()) return
        this.url = url
        this.transport = transport.lowercase()
        useTcpNow = this.transport != "udp" && this.transport != "auto"
        attempts = 0
        running = true
        firstFrameNotified = false
        startedAt = System.currentTimeMillis()
        if (url.startsWith("rtsp", ignoreCase = true)) startRtspAttempt() else startMjpeg()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        releaseRtsp()
        mjpegThread?.interrupt()
        mjpegThread = null
        container.removeAllViews()
        texture = null
        image = null
    }

    /** Rilascia il lettore RTSP corrente (Media3 o integrato) e la sua superficie. */
    private fun releaseRtsp() {
        player?.let { runCatching { it.release() } }
        player = null
        native?.let { runCatching { it.stop() } }
        native = null
        nativeSurface?.let { runCatching { it.release() } }
        nativeSurface = null
    }

    /** Fotogramma corrente (per lo storico chiamate). */
    fun snapshot(): Bitmap? = texture?.takeIf { it.isAvailable }?.bitmap ?: lastBitmap

    private fun frameReady() {
        if (firstFrameNotified) return
        firstFrameNotified = true
        attempts = 0
        L.i("Video", "Primo fotogramma dopo ${System.currentTimeMillis() - startedAt} ms")
        onFirstFrame?.invoke()
    }

    // ------------------------------------------------------------------ RTSP

    private fun newTexture(): TextureView {
        releaseRtsp()
        container.removeAllViews()
        val tv = TextureView(context)
        texture = tv
        container.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        return tv
    }

    private fun startRtspAttempt() {
        if (!running) return
        if (url in nativeUrls) startNative() else startMedia3()
        // Un lettore può restare bloccato senza segnalare errori (server muto): dopo
        // FIRST_FRAME_TIMEOUT_MS senza immagini lo considero un tentativo fallito.
        main.removeCallbacks(firstFrameWatchdog)
        main.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
    }

    private val firstFrameWatchdog = Runnable {
        if (running && !firstFrameNotified) {
            handleError(java.util.concurrent.TimeoutException("Nessun fotogramma entro ${FIRST_FRAME_TIMEOUT_MS / 1000} s"), "controllo tempo")
        }
    }

    private fun startMedia3() {
        val tv = newTexture()
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build()
        val p = ExoPlayer.Builder(context).setLoadControl(load).build()
        player = p
        p.volume = 0f
        p.setVideoTextureView(tv)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = frameReady()
            override fun onVideoSizeChanged(videoSize: VideoSize) = fit(tv, videoSize.width, videoSize.height)
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                handleError(error, "Media3 ${error.errorCodeName}")
            }
        })
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcpNow)
            .setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        L.i("Video", "Avvio RTSP ${mask(url)} (Media3, ${if (useTcpNow) "TCP" else "UDP"}, tentativo ${attempts + 1})")
    }

    private fun startNative() {
        val tv = newTexture()
        val begin = { st: SurfaceTexture ->
            if (running && texture === tv && native == null) {
                val surface = Surface(st)
                nativeSurface = surface
                lateinit var client: RtspH264Client
                client = RtspH264Client(url, surface, object : RtspH264Client.Listener {
                    override fun onFirstFrame() { if (native === client) frameReady() }
                    override fun onVideoSize(width: Int, height: Int) { if (native === client) fit(tv, width, height) }
                    override fun onError(e: Throwable) { if (native === client) handleError(e, "lettore H.264 integrato") }
                })
                native = client
                client.start()
                L.i("Video", "Avvio RTSP ${mask(url)} (lettore H.264 integrato, TCP, tentativo ${attempts + 1})")
            }
        }
        val st = tv.surfaceTexture
        if (tv.isAvailable && st != null) begin(st)
        else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = begin(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** Decide cosa fare dopo un errore: cambio lettore o trasporto, nuovo tentativo, oppure resa. */
    private fun handleError(e: Throwable, source: String) {
        if (!running) return
        main.removeCallbacks(firstFrameWatchdog)
        L.w("Video", "Errore RTSP ($source): ${L.describe(e)}")
        val chain = generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.toList()
        val text = chain.joinToString(" ") { it.message ?: "" }
        val status = chain.filterIsInstance<RtspStatusException>().firstOrNull()?.status
            ?: Regex("\\b(401|403)\\b").find(text)?.value?.toInt()

        when {
            status == 401 || status == 403 -> {
                // Niente nuovi tentativi: insistere con credenziali errate fa bloccare il nostro IP dall'NVR.
                giveUp("Accesso negato dal server video ($status): controlla utente e password nell'URL")
                return
            }
            url !in nativeUrls && (text.contains("sprop", true) || chain.any { it.javaClass.simpleName == "ParserException" }) -> {
                L.i("Video", "Media3 non supporta questo flusso (SDP senza sprop-parameter-sets): passo al lettore H.264 integrato")
                nativeUrls += url
                releaseRtsp()
                main.post { startRtspAttempt() }
                return
            }
            transport == "auto" && !useTcpNow -> {
                L.i("Video", "RTSP su UDP non riuscito: riprovo su TCP")
                useTcpNow = true
                releaseRtsp()
                main.post { startRtspAttempt() }
                return
            }
        }
        attempts++
        releaseRtsp()
        val reason = chain.last().message ?: chain.last().javaClass.simpleName
        if (attempts >= MAX_ATTEMPTS) {
            giveUp("Video non disponibile dopo $MAX_ATTEMPTS tentativi: $reason")
            return
        }
        val delay = (1000L shl (attempts - 1)).coerceAtMost(15_000L)
        onError?.invoke("$reason\nNuovo tentativo tra ${delay / 1000} s ($attempts/$MAX_ATTEMPTS)")
        main.postDelayed({ startRtspAttempt() }, delay)
    }

    private fun giveUp(message: String) {
        L.w("Video", message)
        releaseRtsp()
        running = false
        (onGiveUp ?: onError)?.invoke(message)
    }

    /** Adatta la TextureView mantenendo le proporzioni del video. */
    private fun fit(tv: TextureView, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        container.post {
            val cw = container.width.toFloat()
            val ch = container.height.toFloat()
            if (cw <= 0 || ch <= 0) return@post
            val scale = minOf(cw / w, ch / h)
            tv.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
        }
    }

    // ------------------------------------------------------------------ MJPEG / JPEG

    private fun startMjpeg() {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        image = iv
        container.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val target = url
        mjpegThread = Thread({ mjpegLoop(target, iv) }, "mjpeg").apply { isDaemon = true; start() }
        L.i("Video", "Avvio MJPEG ${mask(url)}")
    }

    private fun mjpegLoop(target: String, iv: ImageView) {
        val (u, user, pass) = HttpActions.splitUserInfo(target, "", "")
        val client = HttpActions.clientFor(user, pass, streaming = true)
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val type = r.header("Content-Type").orEmpty()
                    val stream = r.body!!.byteStream()
                    if (type.startsWith("image/jpeg", true)) {
                        // Snapshot singolo: lo aggiorno in polling
                        show(BitmapFactory.decodeStream(stream), iv)
                        Thread.sleep(400)
                    } else {
                        readMultipart(stream, iv)
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!running) return
                L.w("Video", "MJPEG: ${L.describe(e, 3)}")
                main.post { onError?.invoke(e.message ?: "errore") }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** Estrae i JPEG dal flusso cercando i marker SOI (FFD8) e EOI (FFD9). */
    private fun readMultipart(input: InputStream, iv: ImageView) {
        val buf = ByteArray(16 * 1024)
        val frame = ByteArrayOutputStream(256 * 1024)
        var inFrame = false
        var prev = -1
        while (running) {
            val n = input.read(buf)
            if (n < 0) return
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xff
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        frame.reset()
                        frame.write(0xFF); frame.write(0xD8)
                    }
                } else {
                    frame.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false
                        val bytes = frame.toByteArray()
                        show(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), iv)
                    } else if (frame.size() > 8 * 1024 * 1024) {
                        inFrame = false
                    }
                }
                prev = b
            }
        }
    }

    private fun show(bmp: Bitmap?, iv: ImageView) {
        if (bmp == null || !running) return
        main.post {
            if (!running) return@post
            iv.setImageBitmap(bmp)
            lastBitmap = bmp
            frameReady()
        }
    }

    private fun mask(u: String) = u.replace(Regex("//[^/@]+@"), "//***@")

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        /** URL per cui Media3 ha rifiutato il flusso: si usa subito il lettore integrato. */
        private val nativeUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
