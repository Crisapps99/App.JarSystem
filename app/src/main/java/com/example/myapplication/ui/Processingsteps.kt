package com.example.myapplication.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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