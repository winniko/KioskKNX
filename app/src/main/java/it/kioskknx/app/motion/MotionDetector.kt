package it.kioskknx.app.motion

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import it.kioskknx.app.config.MotionConfig
import it.kioskknx.app.util.L
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Sensore di movimento con la fotocamera frontale: analizza la luminanza a bassa risoluzione
 * e pochi fotogrammi al secondo, confrontando ogni frame con il precedente.
 */
class MotionDetector(
    private val activity: androidx.activity.ComponentActivity,
    private val onMotion: () -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var cfg = MotionConfig()
    private var prev: IntArray? = null
    private var lastFrameAt = 0L
    private var lastMotionAt = 0L
    @Volatile var running = false
        private set

    fun start(config: MotionConfig) {
        cfg = config
        if (running) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            L.w(TAG, "Permesso fotocamera mancante: motion disattivato")
            return
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> { L.w(TAG, "Nessuna fotocamera disponibile"); return@addListener }
                }
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { img -> analyze(img) }
                p.unbindAll()
                p.bindToLifecycle(activity as LifecycleOwner, selector, analysis)
                running = true
                prev = null
                L.i(TAG, "Sensore di movimento attivo (sensibilità ${cfg.sensitivity})")
            } catch (e: Exception) {
                L.e(TAG, "Fotocamera non disponibile (forse usata da un'altra app)", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        prev = null
    }

    fun update(config: MotionConfig) {
        cfg = config
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val interval = 1000L / cfg.analysisFps.coerceIn(1, 15)
            if (now - lastFrameAt < interval) return
            lastFrameAt = now

            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = img.width
            val h = img.height
            val gw = 40
            val gh = 30
            val cur = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val y = gy * h / gh
                for (gx in 0 until gw) {
                    val x = gx * w / gw
                    cur[gy * gw + gx] = buf.get(y * rowStride + x * pixStride).toInt() and 0xff
                }
            }
            val p = prev
            prev = cur
            if (p == null) return

            var changed = 0
            for (i in cur.indices) if (abs(cur[i] - p[i]) > PIXEL_DELTA) changed++
            val ratio = changed.toFloat() / cur.size
            // sensibilità 1 -> serve il 30% dei punti cambiati; 100 -> basta lo 0,5%
            val s = cfg.sensitivity.coerceIn(1, 100)
            val threshold = 0.30f - (s - 1) * (0.295f / 99f)
            if (ratio >= threshold && now - lastMotionAt > cfg.cooldownSec * 1000L) {
                lastMotionAt = now
                activity.runOnUiThread(onMotion)
            }
        } catch (e: Exception) {
            L.w(TAG, "Analisi frame", e)
        } finally {
            img.close()
        }
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "Motion"
        private const val PIXEL_DELTA = 25
    }
}
