package com.example.myapplication.core

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt



/**
 * ContinuousVoiceEngine — Motor de voz con Vosk (wake word) + Google Cloud STT (transcripción)
 *
 * FLUJO:
 *   WAKE_WORD ──[Vosk detecta "hey nexus"]──► LISTENING (Google Cloud STT)
 *   LISTENING ──[usuario habla]──► Google Cloud transcribe ──► onFinalResult
 *   LISTENING ──[silencio 5s]──► WAKE_WORD
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
) {
    companion object {
        private const val TAG = "CONTINUOUS_VOICE"
        const val SAMPLE_RATE = 16000
        const val FRAME_LENGTH = 512
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private val WAKE_WORDS = listOf("hey nexus", "nexus", "ey nexus", "nexo", "asistente")
    }

    // ── Modos del motor ──────────────────────────────────────────────────────
    private enum class EngineMode { WAKE_WORD,MUSIC_RECOGNITION, LISTENING, STOPPED }

    // ── Audio ────────────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var isRunning = false

    // ── Vosk (WAKE_WORD) ────────────────────────────────────────────────────
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    @Volatile private var voskListo = false
    private val voskLock = ReentrantLock()

    // ── Google Cloud STT (LISTENING) ────────────────────────────────────────
    private lateinit var grpcRecognizer: GrpcVoiceRecognizer
    @Volatile private var grpcListo = false
    @Volatile private var grpcActivo = false

    @Volatile private var engineMode = EngineMode.WAKE_WORD
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── RMS suavizado ────────────────────────────────────────────────────────
    private var lastRms = 0f
    private val smoothing = 0.15f
    // ── Music Recognition ──────────────────────────────────────────────────────
    private var musicRecognizer: MusicRecognizerRest? = null
    @Volatile private var musicRecognitionActive = false
    private var onMusicResult: ((MusicRecognizerRest.MusicResult?) -> Unit)? = null
    private var musicWaveJob: Job? = null  // ← AÑADE ESTA LÍNEA
    private val musicScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // ── Timeout para volver a WAKE_WORD ─────────────────────────────────────
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val TIMEOUT_SILENCIO_MS = 5000L  // 5 segundos sin hablar → wake word
    private val timeoutRunnable = Runnable {
        if (engineMode == EngineMode.LISTENING) {
            Log.d(TAG, " Timeout de silencio → volviendo a WAKE_WORD")
            detenerSesion()
            onSpeechEnded()
        }
    }
    @Volatile private var haEnviadoResultado = false
    private var ultimoTextoProcesado = ""
    private var ultimoTimestampProcesado = 0L
    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    init {
        initVoskModel()
        initGrpcRecognizer()
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

    // En initGrpcRecognizer, MODIFICAR onFinalResult:
    private fun initGrpcRecognizer() {
        grpcRecognizer = GrpcVoiceRecognizer(
            context,
            onPartialResult = { texto ->
                timeoutHandler.removeCallbacks(timeoutRunnable)
                mainHandler.post {
                    onPartialResult(texto)
                    timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)
                }
            },
            onFinalResult = { texto ->
                val ahora = System.currentTimeMillis()
                val textoLimpio = texto.trim()

                // ✅ PREVENIR DUPLICADOS
                if (textoLimpio == ultimoTextoProcesado &&
                    (ahora - ultimoTimestampProcesado) < 3000L) {
                    Log.d(TAG, "⏭️ Resultado duplicado de Google Cloud STT, ignorando")
                    return@GrpcVoiceRecognizer
                }

                if (haEnviadoResultado) {
                    Log.d(TAG, "⏭️ Ya se envió un resultado, ignorando")
                    return@GrpcVoiceRecognizer
                }

                if (textoLimpio.isBlank()) {
                    Log.d(TAG, "⚠️ Texto vacío, ignorando")
                    return@GrpcVoiceRecognizer
                }

                haEnviadoResultado = true
                ultimoTextoProcesado = textoLimpio
                ultimoTimestampProcesado = ahora

                Log.d(TAG, "📝 Resultado final de Google Cloud: $texto")

                // ✅ DETENER STREAMING INMEDIATAMENTE
                detenerSesion()
                mainHandler.post {
                    onFinalResult(texto)
                    // ✅ RESETEAR DESPUÉS DE 3 SEGUNDOS
                    mainHandler.postDelayed({
                        haEnviadoResultado = false
                    }, 3000)
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ Error gRPC: $error")
                mainHandler.post {
                    onError(error)
                    if (engineMode == EngineMode.LISTENING) {
                        detenerSesion()
                    }
                }
            }
        )

        engineScope.launch {
            try {
                grpcRecognizer.init()
                grpcListo = true
                Log.d(TAG, "✅ Google Cloud STT inicializado")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error inicializando gRPC: ${e.message}")
            }
        }
    }
    /**
     * Inicia el reconocimiento de música con ACRCloud
     * @param durationSegundos Duración de grabación (default 30)
     * @param onResult Callback con el resultado (null si no se reconoció)
     */
    fun iniciarReconocimientoMusica(
        durationSegundos: Int = 30,
        onResult: (MusicRecognizerRest.MusicResult?) -> Unit
    ) {
        if (musicRecognitionActive) {
            Log.w(TAG, "⚠️ Ya hay un reconocimiento de música activo")
            return
        }
        musicWaveJob = musicScope.launch {
            var counter = 0f
            while (musicRecognitionActive) {
                // Si el RMS es muy bajo (silencio), simular ondas
                if (lastRms < 0.05f) {
                    val wave = 0.3f + 0.7f * (0.5f + 0.5f * kotlin.math.sin(counter))
                    mainHandler.post { onRmsChanged(wave) }
                    counter += 0.15f
                }
                delay(50)
            }
        }
        // Guardar callback
        onMusicResult = onResult

        // Cambiar modo
        engineMode = EngineMode.MUSIC_RECOGNITION
        musicRecognitionActive = true

        // Pausar Google Cloud STT si estaba activo
        if (grpcActivo) {
            grpcActivo = false
            grpcRecognizer.stopStreaming()
        }

        // Crear el reconocedor de música
        musicRecognizer = MusicRecognizerRest(
            context = context,
            onResult = { musicResult ->
                // Resultado recibido
                musicRecognitionActive = false
                engineMode = EngineMode.WAKE_WORD
                startVoskWakeWordMode()

                // Notificar en el hilo principal
                mainHandler.post {
                    onResult(musicResult)
                    // Volver a activar wake word
                    onSpeechEnded()
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ Error en reconocimiento de música: $error")
                musicRecognitionActive = false
                engineMode = EngineMode.WAKE_WORD
                startVoskWakeWordMode()
                mainHandler.post {
                    onResult(null)
                    onSpeechEnded()
                }
            },
            onVolumeChanged = { volume ->
                mainHandler.post { onRmsChanged(volume / 100f) }
            }

        )

        // Iniciar reconocimiento con el SDK de ACRCloud (NO usa AudioRecord propio)
        musicRecognizer?.start(durationSegundos)

        mainHandler.post { onSpeechStarted() }
        Log.d(TAG, "🎵 Reconocimiento de música iniciado por $durationSegundos segundos")
    }

    /**
     * Detiene el reconocimiento de música manualmente
     */
    fun detenerReconocimientoMusica() {
        if (!musicRecognitionActive) return
        musicRecognizer?.stop()
        musicRecognizer = null
        musicRecognitionActive = false
        musicWaveJob?.cancel()
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()
        mainHandler.post { onSpeechEnded() }
        Log.d(TAG, "⏹️ Reconocimiento de música detenido")
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
            Log.i(TAG, "✅ Motor de audio iniciado en modo WAKE_WORD")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Error iniciando AudioRecord: ${e.message}")
            releaseAudioRecord()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // LOOP DE CAPTURA — único loop que alimenta Vosk, Google Cloud STT, y a ACR
    // ────────────────────────────────────────────────────────────────────────

    private fun startCaptureLoop() {
        captureJob = engineScope.launch {
            val buffer = ShortArray(FRAME_LENGTH)
            Log.d(TAG, "🔄 Loop de captura iniciado")

            while (isRunning) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_LENGTH) ?: break
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ SecurityException en lectura: ${e.message}")
                    break
                }

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "❌ Error fatal AudioRecord: $read")
                    break
                }
                if (read <= 0) { delay(10); continue }

                // ── RMS suavizado ──────────────────────────────────────────────
                val rms = calculateRMS(buffer, read)
                lastRms += (rms - lastRms) * smoothing
                mainHandler.post { onRmsChanged(lastRms) }

                // ── Enviar audio al motor correcto según modo ──────────────────
                when (engineMode) {
                    EngineMode.LISTENING -> {
                        // ✅ Google Cloud STT (transcripción)
                        if (grpcActivo && grpcListo) {
                            grpcRecognizer.sendAudioChunk(buffer.copyOf(read))
                        }
                    }
                    EngineMode.MUSIC_RECOGNITION -> {  // ← NUEVO CASO
                        // Enviar audio a ACRCloud
                        if (musicRecognitionActive && musicRecognizer != null) {
                            // El SDK de ACRCloud maneja su propia grabación,
                            // así que NO enviamos el audio desde aquí.
                            // Solo actualizamos el RMS para la UI
                            val rms = calculateRMS(buffer, read)
                            lastRms += (rms - lastRms) * smoothing
                            mainHandler.post { onRmsChanged(lastRms) }
                        }
                    }

                    EngineMode.WAKE_WORD -> {
                        // ✅ Vosk (solo wake word)
                        if (voskListo) {
                            voskLock.withLock {
                                voskRecognizer?.let { rec ->
                                    if (rec.acceptWaveForm(buffer, read)) {
                                        val text = JSONObject(rec.result).optString("text", "")
                                        val lower = text.lowercase().trim()
                                        if (lower.isNotBlank() && WAKE_WORDS.any { lower.contains(it) }) {
                                            Log.i(TAG, "🔊 WAKE WORD DETECTADO: '$lower'")
                                            // ✅ Activar Google Cloud STT
                                            engineMode = EngineMode.LISTENING
                                            mainHandler.post {
                                                onWakeWordDetected()
                                                iniciarSesionContinua()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EngineMode.STOPPED -> break
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
        Log.d(TAG, "🔇 Vosk: modo WAKE_WORD")
    }

    // ────────────────────────────────────────────────────────────────────────
    // GOOGLE CLOUD STT — Streaming Continuo
    // ────────────────────────────────────────────────────────────────────────

    fun iniciarSesionContinua(language: String = "es-ES") {
        if (!grpcListo) {
            Log.w(TAG, "⏳ gRPC no listo aún")
            return
        }

        if (grpcActivo) {
            Log.w(TAG, "⚠️ gRPC ya activo")
            return
        }

        engineMode = EngineMode.LISTENING
        grpcActivo = true
        grpcRecognizer.startStreaming(language)

        // ✅ Iniciar timeout de silencio
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)

        mainHandler.post { onSpeechStarted() }
        Log.d(TAG, "🎙️ Google Cloud STT iniciado (timeout: ${TIMEOUT_SILENCIO_MS}ms)")
    }

    fun detenerSesion() {
        if (!grpcActivo) return

        Log.d(TAG, "⏹️ Deteniendo sesión Google Cloud STT")
        grpcActivo = false
        grpcRecognizer.stopStreaming()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()
        mainHandler.post { onSpeechEnded() }
        Log.d(TAG, "✅ Volviendo a modo WAKE_WORD")
    }

    fun reiniciarEscucha() {
        if (!grpcActivo) {
            Log.d(TAG, "⚠️ gRPC no activo, iniciando sesión")
            iniciarSesionContinua("es-ES")
            return
        }

        Log.d(TAG, "🔄 Reiniciando Google Cloud STT")
        // ✅ No detener, solo resetear timeout
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)
    }

    // ────────────────────────────────────────────────────────────────────────
    // UTILIDADES
    // ────────────────────────────────────────────────────────────────────────

    fun isReady(): Boolean = hasAudioPermission() && voskListo && grpcListo
    fun isRunning(): Boolean = isRunning
    fun isSrSessionActive(): Boolean = grpcActivo
    fun isWakeWordMode(): Boolean = engineMode == EngineMode.WAKE_WORD

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun calculateRMS(frame: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return (sqrt(sum / size).toFloat() / 400f).coerceIn(0f, 12f)
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
        Log.d(TAG, "🛑 Deteniendo motor")
        isRunning = false
        grpcActivo = false
        engineMode = EngineMode.STOPPED

        captureJob?.cancel()
        captureJob = null

        musicWaveJob?.cancel()
        musicWaveJob = null
        musicRecognitionActive = false
        musicRecognizer?.stop()
        musicRecognizer = null
        timeoutHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacksAndMessages(null)

        grpcRecognizer.destroy()

        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = null
        }
        voskModel?.close()
        voskModel = null

        releaseAudioRecord()
        engineScope.cancel()
        musicScope.cancel()

        Log.i(TAG, "✅ Motor detenido")
    }
}