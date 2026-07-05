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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.text.TextStyle
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

    // --- Estado para el modo conversación ---
    var isConversationMode by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) } // text, isUser

    // ─── EJEMPLO DE MÚSICA PARA PRUEBA ───
    val musicaEjemplo = remember {
        mapOf(
            "title" to "MIENTRAS DUERMES",
            "artist" to "Junior H",
            "album" to "AD BOYZ 4 LIFE II",
            "genre" to "Latin",
            "durationMs" to 226000L,
            "coverUrl" to "https://i.scdn.co/image/ab67616d0000b273d8f5b8e3a5c7f5b8e3a5c7f5",
            "externalUrls" to listOf(
                "https://open.spotify.com/track/4e76Ss3ji7HQZ4qwcPNkNA",
                "https://music.youtube.com/watch?v=HJ9Mzq_wYSc"
            )
        )
    }

    // Simulación de la barra de energía
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ─── TÍTULO ───
        Text(
            "🎨 Vista Previa - Barra Nexus",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // ─── CONTROLES DE MODO ───
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Botón para alternar modo conversación
            Button(
                onClick = {
                    isConversationMode = !isConversationMode
                    uiState.showConversation = isConversationMode
                    if (isConversationMode) {
                        uiState.showPanel = true
                        // Agregar mensaje de bienvenida si está vacío
                        if (chatMessages.isEmpty()) {
                            chatMessages = listOf(
                                "¡Hola! Soy Nexus. ¿En qué puedo ayudarte?" to false
                            )
                        }
                    } else {
                        uiState.showPanel = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConversationMode) Color(0xFF4DEEE9) else Color(0xFF2C2C3A)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (isConversationMode) "💬 Modo Conversación ON" else "💬 Activar Conversación",
                    color = if (isConversationMode) Color.Black else Color.White,
                    fontSize = 12.sp
                )
            }

            // Botón para limpiar mensajes
            Button(
                onClick = {
                    chatMessages = emptyList()
                    uiState.showConversation = false
                    isConversationMode = false
                    uiState.showPanel = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("🗑 Limpiar", color = Color.White, fontSize = 12.sp)
            }
        }

        // ─── BOTONES DE PRUEBA DE MÚSICA ───
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    uiState.showWhatsappPreview = false
                    uiState.clearPanel()
                    uiState.musicTitle = musicaEjemplo["title"] as String
                    uiState.musicArtist = musicaEjemplo["artist"] as String
                    uiState.musicAlbum = musicaEjemplo["album"] as String
                    uiState.musicGenre = musicaEjemplo["genre"] as String
                    uiState.musicDurationMs = musicaEjemplo["durationMs"] as Long
                    uiState.musicCoverUrl = musicaEjemplo["coverUrl"] as String
                    uiState.musicExternalUrls = musicaEjemplo["externalUrls"] as List<String>
                    uiState.showMusicResult = true
                    uiState.showPanel = true
                    uiState.applyJarvisState(JarvisState.IDLE)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                modifier = Modifier.weight(1f)
            ) {
                Text("🎵 Mostrar Música", color = Color.White, fontSize = 11.sp)
            }

            Button(
                onClick = {
                    uiState.clearMusicResult()
                    uiState.showPanel = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("✕ Ocultar", color = Color.White, fontSize = 11.sp)
            }
        }

        // ─── BOTONES DE PRUEBA DE WHATSAPP ───
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    uiState.clearMusicResult()
                    uiState.clearPanel()
                    uiState.pendingWhatsappContact = "Juan Pérez"
                    uiState.pendingWhatsappMessage = "Hola, ¿cómo estás? Te envío este mensaje desde Nexus."
                    uiState.showWhatsappPreview = true
                    uiState.showPanel = true
                    uiState.applyJarvisState(JarvisState.IDLE)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                modifier = Modifier.weight(1f)
            ) {
                Text("💬 WhatsApp Preview", color = Color.White, fontSize = 11.sp)
            }

            Button(
                onClick = {
                    uiState.showWhatsappPreview = false
                    uiState.hidePanel()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("✕ Cerrar", color = Color.White, fontSize = 11.sp)
            }
        }

        Divider(color = Color(0xFF2C2C3A), thickness = 1.dp)

        // ─── CONTROLES DE ESTADO ───
        Text("Estado Global:", color = Color.White, style = MaterialTheme.typography.labelSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                JarvisState.IDLE to "IDLE",
                JarvisState.LISTENING to "🎤",
                JarvisState.THINKING to "💭",
                JarvisState.SPEAKING to "🔊"
            ).forEach { (state, label) ->
                Button(
                    onClick = {
                        uiState.applyJarvisState(state)
                        if (state == JarvisState.IDLE && isConversationMode) {
                            uiState.showConversation = true
                            uiState.showPanel = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.jarvisState == state) Color(0xFF4DEEE9) else Color(0xFF2C2C3A)
                    )
                ) {
                    Text(label, color = if (uiState.jarvisState == state) Color.Black else Color.White, fontSize = 10.sp)
                }
            }
        }

        // ─── PANEL DE RESULTADOS DE PRUEBA ───
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    uiState.showWhatsappPreview = false
                    uiState.clearMusicResult()
                    uiState.applyText("Esta es una **respuesta de prueba** con formato.\n• Punto uno\n• Punto dos\n» Sección importante")
                    uiState.showPanel = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0d2e2e))
            ) {
                Text("📄 Texto/HTML", color = Color(0xFF4DEEE9), fontSize = 11.sp)
            }

            Button(
                onClick = { uiState.hidePanel() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A1A))
            ) {
                Text("✕ Ocultar panel", color = Color.White, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ─── MENSAJES DE CHAT (Vista previa) ───
        if (isConversationMode && chatMessages.isNotEmpty()) {
            Text(
                "💬 Mensajes de prueba:",
                color = Color(0xFF888899),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chatMessages.takeLast(5).forEach { (text, isUser) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isUser) Color(0xFF2A2A3A) else Color(0xFF3A3A50),
                                    RoundedCornerShape(
                                        topStart = 12.dp,
                                        topEnd = 12.dp,
                                        bottomStart = if (isUser) 12.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 12.dp
                                    )
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .widthIn(max = 200.dp)
                        ) {
                            Text(
                                text = text,
                                color = ColorTextMain,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Input de prueba para chat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Escribe un mensaje...", color = Color.Gray, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF4DEEE9),
                        unfocusedBorderColor = Color(0xFF2C2C3A)
                    ),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp)
                )

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            // Agregar mensaje del usuario
                            chatMessages = chatMessages + (inputText to true)
                            // Simular respuesta del asistente después de un momento
                            coroutineScope.launch {
                                delay(500)
                                val responses = listOf(
                                    "¡Excelente pregunta! Déjame pensar...",
                                    "Entendido, procesando tu solicitud...",
                                    "Aquí tienes la información que necesitas.",
                                    "Interesante, ¿puedes darme más detalles?",
                                    "¡Genial! Voy a ayudarte con eso."
                                )
                                val response = responses.random()
                                chatMessages = chatMessages + (response to false)
                            }
                            inputText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4DEEE9))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar", tint = Color.Black)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ─── LA BARRA NEXUS COMPLETA ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF0A0A0F))
        ) {
            UnifiedNexusBottomBar(
                uiState = uiState,
                barState = barState,
                inputText = inputText,
                onInputChange = { inputText = it },
                onMicClick = {
                    if (uiState.jarvisState == JarvisState.IDLE) {
                        uiState.applyJarvisState(JarvisState.LISTENING)
                    } else {
                        uiState.applyJarvisState(JarvisState.IDLE)
                    }
                },
                onPauseClick = {
                    uiState.applyJarvisState(JarvisState.IDLE)
                },
                onOrbClick = {
                    // Simular acción del orbe
                    uiState.applyJarvisState(JarvisState.LISTENING)
                },
                onSendClick = {
                    if (inputText.isNotBlank()) {
                        // Enviar mensaje en modo conversación
                        chatMessages = chatMessages + (inputText to true)
                        coroutineScope.launch {
                            delay(500)
                            val responses = listOf(
                                "¡Excelente pregunta! Déjame pensar...",
                                "Entendido, procesando tu solicitud...",
                                "Aquí tienes la información que necesitas.",
                                "Interesante, ¿puedes darme más detalles?",
                                "¡Genial! Voy a ayudarte con eso."
                            )
                            val response = responses.random()
                            chatMessages = chatMessages + (response to false)
                        }
                        inputText = ""
                    }
                },
                onConversationToggle = {
                    isConversationMode = !isConversationMode
                    uiState.showConversation = isConversationMode
                    if (isConversationMode && chatMessages.isEmpty()) {
                        chatMessages = listOf(
                            "¡Hola! Soy Nexus. ¿En qué puedo ayudarte?" to false
                        )
                    }
                    if (isConversationMode) {
                        uiState.showPanel = true
                    } else {
                        uiState.showPanel = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ─── INFORMACIÓN DE DEBUG ───
        Text(
            text = """
                Debug:
                - Modo: ${if (isConversationMode) "Conversación" else "Normal"}
                - Estado: ${uiState.jarvisState}
                - Panel: ${if (uiState.showPanel) "Visible" else "Oculto"}
                - Mensajes: ${chatMessages.size}
                - Input: "${inputText}"
            """.trimIndent(),
            color = Color(0xFF666677),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ─── ColorTextMain definido localmente para la preview ──────────────────
private val ColorTextMain = Color(0xFFE8E8F0)