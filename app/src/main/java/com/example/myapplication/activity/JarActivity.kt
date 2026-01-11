package com.example.myapplication.activity

import androidx.lifecycle.lifecycleScope
import android.content.pm.PackageManager
import android.os.Bundle
import android.Manifest
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.view.View
import com.example.myapplication.databinding.ActivityJarBinding
import com.example.myapplication.core.JarvisState
import com.example.myapplication.core.JarvisUi
import com.example.myapplication.core.JarvisVoiceController
import kotlin.math.sin
import kotlin.random.Random

class JarActivity : AppCompatActivity(), JarvisUi {

    private lateinit var binding: ActivityJarBinding
    private lateinit var controller: com.example.myapplication.core.JarvisVoiceController
    private var jarvisState: JarvisState = JarvisState.IDLE
    private var ttsRhythmRunnable: Runnable? = null
    private var ttsRhythmStart = 0L
    private val RECORD_AUDIO_PERMISSION_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestAudioPermission()
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
        binding.btnTestApis.setOnClickListener {
            switchToActivity(
                context = this@JarActivity,
                destinationActivity = ApiTestActivity::class.java,
                finishCurrent = false
            )
        }
    }
    //jarvisUI
    override fun renderState (state: JarvisState){
        runOnUiThread {
            jarvisState = state
            //si deja de habalr paramos la animacion
            if (state != JarvisState.SPEAKING) stopTtsRhythm()
            when (state) {
                JarvisState.LISTENING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE
                    binding.jarvisOrb.reset()
                }

                JarvisState.THINKING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE
                    binding.jarvisOrb.setArtificialEnergy(0.25f)//puls lentoo de pensamiento
                }

                JarvisState.SPEAKING -> {
                    binding.micButton.visibility = View.GONE
                    binding.jarvisOrb.visibility = View.VISIBLE
                    startTtsRhythm() //iniciamos animacion de voz
                }

                JarvisState.IDLE -> {
                    binding.jarvisOrb.reset()
                    binding.jarvisOrb.visibility = View.GONE
                    binding.micButton.visibility = View.VISIBLE
                }
            }
        }
    }
    override fun showText(text: String){
        runOnUiThread { binding.transcriptionTextView.text = text }
    }
    override fun showToast(text: String){
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

//    //simulacion con seño para simular la amplitud d ela voz
    private fun startTtsRhythm() {
        stopTtsRhythm()
        ttsRhythmStart = System.currentTimeMillis()
        ttsRhythmRunnable = object : Runnable {
//            private var silenceChance = 0.15f // 15% de pausas
            override fun run() {
                if (jarvisState == JarvisState.SPEAKING) {
                    val elapsed = System.currentTimeMillis() - ttsRhythmStart

                    // Simulación de ondas para el orbe
                    val wave = (sin(elapsed / 120.0) + sin(elapsed / 300.0)) / 2.0
                    val energy = (0.4 + wave * 0.4 + Random.nextFloat() * 0.1).toFloat().coerceIn(0.2f, 0.9f)

                    binding.jarvisOrb.setArtificialEnergy(energy)
                    binding.jarvisOrb.postDelayed(this, 50) // 25 FPS
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

    //CONCEDER PERMISOS
    private fun requestAudioPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    override fun onDestroy() {
        controller.destroy()//para liberar el micro y el tts
        super.onDestroy()
    }
}