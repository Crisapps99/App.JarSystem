//package com.example.myapplication.core
//
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import android.util.Log
//import kotlinx.coroutines.*
//import java.util.concurrent.atomic.AtomicBoolean
//import java.util.concurrent.locks.ReentrantLock
//import kotlin.concurrent.withLock
//import kotlin.math.sqrt
//
///**
// * ContinuousAudioEngine — Motor de audio unificado y continuo
// *
// * PROBLEMA QUE RESUELVE:
// * El código anterior tenía TRES fuentes de audio compitiendo:
// *   1. Porcupine (via VoiceProcessor) → su propio AudioRecord
// *   2. CobraVADProcessor              → otro AudioRecord separado
// *   3. SpeechRecognizer               → otro AudioRecord más
// *
// * Esto causaba conflictos, errores ERROR_RECOGNIZER_BUSY, y recreaciones
// * constantes del SpeechRecognizer.
// *
// * SOLUCIÓN:
// * Un único AudioRecord que captura audio y distribuye los frames
// * a quien los necesite vía listeners. El micrófono NUNCA se cierra
// * entre turnos de conversación.
// *
// * FLUJO:
// *   AudioRecord (único) → frame disponible → notifica listeners
// *       ├── PorcupineListener  (cuando está en modo IDLE)
// *       ├── VADListener        (siempre activo para detectar habla)
// *       └── RecordingListener  (cuando está grabando para Whisper)
// */
//class ContinuousAudioEngine(
//    private val onFrameAvailable: (ShortArray) -> Unit,  // Callback con cada frame
//    private val onRmsChanged: (Float) -> Unit             // Callback con nivel de audio
//) {
//    companion object {
//        const val SAMPLE_RATE = 16000
//        const val FRAME_LENGTH = 512          // Frames de 512 samples = ~32ms a 16kHz
//        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
//        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
//        private const val TAG = "AUDIO_ENGINE"
//
//        // Buffer circular: 30 segundos de audio máximo para grabación
//        // 16000 samples/s × 30s = 480,000 samples
//        const val MAX_RECORDING_SAMPLES = 16000 * 30
//    }
//
//    private var audioRecord: AudioRecord? = null
//    private val isRunning = AtomicBoolean(false)
//    private var captureJob: Job? = null
//    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//
//    // Buffer circular para grabación de audio destinado a Whisper
//    // Usamos un lock para acceso thread-safe
//    private val recordingBuffer = ShortArray(MAX_RECORDING_SAMPLES)
//    private var recordingWritePos = 0      // Posición donde escribir el siguiente frame
//    private var recordingSampleCount = 0   // Total de samples acumulados en esta grabación
//    private val bufferLock = ReentrantLock()
//
//    // Estado de grabación
//    private val isRecording = AtomicBoolean(false)
//
//    /**
//     * Inicia el motor de audio.
//     * El AudioRecord se crea aquí y permanece abierto indefinidamente.
//     */
//    fun start() {
//        if (isRunning.get()) {
//            Log.w(TAG, "⚠️ Motor ya en ejecución")
//            return
//        }
//
//        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
//        // Buffer grande para evitar underruns: 4× el mínimo
//        val bufferSize = maxOf(minBuffer * 4, FRAME_LENGTH * 4 * 2)
//
//        try {
//            audioRecord = AudioRecord(
//                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Optimizado para voz
//                SAMPLE_RATE,
//                CHANNEL_CONFIG,
//                AUDIO_FORMAT,
//                bufferSize
//            )
//
//            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
//                Log.e(TAG, "❌ AudioRecord no inicializado — verifica permisos RECORD_AUDIO")
//                audioRecord = null
//                return
//            }
//
//            audioRecord?.startRecording()
//            isRunning.set(true)
//            Log.i(TAG, "✅ Motor de audio iniciado (buffer=${bufferSize}B, rate=${SAMPLE_RATE}Hz)")
//
//            startCaptureLoop()
//
//        } catch (e: SecurityException) {
//            Log.e(TAG, "❌ Sin permiso RECORD_AUDIO: ${e.message}")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error iniciando AudioRecord: ${e.message}", e)
//            cleanup()
//        }
//    }
//
//    /**
//     * El loop de captura corre indefinidamente en un coroutine de background.
//     * Lee frames del AudioRecord y los distribuye.
//     */
//    private fun startCaptureLoop() {
//        captureJob = engineScope.launch {
//            val frame = ShortArray(FRAME_LENGTH)
//
//            Log.d(TAG, "🔄 Loop de captura iniciado")
//
//            while (isRunning.get()) {
//                val read = audioRecord?.read(frame, 0, FRAME_LENGTH) ?: break
//
//                if (read <= 0) {
//                    // read == -1 o -2 indica error de AudioRecord
//                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
//                        read == AudioRecord.ERROR_BAD_VALUE) {
//                        Log.e(TAG, "❌ Error fatal leyendo audio: $read")
//                        break
//                    }
//                    // Otros valores negativos: espera y reintenta
//                    delay(10)
//                    continue
//                }
//
//                // 1. Calcula RMS para la animación del orbe
//                val rms = calculateRMS(frame, read)
//                onRmsChanged(rms)
//
//                // 2. Si estamos grabando para Whisper, acumula en el buffer circular
//                if (isRecording.get()) {
//                    bufferLock.withLock {
//                        val copyLen = minOf(read, MAX_RECORDING_SAMPLES - recordingWritePos)
//                        if (copyLen > 0) {
//                            System.arraycopy(frame, 0, recordingBuffer, recordingWritePos, copyLen)
//                            recordingWritePos += copyLen
//                            recordingSampleCount += copyLen
//                        }
//                        // Si llenamos el buffer, para la grabación automáticamente
//                        if (recordingWritePos >= MAX_RECORDING_SAMPLES) {
//                            Log.w(TAG, "⚠️ Buffer lleno (30s) — deteniendo grabación automáticamente")
//                            isRecording.set(false)
//                        }
//                    }
//                }
//
//                // 3. Notifica a los listeners externos (Porcupine, VAD, etc.)
//                onFrameAvailable(frame.copyOf(read))
//            }
//
//            Log.d(TAG, "⏹️ Loop de captura terminado")
//        }
//    }
//
//    // ─── Control de grabación para Whisper ──────────────────────────────────
//
//    /**
//     * Inicia la grabación de audio para transcripción con Whisper.
//     * El audio se acumula en el buffer hasta que llames stopRecording().
//     */
//    fun startRecording() {
//        bufferLock.withLock {
//            recordingWritePos = 0
//            recordingSampleCount = 0
//        }
//        isRecording.set(true)
//        Log.d(TAG, "⏺️ Grabación iniciada para Whisper")
//    }
//
//    /**
//     * Detiene la grabación y devuelve el audio acumulado.
//     *
//     * @return ShortArray con el audio grabado, o null si no hay nada
//     */
//    fun stopRecordingAndGetAudio(): ShortArray? {
//        isRecording.set(false)
//
//        return bufferLock.withLock {
//            val count = recordingSampleCount
//            if (count == 0) {
//                Log.w(TAG, "⚠️ Grabación vacía")
//                null
//            } else {
//                Log.d(TAG, "⏹️ Grabación detenida — ${count} samples (${count / SAMPLE_RATE}s)")
//                recordingBuffer.copyOf(count)
//            }
//        }
//    }
//
//    /**
//     * Descarta el audio grabado sin devolverlo.
//     * Útil cuando el usuario interrumpe y queremos empezar de nuevo.
//     */
//    fun discardRecording() {
//        isRecording.set(false)
//        bufferLock.withLock {
//            recordingWritePos = 0
//            recordingSampleCount = 0
//        }
//        Log.d(TAG, "🗑️ Grabación descartada")
//    }
//
//    fun isRecording(): Boolean = isRecording.get()
//
//    fun getSampleCount(): Int = bufferLock.withLock { recordingSampleCount }
//
//    // ─── Cálculo de RMS ─────────────────────────────────────────────────────
//
//    private fun calculateRMS(frame: ShortArray, size: Int): Float {
//        var sum = 0.0
//        for (i in 0 until size) {
//            val s = frame[i].toDouble()
//            sum += s * s
//        }
//        // Normalizado a 0.0–12.0 para el orbe
//        return (sqrt(sum / size).toFloat() / 400f).coerceIn(0f, 12f)
//    }
//
//    // ─── Lifecycle ──────────────────────────────────────────────────────────
//
//    fun stop() {
//        isRunning.set(false)
//        isRecording.set(false)
//        captureJob?.cancel()
//        captureJob = null
//        cleanup()
//        Log.i(TAG, "✅ Motor de audio detenido")
//    }
//
//    private fun cleanup() {
//        audioRecord?.let {
//            try {
//                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
//                it.release()
//            } catch (e: Exception) {
//                Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
//            }
//        }
//        audioRecord = null
//    }
//
//    fun isRunning(): Boolean = isRunning.get()
//}