package com.example.temicontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    enum class FaceState {
        IDLE,       // Neutral, blinking
        HAPPY,      // Big smile
        THINKING,   // Looking up
        MOVING,     // Excited
        SPEAKING,   // Talking mouth
        CONFUSED,   // Question mark
        SLEEPY      // Half closed eyes
    }

    init {
        startBlinking()
    }

    fun setState(state: FaceState) {
        faceState = state
        invalidate()
    }

    private fun startBlinking() {
        scope.launch {
            while (isAttachedToWindow) {
                delay(2000 + (Math.random() * 2000).toLong()) // Random blink interval
                blink()
            }
        }
    }

    private suspend fun blink() {
        isBlinking = true
        // Close eyes
        for (i in 0..5) {
            blinkProgress = i / 5f
            invalidate()
            delay(20)
        }
        // Open eyes
        for (i in 5 downTo 0) {
            blinkProgress = i / 5f
            invalidate()
            delay(20)
        }
        isBlinking = false
        blinkProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val faceRadius = minOf(width, height) * 0.4f

        // Draw face background
        paint.color = when (faceState) {
            FaceState.IDLE -> Color.parseColor("#FFD93D") // Yellow
            FaceState.HAPPY -> Color.parseColor("#6BCF7F") // Green
            FaceState.THINKING -> Color.parseColor("#4D96FF") // Blue
            FaceState.MOVING -> Color.parseColor("#FF6B6B") // Red
            FaceState.SPEAKING -> Color.parseColor("#FFD93D") // Yellow
            FaceState.CONFUSED -> Color.parseColor("#C9B1FF") // Purple
            FaceState.SLEEPY -> Color.parseColor("#95A5A6") // Gray
        }
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
            else -> {}
        }
    }

    private fun drawEyes(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        val eyeSpacing = faceRadius * 0.5f
        val eyeY = centerY - faceRadius * 0.15f
        val eyeRadius = faceRadius * 0.15f

        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL

        // Left eye
        if (isBlinking || faceState == FaceState.SLEEPY) {
            // Draw closed eye (line)
            val blinkHeight = eyeRadius * (1 - blinkProgress) * 0.3f
            canvas.drawLine(
                centerX - eyeSpacing - eyeRadius, eyeY,
                centerX - eyeSpacing + eyeRadius, eyeY,
                paint
            )
        } else {
            // Draw open eye (circle)
            canvas.drawCircle(centerX - eyeSpacing, eyeY, eyeRadius, paint)
            // Draw highlight
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
            val blinkHeight = eyeRadius * (1 - blinkProgress) * 0.3f
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
                // Small smile
                val rect = RectF(
                    centerX - mouthWidth * 0.4f,
                    mouthY - faceRadius * 0.1f,
                    centerX + mouthWidth * 0.4f,
                    mouthY + faceRadius * 0.1f
                )
                canvas.drawArc(rect, 20f, 140f, false, paint)
            }
            FaceState.HAPPY, FaceState.MOVING -> {
                // Big smile
                val rect = RectF(
                    centerX - mouthWidth * 0.5f,
                    mouthY - faceRadius * 0.15f,
                    centerX + mouthWidth * 0.5f,
                    mouthY + faceRadius * 0.15f
                )
                canvas.drawArc(rect, 0f, 180f, false, paint)
            }
            FaceState.THINKING -> {
                // Small circle (o shape)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, mouthY, faceRadius * 0.08f, paint)
            }
            FaceState.SPEAKING -> {
                // Open mouth
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, mouthY, faceRadius * 0.12f, paint)
            }
            FaceState.CONFUSED -> {
                // Straight line
                paint.strokeWidth = faceRadius * 0.05f
                canvas.drawLine(
                    centerX - mouthWidth * 0.3f,
                    mouthY,
                    centerX + mouthWidth * 0.3f,
                    mouthY,
                    paint
                )
            }
        }
    }

    private fun drawThinkingBubble(canvas: Canvas, centerX: Float, centerY: Float, faceRadius: Float) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.alpha = 180

        // Draw thought bubbles
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}