package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.*

class OtoBankaKopru(
    private val service: AccessibilityService,
    val screenWidth: Int,
    val screenHeight: Int
) {
    val res = ResolutionManager(screenWidth, screenHeight)
    val pathRecorder = PathRecorder(service)
    val innManager = InnHostessManager(service, res)
    private val scope = CoroutineScope(Dispatchers.Default)

    var kontrolAraligiDakika: Int = 5
    private var sonKontrolZamani: Long = System.currentTimeMillis()

    fun kontrolVaktiGeldiMi(): Boolean {
        val farkDakika = (System.currentTimeMillis() - sonKontrolZamani) / (1000 * 60)
        return farkDakika >= kontrolAraligiDakika
    }

    fun toggleInventory() {
        val pt = res.getInventoryButtonPoint()
        tap(pt.x, pt.y)
    }

    fun rutiniBaslat(
        cantaDoluMuKontrol: () -> Boolean,
        onAtakDurdur: () -> Unit,
        onAtakBaslat: () -> Unit
    ) {
        scope.launch {
            onAtakDurdur()
            delay(600)

            toggleInventory()
            delay(800)

            val dolu = cantaDoluMuKontrol()

            toggleInventory()
            delay(500)

            if (dolu) {
                bankayaGitVeBosalt {
                    sonKontrolZamani = System.currentTimeMillis()
                    onAtakBaslat()
                }
            } else {
                sonKontrolZamani = System.currentTimeMillis()
                onAtakBaslat()
            }
        }
    }

    fun toggleKayit(onDurumDegisti: (kaydediyorMu: Boolean) -> Unit) {
        if (!pathRecorder.isRecording) {
            pathRecorder.startRecording()
            onDurumDegisti(true)
        } else {
            pathRecorder.stopRecording()
            onDurumDegisti(false)
        }
    }

    fun bankayaGitVeBosalt(onTamamlandi: () -> Unit = {}) {
        scope.launch {
            if (pathRecorder.recordedSteps.isNotEmpty()) {
                pathRecorder.playForward()
                delay(600)
            }

            innManager.clickOpenButton()
            innManager.clickInnHostesOpenMenu()
            innManager.dumpInventoryToBank(skipFirstSlotsCount = 2)
            innManager.closeBank()
            delay(500)

            if (pathRecorder.recordedSteps.isNotEmpty()) {
                pathRecorder.playBackward()
                delay(600)
            }

            onTamamlandi()
        }
    }

    private fun tap(x: Float, y: Float, duration: Long = 60) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
