package  com.example.myapplication.core

import android.Manifest
import android.R
import android.R.attr.action
import android.R.attr.text
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private fun startInteraction(){
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
        if (text.isEmpty() || isProcessing) return

        isProcessing = true
        isListening = false
        ui.showText(text)

        scope.launch {
            setState(JarvisState.THINKING)
            try {
                val response = actionApiService.predictAction(ActionRequest(text))
                if (response.success) {
                    ui.showText(response.response_text)

                    speakAndWait(response.response_text) {
                        // Si es comando y trae payload, ejecutamos
                        if (response.mode == "COMMAND" && response.payload?.isNotEmpty()==true) {
                            ejecutarAccionesTecnicas(response.payload)
                        }
                        isProcessing = false
                        setState(JarvisState.IDLE)
                    }
                }
            } catch (e: Exception) {
                ui.showText("Error de conexión con Jarvis")
                setState(JarvisState.IDLE)
                isProcessing = false
            }
        }
    }
    private fun ejecutarAccionesTecnicas(actions: List<ActionDto>) {
        val (openApps, uiActions) = actions.partition { it.tipo == "open_app" }

        // Abrir Apps directamente
        openApps.forEach { a ->
            val pack = a.params?.get("package") as? String
            if (!pack.isNullOrBlank()) ActionExecutor.openApp(context, pack)
        }

        // Acciones de UI (clics, gestos) enviadas al AccessibilityService
        if (uiActions.isNotEmpty()) {
            val intent = Intent(ACTION_EXECUTE).apply {
                setPackage(context.packageName)
                putExtra("actions_json", Gson().toJson(uiActions))
            }
            context.sendBroadcast(intent)
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
    fun destroy() {
        if (::tts.isInitialized) tts.shutdown()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
    }
    // Métodos obligatorios de la interfaz (vacíos para limpieza)
    override fun onReadyForSpeech(p0: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(p0: Float) {}
    override fun onBufferReceived(p0: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(p0: Int) { setState(JarvisState.IDLE); isProcessing = false; isListening = false }
    override fun onPartialResults(p0: Bundle?) {
        val m = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!m.isNullOrEmpty()) ui.showText(m[0])
    }
    override fun onEvent(p0: Int, p1: Bundle?) {}

}

