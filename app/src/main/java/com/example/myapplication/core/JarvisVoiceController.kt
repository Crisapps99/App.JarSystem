package com.example.myapplication.core

import android.Manifest
import android.R.attr.port
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.api.*
import com.example.myapplication.service.MyAccessibilityService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import com.example.myapplication.model.ScreenElement
import com.example.myapplication.service.JarvisNotificationListener
import com.example.myapplication.ui.JarvisOverlayUiState
import com.example.myapplication.ui.StepStatus
import com.example.myapplication.ui.ProcessingStep
import kotlinx.coroutines.withTimeoutOrNull
import com.example.myapplication.grpc.NexusWebSocketClient
import com.example.myapplication.ui.BarColorMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive


enum class JarvisState { IDLE, LISTENING, THINKING, SPEAKING }

interface JarvisUi {
    fun renderState(state: JarvisState)
    fun showText(text: String)
    fun showToast(text: String)
    fun getCurrentScreenText(): List<String>
    fun onRecognizerReady()
    fun updateORB(rms: Float)
    fun setOrbVisibility(visible: Boolean)
    fun getDisplayedText(): String
    fun showImages(urls: List<String>)
    fun hideOverlayFromTimeout()
    fun showSearchResult(textoCompleto: String, fuentes: List<String>, imagenes: List<String>, preguntas: List<String>)
    fun updateProcessingSteps(steps: List<ProcessingStep>)
    fun updateUserTranscription(text: String)
}
interface PorcupineController {
    fun pausarPorcupine()
    fun reanudarPorcupine()
    fun esPorcupinePausado(): Boolean
}
object ElevenLabsConfig {
    const val API_KEY          = "9dcae6842f3e53c4f885e4dcf30bf5635e8284c41df98d93f8b432b5f4383e90"
    const val VOICE_ID         = "eqx5NtkvZtmylCCnpta7"
    const val MODEL_ID         = "eleven_multilingual_v2"
    const val STABILITY        = 0.5f
    const val SIMILARITY_BOOST = 0.75f
    const val STYLE            = 0.0f
    const val SPEAKER_BOOST    = true
}

enum class TtsMode { ANDROID, ELEVEN_LABS }
private val TTS_MODE = TtsMode.ELEVEN_LABS

class JarvisVoiceController(
    private val context: Context,
    private val ui: JarvisUi,
    private val scope: CoroutineScope,
    private val porcupineController: PorcupineController? = null,
    private val uiState: JarvisOverlayUiState? = null
) {
    companion object {
        private const val TAG = "JARVIS_CTRL"
        private const val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
        private const val ACTION_CANCEL = "JARVIS.CANCEL_ACTION"
    }

    // ── Componentes del sistema ──────────────────────────────────────────────
    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private var ttsListo = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var elMediaPlayer: MediaPlayer? = null
    // ── NUEVO: Motor de audio unificado ─────────────────────────────────────
//    private lateinit var audioEngine: ContinuousVoiceEngine
//
////    // ── NUEVO: Transcriptor Whisper local ───────────────────────────────────
////    private lateinit var whisperTranscriber: WhisperTranscriber
////    private var whisperListo = false
//
//    // ── NUEVO: Gestor de sesión y VAD ───────────────────────────────────────
//    private lateinit var sessionManager: VoiceSessionManager

    // ── Estado de la sesión ─────────────────────────────────────────────────
    private var sesionActiva = false
    private var isProcessing = false         // true mientras Whisper transcribe o la API responde
    private var esperandoConfirmacion = false
    private var wakeWordCooldown = false
    private val TIMEOUT_ESCUCHA_MS = 5000L  // 5 segundos
    private var esperandoRespuestaServidor = false
    // ── Modo visual (sin cambios) ────────────────────────────────────────────
    private var modoVisualActivo = false
    private var numberedOverlay: NumberedElementsOverlay? = null
    private var modoConversacionActivo = false
    private var esperandoPostTTS = false

    // ── Orbe y mensajería (sin cambios) ─────────────────────────────────────
    private var orbOcultoPorMensaje = false
    private var pendingMessagePackage = ""

    // ── Receivers (sin cambios en lógica) ───────────────────────────────────
    private var confirmacionReceiver: BroadcastReceiver? = null
    private var orbHideReceiver: BroadcastReceiver? = null
    private var wakeWordReceiver: BroadcastReceiver? = null
    var ultimoResultadoBusqueda: SearchResult? = null
        private set

    // ── Ventana anti-eco para confirmaciones ────────────────────────────────
    private var ttsTerminoTimestamp = 0L
    private val ECO_WINDOW_MS = 500L
    private var timestampUltimoTTS = 0L
    //    private lateinit var hybridTranscriber: HybridSpeechTranscriber
//    private var hybridListo = false
    private var confirmationDoneReceiver: BroadcastReceiver? = null
    private var esperandoConfirmacionGoogle = false
    private var busquedaPendienteGoogle = ""
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val audioManagerSystem by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    // ── Flag para evitar escucharse a sí mismo ──────────────────────────────
    private var estaHablando = false
    private val TIEMPO_BLOQUEO_POST_TTS = 1500L  // 1.5 segundos después de TTS
    private lateinit var voiceEngine: ContinuousVoiceEngine
    private var wakeWordDetected = false

    private var wsClient: NexusWebSocketClient? = null
    private var ultimoComandoEnviado = ""
    private var ultimoTimestampComando = 0L
    private var estaProcesandoComando = false
    private var estaEnviandoAlServidor = false
    // ── Timeout para respuestas del servidor ──────────────────────────────────
    private var timeoutServidor: Runnable? = null
    private val TIMEOUT_SERVIDOR_MS = 15000L  // 15 segundos máximo

    private var spotifyUpdateJob: Job? = null
    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    fun init() {
        registrarReceptorSpotify()
        audioManager = AudioManager(context)
        configurarTts()
        inicializarWebSocket()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Sin permiso de micrófono, no se puede iniciar voiceEngine")
            ui.showToast("Permiso de micrófono necesario")
            return
        }


        voiceEngine = ContinuousVoiceEngine(
            context = context,
            onWakeWordDetected = {
                Log.d(TAG, "Wakeword detectado por Vosk")
                mainHandler.post {
                    esperandoRespuestaServidor = false
                    estaEnviandoAlServidor = false
                    estaProcesandoComando = false
                    timeoutServidor?.let { mainHandler.removeCallbacks(it) }
                    ui.setOrbVisibility(true)
                    ui.showText("🎤 Escuchando...")
                    // Activar sesión de escucha con Google Cloud STT
                    sesionActiva = true
                    isProcessing = false
                    setState(JarvisState.LISTENING)
                }
            },
            onFinalResult = { texto ->
                Log.d(TAG, "resultado final:  $texto")
                if (esperandoRespuestaServidor) {
                    Log.d(TAG, "Ignorando voz mientras se espera respuesta del servidor")
                    return@ContinuousVoiceEngine
                }
                ui.updateUserTranscription(texto)
                if (estaHablando) {
                    Log.d(TAG, "Ignorando resultado porque el asistente está hablando")
                    return@ContinuousVoiceEngine
                }
                val tiempoDesdeTTS = System.currentTimeMillis() - timestampUltimoTTS
                if (tiempoDesdeTTS < TIEMPO_BLOQUEO_POST_TTS + 500) {
                    Log.d(TAG, " Ignorando resultado (${tiempoDesdeTTS}ms desde último TTS)")
                    return@ContinuousVoiceEngine
                }
                if (sesionActiva && !isProcessing) {
                    isProcessing = true
                    setState(JarvisState.THINKING)
                    hacerVibrar(100)
                    procesarTexto(texto)
                }
            },
            onPartialResult = { parcial ->
                Log.v(TAG, " Parcial'$parcial'")
                ui.updateUserTranscription(parcial)
                mainHandler.removeCallbacks(timeoutRunnable)
            },

            onRmsChanged = { rms ->
                mainHandler.post { ui.updateORB(rms) }
            },
            onSpeechStarted = {
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, 10_000L)
                Log.d(TAG, "Timeout reseteado por inicio de habla")
            },
            onSpeechEnded = {
                Log.d(TAG, "Usuario dejó de hablar")
                // Iniciar timeout después de que termine de hablar
                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
            },
//            onSrCycle = {
//                Log.d(TAG, "Timeout reseteado por ciclo SR")
//            }
        )
        registrarReceivers()
        setState(JarvisState.IDLE)
        Log.i(TAG, " Controlador inicializado")
    }

    private fun startSpotifyPlayerUpdates() {
        stopSpotifyPlayerUpdates() // evitar duplicados
        spotifyUpdateJob = scope.launch {
            while (uiState?.showSpotifyPlayer == true && coroutineContext.isActive) {
                val title = SpotifyController.getTituloActual(context) ?: ""
                val artist = SpotifyController.getArtistaActual(context) ?: ""
                val isPlaying = SpotifyController.estaReproduciendo(context)
                withContext(Dispatchers.Main) {
                    uiState?.spotifyTrackTitle = title
                    uiState?.spotifyTrackArtist = artist
                    uiState?.spotifyIsPlaying = isPlaying
                    // Si la canción ha terminado y no hay título, podríamos ocultar el reproductor
                    if (title.isBlank() && artist.isBlank() && !isPlaying) {
                        // no ocultamos automáticamente, el usuario puede cerrarlo
                    }
                }
                delay(2000) // actualiza cada 2 segundos
            }
        }
    }

    private fun stopSpotifyPlayerUpdates() {
        spotifyUpdateJob?.cancel()
        spotifyUpdateJob = null
    }

    private fun ocultarReproductor() {
        uiState?.showSpotifyPlayer = false
        stopSpotifyPlayerUpdates()
        // También resetear los textos
        uiState?.spotifyTrackTitle = ""
        uiState?.spotifyTrackArtist = ""
        uiState?.spotifyIsPlaying = false
    }
    private fun solicitarAudioFocusSR() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                //  MAY_DUCK: el video baja volumen pero NO se pausa
                // Si usas GAIN, el video se pausa completamente
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "🔊 AudioFocus cambió: $focusChange")
                }
                .build()

            val result = audioManagerSystem.requestAudioFocus(audioFocusRequest!!)
            Log.d(
                TAG,
                "🎙️ AudioFocus SR: ${if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "CONCEDIDO" else "DENEGADO"}"
            )
        }
    }

    private fun liberarAudioFocusSR() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManagerSystem.abandonAudioFocusRequest(it)
                audioFocusRequest = null
                Log.d(TAG, "🔊 AudioFocus SR liberado")
            }
        }
    }
//    private fun inicializarHybridTranscriber() {
//        hybridTranscriber = HybridSpeechTranscriber(context, scope)
//        hybridListo = try {
//            hybridTranscriber.init()
//        } catch (e: Exception) {
//            Log.e(TAG, " HybridTranscriber falló: ${e.message}")
//            false
//        }
//        Log.i(TAG, if (hybridListo) " SR inicializado" else " SR no disponible")
//    }


    /**
     * Inicializa el motor de audio.
     * onFrameAvailable se llama con cada frame de 512 samples (~32ms).
     */
//    private fun inicializarAudioEngine() {
//        audioEngine = ContinuousVoiceEngine(
//            onFrameAvailable = { frame ->
//            },
//            onRmsChanged = { rms ->
//                mainHandler.post { ui.updateORB(rms) }
//            }
//        )
//        Log.i(TAG, " Motor de audio iniciado")
//    }

    /**
     * Inicializa el gestor de sesión con los callbacks de VAD.
     */
//    private fun inicializarSessionManager() {
//        sessionManager = VoiceSessionManager(
//            scope = scope,
//
//            onSpeechStarted = {
//                // Ya no usamos AudioEngine para grabar — SR maneja el micrófono
//                // Dejamos vacío o solo log
//                Log.d(TAG, " [VAD] Inicio de habla (informativo)")
//            },
//
//            onSpeechEnded = {
//                // Ya no hay audioData — SR entrega texto directamente
//                Log.d(TAG, " [VAD] Fin de habla (informativo)")
//            },
//
//            onInterruption = {
//                Log.d(TAG, " Interrupción del usuario")
//                detenerTTS()
//            },
//
//            onSessionTimeout = {
//                Log.d(TAG, " Timeout de sesión")
//                stopListeningCompletamente()
//                ui.showToast("Escucha detenida")
//            }
//        )
//    }

    // ────────────────────────────────────────────────────────────────────────
    // FLUJO PRINCIPAL: TRANSCRIPCIÓN Y PROCESAMIENTO
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Transcribe el audio con Whisper y luego procesa el texto resultante.
     * Se ejecuta en IO para no bloquear el hilo principal.
     */
    private fun procesarTexto(texto: String) {
        val ahora = System.currentTimeMillis()
        val textoLimpio = texto.trim()

        if (estaHablando) {
            Log.d(TAG, " Ignorando comando porque el asistente está hablando: '$texto'")
            detenerTTS()
            estaHablando = false
        }
        val tiempoDesdeTTS = ahora - ttsTerminoTimestamp
        if (tiempoDesdeTTS < 2000L) {
            // Si el texto es muy corto o parece eco, ignorar
            if (textoLimpio.length < 15) {
                Log.d(TAG, "⏭️ Posible eco post-TTS ignorado: '$texto'")
                return
            }
        }
        if (textoLimpio == ultimoComandoEnviado &&
            (ahora - ultimoTimestampComando) < 3000L) {
            Log.d(TAG, " Comando duplicado ignorado: '$texto'")
            return
        }
        if (esperandoConfirmacion) {
            Log.d(TAG, " Procesando respuesta de confirmación: '$texto'")
            procesarRespuestaConfirmacion(texto)
            return
        }
        if (estaEnviandoAlServidor) {
            Log.d(TAG, " Limpiando envío colgado antes de procesar nuevo texto")
            cancelarEnvioActual()
            // Pequeña pausa para que se limpie todo
            Thread.sleep(100)
        }
// Interceptor local: PANTALLA DIVIDIDA / MULTIVENTANA
        val textoLower = texto.lowercase().trim()
        if (textoLower.contains("en la misma pantalla") ||
            textoLower.contains("pantalla dividida") ||
            textoLower.contains("multiventana") ||
            textoLower.contains("doble pantalla")) {

            val apps = extraerAppsParaSplitScreen(texto) // definido abajo
            if (apps.isNotEmpty()) {
                ActionExecutor.openAppsInSplitScreen(context, apps)
                hablar("Abriendo las aplicaciones en pantalla dividida.") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            } else {
                hablar("No entendí qué aplicaciones quieres abrir.") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            }
            return
        }
        when {
            // Adelantar (ej: "adelanta 30 segundos")
            textoLimpio.contains("adelanta") || textoLimpio.contains("avanza") -> {
                val segundos = extraerSegundos(textoLimpio) ?: 10 // 10s por defecto
                ActionExecutor.controlYoutube(context, "adelantar", segundos)
                hablar("Adelantando $segundos segundos")
            }

            // Retroceder (ej: "retrocede 10 segundos")
            textoLimpio.contains("retrocede") || textoLimpio.contains("atrasa") -> {
                val segundos = extraerSegundos(textoLimpio) ?: 10
                ActionExecutor.controlYoutube(context, "retroceder", segundos)
                hablar("Retrocediendo $segundos segundos")
            }

            // Pantalla completa
            textoLimpio.contains("pantalla completa") -> {
                if (textoLimpio.contains("salir") || textoLimpio.contains("quitar")) {
                    ActionExecutor.controlYoutube(context, "salir_pantalla_completa")
                    hablar("Saliendo de pantalla completa")
                } else {
                    ActionExecutor.controlYoutube(context, "pantalla_completa")
                    hablar("Poniendo pantalla completa")
                }
            }
            // Pantalla dividida / multiventana


        }
        if (modoConversacionActivo || esperandoPostTTS) {
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
            Log.d(TAG, "Comando recibido en modo conversación, timeout reiniciado")
        }
        if (textoLimpio.contains("busca imágenes de") || textoLimpio.contains("muéstrame imágenes de") ||
            textoLimpio.contains("buscar imágenes de") || textoLimpio.contains("busca fotos de")
        ) {

            val busqueda = textoLimpio
                .replace("busca imágenes de", "")
                .replace("muéstrame imágenes de", "")
                .replace("buscar imágenes de", "")
                .replace("busca fotos de", "")
                .replace("fotos de", "")
                .replace("imágenes de", "").trim()

            if (busqueda.isNotBlank()) {
                ejecutarBusquedaImagenes(busqueda)
            } else {
                hablar("¿Imágenes de qué te gustaría buscar?") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            }
            return
        }
        // INTERCEPTOR DE COMANDO: COPIAR TEXTO
        if (textoLimpio.contains("copiar texto") || textoLimpio.contains("copia el texto") || textoLimpio.contains(
                "copia texto"
            )
        ) {
            val contenidoACopiar = ultimoResultadoBusqueda?.content ?: ui.getDisplayedText()
            if (contenidoACopiar.isNotBlank()) {
                ejecutarCopiarTextoLocal(contenidoACopiar)
            } else {
                hablar("No hay texto en pantalla para copiar.") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            }
            return
        }
        // INTERCEPTOR DE COMANDO: VER MÁS FUENTES / LINKS
        if (textoLimpio.contains("ver más") || textoLimpio.contains("ver mas") || textoLimpio.contains(
                "abrir enlaces"
            )
        ) {
            val urls = ultimoResultadoBusqueda?.urls ?: emptyList()
            if (urls.isNotEmpty()) {
                ejecutarAbrirNavegadorLocal(urls.first())
            } else {
                hablar("No encontré enlaces disponibles de la última búsqueda.") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            }
            return
        }
        if (texto.lowercase().contains("youtube") &&
            (texto.lowercase().contains("pon") || texto.lowercase().contains("reproduce"))
        ) {
            Log.d(TAG, "🎬 Comando de YouTube detectado: '$texto'")
            ejecutarMusica(texto)
            return
        }
        // Detectar comandos para cerrar búsqueda
        if (texto.lowercase().contains("cerrar búsqueda") ||
            texto.lowercase().contains("ocultar resultados") ||
            texto.lowercase().contains("volver atrás")
        ) {

            hablar("Resultados ocultados") {
                isProcessing = false
                if (sesionActiva) iniciarSRContinuo()
            }
            return
        }
        // Interceptor control YouTube via MediaSession
        val controlMedia = when {
            textoLimpio.contains("pausa") || textoLimpio.contains("para la música") || textoLimpio.contains(
                "para el video"
            ) -> "pausa"

            textoLimpio.contains("continúa") || textoLimpio.contains("continua") || textoLimpio.contains(
                "reanuda"
            ) || textoLimpio.contains("sigue") -> "play"

            textoLimpio.contains("siguiente") || textoLimpio.contains("próxima") -> "siguiente"
            textoLimpio.contains("qué suena") || textoLimpio.contains("que suena") || textoLimpio.contains(
                "qué canción"
            ) -> "titulo"

            else -> null
        }

        if (controlMedia != null) {
            val conSpotify = SpotifyController.estaReproduciendo(context)
            val conYoutube = YoutubeController.estaReproduciendo(context)

            val exito = when (controlMedia) {
                "pausa" -> {
                    if (conSpotify) SpotifyController.pausar(context)
                    else if (conYoutube) YoutubeController.pausar(context)
                    else false
                }

                "play" -> {
                    if (conSpotify) SpotifyController.reproducir(context)
                    else if (conYoutube) YoutubeController.reproducir(context)
                    else false
                }

                "siguiente" -> {
                    if (conSpotify) SpotifyController.siguienteCancion(context)
                    else if (conYoutube) YoutubeController.siguienteCancion(context)
                    else false
                }

                "anterior" -> {
                    if (conSpotify) SpotifyController.cancionAnterior(context)
                    else false
                }

                "titulo" -> {
                    val titulo = SpotifyController.getTituloActual(context)
                        ?: YoutubeController.getTituloActual(context)
                    val artista = SpotifyController.getArtistaActual(context)
                    val respuesta = when {
                        titulo != null && artista != null -> "Está sonando $titulo de $artista"
                        titulo != null -> "Está sonando $titulo"
                        else -> "No hay nada reproduciéndose"
                    }
                    hablar(respuesta) {
                        isProcessing = false
                        if (sesionActiva) iniciarSRContinuo()
                    }
                    return
                }

                else -> false
            }
        }
        if (esComandoCancelacion(texto)) {
            Log.d(TAG, "🛑 Comando de cancelación detectado: '$texto'")
            cancelarAccionActual()
            return
        }
        val intencion = CommandAnalyzer.clasificar(texto)

        Log.d(TAG, "Intención detectada: $intencion")

        when (intencion) {
            CommandAnalyzer.Intent.CALL_CONTACT -> {
                val contacto = CommandAnalyzer.detectarParametro(texto, intencion)
                hablar("Llamando a $contacto") {
                    ActionExecutor.callContact(context, contacto)
                    isProcessing = false
                }
            }

            CommandAnalyzer.Intent.SEND_MESSAGE -> {
                val contacto = CommandAnalyzer.detectarParametro(texto, intencion)
                hablar("Enviando mensaje a $contacto") {
                    ActionExecutor.sendWhatsAppMessage(context, contacto, "")
                    isProcessing = false
                }
            }

            CommandAnalyzer.Intent.SEND_WHATSAPP -> {
                // Extraer contacto y mensaje del comando
                val contacto = CommandAnalyzer.detectarParametro(texto, intencion)
                val mensaje = extraerMensajeDelComando(texto)

                if (contacto.isNotBlank() && mensaje.isNotBlank()) {
                    Log.d(TAG, "📱 [WHATSAPP] Mostrando preview para $contacto")
                    ActionExecutor.sendWhatsAppMessage(context, contacto, mensaje)
                    hablar("Enviando mensaje a $contacto") {
                        isProcessing = false
                        if (sesionActiva) iniciarSRContinuo()
                    }
                } else {
                    hablar("No entendí bien el mensaje. Repite por favor.") {
                        isProcessing = false
                        if (sesionActiva) iniciarSRContinuo()
                    }
                }
            }

            CommandAnalyzer.Intent.SEND_TELEGRAM -> {
                val contacto = CommandAnalyzer.detectarParametro(texto, intencion)
                hablar("Enviando por Telegram a $contacto") {
                    ActionExecutor.sendTelegramMessage(context, contacto, "")
                    isProcessing = false
                }
            }

            CommandAnalyzer.Intent.PLAY_MUSIC -> {
                val busqueda = CommandAnalyzer.detectarParametro(texto, intencion)
                ejecutarMusica(busqueda)
            }

            CommandAnalyzer.Intent.OPEN_YOUTUBE -> {
                hablar("Abriendo YouTube") {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com")
                    )
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    isProcessing = false
                }
            }
//
//            CommandAnalyzer.Intent.GET_DIRECTIONS -> {
//                val destino = CommandAnalyzer.detectarParametro(texto, intencion)
//                ejecutarNavegacion(destino)
//            }

            CommandAnalyzer.Intent.OPEN_MAPS -> {
                hablar("Abriendo Google Maps") {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://maps.google.com")
                    )
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    isProcessing = false
                }
            }

            CommandAnalyzer.Intent.WEATHER -> {
                ejecutarClima()
            }

            CommandAnalyzer.Intent.TIME -> {
                ejecutarComandoHora()
            }

//            CommandAnalyzer.Intent.SEARCH_WEB -> {
//                val busqueda = CommandAnalyzer.detectarParametro(texto, intencion)
//                ejecutarBusquedaWeb(busqueda)
//            }

            CommandAnalyzer.Intent.UNKNOWN -> {
                // Intentar con modo visual o servidor
                if (interceptarComandoVisual(texto)) {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                    return
                }

                if (esFraseDeSalida(texto)) {
                    terminarSesion()
                    return
                }

                // Enviar al servidor
                enviarComandoAlServidor(texto)
            }

            else -> {
                // Fallback: enviar al servidor
                enviarComandoAlServidor(texto)
            }
        }
    }
    private fun extraerAppsParaSplitScreen(texto: String): List<String> {
        // Quitar la frase fija
        val limpio = texto.lowercase()
            .replace(Regex("(en la misma pantalla|pantalla dividida|multiventana|doble pantalla)"), "")
            .replace("abre", "").replace("abrir", "").trim()
        // Separar por "y", "e", o coma
        val partes = limpio.split(Regex(",\\s*| y | e "))
        val paquetes = partes.mapNotNull { parte ->
            ActionExecutor.getPackageNameFromAppName(parte.trim(), context)
        }.distinct()
        return paquetes.take(2) // máximo dos apps para pantalla dividida real
    }
    private fun extraerMensajeDelComando(texto: String): String {
        val patrones = listOf(
            "dile que (.+)$",
            "di que (.+)$",
            "dile (.+)$",
            "envia (.+)$",
            "enviale (.+)$"
        )

        for (patron in patrones) {
            val regex = Regex(patron, RegexOption.IGNORE_CASE)
            val resultado = regex.find(texto)
            if (resultado != null) {
                return resultado.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun mostrarPreviewWhatsApp(contactName: String, message: String) {
        Log.d(TAG, "📱 Preview: $contactName - $message")
        esperandoConfirmacion = false
        // Actualizar UI para mostrar preview
        uiState?.apply {
            pendingWhatsappContact = contactName
            pendingWhatsappMessage = message
            showWhatsappPreview = true
            showPanel = true
        }

        hablar("¿Enviar: $message a $contactName?", alTerminar = {
            isProcessing = false
            esperandoConfirmacion = true
            if (sesionActiva) {
                mainHandler.postDelayed({ iniciarSRContinuo() }, 600L)
            }
        })
    }

    // Función auxiliar para extraer números del texto
    private fun extraerSegundos(texto: String): Int? {
        return Regex("\\d+").find(texto)?.value?.toInt()
    }

    private fun ejecutarBusquedaImagenes(query: String) {
        ui.showText("🔍 Buscando imágenes de $query...")
        setState(JarvisState.THINKING)

        scope.launch {
            try {
                // 🌐 Hacemos la petición nativa a Tavily usando tus imports de OkHttp
                val urlsImagenes = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    val mediaType = "application/json; charset=utf-8".toMediaType()

                    // Construimos el JSON requiriendo explícitamente "include_images"
                    val jsonBody = JSONObject().apply {
                        put(
                            "api_key",
                            "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"
                        ) // 🔑 REEMPLAZA CON TU API KEY REAL DE TAVILY
                        put("query", query)
                        put("include_images", true)
                        put("max_results", 6) // El carrusel aguanta bien hasta 6 imágenes
                    }

                    val request = Request.Builder()
                        .url("https://api.tavily.com/search")
                        .post(jsonBody.toString().toRequestBody(mediaType))
                        .build()

                    val response = client.newCall(request).execute()
                    val listaUrls = mutableListOf<String>()

                    if (response.isSuccessful) {
                        val jsonResponse = JSONObject(response.body?.string() ?: "")
                        // Tavily devuelve los links directos en un array JSON llamado "images"
                        val imagesArray = jsonResponse.optJSONArray("images")
                        if (imagesArray != null) {
                            for (i in 0 until imagesArray.length()) {
                                listaUrls.add(imagesArray.getString(i))
                            }
                        }
                    }
                    listaUrls
                }

                // 🎨 Volvemos al hilo principal para renderizar los resultados en el Orbe
                withContext(Dispatchers.Main) {
                    if (urlsImagenes.isNotEmpty()) {
                        ultimoResultadoBusqueda = SearchResult(
                            content = "Imágenes encontradas sobre $query",
                            urls = urlsImagenes
                        )
                        ui.showImages(urlsImagenes)
                        hablar("Aquí tienes las imágenes que encontré sobre $query.") {
                            isProcessing = false
                            if (sesionActiva) iniciarSRContinuo()
                        }
                    } else {
                        val fallbackMsg = "No encontré imágenes multimedia sobre $query."
                        ui.showText(fallbackMsg)
                        hablar(fallbackMsg) {
                            isProcessing = false
                            if (sesionActiva) iniciarSRContinuo()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en búsqueda visual: ${e.message}")
                isProcessing = false
                withContext(Dispatchers.Main) {
                    ui.showText("Hubo un error de conexión al buscar imágenes.")
                }
            }
        }
    }

    private fun ejecutarCopiarTextoLocal(texto: String) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Nexus", texto)
        clipboard.setPrimaryClip(clip)
        hablar("Texto copiado al portapapeles") {
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }

    fun detenerSesionCompleta() {
        stopListeningCompletamente()
    }

    private fun ejecutarAbrirNavegadorLocal(url: String) {
        try {
            val query = ultimoResultadoBusqueda?.query ?: ""
            val destino = if (query.isNotBlank()) {
                "https://www.google.com/search?q=${Uri.encode(query)}"
            } else {
                url
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(destino)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            hablar("Abriendo Google") {
                isProcessing = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al abrir navegador por voz: ${e.message}")
        }
    }

    private fun ejecutarComandoHora() {
        val cal = java.util.Calendar.getInstance()
        val hora = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minuto = String.format("%02d", cal.get(java.util.Calendar.MINUTE))

        val periodo =
            if (hora < 12) "de la mañana" else if (hora < 18) "de la tarde" else "de la noche"
        val hora12 = if (hora == 0) 12 else if (hora > 12) hora - 12 else hora

        val textoHora = "Son las $hora12 y $minuto $periodo"

        hablar(textoHora) {
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }

    private fun ejecutarMusica(texto: String) {
        val esYoutube = texto.contains("youtube", ignoreCase = true)

        var busqueda = texto.lowercase()
            .replace("reproduce", "").replace("pon la canción", "")
            .replace("pon música de", "").replace("pon a", "")
            .replace("en spotify", "").replace("en youtube", "")
            .replace("por youtube", "").replace("pon", "").trim()

        if (busqueda.isEmpty()) {
            busqueda = texto
        }

        val mensaje = if (esYoutube) "Buscando $busqueda en YouTube" else "Reproduciendo $busqueda"

        hablar(mensaje) {
            if (esYoutube) {
                ActionExecutor.playVideo(context, busqueda)
            } else {
                // Spotify
                ActionExecutor.playMusic(context, busqueda, "com.spotify.music")
                // Mostrar reproductor en el panel
                uiState?.showSpotifyPlayer = true
                startSpotifyPlayerUpdates()
            }
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }

    private fun ejecutarNavegacion(texto: String) {
        val destino = texto.lowercase()
            .replace("busca la mejor ruta a", "")
            .replace("cómo llego a", "")
            .replace("llévame a", "")
            .replace("ruta a", "").trim()

        hablar("Calculando la mejor ruta hacia $destino") {
            val gmmIntentUri = Uri.parse("google.navigation:q=${Uri.encode(destino)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                // Usamos setPackage para asegurar que abra Google Maps
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(mapIntent)
            } catch (e: Exception) {
                // Si no tiene Maps, abrimos en el navegador
                val webMaps = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://www.google.com/maps/dir/?api=1&destination=${
                            Uri.encode(
                                destino
                            )
                        }"
                    )
                )
                webMaps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webMaps)
            }
            isProcessing = false
        }
    }


    private fun ejecutarBusquedaWeb(texto: String) {
        var busqueda = texto.lowercase()
            .replace(
                Regex("(busca|buscar|investiga|qué es|que es|quien es|qué significa|que significa|dime sobre|hablame de|cuéntame de)"),
                ""
            )
            .replace(Regex("(en internet|en la web|en google|por favor)"), "")
            .trim()

        busqueda = busqueda.replace(Regex("^(el |la |los |las |un |una |unos |unas )"), "").trim()

        if (busqueda.isEmpty()) {
            hablar("¿Qué quieres que busque?") {
                isProcessing = false
                if (sesionActiva) iniciarSRContinuo()
            }
            return
        }

        ui.showText("🔍 Buscando: $busqueda")
        setState(JarvisState.THINKING)

        scope.launch {
            try {
                // Cambiado a searchAdvanced para obtener tanto texto como URLs asociadas
                val resultado = TavilySearchService.searchAdvanced(busqueda)

                if (resultado.content.isNotBlank() && resultado.content.length > 20) {
                    // Guardamos el contexto estructurado en memoria
                    ultimoResultadoBusqueda = resultado

                    withContext(Dispatchers.Main) {
                        ui.showText(resultado.content)
                        if (resultado.imageUrls.isNotEmpty()) {
                            ui.showImages(resultado.imageUrls)
                        }
                        hablar("Esto fue lo que encontré.") {
                            isProcessing = false
                            if (sesionActiva) {
                                mainHandler.postDelayed({ iniciarSRContinuo() }, 500)
                            }
                        }
                    }
                } else {
                    // Fallback a confirmación de Google existente en tu código...
                    val mensaje = "No encontré información. ¿Quieres que busque en Google?"
                    withContext(Dispatchers.Main) {
                        ui.showText(mensaje)
                        hablar(mensaje) {
                            isProcessing = false
                            if (sesionActiva) {
                                mainHandler.postDelayed({
                                    esperandoConfirmacionGoogle = true
                                    busquedaPendienteGoogle = busqueda
                                    iniciarSRContinuo()
                                }, 500)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en búsqueda Tavily Avanzada: ${e.message}")
                isProcessing = false
            }
        }
    }

    // Función para dividir respuestas largas
    private fun dividirRespuesta(respuesta: String): List<String> {
        val partes = mutableListOf<String>()
        var resto = respuesta

        while (resto.length > 250) {
            // Buscar un punto o espacio para cortar
            var corte = resto.substring(0, 250).lastIndexOf('.')
            if (corte < 50) corte = resto.substring(0, 250).lastIndexOf(' ')
            if (corte < 50) corte = 250

            partes.add(resto.substring(0, corte + 1))
            resto = resto.substring(corte + 1).trim()
        }
        if (resto.isNotEmpty()) partes.add(resto)

        return partes
    }

    // Función para hablar partes con pausas
    private fun hablarPartes(partes: List<String>, index: Int = 0) {
        if (index >= partes.size) {
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
            return
        }

        hablar(partes[index]) {
            if (index + 1 < partes.size) {
                mainHandler.postDelayed({
                    hablarPartes(partes, index + 1)
                }, 1000)
            } else {
                isProcessing = false
                if (sesionActiva) iniciarSRContinuo()
            }
        }
    }

    private suspend fun realizarBusquedaDuckDuckGo(query: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val url =
                    "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_html=1&skip_disambig=1"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "JarvisAssistant/1.0")
                    .build()

                val response = client.newCall(request).execute()
                val jsonString = response.body?.string()

                if (response.isSuccessful && jsonString != null) {
                    val json = JSONObject(jsonString)

                    // Extraer respuesta directa
                    val abstractText = json.optString("AbstractText", "")
                    val answer = json.optString("Answer", "")
                    val definition = json.optString("Definition", "")
                    val heading = json.optString("Heading", "")

                    // Construir respuesta amigable
                    when {
                        answer.isNotEmpty() -> {
                            return@withContext answer
                        }

                        definition.isNotEmpty() -> {
                            return@withContext "Según DuckDuckGo: $definition"
                        }

                        abstractText.isNotEmpty() -> {
                            // Limitar a 500 caracteres para no ser muy largo
                            val texto = if (abstractText.length > 500)
                                abstractText.substring(0, 500) + "..."
                            else abstractText
                            return@withContext texto
                        }

                        heading.isNotEmpty() -> {
                            val relatedTopics = json.optJSONArray("RelatedTopics")
                            if (relatedTopics != null && relatedTopics.length() > 0) {
                                val primerResultado = relatedTopics.getJSONObject(0)
                                val texto = primerResultado.optString("Text", "")
                                if (texto.isNotEmpty()) {
                                    return@withContext "Sobre $heading: ${texto.take(400)}"
                                }
                            }
                            return@withContext "Información sobre $heading. Puedes pedirme más detalles si lo deseas."
                        }

                        else -> {
                            // Buscar en RelatedTopics
                            val relatedTopics = json.optJSONArray("RelatedTopics")
                            if (relatedTopics != null && relatedTopics.length() > 0) {
                                for (i in 0 until minOf(3, relatedTopics.length())) {
                                    val topic = relatedTopics.getJSONObject(i)
                                    val text = topic.optString("Text", "")
                                    if (text.isNotEmpty() && text.length > 20) {
                                        return@withContext text.take(400)
                                    }
                                }
                            }
                            return@withContext "No encontré información detallada sobre '$query'. ¿Quieres que busque otra cosa?"
                        }
                    }
                } else {
                    return@withContext ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en búsqueda DuckDuckGo: ${e.message}")
                return@withContext ""
            }
        }
    }

    private fun ejecutarClima() {
        hablar("Consultando el clima para ti") {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=clima+actual")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }

    private fun ejecutarResumenNotificaciones() {
        // Obtenemos el texto de las notificaciones que ya tienes programado
        val resumen = obtenerResumenNotificaciones()

        hablar(resumen) {
            isProcessing = false
            if (sesionActiva) {
                // Después de leerlas, Jarvis vuelve a escuchar por si quieres hacer algo más
                mainHandler.postDelayed({ iniciarSRContinuo() }, 500)
            }
        }
    }

    // Agregar este método a JarvisVoiceController.kt

    private suspend fun obtenerDireccionGrpc(): Pair<String, Int>? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                // 🔑 REEMPLAZA ESTO CON TU URL REAL DE MODAL
                val url = "https://mausand2499--jarvoice-nexus-api-fastapi-server-dev.modal.run/grpc-endpoint"
                Log.d(TAG, "📡 Consultando: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val status = json.optString("status", "")

                    Log.d(TAG, "📡 Response: $json")

                    if (status == "ready") {
                        val host = json.optString("host", "")
                        val port = json.optInt("port", 0)

                        if (host.isNotEmpty() && port > 0) {
                            Log.d(TAG, "✅ Endpoint: $host:$port")
                            return@withContext Pair(host, port)
                        }
                    } else if (status == "not_ready") {
                        Log.w(TAG, "⏳ gRPC aún no listo, reintentando...")
                        delay(2000)
                        return@withContext obtenerDireccionGrpc()
                    } else {
                        Log.e(TAG, "❌ Estado desconocido: $status")
                    }
                } else {
                    Log.e(TAG, "❌ HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}")
            }
            null
        }
    }
    private fun inicializarWebSocket() {
        if (wsClient != null) return

        scope.launch {
            // Intentar obtener la dirección del servidor gRPC
            val direccion = obtenerDireccionGrpc()

            val urlCompleta = if (direccion != null) {
                val (host, port) = direccion
                "$host:$port"
            } else {
                // Fallback al endpoint directo de Modal
                "mausand2499--jarvoice-nexus-api-fastapi-server-dev.modal.run"        }

            Log.d(TAG, "📡 Conectando a WebSocket en: $urlCompleta")

            wsClient = NexusWebSocketClient(
                hostUrl = urlCompleta,
                scope = scope
            )

            wsClient?.onConnected = {
                Log.d(TAG, "🚀 Canal WebSocket con Nexus AI vinculado exitosamente.")
            }

            wsClient?.onDisconnected = {
                Log.d(TAG, "🛑 Conexión WebSocket cerrada.")
                mainHandler.post {
                    setState(JarvisState.IDLE)
                }
            }

            wsClient?.onError = { error ->
                Log.e(TAG, " Error en el canal de comunicación: $error")
                mainHandler.post {
                    ui.showToast("Error de conexión con el servidor")
                    setState(JarvisState.IDLE)
                    isProcessing = false
                }
            }

            wsClient?.onEvent = { jsonString ->
                Log.d(TAG, "Evento recibido: ${jsonString.take(200)}...")
                procesarEventoWebSocket(jsonString)  // ✅ Aquí está la llamada
            }
            wsClient?.connect()
        }
    }
    // ────────────────────────────────────────────────────────────────────────
// gRPC - Cliente
// ────────────────────────────────────────────────────────────────────────
    private fun procesarEventoWebSocket(jsonString: String) {
        try {
            Log.d(TAG, "Procesando evento: $jsonString")

            val root = JSONObject(jsonString)
            val eventType = root.optString("type", "")
            val stage = root.optString("stage", "")

            Log.d(TAG, "Evento Type: $eventType, Stage: $stage")

            when (eventType) {
                "PAYLOAD" -> {
                    timeoutServidor?.let { mainHandler.removeCallbacks(it) }
                    timeoutServidor = null
                    esperandoRespuestaServidor = false
                    estaEnviandoAlServidor = false
                    estaProcesandoComando = false
                    uiState?.serverProcessing = false

                    val payloadObj = root.getJSONObject("payload")
                    val actionsArray = payloadObj.optJSONArray("payload")
                    val responseText = payloadObj.optString("response", "")
                    val action = payloadObj.optString("action", "")
                    Log.d(TAG, " PAYLOAD recibido:")
                    Log.d(TAG, "  - response: $responseText")
                    Log.d(TAG, "  - action: $action")
                    Log.d(TAG, "  - actions: ${actionsArray?.length() ?: 0}")

                    mainHandler.post {
                        uiState?.serverProcessing = false
                        //  Mostrar respuesta en UI
                        if (responseText.isNotBlank()) {
                            uiState?.apply {
                                showPanel = true
                                transcription = responseText
                                typewriterText = responseText
                                fullHtmlText = responseText
                            }
                            ui.showText(responseText)
                        }

                        // HABLAR LA RESPUESTA (TTS)
                        if (responseText.isNotBlank()){
                            Log.d(TAG, "Hablando: $responseText")
                            hablar(responseText) {
                                finalizarInteraccion()
                            }
                        } else if (responseText.isNotBlank()) {
                            // Respuesta larga - dividir en partes
                            val partes = dividirRespuesta(responseText)
                            hablarPartes(partes)
                        } else {
                            // No hay respuesta de texto, solo ejecutar acciones
                            isProcessing = false
                            if (sesionActiva) {
                                mainHandler.postDelayed({ iniciarSRContinuo() }, 500)
                            }
                        }

                        if (actionsArray != null && actionsArray.length() > 0) {
                            val broadcastActions = mutableListOf<ActionDto>()
                            for (i in 0 until actionsArray.length()) {
                                val accion = actionsArray.getJSONObject(i)
                                val tipo = accion.getString("tipo")
                                when (tipo) {
                                    "navigate" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val lat = params.getDouble("destination_lat")
                                        val lng = params.getDouble("destination_lng")
                                        val name = params.optString("destination_name", "Destino")
                                        ActionExecutor.navigateTo(context, lat, lng, name)
                                    }
                                    "show_search_result" -> {
                                        // (tu código existente para mostrar resultados de búsqueda)
                                        val params = accion.optJSONObject("params")
                                        if (params != null) {
                                            val answer = params.optString("answer", responseText)
                                            val sources = mutableListOf<String>()
                                            val images = mutableListOf<String>()
                                            // ... etc.
                                            ui.showSearchResult(answer, sources, images, emptyList())
                                        }
                                    }
                                    // ═══════ NUEVOS CASOS ═══════
                                    "send_whatsapp" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val contact = params.optString("contact", "")
                                        val message = params.optString("message", "")
                                        if (contact.isNotBlank() && message.isNotBlank()) {
                                            ActionExecutor.sendWhatsAppMessage(context, contact, message)
                                        }
                                    }
                                    "send_telegram" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val contact = params.optString("contact", "")
                                        val message = params.optString("message", "")
                                        if (contact.isNotBlank() && message.isNotBlank()) {
                                            ActionExecutor.sendTelegramMessage(context, contact, message)
                                        }
                                    }
                                    "send_sms" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val contact = params.optString("contact", "")
                                        val message = params.optString("message", "")
                                        if (contact.isNotBlank() && message.isNotBlank()) {
                                            ActionExecutor.sendSms(context, contact, message)
                                        }
                                    }
                                    "open_apps_in_split_screen" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val appsArray = params.optJSONArray("apps")
                                        if (appsArray != null && appsArray.length() >= 2) {
                                            val packages = mutableListOf<String>()
                                            for (i in 0 until appsArray.length()) {
                                                val appName = appsArray.getString(i)
                                                val pkg = ActionExecutor.getPackageNameFromAppName(appName, context)
                                                if (pkg != null) packages.add(pkg)
                                            }
                                            if (packages.size >= 2) {
                                                ActionExecutor.openAppsInSplitScreen(context, packages)
                                            } else {
                                                Log.w(TAG, "No se encontraron paquetes para las apps: $appsArray")
                                            }
                                        }
                                    }
                                    else -> {
                                        // El resto se envía al AccessibilityService (como ya hacías)
                                        try {
                                            val paramsObj = accion.optJSONObject("params") ?: JSONObject()
                                            val actionParams = mutableMapOf<String, Any>()
                                            val keys = paramsObj.keys()
                                            while (keys.hasNext()) {
                                                val key = keys.next()
                                                actionParams[key] = paramsObj.get(key)
                                            }
                                            broadcastActions.add(ActionDto(tipo = tipo, params = actionParams))
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error parseando acción: ${e.message}")
                                        }
                                    }
                                }
                            }
                            if (broadcastActions.isNotEmpty()) {
                                Log.d(TAG, "🎯 Ejecutando ${broadcastActions.size} acciones")
                                ejecutarAccionesTecnicas(broadcastActions, responseText, "websocket_payload")
                            }
                        }
                    }
                }

                "PROGRESS" -> {
                    val payload = root.getJSONObject("payload")
                    val message = payload.optString("message", "")
                    val percent = payload.optDouble("percent", 0.0)
                    val type = payload.optString("type", "")
                    val step = payload.optString("step", "")
                    val status = payload.optString("status", "")
                    val steps = buildStepsFromStep(step, status, message)
                    if (steps.isNotEmpty()) {
                        mainHandler.post {
                            uiState?.serverProcessing = true
                            uiState?.showPanel = true
                            ui.updateProcessingSteps(steps)
                        }
                    }

                    Log.d(TAG, "Progreso: $percent% - $message (type: $type)")

                    mainHandler.post {
                        when {
                            type == "conversacional" || type == "fallback" -> {
                                // ✅ Cancelar timeout del servidor — esta es la respuesta final
                                timeoutServidor?.let { mainHandler.removeCallbacks(it) }
                                timeoutServidor = null
                                esperandoRespuestaServidor = false
                                estaEnviandoAlServidor = false
                                estaProcesandoComando = false

                                if (message.isNotBlank()) {
                                    Log.d(TAG, "Respuesta conversacional: $message")
                                    ui.showText(message)
                                    hablar(message) {
                                        Log.d(TAG, "TTS conversacional completado")
                                        isProcessing = false
                                        sesionActiva = false
                                        estaHablando = false
                                        setState(JarvisState.IDLE)
                                        ui.hideOverlayFromTimeout()
                                    }
                                }
                            }
                            type == "busqueda" -> {
                                if (message.isNotBlank()) {
                                    ui.showText("🔍 $message")
                                }
                            }
                            message.contains("listo") || percent >= 1.0 -> {
                                Log.d(TAG, "Procesamiento completado")
                            }
                            message.isNotBlank() && status != "active" -> {
                                ui.showText(message)
                            }
                        }
                    }

                    if (percent >= 1.0 && message.contains("listo")) {
                        mainHandler.postDelayed({
                            if (estaEnviandoAlServidor && !estaProcesandoComando) {
                                Log.w(TAG, "PAYLOAD tardó — limpiando estado")
                                cancelarEnvioActual()
                                setState(JarvisState.IDLE)
                            }
                        }, 3000L)
                    }
                }

                "INTENT", "REFINED" -> {
                    val payload = root.getJSONObject("payload")
                    val intent = payload.optString("intent", "")
                    val params = payload.optJSONObject("params")

                    Log.d(TAG, "🎯 Intent: $intent, Params: $params")
                }

                "ERROR" -> {
                    val payload = root.getJSONObject("payload")
                    val errorMsg = payload.optString("message", "Error desconocido")
                    Log.e(TAG, "❌ Error del servidor: $errorMsg")
                    estaEnviandoAlServidor = false
                    estaProcesandoComando = false
                    cancelarEnvioActual()
                    mainHandler.post {
                        ui.showText("Error: $errorMsg")
                        setState(JarvisState.IDLE)
                        isProcessing = false
                        if (sesionActiva) {
                            mainHandler.postDelayed({ iniciarSRContinuo() }, 500)
                        }
                    }
                }

                else -> {
                    // Fallback: intentar extraer TTS de cualquier evento
                    if (root.has("tts")) {
                        val ttsObj = root.getJSONObject("tts")
                        val respuestaTexto = ttsObj.getString("text")
                        hablar(respuestaTexto) { finalizarInteraccion() }

                        Log.d(TAG, "🔊 TTS extraído: $respuestaTexto")

                        mainHandler.post {
                            ui.showText(respuestaTexto)
                            hablar(respuestaTexto) {
                                isProcessing = false
                                if (sesionActiva) iniciarSRContinuo()
                            }
                        }
                    }

                    // También revisar si hay respuesta en el payload
                    if (root.has("payload")) {
                        val payloadObj = root.getJSONObject("payload")
                        val responseText = payloadObj.optString("response", "")

                        if (responseText.isNotBlank()) {
                            ui.showText(responseText)
                            hablar(responseText) { finalizarInteraccion() }
                        }
                    }
                }
            }

            // También manejar el campo "stage" directamente
            if (root.has("stage") && eventType != "PAYLOAD") {
                val stageValue = root.getString("stage")
                mainHandler.post {
                    when (stageValue) {
                        "STAGE_LISTENING" -> setState(JarvisState.LISTENING)
                        "STAGE_THINKING" -> setState(JarvisState.THINKING)
                        "STAGE_READY" -> setState(JarvisState.IDLE)
                        "STAGE_SPEAKING" -> setState(JarvisState.SPEAKING)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, " Error procesando evento WebSocket: ${e.message}", e)
        }
    }
    private fun buildStepsFromStep(step: String, status: String, message: String): List<ProcessingStep> {
        // Definir los pasos fijos
        val allSteps = listOf(
            "Texto recibido",
            "Verificando reglas",
            "Clasificando intención",
            "Identificando comando",
            "Refinando con LLaMA",
            "Construyendo respuesta"
        )

        // Mapa de estado para cada paso
        val stepStatusMap = mutableMapOf<String, StepStatus>()

        // Inicializar todos como PENDING
        allSteps.forEach { stepStatusMap[it] = StepStatus.PENDING }

        // Marcar pasos según el step actual
        when (step) {
            "recibido" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
            }
            "rescate" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
                stepStatusMap["Verificando reglas"] = if (status == "done") StepStatus.DONE else StepStatus.ACTIVE
            }
            "clasificando" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
                stepStatusMap["Verificando reglas"] = StepStatus.DONE
                stepStatusMap["Clasificando intención"] = if (status == "done") StepStatus.DONE else StepStatus.ACTIVE
            }
            "jarvoice" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
                stepStatusMap["Verificando reglas"] = StepStatus.DONE
                stepStatusMap["Clasificando intención"] = StepStatus.DONE
                stepStatusMap["Identificando comando"] = if (status == "done") StepStatus.DONE else StepStatus.ACTIVE
            }
            "llama" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
                stepStatusMap["Verificando reglas"] = StepStatus.DONE
                stepStatusMap["Clasificando intención"] = StepStatus.DONE
                stepStatusMap["Identificando comando"] = StepStatus.DONE
                stepStatusMap["Refinando con LLaMA"] = if (status == "done") StepStatus.DONE else StepStatus.ACTIVE
            }
            "payload" -> {
                stepStatusMap["Texto recibido"] = StepStatus.DONE
                stepStatusMap["Verificando reglas"] = StepStatus.DONE
                stepStatusMap["Clasificando intención"] = StepStatus.DONE
                stepStatusMap["Identificando comando"] = StepStatus.DONE
                stepStatusMap["Refinando con LLaMA"] = StepStatus.DONE
                stepStatusMap["Construyendo respuesta"] = if (status == "done") StepStatus.DONE else StepStatus.ACTIVE
            }
            "completado", "saludo", "conversacional", "fallback" -> {
                allSteps.forEach { stepStatusMap[it] = StepStatus.DONE }
            }
        }

        // Convertir a lista de ProcessingStep
        return allSteps.map { stepText ->
            ProcessingStep(stepText, stepStatusMap[stepText] ?: StepStatus.PENDING)
        }
    }
    private fun parseActionsFromJson(actionsArray: org.json.JSONArray): List<ActionDto> {
        val actions = mutableListOf<ActionDto>()
        for (i in 0 until actionsArray.length()) {
            try {
                val actionObj = actionsArray.getJSONObject(i)
                val tipo = actionObj.getString("tipo")
                val params = mutableMapOf<String, Any>()

                val paramsObj = actionObj.optJSONObject("params")
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        params[key] = paramsObj.get(key)
                    }
                }

                actions.add(ActionDto(tipo = tipo, params = params))
            } catch (e: Exception) {
                Log.e(TAG, "Error parseando acción: ${e.message}")
            }
        }
        return actions
    }
    // API
    // ────────────────────────────────────────────────────────────────────────

    private fun enviarComandoAlServidor(texto: String) {
        if (estaEnviandoAlServidor) {
            Log.d(TAG, "Había un envío en curso, cancelando para procesar nuevo comando")
            cancelarEnvioActual()
            // Esperar un momento para que se limpie
            mainHandler.postDelayed({
                enviarComandoAlServidor(texto)
            }, 300L)
            return
        }
        esperandoRespuestaServidor = true
        estaEnviandoAlServidor = true
        estaProcesandoComando = true
        mainHandler.removeCallbacks(timeoutRunnable)
        voiceEngine.detenerSesion()  // Detener STT mientras procesamos
        // iniciar timeout para evitar que se quede colgado
        timeoutServidor?.let { mainHandler.removeCallbacks(it) }
        timeoutServidor = Runnable {
            Log.w(TAG, "⏰ TIMEOUT del servidor (${TIMEOUT_SERVIDOR_MS}ms)")
            cancelarEnvioActual()
            setState(JarvisState.IDLE)
            ui.showText("El servidor tardó demasiado en responder")
            // Volver a modo wake word
            stopListeningCompletamente()
        }

        mainHandler.postDelayed(timeoutServidor!!, TIMEOUT_SERVIDOR_MS)
        scope.launch {
            try {
                setState(JarvisState.THINKING)
                ui.updateProcessingSteps(
                    listOf(
                        ProcessingStep("Escuchando tu pregunta", StepStatus.DONE),
                        ProcessingStep("Obteniendo ubicación", StepStatus.ACTIVE),
                        ProcessingStep("Analizando pantalla", StepStatus.ACTIVE),
                        ProcessingStep("Consultando servidor", StepStatus.PENDING),
                        ProcessingStep("Preparando respuesta", StepStatus.PENDING)
                    )
                )

                // 📍 Obtener ubicación
                var latitude: Double? = null
                var longitude: Double? = null
                var locationError: String? = null
                try {
                    val location = withTimeoutOrNull(5000L) {
                        LocationHelper.getCurrentLocation(context)
                    }
                    if (location != null) {
                        latitude = location.latitude
                        longitude = location.longitude
                        Log.d(TAG, "Ubicación obtenida: $latitude, $longitude")
                        ui.showText("📍 Ubicación obtenida")
                    } else {
                        locationError = "No se pudo obtener ubicación"
                    }
                } catch (e: Exception) {
                    locationError = "Error: ${e.message}"
                }

                // 📸 Capturar pantalla
                MyAccessibilityService.instance?.captureNow()
                delay(200)

                val snapshot = ScreenMemory.lastSnapshot

                ui.updateProcessingSteps(
                    listOf(
                        ProcessingStep("Escuchando tu pregunta", StepStatus.DONE),
                        ProcessingStep("Obteniendo ubicación", StepStatus.DONE),
                        ProcessingStep("Analizando pantalla", StepStatus.DONE),
                        ProcessingStep("Consultando servidor", StepStatus.ACTIVE),
                        ProcessingStep("Preparando respuesta", StepStatus.PENDING)
                    )
                )

                // 🌐 Verificar WebSocket
                val client = wsClient ?: run {
                    inicializarWebSocket()
                    wsClient ?: error("No se pudo inicializar el canal WebSocket")
                }

                if (!client.isReady()) {
                    Log.d(TAG, "Reconectando canal WebSocket antes de transmitir...")
                    client.connect()
                }

                var intentos = 0
                while (!client.isReady() && intentos < 30) {
                    delay(100)
                    intentos++
                }

                if (!client.isReady()) {
                    Log.e(TAG, "WebSocket no se pudo conectar tras 3 segundos de tolerancia")
                    ui.showText("Error: Servidor remoto no disponible")
                    isProcessing = false
                    ui.updateProcessingSteps(emptyList())
                    setState(JarvisState.IDLE)
                    return@launch
                }

                // ✅ Construir el JSON correctamente
                val mensajeJson = JSONObject().apply {
                    put("text", texto.trim())  // ✅ Texto limpio
                    put("timestamp", System.currentTimeMillis())

                    val contextObj = JSONObject().apply {
                        put("packageName", snapshot?.packageName ?: "unknown")
                        put("activityName", snapshot?.activityName ?: "unknown")
                        put("totalElements", snapshot?.totalElements ?: 0)
                        put("lat", latitude ?: 0.0)
                        put("lon", longitude ?: 0.0)
                        put("locationError", locationError ?: "")

                        val elementsArray = org.json.JSONArray()
                        snapshot?.elements?.take(50)?.forEach { element ->
                            val elObj = JSONObject().apply {
                                put("id", element.id)
                                put("text", element.text ?: "")
                                put("x", element.centerX)
                                put("y", element.centerY)
                                put("clickable", element.isClickable)
                                put("description", element.contentDescription ?: "")
                            }
                            elementsArray.put(elObj)
                        }
                        put("elements", elementsArray)
                    }
                    put("context", contextObj)
                }

                // ✅ Enviar el JSON directamente usando el WebSocket
                val jsonString = mensajeJson.toString()
                Log.d(TAG, "📤 Enviando comando: $jsonString")

                // ✅ Enviar directamente sin pasar por sendText()
                client.sendText(jsonString)

                Log.d(TAG, "📤 Comando enviado exitosamente")

            } catch (e: Exception) {
                Log.e(TAG, "Error crítico en enviarComandoAlServidor: ${e.message}")
                isProcessing = false
                setState(JarvisState.IDLE)
                ui.updateProcessingSteps(emptyList())
                ui.showText("Error: ${e.message}")
                cancelarEnvioActual()
            }
        }
    }

    private fun cancelarEnvioActual() {
        // Limpia estado de envío
        estaEnviandoAlServidor = false
        estaProcesandoComando = false

        // Cancelar timeout
        timeoutServidor?.let { mainHandler.removeCallbacks(it) }
        timeoutServidor = null
        esperandoRespuestaServidor = false
        // Limpiar UI de pasos de procesamiento
        ui.updateProcessingSteps(emptyList())
        uiState?.apply {
            serverProcessing = false
            processingSteps = emptyList()
            showPanel = false
        }

        Log.d(TAG, " Envío al servidor cancelado/limpiado")
    }
    /**
     * Obtiene un resumen de las notificaciones activas del dispositivo.
     * Accede a NotificationMemory que ya está sincronizado con las notificaciones.
     */
    private fun obtenerResumenNotificaciones(): String {
        val listener = JarvisNotificationListener.instance
        if (listener == null) return "Lo siento, el servicio de notificaciones no está conectado."

        listener.refrescarNotificacionesActivas()
        val lista = NotificationMemory.getNotifications()

        val filtradas = lista.filter { noti ->
            val esSistema = noti.packageName.contains("android") || noti.packageName.contains("systemui")
            val tieneContenido = noti.title.isNotBlank() || noti.body.isNotBlank()
            val esCodigo = noti.body.matches(Regex(".*[0-9]{5,}.*"))
            !esSistema && tieneContenido && !esCodigo
        }

        if (filtradas.isEmpty()) return "No tienes notificaciones importantes por ahora."

        val agrupadas = filtradas.groupBy { it.appName }
        val total = filtradas.size
        val sb = StringBuilder()
        sb.append("Tienes $total ${if (total == 1) "notificación" else "notificaciones"}. ")

        agrupadas.forEach { (appName, notis) ->
            val cantidad = notis.size
            if (cantidad == 1) {
                val n = notis[0]
                val emisor = n.title.split(":").firstOrNull() ?: n.title
                sb.append("En $appName, $emisor dice: ${n.body.take(40)}. ")
            } else {
                val emisores = notis.map { it.title }.distinct().take(3).joinToString(", ")
                sb.append("En $appName tienes $cantidad mensajes, principalmente de $emisores. ")
            }
        }
        return sb.toString()
    }
    // Función auxiliar para que no diga "com.whatsapp" sino "WhatsApp"
    private fun cuandoSeaPaqueteDimeNombre(pkg: String): String {
        return when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("messenger") -> "Messenger"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("android.gm") -> "Gmail"
            pkg.contains("telegram") -> "Telegram"
            else -> "una aplicación"
        }
    }
    // ────────────────────────────────────────────────────────────────────────
    // TTS
    // ────────────────────────────────────────────────────────────────────────

    private fun configurarTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("es", "ES"))
                ttsListo = true
                Log.d(TAG, " TTS listo")
            } else {
                Log.e(TAG, " Error TTS: $status")
            }
        }
    }
//     ────────────────────────────────────────────────────────────────────────
//     TTS — ELEVEN LABS
//     ────────────────────────────────────────────────────────────────────────

    private fun hablarElevenLabs(texto: String, alTerminar: (() -> Unit)? = null) {
        setState(JarvisState.SPEAKING)

        scope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val jsonBody = JSONObject().apply {
                    put("text", texto)
                    put("model_id", ElevenLabsConfig.MODEL_ID)
                    put("voice_settings", JSONObject().apply {
                        put("stability", ElevenLabsConfig.STABILITY)
                        put("similarity_boost", ElevenLabsConfig.SIMILARITY_BOOST)
                        put("style", ElevenLabsConfig.STYLE)
                        put("use_speaker_boost", ElevenLabsConfig.SPEAKER_BOOST)
                    })
                }.toString()

                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/${ElevenLabsConfig.VOICE_ID}")
                    .addHeader("xi-api-key", ElevenLabsConfig.API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/mpeg")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, " ElevenLabs HTTP ${response.code} — fallback a Android TTS")
                    withContext(Dispatchers.Main) { hablarAndroid(texto, alTerminar) }
                    return@launch
                }

                val audioBytes = response.body!!.bytes()
                val tempFile =
                    java.io.File(context.cacheDir, "jarvis_el_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(audioBytes)

                withContext(Dispatchers.Main) {
                    reproducirAudioElevenLabs(tempFile, alTerminar)
                }

            } catch (e: Exception) {
                Log.e(TAG, " ElevenLabs excepción: ${e.message} — fallback a Android TTS")
                withContext(Dispatchers.Main) { hablarAndroid(texto, alTerminar) }
            }
        }
    }

    private fun reproducirAudioElevenLabs(archivo: java.io.File, alTerminar: (() -> Unit)? = null) {
        iniciarAnimacionOrbeSimulada()

        elMediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(archivo.absolutePath)
            prepare()
        }

        // Permitir interrupción temprana igual que en hablarAndroid
        mainHandler.postDelayed({
//            sessionManager.setAllowEarlyInput(true)
        }, 50L)

        elMediaPlayer?.setOnCompletionListener {
            mainHandler.post {
                ui.updateORB(0f)
                ttsTerminoTimestamp = System.currentTimeMillis()
//                sessionManager.setAllowEarlyInput(false)
                archivo.delete()
                elMediaPlayer?.release()
                elMediaPlayer = null // Libera la referencia
                alTerminar?.invoke()
                if (sesionActiva) {
                    mainHandler.postDelayed({
//                        hybridTranscriber.reiniciarEscucha()
                    }, 800L)
                }
            }
        }
        elMediaPlayer?.setOnErrorListener { _, what, extra ->
            Log.e(TAG, " MediaPlayer error: what=$what extra=$extra")
            archivo.delete()
            elMediaPlayer?.release()
            elMediaPlayer = null
            hablarAndroid("", alTerminar)
            true
        }

        elMediaPlayer?.start()
    }

    private fun hablarAndroid(texto: String, alTerminar: (() -> Unit)? = null) {
        if (!ttsListo) {
            alTerminar?.invoke()
            return
        }
        estaHablando = true
        timestampUltimoTTS = System.currentTimeMillis()
        mainHandler.removeCallbacks(timeoutRunnable)

        if (voiceEngine.isSrSessionActive()) {
            voiceEngine.detenerSesion()
        }
        setState(JarvisState.SPEAKING)
        modoConversacionActivo = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS INICIÓ")
                iniciarAnimacionOrbeSimulada()
            }

            override fun onDone(id: String?) {
                Log.d(TAG, "TTS TERMINÓ")
                mainHandler.post {
                    ui.updateORB(0f)
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    isProcessing = false
                    estaEnviandoAlServidor = false
                    estaProcesandoComando = false
                    estaHablando = false
                    alTerminar?.invoke()
                }
            }

            override fun onError(id: String?) {
                Log.e(TAG, "Error en TTS")
                mainHandler.post {
                    alTerminar?.invoke()
                    if (sesionActiva) {
                        modoConversacionActivo = false
                        esperandoPostTTS = false
                        stopListeningCompletamente()
                    }
                }
            }
        })

        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "UTT_${System.currentTimeMillis()}")
    }
    private fun finalizarInteraccion() {
        Log.d(TAG, "Finalizando interacción, volviendo a modo IDLE")
        stopListeningCompletamente()   // sets sesionActiva = false, stops STT
        ui.hideOverlayFromTimeout()
    }

    fun detenerAudio() {
        mainHandler.post {
            try {
                // 1. Detener el TTS nativo de Android si está hablando
                if (ttsListo && tts.isSpeaking) {
                    Log.d(TAG, " Deteniendo Android TTS por petición del usuario.")
                    tts.stop()
                }

                // 2. Detener el MediaPlayer de ElevenLabs si está activo
                elMediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        Log.d(
                            TAG,
                            " Deteniendo MediaPlayer de ElevenLabs por petición del usuario."
                        )
                        player.stop()
                    }
                    player.release()
                    elMediaPlayer = null
                }

                // 3. Limpiar los efectos visuales del Orbe y actualizar el estado
                ui.updateORB(0f)
//                sessionManager.setAllowEarlyInput(false)
                setState(JarvisState.IDLE)

            } catch (e: Exception) {
                Log.e(TAG, "Error al detener el audio: ${e.message}")
            }
        }
    }

    // ── Router TTS — cambia TTS_MODE arriba del archivo para alternar ────────
    fun hablar(texto: String, alTerminar: (() -> Unit)? = null) {
        // Si el texto está vacío o es un comando interno, no usar TTS
        if (texto.isBlank() || texto.startsWith("JARVIS.")) {
            alTerminar?.invoke()
            return
        }

        when (TTS_MODE) {
            TtsMode.ANDROID -> hablarAndroid(texto, alTerminar)
            TtsMode.ELEVEN_LABS -> {
                // Para comandos de música/YouTube, usar TTS de Android (más rápido)
                if (texto.contains("reproduciendo") || texto.contains("buscando")) {
                    hablarAndroid(texto, alTerminar)
                } else {
                    hablarElevenLabs(texto, alTerminar)
                }
            }
        }
    }

    /**
     * Detiene el TTS inmediatamente (para cuando el usuario interrumpe).
     */
    private fun detenerTTS() {
        if (tts.isSpeaking) {
            tts.stop()
            ui.updateORB(0f)
            ttsTerminoTimestamp = System.currentTimeMillis()
            isProcessing = false
            Log.d(TAG, " TTS detenido por interrupción")
        }
    }

    private fun iniciarAnimacionOrbeSimulada() {
        scope.launch {
            var contador = 0
            while (tts.isSpeaking) {
                val base = 3f
                val impulso = (Math.sin(contador.toDouble() * 0.5).toFloat() * 5f) + 5f
                val pico = if (Math.random() > 0.8) 4f else 0f
                val rms = (base + impulso + pico).coerceIn(0f, 15f)
                withContext(Dispatchers.Main) { ui.updateORB(rms) }
                contador++
                delay(50)
            }
            withContext(Dispatchers.Main) { ui.updateORB(0f) }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // GESTIÓN DE SESIÓN
    // ────────────────────────────────────────────────────────────────────────

    fun startInteraction() {
        if (sesionActiva) {
            Log.w(TAG, "Sesión ya activa")
            return
        }
        sesionActiva = true
        isProcessing = false
        setState(JarvisState.SPEAKING)
        audioManager.playMicOn()
        hacerVibrar(60)
//        porcupineController?.pausarPorcupine()
//        if (::audioEngine.isInitialized) audioEngine.stop()
        mainHandler.postDelayed({ iniciarSRContinuo() }, 300L)
    }

    private fun iniciarSRContinuo(timeoutMs: Long = TIMEOUT_ESCUCHA_MS) {
        if (!sesionActiva) return
        if (estaHablando) {
            Log.d(TAG, " No iniciar SR porque el asistente está hablando")
            return
        }
        if (voiceEngine.isSrSessionActive()) {
            Log.d(TAG, "SR ya activo — ignorando iniciarSRContinuo")
            return
        }

        setState(JarvisState.LISTENING)
        voiceEngine.iniciarSesionContinua(language = "es")
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
        Log.d(TAG, "SR iniciado, timeout de ${timeoutMs}ms")
    }

    private fun hacerVibrar(millisegundos: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ usa VibratorManager
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // API 26+ requiere VibrationEffect
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            millisegundos,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    // Versiones antiguas
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(millisegundos)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, " Vibración no disponible: ${e.message}")
        }
    }

    fun activarDesdeWakeWord() {
        if (sesionActiva) return
        startInteraction()
    }


    private fun stopListeningCompletamente() {

        sesionActiva = false
        isProcessing = false
        esperandoConfirmacion = false
        wakeWordDetected = false
        modoConversacionActivo = false
        esperandoPostTTS = false
        esperandoRespuestaServidor = false
        estaEnviandoAlServidor = false
        estaProcesandoComando = false
        voiceEngine.detenerSesion()
//        hybridTranscriber.detenerSesion()
//        sessionManager.stopSession()
//        liberarAudioFocusSR()
        detenerTTS()
//        if (::audioEngine.isInitializetry:
//    rescate_result = await check_rescates(texto_lower, self.texto_acumulado, metadata_ws)
//except Exception as e:
//    self._log(f"❌ Error en rescates: {e}")
//    rescate_result = Noned) audioEngine.start()
//        porcupineController?.reanudarPorcupine()
        setState(JarvisState.IDLE)
        mainHandler.removeCallbacks(timeoutRunnable)
        Log.d(TAG, " Sesión detenida completamente")
    }

    private fun terminarSesion() {
        ui.showText("Hasta luego")
        hablar("Hasta luego.") {
            scope.launch {
                delay(500)
                withContext(Dispatchers.Main) {
                    stopListeningCompletamente()
                }
            }
        }
    }

    fun toggleMic() {
        val granted =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ui.showText("Permiso de micrófono denegado"); return
        }
        if (sesionActiva) stopListeningCompletamente() else startInteraction()
    }

    // ────────────────────────────────────────────────────────────────────────
    // CONFIRMACIONES (lógica conservada, adaptada al nuevo flujo)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Registra los receivers de broadcast.
     * La lógica de confirmación se conserva igual que antes.
     */
    private fun registrarReceivers() {
        confirmationDoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "JARVIS.CONFIRMATION_DONE") {
                    esperandoConfirmacion = false
                    isProcessing = false
                    Log.d(TAG, "Confirmación completada desde UI")
                }
            }
        }
        context.registerReceiver(confirmationDoneReceiver, IntentFilter("JARVIS.CONFIRMATION_DONE"), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun registrarReceptorWakeWord() {
        wakeWordReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "JARVIS.WAKE_WORD") activarDesdeWakeWord()
            }
        }
        context.registerReceiver(
            wakeWordReceiver,
            IntentFilter("JARVIS.WAKE_WORD"),
            Context.RECEIVER_NOT_EXPORTED
        )
    }
    private fun registrarReceptorSpotify() {
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == "JARVIS.HIDE_SPOTIFY_PLAYER") {
                        ocultarReproductor()
                    }
                }
            },
            IntentFilter("JARVIS.HIDE_SPOTIFY_PLAYER"),
            Context.RECEIVER_NOT_EXPORTED
        )
    }
    private fun registrarReceptorOrbe() {
        orbHideReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "JARVIS.MESSAGE_READY_TO_SEND" -> {
                        pendingMessagePackage = intent.getStringExtra("package") ?: ""
                        orbOcultoPorMensaje = true
                        mainHandler.post { ui.setOrbVisibility(false) }
                    }

                    "JARVIS.MESSAGE_SENT", "JARVIS.MESSAGE_CANCELLED" -> {
                        orbOcultoPorMensaje = false
                        pendingMessagePackage = ""
                        mainHandler.post { ui.setOrbVisibility(true) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("JARVIS.MESSAGE_READY_TO_SEND")
            addAction("JARVIS.MESSAGE_SENT")
            addAction("JARVIS.MESSAGE_CANCELLED")
        }
        context.registerReceiver(orbHideReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun registrarReceptorConfirmacion() {
        confirmacionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != "JARVIS.PEDIR_CONFIRMACION") return
                if (esperandoConfirmacion) return

                val pregunta = intent.getStringExtra("pregunta") ?: "¿Enviar el mensaje?"
                val pkg = intent.getStringExtra("package") ?: ""
                Log.d(TAG, "📡 BROADCAST RECIBIDO: PEDIR_CONFIRMACION - pregunta='$pregunta'")
                if (pkg.isNotEmpty()) pendingMessagePackage = pkg
                esperandoConfirmacion = true
                isProcessing = true

                mainHandler.postDelayed({
//                    hybridTranscriber.detenerSesion()

                    // Otro delay para que el SR libere el micrófono completamente
                    mainHandler.postDelayed({
                        hablar(pregunta) {
                            ttsTerminoTimestamp = System.currentTimeMillis()
                            isProcessing = false
                            esperandoConfirmacion = true

                            mainHandler.postDelayed({
                                if (sesionActiva) {
                                    Log.d(TAG, " Escuchando respuesta de confirmación...")
                                    iniciarSRContinuo()
                                }
                            }, 600L)
                        }
                    }, 400L)  // esperar que SR libere el micrófono
                }, 200L)  // esperar que SR termine de iniciar
            }
        }
        context.registerReceiver(
            confirmacionReceiver,
            IntentFilter("JARVIS.PEDIR_CONFIRMACION"),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Procesa la respuesta del usuario a una confirmación.
     * Llamado desde transcribirYProcesar() cuando esperandoConfirmacion == true.
     */
    private val timeoutRunnable: Runnable = object : Runnable {
        override fun run() {
            if (sesionActiva && !isProcessing && voiceEngine.isSrSessionActive()) {
                if (esperandoConfirmacion) {
                    Log.d(TAG, "Timeout ignorado — esperando confirmación")
                    mainHandler.postDelayed(this, TIMEOUT_ESCUCHA_MS)
                    return
                }
                Log.d(TAG, "Timeout 5s — deteniendo escucha")
                if (modoConversacionActivo || esperandoPostTTS) {
                    modoConversacionActivo = false
                    esperandoPostTTS = false
                    ui.hideOverlayFromTimeout()
                    stopListeningCompletamente()
                } else {
                    ui.hideOverlayFromTimeout()
                    stopListeningCompletamente()
                }
            }
        }
    }

    fun pausarEscuchaWakeWord() {
        // Si necesitas pausar el ContinuousVoiceEngine temporalmente
        // voiceEngine.pause() - si tienes ese método
        Log.d(TAG, "Escucha wake word pausada")
    }

    fun reanudarEscuchaWakeWord() {
        Log.d(TAG, "Escucha wake word reanudada")
    }
    private fun procesarRespuestaConfirmacion(texto: String) {
        val t = texto.lowercase().trim()
        val tiempoDesdeFinTTS = System.currentTimeMillis() - ttsTerminoTimestamp

        if (tiempoDesdeFinTTS < ECO_WINDOW_MS) {
            Log.d(TAG, "Eco ignorado (${tiempoDesdeFinTTS}ms)")
            return
        }

        // 🚀 LÓGICA DE CONFIRMACIÓN WHATSAPP
        if (uiState?.showWhatsappPreview == true) {
            val afirmativos = listOf("sí", "si", "claro", "dale", "ok", "enviar", "manda", "hazlo")
            val negativos = listOf("no", "cancela", "olvídalo", "quita", "mejor no")

            if (afirmativos.any { t.contains(it) }) {
                val contacto: String = uiState.pendingWhatsappContact
                val mensaje: String = uiState.pendingWhatsappMessage
                esperandoConfirmacion = false

                hablar("Listo, abriendo WhatsApp.") {
                    ActionExecutor.sendWhatsAppMessage(context, contacto, mensaje)
                    mainHandler.post {
                        uiState.showWhatsappPreview = false
                        uiState.showPanel = false
                    }
                    isProcessing = false
                    if (!sesionActiva) {
                        sesionActiva = true
                        mainHandler.postDelayed({ iniciarSRContinuo() }, 1500L)
                    } else {
                        iniciarSRContinuo()
                    }
                }
                return

            } else if (negativos.any { t.contains(it) }) {
                esperandoConfirmacion = false
                mainHandler.post {
                    uiState.showWhatsappPreview = false
                    uiState.showPanel = false
                }
                hablar("Cancelado.") {
                    isProcessing = false
                    esperandoConfirmacion = false
                    if (!sesionActiva) {
                        sesionActiva = true
                        mainHandler.postDelayed({ iniciarSRContinuo() }, 800L)
                    } else {
                        iniciarSRContinuo()
                    }
                }
                return
            }
        }


        // Resto del código original de confirmación...
        esperandoConfirmacion = false
        hacerVibrar(50)

        // Palabras para afirmar (sí)
        val afirmativos = listOf(
            "sí", "si", "claro", "dale", "ok", "okay", "okey", "envía",
            "envia", "manda", "confirmo", "adelante", "yes", "va", "bueno",
            "hazlo", "procede", "venga", "simón", "sip", "salea"
        )

        // Palabras para negar (no)
        val negativos = listOf(
            "no", "cancela", "cancel", "para", "detente", "no mandes",
            "mejor no", "no quiero", "olvídalo", "quita", "ni pensarlo"
        )

        val esAfirmativo = afirmativos.any { t == it || t.startsWith("$it ") }
        val esNegativo = negativos.any { t == it || t.startsWith("$it ") }

        when {
            esAfirmativo -> {
                uiState?.apply {
                    showWhatsappPreview = false
                    showPanel = false
                }

                // Enviar el mensaje
                ActionExecutor.sendWhatsAppMessage(
                    context,
                    ActionExecutor.pendingWhatsappContact,
                    ActionExecutor.pendingWhatsappMessage
                )

                hacerVibrar(100)
                hablar("Enviando.") {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                }
            }
            esNegativo -> {
                val cb = ActionExecutor.onConfirmacionPendiente
                ActionExecutor.onConfirmacionPendiente = null
                cb?.invoke(false)
                hacerVibrar(50)
                mainHandler.postDelayed({ hacerVibrar(50) }, 150L)
                hablar("Cancelado.") {
                    isProcessing = false
//                    if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                }
            }
            else -> {
                hacerVibrar(30)
                hablar("No entendí. ¿Envío el mensaje? Responde sí o no.") {
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    esperandoConfirmacion = true
                    isProcessing = false
                    if (sesionActiva) {
                        mainHandler.postDelayed({
//                            sessionManager.onAssistantFinishedSpeaking()
                        }, 500L)
                    }
                }
            }
        }
    }
    fun resetConfirmacion() {
        esperandoConfirmacion = false
        isProcessing = false
    }
    // ────────────────────────────────────────────────────────────────────────
    // MODO VISUAL (sin cambios en lógica)
    // ────────────────────────────────────────────────────────────────────────

    private fun activarModoVisual() {
        setState(JarvisState.THINKING)
        isProcessing = true
        mainHandler.post { ui.setOrbVisibility(false) }

        scope.launch {
            Log.d(TAG, " [DEBUG VISUAL] Solicitando captura de pantalla...")

            // 🔥 Usar callback para asegurar que obtenemos el snapshot
            var elementosCapturados: List<ScreenElement> = emptyList()

            withContext(Dispatchers.Main) {
                MyAccessibilityService.instance?.captureCurrentScreenNow { snapshot ->
                    elementosCapturados = snapshot?.elements ?: emptyList()
                    Log.d(TAG, " Captura callback: ${elementosCapturados.size} elementos")
                }
            }

            // Esperar a que la captura termine (máximo 3 segundos)
            var intentos = 0
            while (elementosCapturados.isEmpty() && intentos < 10) {
                delay(300)
                intentos++
                Log.d(TAG, "  Esperando captura... intento $intentos/10")
            }

            withContext(Dispatchers.Main) {
                if (elementosCapturados.isEmpty()) {
                    Log.e(TAG, "❌ No se pudo capturar la pantalla después de ${intentos} intentos")
                    ui.setOrbVisibility(true)
                    hablar("No pude ver la pantalla. ¿Puedes abrir la app primero?") {
                        isProcessing = false
//                        if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                    }
                } else {
                    // Filtrar solo elementos interactivos con tamaño válido
                    val interactivos = elementosCapturados.filter {
                        (it.isClickable || it.isEditable || it.isScrollable) &&
                                it.bounds.width() > 10 && it.bounds.height() > 10
                    }

                    Log.d(TAG, "📱 Elementos totales: ${elementosCapturados.size}")
                    Log.d(TAG, "🖱️ Elementos interactivos: ${interactivos.size}")

                    if (interactivos.isEmpty()) {
                        ui.setOrbVisibility(true)
                        hablar("No veo nada que pueda tocar en esta pantalla.") {
                            isProcessing = false
//                            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                        }
                    } else {
                        mostrarOverlayNumeros(interactivos)
                    }
                }
            }
        }
    }
    private fun mostrarOverlayNumeros(elementos: List<com.example.myapplication.model.ScreenElement>) {
        if (numberedOverlay == null) {
            numberedOverlay = NumberedElementsOverlay(context)
        }
        modoVisualActivo = true
        numberedOverlay!!.mostrar(elementos)

        hablar("${elementos.count { it.isClickable }} elementos. Di un número.") {
            isProcessing = false
//            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
        }
    }
    private fun desactivarModoVisual() {
        modoVisualActivo = false
        numberedOverlay?.ocultar()
        mainHandler.post { ui.setOrbVisibility(true) }
        hablar("Modo visual desactivado.") {
            isProcessing = false
//            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
        }
    }

    private fun ejecutarClickNumerico(numero: Int) {
        val overlay = numberedOverlay
        if (overlay == null || !overlay.estaVisible()) {
            hablar("El modo visual no está listo.") {
                isProcessing = false
//                if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
            }
            return
        }

        val elemento = overlay.obtenerPorNumero(numero)
        if (elemento == null) {
            hablar("No encuentro el número $numero.") {
                isProcessing = false
//                if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
            }
            return
        }

        Log.d(TAG, "🖱️ CLICK en #$numero: x=${elemento.centerX} y=${elemento.centerY}")

        //  IMPORTANTE: Ejecuta el tap DIRECTAMENTE sin pasar por acciones
        // para evitar delays
        scope.launch {
            // Descarta acciones en espera
            isProcessing = false

            // Ejecuta el TAP
            val accion = ActionDto(tipo = "tap", params = mapOf("x" to elemento.centerX, "y" to elemento.centerY))
            ejecutarAccionesTecnicas(listOf(accion), "toca el $numero", "click_numerico")

            // Espera a que la pantalla cambie
            delay(1200)

            // Fuerza captura de pantalla
            MyAccessibilityService.instance?.captureCurrentScreenNow()

            delay(600)

            // Actualiza overlay con nuevos elementos
            withContext(Dispatchers.Main) {
                actualizarOverlayVisual()
            }
        }
    }
    fun cancelarAccionActual() {
        if (!sesionActiva) {
            Log.d(TAG, "No hay sesión activa para cancelar")
            return
        }

        Log.d(TAG, " CANCELANDO acción actual...")

        detenerTTS()

        isProcessing = false
        esperandoConfirmacion = false

        ActionExecutor.onConfirmacionPendiente = null


        if (modoVisualActivo) {
            desactivarModoVisual()
        }

        numberedOverlay?.ocultar()


        // 7. Notificar al usuario
        val respuestasCancelacion = listOf(
            "Acción cancelada.",
            "Cancelado.",
            "Entendido, cancelado.",
            "Ok, cancelado."
        )
        val mensaje = respuestasCancelacion.random()

        // Limpiar UI
        ui.showText(mensaje)
        ui.updateORB(0f)

        // 8. Ir al inicio (home) después de cancelar
        hablar(mensaje) {
            // Ir al home
            val intent = Intent(ACTION_EXECUTE).apply {
                putExtra("actions_json", Gson().toJson(listOf(
                    ActionDto(tipo = "global_action", params = mapOf("action" to "home"))
                )))
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            // Terminar la sesión completamente y reiniciar Porcupine
            stopListeningCompletamente()
        }
    }
    fun esComandoCancelacion(texto: String): Boolean {
        val t = texto.lowercase().trim().removeSuffix(".")

        // Palabras de cancelación directa
        val palabrasCancelacion = setOf(
            "cancelar", "cancela", "cancelado", "cancele",
            "detener", "deten", "para", "parar", "alto", "alta",
            "olvídalo", "olvidalo", "deja", "dejalo", "quita",
            "no quiero", "ya no", "mejor no", "me arrepenti"
        )

        if (t in palabrasCancelacion) return true
        if (palabrasCancelacion.any { t.startsWith(it) }) return true

        // Frases completas
        val frasesCancelacion = listOf(
            "ya no quiero", "no lo hagas", "detente", "para ya",
            "cancelar acción", "cancela eso", "deja eso"
        )

        return frasesCancelacion.any { t.contains(it) }
    }

    private fun actualizarOverlayVisual() {
        val elementos = ScreenMemory.lastSnapshot?.elements ?: emptyList()
        if (elementos.isNotEmpty()) numberedOverlay?.mostrar(elementos)
        else desactivarModoVisual()
    }

    fun interceptarComandoVisual(texto: String): Boolean {
        val t = texto.lowercase().trim()
        val salidas = listOf("salir de modo visual", "salir modo visual", "desactivar modo visual",
            "cerrar modo visual", "quitar modo visual", "modo normal", "ocultar números",
            "ocultar numeros", "quita los números", "quita numeros")
        if (salidas.any { t.contains(it) }) { desactivarModoVisual(); return true }

        val activadores = listOf("modo visual", "qué puedo tocar", "que puedo tocar",
            "muestra los números", "muestra los numeros", "qué hay en pantalla",
            "que hay en pantalla", "numera la pantalla", "muéstrame opciones",
            "muestrame opciones", "modo número", "modo numero")
        if (activadores.any { t.contains(it) }) { activarModoVisual(); return true }

        if (modoVisualActivo) {
            val numero = extraerNumeroDeTexto(t)
            if (numero != null && numero >= 1) { ejecutarClickNumerico(numero); return true }
        }
        return false
    }

    private fun extraerNumeroDeTexto(texto: String): Int? {
        Regex("""(\d+)""").find(texto)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val palabras = mapOf(
            "uno" to 1, "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
            "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
            "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciséis" to 16, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
            "diecinueve" to 19, "veinte" to 20
        )
        for ((palabra, numero) in palabras) { if (texto.contains(palabra)) return numero }
        return null
    }

    // ────────────────────────────────────────────────────────────────────────
    // UTILIDADES
    // ────────────────────────────────────────────────────────────────────────

    private fun esFraseDeSalida(texto: String): Boolean {
        val t = texto.lowercase().trim().removeSuffix(".")
        return listOf("salir", "adiós", "adios", "hasta luego", "chao", "bye").any { it == t }
    }

    private fun calcularRMS(frame: ShortArray): Float {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return (Math.sqrt(sum / frame.size).toFloat() / 400f).coerceIn(0f, 12f)
    }

    private fun ejecutarAccionesTecnicas(actions: List<ActionDto>, textoOriginal: String, intencion: String) {
        Log.d("ACCESS_FLOW", " Enviando ${actions.size} acciones por broadcast...")
        Log.d("ACCESS_FLOW", "   Acciones: ${actions.map { it.tipo }}")

        val json = Gson().toJson(actions)
        Log.d("ACCESS_FLOW", "   JSON: $json")

        val intent = Intent(ACTION_EXECUTE).apply {
            putExtra("actions_json", json)
            putExtra("texto_original", textoOriginal)
            putExtra("intencion_original", intencion)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d("ACCESS_FLOW", " Broadcast enviado a MyAccessibilityService")
    }

    private fun obtenerSaludoGemma(callback: (String?) -> Unit) {
        scope.launch {
            try {
                val response = actionApiService.regards()
                mainHandler.post { callback(response.saludo) }
            } catch (e: Exception) {
                mainHandler.post { callback(null) }
            }
        }
    }

    private fun setState(s: JarvisState) {
        ui.renderState(s)

        mainHandler.post {
            when (s) {
                JarvisState.LISTENING -> {
                    uiState?.apply {
                        barColors = BarColorMode.LISTENING
                        showPanel = false
                        labelColor = android.graphics.Color.parseColor("#4DEEE9")
                        showPause = false
                        showWave = false
                        labelText = "ESCUCHANDO"
                    }
                }

                JarvisState.THINKING -> {
                    uiState?.apply {
                        barColors = BarColorMode.THINKING
                        labelText = ""
                        labelColor = android.graphics.Color.parseColor("#7BD7F8")
                    }
                }

                JarvisState.SPEAKING -> {
                    uiState?.apply {
                        barColors = BarColorMode.SPEAKING
                        labelText = ""
                        labelColor = android.graphics.Color.parseColor("#1DE0A0")
                        showPause = true
                        showWave = true
                    }
                }

                JarvisState.IDLE -> {
                    // RESETEO COMPLETO
                    uiState?.apply {
                        barColors = BarColorMode.IDLE
                        showPanel = false
                        labelText = "NEXUS"
                        labelColor = android.graphics.Color.WHITE
                        showPause = false
                        showWave = false
                        showWhatsappPreview = false
                        serverProcessing = false
                        transcription = ""
                        typewriterText = "Como puedo Ayudarte"
                        fullHtmlText = ""
                        userTranscription = ""
                        imageUrls = emptyList()
                        sourceUrls = emptyList()
                        processingSteps = emptyList()
                        pendingWhatsappContact = ""
                        pendingWhatsappMessage = ""
                    }
                    ui.showText("")
                }
            }
        }
    }

    fun procesarComandoExterno(comando: String) {
        ui.showText(comando)
        if (interceptarComandoVisual(comando)) return
        enviarComandoAlServidor(comando)
    }

    /**
     * Para uso de audio externo (ej: desde JarvisOverlayService).
     * Ahora el audio va al AudioEngine directamente, no necesitas esto normalmente.
     */
    fun processAudioFrame(frame: ShortArray) {
        // No-op: el AudioEngine maneja sus propios frames ahora
    }

    fun hablarDesdeActivity(texto: String, alTerminar: (() -> Unit)? = null) = hablar(texto, alTerminar)

    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────

    fun destroy() {
        stopSpotifyPlayerUpdates()
//        stopListeningCompletamente()
        voiceEngine.stop()
        if (::tts.isInitialized && ttsListo) tts.shutdown()
//        if (::audioEngine.isInitialized) audioEngine.stop()
//        if (::hybridTranscriber.isInitialized) hybridTranscriber.destroy()

        numberedOverlay?.ocultar()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(confirmationDoneReceiver) }
        runCatching { context.unregisterReceiver(confirmacionReceiver) }
        runCatching { context.unregisterReceiver(orbHideReceiver) }
        runCatching { context.unregisterReceiver(wakeWordReceiver) }

        Log.d(TAG, " Controlador destruido")
    }
}