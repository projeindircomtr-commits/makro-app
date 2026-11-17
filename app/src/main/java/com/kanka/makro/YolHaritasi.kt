package com.kanka.makro

import android.content.Context
import java.io.File
import java.util.PriorityQueue

/**
 * Öğrenen yol haritası (oyun koordinatı = 1 hücre).
 *  - Karakterin geçtiği her nokta "yürünebilir" olarak öğrenilir.
 *  - Takıldığı noktanın önü "duvar" olarak öğrenilir (sonradan oradan geçilirse duvar silinir).
 *  - Yol: A* ile; bilinen yol ucuz, bilinmeyen pahalı, duvar geçilmez.
 *  - Inn Hostes koordinatı: Open görülen ilk yerde kendiliğinden kaydedilir.
 * Her oyunun kendi haritası var (MykoMobile / RevengerOnline / Nowa birbirini bozmaz).
 */
object YolHaritasi {
    private const val YOL: Byte = 1
    private const val DUVAR: Byte = 2
    private const val BILINMEYEN_MALIYET = 4.0
    private const val PAY = 40                 // arama kutusu: başlangıç/hedefin etrafında ek alan
    private const val AZAMI_DUGUM = 150_000

    private val hucre = HashMap<Long, Byte>()
    private var oyun = ""
    private var degisti = 0
    var inn: IntArray? = null
        private set

    private fun k(x: Int, y: Int): Long = x.toLong() * 100_000L + y

    private fun dosya(ctx: Context) = File(ctx.filesDir, "yolharita_${Config.oyun(ctx)}.txt")

    @Synchronized
    fun yukle(ctx: Context) {
        val o = Config.oyun(ctx)
        if (o == oyun) return
        hucre.clear(); inn = null; degisti = 0; oyun = o
        val f = dosya(ctx)
        if (!f.exists()) return
        try {
            f.forEachLine { s ->
                val a = s.split(",")
                if (a.size == 3) {
                    val x = a[0].toIntOrNull() ?: return@forEachLine
                    val y = a[1].toIntOrNull() ?: return@forEachLine
                    when (a[2]) {
                        "y" -> hucre[k(x, y)] = YOL
                        "d" -> hucre[k(x, y)] = DUVAR
                        "i" -> inn = intArrayOf(x, y)
                    }
                }
            }
        } catch (e: Exception) {
            TestLog.olay("HARITA_HATA", "harita okunamadı", e.toString())
        }
        TestLog.olay("HARITA_YUKLENDI", "öğrenilmiş harita", "hucre=${hucre.size} inn=${inn?.joinToString(",") ?: "yok"}")
    }

    @Synchronized
    fun kaydet(ctx: Context) {
        if (degisti == 0) return
        try {
            val sb = StringBuilder()
            for ((kk, v) in hucre) {
                sb.append(kk / 100_000L).append(',').append(kk % 100_000L).append(',')
                    .append(if (v == YOL) 'y' else 'd').append('\n')
            }
            inn?.let { sb.append(it[0]).append(',').append(it[1]).append(",i\n") }
            dosya(ctx).writeText(sb.toString())
            degisti = 0
        } catch (e: Exception) {
            TestLog.olay("HARITA_HATA", "harita yazılamadı", e.toString())
        }
    }

    /** İki ardışık okunan konum arası yürünebilir (Town gibi ışınlanmalar atlanır). */
    @Synchronized
    fun yurundu(x0: Int, y0: Int, x1: Int, y1: Int) {
        val n = maxOf(Math.abs(x1 - x0), Math.abs(y1 - y0))
        if (n > 6) { yolYap(x1, y1); return }
        for (i in 0..n) {
            val x = if (n == 0) x1 else x0 + (x1 - x0) * i / n
            val y = if (n == 0) y1 else y0 + (y1 - y0) * i / n
            yolYap(x, y)
        }
    }

    private fun yolYap(x: Int, y: Int) {
        if (hucre.put(k(x, y), YOL) != YOL) degisti++
    }

    /** Takılma: karakterin bakış yönünde 1-2 birim ötesi duvar (önceden yürünmüş hücreler duvar yapılmaz). */
    @Synchronized
    fun takildi(x: Int, y: Int, yon: Double) {
        for (m in 1..2) {
            val dx = Math.round(Math.cos(yon) * m).toInt()
            val dy = Math.round(Math.sin(yon) * m).toInt()
            val kk = k(x + dx, y + dy)
            if (hucre[kk] == null) { hucre[kk] = DUVAR; degisti++ }
        }
    }

    @Synchronized
    fun innKaydet(x: Int, y: Int) {
        val i = inn
        if (i != null && Math.hypot((i[0] - x).toDouble(), (i[1] - y).toDouble()) < 3.0) return
        inn = intArrayOf(x, y)
        degisti++
        TestLog.olay("HARITA_INN", "Inn Hostes konumu öğrenildi", "$x,$y")
    }

    @Synchronized
    fun hucreSayisi(): Int = hucre.size

    /** A* yolu (başlangıç dahil, hedef dahil). Bulunamazsa null. */
    @Synchronized
    fun yolBul(sx: Int, sy: Int, hx: Int, hy: Int): List<IntArray>? =
        // Önce doğru (bilinen yolları tercih eden) arama; uzak ve bilinmeyen arazide sığmazsa hızlı arama
        ara(sx, sy, hx, hy, 1.0, 60_000) ?: ara(sx, sy, hx, hy, BILINMEYEN_MALIYET, AZAMI_DUGUM)

    private fun ara(sx: Int, sy: Int, hx: Int, hy: Int, agirlik: Double, azami: Int): List<IntArray>? {
        val minX = minOf(sx, hx) - PAY; val maxX = maxOf(sx, hx) + PAY
        val minY = minOf(sy, hy) - PAY; val maxY = maxOf(sy, hy) + PAY
        val g = HashMap<Long, Double>()
        val geldi = HashMap<Long, Long>()
        val acik = PriorityQueue<DoubleArray>(compareBy { it[0] })   // [f, x, y]
        fun h(x: Int, y: Int): Double {
            val dx = Math.abs(x - hx).toDouble(); val dy = Math.abs(y - hy).toDouble()
            return agirlik * (maxOf(dx, dy) + 0.414 * minOf(dx, dy))
        }
        val bas = k(sx, sy)
        g[bas] = 0.0
        acik.add(doubleArrayOf(h(sx, sy), sx.toDouble(), sy.toDouble()))
        var dugum = 0
        while (acik.isNotEmpty()) {
            val c = acik.poll()!!
            val x = c[1].toInt(); val y = c[2].toInt()
            val ck = k(x, y)
            if (x == hx && y == hy) {
                val yol = ArrayList<IntArray>()
                var t: Long? = ck
                while (t != null) { yol.add(intArrayOf((t / 100_000L).toInt(), (t % 100_000L).toInt())); t = geldi[t] }
                yol.reverse()
                return yol
            }
            val gc = g[ck] ?: continue
            if (c[0] - h(x, y) > gc + 1e-6) continue   // eski kayıt
            if (++dugum > azami) return null
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue
                val nk = k(nx, ny)
                val tur = hucre[nk]
                if (tur == DUVAR) continue
                // çaprazda iki yan da duvarsa köşeden geçme
                if (dx != 0 && dy != 0 && hucre[k(x + dx, y)] == DUVAR && hucre[k(x, y + dy)] == DUVAR) continue
                val adim = (if (dx != 0 && dy != 0) 1.414 else 1.0) * (if (tur == YOL) 1.0 else BILINMEYEN_MALIYET)
                val ng = gc + adim
                if (ng < (g[nk] ?: Double.MAX_VALUE)) {
                    g[nk] = ng
                    geldi[nk] = ck
                    acik.add(doubleArrayOf(ng + h(nx, ny), nx.toDouble(), ny.toDouble()))
                }
            }
        }
        return null
    }
}
