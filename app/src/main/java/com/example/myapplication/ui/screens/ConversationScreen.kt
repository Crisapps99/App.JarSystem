package com.example.myapplication.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.voice.JarvisState

// Colores del sistema Nexus
private val ColorBgDark     = Color(0xFF1C1C1E)
private val ColorTextMain   = Color(0xFFE8E8F0)
private val ColorChipBg     = Color(0xFF2C2C3A)
private val ColorChipBorder = Color(0xFF3A3A50)
private val ColorCyanNexus  = Color(0xFF4DEEE9)

// ─── VISTA INTERNA DE CONVERSACIÓN MODIFICADA ──────────────────────────────
@Composable
fun ConversationViewInsideOverlay(
    uiState: JarvisOverlayUiState,
    onBackClose: () -> Unit,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            Message(1, "Hola, ¿en qué puedo ayudarte hoy?", Sender.ASSISTANT, "10:42 AM"),
            Message(2, uiState.userTranscription.ifBlank { "Me gustaría ver opciones para el fin de semana." }, Sender.USER, "10:43 AM")
        )
    }
    val actionChips = listOf("¿Qué precio tiene?", "Ver más fotos", "¿Hay WiFi?")

    Column(modifier = Modifier.fillMaxSize()) {
        // TopBar del Chat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBackClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Cerrar", tint = ColorCyanNexus)
            }
            Text(
                text = "Conversación con Nexus",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color(0xFF888899))
            }
        }

        // Historial de mensajes
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Hoy",
                        modifier = Modifier
                            .background(ColorChipBg, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFF888899), fontSize = 11.sp
                    )
                }
            }

            items(messages) { msg ->
                ChatBubble(message = msg)
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(actionChips) { chipText ->
                        SuggestionChip(text = chipText) {
                            messages.add(Message(messages.size + 1, chipText, Sender.USER, "Ahora"))
                        }
                    }
                }
            }

            if (uiState.jarvisState == JarvisState.THINKING) {
                item { TypingIndicator() }
            }
        }

        // BARRA UNIFICADA ESTILO NEXUS OVERLAY
        NexusUnifiedBottomBar(
            text = inputText,
            onValueChange = { inputText = it },
            uiState = uiState,
            onSendClick = {
                if (inputText.isNotBlank()) {
                    messages.add(Message(messages.size + 1, inputText, Sender.USER, "Ahora"))
                    inputText = ""
                }
            },
            onMicClick = onMicClick,
            onPauseClick = onPauseClick
        )
    }
}

// ─── BARRA DE ENTRADA AVANZADA (UNIFICADA ESTILO NEXUS) ──────────────────────
@Composable
fun NexusUnifiedBottomBar(
    text: String,
    onValueChange: (String) -> Unit,
    uiState: JarvisOverlayUiState,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ColorBgDark.copy(alpha = 0.9f), CircleShape)
                .border(1.dp, ColorChipBorder, CircleShape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón Más / Adjuntar (Aquí puedes invocar tu orbe o menú adicional)
            IconButton(onClick = { /* Acción adicional o animación de Orbe */ }) {
                Icon(
                    imageVector = Icons.Default.AddCircle,
                    contentDescription = "Opciones",
                    tint = ColorCyanNexus
                )
            }

            // Campo de Texto Principal (BasicTextField)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Escribe un mensaje...",
                        color = ColorTextMain.copy(alpha = 0.5f),
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(color = ColorTextMain, fontSize = 15.sp),
                    cursorBrush = SolidColor(ColorCyanNexus),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Controles de Audio Activos (Micrófono y Botón de Pausa opcional si está escuchando)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (uiState.jarvisState == JarvisState.LISTENING) {
                    IconButton(onClick = onPauseClick) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pausar",
                            tint = Color.Red
                        )
                    }
                }

                IconButton(onClick = onMicClick) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Micrófono",
                        tint = if (uiState.jarvisState == JarvisState.LISTENING) ColorCyanNexus else ColorTextMain
                    )
                }
            }

            // Botón de Enviar (Solo resalta si hay texto o acción)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (text.isNotBlank()) ColorCyanNexus else ColorChipBg,
                        CircleShape
                    )
                    .clickable(enabled = text.isNotBlank(), onClick = onSendClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Enviar",
                    tint = if (text.isNotBlank()) ColorBgDark else ColorTextMain.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 🔥 SECCIÓN DE PREVISUALIZACIÓN (JETPACK COMPOSE PREVIEW)
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Nexus Chat - Estado Inactivo", showBackground = true, backgroundColor = 0xFF121214)
@Composable
fun ConversationViewInsideOverlayPreview() {
    // Simulamos un estado inicial por defecto
    val mockUiState = remember {
        JarvisOverlayUiState().apply {
            jarvisState = JarvisState.IDLE
            userTranscription = "Opciones para el fin de semana"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121214) // Color de fondo oscuro a juego con tu overlay
    ) {
        ConversationViewInsideOverlay(
            uiState = mockUiState,
            onBackClose = {},
            onMicClick = {},
            onPauseClick = {}
        )
    }
}

@Preview(name = "Nexus Chat - Escuchando (Listening)", showBackground = true, backgroundColor = 0xFF121214)
@Composable
fun ConversationViewInsideOverlayListeningPreview() {
    // Simulamos el estado cuando el micrófono está activo y el asistente escucha
    val mockUiState = remember {
        JarvisOverlayUiState().apply {
            jarvisState = JarvisState.LISTENING
            userTranscription = ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121214)
    ) {
        ConversationViewInsideOverlay(
            uiState = mockUiState,
            onBackClose = {},
            onMicClick = {},
            onPauseClick = {}
        )
    }
}