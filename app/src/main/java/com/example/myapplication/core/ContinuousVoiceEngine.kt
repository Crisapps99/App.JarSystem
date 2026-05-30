package com.example.myapplication.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * ContinuousVoiceEngine — Motor de voz unificado
 *
 * UN ÚNICO AudioRecord que alimenta:
 *   1. Vosk  → detecta wake word ("hey nexus", etc.) sin red
 *   2. Buffer circular → acumula audio para Whisper si se necesita
 *   3. SpeechRecognizer (Android) → transcripción continua robusta post wake-word
 *
 * FLUJO:
 *   IDLE ──[wake word]──► LISTENING (SR activo) ──[resultado]──► callback onFinalResult
 *          ◄──────────────────────────────────────────────────── (vuelve a IDLE automático)
 *
 * El AudioRecord NUNCA se cierra entre turnos. El SR se pausa/reanuda sin recrear.
 */
class ContinuousVoiceEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onSpeechStarted: () -> Unit = {},
    private val onSpeechEnded: () -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onSrCycle: () -> Unit = {}

) {
    companion object {
        private const val TAG = "CONTINUOUS_VOICE"
        const val SAMPLE_RATE = 16000
        const val FRAME_LENGTH = 512
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * 30   // 30 segundos para Whisper

        private val WAKE_WORDS = listOf("hey nexus", "nexus", "ey nexus", "nexo", "asistente")
        private const val MAX_SR_RETRIES = 3
    }

    // ── Modos del motor ──────────────────────────────────────────────────────
    private enum class EngineMode { WAKE_WORD, LISTENING, STOPPED }
    private val onSrReady: () -> Unit = {}
    // ── Estado del SpeechRecognizer ──────────────────────────────────────────
    private enum class SrState { IDLE, STARTING, LISTENING, STOPPING, ERROR }

    // ── Audio (único AudioRecord) ────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var isRunning = false

    // ── Vosk ─────────────────────────────────────────────────────────────────
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    @Volatile private var voskListo = false
    private val voskLock = ReentrantLock()
    // ── SpeechRecognizer (Android) ────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var srState = SrState.IDLE
    @Volatile private var srSesionActiva = false
    private var srLanguage = "es"
    private var srRetries = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Modo actual ──────────────────────────────────────────────────────────
    @Volatile private var engineMode = EngineMode.WAKE_WORD

    // ── Buffer circular para Whisper ─────────────────────────────────────────
    private val recordingBuffer = ShortArray(MAX_RECORDING_SAMPLES)
    private var recordingWritePos = 0
    private var recordingSampleCount = 0
    private val bufferLock = ReentrantLock()
    private val isRecording = AtomicBoolean(false)

    // ── RMS suavizado ────────────────────────────────────────────────────────
    private var lastRms = 0f
    private val smoothing = 0.15f

    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    init {
        initSpeechRecognizer()
        initVoskModel()
    }

    private fun initVoskModel() {
        StorageService.unpack(
            context, "model-es", "model",
            { model ->
                voskModel = model
                voskListo = true
                Log.d(TAG, "✅ Modelo Vosk cargado")
                if (!isRunning) startAudioCapture()
            },
            { e -> Log.e(TAG, "❌ Error cargando Vosk: ${e.message}") }
        )
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                Log.d(TAG, "✅ SpeechRecognizer creado")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creando SR: ${e.message}")
            }
        }
    }

    private fun startAudioCapture() {
        if (!hasAudioPermission()) {
            Log.e(TAG, "❌ Permiso RECORD_AUDIO no concedido")
            return
        }
        if (isRunning) return

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer * 4, FRAME_LENGTH * 8)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord no inicializado")
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRunning = true
            startVoskWakeWordMode()
            startCaptureLoop()
            Log.i(TAG, "✅ Motor de audio iniciado")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error iniciando AudioRecord: ${e.message}")
            releaseAudioRecord()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // LOOP DE CAPTURA — único, siempre corriendo
    // ────────────────────────────────────────────────────────────────────────

    private fun startCaptureLoop() {
        captureJob = engineScope.launch {
            val buffer = ShortArray(FRAME_LENGTH)
            Log.d(TAG, "🔄 Loop de captura iniciado")

            while (isRunning) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_LENGTH) ?: break
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ SecurityException leyendo audio: ${e.message}")
                    break
                }

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "❌ Error fatal AudioRecord: $read")
                    break
                }
                if (read <= 0) { delay(10); continue }

                // RMS suavizado
                val rms = calculateRMS(buffer, read)
                lastRms += (rms - lastRms) * smoothing
                mainHandler.post { onRmsChanged(lastRms) }

                // Buffer para Whisper (siempre acumula cuando isRecording)
                if (isRecording.get()) {
                    bufferLock.withLock {
                        val copyLen = minOf(read, MAX_RECORDING_SAMPLES - recordingWritePos)
                        if (copyLen > 0) {
                            System.arraycopy(buffer, 0, recordingBuffer, recordingWritePos, copyLen)
                            recordingWritePos += copyLen
                            recordingSampleCount += copyLen
                        }
                        if (recordingWritePos >= MAX_RECORDING_SAMPLES) {
                            Log.w(TAG, "⚠️ Buffer Whisper lleno (30s) — deteniendo auto")
                            isRecording.set(false)
                        }
                    }
                }

                // Vosk solo en modo WAKE_WORD — bloqueo en hilo de captura para evitar race condition
                if (engineMode == EngineMode.WAKE_WORD && voskListo) {
                    voskRecognizer?.let { rec ->
                        if (rec.acceptWaveForm(buffer, read)) {
                            val text = JSONObject(rec.result).optString("text", "")
                            val lower = text.lowercase().trim()
                            if (lower.isNotBlank() && lower != "[unk]" && WAKE_WORDS.any { lower.contains(it) }) {
                                engineMode = EngineMode.LISTENING  // bloqueo aquí, sin race condition
                                Log.i(TAG, "🔥 WAKE WORD: '$lower'")
                                mainHandler.post { onWakeWordDetected() }
                            }
                        } else {
                            val partial = JSONObject(rec.partialResult).optString("partial", "")
                            if (partial.isNotBlank()) {
                                Log.v(TAG, "Vosk parcial: $partial")
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "⏹️ Loop de captura terminado")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // VOSK — Wake Word
    // ────────────────────────────────────────────────────────────────────────

    private fun startVoskWakeWordMode() {
        if (voskModel == null) return
        engineMode = EngineMode.WAKE_WORD
        val grammar = WAKE_WORDS.joinToString("\", \"", "[\"", "\", \"[unk]\"]")
        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), grammar)
        }
        Log.d(TAG, "🎙️ Vosk: modo WAKE_WORD")
    }

    // ────────────────────────────────────────────────────────────────────────
    // SPEECH RECOGNIZER — Sesión continua (igual que HybridSpeechTranscriber)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Activa el SpeechRecognizer en modo continuo.
     * PAUSA el AudioRecord para liberar el micrófono al SR (Android no permite dos fuentes simultáneas).
     */
    fun iniciarSesionContinua(language: String = "es") {
        srLanguage = language
        srSesionActiva = true
        srRetries = 0
        engineMode = EngineMode.LISTENING
        // Pausar AudioRecord ANTES de que el SR intente tomar el micrófono
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
                Log.d(TAG, "⏸️ AudioRecord pausado para SR")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausando AudioRecord: \${e.message}")
        }
        Log.d(TAG, "🚀 Iniciando sesión SR continua")
        mainHandler.post { iniciarEscuchaSR() }
    }

    /**
     * Detiene el SR y vuelve al modo wake word.
     */
    fun detenerSesion() {
        Log.d(TAG, "🛑 Deteniendo sesión SR")
        srSesionActiva = false

        mainHandler.post {
            if (srState != SrState.STOPPING && srState != SrState.ERROR) {
                when (srState) {
                    SrState.LISTENING, SrState.STARTING -> {
                        srState = SrState.STOPPING
                        try { speechRecognizer?.cancel() } catch (e: Exception) {
                            Log.e(TAG, "Error al cancelar SR: ${e.message}")
                        }
                    }
                    else -> {}
                }
            }
            mainHandler.postDelayed({
                if (srState == SrState.STOPPING || srState == SrState.ERROR) {
                    srState = SrState.IDLE
                }
                // Reanudar AudioRecord para que Vosk vuelva a capturar
                try {
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                        audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord?.startRecording()
                        Log.d(TAG, "▶️ AudioRecord reanudado para Vosk")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reanudando AudioRecord: \${e.message}")
                }
                engineMode = EngineMode.WAKE_WORD
                startVoskWakeWordMode()
                Log.d(TAG, "✅ Sesión SR detenida → volviendo a wake word")
            }, 300)
        }
    }

    /**
     * Reinicia la escucha SR desde cualquier estado.
     * Usar después de que el TTS termine para que Jarvis vuelva a escuchar.
     */
    fun reiniciarEscucha() {
        if (!srSesionActiva) return
        Log.d(TAG, "🔄 Reiniciando SR (estado: $srState)")

        mainHandler.post {
            when (srState) {
                SrState.LISTENING, SrState.STARTING -> {
                    srState = SrState.STOPPING
                    try { speechRecognizer?.cancel() } catch (e: Exception) {
                        Log.e(TAG, "Error en cancel para reinicio: ${e.message}")
                    }
                    mainHandler.postDelayed({
                        if (srSesionActiva && srState == SrState.STOPPING) iniciarEscuchaSR()
                    }, 400)
                }
                SrState.IDLE, SrState.ERROR -> iniciarEscuchaSR()
                SrState.STOPPING -> mainHandler.postDelayed({
                    if (srSesionActiva) iniciarEscuchaSR()
                }, 500)
            }
        }
    }

    private fun iniciarEscuchaSR() {
        if (!srSesionActiva) {
            srState = SrState.IDLE
            return
        }
        if (srState != SrState.IDLE && srState != SrState.STOPPING && srState != SrState.ERROR) {
            Log.w(TAG, "iniciarEscuchaSR ignorado: estado=$srState")
            return
        }

        if (speechRecognizer == null) {
            Log.e(TAG, "SR null — recreando")
            initSpeechRecognizer()
            mainHandler.postDelayed({
                if (speechRecognizer != null) iniciarEscuchaSR()
                else onError("Error interno de reconocimiento")
            }, 300)
            return
        }

        srState = SrState.STARTING

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, srLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        speechRecognizer?.setRecognitionListener(createSrListener())

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "🎤 SR startListening()")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error startListening: ${e.message}")
            srState = SrState.ERROR
            srRetries++
            if (srRetries <= MAX_SR_RETRIES && srSesionActiva) {
                mainHandler.postDelayed({ iniciarEscuchaSR() }, 1000)
            } else {
                onError("Error fatal SR: ${e.message}")
            }
        }
    }

    private fun createSrListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (srState == SrState.STARTING || srState == SrState.STOPPING) {
                srState = SrState.LISTENING
            }
            srRetries = 0
            Log.d(TAG, "✅ SR listo")
            onSrReady
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "🎙️ Habla detectada")
            onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // El AudioRecord ya maneja RMS; esto es complementario para el SR nativo
            val normalized = (rmsdB / 100f).coerceIn(0f, 1f) * 12f
            mainHandler.post { onRmsChanged(normalized) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "✋ Fin habla SR")
            onSpeechEnded()
        }

        override fun onError(error: Int) {
            val msg = srErrorMsg(error)
            Log.e(TAG, "❌ SR error: $msg ($error), estado=$srState")

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (srSesionActiva && srState != SrState.STOPPING) {
                        srState = SrState.IDLE
                        mainHandler.postDelayed({ iniciarEscuchaSR() }, 300)
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (srSesionActiva) {
                        srState = SrState.IDLE
                        mainHandler.postDelayed({ iniciarEscuchaSR() }, 800)
                    }
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    if (srState == SrState.STOPPING) {
                        // SR cancelado intencionalmente, no recrear
                        Log.d(TAG, "ERROR_CLIENT ignorado en STOPPING")
                        srState = SrState.IDLE
                        return
                    }
                    Log.e(TAG, "ERROR_CLIENT — recreando SR")
                    srState = SrState.ERROR
                    try { speechRecognizer?.destroy() } catch (e: Exception) {}
                    speechRecognizer = null
                    mainHandler.postDelayed({
                        if (srSesionActiva) {
                            initSpeechRecognizer()
                            mainHandler.postDelayed({ iniciarEscuchaSR() }, 300)
                        }
                    }, 500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (srState == SrState.STOPPING) {
                Log.d(TAG, "onResults ignorado (deteniendo)")
                return
            }
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                val texto = matches[0]
                Log.d(TAG, "📝 SR resultado: \"$texto\"")
                srState = SrState.IDLE
                onFinalResult(texto)
            } else {
                Log.w(TAG, "⚠️ SR sin resultados")
                if (srSesionActiva && srState != SrState.STOPPING) {
                    srState = SrState.IDLE
                    mainHandler.postDelayed({ iniciarEscuchaSR() }, 300)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                Log.v(TAG, "SR parcial: \"${matches[0]}\"")
                onPartialResult(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ────────────────────────────────────────────────────────────────────────
    // BUFFER WHISPER (ContinuousAudioEngine API — mismo contrato)
    // ────────────────────────────────────────────────────────────────────────

    fun startRecording() {
        bufferLock.withLock {
            recordingWritePos = 0
            recordingSampleCount = 0
        }
        isRecording.set(true)
        Log.d(TAG, "⏺️ Grabación Whisper iniciada")
    }

    fun stopRecordingAndGetAudio(): ShortArray? {
        isRecording.set(false)
        return bufferLock.withLock {
            val count = recordingSampleCount
            if (count == 0) {
                Log.w(TAG, "⚠️ Grabación vacía")
                null
            } else {
                Log.d(TAG, "⏹️ Grabación detenida — $count samples (${count / SAMPLE_RATE}s)")
                recordingBuffer.copyOf(count)
            }
        }
    }

    fun discardRecording() {
        isRecording.set(false)
        bufferLock.withLock {
            recordingWritePos = 0
            recordingSampleCount = 0
        }
        Log.d(TAG, "🗑️ Grabación descartada")
    }

    fun isRecording(): Boolean = isRecording.get()
    fun getSampleCount(): Int = bufferLock.withLock { recordingSampleCount }

    // ────────────────────────────────────────────────────────────────────────
    // UTILIDADES
    // ────────────────────────────────────────────────────────────────────────

    fun isReady(): Boolean = hasAudioPermission() && voskListo
    fun isRunning(): Boolean = isRunning
    fun isSrSessionActive(): Boolean = srSesionActiva

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun calculateRMS(frame: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) { val s = frame[i].toDouble(); sum += s * s }
        return (sqrt(sum / size).toFloat() / 400f).coerceIn(0f, 12f)
    }

    private fun srErrorMsg(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO                    -> "Error de audio"
        SpeechRecognizer.ERROR_CLIENT                   -> "Error del cliente"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permisos"
        SpeechRecognizer.ERROR_NETWORK                  -> "Error de red"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "Timeout de red"
        SpeechRecognizer.ERROR_NO_MATCH                 -> "Sin coincidencia"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "SR ocupado"
        SpeechRecognizer.ERROR_SERVER                   -> "Error del servidor"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "Sin habla detectada"
        else                                            -> "Error desconocido: $error"
    }

    private fun releaseAudioRecord() {
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
            }
        }
        audioRecord = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────

    fun stop() {
        isRunning = false
        srSesionActiva = false
        isRecording.set(false)
        engineMode = EngineMode.STOPPED

        captureJob?.cancel()
        captureJob = null

        mainHandler.removeCallbacksAndMessages(null)

        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destruyendo SR: ${e.message}")
            }
            speechRecognizer = null
        }

        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = null
        }
        voskModel?.close()
        voskModel = null

        releaseAudioRecord()
        engineScope.cancel()

        Log.i(TAG, "⏹️ Motor detenido")
    }
}