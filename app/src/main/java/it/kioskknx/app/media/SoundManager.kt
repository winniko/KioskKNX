package it.kioskknx.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.display.NightMode
import it.kioskknx.app.util.L
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Suoneria di chiamata e suoni di notifica/allarme comandabili via API. */
object SoundManager {
    private const val TAG = "Suoni"
    private lateinit var app: Context
    private var ringPlayer: MediaPlayer? = null
    private var soundPlayer: MediaPlayer? = null
    /** Amplificatori collegati ai player (si rilasciano insieme al player). */
    private val boosters = HashMap<MediaPlayer, android.media.audiofx.LoudnessEnhancer>()
    private val audio by lazy { app.getSystemService(AudioManager::class.java) }

    val names = listOf("doorbell", "ring", "notification", "alarm", "beep", "chime")

    fun init(context: Context) {
        app = context.applicationContext
        Thread { generateSounds() }.start()
    }

    // ------------------------------------------------------------------ suoneria chiamata

    fun startRing() {
        stopRing()
        val night = NightMode.isActive()
        val nightCfg = ConfigRepository.current.night
        if (night && nightCfg.silentRing) return
        val vol = if (night) nightCfg.ringVolumePercent else ConfigRepository.current.audio.ringVolumePercent
        val uri = when (ConfigRepository.current.audio.ringtone) {
            "DOORBELL" -> Uri.fromFile(soundFile("doorbell"))
            "BEEP" -> Uri.fromFile(soundFile("beep"))
            else -> RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: Uri.fromFile(soundFile("doorbell"))
        }
        // Canale "allarme": Android non lo abbassa mentre è aperto l'audio della chiamata (early media).
        ringPlayer = createPlayer(uri, loop = true, volumePercent = vol, alarm = true)
    }

    fun stopRing() {
        ringPlayer?.let { releasePlayer(it) }
        ringPlayer = null
    }

    // ------------------------------------------------------------------ suoni API

    /** Riproduce un suono predefinito ([names]) o un URL audio. */
    fun play(name: String, url: String = "", loop: Boolean = false, volumePercent: Int = -1): Boolean {
        stopSound()
        val uri: Uri = when {
            url.isNotBlank() -> Uri.parse(url)
            name == "ring" -> RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE) ?: Uri.fromFile(soundFile("doorbell"))
            name == "notification" -> RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION) ?: Uri.fromFile(soundFile("chime"))
            name in names -> Uri.fromFile(soundFile(name))
            else -> return false
        }
        val vol = if (volumePercent in 0..100) volumePercent else ConfigRepository.current.audio.ringVolumePercent
        soundPlayer = createPlayer(uri, loop, vol)
        return soundPlayer != null
    }

    fun stopSound() {
        soundPlayer?.let { releasePlayer(it) }
        soundPlayer = null
    }

    fun stopAll() {
        stopRing()
        stopSound()
    }

    val isPlaying: Boolean get() = (ringPlayer != null) || (soundPlayer?.isPlaying == true)

    fun setMediaVolume(percent: Int) = setStreamPercent(AudioManager.STREAM_MUSIC, percent)

    /** Imposta il volume di un canale audio di Android in percentuale (0-100). */
    fun setStreamPercent(stream: Int, percent: Int) {
        try {
            val max = audio.getStreamMaxVolume(stream)
            audio.setStreamVolume(stream, (max * percent.coerceIn(0, 100) / 100f).roundToInt(), 0)
        } catch (e: Exception) {
            L.w(TAG, "Volume non impostabile (canale $stream)", e)
        }
    }

    private fun createPlayer(uri: Uri, loop: Boolean, volumePercent: Int, alarm: Boolean = false): MediaPlayer? = try {
        setStreamPercent(if (alarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC, volumePercent)
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(app, uri)
            isLooping = loop
            setOnCompletionListener { if (!loop) releasePlayer(it); if (soundPlayer == it) soundPlayer = null }
            setOnPreparedListener { boost(it); it.start() }
            prepareAsync()
        }
    } catch (e: Exception) {
        L.w(TAG, "Riproduzione fallita $uri", e)
        null
    }

    // ------------------------------------------------------------------ generazione suoni WAV (offline)

    private fun releasePlayer(p: MediaPlayer) {
        synchronized(boosters) { boosters.remove(p) }?.let { runCatching { it.release() } }
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    /** Amplifica il player oltre il volume massimo di Android (LoudnessEnhancer). */
    private fun boost(p: MediaPlayer) {
        val db = ConfigRepository.current.audio.ringGainDb.coerceIn(0, 20)
        if (db == 0) return
        try {
            val le = android.media.audiofx.LoudnessEnhancer(p.audioSessionId)
            le.setTargetGain(db * 100)
            le.enabled = true
            synchronized(boosters) { boosters[p] = le }
            L.i(TAG, "Amplificazione suono +$db dB")
        } catch (e: Exception) {
            L.w(TAG, "Amplificazione non disponibile su questo dispositivo", e)
        }
    }

    // v2: suoni portati al livello massimo (prima ~60%)
    private fun soundFile(name: String) = File(app.filesDir, "sounds/${name}_v2.wav")

    private fun generateSounds() {
        val dir = File(app.filesDir, "sounds").apply { mkdirs() }
        val rate = 22050
        fun tone(freqs: List<Pair<Double, Double>>, decay: Double, gap: Double = 0.0): ShortArray {
            val out = ArrayList<Short>()
            for ((f, dur) in freqs) {
                val n = (rate * dur).toInt()
                for (i in 0 until n) {
                    val t = i.toDouble() / rate
                    val env = if (decay > 0) exp(-t * decay) else 1.0
                    val v = (sin(2 * PI * f * t) * 0.6 + sin(4 * PI * f * t) * 0.15) * env
                    out += (v * Short.MAX_VALUE * 0.8).toInt().toShort()
                }
                repeat((rate * gap).toInt()) { out += 0 }
            }
            // normalizzo al 95% del massimo: suono più forte senza distorsione
            val peak = out.maxOfOrNull { kotlin.math.abs(it.toInt()) }?.coerceAtLeast(1) ?: 1
            val k = Short.MAX_VALUE * 0.95 / peak
            return ShortArray(out.size) { (out[it] * k).toInt().toShort() }
        }
        val sounds = mapOf(
            "doorbell" to tone(listOf(659.0 to 0.9, 523.0 to 1.4), decay = 2.5),
            "chime" to tone(listOf(784.0 to 0.35, 988.0 to 0.35, 1175.0 to 0.8), decay = 3.0),
            "beep" to tone(listOf(1000.0 to 0.15, 1000.0 to 0.15), decay = 0.0, gap = 0.12),
            "alarm" to tone((1..6).flatMap { listOf(880.0 to 0.25, 660.0 to 0.25) }, decay = 0.0),
        )
        for ((name, pcm) in sounds) {
            val f = File(dir, "${name}_v2.wav")
            if (f.exists() && f.length() > 44) continue
            writeWav(f, pcm, rate)
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        DataOutputStream(FileOutputStream(f).buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            val dataLen = pcm.size * 2
            o.writeBytes("RIFF"); le32(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(dataLen)
            pcm.forEach { le16(it.toInt()) }
        }
    }
}
