package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.JarvisState
import kotlinx.coroutines.delay

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PreviewScreen() }
    }
}

@Composable
private fun PreviewScreen() {
    val uiState  = remember { JarvisOverlayUiState() }
    val barState = remember { ListeningBarState() }

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
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.jarvisState == state) Color(0xFF4DEEE9) else Color(0xFF2C2C3A)
                    )
                ) { Text(label, color = if (uiState.jarvisState == state) Color.Black else Color.White, fontSize = 10.sp) }
            }
        }

        Text("Pruebas de Panel:", color = Color.White, style = MaterialTheme.typography.labelLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                uiState.showWhatsappPreview = false // Reset WhatsApp
                uiState.applyText("Esta es una **respuesta de prueba** con formato.\n• Punto uno\n• Punto dos\n» Sección importante")
            }) { Text("Texto/HTML") }

            // ── NUEVO BOTÓN WHATSAPP ───────────────────────────────────────
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