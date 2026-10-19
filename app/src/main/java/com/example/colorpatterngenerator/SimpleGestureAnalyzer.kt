package com.example.colorpatterngenerator

import android.util.Log
import java.nio.ByteBuffer

class SimpleGestureAnalyzer(private val onGesture: (String) -> Unit) {

    private var lastLeftAvg = 0.0
    private var lastRightAvg = 0.0
    private var lastTimestamp = 0L
    private val THRESHOLD = 15.0 // Restored threshold
    private val TIME_THRESHOLD = 500 // Restored window
    private var triggerState = 0 // 0: None, 1: Left Triggered, 2: Right Triggered
    private var triggerTime = 0L
    private var isCoolingDown = false

    fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        if (isCoolingDown) {
            if (System.currentTimeMillis() - triggerTime > 1000) {
                isCoolingDown = false
            }
            imageProxy.close()
            return
        }

        val buffer = imageProxy.planes[0].buffer // Y channel
        val width = imageProxy.width
        val height = imageProxy.height
        
        // Sample middle strip
        val startY = height / 3
        val endY = height * 2 / 3
        val midX = width / 2
        
        var leftSum = 0L
        var rightSum = 0L
        var count = 0
        
        // Simple sampling (step 4 to save CPU)
        for (y in startY until endY step 4) {
            for (x in 0 until width step 4) {
                val pixel = buffer.get(y * width + x).toInt() and 0xFF
                if (x < midX) {
                    leftSum += pixel
                } else {
                    rightSum += pixel
                }
                count++
            }
        }
        
        val leftAvg = leftSum.toDouble() / (count / 2)
        val rightAvg = rightSum.toDouble() / (count / 2)
        
        // Detect sudden change
        val leftDelta = Math.abs(leftAvg - lastLeftAvg)
        val rightDelta = Math.abs(rightAvg - lastRightAvg)
        
        val currentTime = System.currentTimeMillis()
        
        if (triggerState == 0) {
            if (leftDelta > THRESHOLD && rightDelta < THRESHOLD) {
                // Left side changed first -> Likely Right Swipe (Hand moves Left->Right on screen, assuming mirrored)
                // Front camera is mirrored: moving hand Left->Right in physical world looks like Right->Left on screen?
                // Let's assume:
                // Trigger Left Region -> Then Trigger Right Region => Motion Left->Right (on image)
                triggerState = 1
                triggerTime = currentTime
            } else if (rightDelta > THRESHOLD && leftDelta < THRESHOLD) {
                triggerState = 2
                triggerTime = currentTime
            }
        } else {
            // Waiting for second half
            if (currentTime - triggerTime > TIME_THRESHOLD) {
                // Timeout
                triggerState = 0
            } else {
                if (triggerState == 1 && rightDelta > THRESHOLD) {
                    // Sequence: Left -> Right
                    onGesture("SWIPE_RIGHT") // Or Next
                    isCoolingDown = true
                    triggerState = 0
                } else if (triggerState == 2 && leftDelta > THRESHOLD) {
                    // Sequence: Right -> Left
                    onGesture("SWIPE_LEFT") // Or Prev
                    isCoolingDown = true
                    triggerState = 0
                }
            }
        }
        
        lastLeftAvg = leftAvg
        lastRightAvg = rightAvg
        lastTimestamp = currentTime
        
        imageProxy.close()
    }
}
