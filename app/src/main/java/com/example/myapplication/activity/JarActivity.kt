package com.example.myapplication.activity

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.Manifest
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityJarBinding
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.handlers.IntentHandler
import java.util.*
import com.example.myapplication.api.RetrofitClient
import com.example.myapplication.api.ActionApiService
import com.example.myapplication.api.ActionRequest
import com.example.myapplication.api.ActionDto
import com.example.myapplication.api.ActionServerClient
import com.example.myapplication.api.IntentRequest
import com.example.myapplication.api.OllamaClient
import com.example.myapplication.api.OllamaRequest
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.sin
import kotlin.random.Random

enum class JarvisState{
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}
class JarActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityJarBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private var isListener = false
    private lateinit var generativeModel: GenerativeModel // Comentamos Gemini
    private lateinit var textToSpeech: TextToSpeech
    private val actionApiService: ActionApiService = RetrofitClient.actionApiService
    private var jarvisState: JarvisState = JarvisState.IDLE


    private val RECORD_AUDIO_PERMISSION_CODE = 200
    private var ttsRhythmRunnable: Runnable? = null
    private var ttsRhythmStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)



        binding = ActivityJarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //boton para test
        binding.btnTestApis.setOnClickListener {
            val i = Intent(this, ApiTestActivity::class.java)
            startActivity(i)
        }
        //permiso del microfono
        requestAudioPermission()

        configurarReconocedor()
        textToSpeech = TextToSpeech(this){ status ->
            if (status == TextToSpeech.SUCCESS){
                val result = textToSpeech.setLanguage(Locale("es", "ES"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED){
                    Toast.makeText(this,"idioma español no disponible", Toast.LENGTH_LONG).show()
                }
            }else{
                Toast.makeText(this,"eror al inicial el motor tts", Toast.LENGTH_LONG).show()
            }
        }
        binding.micButton.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                if (isListener){
                    detenerRreconocimientovoz()
                }else{
                    iniciarReconocimientoVoz()
                }

            } else {
                Toast.makeText(this, "Permiso de micrófono denegado.", Toast.LENGTH_SHORT).show()
                requestAudioPermission()
            }
        }

    }
    //simulacion con seño para simular la amplitud d ela voz
    private fun startTtsRhythm() {
        stopTtsRhythm()
        ttsRhythmStart = System.currentTimeMillis()

        ttsRhythmRunnable = object : Runnable {
            private var lastPulse = 0L
            private var pulseInterval = 150L // Duración de cada "palabra"
            private var silenceChance = 0.15f // 15% de pausas

            override fun run() {
                if (jarvisState == JarvisState.SPEAKING) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - ttsRhythmStart

                    // 🎙SIMULACIÓN DE HABLA NATURAL
                    // Combinamos 3 ondas para crear variación
                    val wave1 = sin((elapsed / 120.0)) // Onda rápida (sílabas)
                    val wave2 = sin((elapsed / 300.0)) // Onda media (palabras)
                    val wave3 = sin((elapsed / 800.0)) // Onda lenta (entonación)

                    // Añadimos ruido aleatorio para naturalidad
                    val noise = (Random.nextFloat() - 0.5f) * 0.2f

                    // Pausas aleatorias (como respirar o separar palabras)
                    val shouldPause = Random.nextFloat() < silenceChance

                    val energy = if (shouldPause) {
                        0.15f // Energía baja durante pausas
                    } else {
                        // Combinamos las ondas normalizadas (0-1)
                        val combined = (wave1 + 1.0) / 2.0 * 0.4 + // 40% peso
                                (wave2 + 1.0) / 2.0 * 0.35 + // 35% peso
                                (wave3 + 1.0) / 2.0 * 0.25   // 25% peso

                        (0.35 + combined * 0.5 + noise).toFloat().coerceIn(0.2f, 0.85f)
                    }

                    binding.jarvisOrb.setArtificialEnergy(energy)
                    binding.jarvisOrb.postDelayed(this, 40) // 25 FPS
                }
            }
        }

        binding.jarvisOrb.post(ttsRhythmRunnable!!)
    }
    //detener la animaciopon del tts
    private fun stopTtsRhythm(){
        ttsRhythmRunnable?.let{
            binding.jarvisOrb.removeCallbacks (it )
            ttsRhythmRunnable = null
        }
        binding.jarvisOrb.setArtificialEnergy(0f)
    }

    private fun setJarvisState(state: JarvisState) {
        jarvisState = state
// Detener siempre el Runnable antes de cambiar de estado
        if (state != JarvisState.SPEAKING){
            stopTtsRhythm()
        }
        when (state) {
            JarvisState.LISTENING -> {
                binding.micButton.visibility = View.GONE
                binding.jarvisOrb.visibility = View.VISIBLE
                binding.jarvisOrb.reset()
            }
            JarvisState.THINKING -> {
                binding.micButton.visibility = View.GONE
                binding.jarvisOrb.visibility = View.VISIBLE
                binding.jarvisOrb.setArtificialEnergy(0.25f)
            }

            JarvisState.SPEAKING -> {
                binding.micButton.visibility = View.GONE
                binding.jarvisOrb.visibility = View.VISIBLE
                startTtsRhythm()
            }
            JarvisState.IDLE -> {
                binding.jarvisOrb.reset()
                binding.jarvisOrb.visibility = View.GONE
                binding.micButton.visibility = View.VISIBLE
            }

        }
    }

    //CONCEDER PERMISOS
    private fun requestAudioPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),RECORD_AUDIO_PERMISSION_CODE)
        }
    }
    //MANEJO DE RESPUESTA DE LA SOLICTUD
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode== RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this, "permiso consedido", Toast.LENGTH_SHORT).show()
        }else{
            Toast.makeText(this, "permiso denegado", Toast.LENGTH_LONG).show()
        }
    }
    //configuracion del permiso
    private fun configurarReconocedor() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    private suspend fun saludoDinamico(): String{
        //promp
        val promptSaludo= """
        Genera un saludo corto, y en español.
        Luego invita al usuario a dar una orden.
        Ejemplos: "Buenos días, estoy a su servicio", "En línea. ¿Cuál es su solicitud?"
        Responde SOLO con la frase de saludo, sin explicaciones. NO USES MÁS DE 15 PALABRAS.
        y no des explicaciones de nada  solo el saludo y ya"""
            .trimIndent()
        try{
        val ollamaResult = OllamaClient.service.generar(
            OllamaRequest(
                model = "phi3",
                prompt = promptSaludo
            )
        )
            return ollamaResult.response.trim()
        }catch (e: Exception){
            Log.e("OLLAMA-SALUDO","Erro al llamar a ollama: ${e.message}")
            return "se perdio la conexion con ollama"
        }
    }
    //iniciamos el reconocimiento devoz
    private fun iniciarReconocimientoVoz() {
        speechRecognizer.stopListening()
        isListener = true
        setJarvisState(JarvisState.SPEAKING)

        lifecycleScope.launch {
            val saludoDinamico = saludoDinamico()
            binding.transcriptionTextView.text = saludoDinamico

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "SALUDO_TTS")

            textToSpeech.speak(
                saludoDinamico,
                TextToSpeech.QUEUE_FLUSH,
                params,
                "SALUDO_TTS"
            )
          endTTS{
              setJarvisState(JarvisState.LISTENING)
              EscuchaReal()
          }
        }

    }
    //para esuchar la voz real
    private fun  EscuchaReal(){
        try {
            speechRecognizer.startListening(recognizerIntent)
            isListener = true
            Toast.makeText(this, "🎤 Ahora estoy escuchando…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar escucha: ${e.message}", Toast.LENGTH_LONG).show()
            setJarvisState(JarvisState.IDLE)
        }
    }
    //funcion para que termine el tts
    private fun endTTS(onFinish: ()->Unit){
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    stopTtsRhythm()
                    onFinish()
                }
            }
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    if (jarvisState == JarvisState.SPEAKING && ttsRhythmRunnable == null){
                        startTtsRhythm()
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    stopTtsRhythm()
                    setJarvisState(JarvisState.IDLE)
                }
            }
        })
    }
    private fun detenerRreconocimientovoz(){
        if (isListener){
            speechRecognizer.stopListening()
            isListener = false
            binding.transcriptionTextView.text = "reconocimientodetenido"

            //ocultamos las olas
//            binding.micButton.visibility=android.view.View.VISIBLE
//            binding.jarvisOrb.visibility = android.view.View.GONE
//            binding.micButton.visibility = android.view.View.VISIBLE
            setJarvisState(JarvisState.IDLE)

        }
    }
    private fun resetVisualState(){
        isListener=false

//        binding.micButton.visibility=android.view.View.VISIBLE
//        binding.jarvisOrb.visibility = android.view.View.GONE
//        binding.micButton.visibility = android.view.View.VISIBLE
        setJarvisState(JarvisState.IDLE)
    }
    //funcion para hablar y gestionar la transicion de estado final
    private fun speakEndInteraction(text: String, onFinish: () -> Unit){
        setJarvisState(JarvisState.SPEAKING)
        val params= Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "fINAL_RESPONDE_TTS")

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener(){
            override fun onDone(utteranceId: String?){
                runOnUiThread {
                    stopTtsRhythm()
                    onFinish()
                }
            }
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    if (ttsRhythmRunnable == null){
                        startTtsRhythm()
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    stopTtsRhythm()
                    setJarvisState(JarvisState.IDLE)
                    onFinish()
                }
            }
        })
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "FINAL_RESPONDE_TTS")
    }
    //metodos para el recognitionlistener
    override fun onReadyForSpeech(params: Bundle?){
        setJarvisState(JarvisState.LISTENING)
        Toast.makeText(this, "Escuchando...", Toast.LENGTH_SHORT).show()
        binding.transcriptionTextView.text= "di algo"
    }
    //par aimpiar el texto antes de pempesar hablar
    override fun onBeginningOfSpeech() {
        binding.transcriptionTextView.text=""
    }
    //mostramos la trasncripcion en tiempo real
    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()){
            binding.transcriptionTextView.text = matches[0]
        }
    }
    //mostramos el resultado  en la caja de texto
    override fun onResults(results: Bundle?) {
        isListener=false
        setJarvisState(JarvisState.THINKING)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()){
            val userQuery = matches[0]
            binding.transcriptionTextView.text = userQuery

            //llamamoas ala ia con un texto trascrito
            IniciarInteraccionNeurona(userQuery)
            Toast.makeText(this, "transcripcion finalizada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun IniciarInteraccionNeurona(prompt: String){
        binding.transcriptionTextView.text="Pensando en tu comando..."
        setJarvisState(JarvisState.THINKING)

        //ejecutamos la llamada a la api en un hilo de coroutine
        lifecycleScope.launch {
            try {
                Log.d("DEBUG-JARVIS", "📤 Enviando a API: $prompt")

                val tipoIntencion =identificarTipoIntencion(prompt)
                Log.d("DEBUG_JARVIS", "tipodecetado: $tipoIntencion")

                if(tipoIntencion == "ACCION"){
                    procesarAccion(prompt)
                }else{
                    conversarConOllama(prompt)
                }
//                val apiResponse = actionApiService.predictAction(ActionRequest(prompt))
//                val predictedAction = apiResponse.action ?: "UNKNOWN_ACTION"
//
//                //servidor de acciones
//                Log.d("DEBUG-JARVIS", "Enviando a ActionServer intent: $predictedAction")
//                if (predictedAction == "UNKNOWN_ACTION") {
//                    responderConversacion(prompt)
//                    return@launch
//                }
//                val resolveResp = ActionServerClient.service.resolveIntent(IntentRequest(predictedAction))
//                val actions = resolveResp.actions
//
//
//                val promptOllama = """
//                    primero saluda
//Eres un asistente tipo Jarvis.
//Responde SOLO en 1 o 2 frases cortas.
//
//El usuario dijo: "$prompt"
//La intención detectada: "$predictedAction"
//
//Instrucciones:
//- Confirma brevemente la acción.
//- Luego pregunta: "¿Qué más deseas hacer?"
//- NO agregues explicaciones.
//- NO pidas más detalles.
//- NO escribas más de 25 palabras.
//"""
//
//                val ollamaResult = OllamaClient.service.generar(
//                    OllamaRequest(
//                        model = "phi3",
//                        prompt = promptOllama
//                    )
//                )
//
//                val respuestaIA = ollamaResult.response
//                binding.transcriptionTextView.text =respuestaIA
//                Log.d("OLLAMA", "📥 Respuesta: $respuestaIA")
//                setJarvisState(JarvisState.SPEAKING)
//                textToSpeech.speak(respuestaIA, TextToSpeech.QUEUE_FLUSH, null, null)
//                IntentHandler.handleIntent(this@JarActivity, predictedAction)
//
//
//                //enviamos la accion al acceibility
//                enviarAccionesService(actions)
//                speakEndInteraction(respuestaIA){
//                    setJarvisState(JarvisState.IDLE)
//                }
////                val geminiPrompt = when (predictedAction) {
////                    "abrir_camara" -> "El usuario quiere 'abrir_camara'. Da una respuesta natural confirmándolo."
////                    "abrir_mapa" -> "El usuario quiere 'abrir_mapa'. Da una respuesta natural confirmándolo."
////                    "UNKNOWN_ACTION" -> "El comando del usuario ('$prompt') no se reconoció. Pídele que lo repita de forma amable."
////                    else -> "El usuario ha dado el comando '$predictedAction'. Primero Saluda Cordialmente y Da una respuesta corta y natural confirmando la acción. y  pregutna que quieres hacer "
////                }
////                val geminiResponse = try {
////                    val response = generativeModel.generateContent(geminiPrompt)
////                    response.text ?: "No pude generar una respuesta conversacional."
////                } catch (e: Exception) {
////                    "Error en Gemini: No pude generar la respuesta. ${e.message}"
////                }
////                if (geminiResponse.isNotEmpty()){
////                    textToSpeech.speak(geminiResponse, TextToSpeech.QUEUE_FLUSH, null, null)
////                }
////                val executionResult = IntentHandler.handleIntent(this@JarActivity, predictedAction)
////
////                binding.transcriptionTextView.text =
////                    "$geminiResponse\n\n" +
////                            "[Acción ML: $predictedAction, Ejecución Local: $executionResult]"

            }catch (e: Exception){
                // Error de red, JSON o conexión con tu servidor ngrok0
                val errorMessage = "Error API (ngrok): ${e.message ?: "Verifica que el servidor esté activo y la URL correcta."}"
                binding.transcriptionTextView.text = errorMessage
                Toast.makeText(this@JarActivity, errorMessage, Toast.LENGTH_LONG ).show()
                setJarvisState(JarvisState.IDLE)
            }
        }
    }
    private suspend fun identificarTipoIntencion(prompt: String): String{
        val promprClasificacion = """
            Eres un clasificador de intenciones. Analiza el siguiente texto del usuario y responde SOLO con una palabra: "ACCION" o "CONVERSACION".

            Ejemplos de ACCION:
            - "abre la cámara"
            - "llama a Juan"
            - "envía un mensaje a María"
            - "reproduce música"
            - "activa el WiFi"
            - "sube el volumen"

            Ejemplos de CONVERSACION:
            - "hola"
            - "buenos días"
            - "cómo estás"
            - "qué tal"
            - "gracias"
            - "cuéntame un chiste"
            - "qué puedes hacer"
            usuario dijo: "$prompt"
            
        """.trimIndent()
        try {
            val resultado = OllamaClient.service.generar(
                OllamaRequest(model = "phi3", prompt = promprClasificacion)
            )
            val respuesta = resultado.response.trim().uppercase()
            return if (respuesta.contains("ACCION") || respuesta.contains("ACTION")) {
                "ACCION"
            } else {
                "CONVERSACION"
            }
        }catch (e: Exception){
            Log.e("OLLAMA-CLASIFICACION", "Error: ${e.message}")

            return "CONVERSACION"
        }
    }
    //procesar accion con api de colab
    private suspend fun procesarAccion(prompt: String){
        try {
            Log.d("DEBUG-JARVIS","enviando api a colab: $prompt")
            val apiResponse = actionApiService.predictAction(ActionRequest(prompt))
            val predictedAction = apiResponse.action?: "UNKNOWN_ACTION"
            Log.d("DEBUG-JARVIS", "Acción predicha: $predictedAction")
            if(predictedAction == "UNKNOWN_ACTION"){
                conversarConOllama("No se entendio la accion que solicitaste. $prompt")
                return
            }
            //obtenemos acciones del servidor
            val resolveReso = ActionServerClient.service.resolveIntent(IntentRequest(predictedAction))
            val actions = resolveReso.actions
            //generar repuesta confirmando la accion
            val promptOllama = """
                Eres Jarvis, un asistente elegante.
El usuario dijo: "$prompt"
La acción detectada: "$predictedAction"

Responde en 1 frase corta confirmando que ejecutarás la acción.
Luego pregunta: "¿Qué más deseas hacer?"
NO uses más de 20 palabras.
""".trimIndent()
            val ollamaResult = OllamaClient.service.generar(
                OllamaRequest(model = "phi3", prompt = promptOllama)
            )
            val respuestaIA = ollamaResult.response.trim()
            binding.transcriptionTextView.text = respuestaIA
            Log.d("OLLAMA","respuesta: $respuestaIA")

            //ejecuta la accion
            IntentHandler.handleIntent(this@JarActivity, predictedAction)
            enviarAccionesService(actions)
            //hablar y vovler a esucchar
            speakEndInteraction ( respuestaIA ){
                setJarvisState(JarvisState.IDLE)
            }
        }catch (e: Exception){
            Log.e("DEBUG-JARVIS", "Error procesando acción: ${e.message}")
            conversarConOllama("Tuve un problema ejecutando esa acción")
        }
    }
    private suspend fun conversarConOllama(prompt: String){
        setJarvisState(JarvisState.THINKING)
        val promptConversacion = """
            Eres Jarvis, un asistente elegante y amigable como en Iron Man.
            El usuario te dijo: "$prompt"

            Instrucciones:
            - Responde de forma natural y breve (máximo 2 frases).
            - Si es un saludo, responde amablemente.
            - Si te pregunta algo, responde de forma útil.
            - Al final, SIEMPRE pregunta: "¿Qué acción deseas realizar?"
            - NO uses más de 30 palabras en total.

        """.trimIndent()
        try{
            val resultado = OllamaClient.service.generar(
                OllamaRequest(model = "phi3",prompt = promptConversacion)
            )
            val respuesta = resultado.response.trim()
            binding.transcriptionTextView.text = respuesta
            Log.d("OLLAMA-CONV", " Conversación: $respuesta")
            speakEndInteraction ( respuesta ){
                setJarvisState(JarvisState.IDLE)
            }
        }catch (e: Exception){
            Log.e("OLLAMA-CONV", "Error: ${e.message}")
            val fallback = "Disculpa, tuve un problema. ¿Qué acción deseas realizar?"
            binding.transcriptionTextView.text = fallback

            speakEndInteraction(fallback) {
                setJarvisState(JarvisState.IDLE)
            }
        }
    }
//    private suspend fun responderConversacion(texto: String) {
//        setJarvisState(JarvisState.THINKING)
//        val prompt = """
//Eres un asistente elegante como Jarvis.
//Responde en 1–2 frases, breve y en español.
//El usuario dijo: "$texto"
//No pidas aclaraciones.
//""".trimIndent()
//
//        val resp = OllamaClient.service.generar(
//            OllamaRequest("phi3", prompt)
//        ).response.trim()
//
//        binding.transcriptionTextView.text = resp
//        speakEndInteraction (resp){
//            setJarvisState(JarvisState.IDLE)
//        }
//    }


    private fun enviarAccionesService(actions: List<ActionDto>) {
        val intent = Intent("com.example.myapplication.ACTION_EXECUTE_BATCH")
        val json = Gson().toJson(actions)
        intent.putExtra("actions_json", json)
        sendBroadcast(intent)
    }

    override fun onError(error: Int) {
        setJarvisState(JarvisState.IDLE)
        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No se pudo reconocer la voz."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Error de red. ¿Hay conexión?"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes."
            else -> "Error: $error"
        }
        resetVisualState()
        binding.transcriptionTextView.text = errorMessage
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
    }
    // metodo para la voz mueva al aesfera
    override fun onRmsChanged(rmsdB: Float) {
        if (binding.jarvisOrb.visibility == View.VISIBLE && jarvisState == JarvisState.LISTENING) {
            Log.d("JARVIS-RMS", "RMS recibido: $rmsdB") // 🔍 Debug
            binding.jarvisOrb.updateRms(rmsdB)
        }
    }
    override fun onEndOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}


    override fun onDestroy() {
     stopTtsRhythm()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        if (::speechRecognizer.isInitialized){
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}