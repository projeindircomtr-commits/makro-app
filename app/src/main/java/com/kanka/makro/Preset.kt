package com.kanka.makro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.roundToInt

/**
 * MykoMobile icin hazir ayar. Konumlar 2712x1220 ekran goruntulerinden olculdu,
 * farkli cozunurlukte oranla olceklenir. Renkler ve buton sablonlari gercek oyundan alindi.
 */
object Preset {
    private const val BW = 2712f
    private const val BH = 1220f

    @Suppress("DEPRECATION")
    fun landscapeSize(ctx: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
    }

    /**
     * Hedef can barinin tamami: [x1, x2, y]. Barin herhangi bir yerinde kirmizi varsa
     * hedef yasiyor demektir (can %1'e insede).
     */
    fun tgtStrip(ctx: Context, bar: RenkNokta?): IntArray {
        val (w, h) = landscapeSize(ctx)
        if (bar == null) {
            return intArrayOf(
                (1195 * w / BW).roundToInt(),
                (1560 * w / BW).roundToInt(),
                (66 * h / BH).roundToInt()
            )
        }
        // Kaydedilen noktaya gore (tablet vb. farkli ekranlarda da dogru yere bakar):
        // nokta barin sol tarafinda; bar saga dogru uzanir.
        return intArrayOf(
            (bar.x - 0.025f * w).roundToInt().coerceAtLeast(0),
            (bar.x + 0.11f * w).roundToInt().coerceAtMost(w - 1),
            (bar.y + 6f * h / BH).roundToInt().coerceAtMost(h - 1)
        )
    }

    fun apply(ctx: Context) {
        val (w, h) = landscapeSize(ctx)
        fun x(v: Int) = (v * w / BW).roundToInt()
        fun y(v: Int) = (v * h / BH).roundToInt()

        val c = Config.load(ctx)
        c.points.clear()
        c.points.add(Nokta("Saldırı", "saldiri", x(2418), y(930)))
        c.points.add(Nokta("Mob seç", "hedef", x(2570), y(1008)))
        c.points.add(Nokta("HP pot", "hp_pot", x(2370), y(757)))
        c.points.add(Nokta("MP pot", "mp_pot", x(2255), y(877)))
        // Saldiri skilleri (acik) - sira = oncelik
        c.points.add(Nokta("Skill 1", "skill", x(2522), y(760), 4f, true))   // kirmizi isin
        c.points.add(Nokta("Skill 2", "skill", x(2338), y(615), 4f, true))   // mavi isin (ust)
        c.points.add(Nokta("Skill 3", "skill", x(2108), y(862), 4f, true))   // mavi isin (alt)
        c.points.add(Nokta("Skill 4", "skill", x(2212), y(727), 5f, true))   // mavi kilic
        // Buff olabilecekler (kapali, istersen uygulamadan ac)
        c.points.add(Nokta("Skill 5", "skill", x(2107), y(1013), 60f, false)) // ates silueti
        c.points.add(Nokta("Skill 6", "skill", x(2255), y(1013), 60f, false)) // mavi figur

        c.hp = RenkNokta(x(315), y(26), 0xC72720)      // HP ~%74 altina inince pot
        c.mp = RenkNokta(x(150), y(68), 0x3A4CCB)      // MP ~%25 altina inince pot
        c.tgtBar = RenkNokta(x(1262), y(60), 0xB22D29) // hedef can bari (sol taraf)

        loadTemplate(ctx, "open.png", w, 20, 0.5f, 0.5f)?.let { c.openT = it }
        // Collect All + Close penceresi birlikte; dokunus ust butonun ortasina (Close'a asla degil)
        loadTemplate(ctx, "collect.png", w, 25, 0.5f, 0.22f)?.let { c.collectT = it }
        loadTemplate(ctx, "collect_btn.png", w, 19, 0.5f, 0.5f)?.let { c.collectT2 = it }
        c.lootEvery = 350
        c.collectWait = 2500
        c.save(ctx)
    }

    private fun loadTemplate(ctx: Context, name: String, w: Int, tol: Int, tx: Float, ty: Float): Sablon? {
        return try {
            val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } ?: return null
            val f = w / BW * ScreenSampler.SCALE / ScreenSampler.GRID
            val tw = (bmp.width * f).roundToInt().coerceAtLeast(4)
            val th = (bmp.height * f).roundToInt().coerceAtLeast(3)
            val sc = Bitmap.createScaledBitmap(bmp, tw, th, true)
            val px = IntArray(tw * th)
            sc.getPixels(px, 0, tw, 0, 0, tw, th)
            for (i in px.indices) px[i] = px[i] and 0xFFFFFF
            Sablon(tw, th, px, tol, tx, ty)
        } catch (e: Exception) {
            null
        }
    }
}
