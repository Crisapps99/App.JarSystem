package com.example.myapplication.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope

class HybridSpeechTranscriber(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HYBRID_SPEECH"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onSpeechStartedCallback: (() -> Unit)? = null
    private var onSpeechEndedCallback: (() -> Unit)? = null
    private var reinicioEnCurso = false
    private var sesionActiva = false
    private var isListening = false
    private var speechStartTimestamp = 0L
    private var language = "es"

    fun init(): Boolean {
        return try {
            mainHandler.post {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                Log.d(TAG, " Speech Recognizer inicializado en main thread")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error inicializando: ${e.message}")
            false
        }
    }

    fun iniciarSesionContinua(
        language: String = "es",
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSpeechStarted: () -> Unit = {},
        onSpeechEnded: () -> Unit = {}
    ) {
        this.language = language
        onResultCallback = onResult
        onErrorCallback = onError
        onSpeechStartedCallback = onSpeechStarted
        onSpeechEndedCallback = onSpeechEnded
        sesionActiva = true
        isListening = false

        Log.d(TAG, " Sesión continua iniciada - llamando startListening")
        mainHandler.post {
            escucharUnaVez()
        }
    }

    fun detenerSesion() {
        sesionActiva = false
        mainHandler.post {
            speechRecognizer?.cancel()
            isListening = false
            Log.d(TAG, "️ Sesión detenida")
        }
    }

    /**
     * Reinicia la escucha de forma explícita.
     * Esto debe llamarse desde JarvisVoiceController después del TTS.
     */
    fun reiniciarEscucha() {
        if (!sesionActiva) {
            Log.w(TAG, " Sesión no activa, no puedo reiniciar")
            return
        }

        Log.d(TAG, " Reiniciando escucha")

        //  CRÍTICO: Cancelar PRIMERO
        mainHandler.post {
            if (isListening) {
                Log.d(TAG, "   Cancelando SR activo...")
                speechRecognizer?.cancel()
                isListening = false
            }

            //  CRÍTICO: Esperar un poco ANTES de reiniciar
            mainHandler.postDelayed({
                Log.d(TAG, "   Iniciando nuevo escucharUnaVez()...")
                escucharUnaVez()
            }, 200L)  // ← ESPERA IMPORTANTE (200ms es suficiente)
        }
    }

    private fun escucharUnaVez() {
        if (!sesionActiva || speechRecognizer == null) {
            Log.w(TAG, " No puedo escuchar: sesionActiva=$sesionActiva, SR=${speechRecognizer != null}")
            return
        }

        if (isListening) {
            Log.d(TAG, " Ya escuchando, cancelo y reinicio")
            return
        }

        escucharUnaVezInterno()
    }

    private fun escucharUnaVezInterno() {
        if (!sesionActiva || speechRecognizer == null) {
            Log.w(TAG, " Sesión cancelada o SR null")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)

                Log.d(TAG, " SR listo para escuchar")
            }

            override fun onBeginningOfSpeech() {
                speechStartTimestamp = System.currentTimeMillis()
                Log.d(TAG, " Habla detectada")
                onSpeechStartedCallback?.invoke()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "✋ Fin de habla — procesando...")
                onSpeechEndedCallback?.invoke()
            }

            override fun onError(error: Int) {
                isListening = false
                val msg = errorMsg(error)
                Log.e(TAG, "❌ SR error: $msg (código: $error)")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (sesionActiva) {
                            Log.d(TAG, " Reiniciando escucha (error: $msg)...")
                            val duracion = System.currentTimeMillis() - speechStartTimestamp
                            val esRuido = duracion < 1500L
                            mainHandler.postDelayed({ escucharUnaVez() }, 300L)
                            Log.d(TAG, " Reiniciando escucha (error: $msg)...")
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        if (sesionActiva) {
                            Log.d(TAG, " SR ocupado, esperando...")
                            mainHandler.postDelayed({ escucharUnaVez() }, 800L)
                        }
                    }
                    else -> {
                        Log.e(TAG, "️ Error real: $msg")
                        onErrorCallback?.invoke(msg)
                        if (sesionActiva) {
                            mainHandler.postDelayed({ escucharUnaVez() }, 600L)
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    val texto = matches[0]
                    Log.d(TAG, " SR resultado: \"$texto\"")
                    onResultCallback?.invoke(texto)

                    // El reinicio es controlado por JarvisVoiceController.hablar()
                    // para evitar capturar el echo del TTS

                } else {
                    Log.w(TAG, "⚠ SR sin resultados — no reiniciamos aquí")
                    // No reiniciar aquí tampoco
                    // Espera a que JarvisVoiceController explícitamente reinicie
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    Log.d(TAG, " Parcial: \"${matches[0]}\"")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            Log.d(TAG, " Llamando startListening()...")
            // ── Silenciar el beep del sistema antes de iniciar SR ─────────
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)

            speechRecognizer?.startListening(intent)
            Log.d(TAG, " startListening() ejecutado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, " Error en startListening: ${e.message}", e)
            isListening = false
            if (sesionActiva) {
                mainHandler.postDelayed({ escucharUnaVez() }, 500L)
            }
        }
    }

    fun reanudarEscucha(language: String = "es") {
        Log.w(TAG, "️ reanudarEscucha() deprecated - usa reiniciarEscucha()")
    }

    fun transcribeFromAudio(
        audioData: ShortArray,
        language: String = "es",
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.w(TAG, " transcribeFromAudio() ignorado — usando SR continuo")
        onError("Usar iniciarSesionContinua() en su lugar")
    }

    fun stopListening() = detenerSesion()

    private fun errorMsg(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO                  -> "Error de audio"
        SpeechRecognizer.ERROR_CLIENT                 -> "Error del cliente"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permisos"
        SpeechRecognizer.ERROR_NETWORK                -> "Error de red"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "Timeout de red"
        SpeechRecognizer.ERROR_NO_MATCH               -> "Sin coincidencia"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "SR ocupado"
        SpeechRecognizer.ERROR_SERVER                 -> "Error del servidor"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "Sin habla detectada"
        else                                          -> "Error desconocido: $error"
    }

    fun destroy() {
        sesionActiva = false
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d(TAG, " HybridSpeechTranscriber destruido")
        }
    }
}