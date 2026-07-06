package com.example.myapplication.ui

import MusicResultCard
import kotlinx.coroutines.delay
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.core.voice.JarvisState
import com.example.myapplication.data.ChatMessageEntity
import com.example.myapplication.data.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL
import android.graphics.RectF as AndroidRectF
import android.graphics.Paint as AndroidPaint
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.unit.dp
// ─── DEFINIR TODOS LOS COLORES AQUÍ (INCLUYENDO ColorCyanNexus) ─────────────
private val ColorBgDark     = Color(0xFF1C1C1E)
private val ColorTextMain   = Color(0xFFE8E8F0)
private val ColorChipBg     = Color(0xFF2C2C3A)
private val ColorChipBorder = Color(0xFF3A3A50)
private val ColorCyanNexus  = Color(0xFF4DEEE9)  // <-- ESTE FALTABA

// ─────────────────────────────────────────────────────────────────────────────
// Modelos de datos locales para la conversación
// ─────────────────────────────────────────────────────────────────────────────
enum class Sender { USER, ASSISTANT }

data class Message(
    val id: Int,
    val text: String,
    val sender: Sender,
    val time: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Estado observable
// ─────────────────────────────────────────────────────────────────────────────
class JarvisOverlayUiState {
    var jarvisState     by mutableStateOf(JarvisState.IDLE)
    var labelText       by mutableStateOf("NEXUS")
    var labelColor      by mutableStateOf(android.graphics.Color.WHITE)
    var showPanel       by mutableStateOf(false)
    var transcription   by mutableStateOf("")
    var imageUrls       by mutableStateOf<List<String>>(emptyList())
    var sourceUrls      by mutableStateOf<List<String>>(emptyList())
    var showPause       by mutableStateOf(false)
    var showWave        by mutableStateOf(false)
    var typewriterText  by mutableStateOf("")
    var fullHtmlText    by mutableStateOf("")
    var processingSteps by mutableStateOf<List<ProcessingStep>>(emptyList())
    var userTranscription by mutableStateOf("")
    var pendingWhatsappContact by mutableStateOf("")
    var pendingWhatsappMessage by mutableStateOf("")
    var showWhatsappPreview by mutableStateOf(false)

    var barColors        by mutableStateOf(BarColorMode.IDLE)
    var serverProcessing by mutableStateOf(false)

    // RECONOCIMIENTO DE MÚSICA
    var showMusicResult by mutableStateOf(false)
    var musicTitle by mutableStateOf("")
    var musicArtist by mutableStateOf("")
    var musicAlbum by mutableStateOf("")
    var musicGenre by mutableStateOf("")
    var musicDurationMs by mutableStateOf(0L)
    var musicCoverUrl by mutableStateOf("")
    var musicExternalUrls by mutableStateOf<List<String>>(emptyList())
    var spotifyCoverUri by mutableStateOf("")
    var showConversation by mutableStateOf(false)
}

// ─── Composable raíz ──────────────────────────────────────────────────────
// ─── MODIFICACIÓN EN COMPOSABLE RAÍZ ──────────────────────────────────────
@Composable
fun JarvisOverlayContent(
    uiState: JarvisOverlayUiState,
    barState: ListeningBarState,
    chatRepository: ChatRepository,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    onBackgroundClick: () -> Unit,
    onOrbClick: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                onClick = {
                    if (uiState.showConversation) {
                        uiState.showConversation = false
                    } else {
                        onBackgroundClick()
                    }
                },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.BottomCenter
    ) {

        // ─── PANTALLA COMPLETA DE CONVERSACIÓN ───
        AnimatedVisibility(
            visible = uiState.showConversation,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp),
                color = Color(0xFF121214),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                ConversationViewInsideOverlay(
                    uiState = uiState,
                    chatRepository = chatRepository,
                    onBackClose = { uiState.showConversation = false }
                )
            }
        }

        // ─── PANEL DE RESULTADOS ESTÁNDAR ───
        AnimatedVisibility(
            visible = !uiState.showConversation && uiState.showPanel &&
                    uiState.jarvisState != JarvisState.LISTENING &&
                    (uiState.jarvisState != JarvisState.IDLE ||
                            uiState.showMusicResult ||
                            uiState.showWhatsappPreview),
            enter = fadeIn(tween(350)) + slideInVertically(
                animationSpec = tween(450, easing = FastOutSlowInEasing),
                initialOffsetY = { it }
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .width(400.dp)
                .padding(bottom = 150.dp)  // ✅ Subido un poco más para que no tape la barra de 130.dp
        ) {
            ResultsPanel(uiState = uiState)
        }

        // ─── BARRA PRINCIPAL UNIFICADA ───
        UnifiedNexusBottomBar(
            uiState = uiState,
            barState = barState,
            inputText = inputText,
            onInputChange = { inputText = it },
            onMicClick = onMicClick,
            onPauseClick = onPauseClick,
            onOrbClick = onOrbClick,
            onSendClick = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            onConversationToggle = {
                uiState.showConversation = !uiState.showConversation
                if (uiState.showConversation) {
                    uiState.showPanel = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()  // ✅ Ocupa todo el ancho
                .padding(horizontal = 0.dp)  // ✅ Sin padding extra
                .align(Alignment.BottomCenter)// ✅ Dejamos que la altura y paddings se controlen internamente
        )
    }
}

@Composable
fun UnifiedNexusBottomBar(
    uiState: JarvisOverlayUiState,
    barState: ListeningBarState,
    inputText: String,
    onInputChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    onOrbClick: () -> Unit,
    onSendClick: () -> Unit,
    onConversationToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConversationMode = uiState.showConversation

    // Dimensiones de control explícitas
    val barHeight         = 100.dp
    val orbSize           = 40.dp
    val campoAltura       = 60.dp
    val iconSize          = 26.dp
    val paddingHorizontal = 20.dp
    val borderRadius      = 50.dp
    val spacingEntreItems = 15.dp

    Box(
        modifier = modifier
                .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        // Fondo de la barra con luces y esquinas redondeadas tipo píldora
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(borderRadius))
        ) {
            ListeningBar(
                modifier = Modifier.fillMaxSize(),
                state = barState,
                jarvisState = uiState.jarvisState,
                barColorMode = uiState.barColors
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = paddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacingEntreItems)
        ) {
            // Orbe principal
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (uiState.jarvisState == JarvisState.IDLE) {
                            onOrbClick()
                        }
                    }
            ) {
                JarvisOrb(
                    modifier = Modifier.fillMaxSize(),
                    energy = if (uiState.jarvisState == JarvisState.IDLE) 0f else barState.energy,
                    maxRings = 3,
                    showLightCore = true,
                    showParticles = true
                )
            }

            if (isConversationMode) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(campoAltura)
                        .background(
                            Color(0xFF2A2A3A).copy(alpha = 0.6f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        textStyle = TextStyle(
                            color = ColorTextMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(ColorCyanNexus),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "Escribe un mensaje...",
                                        color = ColorTextMain.copy(alpha = 0.4f),
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { onMicClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Micrófono",
                            tint = ColorTextMain.copy(alpha = 0.7f),
                            modifier = Modifier.size(iconSize)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (inputText.isNotBlank()) Color(0xFF0066FF) else ColorChipBg,
                                CircleShape
                            )
                            .clickable(
                                enabled = inputText.isNotBlank(),
                                onClick = onSendClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Enviar",
                            tint = if (inputText.isNotBlank()) Color.White else ColorTextMain.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = when {
                        uiState.jarvisState == JarvisState.IDLE -> "¿En qué puedo ayudarte?"
                        uiState.userTranscription.isNotBlank() -> uiState.userTranscription
                        uiState.jarvisState == JarvisState.LISTENING -> "🎤 Escuchando..."
                        uiState.jarvisState == JarvisState.THINKING -> "💭 Pensando..."
                        uiState.jarvisState == JarvisState.SPEAKING -> "🔊 Hablando..."
                        else -> "NEXUS"
                    },
                    color = Color(uiState.labelColor),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (!isConversationMode) {
                                onConversationToggle()
                            }
                        }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimatedVisibility(
                        visible = uiState.showPause && uiState.jarvisState == JarvisState.SPEAKING
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onPauseClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pausar",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clickable {
                                if (uiState.jarvisState == JarvisState.LISTENING) {
                                    onPauseClick()
                                } else {
                                    onMicClick()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when (uiState.jarvisState) {
                            JarvisState.LISTENING -> MicWaveListening(
                                modifier = Modifier.size(38.dp),
                                energy = barState.energy
                            )
                            JarvisState.SPEAKING -> VoiceWaveCompose(
                                modifier = Modifier.size(38.dp)
                            )
                            else -> Icon(
                                Icons.Default.Mic,
                                contentDescription = "Micrófono",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                color = if (isConversationMode) {
                                    Color(0xFF6C3CE1).copy(alpha = 0.3f)
                                } else {
                                    Color.White.copy(alpha = 0.1f)
                                }
                            )
                            .clickable { onConversationToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConversationMode) Icons.Default.Chat else Icons.Default.ChatBubbleOutline,
                            contentDescription = "Conversación",
                            tint = if (isConversationMode) Color(0xFF4DEEE9) else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
// ─── CONVERSATION VIEW INSIDE OVERLAY ────────────────────────────────────
// ─── CONVERSATION VIEW INSIDE OVERLAY (ESTILO RESULTS PANEL) ──────────────
@Composable
fun ConversationViewInsideOverlay(
    uiState: JarvisOverlayUiState,
    chatRepository: ChatRepository,
    onBackClose: () -> Unit
) {
    val messagesFlow = chatRepository.getMessagesForSession().collectAsState(initial = emptyList())
    val roomMessages = messagesFlow.value
    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val uiMessages = remember(roomMessages) {
        roomMessages.map { entity ->
            Message(
                id = entity.id.toInt(),
                text = entity.content,
                sender = if (entity.sender == "USER") Sender.USER else Sender.ASSISTANT,
                time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(entity.timestamp))
            )
        }
    }

    // ─── CONTENEDOR PRINCIPAL (ESTILO RESULTS PANEL) ──────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)  // ✅ Ocupa 85% de la altura
            .background(ColorBgDark, RoundedCornerShape(28.dp))  // ✅ Mismo fondo que ResultsPanel
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(28.dp),
                clip = false
            )
            .padding(16.dp)
    ) {
        // ─── TOP BAR (ESTILO RESULTS PANEL) ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Botón cerrar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A3A))
                    .clickable { onBackClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Cerrar",
                    tint = ColorCyanNexus,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Título
            Text(
                text = "💬 Conversación",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            // Espacio para balance
            Box(modifier = Modifier.size(40.dp))
        }

        // ─── CONTADOR DE MENSAJES (ESTILO CHIP) ──────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(ColorChipBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Text(
                text = "${uiMessages.size} mensajes",
                color = Color(0xFF888899),
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ─── LISTA DE MENSAJES (ESTILO RESULTS PANEL) ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF121214).copy(alpha = 0.5f))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiMessages.isEmpty()) {
                    // Mensaje vacío (estilo MusicResultCard vacío)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "💬",
                                fontSize = 40.sp
                            )
                            Text(
                                text = "No hay mensajes aún",
                                color = Color(0xFF888899),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                text = "Empieza una conversación",
                                color = Color(0xFF666677),
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    uiMessages.forEach { msg ->
                        ChatBubbleStyled(message = msg)
                    }

                    if (uiState.jarvisState == JarvisState.THINKING) {
                        TypingIndicatorStyled()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ─── BARRA DE INPUT (ESTILO RESULTS PANEL) ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Color(0xFF1A1A2E), RoundedCornerShape(28.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Campo de texto
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                textStyle = TextStyle(
                    color = ColorTextMain,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(ColorCyanNexus),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "Escribe un mensaje...",
                                color = ColorTextMain.copy(alpha = 0.4f),
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Micrófono
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A3A))
                    .clickable {
                        // Acción de micrófono
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Micrófono",
                    tint = ColorCyanNexus,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Botón enviar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank()) Color(0xFF0066FF) else Color(0xFF2A2A3A)
                    )
                    .clickable(
                        enabled = inputText.isNotBlank(),
                        onClick = {
                            if (inputText.isNotBlank()) {
                                // Enviar mensaje
                                inputText = ""
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Enviar",
                    tint = if (inputText.isNotBlank()) Color.White else ColorTextMain.copy(alpha = 0.3f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─── CHAT BUBBLE ESTILIZADA ─────────────────────────────────────────────
@Composable
fun ChatBubbleStyled(message: Message) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) Color(0xFF2A2A3A) else Color(0xFF1A2A3A)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Nombre del remitente
            Text(
                text = if (isUser) "Tú" else "Nexus",
                color = if (isUser) Color(0xFF888899) else ColorCyanNexus,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Mensaje
            Text(
                text = message.text,
                color = ColorTextMain,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            // Hora
            Text(
                text = message.time,
                color = Color(0xFF666677),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ─── TYPING INDICATOR ESTILIZADO ────────────────────────────────────────
@Composable
fun TypingIndicatorStyled() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A2A3A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Avatar Nexus
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ColorCyanNexus.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◆",
                color = ColorCyanNexus,
                fontSize = 14.sp
            )
        }

        // Puntos animados
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { index ->
                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = index * 150, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ColorCyanNexus.copy(alpha = alpha))
                )
            }
        }
    }
}
// ─── COMPONENTES ADICIONALES DE CHAT UI ──────────────────────────────────────
@Composable
fun ChatBubble(message: Message) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp
                    )
                )
                .background(if (isUser) Color(0xFF2A2A3A) else Color(0xFF3A3A50))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 280.dp)
        ) {
            Text(text = message.text, color = ColorTextMain, fontSize = 14.sp)
        }
    }
}

@Composable
fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, ColorChipBorder, RoundedCornerShape(16.dp))
            .background(ColorChipBg, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = Color(0xFFCCCCEE), fontSize = 12.sp)
    }
}

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ), label = "dotAlpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4DEEE9).copy(alpha = alpha))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomInputBar(text: String, onValueChange: (String) -> Unit, onSendClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            value = text,
            onValueChange = onValueChange,
            placeholder = { Text("Escribe un mensaje...", color = Color(0xFF888899)) },
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedTextColor = ColorTextMain,
                unfocusedTextColor = ColorTextMain,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(24.dp)
        )
        FloatingActionButton(
            onClick = onSendClick,
            containerColor = Color(0xFF2A2A3A),
            contentColor = Color(0xFF4DEEE9),
            shape = CircleShape,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Enviar")
        }
    }
}

// ─── Función auxiliar IconButton ──────────────────────────────────────────
@Composable
private fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}


// ─── PANEL DE RESULTADOS ──────────────────────────────────────────────────
@Composable
fun ResultsPanel(uiState: JarvisOverlayUiState) {
    // Contenedor principal con centrado y márgenes profesionales
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(elevation = 20.dp, shape = RoundedCornerShape(24.dp))
            .background(Color(0xFF1C1C1E), RoundedCornerShape(24.dp))
            .border(1.dp, Color(0xFF3A3A50), RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- SECCIÓN DE TÍTULO / HEADER TIPO BUSCADOR ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ColorCyanNexus.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔍", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Resultados de búsqueda",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- CONTENIDO: Separador con puntos y estructura limpia ---
        Divider(color = Color(0xFF3A3A50), thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        val displayText = if (uiState.typewriterText.isNotBlank()) {
            uiState.typewriterText
        } else {
            uiState.transcription   // o uiState.fullHtmlText si quieres HTML
        }

        Text(
            text = displayText,
            color = ColorTextMain,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )

        // --- FUENTES / URLS (Si existen) ---
        if (uiState.sourceUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Fuentes consultadas:", color = Color.Gray, fontSize = 12.sp)
            uiState.sourceUrls.take(2).forEach { url ->
                Text("• ${url.take(30)}...", color = ColorCyanNexus, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun MusicResultCardStyled(uiState: JarvisOverlayUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2C2C3A), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Imagen (si tienes URL)
        AsyncImage(
            model = uiState.musicCoverUrl,
            contentDescription = "Cover",
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(uiState.musicTitle, color = Color.White, fontWeight = FontWeight.Bold)
            Text(uiState.musicArtist, color = Color.Gray, fontSize = 13.sp)
            Text("• ${uiState.musicGenre}", color = ColorCyanNexus, fontSize = 12.sp)
        }
    }
}
// ─── NetworkImage ─────────────────────────────────────────────────────────
@Composable
private fun NetworkImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try { bitmap = BitmapFactory.decodeStream(URL(url).openStream()) } catch (_: Exception) {}
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap             = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = modifier.clickable {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }
        )
    } else {
        Box(modifier = modifier.background(Color(0xFF2A2A3A)))
    }
}

// ─── SourceChip ───────────────────────────────────────────────────────────
@Composable
private fun SourceChip(url: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ColorChipBg)
            .border(1.dp, ColorChipBorder, RoundedCornerShape(14.dp))
            .clickable {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = "🔗 Fuente", color = Color(0xFFCCCCEE), fontSize = 12.sp)
    }
}

// ─── VoiceWaveCompose ────────────────────────────────────────────────────
@Composable
private fun VoiceWaveCompose(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label         = "wavePhase"
    )
    val wavePaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = 200
            style = AndroidPaint.Style.FILL
        }
    }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val barCount = 5
        val barWidth = size.width / (barCount * 2f)
        val maxH     = size.height * 0.75f
        drawIntoCanvas { canvas ->
            for (i in 0 until barCount) {
                val x   = barWidth + i * barWidth * 2f
                val h   = maxH * (0.3f + 0.7f * ((kotlin.math.sin((phase + i * 0.8f).toDouble()).toFloat() + 1f) / 2f))
                val top = (size.height - h) / 2f
                canvas.nativeCanvas.drawRoundRect(
                    AndroidRectF(x - barWidth / 2f, top, x + barWidth / 2f, top + h), 4f, 4f, wavePaint
                )
            }
        }
    }
}

// ─── Extensiones de JarvisOverlayUiState ──────────────────────────────
fun JarvisOverlayUiState.clearMusicResult() {
    showMusicResult = false
    musicTitle = ""
    musicArtist = ""
    musicAlbum = ""
    musicGenre = ""
    musicDurationMs = 0L
    musicCoverUrl = ""
    musicExternalUrls = emptyList()
}

fun JarvisOverlayUiState.applyJarvisState(state: JarvisState) {
    jarvisState = state
    when (state) {
        JarvisState.LISTENING -> {
            labelText = ""
            labelColor = android.graphics.Color.parseColor("#4DEEE9")
            showWave = false
            showPause = false
            barColors = BarColorMode.LISTENING
            showPanel = false
            serverProcessing = false
        }
        JarvisState.THINKING  -> {
            labelText = ""
            labelColor = android.graphics.Color.parseColor("#7BD7F8")
            showWave = false
            showPause = false
            barColors = BarColorMode.THINKING
        }
        JarvisState.SPEAKING  -> {
            labelText = ""
            labelColor = android.graphics.Color.parseColor("#1DE0A0")
            showWave = true
            showPause = true
            barColors = BarColorMode.SPEAKING
        }
        JarvisState.IDLE      -> {
            labelText = "NEXUS"
            labelColor = android.graphics.Color.WHITE
            showWave = false
            showPause = false
            barColors = BarColorMode.IDLE

            if (!showMusicResult &&
                transcription.isBlank() && fullHtmlText.isBlank()) {
                showPanel = false
                imageUrls = emptyList()
                sourceUrls = emptyList()
            }
            serverProcessing = false
            transcription = ""
            typewriterText = ""
            fullHtmlText = ""

            userTranscription = ""
            processingSteps = emptyList()
            showWhatsappPreview = false
            pendingWhatsappContact = ""
            pendingWhatsappMessage = ""
        }
    }
}

fun JarvisOverlayUiState.applyText(text: String) {
    val formatted = text
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("» (.*?)"), "<font color='#4DEEE9'><b>» $1</b></font>")
        .replace("•", "<font color='#4DEEE9'>•</font>")
        .replace("\n", "<br/>")
    fullHtmlText   = formatted
    typewriterText = ""
    transcription  = Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY).toString()
    showPanel      = true
}

suspend fun JarvisOverlayUiState.startTypewriter(plainText: String, delayMs: Long = 0) {
    typewriterText = ""
    if (delayMs > 0) delay(delayMs)
    var index = 0
    while (index < plainText.length && kotlinx.coroutines.currentCoroutineContext().isActive) {
        index = minOf(index + 5, plainText.length)
        typewriterText = plainText.substring(0, index)
        delay(10)
    }
    typewriterText = plainText
}

fun JarvisOverlayUiState.applyImages(urls: List<String>) {
    imageUrls = urls.take(6)
    showPanel = true
}

fun JarvisOverlayUiState.applySources(urls: List<String>) {
    sourceUrls = urls
}

fun JarvisOverlayUiState.clearPanel() {
    transcription = ""
    typewriterText = ""
    fullHtmlText = ""
    imageUrls = emptyList()
    sourceUrls = emptyList()
}

fun JarvisOverlayUiState.hidePanel() {
    showPanel = false
    clearPanel()
}