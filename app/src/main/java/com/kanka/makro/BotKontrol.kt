package com.kanka.makro

import kotlinx.coroutines.*

class BotKontrol(private val koOyun: KoOyun) {
    private var job: Job? = null
    val isRunning: Boolean
        get() = koOyun.isRunning

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.Default).launch {
            koOyun.runLoop()
        }
    }

    fun stop() {
        koOyun.isRunning = false
        job?.cancel()
        job = null
    }

    fun startPathRecording() {
        koOyun.pathRecorder.startRecording()
    }

    fun stopPathRecording() {
        koOyun.pathRecorder.stopRecording()
    }

    fun triggerManualBankTrip() {
        CoroutineScope(Dispatchers.Default).launch {
            koOyun.executeBankRoutine()
        }
    }
}
