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
    private lateinit var btnTestOllama: Button
    private lateinit var btnTestColab: Button
    private lateinit var btnTestActionServer: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_test)

        txtResult = findViewById(R.id.txtApiResult)
        btnTestOllama = findViewById(R.id.btnTestOllama)
        btnTestColab = findViewById(R.id.btnTestColab)
        btnTestActionServer = findViewById(R.id.btnTestActionServer)

        btnTestOllama.setOnClickListener { probarOllama() }
        btnTestColab.setOnClickListener { probarColab() }
        btnTestActionServer.setOnClickListener { probarActionServer() }
    }

    private fun mostrar(msg: String) {
        txtResult.append("\n$msg")
        Log.d("API-TEST", msg)
    }

    // 🔵 1) PRUEBA DE OLLAMA LOCAL O NGROK
    private fun probarOllama() {
        lifecycleScope.launch {
            try {
                mostrar("⏳ Probando Ollama...")

                val resp = service.generar(
                    OllamaRequest(
                        model = "phi3",
                        prompt = "Di 'Hola soy Ollama y estoy conectado'",
                        stream = false
                    )
                )

                mostrar("🟢 OLLAMA RESPONDIÓ:\n${resp.response}")

            } catch (e: Exception) {
                mostrar("🔴 ERROR Ollama:\n${e.message}")
            }
        }
    }

    // 🟡 2) PRUEBA Google Colab / API NLU
    private fun probarColab() {
        lifecycleScope.launch {
            try {
                mostrar("⏳ Probando servidor NLU Colab...")

                val resp = RetrofitClient.actionApiService.predictAction(
                    ActionRequest("abrir whatsapp")
                )

                mostrar("🟢 COLAB RESPONDIÓ:\nIntent = ${resp.action}\nTexto = ${resp.responseText}")

            } catch (e: Exception) {
                mostrar("🔴 ERROR Colab:\n${e.message}")
            }
        }
    }

    // 🔴 3) PRUEBA SERVIDOR DE ACCIONES FastAPI
    private fun probarActionServer() {
        lifecycleScope.launch {
            try {
                mostrar("⏳ Probando Action Server...")

                val resolveResp = ActionServerClient.service.resolveIntent(
                    IntentRequest("abrir_whatsapp")
                )

                mostrar("🟢 ACTION SERVER RESPONDIÓ:\n${Gson().toJson(resolveResp)}")

            } catch (e: Exception) {
                mostrar("🔴 ERROR ActionServer:\n${e.message}")
            }
        }
    }
}