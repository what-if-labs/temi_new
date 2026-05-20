package com.example.temicontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.*

class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var faceState = FaceState.IDLE
    private var blinkProgress = 0f
    private var isBlinking = false
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Animation state for alert effects
    private var pulsePhase = 0f
    private var patrolAngle = 0f
    private var animationJob: Job? = null

    enum class FaceState {
        IDLE,            // Neutral, blinking
        HAPPY,           // Big smile
        THINKING,        // Looking up
        MOVING,          // Excited
        SPEAKING,        // Talking mouth
        CONFUSED,        // Question mark
        SLEEPY,          // Half closed eyes
        PATROL_ACTIVE,   // Blue cycling dots around face
        ALERT_LOITERING, // Orange pulsing
        ALERT_SMOKING,   // Red with warning icon
        ALERT_FALL,      // Red urgent flash
        ALERT_BAG,       // Yellow caution
        ALERT_QUEUE,     // Orange
        ALERT_VEHICLE    // Orange (dark)
    }

    init {
        startBlinking()
        startAlertAnimations()
    }

    fun setState(state: FaceState) {
        faceState = state
        invalidate()
    }

    private fun startBlinking() {
        scope.launch {
            while (isAttachedToWindow) {
                delay(2000 + (Math.random() * 2000).toLong())
                blink()
            }
        }
    }

    private suspend fun blink() {
        isBlinking = true
        for (i in 0..5) {
            blinkProgress = i / 5f
            invalidate()
            delay(20)
        }
        for (i in 5 downTo 0) {
            blinkProgress = i / 5f
            invalidate()
            delay(20)
        }
        isBlinking = false
        blinkProgress = 0f
        invalidate()
    }

    private fun startAlertAnimations() {
        animationJob = scope.launch {
            while (isAttachedToWindow) {
                // Pulse animation for ALERT_FALL and ALERT_LOITERING
                pulsePhase = (pulsePhase + 0.1f) % (2f * Math.PI.toFloat())

                // Patrol cycling dots animation
                patrolAngle = (patrolAngle + 5f) % 360f

                // Redraw only if in an animated state
                when (faceState) {
                    FaceState.ALERT_FALL,
                    FaceState.ALERT_LOITERING,
                    FaceState.PATROL_ACTIVE -> invalidate()
                    else -> {}
                }

                delay(30) // ~33fps for smooth animation
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val faceRadius = minOf(width, height) * 0.4f

        // Draw face background with state-based color
        paint.color = getFaceColor()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, faceRadius, paint)

        // Draw eyes
        drawEyes(canvas, centerX, centerY, faceRadius)

        // Draw mouth
        drawMouth(canvas, centerX, centerY, faceRadius)

        // Draw extra elements for certain states
        when (faceState) {
            FaceState.THINKING -> drawThinkingBubble(canvas, centerX, centerY, faceRadius)
            FaceState.CONFUSED -> drawQuestionMark(canvas, centerX, centerY, faceRadius)
            FaceState.ALERT_SMOKING -> drawWarningIcon(canvas, centerX, centerY, faceRadius)
            FaceState.ALERT_FALL -> drawFallWarning(canvas, centerX, centerY, faceRadius)
            FaceState.ALERT_BAG -> drawCautionIcon(canvas, centerX, centerY, faceRadius)
            FaceState.ALERT_LOITERING -> drawPulseRing(canvas, centerX, centerY, faceRadius)
            FaceState.PATROL_ACTIVE -> drawPatrolDots(canvas, centerX, centerY, faceRadius)
            else -> {}
        }
    }

    private fun getFaceColor(): Int {
        return when (faceState) {
            FaceState.IDLE -> Color.parseColor("#FFD93D")       // Yellow
            FaceState.HAPPY -> Color.parseColor("#6BCF7F")      // Green
            FaceState.THINKING -> Color.parseColor("#4D96FF")   // Blue
            FaceState.MOVING -> Color.parseColor("#FF6B6B")     // Red
            FaceState.SPEAKING -> Color.parseColor("#FFD93D")   // Yellow
            FaceState.CONFUSED -> Color.parseColor("#C9B1FF")   // Purple
            FaceState.SLEEPY -> Color.parseColor("#95A5A6")     // Gray
            FaceState.PATROL_ACTIVE -> Color.parseColor("#2196F3") // Blue
            FaceState.ALERT_LOITERING -> Color.parseColor("#FF9800") // Orange
            FaceState.ALERT_SMOKING -> Color.parseColor("#F44336")   // Red
            FaceState.ALERT_FALL -> getAlertFlashColor()        // Pulsing red
            FaceState.ALERT_BAG -> Color.parseColor("#FFC107")  // Yellow
            FaceState.ALERT_QUEUE -> Color.parseColor("#FF9800") // Orange
            FaceState.ALERT_VEHICLE -> Color.parseColor("#E65100") // Dark orange
        }
    }

    private fun getAlertFlashColor(): Int {
        // Pulsing effect for FALL alert: alternate between bright and dark red
        val intensity = (Math.sin(pulsePhase.toDouble()).toFloat() + 1f) / 2f
        val r = 211 // D3
        val baseG = 47 // 2F
        val baseB = 47 // 2F
        val g = (baseG + intensity * 30).toInt().coerceAtMost(255)
        val b = (baseB + intensity * 30).toInt().coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    private fun drawEyes(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        val eyeSpacing = faceRadius * 0.5f
        val eyeY = centerY - faceRadius * 0.15f
        val eyeRadius = faceRadius * 0.15f

        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL

        // Left eye
        if (isBlinking || faceState == FaceState.SLEEPY) {
            canvas.drawLine(
                centerX - eyeSpacing - eyeRadius, eyeY,
                centerX - eyeSpacing + eyeRadius, eyeY,
                paint
            )
        } else {
            canvas.drawCircle(centerX - eyeSpacing, eyeY, eyeRadius, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(
                centerX - eyeSpacing - eyeRadius * 0.3f,
                eyeY - eyeRadius * 0.3f,
                eyeRadius * 0.3f,
                paint
            )
            paint.color = Color.BLACK
        }

        // Right eye
        if (isBlinking || faceState == FaceState.SLEEPY) {
            canvas.drawLine(
                centerX + eyeSpacing - eyeRadius, eyeY,
                centerX + eyeSpacing + eyeRadius, eyeY,
                paint
            )
        } else {
            canvas.drawCircle(centerX + eyeSpacing, eyeY, eyeRadius, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(
                centerX + eyeSpacing - eyeRadius * 0.3f,
                eyeY - eyeRadius * 0.3f,
                eyeRadius * 0.3f,
                paint
            )
            paint.color = Color.BLACK
        }
    }

    private fun drawMouth(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = faceRadius * 0.08f

        val mouthY = centerY + faceRadius * 0.25f
        val mouthWidth = faceRadius * 0.6f

        when (faceState) {
            FaceState.IDLE, FaceState.SLEEPY -> {
                val rect = RectF(
                    centerX - mouthWidth * 0.4f,
                    mouthY - faceRadius * 0.1f,
                    centerX + mouthWidth * 0.4f,
                    mouthY + faceRadius * 0.1f
                )
                canvas.drawArc(rect, 20f, 140f, false, paint)
            }
            FaceState.HAPPY, FaceState.MOVING -> {
                val rect = RectF(
                    centerX - mouthWidth * 0.5f,
                    mouthY - faceRadius * 0.15f,
                    centerX + mouthWidth * 0.5f,
                    mouthY + faceRadius * 0.15f
                )
                canvas.drawArc(rect, 0f, 180f, false, paint)
            }
            FaceState.THINKING -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, mouthY, faceRadius * 0.08f, paint)
            }
            FaceState.SPEAKING -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, mouthY, faceRadius * 0.12f, paint)
            }
            FaceState.CONFUSED -> {
                paint.strokeWidth = faceRadius * 0.05f
                canvas.drawLine(
                    centerX - mouthWidth * 0.3f, mouthY,
                    centerX + mouthWidth * 0.3f, mouthY,
                    paint
                )
            }
            // Alert states: straight serious mouth
            FaceState.ALERT_LOITERING,
            FaceState.ALERT_SMOKING,
            FaceState.ALERT_FALL,
            FaceState.ALERT_BAG,
            FaceState.ALERT_QUEUE,
            FaceState.ALERT_VEHICLE,
            FaceState.PATROL_ACTIVE -> {
                paint.strokeWidth = faceRadius * 0.06f
                canvas.drawLine(
                    centerX - mouthWidth * 0.3f, mouthY,
                    centerX + mouthWidth * 0.3f, mouthY,
                    paint
                )
            }
        }
    }

    private fun drawThinkingBubble(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.alpha = 180

        val bubbleX = centerX + faceRadius * 0.8f
        val bubbleY = centerY - faceRadius * 0.8f

        canvas.drawCircle(bubbleX, bubbleY, faceRadius * 0.15f, paint)
        canvas.drawCircle(bubbleX + faceRadius * 0.2f, bubbleY - faceRadius * 0.2f, faceRadius * 0.1f, paint)
        canvas.drawCircle(bubbleX + faceRadius * 0.35f, bubbleY - faceRadius * 0.35f, faceRadius * 0.06f, paint)

        paint.alpha = 255
    }

    private fun drawQuestionMark(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = faceRadius * 0.5f
        paint.textAlign = Paint.Align.CENTER

        canvas.drawText("?", centerX + faceRadius * 0.6f, centerY - faceRadius * 0.5f, paint)
    }

    // --- Alert state drawing methods ---

    private fun drawWarningIcon(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        // Draw warning triangle with exclamation mark above the face
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE

        // Triangle
        val triSize = faceRadius * 0.35f
        val triY = centerY - faceRadius * 0.75f
        val path = Path()
        path.moveTo(centerX, triY - triSize)
        path.lineTo(centerX - triSize, triY + triSize * 0.6f)
        path.lineTo(centerX + triSize, triY + triSize * 0.6f)
        path.close()
        canvas.drawPath(path, paint)

        // Exclamation mark
        paint.color = Color.parseColor("#F44336")
        paint.textSize = triSize * 1.2f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("!", centerX, triY + triSize * 0.35f, paint)
    }

    private fun drawFallWarning(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        // Pulsing ring around face for urgent fall alert
        val pulseScale = 1f + (Math.sin(pulsePhase.toDouble()).toFloat() + 1f) * 0.15f
        val ringRadius = faceRadius * pulseScale

        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#FF1744") // Bright red
        paint.strokeWidth = faceRadius * 0.06f
        paint.alpha = 180 + (Math.sin(pulsePhase.toDouble()).toFloat() * 75).toInt()

        canvas.drawCircle(centerX, centerY, ringRadius, paint)

        // Person fallen icon (small stick figure lying down)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = faceRadius * 0.25f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("⚠", centerX, centerY + faceRadius * 0.8f, paint)
    }

    private fun drawCautionIcon(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        // Caution triangle for abandoned bag
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#333333")

        val triSize = faceRadius * 0.3f
        val triY = centerY - faceRadius * 0.7f
        val path = Path()
        path.moveTo(centerX, triY - triSize)
        path.lineTo(centerX - triSize, triY + triSize * 0.6f)
        path.lineTo(centerX + triSize, triY + triSize * 0.6f)
        path.close()
        canvas.drawPath(path, paint)

        // Bag icon text
        paint.color = Color.parseColor("#FFC107")
        paint.textSize = triSize * 1.0f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("👜", centerX, triY + triSize * 0.4f, paint)
    }

    private fun drawPulseRing(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        // Orange pulsing ring for loitering alert
        val pulseScale = 1f + (Math.sin(pulsePhase.toDouble()).toFloat() + 1f) * 0.1f
        val ringRadius = faceRadius * pulseScale

        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#FF9800")
        paint.strokeWidth = faceRadius * 0.04f
        paint.alpha = 150 + (Math.sin(pulsePhase.toDouble()).toFloat() * 100).toInt()

        canvas.drawCircle(centerX, centerY, ringRadius, paint)

        // "Loiter" text indicator
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = faceRadius * 0.2f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("👁", centerX, centerY + faceRadius * 0.75f, paint)
    }

    private fun drawPatrolDots(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        // Cycling dots around the face perimeter for patrol mode
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = 200

        val dotRadius = faceRadius * 0.06f
        val orbitRadius = faceRadius * 1.15f
        val numDots = 5

        for (i in 0 until numDots) {
            val angle = Math.toRadians((patrolAngle + i * (360f / numDots)).toDouble())
            val dotX = (centerX + orbitRadius * Math.cos(angle)).toFloat()
            val dotY = (centerY + orbitRadius * Math.sin(angle)).toFloat()

            // Fade dots based on position for cycling effect
            val fade = 0.4f + 0.6f * ((Math.cos(angle - Math.toRadians(patrolAngle.toDouble())).toFloat() + 1f) / 2f)
            paint.alpha = (200 * fade).toInt()

            canvas.drawCircle(dotX, dotY, dotRadius, paint)
        }

        paint.alpha = 255

        // Small patrol indicator text
        paint.color = Color.WHITE
        paint.textSize = faceRadius * 0.18f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("🔍", centerX, centerY + faceRadius * 0.75f, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        animationJob?.cancel()
    }
}
