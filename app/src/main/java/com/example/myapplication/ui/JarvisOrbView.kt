package com.example.myapplication.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.View
//import androidx.core.content.ContextCompat
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

    private val smoothing = 0.12f
    private val breathingSpeed = 0.01f
    private val waveFrequency = 0.02f

    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintWave = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintHighlight = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colorPrimary: Int = 0
    private var colorSecondary: Int = 0
    private var colorTertiary: Int = 0
    private var colorAccent: Int = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        updateThemeColors()
    }

    private fun updateThemeColors() {
        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            colorPrimary = Color.parseColor("#4DEEE9")
            colorSecondary = Color.parseColor("#1DE0A0")
            colorTertiary = Color.parseColor("#7BD7F8")
            colorAccent = Color.parseColor("#FF6B6B")
        } else {
            colorPrimary = Color.parseColor("#0095D4")
            colorSecondary = Color.parseColor("#00C853")
            colorTertiary = Color.parseColor("#2196F3")
            colorAccent = Color.parseColor("#FF6B6B")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeColors()
        invalidate()
    }

    fun updateRms(rmsDb: Float) {
        if (rmsDb < 1.5f) {
            rms = 0f
            smoothRms += (0f - smoothRms) * smoothing
            return
        }

        val normalized = (rmsDb / 12f).coerceIn(0f, 1f)
        rms = normalized * 1.5f
        smoothRms += (rms - smoothRms) * smoothing
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) * 0.25f

        time += waveFrequency + (smoothRms * 0.005f)
        breathe += breathingSpeed + (smoothRms * 0.002f)

        val breathing = sin(breathe.toDouble()).toFloat() * baseRadius * 0.08f
        val energy = smoothRms * 0.5f

        // CAPA 1: Aura exterior
        drawOuterAura(canvas, cx, cy, baseRadius, breathing, energy)

        // CAPA 2: Ondas Siri (3 capas)
        drawSiriWaveLayers(canvas, cx, cy, baseRadius, breathing, energy)

        // CAPA 3: Núcleo brillante
        drawCoreGlow(canvas, cx, cy, baseRadius, breathing, energy)

        // CAPA 4: Highlight
        drawHighlightSpot(canvas, cx, cy, baseRadius)

        postInvalidateOnAnimation()
    }

    private fun drawOuterAura(
        canvas: Canvas, cx: Float, cy: Float,
        baseRadius: Float, breathing: Float, energy: Float
    ) {
        val auraRadius = baseRadius * 2.2f
        val alpha = (30 + energy * 100).toInt().coerceAtMost(150)

        paintGlow.shader = RadialGradient(
            cx, cy, auraRadius,
            intArrayOf(
                Color.argb(alpha, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)),
                Color.argb(alpha / 3, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        paintGlow.maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, auraRadius * 0.9f, paintGlow)
        paintGlow.maskFilter = null
    }

    private fun drawSiriWaveLayers(
        canvas: Canvas, cx: Float, cy: Float,
        baseRadius: Float, breathing: Float, energy: Float
    ) {
        paintWave.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

        drawSingleWaveLayer(canvas, cx, cy, baseRadius * 1.25f + breathing, time * 1.2f,
            colorPrimary, 0.65f, energy, 4)

        drawSingleWaveLayer(canvas, cx, cy, baseRadius * 0.95f + breathing, -time * 1.5f,
            colorSecondary, 0.75f, energy, 6)

        drawSingleWaveLayer(canvas, cx, cy, baseRadius * 0.7f + breathing, time * 0.8f,
            colorTertiary, 0.85f, energy, 8)

        paintWave.xfermode = null
    }

    private fun drawSingleWaveLayer(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, timeOffset: Float,
        color: Int, intensity: Float,
        energy: Float, peakCount: Int
    ) {
        val path = Path()
        val points = 200
        val energyBoost = energy * radius * 0.25f
        val pi = Math.PI.toFloat()

        for (i in 0 until points) {
            val angle = (i.toFloat() / points) * (2 * pi)

            val wave1 = sin((angle * peakCount + timeOffset).toDouble()).toFloat() *
                    (8f + energyBoost * 0.7f)
            val wave2 = cos((angle * (peakCount + 2) - timeOffset * 0.5).toDouble()).toFloat() * 4f
            val wave3 = sin((angle * 2 + timeOffset * 0.3).toDouble()).toFloat() *
                    (5f + energyBoost * 0.5f)

            val offset = (wave1 + wave2 + wave3) * intensity

            val r = radius + offset
            val x = cx + cos(angle.toDouble()).toFloat() * r
            val y = cy + sin(angle.toDouble()).toFloat() * r

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val alpha1 = (180 + energy * 70).toInt().coerceAtMost(255)
        val alpha2 = (100 + energy * 50).toInt().coerceAtMost(200)

        paintWave.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb(alpha1, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(alpha2, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(path, paintWave)
    }

    private fun drawCoreGlow(
        canvas: Canvas, cx: Float, cy: Float,
        baseRadius: Float, breathing: Float, energy: Float
    ) {
        val coreRadius = baseRadius * 0.55f + (breathing * 0.5f)
        val alpha = (210 + energy * 45).toInt().coerceAtMost(255)

        paintCore.shader = RadialGradient(
            cx - coreRadius * 0.15f,
            cy - coreRadius * 0.15f,
            coreRadius,
            intArrayOf(
                Color.argb(alpha, 255, 255, 255),
                Color.argb((alpha * 0.7).toInt(), 200, 240, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, paintCore)
    }

    private fun drawHighlightSpot(
        canvas: Canvas, cx: Float, cy: Float,
        baseRadius: Float
    ) {
        val highlightX = cx - baseRadius * 0.35f
        val highlightY = cy - baseRadius * 0.35f
        val highlightRadius = baseRadius * 0.3f

        paintHighlight.shader = RadialGradient(
            highlightX, highlightY, highlightRadius,
            intArrayOf(
                Color.argb(200, 255, 255, 255),
                Color.argb(100, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(highlightX, highlightY, highlightRadius * 0.8f, paintHighlight)
    }

    fun reset() {
        rms = 0f
        smoothRms = 0f
        time = 0f
        breathe = 0f
    }

    fun setEnergyLevel(level: Float) {
        rms = level.coerceIn(0f, 12f)
    }

    fun getCurrentRms(): Float = smoothRms
}