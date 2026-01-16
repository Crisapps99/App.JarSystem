package com.example.myapplication.activity

import ai.picovoice.android.voiceprocessor.VoiceProcessor
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import androidx.lifecycle.lifecycleScope
import android.content.pm.PackageManager
import android.os.Bundle
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.Visualizer
import android.provider.Settings
import android.provider.Telephony
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.view.View
import com.example.myapplication.databinding.ActivityJarBinding
import com.example.myapplication.core.JarvisState
import com.example.myapplication.core.JarvisUi
import com.example.myapplication.core.JarvisVoiceController
import com.example.myapplication.service.MyAccessibilityService
import kotlin.math.sin
import kotlin.random.Random

class JarActivity : AppCompatActivity(), JarvisUi {

    private lateinit var binding: ActivityJarBinding
    private lateinit var controller: com.example.myapplication.core.JarvisVoiceController
    private var jarvisState: JarvisState = JarvisState.IDLE
    private var ttsRhythmRunnable: Runnable? = null
    private var ttsRhythmStart = 0L
    private val RECORD_AUDIO_PERMISSION_CODE = 200
    private var porcupineManager: PorcupineManager? = null //gestiona la deteccion local
    private val ACCESS_KEY = "YMYKZrTBnmQeviXKwGY8rrXUiUlcHBC1ApCQwg6G99JrluupBCFbUg=="
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var isListener: Boolean = false
    private var audioVisualizer: android.media.audiofx.Visualizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestAudioPermission()
        checkAndRequestAccessibility()
        //iniciamos el controlador de voz
        controller = JarvisVoiceController(
            context = this,
            ui = this,
            scope = lifecycleScope
        )
        controller.init()

        binding.micButton.setOnClickListener {
            controller.toggleMic()
        }
//        //rpeubas
//        binding.btnTestEyes.setOnClickListener {
//            // Para probar, enviaremos una acción vacía que solo imprima en consola lo que ve
//            val intent = Intent("JARVIS.EXECUTE_ACTIONS").apply {
//                setPackage(packageName)
//                putExtra("actions_json", "[]") // Lista vacía
//            }
//            sendBroadcast(intent)
//            showToast("Revisa el Logcat para ver qué detectó Jarvis")
//        }
        binding.btnTestEyes.setOnClickListener {
            switchToActivity(
                context = this@JarActivity,
                destinationActivity = ApiTestActivity::class.java,
                finishCurrent = false
            )
        }
        setupPorcupine()//preparamos el oido
    }
    //visualizer
    private fun setupGlobalVisualizer(){
        try{
            audioVisualizer = android.media.audiofx.Visualizer(0).apply{//captura la mescla de salida de audio
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]//configuracion de tamaño de frames
                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener{
                    override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?,samplingRate: Int) {
                        if (currentJarvisState == JarvisState.SPEAKING && wave != null){
                            val energy = calculateEnergy(wave)
                            runOnUiThread {
                                binding.jarvisOrb.updateRms(energy)
                            }
                        }
                    }

                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    }
                }, android.media.audiofx.Visualizer.getMaxCaptureRate()/2, true, false)
                enabled = true
            }
        }catch (e: Exception){
            Log.e("VISUALIZER", "no inicio el visualizer: ${e.message}")
        }
    }
    //convierte bytes crudos en un valor de energia
    private fun calculateEnergy(wave: ByteArray): Float{
        var sum = 0.0
        for(i in 0 until wave.size){
            val sample = (wave[i].toInt() and 0xFF) - 128
            sum += (sample * sample).toDouble()
        }
        val rms = Math.sqrt(sum / wave.size).toFloat()
        // Ajustamos la escala: el visualizer suele dar valores pequeños,
        // multiplicamos para que el orbe se infle bien.
        return (rms / 5f).coerceIn(0f, 15f)
    }
    private fun setupPorcupine(){
        try{
            porcupineManager = PorcupineManager.Builder()//motor d ebusqueda
                    .setAccessKey(ACCESS_KEY)
                    .setKeyword(BuiltInKeyword.JARVIS)
                    .setSensitivity(0.7f)
                    .build ( this ){ keywordIndex->
                        if (keywordIndex == 0){
                            runOnUiThread { startJarvisInteraction() }
                        }
                }
            ///movimiento pasivo motor de movimienot
            val voiceProcessor = VoiceProcessor.getInstance() //el que toca al microfono
            voiceProcessor.addFrameListener { frame ->
                val rms = calculateRMS(frame) //calculamos intencidad
                runOnUiThread {//actualiza la ui
                    if (currentJarvisState == JarvisState.IDLE){//mueve el orbe por ruido ambiental
                        binding.jarvisOrb.updateRms(rms)
                    }
                }
            }
            //
            porcupineManager?.start()//inica escucha
        }catch (e: PorcupineException){
            // Manejo de errores por si la llave es incorrecta o no hay permisos
            Log.e("JARVIS", "Error  Porcupine: ${e.message}")
        }
    }
    //calculo amtematico
    //convierte uan lista de numeros ondas de audio en un solo valor de potencia para que el orbe sepa cuadno inflarse
    private fun calculateRMS(audioData: ShortArray):Float{
        var sum = 0.0
        for (sample in audioData) {
            sum += (sample * sample).toDouble() //elevacion al cuadrado para evitar negativos
        }
            val average = sum / audioData.size
            val rms = Math.sqrt(average) // raiz cuadrada del promedio
            //divicon por 40 para que el valr sea pequeño
            return (rms.toFloat()/400f).coerceIn(0f, 10f)

    }
    private fun startJarvisInteraction(){
        //Le dice al controlador que salude y abra el micro de Google
        controller.startInteraction()
    }
    //actualizacion a google speech
    override fun updateORB(rms: Float){
        runOnUiThread {
            binding.jarvisOrb.updateRms(rms)
        }
    }
    override fun onRecognizerReady() {
        runOnUiThread {
            porcupineManager?.stop()
            Log.d("JARVIS", "Reconocedor de Google listo. Porcupine detenido.")
        }
    }
    private fun resetVisualState() {
        isListener = false
        binding.micButton.visibility = View.VISIBLE
        binding.jarvisOrb.visibility = View.GONE
        // SpeechRecognizer ya termina, Porcupine vuelve a tomar el control del micro.
        try {
            porcupineManager?.start()
        } catch (e: Exception) {
            Log.e("JARVIS", "No se pudo reactivar Porcupine")
        }
    }
    //jarvisUI
    override fun renderState(state: JarvisState) {
        runOnUiThread {
            this.currentJarvisState = state
            //si deja de habalr paramos la animacion
            if (state != JarvisState.SPEAKING)
            when (state) {
                JarvisState.LISTENING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE
                    porcupineManager?.stop()
                }

                JarvisState.THINKING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE

                }

                JarvisState.SPEAKING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE
                }

                JarvisState.IDLE -> {
                    binding.jarvisOrb.reset()
                    binding.jarvisOrb.visibility = View.VISIBLE
                    binding.micButton.visibility = View.VISIBLE
                    reiniciarEscuchaPasiva()
                }
            }
        }
    }
    private fun reiniciarEscuchaPasiva() {
        try {
            porcupineManager?.start()
            Log.d("JARVIS", "Vigilancia reactivada: Esperando palabra clave...")
        } catch (e: Exception) {
            // Si ya estaba encendido, Porcupine lanzará una excepción, la ignoramos.
        }
    }
    //rpeubas
// Dentro de JarActivity
    override fun getCurrentScreenText(): List<String> {
        return com.example.myapplication.core.ScreenMemory.lastSeenTexts // Esta lista se llena con los logs que viste
    }
    override fun showText(text: String) {
        runOnUiThread { binding.transcriptionTextView.text = text }
    }

    override fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        setupGlobalVisualizer()
    }

    override fun onPause() {
        super.onPause()
        audioVisualizer?.release()//liberamos
        audioVisualizer = null
    }

    //    //simulacion con seño para simular la amplitud d ela voz
//    private fun startTtsRhythm() {
//        stopTtsRhythm()
//        ttsRhythmStart = System.currentTimeMillis()
//        ttsRhythmRunnable = object : Runnable {
//            //            private var silenceChance = 0.15f // 15% de pausas
//            override fun run() {
//                if (currentJarvisState == JarvisState.SPEAKING) {
//                    val elapsed = System.currentTimeMillis() - ttsRhythmStart
//
//                    // Simulación de ondas para el orbe
//                    val wave = (sin(elapsed / 80.0)*0.5 + sin(elapsed / 1500.0)*0.5)
//                    val energy = (0.7F + wave.toFloat() * 0.4f + Random.nextFloat() * 0.1f)
//
//                    binding.jarvisOrb.setArtificialEnergy(energy)
//                    binding.jarvisOrb.postDelayed(this, 30) // 25 FPS
//                }
//            }
//        }
//        binding.jarvisOrb.post(ttsRhythmRunnable!!)
//    }

    // Función para saber si el permiso ya está concedido
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // Función para pedir el permiso enviando al usuario a Ajustes
    private fun checkAndRequestAccessibility() {
        if (!isAccessibilityServiceEnabled(this, MyAccessibilityService::class.java)) {
            // Si no está activo, mostramos un aviso o enviamos directo
            Toast.makeText(this, "Por favor, activa el servicio de Jarvis para continuar", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } else {
            showToast("Servicio de Accesibilidad ya está activo")
        }
    }


    //CONCEDER PERMISOS
    private fun requestAudioPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    override fun onDestroy() {
        porcupineManager?.delete()
        VoiceProcessor.getInstance().clearFrameListeners()
        controller.destroy()//para liberar el micro y el tts
        super.onDestroy()
    }

}