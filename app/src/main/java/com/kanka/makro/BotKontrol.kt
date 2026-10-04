package com.kanka.makro

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * MykoMobile "bot kontrolu" penceresini okur.
 * SADECE MykoMobile Farm modunda calisir (PK ve KO Mobile'da calismaz).
 * MykoMobile yonetiminin izniyle; kullanan uyeler yonetime bildirilir.
 *
 * Iki pencere tipi:
 *  1) "... OK tusuna bas" + OK butonu  -> OK'a bas
 *  2) "67 + 53 = ?" + Enter Answer + I'm -> cevabi hesapla, yaz, I'm'e bas
 */
object BotKontrol {
    private val okuyucu by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val islem = Regex("""(\d{1,4})\s*([+\-xX×*/÷])\s*(\d{1,4})\s*=""")

    sealed class Sonuc {
        object Yok : Sonuc()
        /** OK butonu (ekran koordinati) */
        class Ok(val x: Float, val y: Float) : Sonuc()
        /** Matematik sorusu: cevap, cevap kutusu ve I'm butonu (bulunamadiysa null) */
        class Soru(val cevap: Int, val kutu: FloatArray?, val im: FloatArray?) : Sonuc()
    }

    /** Islemi hesapla (+ - x / ; derin islem yok) */
    fun hesapla(metin: String): Int? {
        val m = islem.find(metin.replace("—", "-").replace("–", "-")) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[3].toInt()
        return when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "x", "X", "×", "*" -> a * b
            "/", "÷" -> if (b != 0) a / b else null
            else -> null
        }
    }

    /**
     * Ekranin ortasini oku. bolgeX/bolgeY: kirpilan bolgenin ekrandaki sol-ust kosesi,
     * olcek: kirpilan bitmap pikseli -> ekran pikseli carpani.
     */
    fun oku(bm: Bitmap, bolgeX: Int, bolgeY: Int, olcek: Float, sonuc: (Sonuc) -> Unit) {
        okuyucu.process(InputImage.fromBitmap(bm, 0))
            .addOnSuccessListener { t ->
                fun merkez(r: Rect?) = r?.let {
                    floatArrayOf(bolgeX + it.exactCenterX() * olcek, bolgeY + it.exactCenterY() * olcek)
                }
                var cevap: Int? = null
                var kutu: FloatArray? = null
                var im: FloatArray? = null
                var ok: FloatArray? = null
                for (blok in t.textBlocks) for (satir in blok.lines) {
                    val s = satir.text.trim()
                    if (cevap == null) cevap = hesapla(s)
                    val k = s.lowercase()
                    when {
                        k.contains("enter answer") || k.contains("answer") -> kutu = merkez(satir.boundingBox)
                        k == "i'm" || k == "i’m" || k == "im" || k == "i m" -> im = merkez(satir.boundingBox)
                        k == "ok" -> ok = merkez(satir.boundingBox)
                    }
                }
                val c = cevap
                val o = ok
                sonuc(
                    when {
                        c != null -> Sonuc.Soru(c, kutu, im)
                        o != null -> Sonuc.Ok(o[0], o[1])
                        else -> Sonuc.Yok
                    }
                )
            }
            .addOnFailureListener { sonuc(Sonuc.Yok) }
    }
}
