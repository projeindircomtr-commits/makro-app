package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OtoBankaKopru(
    private val service: AccessibilityService,
    screenWidth: Int,
    screenHeight: Int
) {
    val pathRecorder = PathRecorder(service)
    val innManager = InnHostessManager(service, screenWidth, screenHeight)
    private val scope = CoroutineScope(Dispatchers.Default)

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
            // 1. İzi ileri takip et (Slottan Inn Hostess'e varış)
            if (pathRecorder.recordedSteps.isNotEmpty()) {
                pathRecorder.playForward()
                delay(600)
            }

            // 2. NPC Open ve Inn Hostes Open tıkla
            innManager.clickOpenButton()
            innManager.clickInnHostesOpenMenu()

            // 3. Çantayı bankaya çift tıklamalarla aktar
            innManager.dumpInventoryToBank(skipFirstSlotsCount = 2)

            // 4. Bankayı kapat
            innManager.closeBank()
            delay(500)

            // 5. İzi tersine takip et (Bankadan slota dönüş)
            if (pathRecorder.recordedSteps.isNotEmpty()) {
                pathRecorder.playBackward()
                delay(600)
            }

            onTamamlandi()
        }
    }
}
