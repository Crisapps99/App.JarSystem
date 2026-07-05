package com.example.myapplication.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JarPhase {
                    INTRO,
    WAITING_WAKEWORD,
    LISTENING,
    THINKING,
    SPEAKING }

data class JarState(
    val phase: JarPhase = JarPhase.INTRO,
    val statusText: String = "NEXUS",
    val statusColor: String = "#4DEEE9",
    val transcription: String = "Hola, soy Nexus. Tu asistente personal para el móvil.\nEstoy aquí para ayudarte con llamadas, mensajes, apps y mucho más.",
    val instruction: String = "",
    val orbRms: Float = 0f,
    val isOmitirVisible: Boolean = false,
    val isScreenVisible: Boolean = true
)

class JarVm : ViewModel() {
    private val _state = MutableStateFlow(JarState())
    val state = _state.asStateFlow()

    fun updateRms(rms: Float) {
        _state.update { it.copy(orbRms = rms) }
    }

    fun setPhase(phase: JarPhase) {
        _state.update { it.copy(phase = phase) }
    }

    fun hideScreen() {
        _state.update { it.copy(isScreenVisible = false) }
    }

    fun updateUi(
        transcription: String? = null,
        status: String? = null,
        color: String? = null,
        instruction: String? = null,
        omitir: Boolean? = null
    ) {
        _state.update { it.copy(
            transcription = transcription ?: it.transcription,
            statusText = status ?: it.statusText,
            statusColor = color ?: it.statusColor,
            instruction = instruction ?: it.instruction,
            isOmitirVisible = omitir ?: it.isOmitirVisible
        ) }
    }
}