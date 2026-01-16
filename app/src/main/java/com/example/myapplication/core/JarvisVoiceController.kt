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
import okhttp3.Callback

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
            val saludo = saludoAleatorio?: "enlina que desas hacer"
            //muetsra el saludo
            Log.d("JARVIS_DEBUG","REcibido de gem: $saludo")
            ui.showToast("Gemma dice: $saludo")
            ui.showText(saludo)

            //jarvis hhabla y espera a que termine de decir la frase
            speakAndWait (saludo ){
                setState(JarvisState.LISTENING)
                startListening()
            }

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
    private fun startListening(){
        mainHandler.post {
            try {
                speechRecognizer.startListening(recognizerIntent)
                ui.showText("ahora escucho ")
            } catch (e: Exception) {
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

    //resultado final cuando el suario termina de hablar
    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim().orEmpty()
        val textoEscuchado = matches?.firstOrNull()?.trim() ?:""
        if (text.isEmpty() || isProcessing) return

        isProcessing = true
        isListening = false
        ui.showText(text)
        //campturamos el contexto antes de enviar

        scope.launch {
            setState(JarvisState.THINKING)
            val contextoActual = ui.getCurrentScreenText()
            Log.d("JARVIS_DEBUG", "Texto: $textoEscuchado")
            Log.d("JARVIS_DEBUG", "Contexto size: ${contextoActual.size} elementos")
            Log.d("JARVIS_DEBUG", "Contexto contenido: ${contextoActual.take(5)}...") // Ver los primeros 5
            try {
                val request = ActionRequest(texto = textoEscuchado, contexto = contextoActual)
                val response = actionApiService.predictAction(request)
                // 🔥 LOGS DE INSPECCIÓN PROFUNDA 🔥
                Log.d("JARVIS_SERVER", "--- NUEVA RESPUESTA RECIBIDA ---")
                Log.d("JARVIS_SERVER", "Success: ${response.success}")
                Log.d("JARVIS_SERVER", "Modo: ${response.mode}") // Aquí verás si es COMMAND o CHAT_FREE
                Log.d("JARVIS_SERVER", "Texto de Voz: ${response.response_text}")

                // Inspeccionamos el Payload si existe
                response.payload?.let { listaAcciones ->
                    Log.d("JARVIS_SERVER", "Payload detectado con ${listaAcciones.size} acciones")
                    listaAcciones.forEachIndexed { index, accion ->
                        Log.d("JARVIS_SERVER", "Acción [$index]: Tipo=${accion.tipo}")
                        Log.d("JARVIS_SERVER", "Acción [$index]: Params=${accion.params}")
                    }
                } ?: Log.d("JARVIS_SERVER", "Payload es NULO")
                if (response.success) {
                    ui.showText(response.response_text)

                    val intencionDetectada = response.mode ?: "intent_desconocido"
                    speakAndWait(response.response_text) {
                        val payloadSeguro = response.payload
                        // Aceptamos ambos modos para que no se bloquee la acción técnica
                        val esAccionTecnica = response.mode == "COMMAND" || response.mode == "DYNAMIC_ACTION"
                        // Si es comando y trae payload, ejecutamos
                        if (esAccionTecnica && !payloadSeguro.isNullOrEmpty()) {
                            Log.d("JARVIS_SERVER", "Ejecutando acción técnica: ${payloadSeguro[0].tipo}")
                            ejecutarAccionesTecnicas(
                                payloadSeguro,
                                textoEscuchado, // El texto que el usuario dictó
                                intencionDetectada
                            )
                        }
                        isProcessing = false
                        //delay apra que no se escuhe asi mismo si hay eco
                        mainHandler.postDelayed({
                            startListening()
                            iniciarTemporizador()
                        },1000)
                    }
                }
            } catch (e: Exception) {
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
    private fun playJarvisVoice(audioUrl: String) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(audioUrl)
        mediaPlayer.prepareAsync()

        mediaPlayer.setOnPreparedListener { mp ->
            setState(JarvisState.SPEAKING)

            // CONFIGURAR VISUALIZADOR PARA EL ORBE
            val visualizer = Visualizer(mp.audioSessionId)
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    // Calculamos el volumen (RMS) de la onda para mover el orbe
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
                setState(JarvisState.IDLE)
                startListening() // Volver a escuchar tras hablar
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


}

