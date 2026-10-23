package com.kanka.makro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PacketInterceptVpn — Android VPNService tabanlı paket yakalama ve inject
 *
 * Çalışma prensibi:
 *   1. VPN tüneli kur (tun0 arayüzü)
 *   2. Tüm IP trafiğini tünel üzerinden yönlendir
 *   3. 82.38.10.210:19002 hedefli paketleri yakalayıp analiz et
 *   4. Sunucuya forward et ve cevabı geri dön
 *   5. Bot modunda: istediğimiz zaman ekstra paket inject et
 *
 * Manifest gereksinimi:
 *   <service android:name=".PacketInterceptVpn"
 *            android:permission="android.permission.BIND_VPN_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.net.VpnService" />
 *     </intent-filter>
 *   </service>
 */
class PacketInterceptVpn : VpnService() {

    companion object {
        private const val TAG = "PacketInterceptVpn"
        const val GAME_IP = "82.38.10.210"
        const val GAME_PORT = 19002
        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_ROUTE = "0.0.0.0"
        private const val NOTIF_ID = 9002
        private const val CHAN_ID = "vpn_intercept"

        var instance: PacketInterceptVpn? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    // Game server ile gerçek TCP bağlantısı
    private var serverChannel: SocketChannel? = null

    // Yakalanan client→server paketleri (replay için)
    val capturedToServer = ArrayDeque<ByteArray>()
    var captureEnabled = false

    // Inject kuyruğu — buraya eklenen paketler sunucuya gönderilir
    val injectQueue = ArrayDeque<ByteArray>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotif()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.getAndSet(true)) return START_STICKY
        startVpnTunnel()
        return START_STICKY
    }

    private fun startVpnTunnel() {
        Thread {
            try {
                // VPN arayüzü kur
                val builder = Builder()
                    .setSession("MykoBot Interceptor")
                    .addAddress(VPN_ADDRESS, 24)
                    .addRoute(GAME_IP, 32)   // Sadece oyun sunucusunu yönlendir
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Kendi uygulamamızı bypass et (recursive loop önle)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

                vpnInterface = builder.establish()
                Log.d(TAG, "VPN tüneli kuruldu")

                // Sunucuya gerçek bağlantı kur
                serverChannel = SocketChannel.open()
                protect(serverChannel!!.socket())
                serverChannel!!.connect(InetSocketAddress(InetAddress.getByName(GAME_IP), GAME_PORT))
                serverChannel!!.configureBlocking(false)
                Log.d(TAG, "Server bağlantısı kuruldu")

                // Çift yönlü forwarding döngüsü
                val tun = vpnInterface!!
                val inp = FileInputStream(tun.fileDescriptor)
                val out = FileOutputStream(tun.fileDescriptor)

                val buf = ByteBuffer.allocate(32768)
                val serverBuf = ByteBuffer.allocate(65536)

                while (running.get()) {
                    // TUN → Server (client gönderdi)
                    val n = inp.read(buf.array())
                    if (n > 0) {
                        val ipPacket = buf.array().copyOf(n)
                        val tcpPayload = extractTcpPayload(ipPacket)
                        if (tcpPayload != null) {
                            // Capture
                            if (captureEnabled && isGameProtocol(tcpPayload)) {
                                capturedToServer.addLast(tcpPayload.copyOf())
                                Log.d(TAG, "Client paket yakalandı: ${tcpPayload.size} byte")
                            }
                            // Forward
                            serverChannel!!.write(ByteBuffer.wrap(tcpPayload))
                        }
                    }

                    // Inject queue — ekstra paket gönder
                    while (injectQueue.isNotEmpty()) {
                        val inject = injectQueue.removeFirst()
                        serverChannel!!.write(ByteBuffer.wrap(inject))
                        Log.d(TAG, "Inject paketi gönderildi: ${inject.size} byte")
                    }

                    // Server → TUN (server cevabı, client'a yönlendir)
                    serverBuf.clear()
                    val m = serverChannel!!.read(serverBuf)
                    if (m > 0) {
                        serverBuf.flip()
                        val response = ByteArray(m)
                        serverBuf.get(response)
                        // IP paketi olarak wrap edip tun'a yaz
                        val ipWrapped = wrapInIpPacket(response)
                        out.write(ipWrapped)
                    }

                    Thread.sleep(1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN döngü hatası: ${e.message}")
            } finally {
                cleanup()
            }
        }.start()
    }

    /**
     * IP paketinden TCP payload'ı çıkar
     * Ham IP (no Ethernet header) formatı
     */
    private fun extractTcpPayload(ipPacket: ByteArray): ByteArray? {
        if (ipPacket.size < 20) return null
        val version = (ipPacket[0].toInt() and 0xFF) shr 4
        if (version != 4) return null
        val proto = ipPacket[9].toInt() and 0xFF
        if (proto != 6) return null  // TCP

        val dstIp = ipPacket.copyOfRange(16, 20)
        val targetIp = InetAddress.getByName(GAME_IP).address
        if (!dstIp.contentEquals(targetIp)) return null

        val ihl = (ipPacket[0].toInt() and 0x0F) * 4
        if (ipPacket.size < ihl + 20) return null

        val tcp = ipPacket.copyOfRange(ihl, ipPacket.size)
        val dstPort = ((tcp[2].toInt() and 0xFF) shl 8) or (tcp[3].toInt() and 0xFF)
        if (dstPort != GAME_PORT) return null

        val dataOffset = (tcp[12].toInt() and 0xFF) shr 4
        val tcpHdrLen = dataOffset * 4
        if (tcp.size <= tcpHdrLen) return null

        return tcp.copyOfRange(tcpHdrLen, tcp.size)
    }

    /**
     * TCP payload'ı IP paketine wrap et (TUN'a yazı için)
     * Basitleştirilmiş — gerçek implementasyon checksum hesaplar
     */
    private fun wrapInIpPacket(payload: ByteArray): ByteArray {
        // Minimal IP + TCP header
        val totalLen = 40 + payload.size
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45.toByte()   // IPv4, IHL=5
        pkt[1] = 0x00
        pkt[2] = (totalLen shr 8).toByte()
        pkt[3] = totalLen.toByte()
        pkt[8] = 64               // TTL
        pkt[9] = 6                // TCP

        // Src IP = GAME_IP
        val gameAddr = InetAddress.getByName(GAME_IP).address
        gameAddr.copyInto(pkt, 12)
        // Dst IP = client (10.0.0.1)
        pkt[16] = 10; pkt[17] = 0; pkt[18] = 0; pkt[19] = 1

        // TCP header (minimal)
        pkt[20] = (GAME_PORT shr 8).toByte()
        pkt[21] = GAME_PORT.toByte()
        pkt[22] = 0; pkt[23] = 0  // src port (client port)
        pkt[32] = 0x50             // data offset = 5 (20 byte)
        pkt[33] = 0x18             // ACK + PSH

        payload.copyInto(pkt, 40)
        return pkt
    }

    private fun isGameProtocol(data: ByteArray): Boolean {
        return data.size >= 6 &&
            data[0] == 0xAA.toByte() &&
            data[1] == 0x55.toByte() &&
            data[data.size - 2] == 0x55.toByte() &&
            data[data.size - 1] == 0xAA.toByte()
    }

    // ============================================================
    // Public API — Bot tarafından kullanılır
    // ============================================================

    /** Yakalamayı başlat */
    fun enableCapture() { captureEnabled = true }
    fun disableCapture() { captureEnabled = false }

    /** Paket inject et (sunucuya gönder) */
    fun injectPacket(packet: ByteArray) {
        injectQueue.addLast(packet.copyOf())
    }

    /** Capture'dan alınan paketi tekrar gönder */
    fun replayPacket(index: Int) {
        if (index < capturedToServer.size) {
            injectQueue.addLast(capturedToServer[index].copyOf())
            Log.d(TAG, "Replay: paket $index gönderildi")
        }
    }

    private fun cleanup() {
        running.set(false)
        try { serverChannel?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        instance = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHAN_ID, "Packet Interceptor", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, CHAN_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PacketBot Aktif")
            .setContentText("Oyun trafiği intercept ediliyor")
            .build()
        startForeground(NOTIF_ID, notif)
    }
}
