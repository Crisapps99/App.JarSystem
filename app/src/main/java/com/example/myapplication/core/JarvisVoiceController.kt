package com.example.myapplication.core

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
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

// ══════════════════════════════════════════════════════════════
//  CONFIGURACIÓN ELEVEN LABS — edita solo aquí
// ══════════════════════════════════════════════════════════════
object ElevenLabsConfig {
    // 1. Tu API Key de ElevenLabs (https://elevenlabs.io → Profile → API Key)
    const val API_KEY = "9dcae6842f3e53c4f885e4dcf30bf5635e8284c41df98d93f8b432b5f4383e90"

    // 2. Voice ID — copia el ID de la voz desde el panel de ElevenLabs
    //    Ejemplo de voces en español: "pNInz6obpgDQGcFmaJgB" (Adam), "EXAVITQu4vr4xnSDxMaL" (Bella)
    //    O entra a https://elevenlabs.io/voice-library y filtra por Spanish
    const val VOICE_ID = "dQ0C8BEdKF2odmELvNee"

    // 3. Modelo — usa "eleven_multilingual_v2" para español de calidad
    const val MODEL_ID = "eleven_multilingual_v2"

    // 4. Parámetros de voz (0.0 - 1.0)
    const val STABILITY        = 0.5f   // más alto = más estable/monótono
    const val SIMILARITY_BOOST = 0.75f  // más alto = más parecido a la voz original
    const val STYLE            = 0.0f   // estilo expresivo (0 = neutro)
    const val SPEAKER_BOOST    = true   // mejora la claridad del hablante

}

// ══════════════════════════════════════════════════════════════
//  MODO TTS — cambia esta línea para alternar entre motores
// ══════════════════════════════════════════════════════════════
enum class TtsMode { ANDROID, ELEVEN_LABS }

// ▼▼▼ CAMBIA AQUÍ PARA ALTERNAR MOTOR ▼▼▼
private val TTS_MODE = TtsMode.ELEVEN_LABS
// ▲▲▲ ELEVEN_LABS o ANDROID              ▲▲▲


class JarvisVoiceController(
    private val context: Context,
    private val ui: JarvisUi,
    private val scope: CoroutineScope
) : RecognitionListener {

    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private lateinit var audioManager: com.example.myapplication.core.AudioManager
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private var visualizer: android.media.audiofx.Visualizer? = null

    // ── TTS Android ──────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsListo = false

    // ── ElevenLabs ───────────────────────────────────────────
    private val elevenLabsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var mediaPlayer: MediaPlayer? = null

    // ── Estado ────────────────────────────────────────────────
    private var isListening = false
    private var isProcessing = false
    private var modoVisualActivo = false
    private var esperandoFinTTS = false
    private var esperandoConfirmacion = false
    private var ttsTerminoTimestamp: Long = 0L
    private val ECO_WINDOW_MS = 2000L
    private var confirmacionReceiver: BroadcastReceiver? = null
    private var numberedOverlay: NumberedElementsOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d("JARVIS_DEBUG", "⏱️ 10s sin hablar — cerrando micro")
        stopListeningCompletamente()
    }

    // ─────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────
    fun init() {
        audioManager = AudioManager(context)
        configurarReconocedor()
        configurarTtsAndroid()
        setState(JarvisState.IDLE)
        registrarReceptorConfirmacion()
    }

    private fun configurarReconocedor() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer.setRecognitionListener(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun configurarTtsAndroid() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("es", "ES"))
                ttsListo = true
                Log.d("JARVIS_TTS", "✅ TTS Android listo")
            } else {
                Log.e("JARVIS_TTS", "❌ Error iniciando TTS Android: $status")
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // TTS CENTRAL — router entre motores
    // ─────────────────────────────────────────────────────────
    private fun hablar(texto: String, alTerminar: (() -> Unit)? = null) {
        when (TTS_MODE) {
            TtsMode.ANDROID     -> hablarConAndroid(texto, alTerminar)
            TtsMode.ELEVEN_LABS -> hablarConElevenLabs(texto, alTerminar)
        }
    }

    // ── Método público para que JarActivity use el motor correcto ──
    fun hablarDesdeActivity(texto: String, alTerminar: (() -> Unit)? = null) {
        hablar(texto, alTerminar)
    }

    // ── Motor 1: Android TTS ─────────────────────────────────
    private fun hablarConAndroid(texto: String, alTerminar: (() -> Unit)? = null) {
        if (!ttsListo) {
            Log.w("JARVIS_TTS", "⚠️ TTS Android no listo")
            alTerminar?.invoke() ?: abrirMicro()
            return
        }
        setState(JarvisState.SPEAKING)
        val id = "UTT_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                // La animación iniciará cuando el motor de Android realmente empiece a emitir sonido
                iniciarAnimacionOrbeSimulada()
            }
            override fun onDone(id: String?) {
                mainHandler.post {
                    setState(JarvisState.IDLE)
                    ui.updateORB(0f)
                    alTerminar?.invoke() ?: abrirMicro()

                }
            }
            override fun onError(id: String?) {
                mainHandler.post {
                    Log.e("JARVIS_TTS", "Error TTS utterance")
                    setState(JarvisState.IDLE)
                    alTerminar?.invoke() ?: abrirMicro()
                }
            }
            // Iniciar simulación de orbe para Android TTS
            val jobAnim = scope.launch {
                while (esperandoFinTTS || tts.isSpeaking) {
                    val fakeRms = (2f + Math.random().toFloat() * 8f) // Genera valores entre 2 y 10
                    withContext(Dispatchers.Main) { ui.updateORB(fakeRms) }
                    delay(80)
                }
                withContext(Dispatchers.Main) { ui.updateORB(0f) }
            }
        })
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
    }
    // Función separada para limpiar el código principal
    private fun iniciarAnimacionOrbeSimulada() {
        scope.launch {
            var contador = 0
            while (esperandoFinTTS || tts.isSpeaking) {
                // 1. Calculamos una base de ruido (vibración constante)
                val baseRuido = 3f

                // 2. Generamos un "impulso" aleatorio más fuerte (de 0 a 12)
                // Usamos sin() para que el movimiento sea más fluido y no tan entrecortado
                val impulso = (Math.sin(contador.toDouble() * 0.5).toFloat() * 5f) + 5f

                // 3. Añadimos un pico de "agresividad" aleatorio
                val picoAleatorio = if (Math.random() > 0.8) 4f else 0f

                val rmsFinal = (baseRuido + impulso + picoAleatorio).coerceIn(0f, 15f)

                withContext(Dispatchers.Main) {
                    ui.updateORB(rmsFinal)
                }

                contador++
                delay(50) // Bajamos a 50ms para que sea más reactivo/nervioso
            }

            // Al terminar, desinflamos el orbe suavemente
            withContext(Dispatchers.Main) {
                ui.updateORB(0f)
            }
        }
    }

    // ── Motor 2: ElevenLabs TTS ──────────────────────────────
    private fun hablarConElevenLabs(texto: String, alTerminar: (() -> Unit)? = null) {
        Log.d("ELEVEN_LABS", "▶️ hablarConElevenLabs() — texto: '$texto'")
        Log.d("ELEVEN_LABS", "   API_KEY  = '${ElevenLabsConfig.API_KEY.take(8)}...'")
        Log.d("ELEVEN_LABS", "   VOICE_ID = '${ElevenLabsConfig.VOICE_ID}'")

        setState(JarvisState.SPEAKING)

        // Scope propio con IO — no depende del lifecycleScope del caller
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("ELEVEN_LABS", "🌐 Iniciando llamada HTTP...")

                val bodyJson = JSONObject().apply {
                    put("text", texto)
                    put("model_id", ElevenLabsConfig.MODEL_ID)
                    put("voice_settings", JSONObject().apply {
                        put("stability",         ElevenLabsConfig.STABILITY)
                        put("similarity_boost",  ElevenLabsConfig.SIMILARITY_BOOST)
                        put("style",             ElevenLabsConfig.STYLE)
                        put("use_speaker_boost", ElevenLabsConfig.SPEAKER_BOOST)
                    })
                }.toString()

                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/${ElevenLabsConfig.VOICE_ID}")
                    .addHeader("xi-api-key", ElevenLabsConfig.API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/mpeg")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                Log.d("ELEVEN_LABS", "📡 URL: ${request.url}")

                val response = elevenLabsClient.newCall(request).execute()
                Log.d("ELEVEN_LABS", "📥 HTTP ${response.code} ${response.message}")

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "sin cuerpo"
                    Log.e("ELEVEN_LABS", "❌ Error HTTP ${response.code}: $errorBody")
                    withContext(Dispatchers.Main) { hablarConAndroid(texto, alTerminar) }
                    return@launch
                }

                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    Log.e("ELEVEN_LABS", "❌ Respuesta vacía")
                    withContext(Dispatchers.Main) { hablarConAndroid(texto, alTerminar) }
                    return@launch
                }

                Log.d("ELEVEN_LABS", "✅ Audio: ${audioBytes.size} bytes")

                val tempFile = java.io.File(context.cacheDir, "eleven_labs_audio.mp3")
                tempFile.writeBytes(audioBytes)

                withContext(Dispatchers.Main) {
                    Log.d("ELEVEN_LABS", "🔊 Reproduciendo...")
                    reproducirAudioElevenLabs(tempFile, alTerminar)
                }

            } catch (e: java.net.UnknownHostException) {
                Log.e("ELEVEN_LABS", "❌ Sin internet/DNS: ${e.message}")
                withContext(Dispatchers.Main) { hablarConAndroid(texto, alTerminar) }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("ELEVEN_LABS", "❌ Timeout: ${e.message}")
                withContext(Dispatchers.Main) { hablarConAndroid(texto, alTerminar) }
            } catch (e: Exception) {
                Log.e("ELEVEN_LABS", "❌ ${e::class.simpleName}: ${e.message}", e)
                withContext(Dispatchers.Main) { hablarConAndroid(texto, alTerminar) }
            }
        }
    }

    private fun reproducirAudioElevenLabs(file: java.io.File, alTerminar: (() -> Unit)?) {
        try {
            mediaPlayer?.release()
            visualizer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                // --- CONEXIÓN AL ORBE ---
                val audioSessionId = audioSessionId
                visualizer = android.media.audiofx.Visualizer(audioSessionId).apply {
                    captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, sRate: Int) {
                            if (wave != null) {
                                // Calculamos la energía de la onda
                                val rms = calculateRMSFromByte(wave)
                                mainHandler.post { ui.updateORB(rms) }
                            }
                        }
                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, sRate: Int) {}
                    }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false)
                    enabled = true
                }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    setState(JarvisState.IDLE)
                    alTerminar?.invoke() ?: abrirMicro()
                    Log.d("ELEVEN_LABS", "✅ Audio reproducido correctamente")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("ELEVEN_LABS", "❌ Error MediaPlayer: what=$what extra=$extra")
                    release()
                    mediaPlayer = null
                    hablarConAndroid(file.readText(), alTerminar) // fallback
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("ELEVEN_LABS", "❌ Error reproduciendo: ${e.message}")
            hablarConAndroid("Lo siento, hubo un error de audio.", alTerminar)
        }
    }
    // Función auxiliar para convertir bytes de audio en valor para el orbe
    private fun calculateRMSFromByte(wave: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until wave.size) {
            val sample = (wave[i].toInt() and 0xFF) - 128
            sum += (sample * sample).toDouble()
        }
        val rms = Math.sqrt(sum / wave.size).toFloat()
        return (rms / 5f).coerceIn(0f, 15f) // Ajusta el divisor para más/menos sensibilidad
    }
    // ─────────────────────────────────────────────────────────
    // SILENCIAR SISTEMA
    // ─────────────────────────────────────────────────────────
    private fun silenciarSonidoSistema(silenciar: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val modo = if (silenciar) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE
        manager.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, modo, 0)
    }

    // ─────────────────────────────────────────────────────────
    // RECEPTOR DE CONFIRMACIÓN
    // ─────────────────────────────────────────────────────────
    private fun registrarReceptorConfirmacion() {
        confirmacionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != "JARVIS.PEDIR_CONFIRMACION") return
                if (esperandoConfirmacion || esperandoFinTTS) {
                    Log.w("JARVIS_CONFIRM", "⚠️ Confirmación ya activa — ignorando duplicado")
                    return
                }
                val pregunta = intent.getStringExtra("pregunta") ?: "¿Enviar mensaje?"
                try { speechRecognizer.cancel() } catch (_: Exception) {}
                isListening = false
                timeoutHandler.removeCallbacks(timeoutRunnable)
                esperandoFinTTS = true
                esperandoConfirmacion = false
                hablar(pregunta) {
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    esperandoFinTTS = false
                    esperandoConfirmacion = true
                    mainHandler.postDelayed({ abrirMicro() }, ECO_WINDOW_MS)
                }
            }
        }
        context.registerReceiver(
            confirmacionReceiver,
            IntentFilter("JARVIS.PEDIR_CONFIRMACION"),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    // ─────────────────────────────────────────────────────────
    // CONTROL MICRO
    // ─────────────────────────────────────────────────────────
    private fun abrirMicro() {
        mainHandler.post {
            if (esperandoFinTTS) {
                Log.w("JARVIS_MIC", "⚠️ TTS activo — micro bloqueado")
                return@post
            }
            try {
                silenciarSonidoSistema(true)
                speechRecognizer.cancel()
                speechRecognizer.startListening(recognizerIntent)
                audioManager.playMicOn()
                isListening = true
                setState(JarvisState.LISTENING)
                iniciarTemporizador()
            } catch (e: Exception) {
                silenciarSonidoSistema(false)
                Log.e("JARVIS_MIC", "Error al abrir micro: ${e.message}")
            }
        }
    }

    private fun stopListeningCompletamente() {
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        audioManager.playMicOff()
        isListening = false
        isProcessing = false
        esperandoFinTTS = false
        esperandoConfirmacion = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        setState(JarvisState.IDLE)
    }

    private fun iniciarTemporizador() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, 10000L)
    }

    // ─────────────────────────────────────────────────────────
    // MODO VISUAL
    // ─────────────────────────────────────────────────────────
    private fun activarModoVisual() {
        val elementos = ScreenMemory.lastSnapshot?.elements ?: emptyList()
        if (elementos.isEmpty()) { hablar("No detecto elementos en esta pantalla."); return }
        // 1. Ocultar el Orbe antes de mostrar los números
        mainHandler.post { ui.setOrbVisibility(false) }
        if (numberedOverlay == null) numberedOverlay = NumberedElementsOverlay(context)
        modoVisualActivo = true
        numberedOverlay!!.mostrar(elementos)
        val cantidad = numberedOverlay!!.cantidadElementos()
        hablar("Modo visual. $cantidad elementos. Di el número.")
    }

    private fun desactivarModoVisual() {
        modoVisualActivo = false
        numberedOverlay?.ocultar()
        mainHandler.post { ui.setOrbVisibility(true) }
        hablar("Modo visual desactivado.")
    }

    private fun ejecutarClickNumerico(numero: Int) {
        val overlay = numberedOverlay
        if (overlay == null || !overlay.estaVisible()) { hablar("Primero activa el modo visual."); return }
        val elemento = overlay.obtenerPorNumero(numero)
        if (elemento == null) { hablar("No existe el $numero. Hay ${overlay.cantidadElementos()} elementos."); return }
        val accion = ActionDto(tipo = "tap", params = mapOf("x" to elemento.centerX, "y" to elemento.centerY))
        ejecutarAccionesTecnicas(listOf(accion), "toca el $numero", "click_numerico")
        esperandoFinTTS = true
        try { speechRecognizer.cancel() } catch (_: Exception) {}
        isListening = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        hablar("$numero") {
            esperandoFinTTS = false
            if (modoVisualActivo) {
                ui.setOrbVisibility(false)
                mainHandler.postDelayed({
                    actualizarOverlayVisual()
                    abrirMicro()
                }, 800)
            } else {
                abrirMicro()
            }
        }
    }
    private fun actualizarOverlayVisual() {
        // Obtenemos el snapshot más reciente de la memoria de pantalla
        // Nota: Asegúrate que tu AccessibilityService esté actualizando 'ScreenMemory.lastSnapshot' constantemente
        val nuevoSnapshot = ScreenMemory.lastSnapshot

        val elementos = nuevoSnapshot?.elements ?: emptyList()

        if (elementos.isNotEmpty()) {
            numberedOverlay?.mostrar(elementos)
            Log.d("JARVIS_VISUAL", "Overlay actualizado con ${elementos.size} elementos")
        } else {
            // Si no hay elementos (pantalla de carga o error), desactivamos para no estorbar
            Log.w("JARVIS_VISUAL", "No se detectaron elementos nuevos, cerrando modo visual")
            desactivarModoVisual()
        }
    }
    fun interceptarComandoVisual(texto: String): Boolean {
        val t = texto.lowercase().trim()
        val salidas = listOf("salir de modo visual", "salir modo visual", "desactivar modo visual",
            "cerrar modo visual", "quitar modo visual", "modo normal",
            "ocultar números", "ocultar numeros", "quita los números", "quita numeros")
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
        val t = texto.lowercase().trim()
        Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val palabras = mapOf(
            "uno" to 1, "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
            "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
            "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciséis" to 16, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
            "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21,
            "veintidós" to 22, "veintidos" to 22, "veintitrés" to 23, "veintitres" to 23,
            "veinticuatro" to 24, "veinticinco" to 25,
            "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50
        )
        for ((palabra, numero) in palabras) { if (t.contains(palabra)) return numero }
        return null
    }

    // ─────────────────────────────────────────────────────────
    // FLUJO PRINCIPAL
    // ─────────────────────────────────────────────────────────
    fun activarDesdeWakeWord() { if (!isListening) abrirMicro() }

    fun startInteraction() {
        isListening = true
        setState(JarvisState.SPEAKING)
        obtenerSaludoGemma { saludo ->
            val txt = saludo ?: "Listo, te escucho."
            ui.showText(txt)
            hablar(txt)
        }
    }

    fun toggleMic() {
        val granted = ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) { ui.showText("permiso de microfono denegado"); return }
        if (isListening) stopListeningCompletamente() else startInteraction()
    }

    fun procesarComandoExterno(comando: String) {
        ui.showText(comando)
        if (interceptarComandoVisual(comando)) return
        enviarComandoAlServidor(comando)
    }

    // ─────────────────────────────────────────────────────────
    // SPEECH RECOGNIZER CALLBACKS
    // ─────────────────────────────────────────────────────────
    override fun onResults(results: Bundle?) {
        if (esperandoFinTTS) {
            val eco = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            Log.d("JARVIS_ECO", "⚠️ Ignorado (TTS activo): '$eco'")
            return
        }
        val textoEscuchado = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim() ?: ""

        if (esperandoConfirmacion) {
            val ahora = System.currentTimeMillis()
            val tiempoDesdeFinTTS = ahora - ttsTerminoTimestamp
            if (tiempoDesdeFinTTS < ECO_WINDOW_MS) {
                Log.d("JARVIS_CONFIRM", "⚠️ Eco ignorado (${tiempoDesdeFinTTS}ms): '$textoEscuchado'")
                return
            }
            esperandoConfirmacion = false
            isListening = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            val texto = textoEscuchado.lowercase().trim()
            val ecoTardio = listOf("mensaje", "enviar el", "quieres", "seguro", "para enviar")
                .any { texto.contains(it) }
            if (ecoTardio) {
                esperandoConfirmacion = true
                esperandoFinTTS = true
                hablar("¿Sí o no?") {
                    ttsTerminoTimestamp = System.currentTimeMillis()
                    esperandoFinTTS = false
                    mainHandler.postDelayed({ abrirMicro() }, ECO_WINDOW_MS)
                }
                isProcessing = false
                return
            }
            val esAfirmativo = listOf("sí", "si", "claro", "dale", "ok", "okay",
                "envía", "envia", "manda", "confirmo", "adelante", "yes", "va", "bueno")
                .any { texto == it || texto.startsWith(it) }
            val esNegativo = listOf("no", "cancela", "cancel", "para", "detente",
                "no mandes", "mejor no", "no quiero")
                .any { texto == it || texto.startsWith(it) }
            when {
                esAfirmativo -> {
                    val cb = ActionExecutor.onConfirmacionPendiente
                    ActionExecutor.onConfirmacionPendiente = null
                    try { speechRecognizer.cancel() } catch (_: Exception) {}
                    isListening = false
                    esperandoFinTTS = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    hablar("Enviando.") {
                        esperandoFinTTS = false
                        cb?.invoke(true)
                    }
                }
                esNegativo -> {
                    val cb = ActionExecutor.onConfirmacionPendiente
                    ActionExecutor.onConfirmacionPendiente = null
                    try { speechRecognizer.cancel() } catch (_: Exception) {}
                    isListening = false
                    esperandoFinTTS = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    cb?.invoke(false)
                    hablar("Cancelado.") {
                        esperandoFinTTS = false
                        abrirMicro()
                    }
                }
                else -> {
                    esperandoConfirmacion = true
                    esperandoFinTTS = true
                    hablar("¿Sí o no?") {
                        ttsTerminoTimestamp = System.currentTimeMillis()
                        esperandoFinTTS = false
                        mainHandler.postDelayed({ abrirMicro() }, ECO_WINDOW_MS)
                    }
                }
            }
            isProcessing = false
            return
        }

        if (textoEscuchado.isEmpty()) { isProcessing = false; abrirMicro(); return }
        if (isProcessing) return
        isProcessing = true
        isListening = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        ui.showText(textoEscuchado)
        if (interceptarComandoVisual(textoEscuchado)) { isProcessing = false; return }
        enviarComandoAlServidor(textoEscuchado)
    }

    // ─────────────────────────────────────────────────────────
    // SERVIDOR
    // ─────────────────────────────────────────────────────────
    private fun enviarComandoAlServidor(texto: String) {
        scope.launch {
            setState(JarvisState.THINKING)
            val snapshot = ScreenMemory.lastSnapshot
            val contextoDetallado = snapshot?.elements
                ?.filter { it.isClickable || it.isEditable || it.isScrollable || it.importance > 50 }
                ?.sortedByDescending { it.importance }
                ?.take(30)
                ?.map { it.toDto() } ?: emptyList()

            val metadata = mapOf(
                "packageName"     to (snapshot?.packageName   ?: "unknown"),
                "activityName"    to (snapshot?.activityName  ?: "unknown"),
                "totalElements"   to (snapshot?.totalElements   ?: 0),
                "clickableCount"  to (snapshot?.clickableElements ?: 0),
                "editableCount"   to (snapshot?.editableElements  ?: 0),
                "scrollableCount" to (snapshot?.scrollableContainers ?: 0),
                "timestamp"       to System.currentTimeMillis(),
                "contacts"        to ContactsManager.toContextText(context),
                "messagingApps"   to MessagingManager.toContextText(context),
                "notifications"   to NotificationMemory.toContextText()
            )

            try {
                val response = actionApiService.predictActionEnriquecido(
                    ActionRequestEnriquecido(texto, emptyList(), contextoDetallado, metadata)
                )
                if (response.success) {
                    ui.showText(response.response_text)
                    val esAccionTecnica = response.mode == "COMMAND" || response.mode == "DYNAMIC_ACTION"
                    if (esAccionTecnica && !response.payload.isNullOrEmpty()) {
                        ejecutarAccionesTecnicas(response.payload, texto, response.action ?: "unknown")
                    }
                    hablar(response.response_text)
                } else {
                    hablar("Tuve un problema: ${response.response_text}")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_API", "❌ Error: ${e.message}", e)
                hablar("Lo siento, no puedo conectar con mis sistemas.")
            } finally {
                isProcessing = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // RECOGNIZER CALLBACKS RESTANTES
    // ─────────────────────────────────────────────────────────
    override fun onReadyForSpeech(p0: Bundle?) { mainHandler.post { ui.onRecognizerReady() } }
    override fun onBeginningOfSpeech() { timeoutHandler.removeCallbacks(timeoutRunnable) }
    override fun onRmsChanged(rms: Float) {
        // El valor 'rms' del SpeechRecognizer suele ser bajo (-2 a 10)
        // Lo normalizamos para que el orbe se infle bien
        val normalizedRms = (rms + 2f).coerceIn(0f, 12f)
        mainHandler.post { ui.updateORB(normalizedRms) }
    }
    override fun onPartialResults(p0: Bundle?) {
        if (esperandoFinTTS) return
        val m = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!m.isNullOrEmpty()) ui.showText(m[0])
    }
    override fun onError(p0: Int) {
        Log.e("JARVIS_DEBUG", "Error Speech: $p0")
        audioManager.playMicOff()
        isProcessing = false
        isListening = false
        esperandoFinTTS = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        setState(JarvisState.IDLE)
        if (esperandoConfirmacion) {
            mainHandler.postDelayed({ abrirMicro() }, 500)
        }
    }
    override fun onBufferReceived(p0: ByteArray?) {}
    override fun onEndOfSpeech() {
        audioManager.playMicOff()
    }
    override fun onEvent(p0: Int, p1: Bundle?) {}

    // ─────────────────────────────────────────────────────────
    // UTILIDADES
    // ─────────────────────────────────────────────────────────
    private fun ejecutarAccionesTecnicas(actions: List<ActionDto>, textoOriginal: String, intencion: String) {
        val intent = Intent(ACTION_EXECUTE).apply {
            putExtra("actions_json", Gson().toJson(actions))
            putExtra("texto_original", textoOriginal)
            putExtra("intencion_original", intencion)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
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

    private fun setState(s: JarvisState) { ui.renderState(s) }

    fun destroy() {
        if (::tts.isInitialized && ttsListo) tts.shutdown()
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        numberedOverlay?.ocultar()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        runCatching { context.unregisterReceiver(confirmacionReceiver) }
    }
}