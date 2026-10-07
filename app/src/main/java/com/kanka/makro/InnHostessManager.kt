package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay

class InnHostessManager(
    private val service: AccessibilityService,
    private val res: ResolutionManager
) {
    suspend fun clickOpenButton() {
        val pt = res.getOpenButtonPoint()
        tap(pt.x, pt.y)
        delay(700)
    }

    suspend fun clickInnHostesOpenMenu() {
        val pt = res.getInnHostesOpenMenuPoint()
        tap(pt.x, pt.y)
        delay(1200)
    }

    suspend fun dumpInventoryToBank(skipFirstSlotsCount: Int = 2) {
        var currentSlot = 0
        for (row in 0 until 4) {
            for (col in 0 until 7) {
                if (currentSlot >= skipFirstSlotsCount) {
                    val pt = res.getInventorySlotPoint(row, col)
                    doubleTap(pt.x, pt.y)
                    delay(140)
                }
                currentSlot++
            }
        }
    }

    suspend fun closeBank() {
        val pt = res.getBankClosePoint()
        tap(pt.x, pt.y)
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
