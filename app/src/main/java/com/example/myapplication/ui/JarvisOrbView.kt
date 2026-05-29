package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // --- Audio input ---
    private var smoothRms = 0f
    private val smoothing = 0.12f

    // --- Animación continua ---
    private var time = 0f
    private var animator: ValueAnimator? = null
    private var innerGlowPhase = 0f

    // --- Paints ---
    private val paintOuterGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintInnerGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRibbon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSparkles = Paint(Paint.ANTI_ALIAS_FLAG)

    // Colores del arcoíris
    private val rainbowColors = intArrayOf(
        Color.parseColor("#4285F4"), // Azul Google
        Color.parseColor("#9C27B0"), // Morado
        Color.parseColor("#EA4335"), // Coral/Rojo
        Color.parseColor("#FF6D00"), // Naranja
        Color.parseColor("#FBBC05"), // Amarillo
        Color.parseColor("#34A853"), // Verde
        Color.parseColor("#4285F4")  // Cierre azul
    )

    private val white = Color.parseColor("#FFFFFF")
    private val lightCyan = Color.parseColor("#80DEEA")

    private val density = resources.displayMetrics.density

    init {
        // Fondo transparente
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // Forzar fondo transparente
        setWillNotDraw(false)
        startLoop()
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = it.animatedValue as Float
                innerGlowPhase += 0.03f
                invalidate()
            }
            start()
        }
    }

    fun updateRms(rmsDb: Float) {
        val target = if (rmsDb < 1.5f) 0f else (rmsDb / 12f).coerceIn(0f, 1f)
        smoothRms += (target - smoothRms) * smoothing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // No dibujamos fondo base - es transparente

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.85f
        val energy = smoothRms
        val pulse = 1f + energy * 0.15f

        // Solo dibujamos los efectos brillantes sobre fondo transparente
        drawOuterGlow(canvas, cx, cy, radius * pulse, energy)
        drawInnerGlow(canvas, cx, cy, radius * pulse, energy)
        drawRainbowRibbons(canvas, cx, cy, radius * pulse, energy)
        drawCoreGlow(canvas, cx, cy, radius * pulse, energy)
        drawSparkles(canvas, cx, cy, radius * pulse, energy)
        drawOuterBorder(canvas, cx, cy, radius * pulse)
    }

    private fun drawOuterGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val glowRadius = radius * (1.25f + energy * 0.35f)
        val shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((60 + energy * 100).toInt(), 66, 133, 244),
                Color.argb((30 + energy * 50).toInt(), 156, 39, 176),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        paintOuterGlow.shader = shader
        paintOuterGlow.maskFilter = BlurMaskFilter(radius * 0.35f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, radius * 1.15f, paintOuterGlow)
        paintOuterGlow.maskFilter = null
    }

    private fun drawInnerGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val glowRadius = radius * (0.95f + energy * 0.15f)

        val shader = SweepGradient(cx, cy, rainbowColors, null)
        val matrix = Matrix().apply {
            postRotate(time * 50f, cx, cy)
        }
        shader.setLocalMatrix(matrix)

        paintInnerGlow.apply {
            this.shader = shader
            style = Paint.Style.STROKE
            strokeWidth = (10f + energy * 28f) * density
            maskFilter = BlurMaskFilter((14f + energy * 18f) * density, BlurMaskFilter.Blur.NORMAL)
            alpha = (120 + energy * 135).toInt().coerceIn(0, 255)
        }
        canvas.drawCircle(cx, cy, glowRadius, paintInnerGlow)

        paintInnerGlow.apply {
            strokeWidth = (5f + energy * 14f) * density
            maskFilter = BlurMaskFilter((9f + energy * 12f) * density, BlurMaskFilter.Blur.NORMAL)
            alpha = (90 + energy * 110).toInt().coerceIn(0, 200)
        }
        canvas.drawCircle(cx, cy, glowRadius * 0.97f, paintInnerGlow)
    }

    private fun drawRainbowRibbons(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val boost = 1f + energy * 1.5f

        drawSingleRibbon(
            canvas, cx, cy, radius,
            startColor = rainbowColors[0], endColor = rainbowColors[1],
            phase = time, speed = 0.8f, warp = 2.5f * boost,
            thickness = 14f + energy * 22f, alpha = (200 + energy * 55).toInt()
        )

        drawSingleRibbon(
            canvas, cx, cy, radius,
            startColor = rainbowColors[2], endColor = rainbowColors[4],
            phase = time + PI.toFloat() * 0.5f, speed = -0.9f, warp = 3f * boost,
            thickness = 12f + energy * 20f, alpha = (190 + energy * 65).toInt()
        )

        drawSingleRibbon(
            canvas, cx, cy, radius,
            startColor = rainbowColors[5], endColor = rainbowColors[0],
            phase = time + PI.toFloat() * 1.2f, speed = 1.1f, warp = 2.8f * boost,
            thickness = 11f + energy * 18f, alpha = (180 + energy * 75).toInt()
        )

        if (energy > 0.1f) {
            drawSingleRibbon(
                canvas, cx, cy, radius,
                startColor = white, endColor = lightCyan,
                phase = time * 1.8f, speed = -1.5f, warp = 4f * boost,
                thickness = 6f + energy * 14f, alpha = (energy * 220).toInt()
            )
        }
    }

    private fun drawSingleRibbon(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        startColor: Int, endColor: Int,
        phase: Float, speed: Float, warp: Float,
        thickness: Float, alpha: Int
    ) {
        val path = Path()
        val steps = 100

        canvas.save()
        val clipPath = Path().apply { addCircle(cx, cy, radius * 0.98f, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = t * 2 * PI.toFloat()

            val waveX = sin(angle * warp + phase * speed)
            val waveY = cos(angle + phase * 0.5f)

            val x = cx + radius * cos(angle) * (0.75f + 0.2f * waveX.toFloat())
            val y = cy + radius * sin(angle) * (0.6f + 0.15f * waveY.toFloat()) * 0.8f +
                    radius * waveX.toFloat() * 0.3f

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        paintRibbon.apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.argb(alpha, Color.red(startColor), Color.green(startColor), Color.blue(startColor)),
                    Color.argb(alpha / 2, Color.red(endColor), Color.green(endColor), Color.blue(endColor)),
                    Color.argb(alpha, Color.red(startColor), Color.green(startColor), Color.blue(startColor))
                ),
                floatArrayOf(0f, 0.5f, 1f)
            )
            style = Paint.Style.STROKE
            strokeWidth = thickness * density
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(thickness * 0.8f * density, BlurMaskFilter.Blur.NORMAL)
        }

        canvas.drawPath(path, paintRibbon)
        canvas.restore()
        paintRibbon.maskFilter = null
    }

    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val coreRadius = radius * (0.1f + energy * 0.12f)
        val glowRadius = radius * (0.35f + energy * 0.3f)

        paintCore.apply {
            shader = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(
                    Color.argb((80 + energy * 120).toInt(), 255, 255, 255),
                    Color.argb((30 + energy * 60).toInt(), 100, 150, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            maskFilter = BlurMaskFilter(glowRadius * 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, glowRadius, paintCore)

        paintCore.apply {
            shader = RadialGradient(
                cx - coreRadius * 0.2f, cy - coreRadius * 0.2f, coreRadius,
                intArrayOf(white, Color.parseColor("#C0E8FF"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            maskFilter = BlurMaskFilter(coreRadius * 0.3f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, coreRadius, paintCore)
        paintCore.maskFilter = null
    }

    private fun drawSparkles(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        if (energy < 0.05f) return

        val sparkleCount = (5 + energy * 18).toInt()
        val baseAngle = time * 2f

        paintSparkles.apply {
            color = white
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
        }

        for (i in 0 until sparkleCount) {
            val angle = baseAngle + (i.toFloat() / sparkleCount) * 2 * PI.toFloat()
            val orbitRadius = radius * (1.02f + sin(angle.toDouble() + innerGlowPhase).toFloat() * 0.05f)
            val x = cx + orbitRadius * cos(angle)
            val y = cy + orbitRadius * sin(angle) * 0.7f

            val size = (2.5f + energy * 5f) * density
            val alpha = (100 + energy * 130 + 40 * sin(angle.toDouble() * 3).toFloat()).toInt().coerceIn(0, 255)
            paintSparkles.alpha = alpha
            canvas.drawCircle(x, y, size, paintSparkles)
        }
        paintSparkles.maskFilter = null
    }

    private fun drawOuterBorder(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paintBorder.apply {
            shader = null
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.argb(100, 255, 255, 255)
            maskFilter = BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, radius, paintBorder)

        paintBorder.apply {
            color = Color.argb(140, 255, 255, 255)
            strokeWidth = 2f * density
            maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
        }
        val oval = RectF(
            cx - radius * 0.7f, cy - radius * 0.85f,
            cx + radius * 0.7f, cy - radius * 0.4f
        )
        canvas.drawArc(oval, 200f, 140f, false, paintBorder)
        paintBorder.maskFilter = null
    }

    fun reset() {
        smoothRms = 0f
        time = 0f
        innerGlowPhase = 0f
        invalidate()
    }

    fun setEnergyLevel(level: Float) {
        smoothRms = (level / 12f).coerceIn(0f, 1f)
        invalidate()
    }

    fun getCurrentRms(): Float = smoothRms

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}