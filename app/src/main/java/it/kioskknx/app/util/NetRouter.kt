package it.kioskknx.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.NetworkConfig
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Doppia rete: tiene attive LAN e Wi-Fi insieme e decide quale scheda usa ogni funzione.
 * - Citofono (SIP/linphone): tutto il processo viene legato alla sua scheda (bindProcessToNetwork).
 * - Pagina web: Gecko passa da un proxy SOCKS interno ([SocksProxy]) che esce dall'altra scheda.
 * - Connessioni create dall'app (HTTP, RTSP, MJPEG): usano esplicitamente la scheda scelta.
 * In modalità normale non fa nulla: si usa la rete predefinita di Android.
 */
object NetRouter {
    private const val TAG = "Rete"

    enum class Use { SIP, WEB, CAMERA }

    private lateinit var cm: ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile var wifi: Network? = null
        private set
    @Volatile var ethernet: Network? = null
        private set
    private var keepWifi: ConnectivityManager.NetworkCallback? = null
    private var keepEth: ConnectivityManager.NetworkCallback? = null
    private var boundTo: Network? = null
    private var started = false
    private val listeners = mutableListOf<() -> Unit>()

    /** Porta del proxy SOCKS per la pagina web (0 = proxy non attivo). */
    @Volatile var webProxyPort = 0
        private set

    fun addListener(l: () -> Unit) { listeners += l }

    fun start(context: Context) {
        if (started) return
        started = true
        cm = context.getSystemService(ConnectivityManager::class.java)
        // Osservo sempre le due schede (serve anche per mostrarne lo stato nel menu).
        watch(NetworkCapabilities.TRANSPORT_WIFI) { wifi = it }
        watch(NetworkCapabilities.TRANSPORT_ETHERNET) { ethernet = it }
        apply()
    }

    private fun watch(transport: Int, set: (Network?) -> Unit) {
        val req = NetworkRequest.Builder().addTransportType(transport)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { set(network); apply() } }
            override fun onLost(network: Network) { main.post { set(null); apply() } }
        })
    }

    private val cfg: NetworkConfig get() = ConfigRepository.current.network

    private fun byName(name: String): Network? = if (name == "ETHERNET") ethernet else wifi

    /** Scheda da usare per una funzione; null = rete predefinita di Android. */
    fun networkFor(use: Use): Network? {
        val c = cfg
        if (!c.dualMode) return null
        return when (use) {
            Use.SIP -> byName(c.sipNetwork)
            Use.WEB -> byName(c.webNetwork)
            Use.CAMERA -> byName(if (c.cameraNetwork == "SIP") c.sipNetwork else c.webNetwork)
        }
    }

    fun socketFactory(use: Use): SocketFactory = networkFor(use)?.socketFactory ?: SocketFactory.getDefault()

    /** DNS sulla scheda giusta (per OkHttp). */
    fun dns(use: Use): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val n = networkFor(use) ?: return Dns.SYSTEM.lookup(hostname)
            return n.getAllByName(hostname).toList()
        }
    }

    /** Applica la configurazione: legame del processo alla scheda del citofono e proxy della pagina web. */
    fun apply() {
        if (!started) return
        val c = cfg
        updateKeepAlive(c.dualMode)
        val sipNet = networkFor(Use.SIP)
        val webNet = networkFor(Use.WEB)
        if (sipNet != boundTo) {
            cm.bindProcessToNetwork(sipNet)
            boundTo = sipNet
            L.i(TAG, if (sipNet == null) "Rete predefinita di Android per tutta l'app" else "Citofono (SIP) legato a ${describe(sipNet)}")
        }
        // La pagina web ha bisogno del proxy solo se deve uscire da una scheda diversa da quella del processo.
        val needProxy = c.dualMode && webNet != null && webNet != sipNet
        if (needProxy) {
            webProxyPort = SocksProxy.start { webNet }
            L.i(TAG, "Pagina web su ${describe(webNet)} (proxy interno porta $webProxyPort)")
        } else if (webProxyPort != 0) {
            SocksProxy.stop()
            webProxyPort = 0
            L.i(TAG, "Proxy della pagina web disattivato")
        }
        listeners.forEach { runCatching { it() } }
    }

    /** In doppia rete chiedo ad Android di tenere accese entrambe le schede anche se una non è la predefinita. */
    private fun updateKeepAlive(dual: Boolean) {
        if (dual && keepWifi == null) {
            keepWifi = request(NetworkCapabilities.TRANSPORT_WIFI)
            keepEth = request(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else if (!dual && keepWifi != null) {
            listOfNotNull(keepWifi, keepEth).forEach { runCatching { cm.unregisterNetworkCallback(it) } }
            keepWifi = null
            keepEth = null
        }
    }

    private fun request(transport: Int): ConnectivityManager.NetworkCallback {
        val cb = object : ConnectivityManager.NetworkCallback() {}
        val req = NetworkRequest.Builder().addTransportType(transport)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.requestNetwork(req, cb) }.onFailure { L.w(TAG, "Impossibile tenere attiva la scheda $transport", it) }
        return cb
    }

    fun ipOf(network: Network?): String {
        network ?: return ""
        return cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address }?.hostAddress ?: ""
    }

    fun describe(network: Network?): String = when (network) {
        null -> "rete predefinita"
        ethernet -> "LAN (${ipOf(network)})"
        wifi -> "Wi-Fi (${ipOf(network)})"
        else -> "rete ${ipOf(network)}"
    }

    /** Riepilogo per il menu "Stato del pannello" e per /api/status. */
    fun summary(): String {
        val c = cfg
        val base = "LAN: ${if (ethernet != null) ipOf(ethernet) else "non collegata"} - Wi-Fi: ${if (wifi != null) ipOf(wifi) else "non collegato"}"
        if (!c.dualMode) return "$base (modalità normale)"
        return "$base\nDoppia rete: citofono su ${describe(networkFor(Use.SIP))}, pagina web su ${describe(networkFor(Use.WEB))}, popup su ${describe(networkFor(Use.CAMERA))}"
    }
}
