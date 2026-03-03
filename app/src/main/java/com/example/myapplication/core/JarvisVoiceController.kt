package  com.example.myapplication.core


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale

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
    //Apis
    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private  val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    //componentes de voz
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent//coniuracion dde speechRecognizer idioma partial result
    private lateinit var tts: TextToSpeech//motor de voz para hablar

    //estados
    private var isListening = false//para saber si esta en modo escucha
    private var isProcessing = false

    //handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable{
        Log.d("JARVIS_DEBUG", "10 segundos de silencio, cerrando micro.")
        stopListening()
    }
    //elevenLabs config
    private val ELEVEN_LABS_API_KEY = "sk_275d41153da7f3f34a94dfe4ff9636271a1dc92db0f3e23e"
    private val VOICE_ID = "dQ0C8BEdKF2odmELvNee"
//inicializacion
    fun init (){
        configurarReconocedor()
        configurarTts()
        setState(JarvisState.IDLE)
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
    //controles del microfono
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
        obtenerSaludoGemma { saludo ->
            //so falal servidor envia saludo estatico
            val saludoFinal = saludo?: "sistemas en proceso y listos "
            //muetsra el saludo
            Log.d("JARVIS_DEBUG","Saludo de G2: $saludoFinal")
            ui.showText(saludoFinal)
            //jarvis hhabla y espera a que termine de decir la frase
            speakWithAndroidTTS (saludoFinal)

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
    //texxt to speech eleven y tts
    private fun speakWithElevenLabs(text: String) {
        Log.d("ELEVEN_DEBUG", "Generando voz con ElevenLabs: $text")
        setState(JarvisState.THINKING)

        scope.launch(Dispatchers.IO) {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

            val json = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", ELEVEN_LABS_API_KEY)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("ElevenLabs error: ${response.code}")
                    }

                    val audioBytes = response.body?.bytes()
                    if (audioBytes == null || audioBytes.isEmpty()) {
                        throw Exception("Audio vacío")
                    }

                    val tempFile = File.createTempFile("jarvis_voice", ".mp3", context.cacheDir)
                    tempFile.writeBytes(audioBytes)

                    mainHandler.post {
                        playJarvisVoiceFromFile(tempFile)
                    }
                }
            } catch (e: Exception) {
                Log.e("ELEVEN_DEBUG", "Error: ${e.message}")
                mainHandler.post {
                    ui.showToast("Error en voz, usando TTS local")
                    speakWithAndroidTTS(text)
                }
            }
        }
    }

    private fun playJarvisVoiceFromFile(file: File) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.prepareAsync()

        mediaPlayer.setOnPreparedListener { mp ->
            setState(JarvisState.SPEAKING)
            mp.start()
            mp.setOnCompletionListener {
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

//procesamiento de voz
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
        // Obtener snapshot actual de la pantalla
        val snapshot = com.example.myapplication.core.ScreenMemory.lastSnapshot
        // Contexto detallado (nuevo) - solo si hay snapshot disponible
        // Contexto detallado
        val contextoDetallado = snapshot?.elements
            ?.filter { it.isClickable || it.isEditable || it.isScrollable || it.importance > 50 }
            ?.sortedByDescending { it.importance }
            ?.take(30)
            ?.map { it.toDto() }
            ?: emptyList()
// Metadata
        val metadata = mapOf(
            "packageName" to (snapshot?.packageName ?: "unknown"),
            "activityName" to (snapshot?.activityName ?: "unknown"),
            "totalElements" to (snapshot?.totalElements ?: 0),
            "clickableCount" to (snapshot?.clickableElements ?: 0),
            "editableCount" to (snapshot?.editableElements ?: 0),
            "scrollableCount" to (snapshot?.scrollableContainers ?: 0),
            "timestamp" to System.currentTimeMillis()
        )
        Log.d("JARVIS_DEBUG", "🎙️ Usuario dijo: $textoEscuchado")
        Log.d("JARVIS_DEBUG", "📱 Contexto simple: ${metadata["packageName"]}")
        Log.d("JARVIS_DEBUG", "📊 Contexto detallado: ${contextoDetallado.size} }")

        // Log de muestra de elementos detectados
        contextoDetallado.take(5).forEach { elem ->
            Log.d("JARVIS_DEBUG", "  └─ [${elem.importance}] ${elem.text} (${elem.type})")
        }
        try {
            val request = ActionRequestEnriquecido(
                texto = textoEscuchado,
                contexto = emptyList(),
                contextoDetallado = contextoDetallado,
                metadata = metadata
            )

            val response = actionApiService.predictActionEnriquecido(request)

            Log.d("JARVIS_SERVER", "✅ Success: ${response.success}")
            Log.d("JARVIS_SERVER", "🎭 Modo: ${response.mode}")
            Log.d("JARVIS_SERVER", "💬 Respuesta: ${response.response_text}")

            if (response.success) {
                ui.showText(response.response_text)

                val esAccionTecnica = response.mode == "COMMAND" || response.mode == "DYNAMIC_ACTION"
                if (esAccionTecnica && !response.payload.isNullOrEmpty()) {
                    Log.d("JARVIS_SERVER", "⚡ Ejecutando ${response.payload.size} acciones")
                    ejecutarAccionesTecnicas(
                        response.payload,
                        textoEscuchado,
                        response.action ?: "unknown"
                    )
                }

                speakWithAndroidTTS(response.response_text)
                isProcessing = false

            } else {
                ui.showText("Error: ${response.response_text}")
                setState(JarvisState.IDLE)
                isProcessing = false
            }

        } catch (e: Exception) {
            Log.e("JARVIS_API", "❌ Error: ${e.message}", e)
            ui.showText("Error de conexión")
            setState(JarvisState.IDLE)
            isProcessing = false
        }
    }
}
    //ejecucion de acciones
    private fun ejecutarAccionesTecnicas(
        actions: List<ActionDto>,
        textoOriginal: String,
        intencion: String
    ) {
        val (openApps, uiActions) = actions.partition { it.tipo == "open_app" }

        // Abrir Apps directamente
        openApps.forEach { accion ->
            val pack = accion.params?.get("package") as? String
            if (!pack.isNullOrBlank()) {
                ActionExecutor.openApp(context, pack)
            }
        }

        // Acciones de UI
        if (uiActions.isNotEmpty()) {
            val intent = Intent(ACTION_EXECUTE).apply {
                putExtra("actions_json", Gson().toJson(uiActions))
                putExtra("texto_original", textoOriginal)
                putExtra("intencion_original", intencion)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Log.d("JARVIS_DEBUG", "Broadcast enviado: $textoOriginal")
        }
    }
//utilidades

    private fun obtenerSaludoGemma(callback: (String?) -> Unit) {
        scope.launch {
            try {
                val response = actionApiService.regards()
                mainHandler.post {
                    callback(response.saludo)
                }
            } catch (e: Exception) {
                Log.e("JARVIS", "Error al obtener saludo: ${e.message}")
                mainHandler.post { callback(null) }
            }
        }
    }
    private fun setState(s: JarvisState) {
        ui.renderState(s)
    }
    private fun iniciarTemporizador(){
        //cancelamos scualquier temporizador
        timeoutHandler.removeCallbacks(timeoutRunnable)
        //programamos el cierre
        timeoutHandler.postDelayed(timeoutRunnable, 10000)
        Log.d("JARVIS_DEBUG", "Temporizador de 10s iniciado...")
    }

    fun destroy() {
        if (::tts.isInitialized) tts.shutdown()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
    }

    //listener de speech recognizer
    // Métodos obligatorios de la interfaz (vacíos para limpieza)
    override fun onReadyForSpeech(p0: Bundle?) {
        mainHandler.post {
            ui.onRecognizerReady() // Avisa a la Activity para apagar Porcupine
        }
    }
// También debemos limpiar el temporizador si el usuario habla antes de los 10s
    override fun onBeginningOfSpeech() {
        Log.d("JARVIS_DEBUG", "Usuario detectado, cancelando temporizador de cierre")
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }
    override fun onRmsChanged(p0: Float) {
        mainHandler.post {
            ui.updateORB(p0)//p0 es el volumen que envia a google speech
        }
    }
    override fun onPartialResults(p0: Bundle?) {
        val m = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!m.isNullOrEmpty()) ui.showText(m[0])
    }
    override fun onBufferReceived(p0: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(p0: Int, p1: Bundle?) {}
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
//    // --- TTS Y CONFIG ---
//    private fun speakAndWait(text: String, onDone: () -> Unit) {
//        setState(JarvisState.SPEAKING)
//        val utteranceId = "UTT_${System.currentTimeMillis()}"
//        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
//            override fun onDone(id: String?) { mainHandler.post { onDone() } }
//            override fun onStart(id: String?) {}
//            override fun onError(id: String?) { mainHandler.post { onDone() } }
//        })
//        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
//    }



//    // Función auxiliar para convertir onda en volumen para tu Orbe
//    private fun calculateRmsFromWaveform(waveform: ByteArray?): Float {
//        if (waveform == null) return 0f
//        var sum = 0.0
//        for (i in waveform.indices) {
//            val sample = (waveform[i].toInt() and 0xFF) - 128
//            sum += (sample * sample).toDouble()
//        }
//        return Math.sqrt(sum / waveform.size).toFloat() / 5f // Ajusta el divisor para la sensibilidad
//    }
//

//
//    fun debugPantallaActual() {
//        scope.launch(Dispatchers.IO) {
//            try {
//                val snapshot = com.example.myapplication.core.ScreenMemory.lastSnapshot
//                if (snapshot == null) {
//                    Log.w("JARVIS_DEBUG", "No hay snapshot disponible")
//                    return@launch
//                }
//
//                val data = mapOf(
//                    "contexto_detallado" to snapshot.elements.map { elem ->
//                        mapOf(
//                            "text" to elem.getSearchableText(),
//                            "x" to elem.centerX,
//                            "y" to elem.centerY,
//                            "clickable" to elem.isClickable,
//                            "type" to (elem.className ?: "unknown"),
//                            "importance" to elem.importance
//                        )
//                    }
//                )
//
//                val response = RetrofitClient.debugApi.debugPantalla(data)
//                if (response.isSuccessful) {
//                    Log.d("JARVIS_DEBUG", "✅ Debug enviado al servidor")
//                }
//            } catch (e: Exception) {
//                Log.e("JARVIS_DEBUG", "Error: ${e.message}")
//            }
//        }
//    }

}