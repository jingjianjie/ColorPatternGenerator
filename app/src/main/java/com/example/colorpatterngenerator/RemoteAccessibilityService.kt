package com.example.colorpatterngenerator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FileLogger.log("RD", "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events, just perform actions
    }

    override fun onInterrupt() {
        instance = null
        FileLogger.log("RD", "Accessibility Service Interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        val gesture = builder.build()
        dispatchGesture(gesture, null, null)
        FileLogger.log("RD", "Tap at $x, $y")
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        val gesture = builder.build()
        dispatchGesture(gesture, null, null)
        FileLogger.log("RD", "Swipe $x1,$y1 -> $x2,$y2 ($duration ms)")
    }
    
    fun performGlobalKey(action: Int) {
        performGlobalAction(action)
        FileLogger.log("RD", "Global Action: $action")
    }
}