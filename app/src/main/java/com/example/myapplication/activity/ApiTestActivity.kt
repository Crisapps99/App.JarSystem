package com.example.myapplication.activity

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.api.*
import com.example.myapplication.api.OllamaClient.service
import com.google.gson.Gson
import kotlinx.coroutines.launch

class ApiTestActivity : AppCompatActivity() {

    private lateinit var txtResult: TextView
    private lateinit var btnTestColab: Button
    private lateinit var btnGemma: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_test)

        btnGemma = findViewById(R.id.btnGemma)
        btnGemma.setOnClickListener { probarCerebroUnificado() }

        txtResult = findViewById(R.id.txtApiResult)
        btnTestColab = findViewById(R.id.btnTestColab)
        btnTestColab.setOnClickListener { probarColab() }
    }

    private fun mostrar(msg: String) {
        txtResult.append("\n$msg")
        Log.d("API-TEST", msg)
    }
    // PRUEBA Google Colab / API NLU
    private fun probarColab() {
        lifecycleScope.launch {
            try {
                mostrar("⏳ Probando servidor NLU Colab...")

                val resp = RetrofitClient.actionApiService.predictAction(
                    ActionRequest("abrir whatsapp")
                )

                mostrar("COLAB RESPONDIÓ:\nIntent = ${resp.action}\nTexto = ${resp.response_text}")

            } catch (e: Exception) {
                mostrar("ERROR Colab:\n${e.message}")
            }
        }
    }
    private fun probarCerebroUnificado() {
        lifecycleScope.launch {
            try {
                mostrar("⏳ Probando Gemma en Colab...")

                // Probamos con una frase de comando
                val resp = RetrofitClient.actionApiService.predictAction(
                    ActionRequest("abre whatsapp y escribe hola")
                )

                if (resp.success) {
                    mostrar("🟢 ÉXITO:")
                    mostrar("💬 Frase de Jarvis: ${resp.response_text}")
                    mostrar("🤖 Modo: ${resp.mode}")
                    mostrar("📦 Payload acciones: ${resp.payload?.size ?: 0}")
                } else {
                    mostrar("🟠 El servidor respondió pero success es false")
                }

            } catch (e: Exception) {
                mostrar("🔴 ERROR DE CONEXIÓN:\n${e.message}")
            }
        }
    }
}