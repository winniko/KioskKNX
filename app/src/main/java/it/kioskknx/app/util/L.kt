package it.kioskknx.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Log con buffer circolare in memoria, consultabile dal menu e da /api/logs. */
object L {
    private const val MAX = 500
    private val buffer = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY)

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val full = "KioskKNX/$tag"
        when (level) {
            "E" -> Log.e(full, msg, t)
            "W" -> Log.w(full, msg, t)
            else -> Log.i(full, msg)
        }
        val line = "${fmt.format(Date())} $level/$tag: $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    /** Eccezione completa: catena di getCause() + prime righe dello stack della causa radice. */
    fun describe(t: Throwable, stackLines: Int = 6): String {
        val chain = generateSequence(t) { c -> c.cause?.takeIf { it !== c } }.take(8).toList()
        val sb = StringBuilder()
        chain.forEachIndexed { i, c ->
            if (i > 0) sb.append(" <- causa: ")
            sb.append(c.javaClass.name).append(": ").append(c.message)
        }
        chain.last().stackTrace.take(stackLines).forEach { sb.append("\n      at ").append(it) }
        return sb.toString()
    }
}
