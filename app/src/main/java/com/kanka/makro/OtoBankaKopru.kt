package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.*

class OtoBankaKopru(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    val pathRecorder = PathRecorder(service)
    val innManager = InnHostessManager(service, screenWidth, screenHeight)
    private val scope = CoroutineScope(Dispatchers.Default)

    var kontrolAraligiDakika: Int = 5
    private var sonKontrolZamani: Long = System.currentTimeMillis()

    // 1. Süre doldu mu kontrolü
    fun kontrolVaktiGeldiMi(): Boolean {
        val simdi = System.currentTimeMillis()
        val farkDakika = (simdi - sonKontrolZamani) / (1000 * 60)
        return farkDakika >= kontrolAraligiDakika
    }

    // 2. Alt bardaki Inventory butonuna tıkla
    fun toggleInventory() {
        val invX = screenWidth * 0.735f
        val invY = screenHeight * 0.962f
        tap(invX, invY)
    }

    // 3. Çanta doluluk ve banka rutini
    fun rutiniBaslat(
        cantaDoluMuKontrol: () -> Boolean,
        onAtakDurdur: () -> Unit,
        onAtakBaslat: () -> Unit
    ) {
        scope.launch {
            // Atak durduruluyor
            onAtakDurdur()
            delay(600)

            // Envanteri aç
            toggleInventory()
            delay(800)

            // Çantanın doluluğu test ediliyor
            val dolu = cantaDoluMuKontrol()

            // Envanteri kapat
            toggleInventory()
            delay(500)

            if (dolu) {
                // Bankaya git, boşalt, geri dön
                bankayaGitVeBosalt {
                    sonKontrolZamani = System.currentTimeMillis()
                    onAtakBaslat()
                }
            } else {
                // Boşsa hemen atağa dön
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
