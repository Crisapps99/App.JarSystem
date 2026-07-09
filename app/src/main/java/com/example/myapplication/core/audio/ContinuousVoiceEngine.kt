// ContinuousVoiceEngine.kt — VERSIÓN CORREGIDA

package com.example.myapplication.core.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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

        private val WAKE_WORDS = listOf("hey nexus", "nexus", "[unk]")
        private val WAKE_REGEX = Regex("""(?i)^(hey\s+nexus|nexus)\b""")
        private val COOLDOWN_MS = 1500L
        private val RMS_THRESHOLD = 0.08f
        private const val VAD_FRAMES_INICIO = 2
        private const val VAD_SILENCIO_FIN_MS = 1800L
        private const val VAD_WARMUP_MS = 450L
        private const val WAKE_WORD_WARMUP_MS = 800L
        private const val TIMEOUT_SILENCIO_MS = 8000L
        private const val RESULTADO_TIMEOUT_MS = 3000L
    }

    // ── Modos del motor ──────────────────────────────────────────────────────
    private enum class EngineMode { WAKE_WORD, MUSIC_RECOGNITION, LISTENING, STOPPED }

    // ── Audio ────────────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var isRunning = false
    private val onSpeechEndedCallback = onSpeechEnded

    // ── Vosk (WAKE_WORD) ────────────────────────────────────────────────────
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    @Volatile private var voskListo = false
    private val voskLock = ReentrantLock()
    @Volatile private var wakeWordCooldown = false

    // ── Google Cloud STT (LISTENING) ────────────────────────────────────────
    private lateinit var grpcRecognizer: GrpcVoiceRecognizer
    @Volatile private var grpcListo = false
    @Volatile private var grpcActivo = false
    @Volatile private var ttsReproduciendo = false
    @Volatile private var engineMode = EngineMode.WAKE_WORD
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Audio Focus (DUCKING) ──────────────────────────────────────────────
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    @Volatile private var hasAudioFocus = false
    @Volatile private var duckingActivo = false  // ✅ AÑADIDO

    // ── RMS ──────────────────────────────────────────────────────────────────
    private var lastRms = 0f
    private val smoothing = 0.15f
    @Volatile private var wakeWordArmedAt = 0L

    // ── VAD ──────────────────────────────────────────────────────────────────
    @Volatile private var vadHablando = false
    private var vadFramesSobreUmbral = 0
    private var vadTimestampUltimoSonido = 0L
    private var sesionListeningStartTimestamp = 0L

    private fun resetVad() {
        vadHablando = false
        vadFramesSobreUmbral = 0
        vadTimestampUltimoSonido = 0L
    }

    // ── Music Recognition ──────────────────────────────────────────────────
    private var musicRecognizer: MusicRecognizerRest? = null
    @Volatile private var musicRecognitionActive = false
    private var onMusicResult: ((MusicRecognizerRest.MusicResult?) -> Unit)? = null
    private var musicWaveJob: Job? = null
    private val musicScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Timeout ─────────────────────────────────────────────────────────────
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable {
        if (engineMode == EngineMode.LISTENING) {
            Log.d(TAG, " Timeout de silencio en Google Cloud STT")
            detenerSesion(notificarSpeechEnded = false)
        }
    }

    private val resultadoTimeoutRunnable = object : Runnable {
        override fun run() {
            if (engineMode == EngineMode.LISTENING) {
                if (!vadHablando) {
                    Log.d(TAG, "Sin resultado final tras VAD — forzando recuperación")
                    detenerSesion(notificarSpeechEnded = true)
                } else {
                    Log.d(TAG, "Voz activa, extendiendo timeout...")
                    timeoutHandler.removeCallbacks(this)
                    timeoutHandler.postDelayed(this, RESULTADO_TIMEOUT_MS)
                }
            }
        }
    }

    @Volatile private var haEnviadoResultado = false
    private var ultimoTextoProcesado = ""
    private var ultimoTimestampProcesado = 0L

    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initVoskModel()
        initGrpcRecognizer()
        // ✅ Activar ducking permanente al iniciar
        activarDuckingPermanente()
    }

    // ─── DUCKING ─────────────────────────────────────────────────────────────

    private fun activarDuckingPermanente() {
        if (duckingActivo) {
            Log.d(TAG, "🔊 Ducking ya estaba activo")
            return
        }

        val manager = audioManager ?: return

        audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.d(TAG, "🎵 Audio focus recuperado")
                    hasAudioFocus = true
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "🎵 Audio focus perdido permanentemente")
                    hasAudioFocus = false
                    mainHandler.postDelayed({
                        if (!hasAudioFocus && duckingActivo) {
                            solicitarDucking()
                        }
                    }, 1000)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "🎵 Audio focus perdido temporalmente")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "🎵 Ducking activado - otra app reproduce audio")
                    hasAudioFocus = true
                }
            }
        }

        solicitarDucking()
        duckingActivo = true
        Log.d(TAG, "🔊 Ducking permanente ACTIVADO")
    }

    private fun solicitarDucking() {
        val manager = audioManager ?: return
        val listener = audioFocusListener ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .build()

            val result = manager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "🎵 Audio focus (ducking) solicitado: ${if (hasAudioFocus) "✅ CONCEDIDO" else "❌ DENEGADO"}")
        } else {
            @Suppress("DEPRECATION")
            val result = manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "🎵 Audio focus (ducking) solicitado (legacy): ${if (hasAudioFocus) "✅ CONCEDIDO" else "❌ DENEGADO"}")
        }
    }

    private fun desactivarDucking() {
        if (!duckingActivo) return

        val manager = audioManager ?: return

        try {
            audioFocusListener?.let { listener ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { request ->
                        manager.abandonAudioFocusRequest(request)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.abandonAudioFocus(listener)
                }
            }
            hasAudioFocus = false
            duckingActivo = false
            Log.d(TAG, "🔊 Ducking DESACTIVADO")
        } catch (e: Exception) {
            Log.e(TAG, "Error desactivando ducking: ${e.message}")
        }
    }

    // ✅ Método público para activar ducking (usado desde el controlador)
    fun setDuckingEnabled(enabled: Boolean) {
        if (enabled) {
            if (!duckingActivo) {
                activarDuckingPermanente()
            } else if (!hasAudioFocus) {
                solicitarDucking()
            }
            Log.d(TAG, "🔊 Ducking solicitado: activo=$duckingActivo, focus=$hasAudioFocus")
        } else {
            if (duckingActivo) {
                desactivarDucking()
            }
        }
    }

    // ✅ Método para reactivar ducking (se llama desde reiniciarWakeWord)
    fun reactivarDucking() {
        if (!duckingActivo) {
            activarDuckingPermanente()
        } else if (!hasAudioFocus) {
            solicitarDucking()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // VOSK MODEL
    // ────────────────────────────────────────────────────────────────────────

    private fun initVoskModel() {
        StorageService.unpack(
            context, "model-es", "model",
            { model ->
                voskModel = model
                voskListo = true
                Log.d(TAG, " Modelo Vosk cargado")
                if (!isRunning) startAudioCapture()
            },
            { e -> Log.e(TAG, " Error cargando Vosk: ${e.message}") }
        )
    }

    fun setTtsReproduciendo(activo: Boolean) {
        ttsReproduciendo = activo
    }

    private fun initGrpcRecognizer() {
        grpcRecognizer = GrpcVoiceRecognizer(
            context,
            onPartialResult = { parcial ->
                Log.v(TAG, "Parcial: '$parcial'")
                mainHandler.post {
                    onPartialResult(parcial)
                }
                timeoutHandler.removeCallbacks(timeoutRunnable)
            },
            onSpeechStarted = {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                mainHandler.post { onSpeechStarted() }
            },
            onSpeechEnded = {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)
                mainHandler.post { onSpeechEnded() }
            },
            onFinalResult = { texto ->
                timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)
                val ahora = System.currentTimeMillis()
                val textoLimpio = texto.trim()

                if (textoLimpio == ultimoTextoProcesado &&
                    (ahora - ultimoTimestampProcesado) < 3000L) {
                    Log.d(TAG, " Resultado duplicado ignorado")
                    return@GrpcVoiceRecognizer
                }

                if (haEnviadoResultado) {
                    Log.d(TAG, " Ya se envió un resultado")
                    return@GrpcVoiceRecognizer
                }

                if (textoLimpio.isBlank()) {
                    return@GrpcVoiceRecognizer
                }

                haEnviadoResultado = true
                ultimoTextoProcesado = textoLimpio
                ultimoTimestampProcesado = ahora

                Log.d(TAG, " Resultado final: $texto")
                detenerSesion(notificarSpeechEnded = false)
                mainHandler.post {
                    onFinalResult(texto)
                    mainHandler.postDelayed({ haEnviadoResultado = false }, 3000)
                }
            },
            onError = { error ->
                Log.e(TAG, " Error gRPC: $error")
                mainHandler.post {
                    onError(error)
                    if (engineMode == EngineMode.LISTENING) detenerSesion()
                }
            }
        )

        engineScope.launch {
            try {
                grpcRecognizer.init()
                grpcListo = true
                Log.d(TAG, " Google Cloud STT inicializado")
            } catch (e: Exception) {
                Log.e(TAG, " Error inicializando gRPC: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // MÚSICA
    // ────────────────────────────────────────────────────────────────────────

    fun iniciarReconocimientoMusica(
        durationSegundos: Int = 30,
        onResult: (MusicRecognizerRest.MusicResult?) -> Unit
    ) {
        if (musicRecognitionActive) {
            Log.w(TAG, " Ya hay reconocimiento de música activo")
            return
        }

        musicWaveJob = musicScope.launch {
            var counter = 0f
            while (musicRecognitionActive) {
                if (lastRms < 0.05f) {
                    val wave = 0.3f + 0.7f * (0.5f + 0.5f * kotlin.math.sin(counter))
                    mainHandler.post { onRmsChanged(wave) }
                    counter += 0.15f
                }
                delay(50)
            }
        }

        onMusicResult = onResult
        engineMode = EngineMode.MUSIC_RECOGNITION
        musicRecognitionActive = true

        if (grpcActivo) {
            grpcActivo = false
            grpcRecognizer.stopStreaming()
        }

        musicRecognizer = MusicRecognizerRest(
            context = context,
            onResult = { musicResult ->
                musicRecognitionActive = false
                engineMode = EngineMode.WAKE_WORD
                startVoskWakeWordMode()
                mainHandler.post {
                    onResult(musicResult)
                    onSpeechEnded()
                }
            },
            onError = { error ->
                Log.e(TAG, " Error en reconocimiento de música: $error")
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
            },

        )

        musicRecognizer?.start(durationSegundos)
        mainHandler.post { onSpeechStarted() }
        Log.d(TAG, " Reconocimiento de música iniciado por $durationSegundos segundos")
    }

    fun detenerReconocimientoMusica() {
        if (!musicRecognitionActive) return
        musicRecognizer?.stop()
        musicRecognizer = null
        musicRecognitionActive = false
        musicWaveJob?.cancel()
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()
        mainHandler.post { onSpeechEnded() }
        Log.d(TAG, " Reconocimiento de música detenido")
    }

    // ────────────────────────────────────────────────────────────────────────
    // AUDIO CAPTURE LOOP
    // ────────────────────────────────────────────────────────────────────────

    private fun startAudioCapture() {
        if (!hasAudioPermission()) {
            Log.e(TAG, " Permiso RECORD_AUDIO no concedido")
            return
        }
        if (isRunning && audioRecord != null) {
            Log.d(TAG, "AudioCapture ya en ejecución")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer * 4, FRAME_LENGTH * 8)

        try {
            releaseAudioRecord()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, " AudioRecord no inicializado")
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRunning = true
            startVoskWakeWordMode()
            startCaptureLoop()
            Log.i(TAG, " Motor de audio iniciado en modo WAKE_WORD")

        } catch (e: SecurityException) {
            Log.e(TAG, " Error iniciando AudioRecord: ${e.message}")
            releaseAudioRecord()
        }
    }

    private fun startCaptureLoop() {
        captureJob = engineScope.launch {
            val buffer = ShortArray(FRAME_LENGTH)
            Log.d(TAG, " Loop de captura iniciado")

            while (isRunning) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_LENGTH) ?: break
                } catch (e: SecurityException) {
                    Log.e(TAG, " SecurityException en lectura: ${e.message}")
                    break
                }

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, " Error fatal AudioRecord: $read")
                    break
                }
                if (read <= 0) { delay(10); continue }

                val rms = calculateRMS(buffer, read)
                lastRms += (rms - lastRms) * smoothing
                mainHandler.post { onRmsChanged(lastRms) }

                when (engineMode) {
                    EngineMode.LISTENING -> {
                        if (grpcActivo && grpcListo) {
                            procesarFrameVad(rms, buffer, read)
                        }
                    }
                    EngineMode.MUSIC_RECOGNITION -> {
                        // El RMS ya se actualizó
                    }
                    EngineMode.WAKE_WORD -> {
                        val calentando = System.currentTimeMillis() - wakeWordArmedAt < WAKE_WORD_WARMUP_MS
                        if (voskListo && !wakeWordCooldown && !calentando && !ttsReproduciendo && rms > RMS_THRESHOLD) {
                            voskLock.withLock {
                                voskRecognizer?.let { rec ->
                                    val partial = JSONObject(rec.partialResult).optString("partial", "").lowercase().trim()
                                    if (partial.isNotEmpty() && partial.matches(WAKE_REGEX)) {
                                        Log.i(TAG, " WAKE WORD DETECTADO (partial): '$partial'")
                                        handleWakeWordDetected()
                                        return@let
                                    }

                                    if (rec.acceptWaveForm(buffer, read)) {
                                        val text = JSONObject(rec.result).optString("text", "").lowercase().trim()
                                        if (text.isNotEmpty() && text.matches(WAKE_REGEX)) {
                                            Log.i(TAG, " WAKE WORD DETECTADO (final): '$text'")
                                            handleWakeWordDetected()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EngineMode.STOPPED -> break
                }
            }
            Log.d(TAG, " Loop de captura terminado")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // REINICIO
    // ────────────────────────────────────────────────────────────────────────

    private var reiniciando = false

    fun reiniciarAudio() {
        if (reiniciando) {
            Log.d(TAG, "reiniciarAudio() ya en curso, ignorando llamada duplicada")
            return
        }
        reiniciando = true
        Log.d(TAG, "Reiniciando captura de audio (sin detener motor)")

        val jobAnterior = captureJob
        captureJob = null

        if (jobAnterior == null || jobAnterior.isCompleted) {
            continuarReinicioAudio()
        } else {
            jobAnterior.invokeOnCompletion {
                mainHandler.post { continuarReinicioAudio() }
            }
            jobAnterior.cancel()
        }
    }

    private fun continuarReinicioAudio() {
        releaseAudioRecord()
        if (engineMode != EngineMode.WAKE_WORD) {
            engineMode = EngineMode.WAKE_WORD
            startVoskWakeWordMode()
        }
        startAudioCapture()
        reiniciando = false
        Log.d(TAG, "✅ Captura de audio reiniciada")
    }

    fun reiniciarWakeWord() {
        Log.d(TAG, "Reiniciando modo WAKE_WORD")

        // ✅ Reactivar ducking
        reactivarDucking()

        if (grpcActivo) {
            grpcActivo = false
            try {
                grpcRecognizer.stopStreaming()
            } catch (_: Exception) {}
        }

        resetVad()
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()

        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)

        Log.d(TAG, "✅ Modo WAKE_WORD reactivado")
    }

    fun isWakeWordMode(): Boolean = engineMode == EngineMode.WAKE_WORD

    // ────────────────────────────────────────────────────────────────────────
    // VAD
    // ────────────────────────────────────────────────────────────────────────

    private fun procesarFrameVad(rms: Float, buffer: ShortArray, read: Int) {
        val ahora = System.currentTimeMillis()
        if (ahora - sesionListeningStartTimestamp < VAD_WARMUP_MS) return

        val sobreUmbral = rms > RMS_THRESHOLD

        if (!vadHablando) {
            if (sobreUmbral) {
                vadFramesSobreUmbral++
                if (vadFramesSobreUmbral >= VAD_FRAMES_INICIO) {
                    vadHablando = true
                    vadTimestampUltimoSonido = ahora
                    vadFramesSobreUmbral = 0
                    Log.d(TAG, " VAD: inicio de habla REAL (rms=$rms)")
                    mainHandler.post { onSpeechStarted() }

                    if (!grpcActivo) {
                        grpcActivo = true
                        grpcRecognizer.startStreaming("es-ES")
                        Log.d(TAG, " Stream reactivado por VAD")
                    }
                    grpcRecognizer.sendAudioChunk(buffer.copyOf(read))
                    timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)
                }
            } else {
                vadFramesSobreUmbral = 0
            }
        } else {
            if (grpcActivo) {
                grpcRecognizer.sendAudioChunk(buffer.copyOf(read))
            } else {
                Log.w(TAG, "Stream cerrado pero VAD dice que hablamos, reactivando...")
                grpcActivo = true
                grpcRecognizer.startStreaming("es-ES")
                grpcRecognizer.sendAudioChunk(buffer.copyOf(read))
            }

            if (sobreUmbral) {
                vadTimestampUltimoSonido = ahora
                timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)
            } else {
                val silencioMs = ahora - vadTimestampUltimoSonido
                if (silencioMs >= VAD_SILENCIO_FIN_MS) {
                    vadHablando = false
                    Log.d(TAG, " VAD: fin de habla REAL (silencio ${silencioMs}ms)")
                    mainHandler.post { onSpeechEnded() }

                    if (grpcActivo) {
                        timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)
                        timeoutHandler.postDelayed(resultadoTimeoutRunnable, RESULTADO_TIMEOUT_MS)
                        Log.d(TAG, " Esperando resultado final...")
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // WAKE WORD
    // ────────────────────────────────────────────────────────────────────────

    private fun handleWakeWordDetected() {
        mainHandler.post {
            onWakeWordDetected()
        }

        wakeWordCooldown = true
        mainHandler.postDelayed({ wakeWordCooldown = false }, COOLDOWN_MS)

        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(),
                WAKE_WORDS.joinToString("\", \"", "[\"", "\"]"))
        }

        if (musicRecognitionActive) {
            musicRecognitionActive = false
            musicRecognizer?.stop()
            musicRecognizer = null
            musicWaveJob?.cancel()
        }

        engineMode = EngineMode.LISTENING
        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = null
        }

        if (grpcActivo) {
            grpcActivo = false
            try {
                grpcRecognizer.stopStreaming()
            } catch (e: Exception) {
                Log.d(TAG, "Error cerrando stream anterior: ${e.message}")
            }
        }

        resetVad()
        haEnviadoResultado = false
        sesionListeningStartTimestamp = System.currentTimeMillis()

        mainHandler.postDelayed({
            if (grpcListo) {
                grpcActivo = true
                grpcRecognizer.startStreaming("es-ES")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)
                Log.d(TAG, " Google Cloud STT iniciado (post-wake word)")
            } else {
                Log.e(TAG, " gRPC no listo para iniciar sesión")
            }
        }, 300)
    }

    // ────────────────────────────────────────────────────────────────────────
    // VOSK — Wake Word
    // ────────────────────────────────────────────────────────────────────────

    private fun startVoskWakeWordMode() {
        if (voskModel == null) return
        engineMode = EngineMode.WAKE_WORD
        wakeWordArmedAt = System.currentTimeMillis()
        val grammar = WAKE_WORDS.joinToString("\", \"", "[\"", "\"]")
        voskLock.withLock {
            voskRecognizer?.close()
            voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), grammar)
        }
        Log.d(TAG, " Vosk: modo WAKE_WORD (gramática: $grammar)")
    }

    // ────────────────────────────────────────────────────────────────────────
    // GOOGLE CLOUD STT
    // ────────────────────────────────────────────────────────────────────────

    fun iniciarSesionContinua(language: String = "es-ES") {
        if (!grpcListo) {
            Log.w(TAG, " gRPC no listo aún")
            return
        }
        if (grpcActivo) {
            Log.d(TAG, " Sesión activa detectada, reiniciando...")
            detenerSesion(notificarSpeechEnded = false)
            Thread.sleep(100)
        }

        engineMode = EngineMode.LISTENING
        grpcActivo = true
        resetVad()
        sesionListeningStartTimestamp = System.currentTimeMillis()
        grpcRecognizer.startStreaming(language)

        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)

        Log.d(TAG, " Google Cloud STT iniciado, esperando VAD real (timeout total: ${TIMEOUT_SILENCIO_MS}ms)")
    }

    fun detenerSesion(notificarSpeechEnded: Boolean = true) {
        Log.d(TAG, " Deteniendo sesión y volviendo a WAKE_WORD")

        // ✅ NO desactivar ducking al detener sesión (solo al stop completo)

        if (grpcActivo) {
            grpcActivo = false
            try {
                grpcRecognizer.stopStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Error deteniendo stream: ${e.message}")
            }
        }

        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)

        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()
        resetVad()

        if (notificarSpeechEnded) {
            mainHandler.post { onSpeechEnded() }
        }
        Log.d(TAG, " Sesión detenida, modo WAKE_WORD activo")
    }

    fun reiniciarEscucha() {
        if (!grpcActivo) {
            iniciarSesionContinua("es-ES")
            return
        }
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)
    }

    fun restart() {
        Log.d(TAG, "Reiniciando motor de audio completo")
        if (isRunning) {
            stop()
        }
        startAudioCapture()
        Log.d(TAG, "✅ Motor de audio reiniciado")
    }

    // ────────────────────────────────────────────────────────────────────────
    // UTILIDADES
    // ────────────────────────────────────────────────────────────────────────

    fun isReady(): Boolean = hasAudioPermission() && voskListo && grpcListo
    fun isRunning(): Boolean = isRunning
    fun isSrSessionActive(): Boolean = grpcActivo

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
        Log.d(TAG, " Deteniendo motor")

        // ✅ Desactivar ducking al detener completamente
        desactivarDucking()

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

        Log.i(TAG, " Motor detenido")
    }
}