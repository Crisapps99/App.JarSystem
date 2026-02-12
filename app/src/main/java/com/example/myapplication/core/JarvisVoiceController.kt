package  com.example.myapplication.core

import android.Manifest
import android.R
import android.R.attr.action
import android.R.attr.text
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.myapplication.api.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Protocol
import java.util.Locale
import android.os.Handler
import android.os.Looper
import com.example.myapplication.activity.ActionExecutor
import kotlinx.coroutines.Dispatchers
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

//estados de jarvis
enum class JarvisState{IDLE, LISTENING, THINKING, SPEAKING}

//PUERTA ENTRE CONTROLADOR Y ACTIVITY solo llamamos a estyos metodos
interface  JarvisUi{
    fun renderState(state: JarvisState)//cambiar animaciones iconos
    fun showText(text: String)///mostrar textto transcrito
    fun showToast(text:String)//Toast
    fun getCurrentScreenText(): List<String> // para obtener el contexto de la pantalla
    fun onRecognizerReady()
    fun updateORB(rms: Float)
}
//controlador principa
class JarvisVoiceController(
    private val context: Context,
    private val ui: JarvisUi,
    private val scope: CoroutineScope
): RecognitionListener{
    //predice la accion
    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private  val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent//coniuracion dde speechRecognizer idioma partial result
    private lateinit var tts: TextToSpeech//motor de voz para hablar
    private var isListening = false//para saber si esta en modo escucha
    private var isProcessing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable{
        Log.d("JARVIS_DEBUG", "10 segundos de silencio, cerrando micro.")
        stopListening()
    }
    private val ELEVEN_LABS_API_KEY = "sk_275d41153da7f3f34a94dfe4ff9636271a1dc92db0f3e23e"
    private val VOICE_ID = "dQ0C8BEdKF2odmELvNee"

    private fun speakWithElevenLabs(text: String) {
        Log.d("ELEVEN_DEBUG", "Iniciando proceso para: $text")
        //cambiamos el estado de la ui
        setState(JarvisState.THINKING)
        //ejecuta un hilo secundario(IO) PARA NO BLOQUEAR LA PANTALLA
        scope.launch(Dispatchers.IO) {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

            //construccion del cuarpo de la paticion JSON
            val json = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75) //parecido al avzo original
                })
            }
            //conf par arequest HTTP
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", ELEVEN_LABS_API_KEY)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
//                Log.d("ELEVEN_DEBUG", "Enviando petición a ElevenLabs...")
                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->

                    Log.d("ELEVEN_DEBUG", "Respuesta recibida. Código: ${response.code}")

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Log.e("ELEVEN_DEBUG", "Error de API: $errorBody")
                        throw Exception("Fallo ElevenLabs: ${response.code}")
                    }

                    // Verificamos si recibimos datos de audio
                    val audioBytes = response.body?.bytes()
                    if (audioBytes == null || audioBytes.isEmpty()) {
                        Log.e("ELEVEN_DEBUG", "El cuerpo de la respuesta está vacío")
                        return@launch
                    }
                    Log.d("ELEVEN_DEBUG", "Audio descargado: ${audioBytes.size} bytes")

                    val tempFile = File.createTempFile("jarvis_voice", ".mp3", context.cacheDir)
                    tempFile.writeBytes(audioBytes)
                    Log.d("ELEVEN_DEBUG", "Archivo guardado en: ${tempFile.absolutePath}")

                    mainHandler.post {
                        Log.d("ELEVEN_DEBUG", "Enviando archivo al MediaPlayer")
                        playJarvisVoiceFromFile(tempFile)
                    }
                }
            } catch (e: Exception) {
                Log.e("ELEVEN_DEBUG", "EXCEPCIÓN: ${e.message}")
                mainHandler.post {
                    ui.showToast("Error ElevenLabs")
                    speakAndWait(text) { startListening() }
                }
            }
        }
    }
    private fun speakWithAndroidTTS(text: String) {
        Log.d("JARVIS_TTS", "Usando motor local de Android")
        setState(JarvisState.SPEAKING)

        val utteranceId = "UTT_${System.currentTimeMillis()}"

        // Listener para saber cuando termina de hablar y volver a escuchar
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                // El visualizer en onRmsChanged se encarga de mover el orbe con el TTS de Android
            }
            override fun onDone(id: String?) {
                mainHandler.post {
                    setState(JarvisState.IDLE)
                    startListening()
                    iniciarTemporizador()
                }
            }
            override fun onError(id: String?) {
                mainHandler.post { startListening() }
            }
        })

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    private fun playJarvisVoiceFromFile(file: File) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.prepareAsync()

        mediaPlayer.setOnPreparedListener { mp ->
            setState(JarvisState.SPEAKING)

            // Configurar el Visualizador para mover el orbe
            val visualizer = Visualizer(mp.audioSessionId)
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    val rms = calculateRmsFromWaveform(waveform)
                    mainHandler.post { ui.updateORB(rms) }
                }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)

            visualizer.enabled = true
            mp.start()

            mp.setOnCompletionListener {
                visualizer.enabled = false
                visualizer.release()
                mp.release()
                if (file.exists()) file.delete()
                // Cambiamos estado y volvemos a escuchar
                setState(JarvisState.LISTENING)
                mainHandler.postDelayed({
                    startListening()
                    iniciarTemporizador()
                }, 500)
            }
        }
    }

    fun init(){
        configurarReconocedor()
        configurarTts()
        setState(JarvisState.IDLE)
    }
    //boton del microfono
    fun toggleMic(){
        //verificand permisos del record_audio
        val granted = ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)==
                PackageManager.PERMISSION_GRANTED
        if (!granted){
            ui.showText("permiso de microfono denegado")
            return
        }
        //si etsa escuchando se detiene
        //si no escucha inicia inteaccon completa
        if (isListening) stopListening() else startInteraction()
    }

    //speech + tts
    fun startInteraction(){
        isListening = true
        setState(JarvisState.SPEAKING)

        //lamada al servido r
        obtenerSaludoGemma { saludoAleatorio ->
            //so falal servidor envia saludo estatico
            val saludo = saludoAleatorio?: "Un momento, Nuestro servidor está en proceso "
            //muetsra el saludo
            Log.d("JARVIS_DEBUG","Recibido de G2: $saludo")
            ui.showToast("G2 dice: $saludo")
            ui.showText(saludo)

            //jarvis hhabla y espera a que termine de decir la frase
            speakWithAndroidTTS (saludo)

        }
    }
    //funcion del saludo
    private fun obtenerSaludoGemma(callback:(String?)->Unit){
        scope.launch {
            try {
                val response = actionApiService.regards()
                Log.i("JARVIS_API", "$response")
                mainHandler.postAtFrontOfQueue{
                    callback(response.saludo)
                }
            }catch (e: Exception){
                Log.e("JARVIS", "Error al obtener saludo: ${e.message}")
                // Devolvemos 'null' para que la app sepa que falló y use el saludo por defecto.
                mainHandler.post { callback(null) }
            }
        }
    }
    //arranca el reconocimiento de voz
    private fun startListening() {
        mainHandler.post {
            try {
                // Cancelamos cualquier sesión previa antes de iniciar una nueva
                speechRecognizer.cancel()
                speechRecognizer.startListening(recognizerIntent)
                isListening = true
                Log.d("JARVIS_DEBUG", "Micro abierto y escuchando...")
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Error al abrir micro: ${e.message}")
                setState(JarvisState.IDLE)
            }
        }
    }
    //detiene el reocnocimiento de voz
    private fun stopListening() {
        speechRecognizer.stopListening()
        isListening = false
        setState(JarvisState.IDLE)
    }
    //setter centranizado apra el estado
    private fun setState(s: JarvisState){
        ui.renderState(s)
    }
//    private fun resetToListening() {
//        isProcessing = false // ¡IMPORTANTE! Liberamos el proceso
//        setState(JarvisState.IDLE)
//        startListening()
//        iniciarTemporizador()
//    }
    //resultado final cuando el suario termina de hablar
override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val textoEscuchado = matches?.firstOrNull()?.trim() ?: ""
    if (textoEscuchado.isEmpty()) {
        isProcessing = false
        startListening()
        return
    }

    if (isProcessing) return
    isProcessing = true
    isListening = false

    ui.showText(textoEscuchado)

    scope.launch {
        setState(JarvisState.THINKING)

        // ═══════════════════════════════════════════════════════════════
        // 🔍 EXTRACCIÓN DE CONTEXTO ENRIQUECIDO
        // ═══════════════════════════════════════════════════════════════

        // Obtener snapshot actual de la pantalla
        val snapshot = com.example.myapplication.core.ScreenMemory.lastSnapshot

        // Contexto simple (compatibilidad)
        val contextoSimple = ui.getCurrentScreenText()

        // Contexto detallado (nuevo) - solo si hay snapshot disponible
        val contextoDetallado = snapshot?.elements?.map { elem ->
            ElementoDetalladoDto(
                text = elem.text ?: elem.contentDescription ?: "",
                x = elem.centerX,
                y = elem.centerY,
                clickable = elem.isClickable,
                editable = elem.isEditable,
                type = elem.className ?: "view",
                importance = elem.importance,
                actions = elem.availableActions
            )
        }

        Log.d("JARVIS_DEBUG", "🎙️ Usuario dijo: $textoEscuchado")
        Log.d("JARVIS_DEBUG", "📱 Contexto simple: ${contextoSimple.size} elementos")
        Log.d("JARVIS_DEBUG", "📊 Contexto detallado: ${contextoDetallado?.size ?: 0} elementos")

        // Log de muestra de elementos detectados
        contextoDetallado?.take(5)?.forEach { elem ->
            Log.d("JARVIS_DEBUG", "  └─ ${elem.text} [${elem.type}] ${if(elem.clickable) "✅" else "❌"}")
        }

        try {
            // ═══════════════════════════════════════════════════════════
            // 🚀 LLAMADA AL SERVIDOR (Con fallback automático)
            // ═══════════════════════════════════════════════════════════

            val response = if (contextoDetallado != null && contextoDetallado.isNotEmpty()) {
                // VERSIÓN ENRIQUECIDA: Enviar contexto completo
                Log.d("JARVIS_API", "📤 Enviando request ENRIQUECIDO")
                val request = ActionRequestEnriquecido(
                    texto = textoEscuchado,
                    contexto = contextoSimple,
                    contextoDetallado = contextoDetallado
                )
                actionApiService.predictActionEnriquecido(request)
            } else {
                // FALLBACK: Versión básica si no hay contexto detallado
                Log.d("JARVIS_API", "📤 Enviando request BÁSICO (fallback)")
                val request = ActionRequest(
                    texto = textoEscuchado,
                    contexto = contextoSimple
                )
                actionApiService.predictAction(request)
            }


            // PROCESAMIENTO DE RESPUESTA
          

            Log.d("JARVIS_SERVER", "✅ Success: ${response.success}")
            Log.d("JARVIS_SERVER", "🎭 Modo: ${response.mode}")
            Log.d("JARVIS_SERVER", "💬 Texto de Voz: ${response.response_text}")

            response.payload?.let { listaAcciones ->
                Log.d("JARVIS_SERVER", "📦 Payload: ${listaAcciones.size} acciones")
                listaAcciones.forEachIndexed { index, accion ->
                    Log.d("JARVIS_SERVER", "  [$index] ${accion.tipo} → ${accion.params}")
                }
            } ?: Log.d("JARVIS_SERVER", "📦 Payload: VACÍO (modo chat)")

            if (response.success) {
                ui.showText(response.response_text)

                val intencionDetectada = response.action ?: response.mode ?: "intent_desconocido"
                val payloadSeguro = response.payload

                // 1. EJECUTAR ACCIONES TÉCNICAS (si las hay)
                val esAccionTecnica = response.mode == "COMMAND" || response.mode == "DYNAMIC_ACTION"
                if (esAccionTecnica && !payloadSeguro.isNullOrEmpty()) {
                    Log.d("JARVIS_SERVER", "⚡ Ejecutando ${payloadSeguro.size} acciones técnicas")
                    ejecutarAccionesTecnicas(payloadSeguro, textoEscuchado, intencionDetectada)
                }

                // 2. HABLAR LA RESPUESTA
                speakWithAndroidTTS(response.response_text)

                // 3. LIBERAR ESTADO
                isProcessing = false

            } else {
                // Error del servidor
                ui.showText("Error: ${response.response_text}")
                setState(JarvisState.IDLE)
                isProcessing = false
            }

        } catch (e: Exception) {
            Log.e("JARVIS_API", "❌ Error de conexión: ${e.message}", e)
            ui.showText("Error de conexión con Jarvis")
            setState(JarvisState.IDLE)
            isProcessing = false
        }
    }
}

    private fun iniciarTemporizador(){
        //cancelamos scualquier temporizador
        timeoutHandler.removeCallbacks(timeoutRunnable)
        //programamos el cierre
        timeoutHandler.postDelayed(timeoutRunnable, 10000)
        Log.d("JARVIS_DEBUG", "Temporizador de 10s iniciado...")
    }
    // También debemos limpiar el temporizador si el usuario habla antes de los 10s
    override fun onBeginningOfSpeech() {
        Log.d("JARVIS_DEBUG", "Usuario detectado, cancelando temporizador de cierre")
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    private fun ejecutarAccionesTecnicas(actions: List<ActionDto>,textoOriginal: String,
                                         idIntencion: String) {
        val (openApps, uiActions) = actions.partition { it.tipo == "open_app" }

        // Abrir Apps directamente
        openApps.forEach { a ->
            val pack = a.params?.get("package") as? String
            if (!pack.isNullOrBlank()) ActionExecutor.openApp(context, pack)
        }

        // Acciones de UI (clics, gestos) enviadas al AccessibilityService
        if (uiActions.isNotEmpty()) {
            val intent = Intent(ACTION_EXECUTE).apply {
                putExtra("actions_json", Gson().toJson(uiActions))
                putExtra("texto_original", textoOriginal)
                putExtra("intencion_original", idIntencion)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Log.d("JARVIS_DEBUG", "Broadcast enviado con texto: $textoOriginal")
        }
    }
    // --- TTS Y CONFIG ---
    private fun speakAndWait(text: String, onDone: () -> Unit) {
        setState(JarvisState.SPEAKING)
        val utteranceId = "UTT_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(id: String?) { mainHandler.post { onDone() } }
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { mainHandler.post { onDone() } }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    //configurar reconocedor
    private fun configurarReconocedor(){
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer.setRecognitionListener(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    //configuracion de tts
    private fun configurarTts(){
        tts = TextToSpeech(context){status ->
            if (status == TextToSpeech.SUCCESS){
                //idioma de voz
                tts.setLanguage(Locale("es","Es"))
            }
        }
    }

    // Función auxiliar para convertir onda en volumen para tu Orbe
    private fun calculateRmsFromWaveform(waveform: ByteArray?): Float {
        if (waveform == null) return 0f
        var sum = 0.0
        for (i in waveform.indices) {
            val sample = (waveform[i].toInt() and 0xFF) - 128
            sum += (sample * sample).toDouble()
        }
        return Math.sqrt(sum / waveform.size).toFloat() / 5f // Ajusta el divisor para la sensibilidad
    }
    fun destroy() {
        if (::tts.isInitialized) tts.shutdown()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
    }
    // Métodos obligatorios de la interfaz (vacíos para limpieza)
    override fun onReadyForSpeech(p0: Bundle?) {
        mainHandler.post {
            ui.onRecognizerReady() // Avisa a la Activity para apagar Porcupine
        }
    }
    override fun onRmsChanged(p0: Float) {
        mainHandler.post {
            ui.updateORB(p0)//p0 es el volumen que envia a google speech
        }
    }
    override fun onBufferReceived(p0: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(p0: Int) {
        // El error 7 es "No match" (nadie habló) o el 6 es "Timeout"
        Log.e("JARVIS_DEBUG", "Error de Speech: $p0")

        // Si queremos que intente escuchar otra vez o se apague
        isProcessing = false
        isListening = false
        setState(JarvisState.IDLE)

        // Si quieres que el micro se apague definitivamente tras un error de silencio:
        timeoutHandler.removeCallbacks { timeoutRunnable }
    }
    override fun onPartialResults(p0: Bundle?) {
        val m = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!m.isNullOrEmpty()) ui.showText(m[0])
    }
    override fun onEvent(p0: Int, p1: Bundle?) {}
    fun debugPantallaActual() {
        scope.launch(Dispatchers.IO) {
            try {
                val snapshot = com.example.myapplication.core.ScreenMemory.lastSnapshot
                if (snapshot == null) {
                    Log.w("JARVIS_DEBUG", "No hay snapshot disponible")
                    return@launch
                }

                val data = mapOf(
                    "contexto_detallado" to snapshot.elements.map { elem ->
                        mapOf(
                            "text" to elem.getSearchableText(),
                            "x" to elem.centerX,
                            "y" to elem.centerY,
                            "clickable" to elem.isClickable,
                            "type" to (elem.className ?: "unknown"),
                            "importance" to elem.importance
                        )
                    }
                )

                val response = RetrofitClient.debugApi.debugPantalla(data)
                if (response.isSuccessful) {
                    Log.d("JARVIS_DEBUG", "✅ Debug enviado al servidor")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Error: ${e.message}")
            }
        }
    }

}