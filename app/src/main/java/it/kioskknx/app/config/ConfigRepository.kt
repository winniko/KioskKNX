package it.kioskknx.app.config

import android.content.Context
import android.content.SharedPreferences
import it.kioskknx.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ConfigRepository {
    private const val PREFS = "kioskknx"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_DISCLAIMER_TS = "disclaimer_accepted_at"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_EXIT = "exit_requested"
    private const val KEY_NIGHT_OVERRIDE = "night_override"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
    }

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config
    val current: AppConfig get() = _config.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = prefs.getString(KEY_CONFIG, null)?.let { parseOrNull(it) } ?: AppConfig()
    }

    fun save(cfg: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(cfg)).apply()
        _config.value = cfg
        L.i("Config", "Configurazione salvata")
    }

    fun update(block: (AppConfig) -> AppConfig) = save(block(current))

    fun exportJson(): String = json.encodeToString(current)

    /** Importa un JSON di configurazione. Restituisce null se ok, altrimenti il messaggio d'errore. */
    fun importJson(text: String): String? = try {
        save(json.decodeFromString(AppConfig.serializer(), text))
        null
    } catch (e: Exception) {
        e.message ?: "JSON non valido"
    }

    private fun parseOrNull(text: String): AppConfig? = try {
        json.decodeFromString(AppConfig.serializer(), text)
    } catch (e: Exception) {
        L.e("Config", "Config corrotta, uso i valori predefiniti", e)
        null
    }

    var disclaimerAcceptedAt: Long
        get() = prefs.getLong(KEY_DISCLAIMER_TS, 0L)
        set(v) { prefs.edit().putLong(KEY_DISCLAIMER_TS, v).commit() }

    val disclaimerAccepted: Boolean get() = disclaimerAcceptedAt > 0

    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(v) { prefs.edit().putBoolean(KEY_SETUP_DONE, v).apply() }

    /** Impostato da "Esci dall'App": impedisce i riavvii automatici. */
    var exitRequested: Boolean
        get() = prefs.getBoolean(KEY_EXIT, false)
        set(v) { prefs.edit().putBoolean(KEY_EXIT, v).commit() }

    /** "auto" | "on" | "off" */
    var nightOverride: String
        get() = prefs.getString(KEY_NIGHT_OVERRIDE, "auto") ?: "auto"
        set(v) { prefs.edit().putString(KEY_NIGHT_OVERRIDE, v).apply() }
}
