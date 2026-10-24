package it.kioskknx.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** Osserva la rete (LAN o Wi-Fi) e notifica i cambi agli altri moduli. */
object NetworkMonitor {
    private const val TAG = "Rete"
    val connected = MutableStateFlow(true)
    val ip = MutableStateFlow("")
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    fun addListener(l: (Boolean) -> Unit) { listeners += l }

    fun start(context: Context) {
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        registered = true
        ip.value = DeviceInfo.ipAddress()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { notify(true) }
            override fun onLost(network: Network) { notify(cm.activeNetwork != null) }
        })
    }

    private fun notify(up: Boolean) = main.post {
        val newIp = DeviceInfo.ipAddress()
        val changed = up != connected.value || newIp != ip.value
        connected.value = up
        ip.value = newIp
        if (changed) {
            L.i(TAG, if (up) "Rete disponibile ($newIp)" else "Rete persa")
            listeners.forEach { runCatching { it(up) } }
        }
    }
}
