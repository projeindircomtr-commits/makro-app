package com.kanka.makro

import android.content.Context
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * KO Mobile hazir ayari ve ekran okuma.
 * Olculer 2576x1159 KO ekran goruntulerinden alindi; ekrana oranla olceklenir.
 * Yoneticilerin sarti: seri basma yok, surekli kullanma yok (Farm'in sure/mola ayarlariyla).
 */
object KoOyun {
    private const val RW = 2576f
    private const val RH = 1159f

    // Skill halkasindaki slotlar (No 1..17), referans piksel
    val SLOTLAR = listOf(
        2075 to 415, 2337 to 485, 2474 to 485, 2065 to 555, 2232 to 598,
        1972 to 697, 2130 to 697, 2318 to 720, 2455 to 715, 1798 to 780,
        1933 to 845, 2080 to 838, 2228 to 828, 1823 to 950, 1963 to 992,
        2098 to 978, 2238 to 960
    )
    private val SALDIRI = 2410 to 905
    private val MOB_SEC = 2455 to 1050

    // Barlar: x araligi ve okunacak satirlar (ortadaki "1650 / 1650" yazisi atlanir)
    private const val BAR_X1 = 262
    private const val BAR_X2 = 443
    private val HP_Y = intArrayOf(13, 18, 23)
    private val MP_Y = intArrayOf(44, 47, 50)

    // Kutu toplama butonu: koyu daire + ortada altin sandik
    private const val KUTU_X = 0.2686f
    private const val KUTU_Y = 0.591f
    private const val KOYU_R = 0.0225f
    private const val ALTIN_R = 0.008f

    private fun sx(w: Int, v: Int) = (v / RW * w).roundToInt()
    private fun sy(h: Int, v: Int) = (v / RH * h).roundToInt()

    /** Ilk kurulum / yeniden yukleme: KO tuslari, barlar, hedef bari */
    fun apply(ctx: Context) {
        val (w, h) = Preset.landscapeSize(ctx)
        fun n(ad: String, tip: String, p: Pair<Int, Int>, cd: Float = 0f, on: Boolean = true) =
            Nokta(ad, tip, sx(w, p.first), sy(h, p.second), cd, on)

        val c = Config.load(ctx)
        c.points.clear()
        c.points.add(n("Kılıç", "saldiri", SALDIRI))
        c.points.add(n("Mob seç", "hedef", MOB_SEC))
        c.points.add(n("HP pot", "hp_pot", SLOTLAR[7]))
        c.points.add(n("MP pot", "mp_pot", SLOTLAR[12]))
        c.points.add(n("Skill 1", "skill", SLOTLAR[11], 1.5f))
        c.points.add(n("Skill 2", "skill", SLOTLAR[6], 1.5f))
        c.points.add(n("Skill 3", "skill", SLOTLAR[4], 1.5f))
        c.points.add(n("Skill 4", "skill", SLOTLAR[2], 60f, false))

        // Yer tutucu: KO'da barlar dolulukla okunur (KoOyun.doluluk), renk kullanilmaz
        c.hp = RenkNokta(sx(w, 352), sy(h, 18), 0xD02B22)
        c.mp = RenkNokta(sx(w, 316), sy(h, 47), 0x3946AA)
        // Hedef bari: Preset.tgtStrip bu noktadan saga dogru seridi kurar
        c.tgtBar = RenkNokta(
            (1103 / RW * w + 0.025f * w).roundToInt(),
            (72 / RH * h - 6f * h / 1220f).roundToInt(),
            0xB22D29
        )
        c.openT = null
        c.collectT = null
        c.collectT2 = null
        c.otoArayuz = false
        c.tuslarOto = false
        c.hpYuzde = 50
        c.mpYuzde = 30
        c.minDelay = 700      // yonetici sarti: seri basma yok
        c.maxDelay = 1300
        c.save(ctx)
    }

    private fun kirmizi(c: Int): Boolean {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        return r >= 90 && r > g * 2 && r > b * 2
    }

    private fun mavi(c: Int): Boolean {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        return b >= 110 && b > r * 1.6f && b > g * 1.2f
    }

    /** Barin yaklasik dolulugu (0..1); bar hic gorunmuyorsa -1 */
    fun doluluk(w: Int, h: Int, mp: Boolean): Float {
        val x1 = sx(w, BAR_X1); val x2 = sx(w, BAR_X2)
        val ys = if (mp) MP_Y else HP_Y
        val n = 40
        var son = -1
        var bulunan = 0
        for (yr in ys) {
            val y = sy(h, yr)
            for (k in 0 until n) {
                val c = ScreenSampler.readPixel(x1 + (x2 - x1) * k / (n - 1), y)
                if (c >= 0 && (if (mp) mavi(c) else kirmizi(c))) {
                    bulunan++
                    if (k > son) son = k
                }
            }
        }
        if (bulunan < 2) return -1f
        return (son + 1).toFloat() / n
    }

    private fun halka(w: Int, h: Int, r: Float, test: (Int) -> Boolean): Int {
        val cx = KUTU_X * w; val cy = KUTU_Y * h; val rp = r * w
        var say = 0
        for (i in 0 until 24) {
            val a = 2.0 * Math.PI * i / 24
            val c = ScreenSampler.readPixel((cx + rp * cos(a)).toInt(), (cy + rp * sin(a)).toInt())
            if (c >= 0 && test(c)) say++
        }
        return say
    }

    /** Kutu toplama butonu ekranda ve aktif mi? */
    fun kutuVar(w: Int, h: Int): Boolean {
        val koyu = halka(w, h, KOYU_R) { c ->
            ((c shr 16) and 0xff) < 90 && ((c shr 8) and 0xff) < 90 && (c and 0xff) < 90
        }
        if (koyu < 14) return false
        val altin = halka(w, h, ALTIN_R) { c ->
            val r = (c shr 16) and 0xff
            r > 150 && r - (c and 0xff) > 60
        }
        return altin >= 8
    }

    fun kutuNokta(w: Int, h: Int) = floatArrayOf(KUTU_X * w, KUTU_Y * h)

    /** Ust ortadaki secili mob ismi (ornek "Small Bulcan"): mob kilidi bu alani okur */
    fun isimAlani(w: Int, h: Int) = intArrayOf(sx(w, 1060), sy(h, 6), sx(w, 1520), sy(h, 44))

    /** Hedef secilince cikan kirmizi X (hedefi birak) */
    fun iptalNokta(w: Int, h: Int) = intArrayOf(sx(w, 2524), sy(h, 965))
}
