package com.kanka.makro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Oyun ekranının üstünde şeffaf çizim alanı: kullanıcı parmağıyla kare çizer ("Paint gibi").
 * Seçili etiketin karesi her yeni çizimde değişir. Koordinatlar ekran pikseli (rawX/rawY).
 */
class CizimView(ctx: Context, private val renkler: Map<String, Int>, private val adlar: Map<String, String>) : View(ctx) {
    val kutular = LinkedHashMap<String, Rect>()
    var secili: String = renkler.keys.first()
    var degisti: (() -> Unit)? = null

    private var x0 = 0f
    private var y0 = 0f
    private var cizilen: Rect? = null
    private val cizgi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 3 }
    private val dolgu = Paint().apply { style = Paint.Style.FILL }
    private val yazi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.displayMetrics.density * 13; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; cizilen = null }
            MotionEvent.ACTION_MOVE -> {
                cizilen = Rect(minOf(x0, e.rawX).toInt(), minOf(y0, e.rawY).toInt(), maxOf(x0, e.rawX).toInt(), maxOf(y0, e.rawY).toInt())
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = cizilen
                cizilen = null
                // Çok küçük (yanlışlıkla dokunma) çizimleri sayma
                if (r != null && r.width() >= 12 && r.height() >= 6) {
                    kutular[secili] = r
                    degisti?.invoke()
                }
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0x22000000)
        val loc = IntArray(2); getLocationOnScreen(loc)
        for ((k, r) in kutular) ciz(c, k, Rect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1]))
        cizilen?.let { r -> ciz(c, secili, Rect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1])) }
    }

    private fun ciz(c: Canvas, k: String, r: Rect) {
        val renk = renkler[k] ?: Color.WHITE
        dolgu.color = (renk and 0x00FFFFFF) or 0x44000000
        cizgi.color = renk
        yazi.color = renk
        c.drawRect(r, dolgu)
        c.drawRect(r, cizgi)
        val ty = if (r.top > yazi.textSize * 1.6f) r.top - yazi.textSize * 0.4f else r.bottom + yazi.textSize * 1.2f
        c.drawText(adlar[k] ?: k, r.left.toFloat(), ty, yazi)
    }
}
