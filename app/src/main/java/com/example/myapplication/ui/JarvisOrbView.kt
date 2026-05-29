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
    private val smoothing = 0.15f

    // --- Animación continua ---
    private var time = 0f
    private var animator: ValueAnimator? = null

    // --- Paints ---
    private val paintBase    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGrid    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRibbon  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCore    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder  = Paint(Paint.ANTI_ALIAS_FLAG)

    // Colores Siri
    private val pink    = Color.parseColor("#E040C8")
    private val purple  = Color.parseColor("#7B4FE0")
    private val blue    = Color.parseColor("#3A6FF7")
    private val cyan    = Color.parseColor("#40D0F0")
    private val white   = Color.parseColor("#FFFFFF")

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startLoop()
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun updateRms(rmsDb: Float) {
        val target = if (rmsDb < 1.5f) 0f else (rmsDb / 12f).coerceIn(0f, 1f)
        smoothRms += (target - smoothRms) * smoothing
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.88f
        val energy = smoothRms

        drawSphereBase(canvas, cx, cy, radius)
        drawGrid(canvas, cx, cy, radius, energy)
        drawRibbons(canvas, cx, cy, radius, energy)
        drawCoreGlow(canvas, cx, cy, radius, energy)
        drawOuterBorder(canvas, cx, cy, radius)
    }

    // 1. Base esférica con gradiente oscuro azul/púrpura
    private fun drawSphereBase(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paintBase.shader = RadialGradient(
            cx - radius * 0.2f, cy - radius * 0.2f, radius * 1.1f,
            intArrayOf(
                Color.parseColor("#2A1060"),
                Color.parseColor("#0D0630"),
                Color.parseColor("#060418")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paintBase)
    }

    // 2. Rejilla esférica estilo Siri
    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        paintGrid.shader = null
        paintGrid.style = Paint.Style.STROKE
        paintGrid.strokeWidth = 0.7f
        paintGrid.color = Color.argb(55, 100, 160, 255)
        paintGrid.pathEffect = null

        val lines = 10
        // Líneas horizontales (latitud) proyectadas en esfera
        for (i in 1 until lines) {
            val lat = (i.toFloat() / lines) * PI.toFloat() // 0..PI
            val y = cy - radius * cos(lat)
            val r = radius * sin(lat)
            if (r > 2f) canvas.drawOval(cx - r, y - r * 0.3f, cx + r, y + r * 0.3f, paintGrid)
        }
        // Líneas verticales (longitud)
        val longs = 10
        for (i in 0 until longs) {
            val lon = (i.toFloat() / longs) * PI.toFloat()
            val path = Path()
            val steps = 60
            for (s in 0..steps) {
                val lat2 = (s.toFloat() / steps) * PI.toFloat()
                val x = cx + radius * sin(lat2) * cos(lon)
                val y = cy - radius * cos(lat2)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paintGrid)
        }
    }

    // 3. Cintas de luz fluidas (estilo Siri) — reactivas a energía
    private fun drawRibbons(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val boost = 1f + energy * 1.8f

        // Cinta rosa/magenta
        drawSingleRibbon(
            canvas, cx, cy, radius,
            colorA = pink, colorB = Color.parseColor("#FF80E0"),
            phase = time, speed = 1.0f, warp = 2.8f * boost,
            thickness = 18f + energy * 22f, alpha = (200 + energy * 55).toInt().coerceAtMost(255)
        )

        // Cinta azul/cyan
        drawSingleRibbon(
            canvas, cx, cy, radius,
            colorA = blue, colorB = cyan,
            phase = time + PI.toFloat() * 0.6f, speed = -0.7f, warp = 3.2f * boost,
            thickness = 16f + energy * 18f, alpha = (180 + energy * 60).toInt().coerceAtMost(255)
        )

        // Cinta púrpura
        drawSingleRibbon(
            canvas, cx, cy, radius,
            colorA = purple, colorB = Color.parseColor("#B06EFF"),
            phase = time + PI.toFloat() * 1.2f, speed = 1.3f, warp = 2.5f * boost,
            thickness = 14f + energy * 16f, alpha = (160 + energy * 70).toInt().coerceAtMost(240)
        )

        // Extra cuando hay energía: cinta blanca central
        if (energy > 0.1f) {
            drawSingleRibbon(
                canvas, cx, cy, radius,
                colorA = white, colorB = cyan,
                phase = time * 1.5f, speed = -1.8f, warp = 4f * boost,
                thickness = 8f + energy * 14f, alpha = (energy * 200).toInt().coerceAtMost(200)
            )
        }
    }

    private fun drawSingleRibbon(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        colorA: Int, colorB: Int,
        phase: Float, speed: Float, warp: Float,
        thickness: Float, alpha: Int
    ) {
        val path = Path()
        val steps = 120
        val clip = Path().apply { addCircle(cx, cy, radius * 0.98f, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clip)

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = t * 2 * PI.toFloat()

            // Curva 3D proyectada — combina sin/cos para dar profundidad
            val sinA = sin(angle * warp + phase * speed)
            val cosA = cos(angle + phase * 0.4f)

            val x = cx + radius * cos(angle) * (0.75f + 0.18f * sinA)
            val y = cy + radius * sin(angle) * (0.55f + 0.15f * cosA) * 0.7f +
                    radius * sinA * 0.35f

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        paintRibbon.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(alpha, Color.red(colorA), Color.green(colorA), Color.blue(colorA)),
                Color.argb(alpha / 2, Color.red(colorB), Color.green(colorB), Color.blue(colorB)),
                Color.argb(alpha, Color.red(colorA), Color.green(colorA), Color.blue(colorA))
            ),
            floatArrayOf(0f, 0.5f, 1f)
        )
        paintRibbon.style = Paint.Style.STROKE
        paintRibbon.strokeWidth = thickness
        paintRibbon.strokeCap = Paint.Cap.ROUND
        paintRibbon.maskFilter = BlurMaskFilter(thickness * 0.6f, BlurMaskFilter.Blur.NORMAL)

        canvas.drawPath(path, paintRibbon)
        canvas.restore()

        paintRibbon.maskFilter = null
    }

    // 4. Núcleo brillante blanco en el centro
    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val coreR = radius * (0.18f + energy * 0.12f)
        val glowR = radius * (0.45f + energy * 0.2f)

        // Halo exterior suave
        paintGlow.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                Color.argb((80 + energy * 100).toInt().coerceAtMost(180), 255, 255, 255),
                Color.argb(0, 200, 210, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paintGlow.maskFilter = BlurMaskFilter(glowR * 0.5f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, glowR, paintGlow)
        paintGlow.maskFilter = null

        // Núcleo duro
        paintCore.shader = RadialGradient(
            cx - coreR * 0.2f, cy - coreR * 0.2f, coreR,
            intArrayOf(white, Color.parseColor("#C0E8FF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, paintCore)
    }

    // 5. Borde exterior brillante con reflejo superior
    private fun drawOuterBorder(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Borde principal
        paintBorder.shader = null
        paintBorder.style = Paint.Style.STROKE
        paintBorder.strokeWidth = 2.5f
        paintBorder.color = Color.argb(120, 160, 180, 255)
        canvas.drawCircle(cx, cy, radius, paintBorder)

        // Reflejo superior (arco blanco)
        paintBorder.color = Color.argb(180, 255, 255, 255)
        paintBorder.strokeWidth = 3f
        paintBorder.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        val oval = RectF(cx - radius * 0.65f, cy - radius * 0.92f, cx + radius * 0.65f, cy - radius * 0.3f)
        canvas.drawArc(oval, 200f, 140f, false, paintBorder)
        paintBorder.maskFilter = null
    }

    fun reset() {
        smoothRms = 0f
        time = 0f
    }

    fun setEnergyLevel(level: Float) {
        smoothRms = (level / 12f).coerceIn(0f, 1f)
    }

    fun getCurrentRms(): Float = smoothRms

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}