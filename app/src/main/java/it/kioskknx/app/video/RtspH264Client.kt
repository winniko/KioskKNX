package it.kioskknx.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import it.kioskknx.app.util.L
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Errore RTSP con codice di stato del server (es. 401). */
class RtspStatusException(val method: String, val status: Int, reason: String) :
    IOException("$method $status $reason")

/**
 * Lettore RTSP minimale per flussi H.264 (TCP interleaved), usato quando Media3 rifiuta il flusso:
 * tipico degli NVR Hikvision che non mettono `sprop-parameter-sets` nell'SDP. SPS/PPS vengono letti
 * dal flusso; supporta packetization-mode 0 e 1 (NAL singoli, STAP-A, FU-A) e auth Digest/Basic.
 */
@OptIn(UnstableApi::class)
class RtspH264Client(
    private val url: String,
    private val surface: Surface,
    private val listener: Listener,
) {
    interface Listener {
        fun onFirstFrame()
        fun onVideoSize(width: Int, height: Int)
        fun onError(e: Throwable)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null

    // stato RTSP
    private var cseq = 1
    private var session: String? = null
    private var authHeader: ((String, String) -> String)? = null
    private var user = ""
    private var pass = ""
    private var requestBase = "" // URL senza credenziali

    // stato video
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var gotKeyframe = false
    private var firstFrameSent = false
    private val au = ArrayList<ByteArray>()
    private var auTs = -1L
    private var fuBuf: ByteArrayOutputStream? = null
    private var baseTs = -1L

    fun start() {
        running = true
        thread = Thread({ run() }, "rtsp-h264").apply { isDaemon = true; start() }
    }

    /** Ferma tutto: TEARDOWN, chiusura socket, attesa del thread, rilascio decoder. */
    fun stop() {
        if (!running && thread == null) return
        running = false
        runCatching { socket?.close() }
        val t = thread
        thread = null
        if (t != null && t != Thread.currentThread()) runCatching { t.join(1500) }
    }

    // ------------------------------------------------------------------ thread principale

    private fun run() {
        try {
            val uri = URI(url)
            val host = uri.host ?: throw IOException("URL RTSP senza host")
            val port = if (uri.port > 0) uri.port else 554
            uri.rawUserInfo?.let { ui ->
                user = URLDecoder.decode(ui.substringBefore(':'), "UTF-8")
                pass = if (ui.contains(':')) URLDecoder.decode(ui.substringAfter(':'), "UTF-8") else ""
            }
            val baseUrl = URI(uri.scheme, null, host, port, uri.path, uri.query, null).toString()
            requestBase = baseUrl

            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))

            val desc = request(out, inp, "DESCRIBE", baseUrl, mapOf("Accept" to "application/sdp"))
            val sdp = desc.body
            val contentBase = desc.headers["content-base"]?.trim() ?: baseUrl
            val track = parseSdp(sdp) ?: throw IOException("Nessuna traccia H.264 nell'SDP")
            val trackUrl = when {
                track.control.isEmpty() || track.control == "*" -> contentBase
                track.control.startsWith("rtsp://", true) -> track.control
                else -> contentBase.trimEnd('/') + "/" + track.control
            }
            track.sprop?.let { sp ->
                val parts = sp.split(',').mapNotNull { runCatching { Base64.decode(it.trim(), Base64.DEFAULT) }.getOrNull() }
                parts.forEach { onNal(it, isConfigOnly = true) }
            }

            val setup = request(out, inp, "SETUP", trackUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            session = setup.headers["session"]?.substringBefore(';')?.trim()
            request(out, inp, "PLAY", contentBase, mapOf("Range" to "npt=0.000-"))
            L.i(TAG, "RTSP in riproduzione (lettore H.264 integrato, payload ${track.payloadType})")

            var lastKeepAlive = System.currentTimeMillis()
            while (running) {
                if (System.currentTimeMillis() - lastKeepAlive > 25_000) {
                    sendRequest(out, "GET_PARAMETER", contentBase, emptyMap())
                    lastKeepAlive = System.currentTimeMillis()
                }
                val b = inp.readUnsignedByte()
                if (b == '$'.code) {
                    val ch = inp.readUnsignedByte()
                    val len = inp.readUnsignedShort()
                    val pkt = ByteArray(len)
                    inp.readFully(pkt)
                    if (ch == 0) onRtp(pkt, track.payloadType)
                    drainOutput()
                } else {
                    // risposta RTSP testuale (keep-alive): la leggo e la scarto
                    readResponseAfterFirstByte(inp, b)
                }
            }
        } catch (e: Throwable) {
            if (running) {
                running = false
                main.post { listener.onError(e) }
            }
        } finally {
            runCatching { socket?.let { s -> if (!s.isClosed && session != null) sendRequest(s.getOutputStream(), "TEARDOWN", requestBase, emptyMap()) } }
            runCatching { socket?.close() }
            socket = null
            releaseCodec()
        }
    }

    // ------------------------------------------------------------------ RTSP

    private class Response(val status: Int, val reason: String, val headers: Map<String, String>, val body: String)

    private fun sendRequest(out: OutputStream, method: String, reqUrl: String, extra: Map<String, String>) {
        val sb = StringBuilder("$method $reqUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: KioskKNX\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        session?.let { sb.append("Session: $it\r\n") }
        authHeader?.let { sb.append("Authorization: ${it(method, reqUrl)}\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun request(out: OutputStream, inp: DataInputStream, method: String, reqUrl: String, extra: Map<String, String>): Response {
        sendRequest(out, method, reqUrl, extra)
        var r = readResponse(inp)
        if (r.status == 401 && authHeader == null && user.isNotEmpty()) {
            authHeader = buildAuth(r.headers["www-authenticate"] ?: "")
            sendRequest(out, method, reqUrl, extra)
            r = readResponse(inp)
        }
        if (r.status != 200) throw RtspStatusException(method, r.status, r.reason)
        return r
    }

    private fun readResponse(inp: DataInputStream): Response {
        var b = inp.readUnsignedByte()
        while (b == '$'.code) { // pacchetti interleaved arrivati prima della risposta
            inp.readUnsignedByte()
            inp.skipBytes(inp.readUnsignedShort())
            b = inp.readUnsignedByte()
        }
        return readResponseAfterFirstByte(inp, b)
    }

    private fun readResponseAfterFirstByte(inp: DataInputStream, first: Int): Response {
        val head = ByteArrayOutputStream()
        head.write(first)
        while (true) {
            head.write(inp.readUnsignedByte())
            val a = head.toByteArray()
            val n = a.size
            if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
            if (n > 16_384) throw IOException("Risposta RTSP troppo lunga")
        }
        val lines = head.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        val statusParts = lines.first().split(' ', limit = 3)
        val status = statusParts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Risposta RTSP non valida: ${lines.first()}")
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { l -> if (l.contains(':')) headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) ByteArray(len).also { inp.readFully(it) }.toString(Charsets.UTF_8) else ""
        return Response(status, statusParts.getOrNull(2) ?: "", headers, body)
    }

    private fun buildAuth(challenge: String): (String, String) -> String {
        if (challenge.startsWith("Basic", true)) {
            val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            return { _, _ -> "Basic $token" }
        }
        val p = HashMap<String, String>()
        Regex("(\\w+)=(\"([^\"]*)\"|([^,\\s]+))").findAll(challenge).forEach { m ->
            p[m.groupValues[1].lowercase()] = m.groupValues[3].ifEmpty { m.groupValues[4] }
        }
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val opaque = p["opaque"]
        var nc = 0
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        return { method, uri ->
            val ha1 = md5("$user:$realm:$pass")
            val ha2 = md5("$method:$uri")
            val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                nc++
                val ncS = "%08x".format(nc)
                val cnonce = "%08x".format((Math.random() * Int.MAX_VALUE).toInt())
                sb.append(", response=\"${md5("$ha1:$nonce:$ncS:$cnonce:$qop:$ha2")}\", qop=$qop, nc=$ncS, cnonce=\"$cnonce\"")
            } else {
                sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
            }
            opaque?.let { sb.append(", opaque=\"$it\"") }
            sb.toString()
        }
    }

    private class Track(val payloadType: Int, val control: String, val sprop: String?)

    private fun parseSdp(sdp: String): Track? {
        var inVideo = false
        var pt = -1
        var control = ""
        var sprop: String? = null
        var h264 = false
        for (raw in sdp.lines()) {
            val l = raw.trim()
            if (l.startsWith("m=")) {
                if (inVideo && h264) break
                inVideo = l.startsWith("m=video")
                pt = if (inVideo) l.split(' ').getOrNull(3)?.toIntOrNull() ?: -1 else -1
                control = ""; sprop = null; h264 = false
                continue
            }
            if (!inVideo) continue
            when {
                l.startsWith("a=rtpmap:") && l.contains("H264", true) -> {
                    h264 = true
                    pt = l.substringAfter(':').substringBefore(' ').toIntOrNull() ?: pt
                }
                l.startsWith("a=control:") -> control = l.substringAfter("a=control:").trim()
                l.startsWith("a=fmtp:") -> Regex("sprop-parameter-sets=([^;\\s]+)").find(l)?.let { sprop = it.groupValues[1] }
            }
        }
        return if (inVideo && h264 && pt >= 0) Track(pt, control, sprop) else null
    }

    // ------------------------------------------------------------------ RTP / H.264

    private fun onRtp(pkt: ByteArray, payloadType: Int) {
        if (pkt.size < 12 || (pkt[0].toInt() and 0xC0) != 0x80) return
        if ((pkt[1].toInt() and 0x7F) != payloadType) return
        val cc = pkt[0].toInt() and 0x0F
        var off = 12 + cc * 4
        if ((pkt[0].toInt() and 0x10) != 0 && pkt.size >= off + 4) {
            val extLen = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
            off += 4 + extLen * 4
        }
        var end = pkt.size
        if ((pkt[0].toInt() and 0x20) != 0) end -= pkt[pkt.size - 1].toInt() and 0xFF
        if (off >= end) return
        val ts = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
            ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
        val marker = (pkt[1].toInt() and 0x80) != 0

        if (auTs >= 0 && ts != auTs) flushAu()
        auTs = ts

        val type = pkt[off].toInt() and 0x1F
        when (type) {
            in 1..23 -> onNal(pkt.copyOfRange(off, end))
            24 -> { // STAP-A
                var p = off + 1
                while (p + 2 <= end) {
                    val sz = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
                    p += 2
                    if (sz == 0 || p + sz > end) break
                    onNal(pkt.copyOfRange(p, p + sz))
                    p += sz
                }
            }
            28 -> { // FU-A
                if (off + 2 > end) return
                val ind = pkt[off].toInt() and 0xFF
                val hdr = pkt[off + 1].toInt() and 0xFF
                if (hdr and 0x80 != 0) {
                    fuBuf = ByteArrayOutputStream().apply { write((ind and 0xE0) or (hdr and 0x1F)) }
                }
                fuBuf?.write(pkt, off + 2, end - off - 2)
                if (hdr and 0x40 != 0) {
                    fuBuf?.toByteArray()?.let { onNal(it) }
                    fuBuf = null
                }
            }
        }
        if (marker) flushAu()
    }

    private fun onNal(nal: ByteArray, isConfigOnly: Boolean = false) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1F) {
            7 -> {
                if (sps != null && !sps.contentEquals(nal) && codec != null) {
                    L.i(TAG, "SPS cambiato (nuova risoluzione?): ricreo il decoder")
                    releaseCodec()
                }
                sps = nal
            }
            8 -> pps = nal
        }
        if (!isConfigOnly) au.add(nal)
    }

    private fun flushAu() {
        val nals = ArrayList(au)
        au.clear()
        val ts = auTs
        if (nals.isEmpty()) return
        val hasIdr = nals.any { (it[0].toInt() and 0x1F) == 5 }
        if (!gotKeyframe) {
            if (!hasIdr) return // aspetto il primo fotogramma chiave
            gotKeyframe = true
        }
        if (codec == null && !createCodec()) {
            gotKeyframe = false
            return
        }
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(20_000)
        if (idx < 0) return // decoder pieno: scarto (flusso live)
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        var size = 0
        for (n in nals) {
            if (buf.remaining() < n.size + 4) break
            buf.put(START_CODE); buf.put(n)
            size += n.size + 4
        }
        if (baseTs < 0) baseTs = ts
        val ptsUs = ((ts - baseTs) and 0xFFFFFFFFL) * 1_000_000L / 90_000L
        c.queueInputBuffer(idx, 0, size, ptsUs, if (hasIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
    }

    private fun createCodec(): Boolean {
        val s = sps ?: return false
        val p = pps ?: return false
        val data = NalUnitUtil.parseSpsNalUnit(s, 0, s.size)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, data.width, data.height)
        fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + s))
        fmt.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + p))
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, surface, null, 0)
        c.start()
        codec = c
        L.i(TAG, "Decoder H.264 avviato ${data.width}x${data.height} (${c.name})")
        val w = data.width
        val h = data.height
        main.post { listener.onVideoSize(w, h) }
        return true
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx >= 0) {
                c.releaseOutputBuffer(idx, true)
                if (!firstFrameSent) {
                    firstFrameSent = true
                    main.post { if (running) listener.onFirstFrame() }
                }
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val w = f.getInteger(MediaFormat.KEY_WIDTH)
                val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                main.post { listener.onVideoSize(w, h) }
            } else {
                break
            }
        }
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        gotKeyframe = false
        baseTs = -1
    }

    companion object {
        private const val TAG = "Video"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
