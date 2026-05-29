package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

class ListeningBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // El margen reservado FUERA del cajón para que el aura fluya libremente sin cortarse
    private val glowMargin = 20f * density
    private val cornerRadius = 24f * density

    // Pintura del contenedor limpio (Cajón Blanco)
    private val whiteBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val gradientColors = intArrayOf(
        Color.parseColor("#4285F4"), // Azul
        Color.parseColor("#9C27B0"), // Morado
        Color.parseColor("#EA4335"), // Coral
        Color.parseColor("#FF6D00"), // Naranja
        Color.parseColor("#FBBC05"), // Amarillo
        Color.parseColor("#34A853"), // Verde
        Color.parseColor("#4285F4")  // Cierre
    )

    private val glowPaintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var sweepOffset = 0f
    private var wavePhase = 0f
    private var animator: ValueAnimator? = null
    private var energy = 0f

    init {
        setWillNotDraw(false)
        // Forzar renderizado por software para aplicar BlurMaskFilter en tiempo real
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // IMPORTANTE: Calculamos el padding dinámico sumando el espacio del glow + el padding de tus elementos
        val pStart = (glowMargin + 8f * density).toInt()
        val pTop = (glowMargin + 10f * density).toInt()
        val pEnd = (glowMargin + 14f * density).toInt()
        val pBottom = (glowMargin).toInt()
        setPadding(pStart, pTop, pEnd, pBottom)

        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepOffset = it.animatedValue as Float
                val waveSpeed = if (energy > 0.1f) 0.16f else 0.04f
                wavePhase += waveSpeed
                invalidate()
            }
            start()
        }
    }

    fun animateWithEnergy(e: Float) {
        energy = (energy * 0.5f) + (e.coerceIn(0f, 1f) * 0.5f)
        invalidate()
    }

    fun updateProgress(progress: Float) {
        animateWithEnergy(progress)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        // Configuración de matrices de movimiento cruzado para las olas líquidas
        val shaderOuter = SweepGradient(centerX, centerY, gradientColors, null)
        val matrixOuter = Matrix().apply { postRotate(sweepOffset, centerX, centerY) }
        shaderOuter.setLocalMatrix(matrixOuter)

        val shaderInner = SweepGradient(centerX, centerY, gradientColors, null)
        val matrixInner = Matrix().apply { postRotate(-sweepOffset * 1.2f, centerX, centerY) }
        shaderInner.setLocalMatrix(matrixInner)

        // Definimos las dimensiones exactas donde se asentará el cajón blanco
        val rectWhiteBox = RectF(glowMargin, glowMargin, w - glowMargin, h - glowMargin)

        // --- PASO 1: DIBUJAR GLOW EXTERIOR (Ondas expansivas) ---
        val baseBlurOuter = 14f * density
        val dynamicBlurOuter = baseBlurOuter + (10f * density * energy * sin(wavePhase))
        val safeBlurOuter = dynamicBlurOuter.coerceAtLeast(1f)

        glowPaintOuter.apply {
            shader = shaderOuter
            maskFilter = BlurMaskFilter(safeBlurOuter, BlurMaskFilter.Blur.NORMAL)
            // Grosor aumentado (La mitad interna será devorada por el cajón blanco)
            strokeWidth = (10f + energy * 18f) * density
            alpha = (95 + (energy * 120) + (15 * sin(wavePhase))).toInt().coerceIn(0, 255)
        }
        canvas.drawRoundRect(rectWhiteBox, cornerRadius, cornerRadius, glowPaintOuter)

        // --- PASO 2: DIBUJAR GLOW INTERIOR (Foco de brillo en el borde) ---
        val baseBlurInner = 5f * density
        val dynamicBlurInner = baseBlurInner + (3f * density * energy * cos(wavePhase.toDouble()).toFloat())
        val safeBlurInner = dynamicBlurInner.coerceAtLeast(1f)

        glowPaintInner.apply {
            shader = shaderInner
            maskFilter = BlurMaskFilter(safeBlurInner, BlurMaskFilter.Blur.NORMAL)
            strokeWidth = (5f + energy * 8f) * density
            alpha = (160 + (energy * 95)).toInt().coerceIn(0, 255)
        }
        canvas.drawRoundRect(rectWhiteBox, cornerRadius, cornerRadius, glowPaintInner)

        // --- PASO 3: EL TRUCO MAESTRO (Tapar el fondo hacia adentro) ---
        // Dibujamos el cajón sólido encima. Todo vestigio de color interno desaparece por completo
        canvas.drawRoundRect(rectWhiteBox, cornerRadius, cornerRadius, whiteBoxPaint)

        // --- PASO 4: Renderizar textos y componentes secundarios ---
        super.onDraw(canvas)
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}