package com.example.myapplication


import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.activity.LoginActivity
import com.example.myapplication.ui.JarvisOrb
import com.ncorti.slidetoact.SlideToActView
import kotlinx.coroutines.delay
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

// Easing personalizado que imita OvershootInterpolator
class OvershootEasing(private val tension: Float = 1.5f) : Easing {
    override fun transform(x: Float): Float {
        val x1 = x - 1f
        return x1 * x1 * ((tension + 1) * x1 + tension) + 1f
    }
}

class MainActivity : ComponentActivity() {
    private var sliderResetKey = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        sliderResetKey.value++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val resetKey by sliderResetKey
            var energy by remember { mutableFloatStateOf(0f) }

            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseValue by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            LaunchedEffect(pulseValue) {
                energy = pulseValue
            }

            MainComposeScreen(energy = energy, sliderKey = resetKey)
        }

    }

}

@Composable
fun MainComposeScreen(energy: Float, sliderKey: Int = 0) {
    val context = LocalContext.current
    var isTransitioning by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val animAlpha = remember { Animatable(0f) }
    // Colores exactos de tu ejemplo ListeningBarView
    val neonColors = listOf(
        Color(0xFF2979FF), // azul eléctrico
        Color(0xFFD500F9), // violeta neón
        Color(0xFFFF1744), // rojo vivo
        Color(0xFFFF6D00), // naranja
        Color(0xFFFFD600), // amarillo
        Color(0xFF00E676), // verde neón
        Color(0xFF2979FF)  // cierra el ciclo
    )

    val infiniteTransition = rememberInfiniteTransition(label = "neon")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    LaunchedEffect(Unit) {
        animAlpha.animateTo(1f, tween(1500))
    }

    Box(modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {

        // --- PARTE CENTRAL (ORBE Y TEXTOS) ---
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            JarvisOrb(
                modifier = Modifier
                    .size(490.dp)
                    .scale(animScale.value)
                    .alpha(if (isTransitioning) 0f else animAlpha.value),
                energy = energy
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "NEXUS",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 4.sp,
                modifier = Modifier
                    .offset(y = (-100).dp)
                    .alpha(if (isTransitioning) 0f else animAlpha.value)
            )
            Text(
                text = "Tu asistente de voz inteligente",
                fontSize = 20.sp,
                color = Color.LightGray,
                modifier = Modifier
                    .offset(y = (-100).dp)
                    .alpha(if (isTransitioning) 0f else animAlpha.value)
            )
        }

        // --- PARTE INFERIOR (SLIDER CON GLOW ROTATORIO) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-50).dp)
                .padding(horizontal = 55.dp, vertical = 60.dp)
                .fillMaxWidth()
                .height(75.dp)
                .alpha(animAlpha.value)
                .padding(0.dp)  // separación interna para que el slider no tape el glow
        ) {
            key(sliderKey){
                var isTransitioning by remember { mutableStateOf(false) }
                AndroidView(
                    factory = { ctx ->
                        SlideToActView(ctx).apply {
                            text = "COMENZAR"
                            textColor = android.graphics.Color.WHITE
                            // textAppearance = R.style.SliderTextAppearance  // comenta si no existe
                            outerColor = android.graphics.Color.parseColor("#33F0FBFF")
                            innerColor = android.graphics.Color.parseColor("#B3FFFFFF")
                            sliderIcon = R.drawable.ic_mic_vector
                            bumpVibration = 50L

                            onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
                                    override fun onSlideComplete(view: SlideToActView) {
                                        if (isTransitioning) return
                                        isTransitioning = true

                                        postDelayed({
                                            context.startActivity(
                                                Intent(
                                                    context,
                                                    LoginActivity::class.java
                                                )
                                            )
                                        }, 200)
                                    }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

    }

}

@Preview(showBackground = true, backgroundColor = 0xFF070B2C)
@Composable
fun MainPreview() {
    MainComposeScreen(energy = 4f)
}
//fun Modifier.glowingRoundedRect(
//    rotation: Float,
//    energy: Float = 1f,
//    cornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
//    strokeWidthOuter: androidx.compose.ui.unit.Dp = 16.dp,
//    strokeWidthInner: androidx.compose.ui.unit.Dp = 8.dp,
//    blurOuter: Float = 20f,
//    blurInner: Float = 10f
//): Modifier = this.drawWithContent {
//    drawContent()
//    drawIntoCanvas { canvas ->
//        val center = size.center
//        val cornerPx = cornerRadius.toPx()
//
//        val gradientColors = intArrayOf(
//            android.graphics.Color.parseColor("#FF00DAF3"),
//            android.graphics.Color.parseColor("#FF0077FF"),
//            android.graphics.Color.parseColor("#FF00DAF3"),
//            android.graphics.Color.parseColor("#FF1A214D"),
//            android.graphics.Color.parseColor("#FF00DAF3"),
//
//
//        )
//        // Inner glow — rota en sentido contrario, más rápido (igual que ListeningBarView)
//        val shaderInner = android.graphics.SweepGradient(
//            center.x, center.y, gradientColors, null
//        )
//        val matrixInner = android.graphics.Matrix().apply {
//            postRotate(rotation * 2f, center.x, center.y)
//        }
//        shaderInner.setLocalMatrix(matrixInner)
//
//        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
//
//        val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//            shader = shaderInner
//            style = Paint.Style.STROKE
//            strokeWidth = strokeWidthInner.toPx()
//            maskFilter = BlurMaskFilter(blurInner, BlurMaskFilter.Blur.NORMAL)
//            alpha = (200 + (energy * 40)).toInt().coerceIn(0, 255)
//        }
//        canvas.nativeCanvas.drawRoundRect(rect, cornerPx, cornerPx, paintInner)
//    }
//}