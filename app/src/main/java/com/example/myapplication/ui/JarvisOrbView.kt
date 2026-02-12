//package com.example.myapplication.ui
//
//import android.os.Bundle
//import androidx.activity.enableEdgeToEdge
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import com.example.myapplication.R
//import android.content.Context
//import android.graphics.*
//import android.util.AttributeSet
//import android.view.View
//import android.graphics.Path
//import kotlin.math.min
//import kotlin.math.sin
//import kotlin.math.cos
//import kotlin.math.max
//
//class JarvisOrbView @JvmOverloads constructor(
//    context: Context,
//    attrs: AttributeSet? = null
//) : View(context, attrs) {
//
//    private var rms = 0f
//    private var smoothRms = 0f
//    private var rotation = 0f
//    private var breathe = 0f
//
//    private val smoothing = 0.15f
//
//    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
//    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
//    private var externalEnergy = 0f
//    private var targetEnergy = 0f
//
//    fun updateRms(rmsDb: Float) {
//
//        //si el sonido e smuy bajo como lcik ignoramos
//        if (rmsDb<2.0f){
//            rms = 0f
//            smoothRms +=(0f - smoothRms) * smoothing
//            return
//        }
//        //boots y suavisado
//        val normalizer = (rmsDb / 12f).coerceIn(0f, 1f)
//        rms = normalizer *1.5f
//        smoothRms += (rms - smoothRms) * smoothing
//    }
//    // Añade estas propiedades a tu clase
//    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//        maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL) // Desenfoque en los bordes
//    }
//    private var time = 0f
//
//    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)
//        val cx = width / 2f
//        val cy = height / 2f
//        val baseRadius = min(width, height) * 0.35f
//
//        time += 0.02f + (smoothRms * 0.1f) // La velocidad depende del sonido
//
//        // Habilitar mezcla de colores aditiva para brillo
//        blobPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
//
//        // Dibujamos 4 capas de "plasma" con diferentes colores y frecuencias
//        drawOrganicBlob(canvas, cx, cy, baseRadius * 1.1f, time, Color.parseColor("#4DEEE9"), 0.8f) // Cian
//        drawOrganicBlob(canvas, cx, cy, baseRadius * 1.0f, -time * 1.2f, Color.parseColor("#7400FF"), 0.7f) // Púrpura
//        drawOrganicBlob(canvas, cx, cy, baseRadius * 0.9f, time * 0.8f, Color.parseColor("#0078FF"), 0.9f) // Azul
//
//        // Núcleo blanco difuso para dar profundidad
//        blobPaint.xfermode = null
//        blobPaint.shader = RadialGradient(cx, cy, baseRadius * 0.5f,
//            intArrayOf(Color.argb(200, 255, 255, 255), Color.TRANSPARENT),
//            null, Shader.TileMode.CLAMP)
//        canvas.drawCircle(cx, cy, baseRadius * 0.4f, blobPaint)
//
//        postInvalidateOnAnimation()
//    }
//
//    private fun drawOrganicBlob(
//        canvas: Canvas, cx: Float, cy: Float,
//        radius: Float, t: Float, color: Int, speedMod: Float
//    ) {
//        val path = Path()
//        val points = 24 // Más puntos para mayor suavidad
//        val energy = smoothRms * radius * 0.4f
//
//        for (i in 0 until points) {
//            val angle = (i.toFloat() / points) * (Math.PI * 2)
//
//            // Simulación de ruido combinamos varias ondas de seno
//            val offset = sin(angle * 3 + t) * (15f + energy) +
//                    cos(angle * 5 - t * 0.5f) * 10f
//
//            val r = radius + offset
//            val x = cx + cos(angle).toFloat() * r.toFloat()
//            val y = cy + sin(angle).toFloat() * r.toFloat()
//
//            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
//        }
//        path.close()
//
//        blobPaint.shader = null
//        blobPaint.color = color
//        blobPaint.alpha = (140 + (smoothRms * 100)).toInt().coerceAtMost(255)
//
//        // Aplicamos un gradiente lineal que rota para que el color no sea plano
//        blobPaint.shader = LinearGradient(cx - radius, cy, cx + radius, cy,
//            color, Color.TRANSPARENT, Shader.TileMode.MIRROR)
//
//        canvas.drawPath(path, blobPaint)
//    }
//    fun reset(){
//        rms=0f
//        smoothRms = 0f
//        externalEnergy = 0f
//        targetEnergy = 0f
//    }
//}
package com.example.myapplication.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var rms = 0f
    private var smoothRms = 0f
    private var time = 0f
    private var breathe = 0f

    private val smoothing = 0.15f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var externalEnergy = 0f
    private var targetEnergy = 0f

    // Colores adaptativos según el tema
    private var orbColor1: Int = 0
    private var orbColor2: Int = 0
    private var orbColor3: Int = 0
    private var orbColorGlow: Int = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Para blur effects
        updateThemeColors()
    }

    private fun updateThemeColors() {
        orbColor1 = ContextCompat.getColor(context, R.color.orb_color_1)
        orbColor2 = ContextCompat.getColor(context, R.color.orb_color_2)
        orbColor3 = ContextCompat.getColor(context, R.color.orb_color_3)

        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        orbColorGlow = if (isDarkMode) {
            Color.parseColor("#4DEEE9")
        } else {
            Color.parseColor("#4285F4")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeColors()
        invalidate()
    }

    fun updateRms(rmsDb: Float) {
        if (rmsDb < 2.0f) {
            rms = 0f
            smoothRms += (0f - smoothRms) * smoothing
            return
        }
        val normalizer = (rmsDb / 12f).coerceIn(0f, 1f)
        rms = normalizer * 1.5f
        smoothRms += (rms - smoothRms) * smoothing
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) * 0.28f

        // Animación del tiempo
        time += 0.015f + (smoothRms * 0.08f)
        breathe += 0.012f

        val breathing = sin(breathe) * baseRadius * 0.05f
        val energy = smoothRms * 0.6f

        // CAPA 1: Aura exterior difuminada (estilo Siri)
        drawOuterGlow(canvas, cx, cy, baseRadius + breathing, energy)

        // CAPA 2: Anillo de resplandor medio
        drawMidGlow(canvas, cx, cy, baseRadius + breathing, energy)

        // CAPA 3: Ondas orgánicas estilo Siri (3 capas superpuestas)
        blobPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

        drawSiriWave(canvas, cx, cy, baseRadius * 1.15f + breathing, time, orbColor1, 0.7f, energy)
        drawSiriWave(canvas, cx, cy, baseRadius * 1.0f + breathing, -time * 1.3f, orbColor2, 0.8f, energy)
        drawSiriWave(canvas, cx, cy, baseRadius * 0.85f + breathing, time * 0.9f, orbColor3, 0.9f, energy)

        blobPaint.xfermode = null

        // CAPA 4: Núcleo central brillante
        drawCoreGlow(canvas, cx, cy, baseRadius * 0.6f + breathing, energy)

        // CAPA 5: Highlight (punto de luz superior)
        drawHighlight(canvas, cx, cy, baseRadius + breathing)

        postInvalidateOnAnimation()
    }

    private fun drawOuterGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val glowRadius = radius * 2.5f
        val alpha = (40 + energy * 80).toInt().coerceAtMost(120)

        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(alpha, Color.red(orbColorGlow), Color.green(orbColorGlow), Color.blue(orbColorGlow)),
                Color.argb(alpha / 2, Color.red(orbColorGlow), Color.green(orbColorGlow), Color.blue(orbColorGlow)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, glowRadius * 0.8f, glowPaint)
        glowPaint.maskFilter = null
    }

    private fun drawMidGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val alpha = (80 + energy * 100).toInt().coerceAtMost(180)

        basePaint.shader = RadialGradient(
            cx, cy, radius * 1.6f,
            intArrayOf(
                Color.argb(alpha, Color.red(orbColor1), Color.green(orbColor1), Color.blue(orbColor1)),
                Color.argb(alpha / 3, Color.red(orbColor2), Color.green(orbColor2), Color.blue(orbColor2)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.4f, basePaint)
    }

    private fun drawSiriWave(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, t: Float, color: Int, intensity: Float, energy: Float
    ) {
        val path = Path()
        val points = 400 // Más puntos para mayor suavidad tipo Siri
        val energyBoost = energy * radius * 0.3f

        for (i in 0 until points) {
            val angle = (i.toFloat() / points) * (Math.PI * 2)

            // Ondulación orgánica estilo Siri (múltiples frecuencias)
            val wave1 = sin((angle * 4 + t * 2).toDouble()).toFloat() * (12f + energyBoost)
            val wave2 = cos((angle * 6 - t).toDouble()).toFloat() * 8f
            val wave3 = sin((angle * 2 + t * 0.5).toDouble()).toFloat() * (15f + energyBoost * 0.5f)

            val offset = (wave1 + wave2 + wave3) * intensity

            val r = radius + offset
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Gradiente radial para cada onda
        blobPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb((180 + energy * 75).toInt().coerceAtMost(255),
                    Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb((100 + energy * 50).toInt().coerceAtMost(200),
                    Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(path, blobPaint)
    }

    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val alpha = (200 + energy * 55).toInt().coerceAtMost(255)

        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val coreColors = if (isDarkMode) {
            intArrayOf(
                Color.argb(alpha, 255, 255, 255),
                Color.argb(alpha - 50, 200, 240, 255),
                Color.TRANSPARENT
            )
        } else {
            intArrayOf(
                Color.argb(alpha - 50, 255, 255, 255),
                Color.argb(alpha - 100, 220, 235, 255),
                Color.TRANSPARENT
            )
        }

        basePaint.shader = RadialGradient(
            cx - radius * 0.1f,
            cy - radius * 0.1f,
            radius,
            coreColors,
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, basePaint)
    }

    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val highlightAlpha = if (isDarkMode) 180 else 120

        val highlightX = cx - radius * 0.3f
        val highlightY = cy - radius * 0.4f

        basePaint.shader = RadialGradient(
            highlightX, highlightY, radius * 0.5f,
            intArrayOf(
                Color.argb(highlightAlpha, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(highlightX, highlightY, radius * 0.4f, basePaint)
    }

    fun reset() {
        rms = 0f
        smoothRms = 0f
        externalEnergy = 0f
        targetEnergy = 0f
        time = 0f
        breathe = 0f
    }

    fun setEnergy(level: Float) {
        targetEnergy = level.coerceIn(0f, 1f)
    }
}