package com.example.myapplication.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds // ← IMPORTANTE para el efecto túnel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.core.JarvisState
import com.example.myapplication.ui.*
import com.example.myapplication.viewmodel.JarPhase
import com.example.myapplication.viewmodel.JarVm
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.platform.LocalDensity

@Composable
fun MainScreen(
    vm: JarVm,
    onMicClick: () -> Unit,
    onStopListening: () -> Unit
) {
    val state by vm.state.collectAsState()
    val barState = rememberListeningBarState()

    // Definimos estados lógicos según la fase
    val isListening = state.phase == JarPhase.LISTENING
    val isSpeaking = state.phase == JarPhase.SPEAKING
    val isThinking = state.phase == JarPhase.THINKING
    val isIdle = state.phase == JarPhase.WAITING_WAKEWORD || state.phase == JarPhase.INTRO

    val isSystemActive = isListening || isSpeaking || isThinking

    LaunchedEffect(state.orbRms) {
        barState.animateWithEnergy(state.orbRms)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E10))
    ) {
        // Fondo difuso (orbe de luz)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00DAF3).copy(alpha = 0.4f),
                            Color(0xFFADC6FF).copy(alpha = 0.2f),
                            Color(0xFF4029BA).copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        radius = 1.5f
                    )
                )
        )

        // Contenido principal
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Tarjeta de saludo / transcripción (Modificada con tus nuevas reglas de texto)
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + slideInVertically { it } togetherWith
                            fadeOut(animationSpec = tween(300)) + slideOutVertically { -it }
                },
                modifier = Modifier.padding(bottom = 32.dp)
            ) { phase ->
                val text = when (phase) {
                    JarPhase.WAITING_WAKEWORD -> "Da clic en el micrófono para comenzar" // ← Ajustado
                    JarPhase.INTRO -> "Hola, ¿en qué puedo ayudarte hoy?"
                    JarPhase.LISTENING -> "Di hey Nexus para comenzar"               // ← Ajustado
                    JarPhase.THINKING -> "Pensando..."
                    JarPhase.SPEAKING -> state.transcription.ifEmpty { "Hablando..." }
                    else -> ""
                }
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.White.copy(alpha = 0.03f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = text,
                        color = Color(0xFFE2E2E5),
                        fontSize = if (isIdle) 20.sp else 18.sp, // Ajustado ligeramente el tamaño para albergar textos largos
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Orbe central
            JarvisOrb(
                modifier = Modifier.size(240.dp),
                energy = state.orbRms,
                maxRings = 4,
                showLightCore = true,
                showParticles = true
            )

            Spacer(modifier = Modifier.height(80.dp))
        }

//        // Barra de ondas
//        AnimatedVisibility(
//            visible = isListening || isSpeaking,
//            enter = fadeIn() + scaleIn(),
//            exit = fadeOut() + scaleOut()
//        ) {
//            Box(
//                modifier = Modifier
//                    .align(Alignment.BottomCenter)
//                    .padding(bottom = 150.dp)
//            ) {
//                VoiceWaveCompose(modifier = Modifier.size(200.dp, 50.dp))
//            }
//        }

        // ==========================================
        // BOTÓN DE MICRÓFONO CÁPSULA
        // ==========================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
                .wrapContentWidth()
                .height(58.dp)
                .background(
                    color = Color(0xFF0F1115),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = if (isSystemActive) Color(0xFF3DF2FF).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                    shape = CircleShape
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (isListening) onStopListening() else onMicClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Mic",
                    modifier = Modifier.size(24.dp),
                    tint = if (isSystemActive) Color(0xFF3DF2FF) else Color(0xFF8E939E)
                )

                MovingGlowLine(isSystemActive = isSystemActive)

                Text(
                    text = when {
                        isListening -> "LISTENING"
                        isThinking -> "THINKING"
                        isSpeaking -> "SPEAKING"
                        else -> "NEXUS LISTO"
                    },
                    color = if (isSystemActive) Color(0xFF3DF2FF) else Color(0xFF8E939E),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }

        // ==========================================
        // BARRA DE NAVEGACIÓN INFERIOR (Color Fijo)
        // ==========================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .width(350.dp)
                .height(76.dp)
                .background(
                    color = Color(0xFF141518),
                    shape = CircleShape
                )
        ) {
            // Forzamos a que ListeningBar siempre use el estado y color IDLE (fijo)
            ListeningBar(
                modifier = Modifier.matchParentSize().clip(CircleShape),
                state = barState,
                jarvisState = JarvisState.IDLE,      // ← Fijo en IDLE
                barColorMode = BarColorMode.IDLE     // ← Fijo en IDLE (Quita el cambio de colores)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationButton(icon = Icons.Outlined.Home, isActive = true)
                NavigationButton(icon = Icons.Outlined.Explore, isActive = false)
                NavigationButton(icon = Icons.Outlined.History, isActive = false)
                NavigationButton(icon = Icons.Outlined.Settings, isActive = false)
            }
        }
    }
}

// Composable de la línea optimizado con efecto túnel y velocidad reducida
@Composable
private fun MovingGlowLine(isSystemActive: Boolean) {
    val density = LocalDensity.current

    // Medidas en Píxeles para un control matemático exacto del "Túnel"
    val containerWidthPx = with(density) { 70.dp.toPx() }
    val lineWidthPx = with(density) { 28.dp.toPx() }

    val infiniteTransition = rememberInfiniteTransition(label = "line_movement")
    val travelFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Aumentado a 2600ms para que se mueva más despacio y elegante
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fraction"
    )

    // Control de Brillo: Encendido vs Apagado (Tenue) sin sombras raras
    val lineColor = if (isSystemActive) Color(0xFF3DF2FF) else Color(0xFF3DF2FF).copy(alpha = 0.25f)
    val trackColor = if (isSystemActive) Color(0xFF3DF2FF).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f)

    Box(
        modifier = Modifier
            .width(70.dp)
            .height(16.dp)
            .clipToBounds(), // ← CLAVE: Corta todo lo que se salga de los 70.dp, creando el efecto túnel
        contentAlignment = Alignment.CenterStart
    ) {
        // Línea guía de fondo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(color = trackColor, shape = CircleShape)
        )

        // Línea robusta animada
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(5.dp) // Grosor fuerte (Estilo dibujado)
                .graphicsLayer {
                    // ECUACIÓN TÚNEL: Empieza oculta a la izquierda (-lineWidthPx)
                    // y termina totalmente oculta a la derecha (containerWidthPx)
                    translationX = -lineWidthPx + (containerWidthPx + lineWidthPx) * travelFraction
                }
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.5f),
                            lineColor,
                            lineColor.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun NavigationButton(icon: ImageVector, isActive: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(58.dp)
            .then(
                if (isActive) {
                    Modifier
                        .drawBehind {
                            drawCircle(
                                color = Color(0xFF4A89FF).copy(alpha = 0.25f),
                                radius = size.maxDimension / 1.7f
                            )
                        }
                        .background(
                            color = Color(0xFF4A89FF),
                            shape = CircleShape
                        )
                } else {
                    Modifier
                }
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (isActive) Color(0xFF141518) else Color(0xFF8E939E)
        )
    }
}