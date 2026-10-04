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
    // Yukseklige oran (2576x1159 olcumu: 0.0225*2576/1159 ve 0.008*2576/1159)
    private const val KOYU_RH = 0.0500f
    private const val ALTIN_RH = 0.0178f

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

        barlar(c, w, h)
        c.hpYuzde = 50
        c.mpYuzde = 30
        c.minDelay = 700      // yonetici sarti: seri basma yok
        c.maxDelay = 1300
        c.save(ctx)
    }

    /** HP/MP/hedef bari ve KO'ya ozel sabit ayarlar (Farm ve PK ortak) */
    private fun barlar(c: Config, w: Int, h: Int) {
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
        c.potCd = 1800       // KO: HP/MP iksiri ortak bekleme (oyunda 1.8 sn)
    }

    /**
     * KO PK • Asas hazir ayari: kullanicinin videosundaki skill duzeni ve olculen sureler.
     * Video 848x390; skill paneli sag kenara gore yerlesir, bu yuzden x sagdan, y yukseklik
     * oranindan hesaplanir. Farkli telefonda yerler kayabilir: Tuslari duzenle ile surukleyerek oturtulur.
     */
    /**
     * KO PK • Warrior / Mage / Priest hazir ayari: Asas ile ayni ekran duzeni (kilic, iksirler,
     * skill izgarasi). Skill sureleri tahmini; Kayitli tuslar'dan ayarlanir, bos yuvalar kapatilir.
     */
    fun applyPkGenel(ctx: Context) {
        val (w, h) = Preset.landscapeSize(ctx)
        val k = h / 390f
        fun v(ad: String, tip: String, xv: Int, yv: Int, cd: Float = 0f, on: Boolean = true) =
            Nokta(ad, tip, (w - (848 - xv) * k).roundToInt(), (yv * k).roundToInt(), cd, on)
        val c = Config.load(ctx)
        c.points.clear()
        c.points.add(v("Kılıç", "saldiri", 785, 295))
        c.points.add(v("HP pot", "hp_pot", 565, 327))
        c.points.add(v("MP pot", "mp_pot", 613, 327))
        val orta = intArrayOf(548, 597, 649, 698, 748, 800)
        orta.forEachIndexed { i, x -> c.points.add(v("Skill ${i + 1}", "skill", x, 225, 5f)) }
        val alt = intArrayOf(555, 606, 656, 718)
        alt.forEachIndexed { i, x -> c.points.add(v("Skill ${i + 7}", "skill", x, 273, 10f)) }
        barlar(c, w, h)
        c.skillAraMs = 800
        c.hpYuzde = 50
        c.mpYuzde = 30
        c.potCd = 1800
        c.save(ctx)
    }

    fun applyPkAsas(ctx: Context) {
        val (w, h) = Preset.landscapeSize(ctx)
        val k = h / 390f
        fun v(ad: String, tip: String, xv: Int, yv: Int, cd: Float = 0f, on: Boolean = true) =
            Nokta(ad, tip, (w - (848 - xv) * k).roundToInt(), (yv * k).roundToInt(), cd, on)

        val c = Config.load(ctx)
        c.points.clear()
        c.points.add(v("Kılıç", "saldiri", 785, 295))
        c.points.add(v("HP pot", "hp_pot", 565, 327))
        c.points.add(v("MP pot", "mp_pot", 613, 327))
        c.points.add(v("Minor", "minor", 668, 327))
        // Skill sirasi (bot bu sirayla doner, hazir olani basar)
        c.points.add(v("Blinding", "skill", 718, 273, 60f))
        c.points.add(v("Mor dalga", "skill", 606, 273, 11f))
        c.points.add(v("Mor ok", "skill", 656, 273, 10f))
        c.points.add(v("Mor ok 2", "skill", 548, 225, 10.5f))
        c.points.add(v("Kırmızı 1", "skill", 597, 225, 5f))
        c.points.add(v("Kırmızı 2", "skill", 649, 225, 5f))
        c.points.add(v("Kırmızı 3", "skill", 698, 225, 5f))
        c.points.add(v("Kırmızı 4", "skill", 748, 225, 5f))
        c.points.add(v("Hançer", "skill", 800, 225, 5f))
        c.points.add(v("Mavi koşu", "skill", 555, 273, 10f, false))
        barlar(c, w, h)
        c.skillAraMs = 800
        c.hpYuzde = 60
        c.mpYuzde = 30
        c.minorYuzde = 80
        c.minorAktif = true
        c.mpOncelik = true     // Asas: minor manayla calisir, mana bitmesin
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

    // ---- Barlari ekranda kendisi bulma (karakter/telefon/arayuz boyutu farkli olabilir) ----
    @Volatile private var hpBul: IntArray? = null   // x1, x2, y
    @Volatile private var mpBul: IntArray? = null

    // Bar arama agir bir tarama: botun ana dongusunu bekletmesin diye arka planda yapilir
    private val arkaIs by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    @Volatile private var araniyor = false

    /** Barlari arka planda ara (ayni anda tek arama); sonuc hpBul/mpBul'a yazilir */
    fun barlariBulArka(w: Int, h: Int, bitince: (() -> Unit)? = null) {
        if (araniyor) return
        araniyor = true
        arkaIs.execute {
            try { barlariBul(w, h) } catch (_: Exception) {} finally { araniyor = false }
            bitince?.invoke()
        }
    }

    fun sifirla() { hpBul = null; mpBul = null; hedefSatir = -1; for (g in gecmis) g.fill(-1f) }

    /** Bir satirdaki en uzun renkli seridi bul (yazi bosluklarini birlestirerek): x1, x2 ya da null */
    private fun enUzunSerit(y: Int, xBas: Int, xSon: Int, adim: Int, bosluk: Int, test: (Int) -> Boolean): IntArray? {
        var enX1 = -1; var enX2 = -1
        var x1 = -1; var son = -1
        var x = xBas
        while (x <= xSon) {
            val c = ScreenSampler.readPixel(x, y)
            if (c >= 0 && test(c)) {
                if (x1 < 0 || x - son > bosluk) x1 = x
                son = x
                if (son - x1 > enX2 - enX1) { enX1 = x1; enX2 = son }
            }
            x += adim
        }
        return if (enX1 >= 0) intArrayOf(enX1, enX2) else null
    }

    /**
     * Sol ustte HP (kirmizi) ve hemen altinda MP (mavi) barini arar.
     * Bot baslarken ve bar okunamadiginda calisir; bulunca yerini hatirlar.
     */
    fun barlariBul(w: Int, h: Int) {
        if (!ScreenSampler.running) return
        val xSon = (w * 0.42f).toInt()
        val adim = maxOf(2, w / 900)
        val bosluk = (w * 0.012f).toInt()
        val enAz = (w * 0.04f).toInt()
        var hp: IntArray? = null
        var y = 1
        while (y < (h * 0.10f).toInt()) {
            val s = enUzunSerit(y, 0, xSon, adim, bosluk) { kirmizi(it) }
            if (s != null && s[1] - s[0] >= enAz && (hp == null || s[1] - s[0] > hp[1] - hp[0] + adim)) {
                hp = intArrayOf(s[0], s[1], y)
            }
            y += 2
        }
        if (hp == null) return
        hpBul = birlestir(hpBul, hp, w, h)
        var mp: IntArray? = null
        y = hp[2] + 2
        while (y < hp[2] + (h * 0.07f).toInt()) {
            val s = enUzunSerit(y, maxOf(0, hp[0] - bosluk), xSon, adim, bosluk) { mavi(it) }
            if (s != null && s[1] - s[0] >= enAz && (mp == null || s[1] - s[0] > mp[1] - mp[0] + adim)) {
                mp = intArrayOf(s[0], s[1], y)
            }
            y += 2
        }
        if (mp != null) mpBul = birlestir(mpBul, mp, w, h)
    }

    /** Ayni bar ise (yeri ayni) uzun olan boyu koru: can dusukken bulunca bar kisa sanilmasin */
    private fun birlestir(eski: IntArray?, yeni: IntArray, w: Int, h: Int): IntArray {
        if (eski == null) return yeni
        val ayni = Math.abs(eski[2] - yeni[2]) <= maxOf(3, (h * 0.012f).toInt()) &&
            Math.abs(eski[0] - yeni[0]) <= maxOf(3, (w * 0.012f).toInt())
        return if (ayni) intArrayOf(minOf(eski[0], yeni[0]), maxOf(eski[1], yeni[1]), eski[2]) else yeni
    }

    /** Bulunmus barin dolulugu; bar hic yoksa -1. Bar uzarsa (can dolunca) sag ucu gunceller. */
    /**
     * Bulunmus barin dolulugu (0..1); bar yoksa -1.
     * - 5 satir okunur; en az 2 satirin onayladigi en sag nokta alinir (tek piksel efekt/yazi bozamaz)
     * - Once kaba tarama, sonra sinirda 1 piksel ince tarama (net yuzde)
     * - Can dolunca bar uzarsa sag ucu gunceller
     */
    private fun bulunanDoluluk(b: IntArray, w: Int, mp: Boolean): Float {
        val x1 = b[0]; var x2 = b[1]; val y = b[2]
        val ara = (x2 - x1).coerceAtLeast(1)
        val kaba = maxOf(1, ara / 60)
        val bitis = minOf(x2 + (w * 0.02f).toInt(), (w * 0.5f).toInt())
        val sonlar = ArrayList<Int>(5)
        for (dy in intArrayOf(-2, -1, 0, 1, 2)) {
            var son = -1
            var x = x1
            while (x <= bitis) {
                val c = ScreenSampler.readPixel(x, y + dy)
                if (c >= 0 && (if (mp) mavi(c) else kirmizi(c))) son = x
                x += kaba
            }
            if (son >= 0) {
                var xx = son + 1
                while (xx < son + kaba && xx <= bitis) {
                    val c = ScreenSampler.readPixel(xx, y + dy)
                    if (c >= 0 && (if (mp) mavi(c) else kirmizi(c))) son = xx
                    xx++
                }
                sonlar.add(son)
            }
        }
        if (sonlar.isEmpty()) return -1f
        sonlar.sortDescending()
        val son = if (sonlar.size >= 2) sonlar[1] else sonlar[0]
        if (son > x2 && sonlar.size >= 2) { x2 = son; b[1] = son }
        return ((son - x1).toFloat() / (x2 - x1).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // Son 3 okuma (zaman suzgeci): kisa titremeleri yutar, ani dususu aninda gecirir
    private val gecmis = arrayOf(FloatArray(3) { -1f }, FloatArray(3) { -1f })
    private val gecmisAt = LongArray(2)
    private val sonDeger = floatArrayOf(-1f, -1f)

    /** Barin yaklasik dolulugu (0..1); bar hic gorunmuyorsa -1 */
    fun doluluk(w: Int, h: Int, mp: Boolean): Float {
        val k = if (mp) 1 else 0
        val simdi = System.currentTimeMillis()
        // Ayni an icinde birden cok soruluyorsa (pot, minor, guvenlik) ayni degeri ver
        if (simdi - gecmisAt[k] < 40) return sonDeger[k]
        gecmisAt[k] = simdi
        val bulunan = if (mp) mpBul else hpBul
        var ham = -1f
        if (bulunan != null) ham = bulunanDoluluk(bulunan, w, mp)
        if (ham < 0f) ham = sabitDoluluk(w, h, mp)
        if (ham < 0f) { sonDeger[k] = -1f; return -1f }
        val g = gecmis[k]
        g[2] = g[1]; g[1] = g[0]; g[0] = ham
        val gecerli = g.filter { it >= 0f }.sorted()
        val orta = gecerli[gecerli.size / 2]
        // Ani dusus (darbe): beklemeden gercek degeri kullan
        val sonuc = if (ham < orta - 0.12f) ham else orta
        sonDeger[k] = sonuc
        return sonuc
    }

    // ---- Bar yerini kaydet/yukle: bot can dusukken baslasa da bar boyu dogru kalsin ----
    private fun pref(ctx: Context) = ctx.getSharedPreferences("ko_bar", Context.MODE_PRIVATE)

    fun barYukle(ctx: Context, w: Int, h: Int) {
        val p = pref(ctx)
        fun oku(ad: String) = p.getString(ad + "_" + w + "x" + h, null)
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.toIntArray()
        oku("hp")?.let { hpBul = it }
        oku("mp")?.let { mpBul = it }
        for (g in gecmis) g.fill(-1f)
    }

    fun barKaydet(ctx: Context, w: Int, h: Int) {
        val e = pref(ctx).edit()
        hpBul?.let { e.putString("hp_" + w + "x" + h, it.joinToString(",")) }
        mpBul?.let { e.putString("mp_" + w + "x" + h, it.joinToString(",")) }
        e.apply()
    }

    /** Eski yontem: Farm ekran goruntusundeki sabit yer */
    private fun sabitDoluluk(w: Int, h: Int, mp: Boolean): Float {
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

    /**
     * Kutu butonunun halkasi. Yaricap ekran YUKSEKLIGINE gore (oyun arayuzu yukseklige gore
     * olceklenir): telefonda eski degerle ayni, tablette (4:3, 16:10) de dogru boyut.
     */
    private fun halka(cx: Float, cy: Float, h: Int, rH: Float, test: (Int) -> Boolean): Int {
        val rp = rH * h
        var say = 0
        for (i in 0 until 24) {
            val a = 2.0 * Math.PI * i / 24
            val c = ScreenSampler.readPixel((cx + rp * cos(a)).toInt(), (cy + rp * sin(a)).toInt())
            if (c >= 0 && test(c)) say++
        }
        return say
    }

    /**
     * Ust ortada secili hedefin kirmizi can bari: sabit yerde bulunamazsa genis alanda
     * kesintisiz kirmizi serit arar (arayuz boyutu farkli olabilir). Isim yazilari harf
     * aralari yuzunden serit sayilmaz.
     */
    @Volatile private var hedefSatir = -1      // hedef barinin bulundugu satir (hizli kontrol)
    @Volatile private var hedefTamAt = 0L

    fun hedefVar(w: Int, h: Int): Boolean {
        val x1 = (w * 0.28f).toInt(); val x2 = (w * 0.72f).toInt()
        // Once bilinen satira bak (ucuz); yoksa tam aramayi en fazla 0.4 sn'de bir yap
        val ys = hedefSatir
        if (ys >= 0 && satirdaSerit(ys, x1, x2, w)) return true
        val simdi = System.currentTimeMillis()
        if (simdi - hedefTamAt < 400) return false
        hedefTamAt = simdi
        val adim = maxOf(2, w / 400)
        val gerek = maxOf(8, (w * 0.025f / adim).toInt())
        var y = (h * 0.012f).toInt()
        val yBitis = (h * 0.11f).toInt()
        val yAdim = maxOf(2, (h * 0.005f).toInt())
        while (y <= yBitis) {
            var seri = 0
            var x = x1
            while (x <= x2) {
                val c = ScreenSampler.readPixel(x, y)
                if (c >= 0 && kirmizi(c)) {
                    if (++seri >= gerek) { hedefSatir = y; return true }
                } else seri = 0
                x += adim
            }
            y += yAdim
        }
        return false
    }

    private fun satirdaSerit(y: Int, x1: Int, x2: Int, w: Int): Boolean {
        val adim = maxOf(2, w / 400)
        val gerek = maxOf(8, (w * 0.025f / adim).toInt())
        var seri = 0
        var x = x1
        while (x <= x2) {
            val c = ScreenSampler.readPixel(x, y)
            if (c >= 0 && kirmizi(c)) { if (++seri >= gerek) return true } else seri = 0
            x += adim
        }
        return false
    }

    /** Kutu toplama butonu ekranda ve aktif mi? */
    fun kutuVar(w: Int, h: Int, yer: FloatArray? = null): Boolean {
        val p = yer ?: kutuNokta(w, h)
        val koyu = halka(p[0], p[1], h, KOYU_RH) { c ->
            ((c shr 16) and 0xff) < 90 && ((c shr 8) and 0xff) < 90 && (c and 0xff) < 90
        }
        if (koyu < 14) return false
        val altin = halka(p[0], p[1], h, ALTIN_RH) { c ->
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
