package com.kanka.makro

import android.graphics.PointF

class ResolutionManager(val width: Int, val height: Int) {

    val aspectRatio: Float = width.toFloat() / height.toFloat()
    val isTablet: Boolean = aspectRatio < 1.7f // 16:10 veya 4:3 tabletler

    // Alt bardaki "Inventory" butonu konumu (Her ekranda alt sınıra göre sabitlenir)
    fun getInventoryButtonPoint(): PointF {
        val y = height * 0.963f
        // Uzun telefonlarda (20:9) ve tabletlerde menü orantı düzeltmesi
        val x = if (isTablet) width * 0.755f else width * 0.735f
        return PointF(x, y)
    }

    // NPC üzerindeki "Open" butonu
    fun getOpenButtonPoint(): PointF {
        return PointF(width * 0.505f, height * 0.542f)
    }

    // Sağ menüdeki "Inn Hostes Open" seçeneği
    fun getInnHostesOpenMenuPoint(): PointF {
        val x = if (isTablet) width * 0.865f else width * 0.885f
        val y = height * 0.415f
        return PointF(x, y)
    }

    // Bankayı kapatma ("X") butonu
    fun getBankClosePoint(): PointF {
        val x = if (isTablet) width * 0.845f else width * 0.865f
        val y = height * 0.065f
        return PointF(x, y)
    }

    // Envanter grid slotlarını dinamik hesaplama (7 Sütun, 4 Satır)
    fun getInventorySlotPoint(row: Int, col: Int): PointF {
        val startX = if (isTablet) width * 0.590f else width * 0.612f
        val startY = height * 0.628f
        val stepX = if (isTablet) width * 0.057f else width * 0.053f
        val stepY = height * 0.088f

        return PointF(startX + (col * stepX), startY + (row * stepY))
    }
}
