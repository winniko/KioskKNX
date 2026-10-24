package it.kioskknx.app.media

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.display.NightMode
import it.kioskknx.app.util.L
import java.util.Locale

/** Sintesi vocale in italiano per gli annunci della domotica. */
object TtsManager {
    private const val TAG = "TTS"
    private var tts: TextToSpeech? = null
    @Volatile var ready = false
        private set
    @Volatile var italianAvailable = false
        private set
    @Volatile private var failed = false
    private val pending = mutableListOf<Triple<String, Int, Float>>()

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale.ITALIAN)
                italianAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                L.i(TAG, "Sintesi vocale pronta (italiano: $italianAvailable)")
                synchronized(pending) {
                    pending.forEach { (txt, vol, rate) -> speakNow(txt, vol, rate) }
                    pending.clear()
                }
            } else {
                failed = true
                synchronized(pending) { pending.clear() }
                L.w(TAG, "Nessun motore di sintesi vocale disponibile (status $status)")
            }
        }
    }

    /** Restituisce false se l'annuncio è stato saltato (es. modalità notte). */
    fun speak(text: String, volumePercent: Int = -1, rate: Float = -1f, force: Boolean = false): Boolean {
        if (text.isBlank() || failed) return false
        if (!force && NightMode.isActive() && !ConfigRepository.current.night.ttsEnabled) {
            L.i(TAG, "Annuncio saltato (modalità notte)")
            return false
        }
        val vol = if (volumePercent in 0..100) volumePercent else ConfigRepository.current.audio.ttsVolumePercent
        val r = if (rate > 0) rate else ConfigRepository.current.audio.ttsSpeechRate
        if (!ready) {
            synchronized(pending) { pending += Triple(text, vol, r) }
            return true
        }
        speakNow(text, vol, r)
        return true
    }

    private fun speakNow(text: String, vol: Int, rate: Float) {
        val t = tts ?: return
        SoundManager.setMediaVolume(vol)
        t.setSpeechRate(rate)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        t.speak(text, TextToSpeech.QUEUE_ADD, params, "kiosk-${System.currentTimeMillis()}")
        L.i(TAG, "Annuncio: $text")
    }

    fun stop() {
        tts?.stop()
    }
}
