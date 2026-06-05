package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * Nexus Orb — Renderizado Estructural Minimalista de un Octaedro (Rombo 3D Transparente)
 * con Núcleo de Luz Central Resplandeciente, Partículas y 6 Anillos 3D.
 */
class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var timeMs = 0L
    private var animator: ValueAnimator? = null

    // 🎛️ CONTROLES DE VELOCIDAD
    var speedGlobalMorph  = 1.0f
    var speed3DBalanceo   = 1.0f

    var speedOrbWave1     = 1.2f
    var speedOrbWave2     = 1.6f

    // Anillos Originales (1, 2, 3)
    var speedRing1Wave1   = 2.0f
    var speedRing1Wave2   = 2.9f
    var speedRing2Wave1   = 2.8f
    var speedRing2Wave2   = 1.3f
    var speedRing2Rotation = 1.9f
    var speedRing3Wave1   = 1.1f
    var speedRing3Wave2   = 1.4f
    var speedRing3Rotation = 1.5f

    // NUEVOS ANILLOS ADICIONALES (4, 5, 6)
    var speedRing4Wave1   = 1.5f
    var speedRing4Wave2   = 2.2f
    var speedRing4Rotation = 1.2f
    var speedRing5Wave1   = 2.1f
    var speedRing5Wave2   = 1.7f
    var speedRing5Rotation = 2.0f
    var speedRing6Wave1   = 1.3f
    var speedRing6Wave2   = 2.5f
    var speedRing6Rotation = 0.8f

    // 🆕 CONTROL DEL ORBE VIAJERO
    var speedTravelingOrb = 0.8f

    // --- Paints ---
    private val paintAmbient   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintOverlay   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing1     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing2     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintParticle  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPrismCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintTravelGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    // Paint para estructurar las aristas internas del Rombo (Modo Neón Eléctrico)
    private val paintRhombusEdges = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // --- Colores ---
    private val col00daf3 = Color.parseColor("#00daf3")

    private val BLOB_POINTS = 80
    private var energy = 0f

    // --- Estructuras de Datos Proyectadas ---
    private data class RingSegment(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val zMid: Float,
        val alphaFactor: Float,
        val baseStrokeWidth: Float,
        val baseColor: Int,
        val isRing2: Boolean
    )

    private data class Particle3D(
        val localX: Float, val localY: Float, val localZ: Float,
        val period: Float, val phaseOffset: Float, val size: Float
    )

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private val particles3D = mutableListOf<Particle3D>()
    private val density = resources.displayMetrics.density

    init {
        // LAYER_TYPE_SOFTWARE permite efectos avanzados como desenfoques blur de máscara suaves
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        paintRing1.strokeCap = Paint.Cap.ROUND
        paintRing2.strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildParticles3D(w, h)
        if (animator == null) startLoop()
    }

    private fun buildParticles3D(w: Int, h: Int) {
        particles3D.clear()
        val r = min(w, h) * 0.40f
        repeat(25) {
            val theta = Math.random().toFloat() * PI.toFloat()
            val phi = Math.random().toFloat() * 2f * PI.toFloat()
            val mRadius = (0.1f + 0.5f * Math.random().toFloat()) * r

            particles3D += Particle3D(
                localX = mRadius * sin(theta) * cos(phi),
                localY = mRadius * sin(theta) * sin(phi),
                localZ = mRadius * cos(theta),
                period = 3000f + Math.random().toFloat() * 4000f,
                phaseOffset = Math.random().toFloat() * 2f * PI.toFloat(),
                size = (Math.random().toFloat() * 2.0f + 1.0f) * density
            )
        }
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                timeMs += 16
                invalidate()
            }
            start()
        }
    }

    private fun generate3DRingSegments(
        cx: Float, cy: Float, baseRadius: Float, blobPhase: Float,
        wavePerturbations: List<Triple<Float, Float, Float>>,
        rotX: Float, rotY: Float, spinRotationDeg: Float,
        strokeWidth: Float, baseColor: Int, isRing2: Boolean
    ): List<RingSegment> {
        val segments = mutableListOf<RingSegment>()
        val radY = Math.toRadians(rotY.toDouble()).toFloat()
        val radX = Math.toRadians(rotX.toDouble()).toFloat()
        val radSpin = Math.toRadians(spinRotationDeg.toDouble()).toFloat()

        val pulse = 1f + energy * 0.25f
        val rBase = baseRadius * pulse

        val px = FloatArray(BLOB_POINTS + 1)
        val py = FloatArray(BLOB_POINTS + 1)
        val pz = FloatArray(BLOB_POINTS + 1)

        for (i in 0..BLOB_POINTS) {
            val angle = i.toFloat() / BLOB_POINTS * 2 * PI.toFloat()

            val rOrganicoBase = rBase * (1f + (0.12f * sin((3 * angle).toDouble()).toFloat() + 0.08f * cos((2 * angle).toDouble()).toFloat()))
            val rOrganicoBase2 = rBase * (1f + (0.10f * sin((2 * angle + PI).toDouble()).toFloat() + 0.10f * cos((3 * angle).toDouble()).toFloat()))
            val rOrganico = rOrganicoBase * (1f - blobPhase) + rOrganicoBase2 * blobPhase

            var wavePerturbationSum = 0f
            for ((frequency, amplitudeFact, shiftPhase) in wavePerturbations) {
                val totalPhase = frequency * angle + shiftPhase
                wavePerturbationSum += rBase * amplitudeFact * sin(totalPhase.toDouble()).toFloat()
            }

            val rBlend = rOrganico + wavePerturbationSum

            val dynamicAngle = angle + radSpin
            val localX = rBlend * cos(dynamicAngle)
            val localY = rBlend * sin(dynamicAngle)
            val localZ = 0f

            val rotY_X = localX * cos(radY) - localZ * sin(radY)
            val rotY_Z = localX * sin(radY) + localZ * cos(radY)

            val finalX = rotY_X
            val finalY = localY * cos(radX) - rotY_Z * sin(radX)
            val finalZ = localY * sin(radX) + rotY_Z * cos(radX)

            val distance = baseRadius * 2.5f
            val perspective = distance / (distance - finalZ)

            px[i] = cx + finalX * perspective
            py[i] = cy + finalY * perspective
            pz[i] = finalZ
        }

        for (i in 0 until BLOB_POINTS) {
            val zMid = (pz[i] + pz[i + 1]) / 2f
            val depthNorm = (zMid / baseRadius).coerceIn(-1f, 1f)
            val alphaFact = ((depthNorm + 1f) / 2f).coerceIn(0.1f, 1f)

            segments += RingSegment(
                x1 = px[i], y1 = py[i],
                x2 = px[i + 1], y2 = py[i + 1],
                zMid = zMid,
                alphaFactor = alphaFact,
                baseStrokeWidth = strokeWidth,
                baseColor = baseColor,
                isRing2 = isRing2
            )
        }
        return segments
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.50f
        val t = timeMs / 1000.0

        val blobPhase = ((sin(t * speedGlobalMorph * 2 * PI / 8.0) + 1.0) / 2.0).toFloat()
        val inverseBlobPhase = 1f - blobPhase
        val pulseScale = 1f + 0.05f * sin(t * speedGlobalMorph * 2 * PI / 4.0).toFloat()

        val ring2Rot = (t / 30.0 * 360.0 * speedRing2Rotation).toFloat()
        val ring3Rot = (t / 20.0 * 360.0 * speedRing3Rotation).toFloat()

        val ring4Rot = (t / 25.0 * 360.0 * speedRing4Rotation).toFloat()
        val ring5Rot = (t / 35.0 * 360.0 * speedRing5Rotation).toFloat()
        val ring6Rot = (t / 15.0 * 360.0 * speedRing6Rotation).toFloat()

        // Balanceo espacial coordinado
        val rotationY = (t * 22.0 * speed3DBalanceo).toFloat()
        val rotationX = 14f * sin(t * 1.1 * speed3DBalanceo).toFloat()

        // Ondas para anillos
        val r1Shift1 = (t * 2 * PI / 6.0 * speedRing1Wave1).toFloat()
        val r1Shift2 = (t * 2 * PI / 4.0 * speedRing1Wave2).toFloat()
        val ring1Waves = listOf(Triple(4f, 0.04f, r1Shift1), Triple(7f, 0.02f, r1Shift2))

        val r2Shift1 = (t * 2 * PI / 6.0 * speedRing2Wave1).toFloat()
        val r2Shift2 = (t * 2 * PI / 4.0 * speedRing2Wave2).toFloat()
        val ring2Waves = listOf(Triple(4f, 0.04f, r2Shift1), Triple(7f, 0.02f, r2Shift2))

        val r3Shift1 = (t * 2 * PI / 6.0 * speedRing3Wave1).toFloat()
        val r3Shift2 = (t * 2 * PI / 4.0 * speedRing3Wave2).toFloat()
        val ring3Waves = listOf(Triple(4f, 0.04f, r3Shift1), Triple(7f, 0.02f, r3Shift2))

        val r4Shift1 = (t * 2 * PI / 6.0 * speedRing4Wave1).toFloat()
        val r4Shift2 = (t * 2 * PI / 4.0 * speedRing4Wave2).toFloat()
        val ring4Waves = listOf(Triple(5f, 0.035f, r4Shift1), Triple(8f, 0.018f, r4Shift2))

        val r5Shift1 = (t * 2 * PI / 6.0 * speedRing5Wave1).toFloat()
        val r5Shift2 = (t * 2 * PI / 4.0 * speedRing5Wave2).toFloat()
        val ring5Waves = listOf(Triple(4f, 0.045f, r5Shift1), Triple(6f, 0.022f, r5Shift2))

        val r6Shift1 = (t * 2 * PI / 6.0 * speedRing6Wave1).toFloat()
        val r6Shift2 = (t * 2 * PI / 4.0 * speedRing6Wave2).toFloat()
        val ring6Waves = listOf(Triple(6f, 0.03f, r6Shift1), Triple(9f, 0.015f, r6Shift2))

        val allSegments = mutableListOf<RingSegment>()
        allSegments += generate3DRingSegments(cx, cy, radius * 1.06f, inverseBlobPhase, ring1Waves, rotationX, rotationY, 0f, 3.5f * density, Color.argb(130, 255, 255, 255), false)
        allSegments += generate3DRingSegments(cx, cy, radius * 1.12f, inverseBlobPhase, ring3Waves, rotationX + 25f, rotationY - 30f, -ring3Rot, 1f * density, Color.argb(45, 255, 255, 255), false)
        allSegments += generate3DRingSegments(cx, cy, radius * 1.18f, inverseBlobPhase, ring2Waves, rotationX - 40f, rotationY + 20f, ring2Rot, 1.5f * density, Color.argb(130, 0, 218, 243), true)
        allSegments += generate3DRingSegments(cx, cy, radius * 1.24f, inverseBlobPhase, ring4Waves, rotationX + 45f, rotationY + 45f, ring4Rot, 1.2f * density, Color.argb(90, 0, 218, 243), true)
        allSegments += generate3DRingSegments(cx, cy, radius * 1.30f, inverseBlobPhase, ring5Waves, rotationX - 15f, rotationY - 60f, -ring5Rot, 1f * density, Color.argb(55, 255, 255, 255), false)
        allSegments += generate3DRingSegments(cx, cy, radius * 1.36f, inverseBlobPhase, ring6Waves, rotationX + 60f, rotationY - 10f, ring6Rot, 1.5f * density, Color.argb(120, 0, 218, 243), true)

        val backSegments = allSegments.filter { it.zMid < -2f }
        val frontSegments = allSegments.filter { it.zMid >= -2f }

        // ═══════════════════════════════════════════════════════════════
        // RENDER PIPELINE - MODO ENERGÍA TRANSPARENTE
        // ═══════════════════════════════════════════════════════════════

        // Aura ambiental suave trasera
        drawAmbientGlows(canvas, cx, cy, radius * pulseScale)

        // 1. Capas traseras de los anillos periféricos
        draw3DRingSegmentsList(canvas, backSegments, radius)

        // 2. NÚCLEO DE LUZ CENTRAL (Resplandor puro flotando sin máscara de fondo)
        drawFloatingLightCore(canvas, cx, cy, radius, pulseScale, rotationX, rotationY)

        // 3. Estructura transparente de líneas del Rombo 3D
        drawRhombus3DEdges(canvas, cx, cy, radius * pulseScale, rotationX, rotationY)

        // 4. Partículas cuánticas libres internas
        draw3DParticles(canvas, cx, cy, radius, rotationX, rotationY)

        // 5. Capas delanteras de los anillos periféricos
        draw3DRingSegmentsList(canvas, frontSegments, radius)

        // Orbes viajeros (Fondo y Frente)
        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.18f, inverseBlobPhase, ring2Waves, rotationX - 40f, rotationY + 20f, ring2Rot, drawForegroundOnly = false, speed = 1.8f, orbColor = col00daf3, angleOffset = 0f)
        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.06f, inverseBlobPhase, ring1Waves, rotationX, rotationY, 0f, drawForegroundOnly = false, speed = 1.5f, orbColor = Color.WHITE, angleOffset = 3.14f)
        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.36f, inverseBlobPhase, ring6Waves, rotationX + 60f, rotationY - 10f, ring6Rot, drawForegroundOnly = false, speed = 1.5f, orbColor = Color.WHITE, angleOffset = 1.57f)

        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.18f, inverseBlobPhase, ring2Waves, rotationX - 40f, rotationY + 20f, ring2Rot, drawForegroundOnly = true, speed = 1.8f, orbColor = col00daf3, angleOffset = 0f)
        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.06f, inverseBlobPhase, ring1Waves, rotationX, rotationY, 0f, drawForegroundOnly = true, speed = 1.5f, orbColor = Color.WHITE, angleOffset = 3.14f)
        drawTravelingOrbOnRing(canvas, cx, cy, radius * 1.36f, inverseBlobPhase, ring6Waves, rotationX + 60f, rotationY - 10f, ring6Rot, drawForegroundOnly = true, speed = 1.5f, orbColor = Color.WHITE, angleOffset = 1.57f)
    }

    /**
     * 📐 ENERGÍA FACETADA: Dibuja las aristas de luz del Rombo en perspectiva 3D libre
     */
    private fun drawRhombus3DEdges(canvas: Canvas, cx: Float, cy: Float, r: Float, rotX: Float, rotY: Float) {
        val radY = Math.toRadians(rotY.toDouble()).toFloat()
        val radX = Math.toRadians(rotX.toDouble()).toFloat()

        // Geometría octaédrica exacta
        val localVertices = arrayOf(
            Point3D(0f, -r, 0f),  // 0: Norte
            Point3D(0f, r, 0f),   // 1: Sur
            Point3D(-r, 0f, 0f),  // 2: Oeste
            Point3D(r, 0f, 0f),   // 3: Este
            Point3D(0f, 0f, -r),  // 4: Fondo Z-
            Point3D(0f, 0f, r)    // 5: Frente Z+
        )

        val screenPoints = FloatArray(6 * 2)
        val zDepths = FloatArray(6)

        for (i in localVertices.indices) {
            val v = localVertices[i]
            val rY_X = v.x * cos(radY) - v.z * sin(radY)
            val rY_Z = v.x * sin(radY) + v.z * cos(radY)

            val finalX = rY_X
            val finalY = v.y * cos(radX) - rY_Z * sin(radX)
            val finalZ = v.y * sin(radX) + rY_Z * cos(radX)

            val distance = r * 2.5f
            val perspective = distance / (distance - finalZ)

            screenPoints[i * 2] = cx + finalX * perspective
            screenPoints[i * 2 + 1] = cy + finalY * perspective
            zDepths[i] = finalZ
        }

        val edges = arrayOf(
            Pair(0, 2), Pair(0, 3), Pair(0, 4), Pair(0, 5),
            Pair(1, 2), Pair(1, 3), Pair(1, 4), Pair(1, 5),
            Pair(2, 4), Pair(4, 3), Pair(3, 5), Pair(5, 2)
        )

        for (edge in edges) {
            val x1 = screenPoints[edge.first * 2]
            val y1 = screenPoints[edge.first * 2 + 1]
            val x2 = screenPoints[edge.second * 2]
            val y2 = screenPoints[edge.second * 2 + 1]

            val midZ = (zDepths[edge.first] + zDepths[edge.second]) / 2f
            val depthNorm = (midZ / r).coerceIn(-1f, 1f)

            // Modulación de opacidad y grosor según profundidad
            val alphaFactor = ((depthNorm + 1f) / 2f).coerceIn(0.20f, 0.90f)
            val strokeW = (1.0f + (depthNorm + 1f) * 0.8f) * density

            paintRhombusEdges.color = col00daf3
            paintRhombusEdges.alpha = (alphaFactor * 180).toInt()
            paintRhombusEdges.strokeWidth = strokeW

            canvas.drawLine(x1, y1, x2, y2, paintRhombusEdges)
        }
    }

    /**
     * 🔥 NUEVO: Genera el núcleo flotante de luz intensa en el epicentro del rombo
     */
    private fun drawFloatingLightCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, pulse: Float, rotX: Float, rotY: Float) {
        val radY = Math.toRadians(rotY.toDouble()).toFloat()
        val radX = Math.toRadians(rotX.toDouble()).toFloat()

        // Desfase dinámico de la fuente de luz según el cabeceo 3D
        val lightX = cx + sin(radY) * radius * 0.15f
        val lightY = cy - sin(radX) * radius * 0.15f
        val coreRadius = radius * pulse * 0.55f

        if (coreRadius <= 0f) return

        // Gradiente radial esférico puro hiper-brillante
        paintPrismCore.shader = RadialGradient(
            lightX, lightY, coreRadius,
            intArrayOf(Color.WHITE, Color.argb(220, 0, 218, 243), Color.argb(60, 123, 44, 191), Color.TRANSPARENT),
            floatArrayOf(0.0f, 0.25f, 0.65f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(lightX, lightY, coreRadius, paintPrismCore)
    }

    private fun drawTravelingOrbOnRing(
        canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, blobPhase: Float,
        wavePerturbations: List<Triple<Float, Float, Float>>,
        rotX: Float, rotY: Float, spinRotationDeg: Float,
        drawForegroundOnly: Boolean, speed: Float, orbColor: Int, angleOffset: Float
    ) {
        val t = timeMs / 1000.0
        val travelAngle = ((t * speed).toFloat() + angleOffset) % (2f * PI.toFloat())

        val radY = Math.toRadians(rotY.toDouble()).toFloat()
        val radX = Math.toRadians(rotX.toDouble()).toFloat()
        val radSpin = Math.toRadians(spinRotationDeg.toDouble()).toFloat()

        val pulse = 1f + energy * 0.25f
        val rBase = baseRadius * pulse

        val rOrganicoBase = rBase * (1f + (0.12f * sin((3 * travelAngle).toDouble()).toFloat() + 0.08f * cos((2 * travelAngle).toDouble()).toFloat()))
        val rOrganicoBase2 = rBase * (1f + (0.10f * sin((2 * travelAngle + PI).toDouble()).toFloat() + 0.10f * cos((3 * travelAngle).toDouble()).toFloat()))
        val rOrganico = rOrganicoBase * (1f - blobPhase) + rOrganicoBase2 * blobPhase

        var wavePerturbationSum = 0f
        for ((frequency, amplitudeFact, shiftPhase) in wavePerturbations) {
            val totalPhase = frequency * travelAngle + shiftPhase
            wavePerturbationSum += rBase * amplitudeFact * sin(totalPhase.toDouble()).toFloat()
        }
        val rBlend = rOrganico + wavePerturbationSum

        val dynamicAngle = travelAngle + radSpin
        val localX = rBlend * cos(dynamicAngle)
        val localY = rBlend * sin(dynamicAngle)
        val localZ = 0f

        val rotY_X = localX * cos(radY) - localZ * sin(radY)
        val rotY_Z = localX * sin(radY) + localZ * cos(radY)

        val finalX = rotY_X
        val finalY = localY * cos(radX) - rotY_Z * sin(radX)
        val finalZ = localY * sin(radX) + rotY_Z * cos(radX)

        val isForegroundPoint = finalZ >= -2f
        if (isForegroundPoint != drawForegroundOnly) return

        val distance = baseRadius * 2.5f
        val perspective = distance / (distance - finalZ)

        val screenX = cx + finalX * perspective
        val screenY = cy + finalY * perspective

        val depthNorm = (finalZ / baseRadius).coerceIn(-1f, 1f)
        val sizeScale = 1.0f + depthNorm * 0.4f
        val alphaFact = ((depthNorm + 1f) / 2f).coerceIn(0.2f, 1f)

        paintTravelGlow.reset()
        paintTravelGlow.isAntiAlias = true

        if (drawForegroundOnly) {
            paintTravelGlow.style = Paint.Style.FILL
            paintTravelGlow.color = orbColor
            paintTravelGlow.alpha = (alphaFact * 180).toInt()
            paintTravelGlow.maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(screenX, screenY, 9f * density * sizeScale, paintTravelGlow)

            paintTravelGlow.maskFilter = null
            paintTravelGlow.color = Color.WHITE
            paintTravelGlow.alpha = 255
            canvas.drawCircle(screenX, screenY, 3.5f * density * sizeScale, paintTravelGlow)
        } else {
            paintTravelGlow.style = Paint.Style.FILL
            paintTravelGlow.color = orbColor
            paintTravelGlow.alpha = (alphaFact * 110).toInt()
            canvas.drawCircle(screenX, screenY, 3f * density * sizeScale, paintTravelGlow)
        }
    }

    private fun drawAmbientGlows(canvas: Canvas, cx: Float, cy: Float, ambientRadius: Float) {
        if (ambientRadius <= 0f) return
        paintAmbient.shader = RadialGradient(
            cx, cy, ambientRadius * 1.5f,
            intArrayOf(Color.argb(35, 0, 218, 243), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paintAmbient.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, ambientRadius * 1.5f, paintAmbient)
    }

    private fun draw3DRingSegmentsList(canvas: Canvas, segments: List<RingSegment>, referenceRadius: Float) {
        for (seg in segments) {
            val targetPaint = if (seg.isRing2) paintRing2 else paintRing1
            val depthNorm = (seg.zMid / referenceRadius).coerceIn(-1f, 1f)
            val dynamicStrokeWidth = seg.baseStrokeWidth * (1.0f + depthNorm * 0.35f)

            val baseAlpha = Color.alpha(seg.baseColor)
            val finalAlpha = (baseAlpha * seg.alphaFactor).toInt().coerceIn(0, 255)
            val finalColor = (seg.baseColor Nations 0x00FFFFFF) or (finalAlpha shl 24)

            targetPaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = dynamicStrokeWidth
                color = finalColor
            }
            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, targetPaint)
        }
    }

    private fun draw3DParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotX: Float, rotY: Float) {
        paintParticle.style = Paint.Style.FILL
        val tMs = timeMs.toFloat()

        val radY = Math.toRadians(rotY.toDouble()).toFloat()
        val radX = Math.toRadians(rotX.toDouble()).toFloat()

        for (p in particles3D) {
            val phase = tMs / p.period * 2 * PI.toFloat() + p.phaseOffset
            val waveX = p.localX + (radius * 0.04f * sin(phase))
            val waveY = p.localY + (radius * 0.04f * cos(phase * 0.5f))
            val waveZ = p.localZ

            val rotY_X = waveX * cos(radY) - waveZ * sin(radY)
            val rotY_Z = waveX * sin(radY) + waveZ * cos(radY)

            val finalX = rotY_X
            val finalY = waveY * cos(radX) - rotY_Z * sin(radX)
            val finalZ = waveY * sin(radX) + rotY_Z * cos(radX)

            val distance = radius * 2.5f
            val perspectiveFactor = distance / (distance - finalZ)

            val screenX = cx + finalX * perspectiveFactor
            val screenY = cy + finalY * perspectiveFactor

            val depthNorm = (finalZ / radius).coerceIn(-1f, 1f)
            val sizeScale = 1f + depthNorm * 0.4f
            val alphaFactor = ((depthNorm + 1f) / 2f).coerceIn(0.1f, 1f)

            if (depthNorm < -0.3f) {
                paintParticle.maskFilter = BlurMaskFilter(1f * density, BlurMaskFilter.Blur.NORMAL)
            } else {
                paintParticle.maskFilter = null
            }

            paintParticle.color = Color.argb((alphaFactor * 200).toInt(), 255, 255, 255)
            canvas.drawCircle(screenX, screenY, p.size * sizeScale, paintParticle)
        }
        paintParticle.maskFilter = null
    }

    private infix fun Int.Nations(mask: Int): Int = this and mask

    fun updateRms(rmsDb: Float) {
        val target = if (rmsDb < 1.5f) 0f else (rmsDb / 12f).coerceIn(0f, 1f)
        energy += (target - energy) * 0.12f
        invalidate()
    }

    fun setEnergyLevel(level: Float) {
        energy = (level / 12f).coerceIn(0f, 1f)
        invalidate()
    }

    fun getCurrentRms(): Float = energy

    fun reset() {
        energy = 0f
        timeMs = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}