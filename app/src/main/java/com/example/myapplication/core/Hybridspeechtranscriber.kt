//package com.example.myapplication.core
//
//import android.content.Context
//import android.content.Intent
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.speech.RecognitionListener
//import android.speech.RecognizerIntent
//import android.speech.SpeechRecognizer
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//
//class HybridSpeechTranscriber(
//    private val context: Context,
//    private val scope: CoroutineScope
//) {
//    companion object {
//        private const val TAG = "HYBRID_SPEECH"
//
//        // Estados posibles del SR
//        private enum class SrState {
//            IDLE,           // Sin actividad
//            STARTING,       // Llamando a startListening()
//            LISTENING,      // Escuchando activamente
//            STOPPING,       // Cancelando/destruyendo
//            ERROR           // Estado de error, requiere reinicio completo
//        }
//    }
//
//    private var speechRecognizer: SpeechRecognizer? = null
//    private val mainHandler = Handler(Looper.getMainLooper())
//
//    // Callbacks externos
//    private var onResultCallback: ((String) -> Unit)? = null
//    private var onErrorCallback: ((String) -> Unit)? = null
//    private var onSpeechStartedCallback: (() -> Unit)? = null
//    private var onSpeechEndedCallback: (() -> Unit)? = null
//    private var onRmsCallback: ((Float) -> Unit)? = null
//
//    // Estado interno
//    @Volatile
//    private var currentState = SrState.IDLE
//    @Volatile
//    private var sesionActiva = false
//    private var language = "es"
//
//    // Para reintentos controlados
//    private var reintentosConsecutivos = 0
//    private val MAX_REINTENTOS = 3
//
//    init{
//        initRecognizer()
//    }
//
//    private fun initRecognizer() {
//        try {
//            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
//            Log.d(TAG, "✅ SpeechRecognizer creado")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error creando SR: ${e.message}")
//        }
//    }
//
//    fun init(): Boolean {
//        return speechRecognizer != null
//    }
//
//    fun iniciarSesionContinua(
//        language: String = "es",
//        onResult: (String) -> Unit,
//        onError: (String) -> Unit,
//        onSpeechStarted: () -> Unit = {},
//        onSpeechEnded: () -> Unit = {}
//    ) {
//        this.language = language
//        onResultCallback = onResult
//        onErrorCallback = onError
//        onSpeechStartedCallback = onSpeechStarted
//        onSpeechEndedCallback = onSpeechEnded
//        sesionActiva = true
//        reintentosConsecutivos = 0
//
//        Log.d(TAG, "🚀 Iniciando sesión continua")
//        mainHandler.post { iniciarEscucha() }
//    }
//
//    fun detenerSesion() {
//        Log.d(TAG, "🛑 Deteniendo sesión")
//        sesionActiva = false
//
//        mainHandler.post {
//            if (currentState != SrState.STOPPING && currentState != SrState.ERROR) {
//            when (currentState) {
//                SrState.LISTENING, SrState.STARTING -> {
//                    currentState = SrState.STOPPING
//                    try {
//                        speechRecognizer?.cancel()
//                        Log.d(TAG, "  Cancel llamado")
//                    } catch (e: Exception) {
//                        Log.e(TAG, " Error al cancelar: ${e.message}")
//                    }
//                }
//                else -> {
//                    Log.d(TAG, "  Estado $currentState, no necesita cancel")
//                }
//            }
//            } else {
//                Log.d(TAG, "  Ya en estado $currentState, omitiendo cancel")
//            }
//
//            // Forzar limpieza después de un delay
//            mainHandler.postDelayed({
//                if (currentState == SrState.STOPPING || currentState == SrState.ERROR) {
//                    currentState = SrState.IDLE
//                }
//                Log.d(TAG, "  Sesión detenida completamente")
//            }, 300)
//        }
//    }
//
//    fun reiniciarEscucha() {
//        if (!sesionActiva) {
//            Log.d(TAG, " reiniciarEscucha ignorado: sesión inactiva")
//            return
//        }
//
//        Log.d(TAG, "🔄 Reiniciando escucha (estado actual: $currentState)")
//
//        mainHandler.post {
//            when (currentState) {
//                SrState.LISTENING, SrState.STARTING -> {
//                    // Solo cancelamos si realmente está escuchando
//                    currentState = SrState.STOPPING
//                    try {
//                        speechRecognizer?.cancel()
//                        Log.d(TAG, "  Cancel enviado para reinicio")
//                    } catch (e: Exception) {
//                        Log.e(TAG, "  Error en cancel para reinicio: ${e.message}")
//                    }
//
//                    // Esperamos a que el cancel termine antes de reiniciar
//                    mainHandler.postDelayed({
//                        if (sesionActiva && currentState == SrState.STOPPING) {
//                            iniciarEscucha()
//                        }
//                    }, 400)
//                }
//                SrState.IDLE, SrState.ERROR -> {
//                    // Directo a iniciar
//                    iniciarEscucha()
//                }
//                SrState.STOPPING -> {
//                    // Ya está deteniéndose, programamos reinicio después
//                    Log.d(TAG, "  Ya deteniendo, programando reinicio...")
//                    mainHandler.postDelayed({
//                        if (sesionActiva) iniciarEscucha()
//                    }, 500)
//                }
//            }
//        }
//    }
//
//    private fun iniciarEscucha() {
//        if (!sesionActiva) {
//            Log.d(TAG, " iniciarEscucha ignorado: sesión inactiva")
//            currentState = SrState.IDLE
//            return
//        }
//
//        if (currentState != SrState.IDLE && currentState != SrState.STOPPING && currentState != SrState.ERROR) {
//            Log.w(TAG, " iniciarEscucha ignorado: estado actual $currentState")
//            return
//        }
//
//        if (speechRecognizer == null) {
//            Log.e(TAG, " SpeechRecognizer es null, reintentando crear...")
//            initRecognizer()
//            if (speechRecognizer == null) {
//                onErrorCallback?.invoke("Error interno de reconocimiento")
//                return
//            }
//        }
//
//        currentState = SrState.STARTING
//
//        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
//            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
//            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
//            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
//            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
//            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
//        }
//
//        speechRecognizer?.setRecognitionListener(createRecognitionListener())
//
//        try {
//            speechRecognizer?.startListening(intent)
//            Log.d(TAG, "🎤 startListening() ejecutado")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error en startListening: ${e.message}", e)
//            currentState = SrState.ERROR
//            reintentosConsecutivos++
//
//            if (reintentosConsecutivos <= MAX_REINTENTOS && sesionActiva) {
//                Log.d(TAG, " Reintentando en 1s (${reintentosConsecutivos}/$MAX_REINTENTOS)")
//                mainHandler.postDelayed({ iniciarEscucha() }, 1000)
//            } else {
//                onErrorCallback?.invoke("Error fatal: ${e.message}")
//            }
//        }
//    }
//
//    private fun createRecognitionListener() = object : RecognitionListener {
//        override fun onReadyForSpeech(params: Bundle?) {
//            if (currentState == SrState.STARTING || currentState == SrState.STOPPING) {
//                currentState = SrState.LISTENING
//            }
//            reintentosConsecutivos = 0  // Resetear reintentos en éxito
//            Log.d(TAG, "✅ SR listo para escuchar")
//        }
//
//        override fun onBeginningOfSpeech() {
//            Log.d(TAG, "🎙️ Habla detectada")
//            onSpeechStartedCallback?.invoke()
//        }
//
//        override fun onRmsChanged(rmsdB: Float) {
//            val normalized = (rmsdB / 100f).coerceIn(0f, 1f) * 12f
//            onRmsCallback?.invoke(normalized)
//        }
//
//        override fun onBufferReceived(buffer: ByteArray?) {}
//
//        override fun onEndOfSpeech() {
//            Log.d(TAG, "✋ Fin de habla")
//            onSpeechEndedCallback?.invoke()
//        }
//
//        override fun onError(error: Int) {
//            val msg = errorMsg(error)
//            Log.e(TAG, "❌ SR error: $msg (código: $error), estado=$currentState")
//
//            when (error) {
//                SpeechRecognizer.ERROR_NO_MATCH,
//                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
//                    if (sesionActiva && currentState != SrState.STOPPING) {
//                        currentState = SrState.IDLE
//                        Log.d(TAG, "  Reiniciando escucha silenciosamente...")
//                        mainHandler.postDelayed({ iniciarEscucha() }, 300)
//                    }
//                }
//
//                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
//                    if (sesionActiva) {
//                        currentState = SrState.IDLE
//                        Log.d(TAG, "  SR ocupado, esperando 800ms...")
//                        mainHandler.postDelayed({ iniciarEscucha() }, 800)
//                    }
//                }
//
//                SpeechRecognizer.ERROR_CLIENT -> {
//                    // Error grave - necesitamos recrear el SR
//                    Log.e(TAG, "  ERROR_CLIENT - Recreando SpeechRecognizer...")
//                    currentState = SrState.ERROR
//
//                    try {
//                        speechRecognizer?.destroy()
//                    } catch (e: Exception) {
//                        Log.e(TAG, "  Error en destroy: ${e.message}")
//                    }
//
//                    speechRecognizer = null
//
//                    mainHandler.postDelayed({
//                        if (sesionActiva) {
//                            initRecognizer()
//                            iniciarEscucha()
//                        }
//                    }, 500)
//                }
//
//                else -> {
//                    onErrorCallback?.invoke(msg)
//                    if (sesionActiva && currentState != SrState.STOPPING) {
//                        currentState = SrState.IDLE
//                        mainHandler.postDelayed({ iniciarEscucha() }, 600)
//                    }
//                }
//            }
//        }
//
//        override fun onResults(results: Bundle?) {
//            if (currentState == SrState.STOPPING) {
//                Log.d(TAG, " onResults ignorado porque estamos deteniendo")
//                return
//            }
//
//            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//
//            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
//                val texto = matches[0]
//                Log.d(TAG, "📝 Resultado: \"$texto\"")
//
//                // Pausar temporalmente para evitar eco
//                currentState = SrState.IDLE
//                onResultCallback?.invoke(texto)
//                // NOTA: NO reiniciamos aquí - JarvisVoiceController lo hará después del TTS
//            } else {
//                Log.w(TAG, "⚠️ Sin resultados")
//                if (sesionActiva && currentState != SrState.STOPPING) {
//                    currentState = SrState.IDLE
//                    mainHandler.postDelayed({ iniciarEscucha() }, 300)
//                }
//            }
//        }
//
//        override fun onPartialResults(partialResults: Bundle?) {
//            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
//                Log.d(TAG, "📝 Parcial: \"${matches[0]}\"")
//            }
//        }
//
//        override fun onEvent(eventType: Int, params: Bundle?) {}
//    }
//
//    fun setOnRmsCallback(callback: (Float) -> Unit) {
//        onRmsCallback = callback
//    }
//
//    private fun errorMsg(error: Int) = when (error) {
//        SpeechRecognizer.ERROR_AUDIO                  -> "Error de audio"
//        SpeechRecognizer.ERROR_CLIENT                 -> "Error del cliente"
//        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permisos"
//        SpeechRecognizer.ERROR_NETWORK                -> "Error de red"
//        SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "Timeout de red"
//        SpeechRecognizer.ERROR_NO_MATCH               -> "Sin coincidencia"
//        SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "SR ocupado"
//        SpeechRecognizer.ERROR_SERVER                 -> "Error del servidor"
//        SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "Sin habla detectada"
//        else                                          -> "Error desconocido: $error"
//    }
//
//    fun destroy() {
//        Log.d(TAG, "💀 Destruyendo HybridSpeechTranscriber")
//        sesionActiva = false
//        currentState = SrState.STOPPING
//
//        mainHandler.removeCallbacksAndMessages(null)
//
//        try {
//            speechRecognizer?.cancel()
//            speechRecognizer?.destroy()
//        } catch (e: Exception) {
//            Log.e(TAG, " Error en destroy: ${e.message}")
//        }
//
//        speechRecognizer = null
//        onResultCallback = null
//        onErrorCallback = null
//        onSpeechStartedCallback = null
//        onSpeechEndedCallback = null
//        onRmsCallback = null
//    }
//}