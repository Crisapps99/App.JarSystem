package com.example.myapplication.ui

import android.graphics.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.voice.JarvisState
import kotlin.math.sin

class ListeningBarState {
    var energy by mutableFloatStateOf(0f)
        private set

    fun animateWithEnergy(e: Float) {
        energy = (energy * 0.6f) + (e.coerceIn(0f, 1f) * 0.4f)
    }

    fun updateProgress(progress: Float) {
        animateWithEnergy(progress)
    }
}

enum class BarColorMode {
    IDLE, LISTENING, SPEAKING, THINKING
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

    val siriColors = intArrayOf(
        android.graphics.Color.parseColor("#FF2F80ED"),
        android.graphics.Color.parseColor("#FFB800FF"),
        android.graphics.Color.parseColor("#FF00F5D4"),
        android.graphics.Color.parseColor("#FF7000FF"),
        android.graphics.Color.parseColor("#FF2F80ED")
    )

    val infiniteTransition = rememberInfiniteTransition(label = "siriWave")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val backgroundPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FF1C1C1E")
            style = Paint.Style.FILL
        }
    }

    val borderStrokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
    }

    val neonGlowPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            // 1. AUMENTAMOS EL BLUR para que sea más suave y humeante
            maskFilter = BlurMaskFilter(26f * density, BlurMaskFilter.Blur.NORMAL)
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            // 2. EL SECRETO ESTÁ AQUÍ: El margen (32f) AHORA ES MAYOR que el blur (26f).
            // Esto le da a la luz el espacio necesario para desaparecer sin chocar con la pared.
            val margin = 10f * density
            val rectBox = RectF(margin, margin, w - margin, h - margin)

            val dynamicRadius = rectBox.height() / 2f

            if (jarvisState != JarvisState.IDLE) {
                val shader = SweepGradient(w / 2f, h / 2f, siriColors, null)
                val matrix = Matrix()
                matrix.postRotate(rotationAngle, w / 2f, h / 2f)
                shader.setLocalMatrix(matrix)

                neonGlowPaint.shader = shader
                neonGlowPaint.strokeWidth = (8f + (state.energy * 10f)) * density

                val glowPath = Path().apply {
                    addRoundRect(rectBox, dynamicRadius, dynamicRadius, Path.Direction.CW)
                }
                nc.drawPath(glowPath, neonGlowPaint)

                nc.drawRoundRect(rectBox, dynamicRadius, dynamicRadius, backgroundPaint)

                borderStrokePaint.shader = shader
                nc.drawRoundRect(rectBox, dynamicRadius, dynamicRadius, borderStrokePaint)
            } else {
                nc.drawRoundRect(rectBox, dynamicRadius, dynamicRadius, backgroundPaint)
            }
        }
    }
}

@Composable
fun MicWaveListening(modifier: Modifier = Modifier, energy: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "micWave")

    val waveAnim by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label         = "wave"
    )

    val barGradientColors = intArrayOf(
        android.graphics.Color.parseColor("#FF00F5D4"),
        android.graphics.Color.parseColor("#FFB800FF"),
        android.graphics.Color.parseColor("#FF2F80ED")
    )

    Canvas(modifier = modifier) {
        val centerY = size.height / 2f
        val centerX = size.width / 2f

        val barCount = 4
        val barWidth = 5.dp.toPx()
        val barGap = 5.dp.toPx()
        val cornerRadius = 2.5.dp.toPx()

        val baseBarHeight = 14.dp.toPx()
        val maxVariation = 32.dp.toPx()

        val totalWidth = (barCount * barWidth) + ((barCount - 1) * barGap)
        val startX = centerX - (totalWidth / 2f)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            for (i in 0 until barCount) {
                val phase = (waveAnim + (i.toFloat() / barCount)) % 1f
                val variation = sin(phase * Math.PI * 2).toFloat() * (maxVariation * energy)
                val currentBarHeight = (baseBarHeight + variation).coerceAtLeast(6.dp.toPx())

                val left = startX + (i * (barWidth + barGap))
                val top = centerY - (currentBarHeight / 2f)
                val right = left + barWidth
                val bottom = centerY + (currentBarHeight / 2f)

                val barShader = LinearGradient(
                    left, top, left, bottom,
                    barGradientColors, null, Shader.TileMode.CLAMP
                )

                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = barShader
                    style = Paint.Style.FILL
                }

                nc.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, barPaint)
            }
        }
    }
}