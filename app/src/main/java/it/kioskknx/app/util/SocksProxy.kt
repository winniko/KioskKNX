package it.kioskknx.app.util

import android.net.Network
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Proxy SOCKS5 minimo in ascolto solo su 127.0.0.1: la pagina web (Gecko) lo usa per uscire
 * dalla scheda di rete scelta ([NetRouter]) invece che da quella legata al processo.
 */
object SocksProxy {
    private const val TAG = "Proxy"
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "socks").apply { isDaemon = true } }
    @Volatile private var networkProvider: () -> Network? = { null }

    /** Avvia (o riusa) il proxy; restituisce la porta locale. */
    @Synchronized
    fun start(network: () -> Network?): Int {
        networkProvider = network
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15_000
            val inp = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            // saluto: VER, NMETHODS, METHODS -> nessuna autenticazione
            if (inp.readUnsignedByte() != 5) return
            inp.skipBytes(inp.readUnsignedByte())
            out.write(byteArrayOf(5, 0))
            // richiesta: VER CMD RSV ATYP ADDR PORT (solo CONNECT)
            if (inp.readUnsignedByte() != 5) return
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte()
            val host = when (inp.readUnsignedByte()) {
                1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
                4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                else -> return
            }
            val port = inp.readUnsignedShort()
            if (cmd != 1) { reply(out, 7); return }

            val net = networkProvider()
            remote = try {
                val addr = net?.getByName(host) ?: InetAddress.getByName(host)
                val sock = net?.socketFactory?.createSocket() ?: Socket()
                sock.connect(InetSocketAddress(addr, port), 8_000)
                sock
            } catch (e: Exception) {
                L.w(TAG, "Connessione a $host:$port non riuscita: ${e.message}")
                reply(out, 5)
                return
            }
            reply(out, 0)
            client.soTimeout = 0
            val r = remote
            pool.execute { pipe(r.getInputStream(), out, client, r) }
            pipe(inp, r.getOutputStream(), client, r)
        } catch (_: Exception) {
        } finally {
            if (remote == null) runCatching { client.close() }
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }
}
