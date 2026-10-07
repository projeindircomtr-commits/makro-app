package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.delay

class KoOyun(
    private val service: AccessibilityService,
    private val sampler: ScreenSampler,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    val pathRecorder = PathRecorder(service)
    val innManager = InnHostessManager(service, screenWidth, screenHeight)

    var isRunning = false
    private var mobKillCount = 0
    var tripsToBankThreshold = 30

    suspend fun runLoop() {
        isRunning = true
        while (isRunning) {
            if (sampler.isDisconnected()) {
                isRunning = false
                break
            }

            checkPlayerVitals()
            targetAndAttack()

            if (sampler.isLootBoxAvailable()) {
                collectLoot()
                mobKillCount++
            }

            if (mobKillCount >= tripsToBankThreshold && pathRecorder.recordedSteps.isNotEmpty()) {
                executeBankRoutine()
                mobKillCount = 0
            }

            delay(200)
        }
    }

    private suspend fun checkPlayerVitals() {
        if (sampler.needsHpPotion()) {
            pressPotion(isHp = true)
        }
        if (sampler.needsMpPotion()) {
            pressPotion(isHp = false)
        }
    }

    private suspend fun targetAndAttack() {
        tap(screenWidth * 0.93f, screenHeight * 0.88f)
        delay(200)
        tap(screenWidth * 0.89f, screenHeight * 0.74f)
        delay(300)
    }

    private suspend fun collectLoot() {
        tap(screenWidth * 0.50f, screenHeight * 0.50f)
        delay(250)
    }

    suspend fun executeBankRoutine() {
        pathRecorder.playForward()
        delay(600)

        innManager.clickOpenButton()
        innManager.clickInnHostesOpenMenu()
        innManager.dumpInventoryToBank(skipFirstSlotsCount = 2)
        innManager.closeBank()
        delay(500)

        pathRecorder.playBackward()
        delay(600)
    }

    private fun pressPotion(isHp: Boolean) {
        val x = if (isHp) screenWidth * 0.78f else screenWidth * 0.83f
        val y = screenHeight * 0.70f
        tap(x, y)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
