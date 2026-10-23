package com.kanka.makro

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BotController — MacroService içinden çağrılan ana bot koordinatörü
 *
 * Kullanım (MacroService'den):
 *   val bot = BotController(this)
 *   bot.startCapturing()   // Oyunda HP pot kullan → paket kaydedilir
 *   bot.labelNext("hp")    // Son yakalanan paketi HP pot olarak işaretle
 *   bot.startAutoBot()     // Oto döngüsünü başlat
 */
class BotController(private val service: MacroService) {

    companion object {
        private const val TAG = "BotController"
        var instance: BotController? = null
    }

    // VPN interceptor referansı
    private val vpn get() = PacketInterceptVpn.instance

    // Bot durumu
    var hpPotPacket: ByteArray? = null
    var mpPotPacket: ByteArray? = null
    var attackPacket: ByteArray? = null
    val skillPackets = mutableListOf<ByteArray>()

    private val autoBotRunning = AtomicBoolean(false)
    private var autoBotThread: Thread? = null

    // Ayarlar
    var attackIntervalMs: Long = 800
    var hpPotIntervalMs: Long = 3000
    var mpPotIntervalMs: Long = 5000
    var skillIntervalMs: Long = 8000
    var hpThreshold: Int = 50   // % altında pot kullan
    var mpThreshold: Int = 30

    init {
        instance = this
    }

    // ============================================================
    // VPN Servisi Kontrolü
    // ============================================================

    fun startVpnInterceptor() {
        Log.d(TAG, "VPN interceptor başlatılıyor")
        val intent = android.content.Intent(service, PacketInterceptVpn::class.java)
        service.startService(intent)
    }

    fun stopVpnInterceptor() {
        service.stopService(android.content.Intent(service, PacketInterceptVpn::class.java))
    }

    // ============================================================
    // Capture Sistemi
    // ============================================================

    /**
     * Capture modunu başlat.
     * Bundan sonra oyunda yaptığın her aksiyon (pot, saldırı) paket olarak kaydedilir.
     */
    fun startCapturing() {
        vpn?.enableCapture() ?: Log.w(TAG, "VPN servisi aktif değil! startVpnInterceptor() çağır")
        Log.d(TAG, "Capture başladı — şimdi oyunda HP pot, MP pot, saldırı yap")
    }

    fun stopCapturing() {
        vpn?.disableCapture()
        Log.d(TAG, "Capture durduruldu, ${vpn?.capturedToServer?.size ?: 0} paket kaydedildi")
    }

    /**
     * Son yakalanan paketi belirli bir aksiyon olarak etiketle
     */
    fun labelLastCaptured(type: String) {
        val packets = vpn?.capturedToServer ?: run {
            Log.w(TAG, "VPN servisi yok"); return
        }
        if (packets.isEmpty()) {
            Log.w(TAG, "Capture'da hiç paket yok"); return
        }
        val pkt = packets.last()
        when (type.lowercase()) {
            "hp"     -> { hpPotPacket = pkt; Log.d(TAG, "HP Pot kaydedildi (${pkt.size} byte)") }
            "mp"     -> { mpPotPacket = pkt; Log.d(TAG, "MP Pot kaydedildi (${pkt.size} byte)") }
            "attack", "atk" -> { attackPacket = pkt; Log.d(TAG, "Attack kaydedildi (${pkt.size} byte)") }
            "skill"  -> { skillPackets.add(pkt); Log.d(TAG, "Skill${skillPackets.size} kaydedildi") }
        }
    }

    /**
     * Index ile capture listesinden pakeeti al
     */
    fun labelCapturedAt(index: Int, type: String) {
        val packets = vpn?.capturedToServer ?: return
        if (index >= packets.size) { Log.w(TAG, "Index $index yok"); return }
        val pkt = packets[index]
        when (type.lowercase()) {
            "hp"     -> hpPotPacket = pkt
            "mp"     -> mpPotPacket = pkt
            "attack", "atk" -> attackPacket = pkt
            "skill"  -> skillPackets.add(pkt)
        }
        Log.d(TAG, "Paket[$index] → $type olarak etiketlendi")
    }

    // ============================================================
    // Manuel Gönderme
    // ============================================================

    fun sendHpPot() {
        hpPotPacket?.let {
            vpn?.injectPacket(it)
            Log.d(TAG, "HP Pot gönderildi")
        } ?: Log.w(TAG, "HP Pot paketi kayıtlı değil")
    }

    fun sendMpPot() {
        mpPotPacket?.let {
            vpn?.injectPacket(it)
            Log.d(TAG, "MP Pot gönderildi")
        } ?: Log.w(TAG, "MP Pot paketi kayıtlı değil")
    }

    fun sendAttack() {
        attackPacket?.let {
            vpn?.injectPacket(it)
            Log.d(TAG, "Attack gönderildi")
        } ?: Log.w(TAG, "Attack paketi kayıtlı değil")
    }

    fun sendSkill(index: Int = 0) {
        if (skillPackets.size > index) {
            vpn?.injectPacket(skillPackets[index])
            Log.d(TAG, "Skill[$index] gönderildi")
        } else Log.w(TAG, "Skill[$index] kayıtlı değil")
    }

    // ============================================================
    // Oto Bot
    // ============================================================

    fun startAutoBot() {
        if (autoBotRunning.getAndSet(true)) return
        Log.d(TAG, "AutoBot başlatıldı")

        autoBotThread = Thread {
            var lastAttack = 0L
            var lastHp = 0L
            var lastMp = 0L
            var lastSkill = 0L

            while (autoBotRunning.get()) {
                val now = System.currentTimeMillis()

                if (attackPacket != null && now - lastAttack >= attackIntervalMs) {
                    sendAttack()
                    lastAttack = now
                }

                if (hpPotPacket != null && now - lastHp >= hpPotIntervalMs) {
                    sendHpPot()
                    lastHp = now
                }

                if (mpPotPacket != null && now - lastMp >= mpPotIntervalMs) {
                    sendMpPot()
                    lastMp = now
                }

                if (skillPackets.isNotEmpty() && now - lastSkill >= skillIntervalMs) {
                    sendSkill(0)
                    lastSkill = now
                }

                Thread.sleep(50)
            }
        }
        autoBotThread?.isDaemon = true
        autoBotThread?.start()
    }

    fun stopAutoBot() {
        autoBotRunning.set(false)
        autoBotThread?.join(500)
        Log.d(TAG, "AutoBot durduruldu")
    }

    fun isRunning() = autoBotRunning.get()

    // ============================================================
    // Durum
    // ============================================================

    fun getStatus(): String = buildString {
        appendLine("=== BotController Durumu ===")
        appendLine("VPN Aktif: ${vpn != null}")
        appendLine("AutoBot: ${autoBotRunning.get()}")
        appendLine("HP Pot: ${if (hpPotPacket != null) "✓ (${hpPotPacket!!.size}b)" else "✗"}")
        appendLine("MP Pot: ${if (mpPotPacket != null) "✓ (${mpPotPacket!!.size}b)" else "✗"}")
        appendLine("Attack: ${if (attackPacket != null) "✓ (${attackPacket!!.size}b)" else "✗"}")
        appendLine("Skill Sayısı: ${skillPackets.size}")
        appendLine("Capture Buffer: ${vpn?.capturedToServer?.size ?: 0} paket")
    }

    fun destroy() {
        stopAutoBot()
        stopVpnInterceptor()
        instance = null
    }
}
