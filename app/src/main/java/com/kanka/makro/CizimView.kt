package com.kanka.makro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Oyun ekranının üstünde işaretleme alanı. Koordinatlar ekran pikseli (rawX/rawY).
 *  • TEK DOKUNUŞ: çubuğun üstüne dokun → bot çubuğu kendisi bulur ve kareyi tam oturtur
 *    (bulamazsa dokunduğun yere hazır boyutta kare koyar).
 *  • Kenardaki yuvarlak tutamaçları sürükle: kareyi uzat/kısalt. Karenin içinden sürükle: taşı.
 *  • Boş yerde sürükle: yeni kare çiz. Sürüklerken köşede büyüteç çıkar (ince çubuk için).
 * kare: çizim ekranı açılmadan önceki oyun görüntüsü (yarı çözünürlük) — hem çubuk bulmak hem büyüteç için.
 */
class CizimView(
    ctx: Context,
    private val renkler: Map<String, Int>,
    private val adlar: Map<String, String>,
    private val kare: Bitmap?,
    private val ekranW: Int,
    private val ekranH: Int
) : View(ctx) {
    val kutular = LinkedHashMap<String, Rect>()
    var secili: String = renkler.keys.first()
    var degisti: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val tutR = 16 * d            // tutamaç yarıçapı (çizim)
    private val tutDokun = 30 * d        // tutamaca dokunma payı (parmak için geniş)
    private val sx = if (kare != null) kare.width.toFloat() / maxOf(1, ekranW) else 1f
    private val sy = if (kare != null) kare.height.toFloat() / maxOf(1, ekranH) else 1f

    private enum class Is { YOK, CIZ, TASI, SOL, SAG, UST, ALT }
    private var islem = Is.YOK
    private var x0 = 0f; private var y0 = 0f
    private var fx = 0f; private var fy = 0f
    private var bas: Rect? = null       // islem baslarkenki kare
    private var cizilen: Rect? = null
    private var hareket = false

    private val cizgi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val dolgu = Paint().apply { style = Paint.Style.FILL }
    private val yazi = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; isFakeBoldText = true }
    private val yaziZemin = Paint().apply { color = 0xCC000000.toInt() }
    private val tut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tutKenar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val buyCerceve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
    private val arti = Paint().apply { color = Color.WHITE; strokeWidth = 2 * d }
    private val bmpBoya = Paint().apply { isFilterBitmap = false }
    private val siyah = Paint().apply { color = Color.BLACK }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.rawX; val y = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = x; y0 = y; fx = x; fy = y; hareket = false
                val r = kutular[secili]
                bas = r?.let { Rect(it) }
                islem = when {
                    r == null -> Is.CIZ
                    yakin(x, y, r.left.toFloat(), r.exactCenterY()) -> Is.SOL
                    yakin(x, y, r.right.toFloat(), r.exactCenterY()) -> Is.SAG
                    yakin(x, y, r.exactCenterX(), r.top.toFloat()) -> Is.UST
                    yakin(x, y, r.exactCenterX(), r.bottom.toFloat()) -> Is.ALT
                    r.contains(x.toInt(), y.toInt()) -> Is.TASI
                    else -> Is.CIZ
                }
            }
            MotionEvent.ACTION_MOVE -> {
                fx = x; fy = y
                if (!hareket && abs(x - x0) + abs(y - y0) > 10 * d) hareket = true
                if (hareket) {
                    val b = bas
                    val dx = (x - x0).toInt(); val dy = (y - y0).toInt()
                    when (islem) {
                        Is.CIZ -> cizilen = Rect(minOf(x0, x).toInt(), minOf(y0, y).toInt(), maxOf(x0, x).toInt(), maxOf(y0, y).toInt())
                        Is.TASI -> if (b != null) kutular[secili] = Rect(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
                        Is.SOL -> if (b != null) kutular[secili] = Rect(minOf(b.left + dx, b.right - 8), b.top, b.right, b.bottom)
                        Is.SAG -> if (b != null) kutular[secili] = Rect(b.left, b.top, maxOf(b.right + dx, b.left + 8), b.bottom)
                        Is.UST -> if (b != null) kutular[secili] = Rect(b.left, minOf(b.top + dy, b.bottom - 4), b.right, b.bottom)
                        Is.ALT -> if (b != null) kutular[secili] = Rect(b.left, b.top, b.right, maxOf(b.bottom + dy, b.top + 4))
                        else -> {}
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!hareket && e.actionMasked == MotionEvent.ACTION_UP) {
                    // Tek dokunuş BOŞ yere: akıllı yerleştir. Karenin/tutamacın üstüne dokunmak kareyi bozmaz.
                    if (islem == Is.CIZ) {
                        kutular[secili] = yerlestir(secili, x.toInt(), y.toInt())
                        degisti?.invoke()
                    }
                } else if (islem == Is.CIZ) {
                    val r = cizilen
                    if (r != null && r.width() >= 12 && r.height() >= 4) { kutular[secili] = r; degisti?.invoke() }
                } else if (islem != Is.YOK) degisti?.invoke()
                cizilen = null; islem = Is.YOK; hareket = false
                invalidate()
            }
        }
        return true
    }

    private fun yakin(x: Float, y: Float, hx: Float, hy: Float) = abs(x - hx) < tutDokun && abs(y - hy) < tutDokun

    // ---------- tek dokunuşla yerleştirme ----------
    private fun piksel(px: Int, py: Int): Int {
        val k = kare ?: return 0
        if (px < 0 || py < 0 || px >= k.width || py >= k.height) return 0
        return k.getPixel(px, py)
    }

    private fun uygun(ad: String, c: Int): Boolean {
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        return if (ad == "mp") b >= 110 && b > r * 1.6f && b > g * 1.2f
        else r >= 90 && r > g * 2 && r > b * 2
    }

    /** Dokunulan yerin çevresindeki renkli çubuğun sınırları (ekran pikseli); bulunamazsa null */
    private fun cubukBul(ad: String, ex: Int, ey: Int): Rect? {
        if (kare == null) return null
        val cx = (ex * sx).toInt(); val cy = (ey * sy).toInt()
        // Dokunulan noktaya en yakın uygun piksel (parmak çubuğu tam tutturamasa da olur)
        var bx = -1; var by = -1; var en = Int.MAX_VALUE
        val ar = 14
        for (yy in cy - ar..cy + ar) for (xx in cx - ar..cx + ar) {
            if (uygun(ad, piksel(xx, yy))) {
                val dd = (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy)
                if (dd < en) { en = dd; bx = xx; by = yy }
            }
        }
        if (bx < 0) return null
        // Dikey: bu sütun çevresinde uygun piksel olan satırlar
        fun satirVar(yy: Int): Boolean { for (xx in bx - 6..bx + 6) if (uygun(ad, piksel(xx, yy))) return true; return false }
        var ust = by; while (ust - 1 >= 0 && by - ust < 40 && satirVar(ust - 1)) ust--
        var alt = by; while (by - alt > -40 && satirVar(alt + 1)) alt++
        // Yatay: çubuğun ortasındaki "1918/1918" yazısı boşluk yapar; birkaç satırı birlikte tara, 10 px boşluğa izin ver
        fun sutunVar(xx: Int): Boolean { for (yy in ust..alt) if (uygun(ad, piksel(xx, yy))) return true; return false }
        var sol = bx; var bosluk = 0; var xx = bx
        while (xx > 0 && bosluk <= 10) { xx--; if (sutunVar(xx)) { sol = xx; bosluk = 0 } else bosluk++ }
        var sag = bx; bosluk = 0; xx = bx
        while (xx < (kare.width - 1) && bosluk <= 10) { xx++; if (sutunVar(xx)) { sag = xx; bosluk = 0 } else bosluk++ }
        if (sag - sol < 10) return null
        return Rect(((sol - 1) / sx).toInt(), ((ust - 1) / sy).toInt(), ((sag + 2) / sx).toInt(), ((alt + 2) / sy).toInt())
    }

    private fun yerlestir(ad: String, x: Int, y: Int): Rect {
        if (ad == "hp" || ad == "mp" || ad == "hedef") cubukBul(ad, x, y)?.let { return it }
        // Hazır boyut (dokunulan yer merkez): sonra tutamaçlarla ayarlanır
        val (w, h) = when (ad) {
            "hp", "mp" -> ekranW * 0.15f to ekranH * 0.025f
            "hedef" -> ekranW * 0.13f to ekranH * 0.022f
            "koord" -> ekranW * 0.09f to ekranH * 0.035f
            "hud" -> ekranW * 0.20f to ekranH * 0.20f
            else -> ekranW * 0.28f to ekranH * 0.40f
        }
        return Rect((x - w / 2).toInt(), (y - h / 2).toInt(), (x + w / 2).toInt(), (y + h / 2).toInt())
    }

    // ---------- çizim ----------
    override fun onDraw(c: Canvas) {
        for ((k, r) in kutular) ciz(c, k, r, k == secili)
        cizilen?.let { ciz(c, secili, it, true) }
        if (hareket) buyutec(c)
    }

    private fun ciz(c: Canvas, k: String, r: Rect, sec: Boolean) {
        val renk = renkler[k] ?: Color.WHITE
        dolgu.color = (renk and 0x00FFFFFF) or 0x33000000
        cizgi.color = renk
        c.drawRect(r, dolgu)
        c.drawRect(r, cizgi)
        val ad = adlar[k] ?: k
        val tw = yazi.measureText(ad)
        val ty = if (r.top > yazi.textSize * 2f) r.top - yazi.textSize * 0.5f else r.bottom + yazi.textSize * 1.3f
        c.drawRect(r.left.toFloat(), ty - yazi.textSize, r.left + tw + 8 * d, ty + 4 * d, yaziZemin)
        yazi.color = renk
        c.drawText(ad, r.left + 4 * d, ty, yazi)
        if (sec && kutular[k] === r) {
            tutKenar.color = renk
            for ((hx, hy) in listOf(r.left.toFloat() to r.exactCenterY(), r.right.toFloat() to r.exactCenterY(),
                r.exactCenterX() to r.top.toFloat(), r.exactCenterX() to r.bottom.toFloat())) {
                c.drawCircle(hx, hy, tutR, tut); c.drawCircle(hx, hy, tutR, tutKenar)
            }
        }
    }

    /** Parmağın altındaki yeri 3 kat büyütüp parmaktan uzak üst köşede gösterir */
    private fun buyutec(c: Canvas) {
        val k = kare ?: return
        val boy = (150 * d)
        val ox = if (fx < ekranW / 2) width - boy - 12 * d else 12 * d
        val oy = 12 * d
        val yar = boy / 2 / 3f   // ekran pikseli cinsinden yarım pencere (3x büyütme)
        val src = Rect(((fx - yar) * sx).toInt(), ((fy - yar) * sy).toInt(), ((fx + yar) * sx).toInt(), ((fy + yar) * sy).toInt())
        val dst = RectF(ox, oy, ox + boy, oy + boy)
        c.drawRect(dst, siyah)
        c.drawBitmap(k, src, dst, bmpBoya)
        // seçili kareyi büyüteçte de göster
        kutular[secili]?.let { r ->
            val o = 3f
            val rx1 = ox + (r.left - (fx - yar)) * o; val ry1 = oy + (r.top - (fy - yar)) * o
            val rx2 = ox + (r.right - (fx - yar)) * o; val ry2 = oy + (r.bottom - (fy - yar)) * o
            c.save(); c.clipRect(dst)
            cizgi.color = renkler[secili] ?: Color.WHITE
            c.drawRect(rx1, ry1, rx2, ry2, cizgi)
            c.restore()
        }
        c.drawLine(ox + boy / 2, oy + boy / 2 - 12 * d, ox + boy / 2, oy + boy / 2 + 12 * d, arti)
        c.drawLine(ox + boy / 2 - 12 * d, oy + boy / 2, ox + boy / 2 + 12 * d, oy + boy / 2, arti)
        c.drawRect(dst, buyCerceve)
    }
}
