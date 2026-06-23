package com.example.myapplication.ui

import kotlinx.coroutines.delay
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.core.JarvisState
import com.example.myapplication.core.SpotifyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL
import android.graphics.RectF as AndroidRectF
import android.graphics.Paint as AndroidPaint

private val ColorBgDark     = Color(0xFF1C1C1E)
private val ColorTextMain   = Color(0xFFE8E8F0)
private val ColorChipBg     = Color(0xFF2C2C3A)
private val ColorChipBorder = Color(0xFF3A3A50)

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

    var spotifyTrackTitle  by mutableStateOf("")
    var spotifyTrackArtist by mutableStateOf("")
    var spotifyIsPlaying   by mutableStateOf(false)
    var showSpotifyPlayer  by mutableStateOf(false)
}

//mensaje whatsapp
@Composable
private fun WhatsAppMessagePreview(
    contactName: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                ColorBgDark,
                RoundedCornerShape(22.dp)
            )
            .padding(16.dp)

    ) {
        // Encabezado
        Text(
            text = "Mensaje a $contactName",
            color = Color(0xFF4DEEE9),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Preview estilo WhatsApp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2A3A), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = contactName,
                    color = ColorTextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = message,
                    color = Color(0xFFE8E8F0),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = "Ahora",
                    color = Color(0xFF888899),
                    fontSize = 11.sp
                )
            }
        }

        // Pregunta
        Text(
            text = "¿Enviar este mensaje?",
            color = ColorTextMain,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)
        )

        // Botones
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF3A3A50), RoundedCornerShape(10.dp))
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Text("Cancelar", color = Color(0xFFCCCCEE), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF25D366), RoundedCornerShape(10.dp))
                    .clickable { onConfirm() },
                contentAlignment = Alignment.Center
            ) {
                Text("Enviar", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// Composable raíz
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun JarvisOverlayContent(
    uiState: JarvisOverlayUiState,
    barState: ListeningBarState,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    onBackgroundClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                onClick = onBackgroundClick,
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource()}
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // ✅ Panel SOLO visible en SPEAKING o THINKING con serverProcessing
        AnimatedVisibility(
            visible = uiState.showPanel &&
                    uiState.jarvisState != JarvisState.LISTENING &&
                    uiState.jarvisState != JarvisState.IDLE,
            enter = fadeIn(tween(350)) + slideInVertically(
                animationSpec = tween(450, easing = FastOutSlowInEasing),
                initialOffsetY = { it }
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                animationSpec = tween(250, easing = FastOutSlowInEasing),
                targetOffsetY = { it }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 15.dp)
                .align(Alignment.BottomCenter)
                .clickable(enabled = true, onClick = {})
        ) {
            ResultsPanel(
                uiState = uiState,
                onConfirmWhatsapp = {},
                onCancelWhatsapp = {}
            )
        }

        // Barra siempre visible
        ListeningBarRow(
            uiState      = uiState,
            barState     = barState,
            onMicClick   = onMicClick,
            onPauseClick = onPauseClick,
            modifier     = Modifier
                .width(350.dp)
                .wrapContentHeight()
                .padding(bottom = 33.dp)
                .align(Alignment.BottomCenter)
                .clickable(enabled = true, onClick = {})
        )
    }
}
@Composable
private fun SpotifyPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorBgDark, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎵 Spotify",
                color = Color(0xFF1DB954),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = ColorTextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        } else {
            Text(
                text = "Esperando canción...",
                color = Color(0xFF888899),
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                color = Color(0xFFA0A0B0),
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶️",
                    fontSize = 28.sp,
                    color = Color.White
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(48.dp)
            ) {
                Text("⏭", fontSize = 28.sp, color = Color.White)
            }
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
// ─────────────────────────────────────────────────────────────────────────────
// Barra inferior
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ListeningBarRow(
    uiState: JarvisOverlayUiState,
    barState: ListeningBarState,
    onMicClick: () -> Unit,
    onPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.defaultMinSize(minHeight = 68.dp)) {
        ListeningBar(
            modifier    = Modifier.matchParentSize(),
            state       = barState,
            jarvisState = uiState.jarvisState,
            barColorMode = uiState.barColors
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                JarvisOrb(
                    modifier      = Modifier.size(52.dp, 42.dp),
                    energy        = if (uiState.jarvisState == JarvisState.IDLE) 0f else barState.energy,
                    maxRings      = 3,
                    showLightCore = false,
                    showParticles = false
                )
            Text(
                text = when {
                    uiState.jarvisState == JarvisState.IDLE -> "En que peudo Ayudarte"  //  Siempre "NEXUS" en IDLE
                    uiState.userTranscription.isNotBlank() -> uiState.userTranscription
                    uiState.jarvisState == JarvisState.LISTENING -> ""  //  Vacío en LISTENING
                    uiState.jarvisState == JarvisState.THINKING -> ""   //  Vacío en THINKING
                    uiState.jarvisState == JarvisState.SPEAKING -> ""   //  Vacío en SPEAKING
                    else -> "¿Cómo puedo ayudarte?"
                },
                color = Color(uiState.labelColor),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.sp,
                modifier = Modifier.weight(1f)
            )

            // Pause solo visible en SPEAKING
            AnimatedVisibility(visible = uiState.showPause && uiState.jarvisState == JarvisState.SPEAKING) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 6.dp)
                        .clickable { onPauseClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏸", fontSize = 20.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }

            // Mic button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .padding(start = 6.dp)
                    .clickable { onMicClick() },
                contentAlignment = Alignment.Center
            ) {
                when (uiState.jarvisState) {
                    JarvisState.LISTENING -> MicWaveListening(modifier = Modifier.fillMaxSize(), energy = barState.energy)
                    JarvisState.SPEAKING  -> VoiceWaveCompose(modifier = Modifier.size(44.dp))
                    else                  -> Text("🎙", fontSize = 20.sp, color = Color(0xFFAAAAACC))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Panel de resultados
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ResultsPanel(
    uiState: JarvisOverlayUiState,
    onConfirmWhatsapp: () -> Unit = {},
    onCancelWhatsapp: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorBgDark, RoundedCornerShape(22.dp))
    ) {  //preview de whats
        if (uiState.showWhatsappPreview && uiState.pendingWhatsappContact.isNotBlank()) {
            WhatsAppMessagePreview(
                contactName = uiState.pendingWhatsappContact,
                message = uiState.pendingWhatsappMessage,
                onConfirm = {
                    ActionExecutor.sendWhatsAppMessage(
                        context, uiState.pendingWhatsappContact, uiState.pendingWhatsappMessage
                    )
                    uiState.showWhatsappPreview = false
                    uiState.hidePanel()
                    // Notificar al controller para que limpie esperandoConfirmacion
                    context.sendBroadcast(Intent("JARVIS.CONFIRMATION_DONE").apply {
                        setPackage(context.packageName)
                    })
                    onConfirmWhatsapp()
                },
                onCancel = {
                    uiState.showWhatsappPreview = false
                    uiState.hidePanel()
                    onCancelWhatsapp()
                }
            )
            return  // No mostrar el resto del panel
        }
        if (uiState.showSpotifyPlayer) {
            SpotifyPlayer(
                title = uiState.spotifyTrackTitle,
                artist = uiState.spotifyTrackArtist,
                isPlaying = uiState.spotifyIsPlaying,
                onPlayPause = {
                    if (uiState.spotifyIsPlaying) {
                        SpotifyController.pausar(context)
                    } else {
                        SpotifyController.reproducir(context)
                    }
                },
                onNext = {
                    SpotifyController.siguienteCancion(context)
                },
                onClose = {
                    // Enviar broadcast para que el controlador oculte y detenga actualizaciones
                    context.sendBroadcast(
                        Intent("JARVIS.HIDE_SPOTIFY_PLAYER").apply {
                            setPackage(context.packageName)
                        }
                    )
                }
            )
        }
        if (uiState.imageUrls.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(uiState.imageUrls.take(6)) { url ->
                    NetworkImage(url = url, modifier = Modifier.width(185.dp).fillMaxHeight())
                }
            }
        }

        if (uiState.processingSteps.isNotEmpty()) {
            ProcessingStepsList(steps = uiState.processingSteps)
            Spacer(modifier = Modifier.height(8.dp))
        }

        val scrollState = rememberScrollState()
        val textKey     = uiState.transcription + uiState.typewriterText
        var textVisible by remember(textKey) { mutableStateOf(false) }

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    val displayText = when {
                        uiState.typewriterText.isNotEmpty() -> uiState.typewriterText
                        uiState.fullHtmlText.isNotEmpty() -> {
                            // Solo convertir si realmente tiene contenido
                            Html.fromHtml(uiState.fullHtmlText, Html.FROM_HTML_MODE_LEGACY).toString()
                        }
                        else -> ""
                    }
                    if (displayText.isNotEmpty()) {
                        Text(
                            text       = displayText,
                            color      = ColorTextMain,
                            fontSize   = 15.sp,
                            lineHeight = 20.sp,
                            modifier   = Modifier.padding(bottom = 100.dp)
                        )
                    }
                }

                if (uiState.sourceUrls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.sourceUrls.forEach { url -> SourceChip(url = url) }
                    }
                }
                Spacer(modifier = Modifier.height(55.dp))
            }
        }
    }


// ─────────────────────────────────────────────────────────────────────────────
// NetworkImage
// ─────────────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// SourceChip
// ─────────────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// VoiceWaveCompose
// ─────────────────────────────────────────────────────────────────────────────
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
            color = android.graphics.Color.WHITE; alpha = 200; style = AndroidPaint.Style.FILL
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

// ─────────────────────────────────────────────────────────────────────────────
// Extensiones
// ─────────────────────────────────────────────────────────────────────────────
fun JarvisOverlayUiState.applyJarvisState(state: JarvisState) {
    jarvisState = state
    when (state) {
        JarvisState.LISTENING -> {
            labelText = ""  //  Sin texto
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
            //  RESETEO COMPLETO
            labelText = "NEXUS"
            labelColor = android.graphics.Color.WHITE
            showWave = false
            showPause = false
            barColors = BarColorMode.IDLE
            showPanel = false
            serverProcessing = false
            transcription = ""
            typewriterText = ""
            fullHtmlText = ""
            imageUrls = emptyList()
            sourceUrls = emptyList()
            userTranscription = ""
            processingSteps = emptyList()
            showWhatsappPreview = false
            pendingWhatsappContact = ""
            pendingWhatsappMessage = ""
            showSpotifyPlayer = false
            spotifyTrackTitle = ""
            spotifyTrackArtist = ""
            spotifyIsPlaying = false

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
        // Aumenta a 4 o 5 caracteres para que se vea rápido pero fluido
        index = minOf(index + 5, plainText.length)
        typewriterText = plainText.substring(0, index)
        delay(10) // Un delay menor (10ms es lo mínimo que percibe el ojo como fluido)
    }
}

fun JarvisOverlayUiState.applyImages(urls: List<String>)  { imageUrls  = urls.take(6); showPanel = true }
fun JarvisOverlayUiState.applySources(urls: List<String>) { sourceUrls = urls }

fun JarvisOverlayUiState.clearPanel() {
    transcription = ""; typewriterText = ""; fullHtmlText = ""
    imageUrls = emptyList(); sourceUrls = emptyList()
}

fun JarvisOverlayUiState.hidePanel() { showPanel = false; clearPanel() }