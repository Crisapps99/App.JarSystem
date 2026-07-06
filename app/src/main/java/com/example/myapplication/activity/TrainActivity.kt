//package com.example.myapplication.activity
//
//import android.Manifest
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.speech.RecognitionListener
//import android.speech.RecognizerIntent
//import android.speech.SpeechRecognizer
//import android.speech.tts.TextToSpeech
//import android.widget.Button
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AlertDialog
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import androidx.lifecycle.lifecycleScope
//import com.example.myapplication.R
//import com.example.myapplication.api.ActionApiService
//import com.example.myapplication.api.ActionRequest
//import com.example.myapplication.api.CommandData
//import com.example.myapplication.api.OllamaClient
//import com.example.myapplication.api.OllamaRequest
//import com.example.myapplication.api.RetrofitClient
//import com.google.ai.client.generativeai.GenerativeModel
//import com.google.gson.Gson
//import kotlinx.coroutines.launch
//import java.util.*
//
//class TrainActivity : AppCompatActivity(), RecognitionListener {
//
//    private lateinit var txtStatus: TextView
//    private lateinit var btnEscuchar: Button
//
//    private lateinit var speechRecognizer: SpeechRecognizer
//    private lateinit var recognizerIntent: Intent
//
//    private lateinit var tts: TextToSpeech
//    private lateinit var generativeModel: GenerativeModel
//    private val api: ActionApiService = RetrofitClient.actionApiService
//
//    private val MIC_CODE = 200
//
//    private var fraseCapturada = ""
//    private lateinit var command: CommandData
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_train)
//
//        txtStatus = findViewById(R.id.txtStatus)
//        btnEscuchar = findViewById(R.id.btnEscuchar)
//
//        // Inicializa Gemini
//        generativeModel = GenerativeModel(
//            modelName = "gemini-2.5-flash",
//            apiKey = com.example.myapplication.BuildConfig.GEMINI_API_KEY
//        )
//
//        // Inicializar TTS
//        tts = TextToSpeech(this) {
//            tts.language = Locale("es", "ES")
//        }
//
//        requestMicPermission()
//        setupRecognizer()
//
//        btnEscuchar.setOnClickListener {
//            iniciarEscucha()
//        }
//    }
//
//    // =============================
//    // MIC PERMISSION
//    // =============================
//    private fun requestMicPermission() {
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.RECORD_AUDIO
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(
//                this,
//                arrayOf(Manifest.permission.RECORD_AUDIO),
//                MIC_CODE
//            )
//        }
//    }
//
//    // =============================
//    // SPEECH RECOGNIZER
//    // =============================
//    private fun setupRecognizer() {
//        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
//        speechRecognizer.setRecognitionListener(this)
//
//        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
//            putExtra(
//                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
//                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
//            )
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
//            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
//        }
//    }
//
//    private fun iniciarEscucha() {
//        txtStatus.text = " Escuchando..."
//        tts.speak("Entrenemos.", TextToSpeech.QUEUE_FLUSH, null, null)
//        speechRecognizer.startListening(recognizerIntent)
//    }
//
////    // =============================
////    // SPEECH LISTENER CALLBACKS
////    // =============================
////    override fun onResults(bundle: Bundle?) {
////        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
////        if (!results.isNullOrEmpty()) {
////            fraseCapturada = results[0]
////            txtStatus.text = "Frase capturada:\n$fraseCapturada"
////
////            lifecycleScope.launch {
////                clasificarOllama(fraseCapturada)
////            }
////        }
////    }
//
//    override fun onPartialResults(bundle: Bundle?) {}
//    override fun onReadyForSpeech(p0: Bundle?) {}
//    override fun onBeginningOfSpeech() {}
//    override fun onRmsChanged(p0: Float) {}
//    override fun onBufferReceived(p0: ByteArray?) {}
//    override fun onEndOfSpeech() {}
//    override fun onEvent(p0: Int, p1: Bundle?) {}
//    override fun onError(p0: Int) {}
//
//    // =============================
//    // GEMINI CLASSIFIER
//    // =============================
////    private suspend fun clasificarConGemini(texto: String) {
////        txtStatus.text = "Clasificando con Gemini..."
////        val prompt = """
////Eres un sistema experto en clasificación de comandos de voz para un asistente virtual de Android.
////
////Tu tarea es analizar la frase del usuario y devolver SOLO un JSON válido con este formato EXACTO:
////
////{
////  "frase": "texto_original",
////  "intencion": "snake_case",
////  "categoria": "APP_LAUNCH | AUDIO | PANTALLA | RED | SISTEMA | OTRO",
////  "componente": "android_app | android_volume | android_brightness | android_network | android_system"
////}
////
////REGLAS:
////1. Si el usuario nombra una aplicación:
////   - intencion = "abrir_nombreapp"
////   - categoria = "APP_LAUNCH"
////   - componente = "android_app"
////
////2. Si el usuario pide volumen:
////   - intencion = "subir_volumen" / "bajar_volumen" / "silenciar_volumen"
////   - categoria = "AUDIO"
////   - componente = "android_volume"
////
////3. Si pide brillo:
////   - intencion = "subir_brillo" / "bajar_brillo"
////   - categoria = "PANTALLA"
////   - componente = "android_brightness"
////
////4. Si pide conectividad:
////   - intencion = "activar_bluetooth" / "desactivar_wifi" etc.
////   - categoria = "RED"
////   - componente = "android_network"
////
////5. Si pide acciones generales del sistema:
////   - categoria = "SISTEMA"
////   - componente = "android_system"
////
////6. SOLO devuelve JSON. No devuelvas explicaciones.
////
////Frase del usuario: "$texto"
////""".trimIndent()
////
////        try {
////            val respuesta = generativeModel.generateContent(prompt).text ?: "{}"
////
////            // Sanitizar JSON
////            val raw = respuesta.substringAfter("{")
////                .substringBeforeLast("}")
////                .let { "{${it}}" }
////
////            val gson = com.google.gson.Gson()
////            command = gson.fromJson(raw, CommandData::class.java)
////
////            // Mostrar resultado
////            clasificacionGemini = command.categoria
////
////            txtStatus.text = """
////        Frase: $fraseCapturada
////
////        Intención: ${command.intencion}
////        Categoría: ${command.categoria}
////        Componente: ${command.componente}
////    """.trimIndent()
////
////            tts.speak(
////                "He clasificado la frase como ${command.categoria}. ¿Deseas guardarla?",
////                TextToSpeech.QUEUE_FLUSH, null, null
////            )
////
////            mostrarDialogoGuardar()
////
////        } catch (e: Exception) {
////            txtStatus.text = "Error con Gemini: ${e.message}"
////        }
////
////    }
////
////    private suspend fun clasificarOllama(texto: String) {
////
////        val prompt = """
////Eres un extractor de intenciones.
////Devuelve SOLO JSON válido.
////
////FORMATO:
////{
////  "intent": "...",
////  "categoria": "...",
////  "componente": "...",
////  "entities": {}
////}
////
////Frase del usuario: "$texto"
////""".trimIndent()
////
////        txtStatus.text = "Analizando con Ollama..."
////
////        try {
////            val req = OllamaRequest(model = "phi3", prompt = prompt)
////            val resp = OllamaClient.service.generar(req)
////
////            var json = resp.response
////            json = json.substringAfter("{").substringBeforeLast("}")
////            json = "{${json}}"
////
////            command = Gson().fromJson(json, CommandData::class.java)
////
////            //  AGREGAMOS LOS CAMPOS FALTANTES
////            command = command.copy(
////                frase = texto,
////                created_at = Date().toString()
////            )
////
////            mostrarDialogoGuardar()
////
////        } catch (e: Exception) {
////            txtStatus.text = "Error: ${e.message}"
////        }
////    }
//
//
//    // =============================
//    // DIALOGO PARA GUARDAR
//    // =============================
////    private fun mostrarResultado(cmd: CommandData) {
////
////        txtStatus.text = """
//// Resultado NLU (Ollama)
////
////Frase: ${cmd.frase}
////Intención: ${cmd.intent}
////Categoría: ${cmd.categoria}
////Componente: ${cmd.componente}
////Entities: ${cmd.entities}
////""".trimIndent()
////
////        tts.speak("Frase analizada. ¿Deseas guardarla?", TextToSpeech.QUEUE_FLUSH, null, null)
////
////        mostrarDialogoGuardar()
////    }
//
//    // =============================
//    // ENVIO A MONGO
//    // =============================
////    private suspend fun enviarAMongo() {
////        txtStatus.text = " Guardando en Mongo..."
////
////        try {
////            val resp = api.trainexample(command)
////            txtStatus.text = "Guardado \n${resp.message}"
////            mostrarDialogoEntrenar()
////
////        } catch (e: Exception) {
////            txtStatus.text = "Error al guardar: ${e.message}"
////        }
////    }
//
//    // =============================
//    // DIALOGO PARA ENTRENAR
//    // =============================
////    private fun mostrarDialogoEntrenar() {
////        AlertDialog.Builder(this)
////            .setTitle("¿Entrenar ahora?")
////            .setMessage("Se usará todo el dataset almacenado en Mongo Atlas.")
////            .setPositiveButton("SI") { _, _ ->
////                entrenarModelo()
////            }
////            .setNegativeButton("NO") { _, _ ->
////                tts.speak("Entrenamiento cancelado.", TextToSpeech.QUEUE_FLUSH, null, null)
////            }
////            .show()
////    }
////    private fun mostrarDialogoGuardar() {
////
////        val entitiesFormatted = command.entities.entries.joinToString("\n") { "• ${it.key} = ${it.value}" }
////
////        val mensaje = """
//// Resultado NLU (Ollama):
////
////Frase: ${command.frase}
////Intención: ${command.intent}
////Categoría: ${command.categoria}
////Componente: ${command.componente}
////
////Entities:
////$entitiesFormatted
////
////Fecha creación:
////${command.created_at}
////""".trimIndent()
////
////        AlertDialog.Builder(this)
////            .setTitle("¿Guardar ejemplo de entrenamiento?")
////            .setMessage(mensaje)
////            .setPositiveButton("Guardar") { _, _ ->
////                lifecycleScope.launch { enviarAMongo() }
////            }
////            .setNegativeButton("Cancelar") { _, _ -> }
////            .show()
////    }
//
//
//    // =============================
//    // ENTRENAMIENTO
//    // =============================
////    private fun entrenarModelo() {
////        txtStatus.text = " Entrenando modelo..."
////        tts.speak("Iniciando entrenamiento.", TextToSpeech.QUEUE_FLUSH, null, null)
////
////        lifecycleScope.launch {
////            try {
////                val resp = api.trainModel()
////
////                txtStatus.text = " Entrenamiento finalizado.\n${resp.message}"
////
////                tts.speak("Entrenamiento completado.", TextToSpeech.QUEUE_FLUSH, null, null)
////
////            } catch (e: Exception) {
////                txtStatus.text = " Error entrenando: ${e.message}"
////                tts.speak("Error durante el entrenamiento.", TextToSpeech.QUEUE_FLUSH, null, null)
////            }
////        }
////    }
//}
