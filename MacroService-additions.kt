// MacroService.kt içine ekle:

// 1. onServiceConnected() veya ilk başlatma noktasında:
val botController = BotController(this)

// 2. Overlay butonu veya bildirim aksiyonu ile:
// HP Pot kaydet: botController.startCapturing() → oyunda HP pot kullan → botController.labelLastCaptured("hp")
// MP Pot kaydet: botController.labelLastCaptured("mp")
// Attack kaydet: botController.labelLastCaptured("attack")

// 3. Bot başlat/durdur:
// botController.startVpnInterceptor()
// botController.startAutoBot()
// botController.stopAutoBot()
