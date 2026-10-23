package com.kanka.makro

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PacketBot — TCP packet interceptor + replay engine
 *
 * Protokol analizi:
 *   aa 55 [uint16_LE body_len] [uint16_LE opcode=0x79e0] [uint32_LE nonce] [uint32_LE enc_len] [enc_data] 55 aa
 *
 * Kullanım:
 *   1. start() → VPN tünelini başlat, oyun trafiğini kapat
 *   2. onGamePacketCaptured() → ilk gerçek paketleri depola
 *   3. replaySavedPacket() → kaydedilmiş paketi tekrar gönder
 */
class PacketBot(private val service: MacroService) {

    companion object {
        private const val TAG = "PacketBot"
        const val GAME_IP = "82.38.10.210"
        const val GAME_PORT = 19002

        // Paket magic bytes
        val MAGIC_START = byteArrayOf(0xAA.toByte(), 0x55.toByte())
        val MAGIC_END   = byteArrayOf(0x55.toByte(), 0xAA.toByte())

        // Opcode'lar
        const val OP_HANDSHAKE: Short = 0x01FE.toShort()
        const val OP_GAME: Short      = 0x79E0.toShort()

        // Singleton
        var instance: PacketBot? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    // Yakalanan paketler (HP pot, MP pot, attack gibi)
    private val capturedPackets = ArrayBlockingQueue<ByteArray>(64)
    private var captureMode = AtomicBoolean(false)
    private var captureRemaining = 0

    // Game TCP socket
    private var gameSocket: Socket? = null
    private var gameOutputStream: java.io.OutputStream? = null

    // Paket türleri (capture sonrası etiketlenir)
    var hpPotPacket: ByteArray? = null
    var mpPotPacket: ByteArray? = null
    var attackPacket: ByteArray? = null
    var skillPackets: MutableList<ByteArray> = mutableListOf()

    init {
        instance = this
    }

    // ============================================================
    // Bağlantı
    // ============================================================

    /**
     * Oyun sunucusuna direkt TCP bağlantısı kur.
     * Bu yaklaşımda yeni bir socket açıyoruz, mevcut oturumu REPLAY ediyoruz.
     *
     * NOT: Yeni socket yeni session ister — replay için mevcut oturumun
     * outputstream'ini hook'lamamız lazım. Bunun için interceptGameSocket() kullan.
     */
    fun connectDirect() {
        Thread {
            try {
                Log.d(TAG, "Oyun sunucusuna bağlanılıyor: $GAME_IP:$GAME_PORT")
                val sock = Socket()
                sock.connect(InetSocketAddress(InetAddress.getByName(GAME_IP), GAME_PORT), 5000)
                sock.soTimeout = 30000
                gameSocket = sock
                gameOutputStream = sock.getOutputStream()
                Log.d(TAG, "Bağlantı başarılı")

                // Handshake gönder
                sendHandshake()

                // Server cevabını dinle
                listenServerLoop(sock)
            } catch (e: Exception) {
                Log.e(TAG, "Bağlantı hatası: ${e.message}")
            }
        }.start()
    }

    private fun sendHandshake() {
        // aa 55 06 00 fe 01 00 00 00 00 55 aa
        val handshake = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(),
            0x06.toByte(), 0x00.toByte(),  // body_len = 6
            0xFE.toByte(), 0x01.toByte(),  // opcode = 0x01FE (LE)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),  // inner = 0
            0x55.toByte(), 0xAA.toByte()
        )
        sendRaw(handshake)
        Log.d(TAG, "Handshake gönderildi")
    }

    private fun listenServerLoop(sock: Socket) {
        val inp = sock.getInputStream()
        val buf = ByteArray(65536)
        try {
            while (running.get() && !sock.isClosed) {
                val n = inp.read(buf)
                if (n <= 0) break
                val pkt = buf.copyOf(n)
                onServerPacket(pkt)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Server dinleme bitti: ${e.message}")
        }
    }

    private fun onServerPacket(data: ByteArray) {
        if (data.size < 6) return
        if (data[0] != 0xAA.toByte() || data[1] != 0x55.toByte()) return
        val opcode = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        Log.d(TAG, "Server paket: opcode=0x${opcode.toString(16)} size=${data.size}")
    }

    // ============================================================
    // Paket gönderme
    // ============================================================

    /**
     * Ham byte array'i direkt gönder
     */
    fun sendRaw(packet: ByteArray) {
        try {
            gameOutputStream?.write(packet)
            gameOutputStream?.flush()
            Log.d(TAG, "Paket gönderildi: ${packet.size} byte")
        } catch (e: Exception) {
            Log.e(TAG, "Gönderme hatası: ${e.message}")
        }
    }

    /**
     * Kaydedilmiş HP pot paketini gönder
     */
    fun sendHpPot() {
        hpPotPacket?.let {
            Log.d(TAG, "HP Pot gönderiliyor")
            sendRaw(it)
        } ?: Log.w(TAG, "HP Pot paketi henüz kaydedilmedi")
    }

    /**
     * Kaydedilmiş MP pot paketini gönder
     */
    fun sendMpPot() {
        mpPotPacket?.let {
            Log.d(TAG, "MP Pot gönderiliyor")
            sendRaw(it)
        } ?: Log.w(TAG, "MP Pot paketi henüz kaydedilmedi")
    }

    /**
     * Kaydedilmiş saldırı paketini gönder
     */
    fun sendAttack() {
        attackPacket?.let {
            Log.d(TAG, "Attack gönderiliyor")
            sendRaw(it)
        } ?: Log.w(TAG, "Attack paketi henüz kaydedilmedi")
    }

    /**
     * Kaydedilmiş skill paketini gönder (index ile)
     */
    fun sendSkill(index: Int = 0) {
        if (skillPackets.size > index) {
            Log.d(TAG, "Skill[$index] gönderiliyor")
            sendRaw(skillPackets[index])
        } else {
            Log.w(TAG, "Skill[$index] henüz kaydedilmedi")
        }
    }

    // ============================================================
    // Packet Capture Mode
    // ============================================================

    /**
     * Capture modunu başlat — sonraki N paketi kaydet.
     * Kullanıcı oyunda HP pot kullandığında packet otomatik kaydedilir.
     */
    fun startCapture(count: Int = 5) {
        captureMode.set(true)
        captureRemaining = count
        capturedPackets.clear()
        Log.d(TAG, "Capture modu başladı, $count paket bekleniyor")
    }

    /**
     * Yakalanan paketi etiketle
     */
    fun labelCaptured(type: String) {
        val pkt = capturedPackets.poll() ?: return
        when (type.lowercase()) {
            "hp"  -> { hpPotPacket = pkt; Log.d(TAG, "HP Pot paketi kaydedildi") }
            "mp"  -> { mpPotPacket = pkt; Log.d(TAG, "MP Pot paketi kaydedildi") }
            "atk" -> { attackPacket = pkt; Log.d(TAG, "Attack paketi kaydedildi") }
            "skill" -> { skillPackets.add(pkt); Log.d(TAG, "Skill paketi kaydedildi: ${skillPackets.size}") }
        }
    }

    /**
     * Capture'a yeni paket ekle (OutputStreamHook'tan çağrılır)
     */
    internal fun onClientPacketSent(rawBytes: ByteArray) {
        if (!captureMode.get()) return
        if (captureRemaining <= 0) return
        if (rawBytes.size < 12) return
        if (rawBytes[0] != 0xAA.toByte() || rawBytes[1] != 0x55.toByte()) return

        capturedPackets.offer(rawBytes.copyOf())
        captureRemaining--
        Log.d(TAG, "Paket yakalandı! Kalan: $captureRemaining (${rawBytes.size} byte)")

        if (captureRemaining <= 0) {
            captureMode.set(false)
            Log.d(TAG, "Capture tamamlandı, ${capturedPackets.size} paket hazır")
        }
    }

    // ============================================================
    // Oto Bot Döngüsü
    // ============================================================

    private var botRunning = AtomicBoolean(false)
    private var botThread: Thread? = null

    /**
     * Otomatik saldırı + pot döngüsü başlat
     */
    fun startAutoBot(
        attackIntervalMs: Long = 1000,
        hpCheckIntervalMs: Long = 3000,
        mpCheckIntervalMs: Long = 5000,
        skillIntervalMs: Long = 8000
    ) {
        if (botRunning.getAndSet(true)) return
        Log.d(TAG, "AutoBot başlatıldı")

        botThread = Thread {
            var lastAttack = 0L
            var lastHp = 0L
            var lastMp = 0L
            var lastSkill = 0L

            while (botRunning.get()) {
                val now = System.currentTimeMillis()

                if (attackPacket != null && now - lastAttack >= attackIntervalMs) {
                    sendAttack()
                    lastAttack = now
                }

                if (hpPotPacket != null && now - lastHp >= hpCheckIntervalMs) {
                    sendHpPot()
                    lastHp = now
                }

                if (mpPotPacket != null && now - lastMp >= mpCheckIntervalMs) {
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
        botThread?.start()
    }

    fun stopAutoBot() {
        botRunning.set(false)
        botThread?.join(1000)
        Log.d(TAG, "AutoBot durduruldu")
    }

    // ============================================================
    // Socket Interceptor (refleksiyon ile mevcut bağlantıyı hook'la)
    // ============================================================

    /**
     * Oyun uygulamasının mevcut TCP socket'ını bul ve output stream'ini wrap et.
     * Bu, hem capture hem de inject için kullanılır.
     *
     * NOT: Bu yaklaşım aynı process içinde çalışmak gerektirir.
     * Android'de farklı process'lerin socket'larına erişilemez.
     * Bu nedenle tercih edilen yol: VPNService tabanlı intercept.
     */
    @Deprecated("Aynı process gerektirir. VPNService yaklaşımını kullan.")
    fun interceptGameSocket() {
        // Oyun process'i farklıysa bu çalışmaz
        // Accessibility service oyun ile aynı process değil
        Log.w(TAG, "interceptGameSocket: Farklı process, çalışmaz")
    }

    // ============================================================
    // VPNService tabanlı interceptor (tam implementasyon ayrı sınıfta)
    // ============================================================

    /**
     * PacketInterceptVpn servisini başlat.
     * Bu servis 82.38.10.210:19002 trafiğini intercept eder.
     */
    fun startVpnInterceptor() {
        val intent = Intent(service, PacketInterceptVpn::class.java)
        service.startService(intent)
        Log.d(TAG, "VPN interceptor başlatıldı")
    }

    fun stopVpnInterceptor() {
        service.stopService(Intent(service, PacketInterceptVpn::class.java))
    }

    // ============================================================
    // Temizlik
    // ============================================================

    fun destroy() {
        stopAutoBot()
        running.set(false)
        try { gameSocket?.close() } catch (e: Exception) {}
        instance = null
    }
}
