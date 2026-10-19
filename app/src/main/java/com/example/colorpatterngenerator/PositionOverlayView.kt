package com.example.colorpatterngenerator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class PositionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val staticPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val dynamicPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bubblePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        alpha = 180
    }

    private var centerX = 0f
    private var centerY = 0f
    private var gravityAngle = 0.0 // Radians
    private var pitch = 0.0 // Degrees
    
    // Cross size
    private val crossLength = 200f
    private val bubbleRadius = 20f
    private val bubbleTrackRadius = 150f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
    }
    
    fun setCenter(x: Float, y: Float) {
        centerX = x
        centerY = y
        invalidate()
    }

    fun updateGravityAngle(angleRad: Double, pitchDeg: Double) {
        gravityAngle = angleRad
        pitch = pitchDeg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Static Cross (Gray) - Aligned with screen
        canvas.drawLine(centerX - crossLength, centerY, centerX + crossLength, centerY, staticPaint)
        canvas.drawLine(centerX, centerY - crossLength, centerX, centerY + crossLength, staticPaint)

        // 2. Draw Dynamic Cross (Green) - Aligned with Gravity
        canvas.save()
        canvas.rotate(Math.toDegrees(gravityAngle).toFloat(), centerX, centerY)
        
        // Draw the cross
        canvas.drawLine(centerX - crossLength, centerY, centerX + crossLength, centerY, dynamicPaint)
        canvas.drawLine(centerX, centerY - crossLength, centerX, centerY + crossLength, dynamicPaint)
        
        // Draw Down Indicator
        dynamicPaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY + crossLength, 15f, dynamicPaint)
        dynamicPaint.style = Paint.Style.STROKE
        
        canvas.restore()
        
        // 3. Draw Pitch Bubbles (iOS Compass Style)
        // Two bubbles moving away from center as pitch increases (0 is vertical)
        // If pitch is 0 (vertical), bubbles overlap at center? Or iOS style:
        // Actually iOS compass level:
        // - Two circles.
        // - When perfectly level (flat on table), they overlap.
        // - Here we are talking about "Vertical" alignment (Pitch).
        // "Two bubbles moving away from each other"
        
        // Pitch 0 = Upright Vertical (Phone held normally) -> Bubbles at center
        // Pitch +/- 90 = Flat -> Bubbles far apart
        // Let's map pitch (approx -90 to 90) to offset.
        // Pitch is usually 0 when flat on table? No, definition depends on sensor.
        // In MainActivity: pitch = atan2(y, sqrt(x*x + z*z))
        // If phone is upright (Portrait): y is gravity (9.8), x=0, z=0. Pitch = 90 deg?
        // Let's re-verify pitch definition in MainActivity.
        
        // Standard Android:
        // Upright: Y axis points UP (against gravity). Gravity vector is (0, -9.8, 0)? No.
        // Gravity sensor: (0, 9.8, 0) when device is flat on table?
        // Flat on table: Z = 9.8.
        // Upright Portrait: Y = 9.8.
        // Pitch = atan2(Y, sqrt(X*X + Z*Z))
        // Upright: Y=9.8, X=0, Z=0 -> atan2(1, 0) -> 90 degrees.
        
        // So Pitch 90 is Vertical. Pitch 0 is Flat.
        // Requirement: "Show if pitch is vertical"
        // So target is 90 degrees.
        // Error = 90 - abs(pitch).
        
        // Bubbles:
        // Bubble 1 (Fixed): Center outline
        // Bubble 2 (Moving): Moves based on error.
        
        // User request: "Two bubbles moving away from each other"
        // Let's implement:
        // Distance between bubbles proportional to error from 90 degrees.
        // Direction: Vertical axis on screen.
        
        val targetPitch = 90.0
        val error = pitch - targetPitch // 0 when vertical
        
        // Map error to distance. Max error ~90. Max distance ~100px.
        val distance = (error / 90.0 * 200.0).toFloat()
        
        // Bubble 1 moves Up, Bubble 2 moves Down
        // Or just one bubble moving relative to center.
        // "Two bubbles moving away from each other"
        
        val bubbleY1 = centerY - distance
        val bubbleY2 = centerY + distance
        
        canvas.drawCircle(centerX, bubbleY1, bubbleRadius, bubblePaint)
        canvas.drawCircle(centerX, bubbleY2, bubbleRadius, bubblePaint)
        
        // Draw target lines for bubbles
        canvas.drawLine(centerX - 30, centerY, centerX + 30, centerY, staticPaint)
    }
}
