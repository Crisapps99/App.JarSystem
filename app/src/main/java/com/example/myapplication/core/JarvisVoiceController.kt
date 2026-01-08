package  com.example.myapplication.core

import android.Manifest
import android.R.attr.action
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
    private var state: JarvisState = JarvisState.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    //flags
    private var isProcessing = false
    private var lastFinalText: String? = null
    private var lastFinalAt = 0L
    fun init(){
        configurarReconocedor()
        configurarTts()
        setState(JarvisState.IDLE)
    }
    fun destroy(){
        //limpiza para evitar que la app crashee
        runCatching {
            if (::tts.isInitialized){
                tts.stop()
                tts.shutdown()
            }
        }
        runCatching {
            if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        }
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
        //usa coroutinas para elsaludo dinamico
        scope.launch {
            val saludo = saludoDinamico()
            ui.showText(saludo)
            //habla el tts  y ejecuta el callbak
            speakAndWait(saludo){
                setState(JarvisState.LISTENING)
                startListening()
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
                ui.showText("error al iniciar escucha ${e.message}")
                setState(JarvisState.IDLE)
                isListening = false
            }
        }
    }
    //detiene el reocnocimiento de voz
    private fun stopListening() {
        mainHandler.post {
            runCatching { speechRecognizer.stopListening() }
            isListening = false
            ui.showText("Reconocmiento detenmido ")
            setState(JarvisState.IDLE)
        }
    }
    //setter centranizado apra el estado
    private fun setState(s: JarvisState){
        state = s
        ui.renderState(s)
    }
    //RecognitionListener
    //android llama cuadno ya est alsito para escuchar
    override fun onReadyForSpeech(params: Bundle?) {
        setState(JarvisState.LISTENING)
        ui.showText("Di algo..")
    }
    //avisa cuadno la persona emppieza habalr
    override fun onBeginningOfSpeech() {
        ui.showText("")//limpia el texto en pantalla
    }
    //resultado parciales mientra el usario habal
    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) ui.showText(matches[0]) // SOLO UI
    }

    //resultado final cuando el suario termina de hablar
    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim().orEmpty()
        if (matches.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        if (isProcessing){
            Log.d("DEBUG-JARVIS","ignorando(isProcessing-true):$text")
            return
        }
        // ✅ Si es lo mismo y muy seguido, ignora (debounce)
        if (text == lastFinalText && (now - lastFinalAt) < 3500) {
            Log.d("DEBUG-JARVIS", "Ignorado (duplicado): $text")
            return
        }
        lastFinalText = text
        lastFinalAt = now
        isProcessing = true
        runCatching { speechRecognizer.cancel() }
        setState(JarvisState.THINKING)
        ui.showText(text)
        //procesa la intencion en corrutinas
        scope.launch {
            try {
                Log.d("DEBUG-JARVIS","eviado final: $text")
                iniciarInteraccion(text)
            }finally {

            }
        }
    }
    //manejo de errores del speechRec
    override fun onError(error: Int) {
        val msg = when (error){
            SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoce la voz"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "eror de red"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->"permisos insuficientes"
            else -> "Error: $error"
        }
        ui.showText(msg)
        ui.showToast(msg)
        setState(JarvisState.IDLE)
        isListening = false
    }

    //callbacks
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onEndOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    //logica Ia y acciones
    private suspend fun iniciarInteraccion(prompt: String){
        ui.showText("pensando ent u comando ")
        setState(JarvisState.THINKING)

        try {
            Log.d("DEBUG-JARVIS", "ENVIANDO: $prompt")
            //ollama predice si es accion o accioens
            val tipo = identificarTipoIntencion(prompt)
            Log.d("DEBUG-JARVIS","Tipo:$tipo")
            if (tipo == "ACCION"){
                procesarAccion(prompt)
            }else{
                conversarConOllama(prompt)
            }
        }catch (e: Exception){
            val err = "Erro: ${e.message ?: " Verificar servidores"}"
            ui.showText(err)
            ui.showToast(err)
            setState(JarvisState.IDLE)
        }
    }
//flujo de acciones procesar accion
    private suspend fun procesarAccion(prompt: String){
        try {
            val apiResponse = actionApiService.predictAction(ActionRequest(prompt))
            val predictedAction = apiResponse.action ?: "UNKNOWN_ACTION"
            //si el servido rno entiende se va a conversaciin
            if (predictedAction == "UNKNOWN_ACTION"){
                conversarConOllama("No se entendio la accion $prompt")
                return
            }
            val resolve = ActionServerClient.service.resolveIntent(IntentRequest(predictedAction))
            val actions = resolve.actions
            //ejecutar openapp
            val(openApps, uiActions) = actions.partition{it.tipo=="open_app"}
            openApps.forEach { a ->
                val pack = a.params?.get("package") as? String
                Log.d("DEBUG-JARVIS", "🟢 open_app directo: $pack")
                if (!pack.isNullOrBlank()) {
                    val ok = ActionExecutor.openApp(context, pack)
                    Log.d("DEBUG-JARVIS", "🟢 open_app result=$ok pack=$pack")
                }
            }
            if (uiActions.isNotEmpty()) {
                enviarAccionesService(uiActions)
            }
            //generamos confirmacion con ollama
            val confirm = generarConfirmacion(prompt, predictedAction)
            ui.showText(confirm)
            speakAndWait(confirm){
                isProcessing = false
                setState(JarvisState.IDLE)
            }
        }catch (e: Exception){
            Log.e("DEBUG-JARVIS","error procesando accion: ${e.message}")
            conversarConOllama("tuve un problema ejecutando esa accion ")
        }
//    speakAndWait(respuestaIA) {
//        isProcessing = false
//        setState(JarvisState.IDLE)
//        // si quieres volver a escuchar automáticamente:
//        // startInteraction() o startListening()
//    }

    }
    //flujo de conversacion
    private suspend fun conversarConOllama(prompt: String){
        setState(JarvisState.THINKING)
        //propmt para enviar a ooollama
        val promptConversacion = """
            Eres Jarvis, elegante y amigable.
            Usuario: "$prompt"
            Responde máximo 2 frases y al final pregunta: "¿Qué acción deseas realizar?"
            No más de 30 palabras.
            """.trimIndent()
        try {
            val r = OllamaClient.service.generar(
                OllamaRequest(model = "phi3", prompt = promptConversacion)
            )
            val respuesta = r.response.trim()
            ui.showText(respuesta)
            //habla depues vuelva a IDLE
            speakAndWait(respuesta){
                isProcessing = false
                setState(JarvisState.IDLE)
            }
        }catch (e: Exception){
            //respuesta del fallback s i oollama falla
            val fallback = "Disculpa, tuve un problema. ¿Qué acción deseas realizar?"
            ui.showText(fallback)
            speakAndWait(fallback){
                isProcessing = false
                setState(JarvisState.IDLE)}
        }
    }
    //confirmacion corta luego de ejectuar la acion
    private suspend fun generarConfirmacion(prompt: String, action: String): String{
        val p = """
            Eres Jarvis.
            Usuario: "$prompt"
            Acción: "$action"
            Responde 1 frase confirmando y luego: "¿Qué más deseas hacer?"
            No más de 20 palabras.
            """.trimIndent()
        return try {
            OllamaClient.service.generar(OllamaRequest("phi3", p)).response.trim()
        }catch (e: Exception){
            "listo ejecutare eso que mas deseas ahcer"
        }
    }
    //clasificar accion o conversacio
    private suspend fun identificarTipoIntencion(prompt: String): String{
        val p = """
            Responde SOLO: "ACCION" o "CONVERSACION".
            Usuario: "$prompt"
            """.trimIndent()
        return try {
            val r = OllamaClient.service.generar(OllamaRequest("phi3",p))
                .response.trim()
                .uppercase()
            if (r.contains("ACCION")|| r.contains("ACTION"))"ACCION" else "CONVERSACION"
        }catch (_: Exception){
            "CONVERSACION"
        }
    }
    //saludo que dice al iniciar interaccion
    private suspend fun saludoDinamico(): String{
        val p = """
            Genera un saludo corto en español (máximo 15 palabras) e invita a dar una orden.
            Responde SOLO el saludo.
            """.trimIndent()
        return try {
            OllamaClient.service.generar(OllamaRequest("phi3",p)).response.trim()
        }catch (_: Exception){
            "Listo que deseas acer"
        }
    }
    //tts hablar y esperar fin
    private fun speakAndWait(text: String, onDone: () -> Unit){
        setState(JarvisState.SPEAKING)
        val utteranceId = "UTT_${System.currentTimeMillis()}"
        //listener para saber cuadno termina de hablar
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
            override fun onDone(id: String?) {
                //siempre vuelve al main thread
                mainHandler.post { onDone() }
            }

            override fun onStart(id: String?) {}
            override fun onError(id: String?) {
              mainHandler.post { onDone() }
            }
        })
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        //corta calqueir audio anterior y habla esto ya
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
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
    private fun enviarAccionesService(actions: List<ActionDto>){
        //convierte las acciones a json
        val json = Gson().toJson(actions)
        //broadcast ecplisto a la app
        val intent = Intent(ACTION_EXECUTE).apply {
            setPackage(context.packageName)
            putExtra("actions_json", json)
        }
        //envia el broadcast
        context.sendBroadcast(intent)
        Log.d("DEBUG-JARVIS", "Broadcast acciones: $json")
    }

}

