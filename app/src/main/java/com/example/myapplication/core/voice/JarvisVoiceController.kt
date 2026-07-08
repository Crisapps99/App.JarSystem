package com.example.myapplication.core.voice

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
import com.example.myapplication.core.audio.AudioManager
import com.example.myapplication.core.audio.ContinuousVoiceEngine
import com.example.myapplication.core.audio.MusicRecognizerRest
import com.example.myapplication.core.integrations.LocationHelper
import com.example.myapplication.core.memory.ScreenMemory
import com.example.myapplication.core.overlay.NumberedElementsOverlay
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
import com.example.myapplication.core.integrations.SearchResult
import com.example.myapplication.data.ChatRepository
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
    fun showSearchResult(textoCompleto: String, fuentes: List<String>, imagenes: List<String>, preguntas: List<String>, html: String = "")
    fun updateProcessingSteps(steps: List<ProcessingStep>)
    fun updateUserTranscription(text: String)
}
interface PorcupineController {
    fun pausarPorcupine()
    fun reanudarPorcupine()
    fun esPorcupinePausado(): Boolean
}
object ElevenLabsConfig {
    val API_KEY                = com.example.myapplication.BuildConfig.ELEVENLABS_API_KEY
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
    private val uiState: JarvisOverlayUiState? = null,
    private val chatRepository: ChatRepository? = null

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
            // En JarvisVoiceController.kt - En el bloque onWakeWordDetected
            onWakeWordDetected = {
                Log.d(TAG, "Wakeword detectado por Vosk")
                mainHandler.post {
                    esperandoRespuestaServidor = false
                    estaEnviandoAlServidor = false
                    estaProcesandoComando = false
                    timeoutServidor?.let { mainHandler.removeCallbacks(it) }

                    // 1. Mostrar la barra
                    ui.setOrbVisibility(true)
                    // 2. Limpiar cualquier texto anterior y mostrar "Escuchando..."
                    uiState?.userTranscription = ""
                    uiState?.applyJarvisState(JarvisState.LISTENING)
                    // 3. IMPORTANTE: Mostrar el panel de transcripción (si no está visible)
                    uiState?.showPanel = false  // No queremos el panel grande, solo la barra

                    // 4. Activar sesión de escucha
                    sesionActiva = true
                    isProcessing = false
                    setState(JarvisState.LISTENING)

                    // 5. Iniciar reconocimiento de voz continuo
                    iniciarSRContinuo()
                }
            },
            onFinalResult = { texto ->
                Log.d(TAG, "resultado final:  $texto")
                isProcessing = false
                if (esperandoRespuestaServidor) {
                    Log.d(TAG, "Ignorando voz mientras se espera respuesta del servidor")
                    if (sesionActiva) iniciarSRContinuo()
                    return@ContinuousVoiceEngine
                }
                //  Filtro de basura — antes de mostrar y antes de procesar
                if (esResultadoBasura(texto)) {
                    Log.d(TAG, " Resultado descartado por ser basura/muletilla: '$texto'")
                    // Seguimos escuchando en vez de procesar como comando
                    if (sesionActiva && !isProcessing && voiceEngine.isSrSessionActive().not()) {
                        iniciarSRContinuo()
                    }
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
                // Reiniciamos el timeout para esperar el resultado final (5 segundos)
                mainHandler.removeCallbacks(timeoutRunnable)
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
//
//    private fun iniciarModoConversacional() {
//        Log.d(TAG, " Iniciando modo conversacional - esperando 5 segundos")
//
//        // Cancelar timeout anterior si existe
//        timeoutConversacional?.let { mainHandler.removeCallbacks(it) }
//
//        // Pausar wake word
//        porcupineController?.pausarPorcupine()
//
//        // Iniciar escucha continua si no está activa
//        if (!voiceEngine.isSrSessionActive()) {
//            voiceEngine.iniciarSesionContinua("es")
//        }
//
//        // Configurar timeout para volver a modo wake word
//        timeoutConversacional = Runnable {
//            Log.d(TAG, " 5 segundos sin actividad - volviendo a modo wake word")
//            finalizarSesionConversacional()
//        }
//        mainHandler.postDelayed(timeoutConversacional!!, TIMEOUT_CONVERSACIONAL_MS)
//
//        // Reiniciar timeout cada vez que el usuario habla
//        // Esto se hará desde onSpeechStarted o onFinalResult
//    }



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

//  PRIMERO: Si estamos en modo visual, procesar TODO localmente
        if (modoVisualActivo) {
            Log.d(TAG, " Modo visual activo - procesando localmente: '$texto'")
            // Intentar interceptar el comando visual
            if (interceptarComandoVisual(texto)) {
                // Ya se procesó, no hacer nada más
                return
            }
            // Si no se procesó, ignorar (no enviar al servidor)
            Log.d(TAG, " Modo visual: ignorando '$texto'")
            return
        }

        // Guardar mensaje del usuario
        if (chatRepository != null) {
            scope.launch {
                chatRepository.addUserMessage(texto)
            }
        }
        setState(JarvisState.THINKING)

        if (estaHablando) {
            Log.d(TAG, " Ignorando comando porque el asistente está hablando: '$texto'")
            return
        }

        val tiempoDesdeTTS = ahora - ttsTerminoTimestamp
        if (tiempoDesdeTTS < 2000L) {
            if (textoLimpio.length < 15) {
                Log.d(TAG, " Posible eco post-TTS ignorado: '$texto'")
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

        // ═══════════════════════════════════════════════════════════
        // 1. COMANDOS LOCALES (se ejecutan SIN enviar al servidor)
        // ═══════════════════════════════════════════════════════════

        val lowerText = textoLimpio.lowercase()

        // Comando de reconocimiento de música
        val comandosMusica = listOf(
            "reconoce esta canción", "qué canción es esta", "qué música es",
            "identifica esta canción", "qué está sonando", "qué tema es",
            "qué canción suena", "reconocé esta canción", "descubre esta canción",
            "cuál es la canción", "shazam", "qué canción", "identificar canción"
        )
        if (comandosMusica.any { lowerText.contains(it) }) {
            Log.d(TAG, " Comando de reconocimiento de música detectado")
            startMusicRecognition()
            return
        }

        // Comandos de YouTube
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

        // YouTube
        if (texto.lowercase().contains("youtube") &&
            (texto.lowercase().contains("pon") || texto.lowercase().contains("reproduce"))) {
            Log.d(TAG, " Comando de YouTube detectado: '$texto'")
            ejecutarMusica(texto)
            return
        }

        // Cancelación
        if (esComandoCancelacion(texto)) {
            Log.d(TAG, " Comando de cancelación detectado: '$texto'")
            cancelarAccionActual()
            return
        }

        // ═══════════════════════════════════════════════════════════
        // 2. COMANDOS POR INTENCIÓN
        // ═══════════════════════════════════════════════════════════

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
                    Log.d(TAG, " [WHATSAPP] Mostrando preview para $contacto")
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
                // ✅ Si es un saludo, responder localmente
                if (texto.contains("hola", ignoreCase = true) && texto.length < 15) {
                    val respuesta = "¡Hola! ¿Qué necesitas?"
                    hablar(respuesta)
                    chatRepository?.let { repo ->
                        scope.launch {
                            repo.addAssistantMessage(
                                content = respuesta,
                                tag = "conversation",
                                displayText = respuesta
                            )
                        }
                    }
                    return
                }

                // ✅ Si es frase de salida, terminar sesión
                if (esFraseDeSalida(texto)) {
                    terminarSesion()
                    return
                }

                // ✅ Si es comando visual, procesar
                if (interceptarComandoVisual(texto)) {
                    isProcessing = false
                    if (sesionActiva) iniciarSRContinuo()
                    return
                }

                // ✅ ENVIAR AL SERVIDOR
                Log.d(TAG, " Enviando al servidor: '$texto'")
                enviarComandoAlServidor(texto)
            }
            else -> {
                // ✅ Cualquier otra intención se envía al servidor
                Log.d(TAG, " Enviando al servidor (intención: $intencion): '$texto'")
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

        ui.showText(" Reconociendo música... acercando el dispositivo")
        ui.renderState(JarvisState.LISTENING)
        ui.setOrbVisibility(true)

        voiceEngine.iniciarReconocimientoMusica(
            durationSegundos = 10,  //  Solo 10 segundos
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
                ui.showText(" Tiempo agotado")
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
        Log.d(TAG, " Mostrando resultado: ${music.title} - ${music.artist}")
        Log.d(TAG, "   Enlaces: ${music.externalUrls}")

        uiState?.let { state ->
            //  Asignar TODOS los campos
            state.musicTitle = music.title
            state.musicArtist = music.artist
            state.musicAlbum = music.album
            state.musicGenre = music.genre
            state.musicDurationMs = music.durationMs
            state.musicCoverUrl = music.coverUrl
            state.musicExternalUrls = music.externalUrls

            //  Mostrar en la UI
            state.showMusicResult = true
            state.showPanel = true
        }

        //  Hablar el resultado
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
                    Log.d(TAG, " App detectada: '$nombre' → $pkg")
                } else {
                    Log.w(TAG, " App no encontrada: '$nombre'")
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
        ui.showText(" Buscando imágenes de $query...")
        setState(JarvisState.THINKING)

        scope.launch {
            try {
                //  Hacemos la petición nativa a Tavily usando tus imports de OkHttp
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
                            com.example.myapplication.BuildConfig.TAVILY_API_KEY
                        )
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

                //  Volvemos al hilo principal para renderizar los resultados en el Orbe
                withContext(Dispatchers.Main) {
                    if (urlsImagenes.isNotEmpty()) {
                        ultimoResultadoBusqueda = SearchResult(
                            content = "Imágenes encontradas sobre $query",
                            urls = urlsImagenes
                        )
                        ui.showImages(urlsImagenes)
                        hablar("Aquí tienes las imágenes que encontré sobre $query.") {
                            isProcessing = false
                            finalizarInteraccion()
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
                Log.e(TAG, " Error en búsqueda visual: ${e.message}")
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
            //  Abrir Spotify sin setPackage
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

                //  REEMPLAZA ESTO CON TU URL REAL DE MODAL
                val url = "wss://mausand2499--jarvoice-nexus-api-nexusserver-serve-dev.modal.run/ws/jarvis"
                Log.d(TAG, " Consultando: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val status = json.optString("status", "")

                    Log.d(TAG, " Response: $json")

                    if (status == "ready") {
                        val host = json.optString("host", "")
                        val port = json.optInt("port", 0)

                        if (host.isNotEmpty() && port > 0) {
                            Log.d(TAG, " Endpoint: $host:$port")
                            return@withContext Pair(host, port)
                        }
                    } else if (status == "not_ready") {
                        Log.w(TAG, " gRPC aún no listo, reintentando...")
                        delay(2000)
                        return@withContext obtenerDireccionGrpc()
                    } else {
                        Log.e(TAG, " Estado desconocido: $status")
                    }
                } else {
                    Log.e(TAG, " HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, " Error: ${e.message}")
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
                com.example.myapplication.BuildConfig.NEXUS_WS_HOST
            }

            Log.d(TAG, " Conectando a WebSocket en: $urlCompleta")

            wsClient = NexusWebSocketClient(
                hostUrl = urlCompleta,
                scope = scope
            )

            wsClient?.onConnected = {
                Log.d(TAG, " Canal WebSocket con Nexus AI vinculado exitosamente.")
            }

            wsClient?.onDisconnected = {
                Log.d(TAG, " Conexión WebSocket cerrada.")
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
                procesarEventoWebSocket(jsonString)  //  Aquí está la llamada
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
                    var responseText = payloadObj.optString("response", "")
                    val action = payloadObj.optString("action", "")
                    val mode = payloadObj.optString("mode", "")
                    var images = mutableListOf<String>()
                    var sources = mutableListOf<String>()
                    var htmlCompleto = ""
                    //  DETECTAR SI ES CONVERSACIONAL
                    val esConversacional = mode == "conversacional" || action == "conversational" || action == "greet"
                    //  GUARDAR RESPUESTA DEL ASISTENTE EN ROOM
                    if (responseText.isNotBlank() && chatRepository != null) {
                        val tag = when {
                            action == "conversational" || action == "greet" -> "conversation"
                            action == "search" -> "search"
                            else -> "action"
                        }
                        scope.launch {
                            chatRepository.addAssistantMessage(
                                content = responseText,
                                tag = tag,
                                displayText = responseText
                            )
                            Log.d(TAG, " Respuesta guardada en Room: $responseText")
                        }
                    }
                    Log.d(TAG, " PAYLOAD recibido:")
                    Log.d(TAG, "  - response: $responseText")
                    Log.d(TAG, "  - action: $action")
                    Log.d(TAG, "  - actions: ${actionsArray?.length() ?: 0}")
                    var finalResponse = responseText

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
                                        val params = accion.optJSONObject("params")
                                        if (params != null) {
                                            // Extraer ANSWER
                                            val answer = params.optString("answer", responseText)
                                            if (answer.isNotBlank()) responseText = answer
                                            htmlCompleto = params.optString("html", "")
                                            // Extraer IMÁGENES
                                            params.optJSONArray("images")?.let { array ->
                                                for (j in 0 until array.length()) {
                                                    images.add(array.getString(j))
                                                }
                                            }

                                            // Extraer FUENTES
                                            params.optJSONArray("sources")?.let { array ->
                                                for (j in 0 until array.length()) {
                                                    sources.add(array.getString(j))
                                                }
                                            }
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
                                Log.d(TAG, " Ejecutando ${broadcastActions.size} acciones")
                                ejecutarAccionesTecnicas(broadcastActions, responseText, "websocket_payload")
                            }
                        }
                        // Después de procesar acciones y actualizar la UI
                        if (responseText.isNotBlank()) {
                            ui.showSearchResult(responseText, sources, images, emptyList(), htmlCompleto)
                            Log.d(TAG, " Hablando respuesta: $responseText")
                            hablar(responseText) {
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
                    if (message == "Conexión establecida" ||
                        (stage == "STAGE_LISTENING" && message == "Conexión establecida")) {
                        Log.d(TAG, " Ignorando evento de conexión - NO activar escucha")
                        return
                    }
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
                                //  Cancelar timeout del servidor — esta es la respuesta final
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
                                        finalizarInteraccion()   //  antes hacía set manual de sesionActiva/estaHablando/IDLE
                                    }
                                }
                            }
                            type == "busqueda" -> {
                                if (message.isNotBlank()) {
                                    ui.showText(" $message")
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

                    Log.d(TAG, " Intent: $intent, Params: $params")
                }

                "ERROR" -> {
                    val payload = root.getJSONObject("payload")
                    val errorMsg = payload.optString("message", "Error desconocido")
                    Log.e(TAG, " Error del servidor: $errorMsg")
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

                        Log.d(TAG, " TTS extraído: $respuestaTexto")

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

    fun enviarComandoAlServidor(texto: String) {
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
            Log.w(TAG, " TIMEOUT del servidor (${TIMEOUT_SERVIDOR_MS}ms)")
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

                //  Obtener ubicación
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
                        ui.showText(" Ubicación obtenida")
                    } else {
                        locationError = "No se pudo obtener ubicación"
                    }
                } catch (e: Exception) {
                    locationError = "Error: ${e.message}"
                }

                //  Capturar pantalla
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

                //  Verificar WebSocket
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

                //  Construir el JSON correctamente
                val mensajeJson = JSONObject().apply {
                    put("text", texto.trim())  //  Texto limpio
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

                //  Enviar el JSON directamente usando el WebSocket
                val jsonString = mensajeJson.toString()
                Log.d(TAG, " Enviando comando: $jsonString")

                //  Enviar directamente sin pasar por sendText()
                client.sendText(jsonString)

                Log.d(TAG, " Comando enviado exitosamente")

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
        voiceEngine.setTtsReproduciendo(true)
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
                voiceEngine.setTtsReproduciendo(true)
                alTerminar?.invoke()
//                if (sesionActiva) {
//                    mainHandler.postDelayed({
////                        hybridTranscriber.reiniciarEscucha()
//                    }, 800L)
//                }
            }
        }
        elMediaPlayer?.setOnErrorListener { _, what, extra ->
            Log.e(TAG, " MediaPlayer error: what=$what extra=$extra")
            archivo.delete()
            elMediaPlayer?.release()
            elMediaPlayer = null
            estaHablando = false
            voiceEngine.setTtsReproduciendo(true)
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
        voiceEngine.setTtsReproduciendo(true)
        timestampUltimoTTS = System.currentTimeMillis()
        mainHandler.removeCallbacks(timeoutRunnable)
        //  En modo visual, NO detener la escucha
        if (!modoVisualActivo && voiceEngine.isSrSessionActive()) {
            voiceEngine.detenerSesion()
        }

        if (voiceEngine.isSrSessionActive()) {
            voiceEngine.detenerSesion()
        }
        setState(JarvisState.SPEAKING)
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
                    voiceEngine.setTtsReproduciendo(false)
                    alTerminar?.invoke()
                    if (!modoVisualActivo) {
                        finalizarInteraccion()
                    } else {
                        // ✅ Mantener sesión activa para escuchar números
                        if (sesionActiva && !voiceEngine.isSrSessionActive()) {
                            iniciarSRContinuo()
                        }
                    }
                }
            }

            override fun onError(id: String?) {
                Log.e(TAG, "Error en TTS")
                mainHandler.post {
                    estaHablando = false
                    voiceEngine.setTtsReproduciendo(false)   //  NUEVO
                    alTerminar?.invoke()
                    if (!modoVisualActivo && sesionActiva) {
                        stopListeningCompletamente()
                    } else if (modoVisualActivo && sesionActiva) {
                        iniciarSRContinuo()
                    }
                }
            }
        })

        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "UTT_${System.currentTimeMillis()}")
    }
    private fun finalizarInteraccion() {
        Log.d(TAG, "Finalizando interacción, volviendo a modo IDLE")

        // Detener todo
        stopListeningCompletamente()

        // ✅ REINICIAR WAKE WORD
        reiniciarWakeWord()
    }

    private fun reiniciarWakeWord() {
        Log.d(TAG, "Reiniciando modo wake word")

        // Asegurar que el engine esté en modo WAKE_WORD
        if (!voiceEngine.isWakeWordMode()) {
            voiceEngine.reiniciarWakeWord()
        }

        // Reanudar Porcupine si existe
        porcupineController?.reanudarPorcupine()

        // Mostrar estado IDLE en UI
        ui.renderState(JarvisState.IDLE)
        ui.setOrbVisibility(true)
        ui.updateORB(0f)

        // Limpiar UI de texto
        ui.showText("")
        uiState?.apply {
            showPanel = false
            userTranscription = ""
            processingSteps = emptyList()
        }
    }
    fun detenerAudio() {
        mainHandler.post {
            try {
                // 1. Detener el TTS nativo de Android si está hablando
                if (ttsListo && tts.isSpeaking) {
                    Log.d(TAG, " Deteniendo Android TTS por petición del usuario.")
                    tts.stop()
                }
                voiceEngine.setTtsReproduciendo(false)
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
                setState(JarvisState.IDLE)
                //  wake word inmediatamente
                // Detener cualquier sesión de escucha activa
                if (voiceEngine.isSrSessionActive()) {
                    voiceEngine.detenerSesion()
                }
                // 5.  REINICIAR EL MOTOR DE AUDIO COMPLETAMENTE
                try {
                    voiceEngine.reiniciarAudio()
                    Log.d(TAG, " voiceEngine.reiniciarAudio() ejecutado en detenerAudio")    } catch (e: Exception) {
                    Log.e(TAG, " Error en voiceEngine.restart(): ${e.message}")
                }
                estaHablando = false
                isProcessing = false
                sesionActiva = false
                porcupineController?.reanudarPorcupine()

                // Limpiar UI
                ui.showText("")
                uiState?.apply {
                    showPanel = false
                    userTranscription = ""
                    processingSteps = emptyList()
                }
                Log.d(TAG, "✅ Audio detenido - Wake word reactivado")

            } catch (e: Exception) {
                Log.e(TAG, "Error al detener el audio: ${e.message}")
            }
        }
    }

    // ── Router TTS — cambia TTS_MODE arriba del archivo para alternar ────────
    fun hablar(texto: String, alTerminar: (() -> Unit)? = null) {
        Log.d(TAG, " hablar() llamado con: '$texto'")
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
        if (!sesionActiva) {
            Log.d(TAG, " Sesión inactiva, no iniciar SR")
            return
        }
        if (estaHablando) {
            Log.d(TAG, " No iniciar SR porque el asistente está hablando")
            return
        }
        if (voiceEngine.isSrSessionActive()) {
            Log.d(TAG, "SR ya activo — ignorando iniciarSRContinuo")
            return
        }
        if (!voiceEngine.isReady()) {
            Log.d(TAG, "Engine no listo, esperando...")
            mainHandler.postDelayed({ iniciarSRContinuo(timeoutMs) }, 500)
            return
        }

        setState(JarvisState.LISTENING)
        voiceEngine.iniciarSesionContinua(language = "es")
        mainHandler.removeCallbacks(timeoutRunnable)

        // En modo visual, timeout más largo (10 segundos)
        val timeout = if (modoVisualActivo) {
            Log.d(TAG, "⏰ Modo visual: timeout de 10000ms")
            10000L
        } else {
            timeoutMs
        }

        mainHandler.postDelayed(timeoutRunnable, timeout)
        Log.d(TAG, "SR iniciado, timeout de ${timeout}ms")
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

        esperandoRespuestaServidor = false
        estaEnviandoAlServidor = false
        estaProcesandoComando = false

        if (voiceEngine.isSrSessionActive()) {
            voiceEngine.detenerSesion()
        }
        detenerTTS()
        setState(JarvisState.IDLE)
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!voiceEngine.isWakeWordMode()) {
            voiceEngine.reiniciarWakeWord()
        }
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
            //  Si estamos en modo visual, NO detener la escucha
            if (modoVisualActivo) {
                Log.d(TAG, "⏰ Timeout en modo visual - reiniciando timeout")
                mainHandler.postDelayed(this, TIMEOUT_ESCUCHA_MS)
                return
            }

            if (!voiceEngine.isSrSessionActive()) {
                Log.d(TAG, "Timeout ignorado - STT ya no está activo, reseteando estado")
                if (sesionActiva) {
                    stopListeningCompletamente()
                }
                return
            }

            if (sesionActiva && !isProcessing && voiceEngine.isSrSessionActive()) {
                if (esperandoConfirmacion) {
                    Log.d(TAG, "Timeout ignorado — esperando confirmación")
                    mainHandler.postDelayed(this, TIMEOUT_ESCUCHA_MS)
                    return
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

        //  LÓGICA DE CONFIRMACIÓN WHATSAPP
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
        Log.d(TAG, " ACTIVANDO MODO VISUAL")
        voiceEngine.setDuckingEnabled(true)
        Log.d(TAG, "🔊 Ducking activado para modo visual")

        setState(JarvisState.THINKING)
        isProcessing = true
        modoVisualActivo = true
        mainHandler.post {
            uiState?.apply {
                modoVisualActivo = true
                barColors = BarColorMode.TRANSPARENT
                labelText = "ESCUCHANDO NÚMEROS"
                labelColor = android.graphics.Color.parseColor("#00FF88")
                showWave = true
                showPause = false
            }
            ui.setOrbVisibility(true)
        }
        scope.launch {
            Log.d(TAG, " [DEBUG VISUAL] Solicitando captura de pantalla...")

            //  Usar callback para asegurar que obtenemos el snapshot
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
                    Log.e(TAG, " No se pudo capturar la pantalla después de ${intentos} intentos")
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

                    Log.d(TAG, " Elementos totales: ${elementosCapturados.size}")
                    Log.d(TAG, " Elementos interactivos: ${interactivos.size}")

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
    private var timeoutVisual: Runnable? = null
    private val TIMEOUT_VISUAL_MS = 30000L // 30 segundos

    private fun mostrarOverlayNumeros(elementos: List<ScreenElement>) {
        if (numberedOverlay == null) {
            numberedOverlay = NumberedElementsOverlay(context)
        }
        modoVisualActivo = true
        numberedOverlay!!.mostrar(elementos)

        //  Timeout automático
        timeoutVisual?.let { mainHandler.removeCallbacks(it) }
        timeoutVisual = Runnable {
            Log.d(TAG, "Timeout modo visual - ocultando automáticamente")
            desactivarModoVisual()
        }
        mainHandler.postDelayed(timeoutVisual!!, TIMEOUT_VISUAL_MS)

        //  Mostrar en la barra que está escuchando números
        mainHandler.post {
            uiState?.apply {
                labelText = "🔢 ESCUCHANDO NÚMEROS"
                labelColor = android.graphics.Color.parseColor("#00FF88")
                showWave = true
            }
        }

        val resumen = "Encontré ${elementos.size} elementos. Di el número."
        ui.showText(resumen)

        // Hablar el resumen pero NO detener la escucha
        hablar(resumen) {
            isProcessing = false
            // IMPORTANTE: Mantener la escucha activa
            if (modoVisualActivo && sesionActiva && !voiceEngine.isSrSessionActive()) {
                iniciarSRContinuo()
            } else if (modoVisualActivo && sesionActiva && voiceEngine.isSrSessionActive()) {
                //  Reiniciar timeout para mantener escucha
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
            }
            Log.d(TAG, "Resumen hablado - modo visual escuchando números")
        }
    }

    private fun desactivarModoVisual() {
        Log.d(TAG, " DESACTIVANDO MODO VISUAL")
        Log.d(TAG, "🔊 Ducking desactivado")

        modoVisualActivo = false
        timeoutVisual?.let { mainHandler.removeCallbacks(it) }
        numberedOverlay?.ocultar()

        mainHandler.post {
            uiState?.apply {
                modoVisualActivo = false
                barColors = BarColorMode.IDLE
                labelText = "NEXUS"  //  Restaurar texto original
                labelColor = android.graphics.Color.WHITE
                showWave = false
                showPause = false
            }
            ui.setOrbVisibility(true)
        }

        hablar("Modo visual desactivado.") {
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }
    private fun ejecutarClickNumerico(numero: Int) {
        val overlay = numberedOverlay
        if (overlay == null || !overlay.estaVisible()) {
            hablar("El modo visual no está listo.") {
                isProcessing = false
            }
            return
        }

        val elemento = overlay.obtenerPorNumero(numero)
        if (elemento == null) {
            hablar("No encuentro el número $numero.") {
                isProcessing = false
            }
            return
        }

        overlay.resaltarElemento(numero, 500L)
        hacerVibrar(50)

        Log.d(TAG, " CLICK en #$numero: x=${elemento.centerX} y=${elemento.centerY}")
        Log.d(TAG, "   Bounds: ${elemento.bounds}")
        Log.d(TAG, "   Clickable: ${elemento.isClickable}")

        scope.launch {
            isProcessing = false

            //  Esperar a que se vea el resaltado
            delay(300)

            val x = elemento.centerX.toFloat()
            val y = elemento.centerY.toFloat()

            //  Usar el AccessibilityService directamente
            MyAccessibilityService.instance?.let { service ->
                var exito = service.tapEnNodoPorCoordenadas(x, y)

                if (!exito) {
                    Log.w(TAG, "Falló tap en nodo, usando coordenadas directas")
                    service.tapCoordenadas(mapOf("x" to x, "y" to y))
                }

                //  Esperar a que la pantalla cambie
                delay(1200)

                //  Fuerza captura de pantalla y actualizar overlay
                service.captureCurrentScreenNow()
                delay(600)

                withContext(Dispatchers.Main) {
                    actualizarOverlayVisual()
                }
            } ?: run {
                val accion = ActionDto(
                    tipo = "tap",
                    params = mapOf("x" to elemento.centerX, "y" to elemento.centerY)
                )
                ejecutarAccionesTecnicas(listOf(accion), "toca el $numero", "click_numerico")

                delay(1200)
                MyAccessibilityService.instance?.captureCurrentScreenNow()
                delay(600)
                withContext(Dispatchers.Main) {
                    actualizarOverlayVisual()
                }
            }

            //  CRUCIAL: NO detener la sesión de escucha
            //  Asegurar que el micrófono sigue activo para el siguiente número
            withContext(Dispatchers.Main) {
                if (modoVisualActivo && sesionActiva && !voiceEngine.isSrSessionActive()) {
                    Log.d(TAG, "🔊 Reactivando escucha para modo visual continuo")
                    iniciarSRContinuo()
                } else if (modoVisualActivo && sesionActiva && voiceEngine.isSrSessionActive()) {
                    Log.d(TAG, "🎤 Micrófono ya activo, continuando escucha")
                    //  Reiniciar el timeout para mantener la escucha
                    mainHandler.removeCallbacks(timeoutRunnable)
                    mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
                }
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
        detenerAudio()
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

        val intent = Intent(ACTION_EXECUTE).apply {
            putExtra("actions_json", Gson().toJson(listOf(
                ActionDto(tipo = "global_action", params = mapOf("action" to "home"))
            )))
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, " Cancelación completada - Wake word reactivado")
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
        if (elementos.isNotEmpty()) {
            numberedOverlay?.mostrar(elementos)

            val cantidad = numberedOverlay?.cantidadElementos() ?: 0
            Log.d(TAG, " Overlay actualizado: $cantidad elementos")

            // Notificar cambio con vibración suave
            hacerVibrar(30)

            //  Mantener la escucha activa después de actualizar
            if (modoVisualActivo && sesionActiva && !voiceEngine.isSrSessionActive()) {
                iniciarSRContinuo()
            } else if (modoVisualActivo && sesionActiva && voiceEngine.isSrSessionActive()) {
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_ESCUCHA_MS)
            }
        } else {
            Log.d(TAG, " No hay elementos para actualizar, desactivando modo visual")
            desactivarModoVisual()
        }
    }

    fun interceptarComandoVisual(texto: String): Boolean {
        Log.d(TAG, " interceptarComandoVisual: '$texto', modoVisualActivo=$modoVisualActivo")
        val t = texto.lowercase().trim()

        //  Si el modo visual NO está activo, solo detectar activación
        if (!modoVisualActivo) {
            val activadores = listOf(
                "modo visual", "qué puedo tocar", "que puedo tocar",
                "muestra los números", "muestra los numeros", "qué hay en pantalla",
                "que hay en pantalla", "numera la pantalla", "muéstrame opciones",
                "muestrame opciones", "modo número", "modo numero",
                "activar modo visual", "ver números", "ver numeros",
                "qué puedo presionar", "que puedo presionar", "qué puedo pulsar",
                "muéstrame los números", "muestrame los numeros",
                "dime qué hay", "qué hay aquí", "que hay aqui",
                "muestra los números", "muestrame numeros", "ver numeros",
                "muestra números", "muestrame numeros", "muestrame los numeros"
            )
            if (activadores.any { t.contains(it) }) {
                Log.d(TAG, " Activando modo visual por: '$texto'")
                activarModoVisual()
                return true
            }
            return false
        }

        //  ESTAMOS EN MODO VISUAL - TODOS LOS COMANDOS SE PROCESAN LOCALMENTE

        // 1. Comandos de SALIDA (desactivar modo visual)
        val salidas = listOf(
            "salir de modo visual", "salir modo visual", "desactivar modo visual",
            "cerrar modo visual", "quitar modo visual", "modo normal", "ocultar números",
            "ocultar numeros", "quita los números", "quita numeros",
            "terminar modo visual", "apagar modo visual", "no quiero números",
            "quitar números", "remover números", "modo visual off",
            "salir", "atrás", "volver", "cancelar", "cancela",
            "salir modo", "cerrar modo", "apagar modo", "quitar modo"
        )
        if (salidas.any { t.contains(it) }) {
            Log.d(TAG, " Desactivando modo visual por: '$texto'")
            desactivarModoVisual()
            return true
        }

        // 2.  PALABRAS CLAVE - acciones rápidas sin números
        // Reconocer "atrás", "home", "menú" para navegación básica
        val palabrasClave = mapOf(
            "atrás" to "back",
            "volver" to "back",
            "regresa" to "back",
            "home" to "home",
            "inicio" to "home",
            "pantalla principal" to "home",
            "menú" to "menu",
            "opciones" to "menu",
            "notificaciones" to "notifications",
            "captura de pantalla" to "screenshot",
            "captura" to "screenshot"
        )

        for ((palabra, accion) in palabrasClave) {
            if (t.contains(palabra)) {
                Log.d(TAG, " Palabra clave detectada: '$palabra' → $accion")
                val accionDto = ActionDto(tipo = "global_action", params = mapOf("action" to accion))
                ejecutarAccionesTecnicas(listOf(accionDto), "palabra clave $palabra", "palabra_clave")
                hacerVibrar(50)
                return true
            }
        }

        // 3.  MODO "COMANDO RÁPIDO" - SOLO NÚMERO
        // Verificar si el texto ES un número (solo el número)
        val numeroSolo = t.replace(Regex("[^0-9]"), "").toIntOrNull()
        if (numeroSolo != null && numeroSolo >= 1 && numeroSolo <= 40 && t.matches(Regex("^\\d+$"))) {
            Log.d(TAG, " Número solo detectado: $numeroSolo")
            ejecutarClickNumerico(numeroSolo)
            return true
        }

        // 4. Comandos de TAP (tocar un número) - con variantes
        val patrones = listOf(
            Regex("toca el (\\d+)"),
            Regex("toca (\\d+)"),
            Regex("presiona el (\\d+)"),
            Regex("presiona (\\d+)"),
            Regex("pulsa el (\\d+)"),
            Regex("pulsa (\\d+)"),
            Regex("dale al (\\d+)"),
            Regex("dale clic al (\\d+)"),
            Regex("haz clic en el (\\d+)"),
            Regex("selecciona el (\\d+)"),
            Regex("elige el (\\d+)"),
            Regex("número (\\d+)"),
            Regex("numero (\\d+)"),
            Regex("opción (\\d+)"),
            Regex("opcion (\\d+)"),
            Regex("el (\\d+)"),  // Solo "el 3" en contexto visual
            Regex("la opción (\\d+)"),
            Regex("la opcion (\\d+)"),
            Regex("dale (\\d+)"),
            Regex("pulsar (\\d+)"),
            Regex("tocar (\\d+)"),
            Regex("presionar (\\d+)")
        )

        for (patron in patrones) {
            val match = patron.find(t)
            if (match != null) {
                val numero = match.groupValues[1].toIntOrNull()
                if (numero != null && numero >= 1 && numero <= 40) {
                    Log.d(TAG, " Tap en número $numero detectado en modo visual")
                    ejecutarClickNumerico(numero)
                    return true
                }
            }
        }

        // 5. Fallback: buscar cualquier número en el texto (con contexto)
        val numero = extraerNumeroDeTexto(t)
        if (numero != null && numero >= 1 && numero <= 40) {
            Log.d(TAG, "🎯 Número $numero detectado, ejecutando click")
            ejecutarClickNumerico(numero)
            //  NO detener la escucha - devolver true para que el comando no vaya al servidor
            return true
        }

        // 6. Si no se detectó nada útil, ignorar (NO enviar al servidor)
        Log.d(TAG, " En modo visual, ignorando: '$texto' (no es un comando válido)")
        return true  // Devuelve true para que NO se envíe al servidor
    }

    private fun extraerNumeroDeTexto(texto: String): Int? {
        //  Primero buscar dígitos
        Regex("""\b(\d+)\b""").find(texto)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        //  Palabras numéricas más completas
        val palabras = mapOf(
            "uno" to 1, "un" to 1, "una" to 1,
            "dos" to 2,
            "tres" to 3,
            "cuatro" to 4,
            "cinco" to 5,
            "seis" to 6,
            "siete" to 7,
            "ocho" to 8,
            "nueve" to 9,
            "diez" to 10,
            "once" to 11,
            "doce" to 12,
            "trece" to 13,
            "catorce" to 14,
            "quince" to 15,
            "dieciséis" to 16, "dieciseis" to 16,
            "diecisiete" to 17,
            "dieciocho" to 18,
            "diecinueve" to 19,
            "veinte" to 20,
            "veintiuno" to 21, "veintiun" to 21,
            "veintidós" to 22, "veintidos" to 22,
            "veintitrés" to 23, "veintitres" to 23,
            "treinta" to 30,
            "cuarenta" to 40,
            "cincuenta" to 50
        )

        for ((palabra, numero) in palabras) {
            if (texto.contains(palabra)) return numero
        }

        return null
    }
    private val MULETILLAS_Y_RUIDO = setOf(
        "eh", "ehh", "eeh", "ah", "ahh", "aah", "mmm", "mm", "m",
        "uh", "uhh", "um", "umm", "este", "esteee", "ay", "aja",
        "aha", "ajá", "okay", "ok", "eso", "bueno", "y", "o", "pero",
        "gracias",)
    /**
     * Devuelve true si el texto es "basura": muy corto, una sola palabra común,
     * o solo signos de puntuación / silencio mal transcripto.
     */
    private fun esResultadoBasura(texto: String): Boolean {
        val limpio = texto.lowercase().trim().removeSuffix(".").removeSuffix("¿").removeSuffix("?")

        if (limpio.isBlank()) return true

        // Muy corto (menos de 2 caracteres) casi siempre es ruido mal transcripto
        if (limpio.length < 2) return true

        val palabras = limpio.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Una sola palabra Y esa palabra está en la lista de muletillas
        if (palabras.size == 1 && palabras[0] in MULETILLAS_Y_RUIDO) {
            return true
        }

        // Todas las palabras son muletillas (ej: "eh eh mmm")
        if (palabras.isNotEmpty() && palabras.all { it in MULETILLAS_Y_RUIDO }) {
            return true
        }

        return false
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
                        if (!modoVisualActivo) {
                            labelText = "ESCUCHANDO"
                        }
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
                        //  En modo visual, mantener "ESCUCHANDO NÚMEROS"
                        if (modoVisualActivo) {
                            labelText = "🔢 ESCUCHANDO NÚMEROS"
                            labelColor = android.graphics.Color.parseColor("#00FF88")
                            showWave = true
                        } else {
                            labelText = "NEXUS"
                            labelColor = android.graphics.Color.WHITE
                            showWave = false
                        }
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

    /**
     * Para cuando el usuario toca fuera del panel/barra:
     * oculta todo y vuelve a modo wake word, SIN tocar el AudioRecord.
     */
    fun volverAWakeWordPorClicAfuera() {
        Log.d(TAG, " Clic afuera - ocultando y volviendo a wake word")

        // 1. Detener TTS si está hablando
        detenerTTS()
        voiceEngine.setTtsReproduciendo(false)

        // 2. Detener MediaPlayer de ElevenLabs si estaba activo
        elMediaPlayer?.let { player ->
            try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
            player.release()
        }
        elMediaPlayer = null

        // 3. Detener sesión de escucha activa (si la hay) SIN recrear el AudioRecord
        if (voiceEngine.isSrSessionActive()) {
            voiceEngine.detenerSesion()   // esto ya deja engineMode en WAKE_WORD internamente
        } else if (!voiceEngine.isWakeWordMode()) {
            voiceEngine.reiniciarWakeWord()   // camino liviano, no toca AudioRecord
        }

        // 4. Cancelar cualquier envío pendiente al servidor
        cancelarEnvioActual()

        // 5. Resetear flags de estado
        sesionActiva = false
        isProcessing = false
        esperandoConfirmacion = false
        estaHablando = false
        esperandoRespuestaServidor = false
        estaEnviandoAlServidor = false
        estaProcesandoComando = false
        isRecognizingMusic = false
        mainHandler.removeCallbacks(timeoutRunnable)

        // 6. UI a IDLE
        setState(JarvisState.IDLE)
        ui.showText("")
        uiState?.apply {
            showPanel = false
            userTranscription = ""
            processingSteps = emptyList()
            showWhatsappPreview = false
        }

        porcupineController?.reanudarPorcupine()
        Log.d(TAG, " Vuelta a wake word completa (sin tocar AudioRecord)")
    }
    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────
    fun resetCompleto() {
        Log.d(TAG, " Reset completo desde controlador")

        // 1. Detener todo (pero NO destruir)
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
        // 5. Reanudar wake word
        porcupineController?.reanudarPorcupine()

        Log.d(TAG, " Reset completo - Listo para wake word")
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
