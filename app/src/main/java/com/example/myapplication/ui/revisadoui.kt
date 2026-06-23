package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.JarvisState
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PreviewScreen() }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen() {
    val uiState  = remember { JarvisOverlayUiState() }
    val barState = remember { ListeningBarState() }
    var searchQuery by remember { mutableStateOf("") }
    var searchResponse by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var t = 0f
        while (true) {
            androidx.compose.runtime.withFrameMillis {
                t += 0.05f
                barState.animateWithEnergy((kotlin.math.sin(t.toDouble()).toFloat() + 1f) / 2f)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Probar API de Brave:", color = Color.White, style = MaterialTheme.typography.labelLarge)

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Buscar...", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF4DEEE9),
                unfocusedBorderColor = Color(0xFF2C2C3A)
            ),
            singleLine = true
        )

        Button(
            onClick = {
                if (searchQuery.isNotBlank() && !isLoading) {
                    coroutineScope.launch {
                        isLoading = true
                        searchResponse = "Consultando..."
                        searchResponse = withContext(Dispatchers.IO) {
                            try {
                                val client = OkHttpClient.Builder()
                                    .connectTimeout(8, TimeUnit.SECONDS)
                                    .readTimeout(8, TimeUnit.SECONDS)
                                    .build()

                                val json = JSONObject().apply {
                                    put("texto", searchQuery.trim())
                                    put("metadata", JSONObject().apply {
                                        put("lat", -0.266)
                                        put("lon", -78.512)
                                        put("packageName", "com.android.test")  // opcional, ayuda al contexto
                                    })
                                    put("contexto", JSONArray())               // obligatorio, lista vacía
                                    put("contexto_detallado", JSONArray())     // obligatorio, lista vacía
                                }

                                val request = Request.Builder()
                                    .url("https://mausand2499--jarvoice-nexus-api-fastapi-server-dev.modal.run/jarvis")
                                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                                    .build()

                                val response = client.newCall(request).execute()
                                if (response.isSuccessful) {
                                    val body = response.body?.string() ?: "Vacío"
                                    val jsonResp = JSONObject(body)
                                    jsonResp.optString("response_text", body).trim()
                                } else {
                                    "Error HTTP ${response.code}"
                                }
                            } catch (e: Exception) {
                                "Error: ${e.localizedMessage}"
                            }
                        }
                        isLoading = false
                    }
                }
            },
            enabled = searchQuery.isNotBlank() && !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0d2e2e))
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color(0xFF4DEEE9),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF4DEEE9))
            }
            Spacer(Modifier.width(8.dp))
            Text("Buscar", color = Color.White)
        }

        if (searchResponse.isNotEmpty()) {
            Text(
                text = searchResponse,
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Text("Estado Global:", color = Color.White, style = MaterialTheme.typography.labelLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                JarvisState.IDLE      to "IDLE",
                JarvisState.LISTENING to "LISTENING",
                JarvisState.THINKING  to "THINKING",
                JarvisState.SPEAKING  to "SPEAKING"
            ).forEach { (state, label) ->
                Button(
                    onClick = { uiState.applyJarvisState(state) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.jarvisState == state) Color(0xFF4DEEE9) else Color(0xFF2C2C3A)
                    )
                ) { Text(label, color = if (uiState.jarvisState == state) Color.Black else Color.White, fontSize = 10.sp) }
            }
        }

        Text("Pruebas de Panel:", color = Color.White, style = MaterialTheme.typography.labelLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                uiState.showWhatsappPreview = false
                uiState.applyText("Esta es una **respuesta de prueba** con formato.\n• Punto uno\n• Punto dos\n» Sección importante")
            }) { Text("Texto/HTML") }

            Button(
                onClick = {
                    uiState.clearPanel()
                    uiState.pendingWhatsappContact = "Juan Pérez"
                    uiState.pendingWhatsappMessage = "Hola, ¿cómo estás? Te envío este mensaje desde Nexus."
                    uiState.showWhatsappPreview = true
                    uiState.showPanel = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
            ) { Text("Test WhatsApp", color = Color.White) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { uiState.hidePanel() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A))
            ) { Text("Ocultar panel") }

            var simulando by remember { mutableStateOf(false) }
            Button(
                onClick = { if (!simulando) simulando = true },
                enabled = !simulando,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0d2e2e))
            ) { Text("▶ Simular Proceso", color = Color(0xFF4DEEE9)) }

            LaunchedEffect(simulando) {
                if (!simulando) return@LaunchedEffect
                uiState.showWhatsappPreview = false
                uiState.clearPanel()
                uiState.showPanel = true
                uiState.applyJarvisState(JarvisState.THINKING)

                val steps = listOf("Escuchando...", "Analizando...", "Consultando...", "Respondiendo...")
                steps.forEachIndexed { index, s ->
                    uiState.processingSteps = steps.mapIndexed { i, stepStr ->
                        ProcessingStep(stepStr, when {
                            i < index -> StepStatus.DONE
                            i == index -> StepStatus.ACTIVE
                            else -> StepStatus.PENDING
                        })
                    }
                    delay(800)
                }

                uiState.processingSteps = emptyList()
                uiState.applyJarvisState(JarvisState.SPEAKING)
                uiState.applyText("**Simulación terminada.**\nTodo se ejecutó correctamente.")
                simulando = false
            }
        }

        Spacer(Modifier.weight(1f))

        // ── El Componente Real ──────────────────────────────────────────
        JarvisOverlayContent(
            uiState      = uiState,
            barState     = barState,
            onMicClick   = {
                if (uiState.jarvisState == JarvisState.IDLE) uiState.applyJarvisState(JarvisState.LISTENING)
                else uiState.applyJarvisState(JarvisState.IDLE)
            },
            onPauseClick = { uiState.applyJarvisState(JarvisState.IDLE) },
            onBackgroundClick = { uiState.hidePanel() }
        )
    }
}