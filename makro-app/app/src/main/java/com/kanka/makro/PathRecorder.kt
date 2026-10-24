package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay

data class JoystickStep(
    val deltaX: Float,
    val deltaY: Float,
    val durationMs: Long
)

class PathRecorder(private val service: AccessibilityService) {
    var joystickCenterX = 450f
    var joystickCenterY = 750f
    var joystickRadius = 130f

    val recordedSteps = mutableListOf<JoystickStep>()
    var isRecording = false
        private set

    fun startRecording() {
        recordedSteps.clear()
        isRecording = true
    }

    fun recordStep(dx: Float, dy: Float, durationMs: Long) {
        if (isRecording) {
            recordedSteps.add(JoystickStep(dx, dy, durationMs))
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    suspend fun playForward() {
        for (step in recordedSteps) {
            applyVector(step.deltaX, step.deltaY, step.durationMs)
            delay(step.durationMs + 20)
        }
    }

    suspend fun playBackward() {
        val reversed = recordedSteps.reversed()
        for (step in reversed) {
            applyVector(-step.deltaX, -step.deltaY, step.durationMs)
            delay(step.durationMs + 20)
        }
    }

    private fun applyVector(dx: Float, dy: Float, duration: Long) {
        val targetX = joystickCenterX + (dx * joystickRadius)
        val targetY = joystickCenterY + (dy * joystickRadius)

        val path = Path().apply {
            moveTo(joystickCenterX, joystickCenterY)
            lineTo(targetX, targetY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
