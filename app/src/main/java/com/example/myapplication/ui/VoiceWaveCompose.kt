// VoiceWaveCompose.kt
package com.example.myapplication.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun VoiceWaveCompose(   // ← SIN private
    modifier: Modifier = Modifier,
    barCount: Int = 15
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val barWidth = size.width / barCount
        val maxHeight = size.height * 0.8f

        for (i in 0 until barCount) {
            val x = i * barWidth + barWidth / 2
            val randomOffset = (i.toFloat() / barCount) * 2f
            val heightFactor = (sin((phase * 2 * Math.PI).toFloat() + randomOffset * 3f) + 1f) / 2f
            val barHeight = maxHeight * (0.15f + 0.75f * heightFactor)

            drawRoundRect(
                color = Color(0xFF00DAF3).copy(
                    alpha = 0.5f + 0.4f * (1f - heightFactor)
                ),
                topLeft = Offset(x - barWidth * 0.3f, (size.height - barHeight) / 2),
                size = androidx.compose.ui.geometry.Size(
                    width = barWidth * 0.6f,
                    height = barHeight
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}