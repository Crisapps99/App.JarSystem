package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * VoiceWaveView — Ícono de ondas de radio que pulsa al ritmo del TTS.
 *
 * Muestra 3 arcos concéntricos que se expanden y contraen según la energía de audio.
 * Reemplaza al ícono de micrófono cuando el asistente está hablando.
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    // Colores del tema Nexus
    private val colorPrimary = Color.parseColor("#4DEEE9")   // Aqua
    private val colorSecondary = Color.parseColor("#4285F4") // Azul

    private val paintWave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Energía actual (0..1)
    @Volatile private var energy = 0f
    private var smoothEnergy = 0f
    private val smoothing = 0.18f

    // Fase de animación continua
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        startLoop()
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // Suavizado de energía
                smoothEnergy += (energy - smoothEnergy) * smoothing
                invalidate()
            }
            start()
        }
    }

    /**
     * Actualizar la energía de la voz (0..1).
     * Llamar desde el TTS onRmsChanged o desde la animación simulada.
     */
    fun setEnergy(e: Float) {
        energy = e.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (width / 2f) * 0.92f
        val e = smoothEnergy

        // ── Punto central (micrófono minimalista) ──────────────────────────
        val centerRadius = maxRadius * 0.18f
        paintCenter.apply {
            shader = RadialGradient(
                cx, cy, centerRadius,
                intArrayOf(colorPrimary, colorSecondary),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            maskFilter = BlurMaskFilter(centerRadius * 0.3f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, centerRadius, paintCenter)
        paintCenter.maskFilter = null

        // ── 3 arcos de onda concéntricos ───────────────────────────────────
        val arcData = listOf(
            Triple(0.40f, 1.8f * density, 0f),    // Arco 1 (más cercano)
            Triple(0.63f, 1.5f * density, 0.4f),  // Arco 2
            Triple(0.86f, 1.2f * density, 0.8f)   // Arco 3 (más lejano)
        )

        arcData.forEachIndexed { index, (radiusFraction, baseStroke, phaseOffset) ->
            val radius = maxRadius * radiusFraction

            // Pulsación: cuando hay energía los arcos se engrosan y brillan
            val pulse = 1f + e * (0.8f - index * 0.15f) * sin(phase + phaseOffset).toFloat()
            val strokeWidth = (baseStroke + e * 3.5f * density) * pulse.coerceAtLeast(0.5f)

            // Alpha: base + boost de energía + onda sinusoidal
            val baseAlpha = 100 - index * 20
            val energyAlpha = (e * 155).toInt()
            val waveAlpha = (40 * sin(phase + phaseOffset + index * 0.6f)).toInt()
            val alpha = (baseAlpha + energyAlpha + waveAlpha).coerceIn(0, 255)

            // Gradiente del color por distancia
            val fraction = index.toFloat() / (arcData.size - 1)
            val r = lerp(Color.red(colorPrimary), Color.red(colorSecondary), fraction)
            val g = lerp(Color.green(colorPrimary), Color.green(colorSecondary), fraction)
            val b = lerp(Color.blue(colorPrimary), Color.blue(colorSecondary), fraction)

            paintWave.apply {
                color = Color.argb(alpha, r, g, b)
                this.strokeWidth = strokeWidth
                maskFilter = if (e > 0.1f)
                    BlurMaskFilter(strokeWidth * 0.8f, BlurMaskFilter.Blur.NORMAL)
                else null
            }

            // Dibujamos arcos izquierdo y derecho (como señal de radio)
            val sweepAngle = 55f + e * 25f
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            // Arco izquierdo
            canvas.drawArc(oval, 180f - sweepAngle / 2f, sweepAngle, false, paintWave)
            // Arco derecho
            canvas.drawArc(oval, -sweepAngle / 2f, sweepAngle, false, paintWave)
        }

        paintWave.maskFilter = null
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}