package it.kioskknx.app.display

import it.kioskknx.app.config.ConfigRepository
import java.util.Calendar

/** Modalità notte / Non disturbare: per orario oppure forzata via API. */
object NightMode {
    fun isActive(): Boolean {
        when (ConfigRepository.nightOverride) {
            "on" -> return true
            "off" -> return false
        }
        val n = ConfigRepository.current.night
        if (!n.enabled) return false
        val start = minutes(n.start) ?: return false
        val end = minutes(n.end) ?: return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (start <= end) now in start until end else now >= start || now < end
    }

    /** "HH:mm" -> minuti dalla mezzanotte. */
    fun minutes(hhmm: String): Int? {
        val p = hhmm.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
