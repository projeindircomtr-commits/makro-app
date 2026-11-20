#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
yama3_rapor_ekran.py
====================
İki patch:

P1 — Oturum raporu (harici dosyaya)
    TestLog.kt : context saklama + hariciRaporYaz() fonksiyonu
    MacroService.kt : oturumKapat() içinde hariciRaporYaz() çağrısı

P2 — Çoklu ekran boyutu bildirimi
    MacroService.kt : bot başlarken ekran + ölçek bilgisini Toast ile göster

Kullanım (proje kök dizininden çalıştır):
  python3 yama3_rapor_ekran.py

İdempotent: her patch zaten uygulanmışsa "zaten var" yazar, tekrar uygulamaz.
"""

import os, sys

# ─── Dosya yolları ───────────────────────────────────────────────────────────
BASE = os.path.dirname(os.path.abspath(__file__))
TESTLOG    = os.path.join(BASE, "app/src/main/java/com/kanka/makro/TestLog.kt")
SERVICE    = os.path.join(BASE, "app/src/main/java/com/kanka/makro/MacroService.kt")

def oku(yol):
    with open(yol, encoding="utf-8") as f:
        return f.read()

def yaz(yol, icerik):
    with open(yol, "w", encoding="utf-8") as f:
        f.write(icerik)

def patch(dosya_adi, yol, imza, eski, yeni, aciklama):
    icerik = oku(yol)
    if imza in icerik:
        print(f"  [ATLA] {dosya_adi} — {aciklama} zaten var")
        return
    if eski not in icerik:
        print(f"  [HATA] {dosya_adi} — {aciklama}: hedef metin bulunamadı")
        print(f"         Aranan:\n{eski[:120]!r}")
        sys.exit(1)
    yaz(yol, icerik.replace(eski, yeni, 1))
    print(f"  [OK]   {dosya_adi} — {aciklama}")

# ─────────────────────────────────────────────────────────────────────────────
# P1a: TestLog.kt — context saklama + hariciRaporYaz()
# ─────────────────────────────────────────────────────────────────────────────
P1A_IMZA = "// p1a-harici-rapor-yama3"

P1A_ESKI = """\
    /** Günlük dosyasını hazırla (uygulama dizininde, dışarıdan erişilemez) */
    fun hazirla(ctx: Context) {
        synchronized(kilit) {
            if (dosya == null) dosya = File(ctx.filesDir, "testlog.txt")
        }
    }"""

P1A_YENI = """\
    /** Günlük dosyasını hazırla (uygulama dizininde, dışarıdan erişilemez) */
    fun hazirla(ctx: Context) {
        synchronized(kilit) {
            if (dosya == null) dosya = File(ctx.filesDir, "testlog.txt")
            if (ctxRef == null) ctxRef = ctx.applicationContext
        }
    }

    // p1a-harici-rapor-yama3
    @Volatile private var ctxRef: android.content.Context? = null

    /**
     * Oturum bittiğinde özet raporu harici depolamaya yazar.
     * Dosya: /sdcard/Android/data/<paket>/files/projeindir_rapor_TARIH.txt
     * İzin gerektirmez (getExternalFilesDir — kendi uygulama klasörü).
     * Dosya yöneticisinden "Android/data/com.kanka.makro/files/" altında görünür.
     */
    fun hariciRaporYaz(surum: String) {
        val ctx = ctxRef ?: return
        yazici.execute {
            try {
                val dir = ctx.getExternalFilesDir(null) ?: return@execute
                dir.mkdirs()
                val ad = "projeindir_rapor_${
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                }.txt"
                val dosyaHarici = java.io.File(dir, ad)
                dosyaHarici.writeText(rapor(surum), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }"""

patch("TestLog.kt", TESTLOG, P1A_IMZA, P1A_ESKI, P1A_YENI,
      "P1a — context saklama + hariciRaporYaz()")

# ─────────────────────────────────────────────────────────────────────────────
# P1b: MacroService.kt — oturumKapat() içinde hariciRaporYaz() çağrısı
# ─────────────────────────────────────────────────────────────────────────────
P1B_IMZA = "// p1b-harici-rapor-cagri-yama3"

P1B_ESKI = """\
    private fun oturumKapat(reason: String?) {
        agIzlemeDurdur()
        if (testCalisiyor) {
            TestLog.oturumBitir(reason ?: "kullanıcı durdurdu")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", false).apply()
        }
        testCalisiyor = false
    }"""

P1B_YENI = """\
    private fun oturumKapat(reason: String?) {
        agIzlemeDurdur()
        if (testCalisiyor) {
            TestLog.oturumBitir(reason ?: "kullanıcı durdurdu")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", false).apply()
        }
        // p1b-harici-rapor-cagri-yama3 — test modu açık olmasa da bot çalıştıysa rapor yaz
        if (TestLog.acik) TestLog.oturumBitir(reason ?: "kullanıcı durdurdu")
        TestLog.hariciRaporYaz(surumAdi())
        testCalisiyor = false
    }"""

patch("MacroService.kt", SERVICE, P1B_IMZA, P1B_ESKI, P1B_YENI,
      "P1b — oturumKapat() hariciRaporYaz çağrısı")

# ─────────────────────────────────────────────────────────────────────────────
# P1c: MacroService.kt — bot başlarken (oturumBaslat) oturumBasla'yı her zaman çağır
# Şu an yalnızca testCalisiyor=true olduğunda çağrılıyor.
# Bunu her bot başlangıcında aktif edelim.
# ─────────────────────────────────────────────────────────────────────────────
P1C_IMZA = "// p1c-oturum-her-zaman-yama3"

P1C_ESKI = """\
        if (testCalisiyor) {
            TestLog.oturumBasla(this, testAyarOzeti())
            agIzlemeBasla()
            toast("🏙 Çanta döngüsü açık: olaylar kaydediliyor")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", true).apply()
        }
    }"""

P1C_YENI = """\
        // p1c-oturum-her-zaman-yama3 — TestLog oturumu her bot çalışmasında başlasın (rapor için)
        if (!TestLog.acik) TestLog.oturumBasla(this, testAyarOzeti())
        if (testCalisiyor) {
            agIzlemeBasla()
            toast("🏙 Çanta döngüsü açık: olaylar kaydediliyor")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", true).apply()
        }
    }"""

patch("MacroService.kt", SERVICE, P1C_IMZA, P1C_ESKI, P1C_YENI,
      "P1c — TestLog.oturumBasla her bot başlangıcında")

# ─────────────────────────────────────────────────────────────────────────────
# P2: MacroService.kt — bot başlarken ekran + ölçek bilgisini Toast ile göster
# (çoklu ekran boyutu için kullanıcıya hangi profile girdiğini bildir)
# ─────────────────────────────────────────────────────────────────────────────
P2_IMZA = "// p2-ekran-bilgi-toast-yama3"

# Mevcut bot başlangıcındaki toast'ı buluyoruz — 4732. satır civarı
# "testCalisiyor = Lisans.gecerliSimdi() && ..." satırının hemen ardına ekle
P2_ESKI = """\
        testCalisiyor = Lisans.gecerliSimdi() && (cfg.townAcik || (Lisans.testHesabi() && cfg.testModu))
        if (testCalisiyor) testAyarlariUygula()"""

P2_YENI = """\
        testCalisiyor = Lisans.gecerliSimdi() && (cfg.townAcik || (Lisans.testHesabi() && cfg.testModu))
        if (testCalisiyor) testAyarlariUygula()
        // p2-ekran-bilgi-toast-yama3 — farklı cihazlarda hangi ekran profilinin kullanıldığını göster
        run {
            val olcekStr = if (uOlc > 0f) "%.2f(ölç)".format(uOlc) else "%.2f(tahmini)".format(uiSEski())
            toast("📱 ${screenW}x${screenH} · ölçek=$olcekStr · profil=${ekranAnahtar()}")
        }"""

patch("MacroService.kt", SERVICE, P2_IMZA, P2_ESKI, P2_YENI,
      "P2 — ekran + ölçek bilgi Toast'ı")

# ─────────────────────────────────────────────────────────────────────────────
print("\n✅ Tüm yamalar tamamlandı.")
print("   Rapor klasörü: Android/data/com.kanka.makro/files/")
print("   (Dosya yöneticisinde 'Dahili Depolama > Android > data > com.kanka.makro > files' altında)")
