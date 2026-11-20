package com.kanka.makro

// net-konum-yama4: Rootless VPN ile oyun trafiğinden koordinat çıkaran singleton.
// MacroService'e netKonumGuncelle() üzerinden koordinat iletir.

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetKonum {
    private const val OYUN_IP   = "82.38.10.210"
    private const val OYUN_PORT = 19002
    // 0x001e paketi: sunucu→telefon, düz metin entity konum güncelleme
    private const val PKT_KONUM = 0x001e

    @Volatile var aktif = false
        private set

    private val OYUN_IP_BYTES: ByteArray = run {
        OYUN_IP.split(".").map { it.toInt().toByte() }.toByteArray()
    }

    fun baslatFd(fd: ParcelFileDescriptor) {
        aktif = true
        Thread({ okumaDongusu(fd) }, "netkonum-reader").start()
    }

    fun durdur() {
        aktif = false
    }

    // VPN tünelinden gelen ham IP paketlerini oku
    private fun okumaDongusu(fd: ParcelFileDescriptor) {
        try {
            val fis = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32768)
            while (aktif) {
                val n = try { fis.read(buf) } catch (e: Exception) { break }
                if (n <= 0) continue
                isleIpPaket(buf, n)
            }
        } finally {
            aktif = false
            try { fd.close() } catch (_: Exception) {}
        }
    }

    // Ham IPv4 paketi — sadece TCP, sadece OYUN_IP:OYUN_PORT kaynağından
    private fun isleIpPaket(buf: ByteArray, n: Int) {
        if (n < 20) return
        val verLen = buf[0].toInt() and 0xFF
        val version = verLen shr 4
        if (version != 4) return
        val ipHdrLen = (verLen and 0x0F) * 4
        if (n < ipHdrLen + 20) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 6) return  // TCP değil
        // Kaynak IP kontrolü (offset 12)
        for (i in 0..3) {
            if (buf[12 + i] != OYUN_IP_BYTES[i]) return
        }
        // Kaynak port kontrolü (TCP header offset ipHdrLen, 2 byte big-endian)
        val srcPort = ((buf[ipHdrLen].toInt() and 0xFF) shl 8) or (buf[ipHdrLen + 1].toInt() and 0xFF)
        if (srcPort != OYUN_PORT) return
        // TCP data offset
        val tcpHdrLen = ((buf[ipHdrLen + 12].toInt() and 0xFF) shr 4) * 4
        val payloadOff = ipHdrLen + tcpHdrLen
        val payloadLen = n - payloadOff
        if (payloadLen <= 0) return
        parseOyunStream(buf, payloadOff, payloadLen)
    }

    // Oyun protokolü stream reassembly tamponu
    private val parseBuf = ByteArray(65536)
    private var parseBufPos = 0

    @Synchronized
    private fun parseOyunStream(arr: ByteArray, off: Int, len: Int) {
        // Tampon taşmasını önle
        if (parseBufPos + len > parseBuf.size) parseBufPos = 0
        System.arraycopy(arr, off, parseBuf, parseBufPos, len)
        parseBufPos += len

        var i = 0
        while (i < parseBufPos - 7) {
            // Magic: AA 55
            if (parseBuf[i].toInt() and 0xFF != 0xAA || parseBuf[i + 1].toInt() and 0xFF != 0x55) {
                i++
                continue
            }
            // len: 2 byte little-endian
            val pktLen = ((parseBuf[i + 2].toInt() and 0xFF)) or
                         ((parseBuf[i + 3].toInt() and 0xFF) shl 8)
            val totalLen = 4 + pktLen + 2  // magic(2) + len(2) + data(pktLen) + trailer(2)
            if (i + totalLen > parseBufPos) break  // henüz tam paket yok
            // type: 2 byte little-endian (data[0..1])
            val pktType = ((parseBuf[i + 4].toInt() and 0xFF)) or
                          ((parseBuf[i + 5].toInt() and 0xFF) shl 8)
            if (pktType == PKT_KONUM && pktLen >= 17) {
                // 0x001e yapısı: id(4) + flag(1) + x_float(4) + 0(4) + z_float(4) + ...
                val bb = ByteBuffer.wrap(parseBuf, i + 4 + 2 + 5, 12).order(ByteOrder.LITTLE_ENDIAN)
                val xf = bb.float
                bb.getFloat()  // y (her zaman 0)
                val zf = bb.float
                MacroService.netKonumGuncelle(xf.toInt(), zf.toInt())
            }
            i += totalLen
        }
        // Kalan işlenmemiş veriyi tampona taşı
        val remaining = parseBufPos - i
        if (remaining > 0 && i > 0) {
            System.arraycopy(parseBuf, i, parseBuf, 0, remaining)
        }
        parseBufPos = if (i > 0) remaining else parseBufPos
    }
}
