package com.kanka.makro

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs

/**
 * Ekranin en son karesini tutar; sadece istenen birkac pikseli okur.
 * Tum ekrani islemedigi icin CPU'yu yormaz.
 */
object ScreenSampler {
    private val lock = Any()
    private var latest: Image? = null

    /** Yakalama cozunurlugu (0.5 = yari boyut, daha az yuk) */
    const val SCALE = 0.5f

    @Volatile
    var running = false

    fun offer(img: Image) {
        synchronized(lock) {
            latest?.close()
            latest = img
        }
    }

    fun clear() {
        synchronized(lock) {
            latest?.close()
            latest = null
        }
    }

    /** Ekran koordinatindaki rengi (3x3 ortalama) 0xRRGGBB olarak dondurur; yoksa -1 */
    fun readPixel(x: Int, y: Int): Int {
        synchronized(lock) {
            val img = latest ?: return -1
            return try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val pixStride = plane.pixelStride
                val cx = (x * SCALE).toInt()
                val cy = (y * SCALE).toInt()
                var rs = 0
                var gs = 0
                var bs = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = (cx + dx).coerceIn(0, img.width - 1)
                        val py = (cy + dy).coerceIn(0, img.height - 1)
                        val i = py * rowStride + px * pixStride
                        if (i + 2 < buf.limit()) {
                            rs += buf.get(i).toInt() and 0xff
                            gs += buf.get(i + 1).toInt() and 0xff
                            bs += buf.get(i + 2).toInt() and 0xff
                            n++
                        }
                    }
                }
                if (n == 0) -1 else ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
            } catch (e: Exception) {
                -1
            }
        }
    }

    /** Arama izgarasi: yakalanan karenin her GRID pikselinden biri (ekranin 1/4'u) */
    const val GRID = 2

    class Frame(val w: Int, val h: Int, val px: IntArray)

    /** Son kareyi izgara cozunurlugunde kopyalar (kilit kisa surer) */
    fun grab(): Frame? {
        synchronized(lock) {
            val img = latest ?: return null
            return try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                val w = img.width / GRID
                val h = img.height / GRID
                val lim = buf.limit()
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    val row = y * GRID * rs
                    val o = y * w
                    for (x in 0 until w) {
                        val i = row + x * GRID * ps
                        if (i + 2 < lim) {
                            out[o + x] = ((buf.get(i).toInt() and 0xff) shl 16) or
                                ((buf.get(i + 1).toInt() and 0xff) shl 8) or
                                (buf.get(i + 2).toInt() and 0xff)
                        }
                    }
                }
                Frame(w, h, out)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Ekran pikseli -> izgara */
    fun toGrid(v: Int) = (v * SCALE / GRID).toInt()

    /** Izgara -> ekran pikseli */
    fun toScreen(g: Float) = g * GRID / SCALE

    /** Karenin bir bolumunu sablon olarak keser (ekran koordinatlari) */
    fun crop(f: Frame, x1: Int, y1: Int, x2: Int, y2: Int): Sablon? {
        val gx1 = toGrid(minOf(x1, x2)).coerceIn(0, f.w - 1)
        val gy1 = toGrid(minOf(y1, y2)).coerceIn(0, f.h - 1)
        val gx2 = toGrid(maxOf(x1, x2)).coerceIn(0, f.w - 1)
        val gy2 = toGrid(maxOf(y1, y2)).coerceIn(0, f.h - 1)
        val w = gx2 - gx1 + 1
        val h = gy2 - gy1 + 1
        if (w < 4 || h < 3) return null
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = f.px[(gy1 + y) * f.w + gx1 + x]
        return Sablon(w, h, px)
    }

    /**
     * Sablonu karede arar. Bulursa sablonun sol-ust izgara konumunu dondurur.
     * tol: kanal basina ortalama izin verilen fark.
     * Hizli: once seyrek noktalarla eler, uyusmayan konumu erken birakir.
     */
    fun find(f: Frame, t: Sablon, tol: Int): IntArray? {
        if (t.w > f.w || t.h > f.h) return null
        // Seyrek ornek noktalar (her 2 pikselden biri)
        val sxl = ArrayList<Int>()
        val syl = ArrayList<Int>()
        var yy = 0
        while (yy < t.h) {
            var xx = 0
            while (xx < t.w) { sxl.add(xx); syl.add(yy); xx += 2 }
            yy += 2
        }
        val n = sxl.size
        val sx = IntArray(n) { sxl[it] }
        val sy = IntArray(n) { syl[it] }
        val sv = IntArray(n) { t.px[sy[it] * t.w + sx[it]] }
        val perPix = tol * 3
        val limit = perPix * n
        // Erken eleme: ara toplam, ortalamanin 2 katini gecerse bu konum olmaz
        val slack = perPix * 8
        val fp = f.px
        val fw = f.w
        var best = limit + 1
        var bx = -1
        var by = -1
        for (y in 0..(f.h - t.h)) {
            for (x in 0..(fw - t.w)) {
                var s = 0
                var k = 0
                while (k < n) {
                    val a = fp[(y + sy[k]) * fw + x + sx[k]]
                    val b = sv[k]
                    s += abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) +
                        abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) +
                        abs((a and 0xff) - (b and 0xff))
                    if (s >= best || s > slack + k * perPix * 2) break
                    k++
                }
                if (k == n && s < best) { best = s; bx = x; by = y }
            }
        }
        return if (bx >= 0) intArrayOf(bx, by) else null
    }

    /** Ekranin bir bolgesini bitmap olarak keser ve buyutur (OCR icin) */
    private class Crop(val w: Int, val h: Int, val px: IntArray)

    private fun copyRegion(x1: Int, y1: Int, x2: Int, y2: Int): Crop? {
        synchronized(lock) {
            val img = latest ?: return null
            return try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                val lim = buf.limit()
                val sx1 = (x1 * SCALE).toInt().coerceIn(0, img.width - 1)
                val sy1 = (y1 * SCALE).toInt().coerceIn(0, img.height - 1)
                val sx2 = (x2 * SCALE).toInt().coerceIn(sx1, img.width - 1)
                val sy2 = (y2 * SCALE).toInt().coerceIn(sy1, img.height - 1)
                val w = sx2 - sx1 + 1
                val h = sy2 - sy1 + 1
                val px = IntArray(w * h)
                for (y in 0 until h) {
                    val row = (sy1 + y) * rs
                    for (x in 0 until w) {
                        val i = row + (sx1 + x) * ps
                        if (i + 2 < lim) {
                            px[y * w + x] = (0xFF shl 24) or
                                ((buf.get(i).toInt() and 0xff) shl 16) or
                                ((buf.get(i + 1).toInt() and 0xff) shl 8) or
                                (buf.get(i + 2).toInt() and 0xff)
                        }
                    }
                }
                Crop(w, h, px)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Ekranin bir bolgesini bitmap olarak keser ve buyutur (OCR icin) */
    fun cropBitmap(x1: Int, y1: Int, x2: Int, y2: Int, up: Int): Bitmap? {
        val c = copyRegion(x1, y1, x2, y2) ?: return null
        return try {
            val b = Bitmap.createBitmap(c.px, c.w, c.h, Bitmap.Config.ARGB_8888)
            if (up > 1) Bitmap.createScaledBitmap(b, c.w * up, c.h * up, true) else b
        } catch (e: Exception) {
            null
        }
    }

    fun diff(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) +
            abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) +
            abs((a and 0xff) - (b and 0xff))
}
