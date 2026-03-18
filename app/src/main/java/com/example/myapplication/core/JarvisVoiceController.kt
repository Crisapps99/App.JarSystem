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
import com.example.myapplication.service.JarvisNotificationListener

enum class JarvisState { IDLE, LISTENING, THINKING, SPEAKING }

interface JarvisUi {
    fun renderState(state: JarvisState)
    fun showText(text: String)
    fun showToast(text: String)
    fun getCurrentScreenText(): List<String>
    fun onRecognizerReady()
    fun updateORB(rms: Float)
    fun setOrbVisibility(visible: Boolean)
}
interface PorcupineController {
    fun pausarPorcupine()
    fun reanudarPorcupine()
    fun esPorcupinePausado(): Boolean
}
object ElevenLabsConfig {
    const val API_KEY          = "9dcae6842f3e53c4f885e4dcf30bf5635e8284c41df98d93f8b432b5f4383e90"
    const val VOICE_ID         = "dQ0C8BEdKF2odmELvNee"
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
    private val porcupineController: PorcupineController? = null
) {
    companion object {
        private const val TAG = "JARVIS_CTRL"
        private const val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    }

    // ── Componentes del sistema ──────────────────────────────────────────────
    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private var ttsListo = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── NUEVO: Motor de audio unificado ─────────────────────────────────────
    private lateinit var audioEngine: ContinuousAudioEngine

//    // ── NUEVO: Transcriptor Whisper local ───────────────────────────────────
//    private lateinit var whisperTranscriber: WhisperTranscriber
//    private var whisperListo = false

    // ── NUEVO: Gestor de sesión y VAD ───────────────────────────────────────
    private lateinit var sessionManager: VoiceSessionManager

    // ── Estado de la sesión ─────────────────────────────────────────────────
    private var sesionActiva = false
    private var isProcessing = false         // true mientras Whisper transcribe o la API responde
    private var esperandoConfirmacion = false

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

    // ── Ventana anti-eco para confirmaciones ────────────────────────────────
    private var ttsTerminoTimestamp = 0L
    private val ECO_WINDOW_MS = 500L

    private lateinit var hybridTranscriber: HybridSpeechTranscriber
    private var hybridListo = false

    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val audioManagerSystem by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    // ────────────────────────────────────────────────────────────────────────
    // INICIALIZACIÓN
    // ────────────────────────────────────────────────────────────────────────

    fun init() {
        audioManager = AudioManager(context)
        configurarTts()
        inicializarHybridTranscriber()
        inicializarAudioEngine()
        inicializarSessionManager()
        registrarReceivers()
        setState(JarvisState.IDLE)
        Log.i(TAG, "✅ Controlador inicializado")
    }
    private fun solicitarAudioFocusSR() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                // ✅ MAY_DUCK: el video baja volumen pero NO se pausa
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
            Log.d(TAG, "🎙️ AudioFocus SR: ${if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "CONCEDIDO" else "DENEGADO"}")
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
    private fun inicializarHybridTranscriber() {
        hybridTranscriber = HybridSpeechTranscriber(context, scope)
        hybridListo = try {
            hybridTranscriber.init()
        } catch (e: Exception) {
            Log.e(TAG, "❌ HybridTranscriber falló: ${e.message}")
            false
        }
        Log.i(TAG, if (hybridListo) "✅ SR inicializado" else "❌ SR no disponible")
    }


    /**
     * Inicializa el motor de audio.
     * onFrameAvailable se llama con cada frame de 512 samples (~32ms).
     */
    private fun inicializarAudioEngine() {
        audioEngine = ContinuousAudioEngine(
            onFrameAvailable = { frame ->
                // Solo Porcupine cuando sesión inactiva
                if (!sesionActiva) {
                    procesarFrameParaPorcupine(frame)
                }
                // NOTA: cuando sesionActiva==true, el SR tiene el micrófono
                // El ContinuousAudioEngine puede estar corriendo en paralelo
                // pero NO interferirá porque SR usa su propio canal de audio
            },
            onRmsChanged = { rms ->
                mainHandler.post { ui.updateORB(rms) }
            }
        )
        audioEngine.start()
        Log.i(TAG, "✅ Motor de audio iniciado")
    }

    /**
     * Inicializa el gestor de sesión con los callbacks de VAD.
     */
    private fun inicializarSessionManager() {
        sessionManager = VoiceSessionManager(
            scope = scope,

            onSpeechStarted = {
                // Ya no usamos AudioEngine para grabar — SR maneja el micrófono
                // Dejamos vacío o solo log
                Log.d(TAG, "🎙️ [VAD] Inicio de habla (informativo)")
            },

            onSpeechEnded = {
                // Ya no hay audioData — SR entrega texto directamente
                Log.d(TAG, "🎙️ [VAD] Fin de habla (informativo)")
            },

            onInterruption = {
                Log.d(TAG, "🛑 Interrupción del usuario")
                detenerTTS()
            },

            onSessionTimeout = {
                Log.d(TAG, "⏱️ Timeout de sesión")
                stopListeningCompletamente()
                ui.showToast("Escucha detenida")
            }
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // FLUJO PRINCIPAL: TRANSCRIPCIÓN Y PROCESAMIENTO
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Transcribe el audio con Whisper y luego procesa el texto resultante.
     * Se ejecuta en IO para no bloquear el hilo principal.
     */
    private fun procesarTexto(texto: String) {
        // Ya tenemos el texto del SR — no hay transcripción que esperar
        mainHandler.post {
            if (esperandoConfirmacion) {
                procesarRespuestaConfirmacion(texto)
                return@post
            }
            val intencion = CommandAnalyzer.clasificar(texto)

            when (intencion) {CommandAnalyzer.Intent.MUSIC -> { ejecutarMusica(texto); return@post }
                CommandAnalyzer.Intent.NAVIGATION -> { ejecutarNavegacion(texto); return@post }
                CommandAnalyzer.Intent.SEARCH -> { ejecutarBusquedaWeb(texto); return@post }
                CommandAnalyzer.Intent.TIME -> { ejecutarComandoHora(); return@post }
                CommandAnalyzer.Intent.WEATHER -> { ejecutarClima(); return@post }
                CommandAnalyzer.Intent.NOTIFICATIONS -> {
                    val resumen = obtenerResumenNotificaciones()
                    hablar(resumen) {
                        isProcessing = false
                        // Opcional: Jarvis vuelve a escuchar por si quieres que las lea completas
                        if (sesionActiva) {
                            mainHandler.postDelayed({ iniciarSRContinuo() }, 500)
                        }
                    }
                    return@post // <--- ESTO ES VITAL
                }
                CommandAnalyzer.Intent.UNKNOWN -> {
                // Si es UNKNOWN, el código sigue hacia abajo para buscar en la IA o Comandos Visuales
                Log.d(TAG, "Intención local desconocida, intentando IA/Visual...")
            }

            }
            if (interceptarComandoVisual(texto)) {
                isProcessing = false
                if (sesionActiva) iniciarSRContinuo()
                return@post
            }
            if (esFraseDeSalida(texto)) {
                terminarSesion()
                return@post
            }
            enviarComandoAlServidor(texto)
        }
    }
    private fun ejecutarComandoHora() {
        val cal = java.util.Calendar.getInstance()
        val hora = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minuto = String.format("%02d", cal.get(java.util.Calendar.MINUTE))

        val periodo = if (hora < 12) "de la mañana" else if (hora < 18) "de la tarde" else "de la noche"
        val hora12 = if (hora == 0) 12 else if (hora > 12) hora - 12 else hora

        val textoHora = "Son las $hora12 y $minuto $periodo"

        hablar(textoHora) {
            isProcessing = false
            if (sesionActiva) iniciarSRContinuo()
        }
    }
    private fun ejecutarMusica(texto: String) {
        val busqueda = texto.lowercase()
            .replace("reproduce", "").replace("pon la canción", "")
            .replace("pon música de", "").replace("pon a", "")
            .replace("en spotify", "").replace("en youtube", "")
            .replace("pon", "").trim()

        val mensaje = "Reproduciendo $busqueda"

        hablar(mensaje) {
            try {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(android.app.SearchManager.QUERY, busqueda)

                    // ESTA LÍNEA ES LA CLAVE PARA QUE REPRODUZCA Y NO SOLO BUSQUE
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")

                    if (texto.contains("youtube", ignoreCase = true)) {
                        // YouTube Music o YouTube principal procesan mejor este Action
                        setPackage("com.google.android.youtube")
                    } else {
                        // Paquete OFICIAL de Spotify
                        setPackage("com.spotify.music")
                    }
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al reproducir: ${e.message}")
                // Fallback: Si falla, abrir búsqueda normal en YouTube
                val fallbackYT = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", busqueda)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackYT)
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
                val webMaps = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destino)}"))
                webMaps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webMaps)
            }
            isProcessing = false
        }
    }
    private fun ejecutarBusquedaWeb(texto: String) {
        val busqueda = texto.lowercase()
            .replace("busca en internet", "")
            .replace("investiga", "")
            .replace("busca", "").trim()

        hablar("Buscando $busqueda en la web") {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$busqueda"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            isProcessing = false
        }
    }
    private fun ejecutarClima() {
        hablar("Consultando el clima para ti") {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=clima+actual"))
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
    // ────────────────────────────────────────────────────────────────────────
    // API
    // ────────────────────────────────────────────────────────────────────────

    private fun enviarComandoAlServidor(texto: String) {
        scope.launch {
            setState(JarvisState.THINKING)
            ui.showText("Procesando comando...")

            // Capturar pantalla
            MyAccessibilityService.instance?.captureNow()
            delay(200)

            val snapshot = ScreenMemory.lastSnapshot

            // ✅ UNIVERSAL: ENVIAR TODOS LOS ELEMENTOS SIN FILTRAR
            val contextoDetallado = snapshot?.elements
                ?.sortedByDescending { it.importance }
                ?.take(100)  // Aumentar límite para más contexto
                ?.map { it.toDto() } ?: emptyList()

            Log.d(TAG, "📱 Elementos capturados: ${contextoDetallado.size}")
            contextoDetallado.take(5).forEach { el ->
                Log.d(TAG, "   • ${el.text} (clickable=${el.clickable}, type=${el.className})")
            }
            val notificacionesText = obtenerResumenNotificaciones()
            val metadata = mapOf(
                "packageName"     to (snapshot?.packageName ?: "unknown"),
                "activityName"    to (snapshot?.activityName ?: "unknown"),
                "totalElements"   to (snapshot?.totalElements ?: 0),
                "clickableCount"  to (snapshot?.clickableElements ?: 0),
                "editableCount"   to (snapshot?.editableElements ?: 0),
                "timestamp"       to System.currentTimeMillis(),
                // ✅ SIN appContext específico - Es UNIVERSAL
                "screenInfo"      to "Todos los elementos clickeables/editables/scrollables están en contexto_detallado"
            )

            try {
                val response = actionApiService.predictActionEnriquecido(
                    ActionRequestEnriquecido(
                        texto = texto,
                        contexto = emptyList(),
                        contextoDetallado = contextoDetallado,
                        metadata = metadata
                    )
                )

                if (response.success) {
                    ui.showText(response.response_text)
                    val esAccionTecnica = response.mode == "COMMAND" || response.mode == "DYNAMIC_ACTION"
                    if (esAccionTecnica && !response.payload.isNullOrEmpty()) {
                        ejecutarAccionesTecnicas(response.payload, texto, response.action ?: "unknown")
                    }
                    hablar(response.response_text) {
                        isProcessing = false
                        if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                    }
                } else {
                    hablar("No pude procesar eso.") {
                        isProcessing = false
                        if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}")
                hablar("Error de conexión.") {
                    isProcessing = false
                    if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                }
            }
        }
    }
    /**
     * Obtiene un resumen de las notificaciones activas del dispositivo.
     * Accede a NotificationMemory que ya está sincronizado con las notificaciones.
     */
    private fun obtenerResumenNotificaciones(): String {
        // 1. Forzamos al Listener a que mire el panel de Android justo ahora
        val listener = JarvisNotificationListener.instance
        if (listener == null) return "Lo siento, el servicio de notificaciones no está conectado."

        listener.refrescarNotificacionesActivas()

        // 2. Obtenemos la lista actualizada de la memoria
        val lista = NotificationMemory.getNotifications()
// 1. Filtrar notificaciones irrelevantes (códigos, sistema, vacías)
        val filtradas = lista.filter { noti ->
            val esSistema = noti.packageName.contains("android") || noti.packageName.contains("systemui")
            val tieneContenido = noti.title.isNotBlank() || noti.body.isNotBlank()
            val esCodigo = noti.body.matches(Regex(".*[0-9]{5,}.*")) // Filtra si el cuerpo es solo un código largo

            !esSistema && tieneContenido && !esCodigo
        }

        if (filtradas.isEmpty()) return "No tienes notificaciones importantes por ahora."
        // 3. Agrupamos por el nombre de la app (appName) que ya extrajimos en el listener
        val agrupadas = lista.groupBy { it.appName }
        val total = lista.size

        val sb = StringBuilder()
        sb.append("Tienes $total ${if (total == 1) "notificación" else "notificaciones"}. ")

        agrupadas.forEach { (appName, notis) ->
            val cantidad = notis.size
            if (cantidad == 1) {
                val n = notis[0]
                // Limpiamos el título para que no lea "com.whatsapp.chat..."
                val emisor = n.title.split(":").firstOrNull() ?: n.title
                sb.append("En $appName, $emisor dice: ${n.body.take(40)}. ")
            } else {
                // Si hay muchas, solo mencionamos quiénes escribieron para no aturdir
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
                Log.d(TAG, "✅ TTS listo")
            } else {
                Log.e(TAG, "❌ Error TTS: $status")
            }
        }
    }

    private fun hablar(texto: String, alTerminar: (() -> Unit)? = null) {
        if (!ttsListo) {
            alTerminar?.invoke()
            return
        }

        setState(JarvisState.SPEAKING)
//        hybridTranscriber.reanudarEscucha()

        val id = "UTT_${System.currentTimeMillis()}"
        val startTtsTime = System.currentTimeMillis()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "🔊 TTS INICIÓ - activando micrófono para captura")
                iniciarAnimacionOrbeSimulada()

                // ✅ CRUCIAL: Activa micrófono MIENTRAS TTS habla
                // Esto permite que captures TODAS las palabras sin perder nada
                mainHandler.postDelayed({
                    Log.d(TAG, "🎙️ [TEMPRANO] Activando escucha - usuario puede hablar ahora")
                    sessionManager.setAllowEarlyInput(true)
                }, 50L)  // Pequeño delay para que TTS estabilice
            }

            // ✅ CAMBIO: Este método onDone() debe quedar así:
            override fun onDone(id: String?) {
                Log.d(TAG, "🔊 TTS TERMINÓ")
                mainHandler.post {
                    ui.updateORB(0f)
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    sessionManager.setAllowEarlyInput(false)

                    // ✅ Ejecutar callback
                    alTerminar?.invoke()

                    // ✅ NUEVO: Reiniciar SR explícitamente después de TTS
                    if (sesionActiva) {
                        Log.d(TAG, "⏳ Esperando 800ms antes de reiniciar SR...")
                        mainHandler.postDelayed({
                            Log.d(TAG, "🔄 SR reiniciado después de TTS")
                            hybridTranscriber.reiniciarEscucha()
                        }, 800L)
                    }
                }
            }

            override fun onError(id: String?) {
                Log.e(TAG, "❌ Error en TTS")
                mainHandler.post {
                    sessionManager.setAllowEarlyInput(false)
                    alTerminar?.invoke()
                }
            }
        })

        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
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
            Log.d(TAG, "⏹️ TTS detenido por interrupción")
        }
    }

    private fun iniciarAnimacionOrbeSimulada() {
        scope.launch {
            var contador = 0
            while (tts.isSpeaking) {
                val base     = 3f
                val impulso  = (Math.sin(contador.toDouble() * 0.5).toFloat() * 5f) + 5f
                val pico     = if (Math.random() > 0.8) 4f else 0f
                val rms      = (base + impulso + pico).coerceIn(0f, 15f)
                withContext(Dispatchers.Main) { ui.updateORB(rms) }
                contador++
                delay(50)
            }
            withContext(Dispatchers.Main) { ui.updateORB(0f) }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PORCUPINE — Integración con el motor unificado
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Procesa frames para Porcupine cuando la sesión está inactiva.
     *
     * NOTA IMPORTANTE sobre la integración de Porcupine con ContinuousAudioEngine:
     *
     * En la versión anterior, Porcupine usaba VoiceProcessor (su propio AudioRecord).
     * Ahora usamos nuestro ContinuousAudioEngine como fuente de audio única.
     *
     * Para que Porcupine funcione sin VoiceProcessor necesitas usar PorcupineManager
     * en modo "manual" (sin VoiceProcessor) alimentándolo con nuestros frames.
     *
     * Opción A — Usar PorcupineManager con frames manuales:
     *   El PorcupineManager estándar no expone process(frame) directamente.
     *   Necesitas usar la clase Porcupine (no PorcupineManager) que sí tiene process().
     *
     * Opción B — Mantener VoiceProcessor de Porcupine SOLO para wake word:
     *   Porcupine usa VoiceProcessor cuando la sesión está IDLE.
     *   Cuando empieza la sesión, pausas Porcupine (como hacías antes).
     *   La diferencia es que CobraVAD ya no existe → no hay conflicto.
     *
     * RECOMENDACIÓN: Usa la Opción B (más simple, menos cambios en tu código de Porcupine).
     * El conflicto de audio que tenías era Cobra vs Porcupine, no Porcupine vs nada.
     * Con Cobra eliminado, Porcupine puede seguir usando VoiceProcessor en IDLE.
     *
     * Si quieres implementar Opción A (un solo AudioRecord), cambia PorcupineManager
     * por la clase Porcupine directa y llama porcupine.process(frame) aquí.
     */
    private fun procesarFrameParaPorcupine(frame: ShortArray) {
        // Con la Opción B: Porcupine sigue usando su VoiceProcessor en IDLE
        // Esta función queda vacía — Porcupine maneja sus propios frames
        // Con la Opción A: descomenta y usa porcupine.process(frame)
    }

    // ────────────────────────────────────────────────────────────────────────
    // GESTIÓN DE SESIÓN
    // ────────────────────────────────────────────────────────────────────────

    fun startInteraction() {
        if (sesionActiva) { Log.w(TAG, "Sesión ya activa"); return }

        sesionActiva = true
        isProcessing = false
        porcupineController?.pausarPorcupine()
        if (::audioEngine.isInitialized) audioEngine.stop()
        setState(JarvisState.SPEAKING)

        obtenerSaludoGemma { saludo ->
            val txt = saludo ?: "Listo, te escucho."
            ui.showText(txt)
            hablar(txt) {
                isProcessing = false
                // Inicia SR continuo DESPUÉS del saludo
                iniciarSRContinuo()
            }
        }
        Log.d(TAG, "▶️ Sesión iniciada")
    }
    private fun iniciarSRContinuo() {
        solicitarAudioFocusSR()
        if (!sesionActiva || !hybridListo) return
        setState(JarvisState.LISTENING)

        hybridTranscriber.iniciarSesionContinua(
            language = "es",
            onResult = { texto ->
                if (!isProcessing && sesionActiva) {
                    Log.d(TAG, "✅ SR resultado: \"$texto\"")
                    ui.showText(texto)
                    isProcessing = true
                    setState(JarvisState.THINKING)

                    // ✅ Vibración en lugar de sonido
                    hacerVibrar(100)

                    procesarTexto(texto)
                }
            },
            onError = { error ->
                Log.w(TAG, "⚠️ SR error (se reinicia): $error")
            },
            onSpeechStarted = {
                if (sesionActiva && !isProcessing) {
                    setState(JarvisState.LISTENING)

                    // ✅ Vibración en lugar de sonido
                    hacerVibrar(50)
                }
            },
            onSpeechEnded = {}
        )
    }
    private fun hacerVibrar(millisegundos: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ usa VibratorManager
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // API 26+ requiere VibrationEffect
                    vibrator.vibrate(VibrationEffect.createOneShot(millisegundos, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    // Versiones antiguas
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(millisegundos)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Vibración no disponible: ${e.message}")
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

        hybridTranscriber.detenerSesion()
        sessionManager.stopSession()
        liberarAudioFocusSR()
        detenerTTS()
        if (::audioEngine.isInitialized) audioEngine.start()

        porcupineController?.reanudarPorcupine()
        setState(JarvisState.IDLE)
        Log.d(TAG, "⏹️ Sesión detenida completamente")
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
        val granted = ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) { ui.showText("Permiso de micrófono denegado"); return }
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
        registrarReceptorConfirmacion()
        registrarReceptorOrbe()
        registrarReceptorWakeWord()
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
                if (pkg.isNotEmpty()) pendingMessagePackage = pkg
                esperandoConfirmacion = true
                isProcessing = true

                mainHandler.postDelayed({
                    hybridTranscriber.detenerSesion()

                    // Otro delay para que el SR libere el micrófono completamente
                    mainHandler.postDelayed({
                        hablar(pregunta) {
                            ttsTerminoTimestamp = System.currentTimeMillis()
                            isProcessing = false
                            esperandoConfirmacion = true

                            mainHandler.postDelayed({
                                if (sesionActiva) {
                                    Log.d(TAG, "🎙️ Escuchando respuesta de confirmación...")
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
    private fun procesarRespuestaConfirmacion(texto: String) {
        val t = texto.lowercase().trim()
        val tiempoDesdeFinTTS = System.currentTimeMillis() - ttsTerminoTimestamp

        if (tiempoDesdeFinTTS < ECO_WINDOW_MS) {
            Log.d(TAG, "⚠️ Eco ignorado (${tiempoDesdeFinTTS}ms)")
            return
        }

        esperandoConfirmacion = false
        hacerVibrar(50)
        val esAfirmativo = listOf(
            "sí", "si", "claro", "dale", "ok", "okay", "okey", "envía",
            "envia", "manda", "confirmo", "adelante", "yes", "va", "bueno", "hazlo", "procede", "venga"
        ).any { t == it || t.startsWith("$it ") }

        val esNegativo = listOf(
            "no", "cancela", "cancel", "para", "detente", "no mandes", "mejor no", "no quiero", "olvídalo"
        ).any { t == it || t.startsWith("$it ") }

        when {
            esAfirmativo -> {
                val cb = ActionExecutor.onConfirmacionPendiente
                ActionExecutor.onConfirmacionPendiente = null
                cb?.invoke(true)
                hacerVibrar(100)
                hablar("Enviando.") {
                    isProcessing = false
                    if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
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
                    if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                }
            }
            else -> {
                hacerVibrar(30)
                hablar("No entendí. ¿Envío el mensaje? Di sí o no.") {
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    esperandoConfirmacion = true
                    isProcessing = false
                    if (sesionActiva) {
                        mainHandler.postDelayed({
                            sessionManager.onAssistantFinishedSpeaking()
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
            Log.d(TAG, "🔍 [DEBUG VISUAL] Solicitando captura de pantalla inmediata...")
            MyAccessibilityService.instance?.captureCurrentScreenNow()
            delay(1200)  // Aumentamos un poco para apps pesadas como FB

            val snapshot = ScreenMemory.lastSnapshot
            val elementos = snapshot?.elements ?: emptyList()

            // --- BLOQUE DE LOGS PARA DEPURACIÓN ---
            Log.d(TAG, "📱 App activa: ${snapshot?.packageName}")
            Log.d(TAG, "🔢 Elementos encontrados: ${elementos.size}")

            elementos.take(10).forEachIndexed { index, el ->
                Log.d(TAG, "   #$index -> Texto: [${el.text}] | Clase: ${el.className} | Clickable: ${el.isClickable}")
            }
            // --------------------------------------

            withContext(Dispatchers.Main) {
                if (elementos.isEmpty()) {
                    // ✅ RETRY: intentar una segunda captura
                    Log.w(TAG, "⚠️ Primera captura vacía, reintentando...")
                    MyAccessibilityService.instance?.captureNow()

                    delay(800)
                    val retry = ScreenMemory.lastSnapshot?.elements ?: emptyList()

                    if (retry.isEmpty()) {
                        ui.setOrbVisibility(true)
                        hablar("No detecté elementos en pantalla.") {
                            isProcessing = false
                            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
                        }
                        return@withContext
                    }

                    // Segunda captura exitosa
                    mostrarOverlayNumeros(retry)
                } else {
                    mostrarOverlayNumeros(elementos)
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
            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
        }
    }
    private fun desactivarModoVisual() {
        modoVisualActivo = false
        numberedOverlay?.ocultar()
        mainHandler.post { ui.setOrbVisibility(true) }
        hablar("Modo visual desactivado.") {
            isProcessing = false
            if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
        }
    }

    private fun ejecutarClickNumerico(numero: Int) {
        val overlay = numberedOverlay
        if (overlay == null || !overlay.estaVisible()) {
            hablar("El modo visual no está listo.") {
                isProcessing = false
                if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
            }
            return
        }

        val elemento = overlay.obtenerPorNumero(numero)
        if (elemento == null) {
            hablar("No encuentro el número $numero.") {
                isProcessing = false
                if (sesionActiva) sessionManager.onAssistantFinishedSpeaking()
            }
            return
        }

        Log.d(TAG, "🖱️ CLICK en #$numero: x=${elemento.centerX} y=${elemento.centerY}")

        // ✅ IMPORTANTE: Ejecuta el tap DIRECTAMENTE sin pasar por acciones
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
        Log.d("ACCESS_FLOW", "📤 Enviando ${actions.size} acciones por broadcast...")
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
        Log.d("ACCESS_FLOW", "✅ Broadcast enviado a MyAccessibilityService")
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
        stopListeningCompletamente()

        if (::tts.isInitialized && ttsListo) tts.shutdown()
        if (::audioEngine.isInitialized) audioEngine.stop()
        if (::hybridTranscriber.isInitialized) hybridTranscriber.destroy()

        numberedOverlay?.ocultar()
        mainHandler.removeCallbacksAndMessages(null)

        runCatching { context.unregisterReceiver(confirmacionReceiver) }
        runCatching { context.unregisterReceiver(orbHideReceiver) }
        runCatching { context.unregisterReceiver(wakeWordReceiver) }

        Log.d(TAG, "✅ Controlador destruido")
    }
}