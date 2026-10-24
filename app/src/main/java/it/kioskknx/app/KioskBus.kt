package it.kioskknx.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Stato della chiamata SIP mostrato dall'overlay. */
sealed class CallUiState {
    data object Idle : CallUiState()
    data class Incoming(val remote: String, val remoteIp: String) : CallUiState()
    /** Chiamata in uscita dal dialer, non ancora risposta. */
    data class Outgoing(val remote: String, val video: Boolean) : CallUiState()
    data class Active(val remote: String, val remoteIp: String, val startedAt: Long) : CallUiState()
}

/** Comandi inviati all'Activity (da API REST, servizio, motion, ecc.). */
sealed class UiCommand {
    data object Reload : UiCommand()
    data class Navigate(val url: String) : UiCommand()
    data object GoHome : UiCommand()
    data object ScreenOn : UiCommand()
    data object Screensaver : UiCommand()
    data object ScreenOff : UiCommand()
    data class Brightness(val percent: Int) : UiCommand()
    /** [transport]: "tcp" | "udp" | "auto" | "" (= impostazione dell'app). */
    data class CameraPopup(val url: String, val seconds: Int, val title: String, val transport: String = "") : UiCommand()
    data object CloseCameraPopup : UiCommand()
    data object UserActivity : UiCommand()
    data object ApplyConfig : UiCommand()
    data object BringToFront : UiCommand()
}

/** Bus di stato condiviso fra servizio e Activity (stesso processo). */
object KioskBus {
    val callState = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val sipRegistration = MutableStateFlow("disattivato")
    val sipVideoReceived = MutableStateFlow(false)

    /** "on" | "dim" | "screensaver" | "off" */
    val screenState = MutableStateFlow("on")
    val brightness = MutableStateFlow(-1)
    val lastMotionAt = MutableStateFlow(0L)
    val currentUrl = MutableStateFlow("")
    val pageLoaded = MutableStateFlow(false)
    /** Motore web in uso: "SYSTEM" | "GECKO" */
    val webEngine = MutableStateFlow("")
    val activityVisible = MutableStateFlow(false)
    val lockTaskActive = MutableStateFlow(false)
    val cameraPopupOpen = MutableStateFlow(false)

    private val _commands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<UiCommand> = _commands

    fun send(cmd: UiCommand) {
        _commands.tryEmit(cmd)
    }
}
