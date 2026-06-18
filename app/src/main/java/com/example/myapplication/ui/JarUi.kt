package com.example.myapplication.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
// IMPORTANTE: Asegúrate de importar tu función JarvisOrb y glowingRoundedRect
import com.example.myapplication.ui.JarvisOrb
import com.example.myapplication.ui.ProcessingStep
//import com.example.myapplication.glowingRoundedRect
import com.example.myapplication.viewmodel.JarPhase
import com.example.myapplication.viewmodel.JarVm
import com.ncorti.slidetoact.SlideToActView


@Composable
fun JarScreen(vm: JarVm, onStart: () -> Unit, onOmitir: () -> Unit) {
    val state by vm.state.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    AnimatedVisibility(
        visible = state.isScreenVisible,
        exit = fadeOut(tween(400))
    ) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0C))) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Orbe Principal
                JarvisOrb(
                    modifier = Modifier
                        .size(390.dp)
                        .scale(1.1f), // Un poco de escala para que luzca imponente
                    energy = state.orbRms // El VM ya pasa el RMS aquí
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Tarjeta de Transcripción
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2022)),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier
                                .size(8.dp)
                                .background(Color(android.graphics.Color.parseColor(state.statusColor))))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.statusText,
                                color = Color(android.graphics.Color.parseColor(state.statusColor)),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = state.transcription,
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                        if (state.instruction.isNotEmpty()) {
                            Text(
                                text = state.instruction,
                                color = Color(0xFF4DEEE9),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))


                // --- BOTÓN ELEGANTE SUSTITUTO DEL SLIDER ---
                if (state.phase == JarPhase.INTRO) {
                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(64.dp)
//                            // Aplicamos el efecto de brillo rotatorio que ya tienes definido
//                            .glowingRoundedRect(
//                                rotation = rotation,
//                                energy = 1.5f,
//                                cornerRadius = 32.dp,
//                                strokeWidthOuter = 10.dp,
//                                strokeWidthInner = 2.dp
//                            )
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF1E2022))
                            .clickable { onStart() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "COMENZAR",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp
                        )
                    }
                }
            }
            // Botón Omitir
            if (state.isOmitirVisible) {
                TextButton(
                    onClick = onOmitir,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .zIndex(1f)
                ) {
                    Text("Omitir introducción", color = Color(0xFFA0A0A0))
                }
            }
        }
        
    }
    
}
