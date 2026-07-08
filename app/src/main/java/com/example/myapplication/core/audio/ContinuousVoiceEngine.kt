// ContinuousVoiceEngine.kt — VERSIÓN CORREGIDA

package com.example.myapplication.core.audio

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

        // SOLO ESTAS DOS PALABRAS CLAVE
        private val WAKE_WORDS = listOf("hey nexus", "nexus", "[unk]")
        private val WAKE_REGEX = Regex("""(?i)^(hey\s+nexus|nexus)\b""")
        private val COOLDOWN_MS = 1500L
        private val RMS_THRESHOLD = 0.08f
        private const val VAD_FRAMES_INICIO = 2
        private const val VAD_SILENCIO_FIN_MS = 1800L
        private const val VAD_WARMUP_MS = 450L
        private const val WAKE_WORD_WARMUP_MS = 800L
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
    private val TIMEOUT_SILENCIO_MS = 5000L
    // agregar junto a timeoutRunnable
    private val resultadoTimeoutRunnable = Runnable {
        if (engineMode == EngineMode.LISTENING && !grpcActivo) {
            Log.d(TAG, " Sin resultado final tras VAD — forzando recuperación")
            detenerSesion(notificarSpeechEnded = true)
        }
    }
    private val timeoutRunnable = Runnable {
        if (engineMode == EngineMode.LISTENING) {
            Log.d(TAG, " Timeout de silencio en Google Cloud STT")
            detenerSesion(notificarSpeechEnded = true)
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
                // ✅ Usar callback en lugar de referencia directa a ui
                mainHandler.post {
                    onPartialResult(parcial)  // ← Esto notifica al controlador
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
            }
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

                // ── RMS ──────────────────────────────────────────────────────
                val rms = calculateRMS(buffer, read)
                lastRms += (rms - lastRms) * smoothing
                mainHandler.post { onRmsChanged(lastRms) }

                // ── Modos ────────────────────────────────────────────────────
                when (engineMode) {
                    EngineMode.LISTENING -> {
                        if (grpcActivo && grpcListo) {
                            procesarFrameVad(rms, buffer, read)
                        }
                    }
                    EngineMode.MUSIC_RECOGNITION -> {
                        // El RMS ya se actualizó, no hacemos nada más
                    }
                    EngineMode.WAKE_WORD -> {
                        val calentando = System.currentTimeMillis() - wakeWordArmedAt < WAKE_WORD_WARMUP_MS
                        if (voskListo && !wakeWordCooldown && !calentando && !ttsReproduciendo && rms > RMS_THRESHOLD) {      voskLock.withLock {
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
    /**
     * Reinicia la captura de audio (AudioRecord) y el modo WAKE_WORD
     * sin detener completamente el motor (no cancela scopes).
     * Útil para reiniciar el audio después de una pausa.
     */
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
            // No había loop corriendo, procede directo
            continuarReinicioAudio()
        } else {
            // Espera a que el loop actual salga de verdad antes de tocar el AudioRecord
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


    /**
     * Reinicia el motor al modo WAKE_WORD, deteniendo cualquier sesión activa
     */
    fun reiniciarWakeWord() {
        Log.d(TAG, "Reiniciando modo WAKE_WORD")

        // 1. Detener cualquier sesión STT activa
        if (grpcActivo) {
            grpcActivo = false
            try {
                grpcRecognizer.stopStreaming()
            } catch (_: Exception) {}
        }

        // 2. Resetear VAD
        resetVad()

        // 3. Cambiar al modo WAKE_WORD
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()

        // 4. Limpiar timeouts
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)

        Log.d(TAG, "✅ Modo WAKE_WORD reactivado")
    }

    /**
     * Verifica si el motor está en modo WAKE_WORD
     */
    fun isWakeWordMode(): Boolean = engineMode == EngineMode.WAKE_WORD


    // En ContinuousVoiceEngine.kt - procesarFrameVad()
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
                    grpcRecognizer.sendAudioChunk(buffer.copyOf(read))
                }
            } else {
                vadFramesSobreUmbral = 0
            }
        } else {
            // ✅ SIEMPRE enviar audio a gRPC mientras se esté hablando
            grpcRecognizer.sendAudioChunk(buffer.copyOf(read))

            if (sobreUmbral) {
                vadTimestampUltimoSonido = ahora
            } else {
                val silencioMs = ahora - vadTimestampUltimoSonido
                if (silencioMs >= VAD_SILENCIO_FIN_MS) {
                    vadHablando = false
                    Log.d(TAG, " VAD: fin de habla REAL (silencio ${silencioMs}ms)")
                    mainHandler.post { onSpeechEnded() }

                    // Cerrar el stream YA: así el servidor finaliza la transcripción
                    // de inmediato en vez de esperar su propio timeout de ~10s
                    if (grpcActivo) {
                        grpcActivo = false
                        grpcRecognizer.stopStreaming()
                        Log.d(TAG, " Sesión STT detenida por fin de habla")
                    }
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    timeoutHandler.postDelayed(resultadoTimeoutRunnable, 1500L)
                }
            }
        }
    }

    // En ContinuousVoiceEngine.kt - handleWakeWordDetected()
    private fun handleWakeWordDetected() {
        //  : Permitir interrupción del TTS
        mainHandler.post {
            // Notificar al controlador que debe interrumpir el TTS
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
        // Asegurar que no haya residuos de la sesión anterior
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

        //  Pequeño delay para que los recursos se liberen
        mainHandler.postDelayed({
            // Iniciar sesión de Google Cloud STT
            if (grpcListo) {
                grpcActivo = true
                grpcRecognizer.startStreaming("es-ES")

                timeoutHandler.removeCallbacks(timeoutRunnable)
                timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_SILENCIO_MS)

                Log.d(TAG, " Google Cloud STT iniciado (post-wake word)")
            } else {
                Log.e(TAG, " gRPC no listo para iniciar sesión")
            }
        }, 150) //  150ms de delay para liberar recursos
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
            // Pequeña pausa para que se liberen los recursos
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

        // 1. Detenemos gRPC solo si estaba activo
        if (grpcActivo) {
            grpcActivo = false
            try {
                grpcRecognizer.stopStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Error deteniendo stream: ${e.message}")
            }
        }

        // 2. Limpiamos los timeouts
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.removeCallbacks(resultadoTimeoutRunnable)

        // 3. Siempre forzamos el regreso al modo Wake Word
        engineMode = EngineMode.WAKE_WORD
        startVoskWakeWordMode()
        resetVad()

        // 4. Notificamos a la UI/Controller
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
    /**
     * Reinicia el motor de audio (AudioRecord) y vuelve a modo WAKE_WORD
     * Útil cuando el motor se detuvo completamente y queremos reactivarlo
     */
    fun restart() {
        Log.d(TAG, "Reiniciando motor de audio completo")

        // 1. Si está corriendo, detener primero
        if (isRunning) {
            stop()
        }

        // 2. Reiniciar el audio
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