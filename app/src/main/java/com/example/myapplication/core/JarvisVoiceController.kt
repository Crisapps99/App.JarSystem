package com.example.myapplication.core

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.example.myapplication.ui.JarvisOverlayUiState
import com.example.myapplication.ui.StepStatus
import com.example.myapplication.ui.ProcessingStep
import kotlinx.coroutines.withTimeoutOrNull
import com.example.myapplication.grpc.NexusWebSocketClient
import com.example.myapplication.ui.BarColorMode
import com.example.myapplication.ui.applyJarvisState
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
private val TTS_MODE = TtsMode.ANDROID

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
    private var isRecognizingMusic = false

    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    fun init() {
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



    private fun procesarTexto(texto: String) {
        mainHandler.post {
            uiState?.apply {
                transcription = ""
                typewriterText = ""
                fullHtmlText = ""
                imageUrls = emptyList()
                sourceUrls = emptyList()
                showMusicResult = false
                musicTitle = ""
                musicArtist = ""
                musicCoverUrl = ""
                musicExternalUrls = emptyList()
                processingSteps = emptyList()
            }
        }
        val ahora = System.currentTimeMillis()
        val textoLimpio = texto.trim()

        if (estaHablando) {
            Log.d(TAG, " Ignorando comando porque el asistente está hablando: '$texto'")
            detenerTTS()
            estaHablando = false
        }

        val tiempoDesdeTTS = ahora - ttsTerminoTimestamp
        if (tiempoDesdeTTS < 2000L) {
            if (textoLimpio.length < 15) {
                Log.d(TAG, "⏭️ Posible eco post-TTS ignorado: '$texto'")
                return
            }
        }

        if (textoLimpio == ultimoComandoEnviado && (ahora - ultimoTimestampComando) < 3000L) {
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
            Thread.sleep(100)
        }

        // ============================================================
        // ⭐ PRIMERO: COMANDOS DE RECONOCIMIENTO DE MÚSICA (ACRCloud)
        // ============================================================
        val lowerText = textoLimpio.lowercase()
        val comandosMusica = listOf(
            "reconoce esta canción",
            "qué canción es esta",
            "qué música es",
            "identifica esta canción",
            "qué está sonando",
            "qué tema es",
            "qué canción suena",
            "reconocé esta canción",
            "descubre esta canción",
            "cuál es la canción",
            "shazam",
            "qué canción",
            "identificar canción"
        )

        if (comandosMusica.any { lowerText.contains(it) }) {
            Log.d(TAG, "🎵 Comando de reconocimiento de música detectado")
            startMusicRecognition()
            return  // ✅ IMPORTANTE: salir aquí para que no siga procesando
        }

        // ============================================================
        // INTERCEPTORES LOCALES (YouTube, imágenes, etc.)
        // ============================================================

        when {
            textoLimpio.contains("adelanta") || textoLimpio.contains("avanza") -> {
                val segundos = extraerSegundos(textoLimpio) ?: 10
                ActionExecutor.controlYoutube(context, "adelantar", segundos)
                hablar("Adelantando $segundos segundos")
                return
            }
            textoLimpio.contains("retrocede") || textoLimpio.contains("atrasa") -> {
                val segundos = extraerSegundos(textoLimpio) ?: 10
                ActionExecutor.controlYoutube(context, "retroceder", segundos)
                hablar("Retrocediendo $segundos segundos")
                return
            }
            textoLimpio.contains("pantalla completa") -> {
                if (textoLimpio.contains("salir") || textoLimpio.contains("quitar")) {
                    ActionExecutor.controlYoutube(context, "salir_pantalla_completa")
                    hablar("Saliendo de pantalla completa")
                } else {
                    ActionExecutor.controlYoutube(context, "pantalla_completa")
                    hablar("Poniendo pantalla completa")
                }
                return
            }
        }

        if (modoConversacionActivo || esperandoPostTTS) {
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
            Log.d(TAG, "Comando recibido en modo conversación, timeout reiniciado")
        }

        // Búsqueda de imágenes
        if (textoLimpio.contains("busca imágenes de") || textoLimpio.contains("muéstrame imágenes de") ||
            textoLimpio.contains("buscar imágenes de") || textoLimpio.contains("busca fotos de")) {
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

        // Copiar texto
        if (textoLimpio.contains("copiar texto") || textoLimpio.contains("copia el texto") ||
            textoLimpio.contains("copia texto")) {
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

        // Ver más fuentes
        if (textoLimpio.contains("ver más") || textoLimpio.contains("ver mas") ||
            textoLimpio.contains("abrir enlaces")) {
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

        // YouTube
        if (texto.lowercase().contains("youtube") &&
            (texto.lowercase().contains("pon") || texto.lowercase().contains("reproduce"))) {
            Log.d(TAG, "🎬 Comando de YouTube detectado: '$texto'")
            ejecutarMusica(texto)
            return
        }

        // Cerrar búsqueda
        if (texto.lowercase().contains("cerrar búsqueda") ||
            texto.lowercase().contains("ocultar resultados") ||
            texto.lowercase().contains("volver atrás")) {
            hablar("Resultados ocultados") {
                isProcessing = false
                if (sesionActiva) iniciarSRContinuo()
            }
            return
        }
        // Cancelación
        if (esComandoCancelacion(texto)) {
            Log.d(TAG, "🛑 Comando de cancelación detectado: '$texto'")
            cancelarAccionActual()
            return
        }
        processCommand(textoLimpio)
        // ============================================================
        // COMANDOS POR INTENCIÓN (CommandAnalyzer)
        // ============================================================
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
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    isProcessing = false
                }
            }
            CommandAnalyzer.Intent.OPEN_MAPS -> {
                hablar("Abriendo Google Maps") {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
            CommandAnalyzer.Intent.UNKNOWN -> {
                if (interceptarComandoVisual(texto)) {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                    return
                }
                if (esFraseDeSalida(texto)) {
                    terminarSesion()
                    return
                }
                enviarComandoAlServidor(texto)
            }
            else -> {
                enviarComandoAlServidor(texto)
            }
        }
    }
    private fun processCommand(text: String) {
        val lower = text.lowercase().trim()
        when {
            // Detectar comando de música
            lower.contains("reconoce esta canción") ||
                    lower.contains("qué canción es esta") ||
                    lower.contains("qué música es") ||
                    lower.contains("identifica esta canción") -> {
                startMusicRecognition()
            }

            // ... otros comandos existentes
        }
    }

    private fun startMusicRecognition() {
        if (isRecognizingMusic) return
        isRecognizingMusic = true

        ui.showText("🎵 Reconociendo música... acercando el dispositivo")
        ui.renderState(JarvisState.LISTENING)
        ui.setOrbVisibility(true)

        voiceEngine.iniciarReconocimientoMusica(
            durationSegundos = 10,  // ✅ Solo 10 segundos
            onResult = { musicResult ->
                isRecognizingMusic = false
                if (musicResult != null) {
                    showMusicResult(musicResult)

                } else {
                    ui.showText("No pude identificar la canción")
                    ui.renderState(JarvisState.IDLE)
                }

            }
        )

        scope.launch {
            delay(12_000) // 12 segundos de timeout
            if (isRecognizingMusic) {
                isRecognizingMusic = false
                voiceEngine.detenerReconocimientoMusica()
                ui.showText("⏰ Tiempo agotado")
                ui.renderState(JarvisState.IDLE)
            }
        }
    }
    private fun onMusicIdentified(music: MusicRecognizerRest.MusicResult) {
        // Usamos el 'uiState' que ya tienes en el constructor de la clase
        uiState?.let { state ->
            state.musicTitle = music.title
            state.musicArtist = music.artist
            // Asegúrate de que tu modelo de datos tenga 'coverUrl'
            state.musicCoverUrl = music.coverUrl ?: ""
            state.musicExternalUrls = music.externalUrls

            // Esto activa la tarjeta en tu UI
            state.showMusicResult = true
            state.showPanel = true
        }
    }
    private fun showMusicResult(music: MusicRecognizerRest.MusicResult) {
        Log.d(TAG, "🎵 Mostrando resultado: ${music.title} - ${music.artist}")
        Log.d(TAG, "   Enlaces: ${music.externalUrls}")

        uiState?.let { state ->
            // ✅ Asignar TODOS los campos
            state.musicTitle = music.title
            state.musicArtist = music.artist
            state.musicAlbum = music.album
            state.musicGenre = music.genre
            state.musicDurationMs = music.durationMs
            state.musicCoverUrl = music.coverUrl
            state.musicExternalUrls = music.externalUrls

            // ✅ Mostrar en la UI
            state.showMusicResult = true
            state.showPanel = true
        }

        // ✅ Hablar el resultado
        hablar("La canción es ${music.title} de ${music.artist}")
    }
    private fun extraerAppsParaSplitScreen(texto: String): List<String> {
        // Limpiar la frase
        val limpio = texto.lowercase()
            .replace(Regex("(en la misma pantalla|pantalla dividida|multiventana|doble pantalla|abre|abrir|por favor)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Si está vacío, intentar con el texto original
        if (limpio.isEmpty()) {
            // Intentar extraer nombres después de "abre" y antes de "en la misma"
            val regex = Regex("abre\\s+(.+?)\\s+(?:y|e)\\s+(.+?)(?:\\s+en la misma pantalla|$)", RegexOption.IGNORE_CASE)
            val match = regex.find(texto)
            if (match != null) {
                val app1 = match.groupValues[1].trim()
                val app2 = match.groupValues[2].trim()
                val pkg1 = ActionExecutor.getPackageNameFromAppName(app1, context)
                val pkg2 = ActionExecutor.getPackageNameFromAppName(app2, context)
                val result = mutableListOf<String>()
                if (pkg1 != null) result.add(pkg1)
                if (pkg2 != null) result.add(pkg2)
                return result
            }
            return emptyList()
        }

        // Separar por "y", "e", o coma
        val partes = limpio.split(Regex(",\\s*| y | e | & "))

        val paquetes = mutableListOf<String>()
        for (parte in partes) {
            val nombre = parte.trim()
            if (nombre.isNotEmpty()) {
                val pkg = ActionExecutor.getPackageNameFromAppName(nombre, context)
                if (pkg != null) {
                    paquetes.add(pkg)
                    Log.d(TAG, "✅ App detectada: '$nombre' → $pkg")
                } else {
                    Log.w(TAG, "⚠️ App no encontrada: '$nombre'")
                }
            }
        }

        return paquetes.take(2)
    }
    // ContinuousVoiceEngine.kt - Añadir al final de la clase


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

        if (busqueda.isEmpty()) busqueda = texto

        if (esYoutube) {
            hablar("Buscando $busqueda en YouTube") {
                ActionExecutor.playVideo(context, busqueda)
                isProcessing = false
            }
        } else {
            // 🟢 Abrir Spotify sin setPackage
            val queryEncoded = Uri.encode(busqueda)
            val uri = Uri.parse("https://open.spotify.com/search/$queryEncoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                hablar("Abriendo Spotify para $busqueda")
            } catch (e: Exception) {
                // Fallback a navegador
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$busqueda"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
                hablar("No encontré Spotify, abro búsqueda en Google")
            }
            isProcessing = false
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
    private suspend fun obtenerDireccionGrpc(): Pair<String, Int>? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                // 🔑 REEMPLAZA ESTO CON TU URL REAL DE MODAL
                val url = "wss://mausand2499--jarvoice-nexus-api-nexusserver-serve-dev.modal.run/ws/jarvis"
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
                                    "open_uber" -> {
                                        val params = accion.optJSONObject("params") ?: continue
                                        val pickupLat = params.optDouble("pickup_lat", 0.0)
                                        val pickupLng = params.optDouble("pickup_lng", 0.0)
                                        val dropoffLat = params.optDouble("dropoff_lat", 0.0)
                                        val dropoffLng = params.optDouble("dropoff_lng", 0.0)
                                        val dropoffName = params.optString("dropoff_name", "Destino")
                                        val deeplink = params.optString("deeplink", "")

                                        // Llamamos a ActionExecutor para abrir Uber
                                        ActionExecutor.openUber(
                                            context = context,
                                            pickupLat = pickupLat,
                                            pickupLng = pickupLng,
                                            dropoffLat = dropoffLat,
                                            dropoffLng = dropoffLng,
                                            dropoffName = dropoffName,
                                            deeplink = deeplink
                                        )
                                    }
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
        estaHablando = true
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
                withContext(Dispatchers.Main) {
                    estaHablando = false
                    hablarAndroid(texto, alTerminar) }
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
                estaHablando = false
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
            estaHablando = false
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
                    estaHablando = false
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
            estaHablando = false
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
                        labelText = "NEXUS"
                        labelColor = android.graphics.Color.WHITE
                        showPause = false
                        showWave = false
                        showWhatsappPreview = false
                        serverProcessing = false
                        userTranscription = ""
                        processingSteps = emptyList()
                        pendingWhatsappContact = ""
                        pendingWhatsappMessage = ""
                    }
                }
            }
        }
    }
    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────
    fun resetCompleto() {
        Log.d(TAG, "🔄 Reset completo desde controlador")

        // 1. Detener todo
        stopListeningCompletamente()
        detenerTTS()
        detenerAudio()

        // 2. Cancelar envíos pendientes
        cancelarEnvioActual()
        esperandoConfirmacion = false
        isRecognizingMusic = false

        // 3. Resetear UI
        mainHandler.post {
            uiState?.apply {
                applyJarvisState(JarvisState.IDLE)

                showWhatsappPreview = false

                showPanel = false
                userTranscription = ""
                processingSteps = emptyList()
                imageUrls = emptyList()
                sourceUrls = emptyList()
            }
            ui.renderState(JarvisState.IDLE)
            ui.setOrbVisibility(true)
        }

        // 4. Reanudar wake word
        porcupineController?.reanudarPorcupine()

        Log.d(TAG, "✅ Reset completo - Listo para wake word")
    }
    fun destroy() {

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
