//package com.example.myapplication.core.deprecated
//
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//
//class VoiceSessionManager(
//    private val scope: CoroutineScope,
//    private val onSpeechStarted: (() -> Unit)? = null,
//    private val onSpeechEnded: (() -> Unit)? = null,
//    private val onInterruption: (() -> Unit)? = null,
//    private val onSessionTimeout: (() -> Unit)? = null
//) {
//
//    companion object {
//        private const val TAG = "VAD_MANAGER"
//
//        //  PARÁMETROS VAD (optimizados para respuesta rápida)
//        private const val SPEECH_ONSET_THRESHOLD = 3.5f
//        private const val SPEECH_OFFSET_THRESHOLD = 2.0f
//        private const val ONSET_FRAMES_REQUIRED = 2          // REDUCIDO (64ms en lugar de 96ms)
//        private const val OFFSET_FRAMES_REQUIRED = 25        // Silencio para terminar
//        private const val MIN_SPEECH_FRAMES = 6
//        private const val SESSION_TIMEOUT_MS = 20_000L
//        private const val POST_TTS_WINDOW_MS = 200L          //  REDUCIDO
//    }
//
//    private var sessionState = SessionState.IDLE
//    private var assistantSpeaking = false
//    private var sessionStartTime = 0L
//    private var speechStartTime = 0L
//    private var onsetCount = 0
//    private var offsetCount = 0
//    private var sessionTimeoutJob: Job? = null
//
//    //  NUEVO: Variables para early input
//    private var allowEarlyInput = false
//    private var earliestInputTime = 0L
//    private var ttsTerminoTimestamp = 0L
//
//    private enum class SessionState {
//        IDLE,      // No escuchando
//        WAITING,   // Escuchando, esperando voz
//        SPEAKING   // Detectó voz, grabando
//    }
//
//    //  NUEVO: Setter para permitir entrada temprana
//    fun setAllowEarlyInput(allow: Boolean) {
//        allowEarlyInput = allow
//        if (allow) {
//            earliestInputTime = System.currentTimeMillis()
//            Log.d(TAG, " Early input ACTIVADO - escuchando mientras TTS habla")
//        } else {
//            Log.d(TAG, " Early input DESACTIVADO - modo normal")
//        }
//    }
//
//    //  NUEVO: Setter para timestamp de TTS
//    fun setTtsEndTimestamp(timestamp: Long) {
//        ttsTerminoTimestamp = timestamp
//        Log.d(TAG, "📍 TTS terminó en: $timestamp")
//    }
//
//    fun startSession() {
//        sessionState = SessionState.WAITING
//        sessionStartTime = System.currentTimeMillis()
//        onsetCount = 0
//        offsetCount = 0
//        allowEarlyInput = false
//        ttsTerminoTimestamp = 0L
//        Log.d(TAG, " Sesión iniciada - esperando voz")
//        scheduleSessionTimeout()
//    }
//
//    fun stopSession() {
//        sessionState = SessionState.IDLE
//        assistantSpeaking = false
//        allowEarlyInput = false
//        sessionTimeoutJob?.cancel()
//        Log.d(TAG, " Sesión detenida")
//    }
//
//    fun onAssistantStartedSpeaking() {
//        assistantSpeaking = true
//        Log.d(TAG, " Asistente hablando — VAD en modo interrupción")
//    }
//
//    fun onAssistantFinishedSpeaking() {
//        assistantSpeaking = false
//        Log.d(TAG, " Asistente terminó — esperando respuesta del usuario")
//    }
//
//    fun processFrame(rms: Float) {
//        if (sessionState == SessionState.IDLE) return
//
//        val currentTime = System.currentTimeMillis()
//
//        //  LÓGICA DE ECO:
//        // Si early input está activo (TTS todavía hablando), IGNORA eco
//        // Si early input está inactivo, usa ventana anti-eco normal
//        val inEcoWindow = if (allowEarlyInput) {
//            // Mientras TTS está hablando: NO hay ventana anti-eco
//            false
//        } else if (assistantSpeaking) {
//            // Mientras asistente habla: NO registres entrada
//            true
//        } else {
//            // Después de que TTS termina: pequeña ventana anti-eco de 200ms
//            val timeSinceTtsEnd = currentTime - ttsTerminoTimestamp
//            timeSinceTtsEnd < POST_TTS_WINDOW_MS && ttsTerminoTimestamp > 0
//        }
//
//        if (inEcoWindow && !allowEarlyInput) {
//            val timeSinceTtsEnd = currentTime - ttsTerminoTimestamp
//            Log.d(TAG, "⚠️ Eco ignorado (${timeSinceTtsEnd}ms) - esperando")
//            onsetCount = 0
//            offsetCount = 0
//            return
//        }
//
//        //  DETECCIÓN DE VOZ
//        when {
//            // Detecta INICIO de voz
//            rms > SPEECH_ONSET_THRESHOLD -> {
//                onsetCount++
//                offsetCount = 0
//
//                if (onsetCount % 10 == 1) {
//                    val desde = if (allowEarlyInput) "durante TTS" else "normal"
//                    Log.d(TAG, "🎙️ Voz detectada [$desde] (RMS=$rms, onset=$onsetCount/${ONSET_FRAMES_REQUIRED})")
//                }
//
//                // Después de N frames, confirma inicio de voz
//                if (onsetCount >= ONSET_FRAMES_REQUIRED && sessionState == SessionState.WAITING) {
//                    Log.d(TAG, " Inicio de voz CONFIRMADO (${onsetCount * 32}ms)")
//                    sessionState = SessionState.SPEAKING
//                    speechStartTime = currentTime
//                    onsetCount = 0
//                    offsetCount = 0
//                    onSpeechStarted?.invoke()
//                }
//            }
//
//            // Detecta FIN de voz
//            sessionState == SessionState.SPEAKING && rms <= SPEECH_OFFSET_THRESHOLD -> {
//                offsetCount++
//
//                if (offsetCount >= OFFSET_FRAMES_REQUIRED) {
//                    val duration = currentTime - speechStartTime
//                    if (duration >= MIN_SPEECH_FRAMES * 32) {
//                        Log.d(TAG, " Fin de voz — duración ~${duration}ms")
//                        sessionState = SessionState.WAITING
//                        onsetCount = 0
//                        offsetCount = 0
//                        allowEarlyInput = false  // Desactiva early input cuando termina el usuario
//                        onSpeechEnded?.invoke()
//                    } else {
//                        Log.d(TAG, " Audio muy corto (${duration}ms) - ignorando")
//                        sessionState = SessionState.WAITING
//                        onsetCount = 0
//                        offsetCount = 0
//                    }
//                }
//            }
//
//            else -> {
//                offsetCount = 0
//            }
//        }
//
//        // Timeout de sesión
//        if (currentTime - sessionStartTime > SESSION_TIMEOUT_MS) {
//            Log.d(TAG, " Timeout de sesión (20s sin voz)")
//            sessionTimeoutJob?.cancel()
//            onSessionTimeout?.invoke()
//        }
//    }
//
//    private fun scheduleSessionTimeout() {
//        sessionTimeoutJob?.cancel()
//        sessionTimeoutJob = scope.launch {
//            delay(SESSION_TIMEOUT_MS)
//            if (sessionState != SessionState.IDLE) {
//                Log.d(TAG, "⏱️ Timeout alcanzado")
//                onSessionTimeout?.invoke()
//            }
//        }
//    }
//}