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
import com.example.myapplication.core.JarvisState
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

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

@Composable
fun rememberListeningBarState(): ListeningBarState = remember { ListeningBarState() }

@Composable
fun ListeningBar(
    modifier: Modifier = Modifier,
    state: ListeningBarState,
    jarvisState: JarvisState
) {
    val density      = LocalDensity.current.density
    val glowMargin   = 16f * density
    val cornerRadius = 22f * density

    val activeColors = remember {
        intArrayOf(
            Color.parseColor("#2979FF"), Color.parseColor("#D500F9"),
            Color.parseColor("#FF1744"), Color.parseColor("#FF6D00"),
            Color.parseColor("#FFD600"), Color.parseColor("#00E676"),
            Color.parseColor("#2979FF")
        )
    }

    val darkBoxPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#131618")
            style = Paint.Style.FILL
        }
    }
    val glowPaintOuter = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val glowPaintInner = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }

    var sweepOffset by remember { mutableFloatStateOf(0f) }
    var wavePhase   by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(jarvisState) {
        if (jarvisState != JarvisState.IDLE) {
            val degreesPerMs = 360f / 4200f
            var lastMs = System.currentTimeMillis()
            while (isActive) {
                androidx.compose.runtime.withFrameMillis {
                    val now   = System.currentTimeMillis()
                    val delta = (now - lastMs).coerceIn(0, 64).toFloat()
                    lastMs    = now
                    sweepOffset = (sweepOffset + degreesPerMs * delta) % 360f
                    wavePhase  += if (state.energy > 0.1f) 0.14f else 0.03f
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawIntoCanvas { canvas ->
            val nc      = canvas.nativeCanvas
            val centerX = w / 2f
            val centerY = h / 2f
            val energy  = state.energy
            val rectBox = RectF(glowMargin, glowMargin, w - glowMargin, h - glowMargin)

            if (jarvisState != JarvisState.IDLE) {
                val shaderOuter = SweepGradient(centerX, centerY, activeColors, null).apply {
                    setLocalMatrix(Matrix().apply { postRotate(sweepOffset, centerX, centerY) })
                }
                val blurO = (12f * density + 8f * density * energy * sin(wavePhase)).coerceAtLeast(1f)
                glowPaintOuter.apply {
                    shader      = shaderOuter
                    maskFilter  = BlurMaskFilter(blurO, BlurMaskFilter.Blur.NORMAL)
                    strokeWidth = (12f + energy * 18f) * density
                    alpha       = (160 + (energy * 90) + (12 * sin(wavePhase))).toInt().coerceIn(0, 255)
                }
                nc.drawRoundRect(rectBox, cornerRadius, cornerRadius, glowPaintOuter)

                val shaderInner = SweepGradient(centerX, centerY, activeColors, null).apply {
                    setLocalMatrix(Matrix().apply { postRotate(-sweepOffset * 1.2f, centerX, centerY) })
                }
                val blurI = (4f * density + 2f * density * energy * cos(wavePhase.toDouble()).toFloat()).coerceAtLeast(1f)
                glowPaintInner.apply {
                    shader      = shaderInner
                    maskFilter  = BlurMaskFilter(blurI, BlurMaskFilter.Blur.NORMAL)
                    strokeWidth = (6f + energy * 10f) * density
                    alpha       = (200 + (energy * 55)).toInt().coerceIn(0, 255)
                }
                nc.drawRoundRect(rectBox, cornerRadius, cornerRadius, glowPaintInner)
            }

            nc.drawRoundRect(rectBox, cornerRadius, cornerRadius, darkBoxPaint)
        }
    }
}

@Composable
fun MicWaveListening(modifier: Modifier = Modifier, energy: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "micWave")
    val waveAnim by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label         = "wave"
    )

    Canvas(modifier = modifier) {
        val waveColor = androidx.compose.ui.graphics.Color(0xFF4DEEE9)
        for (i in 0..2) {
            val progress = (waveAnim + i / 3f) % 1f
            drawCircle(
                color  = waveColor,
                radius = progress * (size.width / 2f) * (0.8f + energy * 0.5f),
                center = size.center,
                alpha  = (1f - progress) * 0.5f,
                style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }
        drawCircle(
            color  = waveColor,
            radius = (size.width / 2f * 0.3f) + (energy * 5.dp.toPx()),
            center = size.center
        )
    }
}