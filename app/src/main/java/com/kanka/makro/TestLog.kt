package com.kanka.makro

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Test / olay günlüğü.
 *
 * SADECE yönetimin admin panelinde "Test" yetkisi verdiği hesaplarda ve test modu açıkken çalışır
 * (MacroService bunu kontrol eder; bu sınıf yalnızca kayıt tutar).
 *
 * Ne kaydedilir: hangi aksiyon, ne zaman, neden; hata/uyarı; ağ durumu; durma nedeni; toparlanma süresi;
 * dakika başına basış sayısı. Hiçbir şey oyuna gönderilmez, sunucuya gitmez; yalnızca telefondaki dosyaya yazılır
 * ve kullanıcı isterse "Raporu paylaş" ile dışarı verilir.
 */
object TestLog {
    /** Oturum sürerken true */
    @Volatile var acik = false

    /** Servis olayları (servis başladı/durdu) oturum dışında da kaydedilsin diye ayrı bayrak */
    @Volatile var sistem = false

    private val kilit = Any()
    private val yazici = Executors.newSingleThreadExecutor()
    private val tarih = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var dosya: File? = null
    private const val DOSYA_MAX = 3_000_000L

    // Bellekte tutulan önemli olaylar (rutin eylemler hariç) ve ilk eylem örnekleri
    private val onemli = ArrayDeque<String>()
    private val eylemOrnek = ArrayList<String>()
    private const val ONEMLI_MAX = 4000

    // Oturum bilgisi
    private var oturumAd = ""
    private var basWall = 0L
    private var basReal = 0L
    private var ayarOzet = ""
    private var bittiNeden = ""
    private var sureBittiMs = 0L
    private val tipSayac = HashMap<String, Int>()
    private val katSayac = HashMap<String, Int>()
    private var eylemToplam = 0

    // Dakika istatistiği
    private var dkIndeks = -1
    private var dkTap = 0
    private var dkAtak = 0
    private var dkPot = 0
    private var dkKutu = 0
    private var dkKes0 = 0
    private var dkTop0 = 0
    private var dkPot0 = 0
    private var sonKesilen = 0
    private var sonToplanan = 0
    private var sonPotSay = 0
    private val dkSatir = ArrayList<String>()
    private val dkTapListe = ArrayList<Int>()

    /** Günlük dosyasını hazırla (uygulama dizininde, dışarıdan erişilemez) */
    fun hazirla(ctx: Context) {
        synchronized(kilit) {
            if (dosya == null) dosya = File(ctx.filesDir, "testlog.txt")
        }
    }

    private fun gecen(): String {
        val s = (SystemClock.elapsedRealtime() - basReal) / 1000
        return String.format(Locale.US, "+%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun dosyayaYaz(satir: String) {
        yazici.execute {
            try {
                val f = dosya ?: return@execute
                if (f.length() > DOSYA_MAX) {
                    val eski = File(f.parentFile, "testlog.old.txt")
                    eski.delete()
                    f.renameTo(eski)
                }
                FileOutputStream(f, true).use { it.write((satir + "\n").toByteArray()) }
            } catch (e: Exception) {
                // günlük hatası uygulamayı etkilemesin
            }
        }
    }

    /** Yeni test oturumu başlat */
    fun oturumBasla(ctx: Context, ayar: String) {
        hazirla(ctx)
        synchronized(kilit) {
            oturumAd = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            basWall = System.currentTimeMillis()
            basReal = SystemClock.elapsedRealtime()
            ayarOzet = ayar
            bittiNeden = ""
            sureBittiMs = 0L
            tipSayac.clear()
            katSayac.clear()
            eylemToplam = 0
            dkIndeks = -1
            dkTap = 0; dkAtak = 0; dkPot = 0; dkKutu = 0
            dkKes0 = 0; dkTop0 = 0; dkPot0 = 0
            sonKesilen = 0; sonToplanan = 0; sonPotSay = 0
            dkSatir.clear()
            dkTapListe.clear()
            eylemOrnek.clear()
        }
        acik = true
        olay("OTURUM_BASLADI", "test oturumu başladı", ayar)
    }

    /** Oturumu bitir; durma nedeni ve özet kaydedilir */
    fun oturumBitir(neden: String) {
        if (!acik) return
        synchronized(kilit) {
            bittiNeden = neden
            sureBittiMs = SystemClock.elapsedRealtime() - basReal
        }
        olay("OTURUM_BITTI", neden, ozetSatiri())
        acik = false
    }

    /** Olay kaydı: tip, neden, ayrıntı */
    fun olay(tip: String, neden: String = "", detay: String = "") {
        if (!acik && !sistem) return
        val satir = synchronized(kilit) {
            val s = tarih.format(Date()) + " | " + gecen() + " | " + tip + " | " + neden +
                (if (detay.isNotEmpty()) " | $detay" else "")
            tipSayac[tip] = (tipSayac[tip] ?: 0) + 1
            onemli.addLast(s)
            while (onemli.size > ONEMLI_MAX) onemli.removeFirst()
            s
        }
        dosyayaYaz(satir)
    }

    /**
     * Bir dokunuş kategorisi yapıldı (atak, hp, mp, minor, heal, buff, kutu...).
     * Her eylem sayılır; ayrıntılı satır sadece ilk 150 eylem ve her 100. eylem için yazılır
     * (24 saatlik testte günlük şişmesin diye); dakika başına basış sayısı dakika satırında verilir.
     */
    fun eylem(kat: String, ad: String, neden: String) {
        if (!acik) return
        val satir: String? = synchronized(kilit) {
            eylemToplam++
            katSayac[kat] = (katSayac[kat] ?: 0) + 1
            dkTap++
            when (kat) {
                "atak" -> dkAtak++
                "hp", "mp", "minor", "heal", "buff" -> dkPot++
                "kutu" -> dkKutu++
            }
            if (eylemToplam <= 150 || eylemToplam % 100 == 0) {
                val s = tarih.format(Date()) + " | " + gecen() + " | EYLEM | " + kat + " | " + ad +
                    (if (neden.isNotEmpty()) " | $neden" else "")
                if (eylemOrnek.size < 40) eylemOrnek.add(s)
                s
            } else null
        }
        if (satir != null) dosyayaYaz(satir)
    }

    /** Saniyede bir çağrılır: dakika sınırında dakika satırı yazar */
    fun tik(kesilen: Int, toplanan: Int, pot: Int) {
        if (!acik) return
        val satir: String? = synchronized(kilit) {
            sonKesilen = kesilen; sonToplanan = toplanan; sonPotSay = pot
            val dk = ((SystemClock.elapsedRealtime() - basReal) / 60_000L).toInt()
            if (dkIndeks < 0) {
                dkIndeks = dk
                dkKes0 = kesilen; dkTop0 = toplanan; dkPot0 = pot
                null
            } else if (dk > dkIndeks) {
                val s = String.format(
                    Locale.US,
                    "DK %d: basis=%d atak=%d pot/destek=%d kutu=%d | kesilen=+%d toplanan=+%d pot=+%d",
                    dkIndeks + 1, dkTap, dkAtak, dkPot, dkKutu,
                    kesilen - dkKes0, toplanan - dkTop0, pot - dkPot0
                )
                dkSatir.add(s)
                dkTapListe.add(dkTap)
                if (dkSatir.size > 3000) dkSatir.removeAt(0)
                dkIndeks = dk
                dkTap = 0; dkAtak = 0; dkPot = 0; dkKutu = 0
                dkKes0 = kesilen; dkTop0 = toplanan; dkPot0 = pot
                tarih.format(Date()) + " | " + gecen() + " | DAKIKA | " + s
            } else null
        }
        if (satir != null) dosyayaYaz(satir)
    }

    private fun ozetSatiri(): String = synchronized(kilit) {
        var min = Int.MAX_VALUE
        var max = 0
        var top = 0L
        for (v in dkTapListe) { if (v < min) min = v; if (v > max) max = v; top += v }
        val n = dkTapListe.size
        val sn = if (sureBittiMs > 0L) sureBittiMs / 1000 else (SystemClock.elapsedRealtime() - basReal) / 1000
        String.format(
            Locale.US,
            "sure=%ds eylem=%d kesilen=%d toplanan=%d pot=%d | dk_basis ort=%s min=%s max=%s (%d dk)",
            sn, eylemToplam, sonKesilen, sonToplanan, sonPotSay,
            if (n > 0) String.format(Locale.US, "%.1f", top.toDouble() / n) else "-",
            if (n > 0) min.toString() else "-", if (n > 0) max.toString() else "-", n
        )
    }

    /** Paylaşılacak/kopyalanacak rapor metni */
    fun rapor(surum: String): String {
        val sb = StringBuilder()
        synchronized(kilit) {
            sb.append("PROJEINDIR BOT - TEST RAPORU\n")
            sb.append("Sürüm: ").append(surum).append('\n')
            sb.append("Oturum: ").append(oturumAd).append('\n')
            sb.append("Başlangıç: ").append(if (basWall > 0) tarih.format(Date(basWall)) else "-").append('\n')
            sb.append("Ayarlar: ").append(ayarOzet).append('\n')
            sb.append("Durma nedeni: ").append(if (bittiNeden.isEmpty()) "(oturum sürüyor ya da kayıt yok)" else bittiNeden).append('\n')
        }
        sb.append("Özet: ").append(ozetSatiri()).append('\n')
        synchronized(kilit) {
            sb.append("\nOLAY SAYILARI:\n")
            for (k in tipSayac.keys.sorted()) sb.append("  ").append(k).append(": ").append(tipSayac[k]).append('\n')
            sb.append("EYLEM KATEGORİLERİ:\n")
            for (k in katSayac.keys.sorted()) sb.append("  ").append(k).append(": ").append(katSayac[k]).append('\n')

            // Saatlik özet
            if (dkTapListe.isNotEmpty()) {
                sb.append("\nSAATLİK BASIŞ ÖZETİ:\n")
                var i = 0
                var saat = 1
                while (i < dkTapListe.size) {
                    var toplam = 0
                    var say = 0
                    while (say < 60 && i < dkTapListe.size) { toplam += dkTapListe[i]; i++; say++ }
                    sb.append("  Saat ").append(saat).append(": ").append(toplam).append(" basış (")
                        .append(say).append(" dk)\n")
                    saat++
                }
            }
            sb.append("\nSON DAKİKALAR (en fazla 90):\n")
            val bas = if (dkSatir.size > 90) dkSatir.size - 90 else 0
            for (j in bas until dkSatir.size) sb.append(dkSatir[j]).append('\n')

            sb.append("\nÖNEMLİ OLAYLAR (rutin eylemler hariç, son 900):\n")
            val liste = ArrayList(onemli)
            val b2 = if (liste.size > 900) liste.size - 900 else 0
            for (j in b2 until liste.size) sb.append(liste[j]).append('\n')

            if (eylemOrnek.isNotEmpty()) {
                sb.append("\nİLK EYLEM ÖRNEKLERİ:\n")
                for (e in eylemOrnek) sb.append(e).append('\n')
            }
        }
        val s = sb.toString()
        return if (s.length > 120_000) s.substring(s.length - 120_000) else s
    }

    /** Günlüğü sil */
    fun temizle() {
        synchronized(kilit) {
            onemli.clear()
            eylemOrnek.clear()
            dkSatir.clear()
            dkTapListe.clear()
            tipSayac.clear()
            katSayac.clear()
            eylemToplam = 0
        }
        yazici.execute {
            try {
                dosya?.delete()
                File(dosya?.parentFile, "testlog.old.txt").delete()
            } catch (e: Exception) {
            }
        }
    }
}
