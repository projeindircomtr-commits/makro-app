package com.kanka.makro

import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Hedef barinin ustundeki mob ismini okur (cihaz ici OCR).
 * Arka planda surekli calisir; motor sadece son sonuca bakar, beklemez.
 */
class MobNameReader {

    enum class Durum { BILINMIYOR, UYGUN, DEGIL }

    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    var lastText = ""
        private set

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var busy = false

    @Volatile
    private var gen = 0

    private var lastReq = 0L
    private var notBefore = 0L

    /** Hedef degisti: eski okumayi gecersiz say, ekran guncellensin diye kisa bekle */
    fun invalidate(now: Long, waitMs: Long = 250) {
        gen++
        lastAt = 0L
        lastText = ""
        notBefore = now + waitMs
    }

    /** Gerekirse yeni okuma baslat (kendi kendine sinirlanir) */
    fun request(now: Long, rect: IntArray) {
        if (busy || now < notBefore || now - lastReq < 300) return
        val bmp = ScreenSampler.cropBitmap(rect[0], rect[1], rect[2], rect[3], 3) ?: return
        lastReq = now
        busy = true
        val myGen = gen
        val shotAt = SystemClock.uptimeMillis()
        try {
            rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    if (myGen == gen) {
                        lastText = t.text.replace('\n', ' ').trim()
                        lastAt = shotAt
                    }
                }
                .addOnCompleteListener { busy = false }
        } catch (e: Exception) {
            busy = false
        }
    }

    fun durum(now: Long, filtre: List<String>): Durum {
        if (lastAt == 0L || now - lastAt > 2000) return Durum.BILINMIYOR
        return if (eslesir(lastText, filtre)) Durum.UYGUN else Durum.DEGIL
    }

    fun close() {
        try {
            rec.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun liste(s: String): List<String> =
            s.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** OCR hatalarina toleransli karsilastirma */
        fun eslesir(text: String, filtre: List<String>): Boolean {
            val t = norm(text)
            if (t.isEmpty()) return false
            for (f in filtre) {
                val n = norm(f)
                if (n.isEmpty()) continue
                if (t.contains(n)) return true
                if (n.contains(t) && t.length >= n.length - 1) return true
                if (lev(t, n) <= maxOf(1, n.length / 5)) return true
            }
            return false
        }

        private fun lev(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(
                        dp[j] + 1,
                        dp[j - 1] + 1,
                        prev + if (a[i - 1] == b[j - 1]) 0 else 1
                    )
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
