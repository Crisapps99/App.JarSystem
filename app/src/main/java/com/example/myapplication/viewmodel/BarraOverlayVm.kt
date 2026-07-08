package com.example.myapplication.ui

import android.graphics.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.voice.JarvisState
import androidx.compose.animation.core.*
import kotlin.random.Random

class ListeningBarState {
    var energy by mutableFloatStateOf(0f)
        private set

    fun animateWithEnergy(e: Float) {
        energy = (energy * 0.5f) + (e.coerceIn(0f, 1f) * 0.5f)
    }

    fun updateProgress(progress: Float) {
        animateWithEnergy(progress)
    }
}

enum class BarColorMode {
    IDLE,       // Neutro/oscuro
    LISTENING,  // Azul → Morado
    SPEAKING,   // Todos los colores (arcoíris)
    THINKING    // Cyan
}

@Composable
fun rememberListeningBarState(): ListeningBarState = remember { ListeningBarState() }

@Composable
fun ListeningBar(
    modifier: Modifier = Modifier,
    state: ListeningBarState,
    jarvisState: JarvisState,
    barColorMode: BarColorMode = BarColorMode.IDLE
) {
    val density = LocalDensity.current.density
    val cornerRadius = 50f * density

    // PALETA MULTICOLOR MEJORADA (Glow vivo como la imagen)
    val assistantColors = intArrayOf(
        android.graphics.Color.parseColor("#FF4285F4"), // Azul Google
        android.graphics.Color.parseColor("#FFEA4335"), // Rojo Google
        android.graphics.Color.parseColor("#FFFBBC05"), // Amarillo Google
        android.graphics.Color.parseColor("#FF34A853"), // Verde Google
        android.graphics.Color.parseColor("#FF4285F4")  // Cierre en Azul
    )

    // Animación de rotación continua
    val infiniteTransition = rememberInfiniteTransition(label = "multiColorWave")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pintura para la barra oscura del frente
    val darkBoxPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FF131618") // Fondo oscuro de la barra
            style = Paint.Style.FILL
        }
    }

    // Pintura para las ondas de color difuminadas (ATRÁS)
    val glowPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            // Blur para efecto aura viva
            maskFilter = BlurMaskFilter(15f * density, BlurMaskFilter.Blur.NORMAL)
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            // Espacio para el aura
            val margin = 18f * density
            val rectBox = RectF(margin, margin, w - margin, h - margin)

            // 1. DIBUJAMOS EL GLOW MULTICOLOR (ATRÁS)
            if (jarvisState != JarvisState.IDLE) {
                val shader = SweepGradient(w / 2f, h / 2f, assistantColors, null)
                val matrix = Matrix()
                matrix.postRotate(rotationAngle, w / 2f, h / 2f)
                shader.setLocalMatrix(matrix)

                glowPaint.shader = shader
                // El grosor reacciona a la energía
                glowPaint.strokeWidth = (16f + (state.energy * 8f)) * density

                val path = Path().apply {
                    addRoundRect(rectBox, cornerRadius, cornerRadius, Path.Direction.CW)
                }
                nc.drawPath(path, glowPaint)
            }

            // 2. DIBUJAMOS LA BARRA OSCURA (ENFRENTE)
            nc.drawRoundRect(rectBox, cornerRadius, cornerRadius, darkBoxPaint)
        }
    }
}

@Composable
fun MicWaveListening(modifier: Modifier = Modifier, energy: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "micWave")

    // Animación suave de las barras
    val waveAnim by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label         = "wave"
    )

    Canvas(modifier = modifier) {
        val centerY = size.height / 2f
        val centerX = size.width / 2f

        // Color Cyan vivo de la imagen
        val cyanColor = androidx.compose.ui.graphics.Color(0xFF00FFFF)

        // Parámetros de las barras
        val barCount = 5
        val barWidth = 4.dp.toPx()
        val barGap = 6.dp.toPx()
        val cornerRadius = 2.dp.toPx()

        // Altura base y variación máxima
        val baseBarHeight = 15.dp.toPx()
        val maxVariation = 30.dp.toPx()

        // Calculamos el ancho total para centrar
        val totalWidth = (barCount * barWidth) + ((barCount - 1) * barGap)
        val startX = centerX - (totalWidth / 2f)

        for (i in 0 until barCount) {
            // Calculamos una variación "aleatoria" desfasada para cada barra
            val phase = (waveAnim + (i.toFloat() / barCount)) % 1f
            // Usamos un seno para suavizar y el valor de energía para la amplitud
            val variation = kotlin.math.sin(phase * Math.PI * 2).toFloat() * (maxVariation * energy)

            val currentBarHeight = baseBarHeight + variation

            // Dibujamos la barra centrada verticalmente
            drawRoundRect(
                color = cyanColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = startX + (i * (barWidth + barGap)),
                    y = centerY - (currentBarHeight / 2f)
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, currentBarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
            )
        }
    }
}