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
import androidx.compose.ui.draw.scale      // <-- necesario para .scale()
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
import android.widget.TextView
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
// ─── COLORES ─────────────────────────────────────────────────────────────
private val ColorBgDark     = Color(0xFF1C1C1E)
private val ColorTextMain   = Color(0xFFE8E8F0)
private val ColorChipBg     = Color(0xFF2C2C3A)
private val ColorChipBorder = Color(0xFF3A3A50)
private val ColorCyanNexus  = Color(0xFF4DEEE9)

// ─── MODELOS ──────────────────────────────────────────────────────────────
enum class Sender { USER, ASSISTANT }

data class Message(
    val id: Int,
    val text: String,
    val sender: Sender,
    val time: String
)

// ─── ESTADO OBSERVABLE ──────────────────────────────────────────────────
class JarvisOverlayUiState {
    var jarvisState         by mutableStateOf(JarvisState.IDLE)
    var labelText           by mutableStateOf("NEXUS")
    var labelColor          by mutableStateOf(android.graphics.Color.WHITE)
    var showPanel           by mutableStateOf(false)
    var transcription       by mutableStateOf("")
    var imageUrls           by mutableStateOf<List<String>>(emptyList())
    var sourceUrls          by mutableStateOf<List<String>>(emptyList())
    var showPause           by mutableStateOf(false)
    var showWave            by mutableStateOf(false)
    var typewriterText      by mutableStateOf("")
    var fullHtmlText        by mutableStateOf("")
    var processingSteps     by mutableStateOf<List<ProcessingStep>>(emptyList())
    var userTranscription   by mutableStateOf("")
    var pendingWhatsappContact by mutableStateOf("")
    var pendingWhatsappMessage by mutableStateOf("")
    var showWhatsappPreview by mutableStateOf(false)

    var barColors           by mutableStateOf(BarColorMode.IDLE)
    var serverProcessing    by mutableStateOf(false)

    // MÚSICA
    var showMusicResult     by mutableStateOf(false)
    var musicTitle          by mutableStateOf("")
    var musicArtist         by mutableStateOf("")
    var musicAlbum          by mutableStateOf("")
    var musicGenre          by mutableStateOf("")
    var musicDurationMs     by mutableStateOf(0L)
    var musicCoverUrl       by mutableStateOf("")
    var musicExternalUrls   by mutableStateOf<List<String>>(emptyList())
    var spotifyCoverUri     by mutableStateOf("")
    var showConversation    by mutableStateOf(false)
    var modoVisualActivo: Boolean = false
}

// ─── PROCESAMIENTO DE PASOS ─────────────────────────────────────────────
enum class StepStatus { DONE, ACTIVE, PENDING }

data class ProcessingStep(
    val text: String,
    val status: StepStatus
)

@Composable
fun ProcessingStepsList(steps: List<ProcessingStep>) {
    val colorDone    = Color(0xFF1DE0A0)
    val colorActive  = Color(0xFF4DEEE9)
    val colorPending = Color(0xFF2C2C3A)
    val colorLine    = Color(0xFF2C2C3A)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 20.dp)) {
        Text(
            text = when {
                steps.any { it.status == StepStatus.ACTIVE } -> "PROCESANDO"
                steps.all { it.status == StepStatus.DONE }   -> "COMPLETADO"
                else -> "PREPARANDO"
            },
            color         = colorActive,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier      = Modifier.padding(bottom = 10.dp)
        )

        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            var visible by remember(step.text) { mutableStateOf(false) }

            LaunchedEffect(step.text) {
                delay(index * 120L)
                visible = true
            }

            AnimatedVisibility(
                visible = visible,
                enter   = fadeIn(tween(300)) + slideInVertically(
                    animationSpec  = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 2 }
                )
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.padding(start = 10.dp)
                    ) {
                        StepDot(
                            status       = step.status,
                            colorDone    = colorDone,
                            colorActive  = colorActive,
                            colorPending = colorPending
                        )
                        if (!isLast) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(colorLine)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text       = step.text,
                        color      = when (step.status) {
                            StepStatus.DONE    -> Color(0xFF6B7280)
                            StepStatus.ACTIVE  -> Color(0xFFE8E8F0)
                            StepStatus.PENDING -> Color(0xFF3A3A50)
                        },
                        fontSize   = 13.sp,
                        fontWeight = if (step.status == StepStatus.ACTIVE) FontWeight.Medium else FontWeight.Normal,
                        modifier   = Modifier.padding(top = 3.dp, bottom = if (!isLast) 12.dp else 0.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDot(
    status: StepStatus,
    colorDone: Color,
    colorActive: Color,
    colorPending: Color
) {
    when (status) {
        StepStatus.DONE -> {
            Box(
                modifier         = Modifier.size(14.dp).clip(CircleShape).background(colorDone),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 8.sp, color = Color(0xFF131618), fontWeight = FontWeight.Bold)
            }
        }
        StepStatus.ACTIVE -> {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue  = 0.85f,
                targetValue   = 1.15f,
                animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label         = "scale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue  = 0.3f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
                label         = "alpha"
            )
            Box(
                modifier         = Modifier.size(14.dp).scale(scale).clip(CircleShape).background(colorActive),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(5.dp).clip(CircleShape)
                        .background(Color(0xFF131618).copy(alpha = alpha))
                )
            }
        }
        StepStatus.PENDING -> {
            Box(
                modifier = Modifier.size(14.dp).clip(CircleShape).background(colorPending)
            )
        }
    }
}

// ─── COMPOSABLE RAÍZ ─────────────────────────────────────────────────────
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

        // ─── PANTALLA DE CONVERSACIÓN (con todos los parámetros) ───
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
                    onBackClose = { uiState.showConversation = false },
                    onMicClick = onMicClick,
                    onPauseClick = onPauseClick,
                    onSendMessage = onSendMessage
                )
            }
        }


        // ─── PANEL DE RESULTADOS ───
        AnimatedVisibility(
            visible = !uiState.showConversation && uiState.showPanel &&
                    uiState.jarvisState != JarvisState.LISTENING &&
                    (uiState.jarvisState != JarvisState.IDLE ||
                            uiState.showMusicResult ||
                            uiState.showWhatsappPreview ||
                            uiState.fullHtmlText.isNotBlank()),
            enter = fadeIn(tween(350)) + slideInVertically(
                animationSpec = tween(450, easing = FastOutSlowInEasing),
                initialOffsetY = { it }
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .wrapContentHeight() // <-- Hace que se adapte al contenido
                .padding(bottom = 9.dp)
                .align(Alignment.BottomCenter) // <-- Lo mantiene pegado al fondo (detrás de la barra)
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
                .fillMaxWidth()
                .padding(horizontal = 0.dp)
                .align(Alignment.BottomCenter)
        )
    }
}

// ─── BARRA INFERIOR UNIFICADA ──────────────────────────────────────────
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
            // Orbe
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
                // Input para modo conversación (ya incluido dentro de la barra)
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
                // Modo normal: texto y controles
                val estaPensandoOProcesando = uiState.jarvisState == JarvisState.THINKING ||
                        uiState.serverProcessing
                val estaEscuchando = uiState.jarvisState == JarvisState.LISTENING

                val colorTextoPrincipal by animateColorAsState(
                    targetValue = when {
                        estaPensandoOProcesando -> Color(uiState.labelColor).copy(alpha = 0.4f)
                        estaEscuchando && uiState.userTranscription.isNotBlank() -> Color(uiState.labelColor).copy(alpha = 1.0f)
                        estaEscuchando -> Color(uiState.labelColor).copy(alpha = 0.8f)
                        uiState.jarvisState == JarvisState.IDLE -> Color(uiState.labelColor).copy(alpha = 1.0f)
                        else -> Color(uiState.labelColor).copy(alpha = 1.0f)
                    },
                    animationSpec = tween(400),
                    label = "textoOpaco"
                )

                Text(
                    text = when {
                        uiState.jarvisState == JarvisState.IDLE -> "¿En qué puedo ayudarte?"
                        uiState.jarvisState == JarvisState.LISTENING && uiState.userTranscription.isNotBlank() ->
                            uiState.userTranscription
                        uiState.jarvisState == JarvisState.LISTENING ->
                            if (uiState.userTranscription.isBlank()) "Escuchando..." else uiState.userTranscription
                        uiState.jarvisState == JarvisState.THINKING ->
                            if (uiState.userTranscription.isNotBlank()) uiState.userTranscription else "Pensando..."
                        uiState.jarvisState == JarvisState.SPEAKING ->
                            if (uiState.userTranscription.isNotBlank()) uiState.userTranscription else "Hablando..."
                        else -> "NEXUS"
                    },
                    color = colorTextoPrincipal,
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

// ─── VISTA DE CONVERSACIÓN (COMPLETA) ──────────────────────────────────
@Composable
fun ConversationViewInsideOverlay(
    uiState: JarvisOverlayUiState,
    chatRepository: ChatRepository,
    onBackClose: () -> Unit,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    onSendMessage: (String) -> Unit
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

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp)
            .shadow(elevation = 24.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = ColorBgDark)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A3A))
                        .clickable { onBackClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Cerrar", tint = ColorCyanNexus, modifier = Modifier.size(22.dp))
                }
                Text("Conversación", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Box(modifier = Modifier.size(40.dp))
            }

            // Contador de mensajes
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(ColorChipBg, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text("${uiMessages.size} mensajes", color = Color(0xFF888899), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Lista de mensajes
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                if (uiMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("💬", fontSize = 40.sp)
                                Text("No hay mensajes aún", color = Color(0xFF888899), fontSize = 14.sp)
                                Text("Empieza una conversación", color = Color(0xFF666677), fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(uiMessages) { msg ->
                        ChatBubbleStyled(message = msg)
                    }
                    if (uiState.jarvisState == JarvisState.THINKING) {
                        item { TypingIndicatorStyled() }
                    }
                }
            }

            // Barra inferior
            ConversationBottomBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSendClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                },
                onMicClick = onMicClick,
                onPauseClick = onPauseClick,
                uiState = uiState
            )
        }
    }
}

@Composable
fun ConversationBottomBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    uiState: JarvisOverlayUiState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(60.dp)
            .background(Color(0xFF1A1A2E), RoundedCornerShape(30.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = inputText,
            onValueChange = onInputChange,
            textStyle = TextStyle(color = ColorTextMain, fontSize = 15.sp),
            cursorBrush = SolidColor(ColorCyanNexus),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (inputText.isEmpty()) {
                        Text("Escribe un mensaje...", color = ColorTextMain.copy(alpha = 0.4f), fontSize = 15.sp)
                    }
                    innerTextField()
                }
            }
        )

        if (uiState.jarvisState == JarvisState.LISTENING) {
            IconButton(onClick = onPauseClick) {
                Icon(Icons.Default.Pause, contentDescription = "Pausar", tint = Color.Red)
            }
        }
        IconButton(onClick = onMicClick) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Micrófono",
                tint = if (uiState.jarvisState == JarvisState.LISTENING) ColorCyanNexus else ColorTextMain
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (inputText.isNotBlank()) Color(0xFF0066FF) else Color(0xFF2A2A3A),
                    CircleShape
                )
                .clickable(enabled = inputText.isNotBlank(), onClick = onSendClick),
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

// ─── BURBUJAS DE CHAT ────────────────────────────────────────────────────
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
            Text(
                text = if (isUser) "Tú" else "Nexus",
                color = if (isUser) Color(0xFF888899) else ColorCyanNexus,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (!isUser && message.text.contains("<")) {
                HtmlText(html = message.text)
            } else {
                Text(
                    text = message.text,
                    color = ColorTextMain,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }

            Text(
                text = message.time,
                color = Color(0xFF666677),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun TypingIndicatorStyled() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A2A3A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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

// ─── OTROS COMPONENTES DE CHAT ──────────────────────────────────────────
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(), // <-- Se adapta al tamaño de la Columna interna
        color = Color(0xFF131315),
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        )
    ) {
        Column(
            modifier = Modifier.wrapContentHeight()
        ) {
            // 1. Manija de arrastre superior
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color(0xFF383842), CircleShape)
                )
            }

            // 2. Contenedor deslizable
            Column(
                modifier = Modifier
                    // Ponemos un límite de altura máxima (ej. 650.dp o 700.dp)
                    // Si el contenido es corto, medirá poco. Si es larguísimo, topará aquí y activará el scroll.
                    .heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {

//                // Carrusel de Imágenes
//                if (uiState.imageUrls.isNotEmpty()) {
//                    LazyRow(
//                        horizontalArrangement = Arrangement.spacedBy(10.dp),
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .padding(bottom = 16.dp)
//                    ) {
//                        items(uiState.imageUrls) { url ->
//                            AsyncImage(
//                                model = url,
//                                contentDescription = null,
//                                contentScale = ContentScale.Crop,
//                                modifier = Modifier
//                                    .size(width = 150.dp, height = 150.dp)
//                                    .clip(RoundedCornerShape(16.dp))
//                            )
//                        }
//                    }
//                }

                // Texto
                if (uiState.fullHtmlText.isNotBlank()) {
                    AndroidView(
                        factory = { context ->
                            TextView(context).apply {
                                setTextColor(android.graphics.Color.parseColor("#E8E8F0"))
                                textSize = 15f
                                setLineSpacing(0f, 1.3f)
                                movementMethod = LinkMovementMethod.getInstance()
                            }
                        },
                        update = { textView ->
                            textView.text = Html.fromHtml(uiState.fullHtmlText, Html.FROM_HTML_MODE_COMPACT)
                        },
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else if (uiState.typewriterText.isNotBlank()) {
                    Text(
                        text = uiState.typewriterText,
                        color = ColorTextMain,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }

                // Barra Inferior de Acciones
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF2C2C3A),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { /* Lógica de fuentes */ }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                                    }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Comentar", tint = Color.Gray, modifier = Modifier.size(22.dp))
                        Icon(Icons.Default.ThumbUp, contentDescription = "Me gusta", tint = Color.Gray, modifier = Modifier.size(22.dp))
                        Icon(Icons.Default.ThumbDown, contentDescription = "No me gusta", tint = Color.Gray, modifier = Modifier.size(22.dp))
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = Color.Gray, modifier = Modifier.size(20.dp))
                        Icon(Icons.Default.MoreVert, contentDescription = "Más", tint = Color.Gray, modifier = Modifier.size(24.dp))
                    }
                }

                // 🛑 EL ESPACIO INVISIBLE OBLIGATORIO
                // Como el panel se adapta a su contenido interno, esto añade 130dp "falsos" al final.
                // Esos 130dp son exactamente los que quedan ocultos detrás de la barra luminosa.
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}
// ─── IMAGEN DE RED ────────────────────────────────────────────────────────
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
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = " Fuente", color = Color(0xFFCCCCEE), fontSize = 12.sp)
    }
}

// ─── ONDA DE VOZ ──────────────────────────────────────────────────────────
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

// ─── EXTENSIONES DE ESTADO ──────────────────────────────────────────────
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
                serverProcessing = false
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

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            android.webkit.WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
//                settings.textZoom = 150
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url ?: return false
                        return try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        } catch (_: Exception) { false }
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()        // ✅ WebView dinámico
            .heightIn(max = 500.dp),
        update = { webView ->
            val wrapped = """
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                body { 
                    margin: 8px; 
                    padding: 0; 
                    background: transparent; 
                    color: #E8E8F0; 
                    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                    font-size: 20px;      //  20px (antes 18)
                    line-height: 1.7;     //  1.7 (antes 1.6)
                }
                a { color: #4DEEE9; }
                p { margin: 8px 0; }
                li { margin: 4px 0; }
                </style>
                </head><body>$html</body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, wrapped, "text/html", "UTF-8", null)
        }
    )
}

// ─── PREVIEWS ─────────────────────────────────────────────────────────────
@Composable
fun previewUiState(): JarvisOverlayUiState {
    return remember {
        JarvisOverlayUiState().apply {
            jarvisState = JarvisState.IDLE
            labelText = "NEXUS"
            showPanel = true
            transcription = "Aquí aparecerán los resultados de búsqueda."
            fullHtmlText = "<b>Resultado de ejemplo</b><br>• Punto 1<br>• Punto 2"
            sourceUrls = listOf("https://ejemplo.com/fuente1", "https://ejemplo.com/fuente2")
            showMusicResult = true
            musicTitle = "Bohemian Rhapsody"
            musicArtist = "Queen"
            musicCoverUrl = "https://i.scdn.co/image/ab67616d0000b273e8b066f70c206551210d4f3b"
        }
    }
}

@Composable
fun previewBarState(): ListeningBarState {
    return remember { ListeningBarState() }
}

@Composable
fun previewChatRepository(): ChatRepository {
    val context = LocalContext.current
    return remember { ChatRepository(context.applicationContext) }
}

@Preview(
    name = "Overlay Principal",
    showBackground = true,
    widthDp = 400,
    heightDp = 850,
    backgroundColor = 0xFF000000
)
@Composable
fun PreviewJarvisOverlay() {
    val uiState = previewUiState()
    val barState = previewBarState()
    val chatRepo = previewChatRepository()

    val onMicClick: () -> Unit = {}
    val onPauseClick: () -> Unit = {}
    val onBackgroundClick: () -> Unit = {}
    val onOrbClick: () -> Unit = {}
    val onSendMessage: (String) -> Unit = {}

    JarvisOverlayContent(
        uiState = uiState,
        barState = barState,
        chatRepository = chatRepo,
        onMicClick = onMicClick,
        onPauseClick = onPauseClick,
        onBackgroundClick = onBackgroundClick,
        onOrbClick = onOrbClick,
        onSendMessage = onSendMessage
    )
}

@Preview(name = "Panel de Resultados")
@Composable
fun PreviewResultsPanel() {
    val uiState = previewUiState()
    ResultsPanel(uiState = uiState)
}

@Preview(name = "Barra Inferior")
@Composable
fun PreviewUnifiedBottomBar() {
    val uiState = previewUiState()
    val barState = previewBarState()
    UnifiedNexusBottomBar(
        uiState = uiState,
        barState = barState,
        inputText = "",
        onInputChange = {},
        onMicClick = {},
        onPauseClick = {},
        onOrbClick = {},
        onSendClick = {},
        onConversationToggle = {}
    )
}