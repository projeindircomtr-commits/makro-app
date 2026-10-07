package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay

class InnHostessManager(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    suspend fun clickOpenButton() {
        val x = screenWidth * 0.505f
        val y = screenHeight * 0.542f
        tap(x, y)
        delay(700)
    }

    suspend fun clickInnHostesOpenMenu() {
        val x = screenWidth * 0.885f
        val y = screenHeight * 0.415f
        tap(x, y)
        delay(1200)
    }

    suspend fun dumpInventoryToBank(skipFirstSlotsCount: Int = 2) {
        val startX = screenWidth * 0.612f
        val startY = screenHeight * 0.628f
        val stepX = screenWidth * 0.053f
        val stepY = screenHeight * 0.088f

        val columns = 7
        val rows = 4

        var currentSlot = 0
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                if (currentSlot >= skipFirstSlotsCount) {
                    val posX = startX + (col * stepX)
                    val posY = startY + (row * stepY)
                    doubleTap(posX, posY)
                    delay(150)
                }
                currentSlot++
            }
        }
    }

    suspend fun closeBank() {
        val closeX = screenWidth * 0.865f
        val closeY = screenHeight * 0.065f
        tap(closeX, closeY)
        delay(500)
    }

    private fun tap(x: Float, y: Float, duration: Long = 60) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private suspend fun doubleTap(x: Float, y: Float) {
        tap(x, y, 50)
        delay(90)
        tap(x, y, 50)
    }
}
