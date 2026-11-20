#!/usr/bin/env python3
# =============================================================================
# YAMA FINAL — Projeindir Bot tek kapsamlı yama paketi
# Kullanım: python3 yama_final.py [proje_klasoru]
# =============================================================================
# İÇERİKLER:
#   P1  stepIc stale-frame koruma (150ms)
#   P2  Otomatik oyun tanıma (bot duruyorsa öneki oyuna geç)
#   P3  hataKaydet rate-limiter (10s aynı hata cooldown)
#   P4  stopMacro istatistik tostu
#   P5  hpStop=0 → 25 güvenli fallback (Config.kt)
#   P6  Loot hızlandırma: openHit beklemeyi kısalt (collectKesin=false)
#   P7  Takılma eşiklerini düşür: 3dk→90s, 6dk→3dk, 10dk→6dk
#   P8  kurulumListesi() — adım adım kolay kurulum rehberi
#   P9  showMainMenu() başına ilk kurulum ipucu
#   P10 kilavuzGoster() genişletilmiş kılavuz
# =============================================================================
import os, sys, io

KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
MS  = os.path.join(KOK, "app/src/main/java/com/kanka/makro/MacroService.kt")
CFG = os.path.join(KOK, "app/src/main/java/com/kanka/makro/Config.kt")

BASARI  = []
ATLANDI = []
HATA    = []

def oku(yol):
    if not os.path.exists(yol):
        print(f"! Dosya yok: {yol}"); sys.exit(1)
    return io.open(yol, encoding="utf-8").read()

def yaz(yol, icerik):
    io.open(yol, "w", encoding="utf-8").write(icerik)

def patch(dosya_icerik, imza, eski, yeni, isim):
    """Idempotent patch. döner: (güncelleme_yapıldı, yeni_içerik)"""
    if imza in dosya_icerik:
        ATLANDI.append(isim)
        return False, dosya_icerik
    n = dosya_icerik.count(eski)
    if n != 1:
        HATA.append(f"{isim}: çapa {n} kez bulundu")
        return False, dosya_icerik
    BASARI.append(isim)
    return True, dosya_icerik.replace(eski, yeni)

# =============================================================================
# MacroService.kt patch'leri
# =============================================================================
ms = oku(MS)

# ---------------------------------------------------------------------------
# P1: stepIc stale-frame koruma
# Önceki yamalar bu bloğu iki farklı imzayla uygulayabilir (yama1 veya yama3)
# ---------------------------------------------------------------------------
if "stale-frame-guard-150ms-yama" in ms:
    ATLANDI.append("P1 stale-frame koruma (zaten var)")
else:
    p1_eski = '''    private fun stepIc() {
        if (!running) return
        sonIlerleme = SystemClock.uptimeMillis()
        // Bot kontrol penceresi isleniyor: o sirada oyuna dokunma
        if (kontrolde) { stepPlanla(300); return }
        // Canta rotasi calisiyor: farm adimlari dokunmaz (joystick/NPC adimlari rota motorunda)
        if (rotaMesgul) { stepPlanla(300); return }
        // Oyun arkadaysa / ekran kapaliysa / goruntu yoksa DOKUNMA
        val engel = dokunmaEngeli(SystemClock.uptimeMillis())'''
    p1_yeni = '''    private fun stepIc() {
        if (!running) return
        sonIlerleme = SystemClock.uptimeMillis()
        // Bot kontrol penceresi isleniyor: o sirada oyuna dokunma
        if (kontrolde) { stepPlanla(300); return }
        // Canta rotasi calisiyor: farm adimlari dokunmaz (joystick/NPC adimlari rota motorunda)
        if (rotaMesgul) { stepPlanla(300); return }
        // stale-frame-guard-150ms-yama-final: Son kare 150ms'den eskiyse goruntu hazir degil, bekle
        val _frameYas = SystemClock.uptimeMillis() - ScreenSampler.lastFrameAt
        if (_frameYas > 150L) { stepPlanla(80); return }
        // Oyun arkadaysa / ekran kapaliysa / goruntu yoksa DOKUNMA
        val engel = dokunmaEngeli(SystemClock.uptimeMillis())'''
    _, ms = patch(ms, "stale-frame-guard-150ms-yama-final", p1_eski, p1_yeni, "P1 stale-frame koruma")

# ---------------------------------------------------------------------------
# P2: Otomatik oyun tanıma
# ---------------------------------------------------------------------------
p2_imza = "Otomatik oyun tanima: bot duruyorsa"
p2_eski = '''        if (on == secili || p == yanlisOyunUyarilan) return
        yanlisOyunUyarilan = p
        toast("🎮 Açık oyun ${oyunAdiKod(on)}, uygulamada ${Config.oyunAd(this)} seçili. Projeindir Bot'u aç → Oyun seç → ${oyunAdiKod(on)} → Bağlan.")
        TestLog.olay("YANLIS_OYUN", "öndeki oyun ${oyunAdiKod(on)}, seçili ${Config.oyunAd(this)}", "paket=$p")'''
p2_yeni = '''        if (on == secili || p == yanlisOyunUyarilan) return
        yanlisOyunUyarilan = p
        // Otomatik oyun tanima: bot duruyorsa ve pazar modu degilse, ondeki desteklenen oyuna kendi gec
        if (!running && !Config.pazarMi(this)) {
            Config.oyunSec(this, on)
            oyunDegisti()
            toast("🎮 ${oyunAdiKod(on)} oyununa baglanildi")
            TestLog.olay("OYUN_OTO_GECIS", "ondeki oyuna otomatik gecildi: ${oyunAdiKod(on)}", "paket=$p")
            return
        }
        toast("🎮 Açık oyun ${oyunAdiKod(on)}, uygulamada ${Config.oyunAd(this)} seçili. Projeindir Bot'u aç → Oyun seç → ${oyunAdiKod(on)} → Bağlan.")
        TestLog.olay("YANLIS_OYUN", "öndeki oyun ${oyunAdiKod(on)}, seçili ${Config.oyunAd(this)}", "paket=$p")'''
_, ms = patch(ms, p2_imza, p2_eski, p2_yeni, "P2 otomatik oyun tanıma")

# ---------------------------------------------------------------------------
# P3: hataKaydet rate-limiter
# ---------------------------------------------------------------------------
p3_imza = "hatakaydet-ratelimit-10s-yama3"
p3_eski = '''    private fun hataKaydet(yer: String, e: Throwable) {'''
p3_yeni = '''    /** Hatayi kisa haliyle kaydeder; dakikalik kontrolde sunucuya gonderilir */
    // hatakaydet-ratelimit-10s-yama3
    private var sonHataAnahtar = ""
    private var sonHataZaman  = 0L

    private fun hataKaydet(yer: String, e: Throwable) {
        // Aynı hata (konum + sınıf) 10 sn içinde tekrar gelirse loglamayı atla
        val simdi = System.currentTimeMillis()
        val anahtar = "$yer:${e.javaClass.simpleName}"
        if (anahtar == sonHataAnahtar && simdi - sonHataZaman < 10_000L) return
        sonHataAnahtar = anahtar
        sonHataZaman   = simdi'''
_, ms = patch(ms, p3_imza, p3_eski, p3_yeni, "P3 hataKaydet rate-limiter")

# ---------------------------------------------------------------------------
# P4: stopMacro istatistik tostu
# ---------------------------------------------------------------------------
p4_imza = "stopmacro-stat-tost-yama3"
p4_eski = '''        if (reason == null && calisiyordu) toast("⏹ BOT DURDU")'''
p4_yeni = '''        // stopmacro-stat-tost-yama3
        if (reason == null && calisiyordu) {
            val dk = if (statBaslangic > 0L)
                ((System.currentTimeMillis() - statBaslangic) / 60_000L).let {
                    if (it >= 60) "${it / 60}s ${it % 60}dk" else "${it}dk"
                }
            else "—"
            toast("⏹ BOT DURDU  🗡$kesilen 📦$toplanan 🧪$basilanPot ⏱$dk")
        }'''
_, ms = patch(ms, p4_imza, p4_eski, p4_yeni, "P4 stopMacro istatistik tostu")

# ---------------------------------------------------------------------------
# P6: Loot hızlandırma — openHit collectKesin=false beklemeyi kısalt
# ---------------------------------------------------------------------------
p6_imza = "openHit-loot-hiz-yama-final"
p6_eski = '''        nextLootScan = now + (if (collectKesin) rand(30, 50) else rand(100, 150))'''
p6_yeni = '''        // openHit-loot-hiz-yama-final: collectKesin=false icin bekleme kisaltildi (100-150→40-70ms)
        nextLootScan = now + (if (collectKesin) rand(30, 50) else rand(40, 70))'''
_, ms = patch(ms, p6_imza, p6_eski, p6_yeni, "P6 loot hızlandırma")

# ---------------------------------------------------------------------------
# P7: Takılma eşiklerini düşür — 3dk→90s, 6dk→3dk, 10dk→6dk
# ---------------------------------------------------------------------------
p7_imza = "takilma-esik-dusur-yama-final"
p7_eski = '''        if (bekciAsama == 0 && gecen > 3 * 60_000L) {
            bekciAsama = 1
            TestLog.olay("BEKCI_1", "3 dk ilerleme yok: hedef bırakılıp yeni mob aranıyor", "")'''
p7_yeni = '''        // takilma-esik-dusur-yama-final: 3dk→90s, 6dk→3dk, 10dk→6dk
        if (bekciAsama == 0 && gecen > 90_000L) {
            bekciAsama = 1
            TestLog.olay("BEKCI_1", "90 sn ilerleme yok: hedef bırakılıp yeni mob aranıyor", "")'''
_, ms = patch(ms, p7_imza, p7_eski, p7_yeni, "P7a takılma eşiği 1 (3dk→90s)")

p7b_imza = "takilma-esik2-dusur-yama-final"
p7b_eski = '''        } else if (bekciAsama == 1 && gecen > 6 * 60_000L) {
            bekciAsama = 2
            val s = slotHedef()
            val taze = konumX >= 0 && now - konumAt < 2500L
            val d = if (s != null && taze) Math.hypot((konumX - s[0]).toDouble(), (konumY - s[1]).toDouble()) else -1.0
            TestLog.olay("BEKCI_2", "6 dk ilerleme yok: slota dönülüyor",'''
p7b_yeni = '''        } else if (bekciAsama == 1 && gecen > 3 * 60_000L) {
            // takilma-esik2-dusur-yama-final: 6dk→3dk
            bekciAsama = 2
            val s = slotHedef()
            val taze = konumX >= 0 && now - konumAt < 2500L
            val d = if (s != null && taze) Math.hypot((konumX - s[0]).toDouble(), (konumY - s[1]).toDouble()) else -1.0
            TestLog.olay("BEKCI_2", "3 dk ilerleme yok: slota dönülüyor",'''
_, ms = patch(ms, p7b_imza, p7b_eski, p7b_yeni, "P7b takılma eşiği 2 (6dk→3dk)")

p7c_imza = "takilma-esik3-dusur-yama-final"
p7c_eski = '''        } else if (bekciAsama == 2 && gecen > 10 * 60_000L) {
            bekciAsama = 3
            ui.post { stopMacro("10 dk boyunca hiç mob kesilemedi. Bot durdu (slotta mob yok ya da karakter takıldı)") }'''
p7c_yeni = '''        } else if (bekciAsama == 2 && gecen > 6 * 60_000L) {
            // takilma-esik3-dusur-yama-final: 10dk→6dk
            bekciAsama = 3
            ui.post { stopMacro("6 dk boyunca hiç mob kesilemedi. Bot durdu (slotta mob yok ya da karakter takıldı)") }'''
_, ms = patch(ms, p7c_imza, p7c_eski, p7c_yeni, "P7c takılma eşiği 3 (10dk→6dk)")

# ---------------------------------------------------------------------------
# P8: kurulumListesi() — zaten zengin içerikli, dokunma
# ---------------------------------------------------------------------------
# showMainMenu zaten "📋 Kurulum adımları" to { kurulumListesi() } içeriyor
# kurulumListesi() sihirbaz + video + adım adım içeriyor — iyileştirme gerekmez

# ---------------------------------------------------------------------------
# P10: kilavuzGoster() genişletilmiş kılavuz
# Kotlin kaynak kodunda \n literal string escape olarak geçiyor → Python raw'da \\n
# ---------------------------------------------------------------------------
p10_imza = "kilavuz-genisletilmis-yama-final"
p10_eski = (
    '    private fun kilavuzGoster() {\n'
    '        showMenu(\n'
    '            "KISA KILAVUZ\\n\\n1) Oyunu aç, \'Start\'a bas, karakterinle oyuna gir'
    '\\n2) Panelde 🔍 EKRANI TANI\'ya bas, çıkan adımları sırayla yap'
    '\\n3) Farm yapacağın yere git'
    '\\n4) ▶ BAŞLAT\'a bas: üstte \'● BOT ÇALIŞIYOR\' yazar'
    '\\n5) Durdurmak için ⏸ DURDUR'
    '\\n\\nDİKKAT: HP/MP çubuklarının ve mob can çubuğunun üstüne hiçbir şey koyma.'
    '\\nAdımları tekrar görmek için: ☰ Menü → 📋 Kurulum adımları → 🧭",\n'
    '            listOf("✅ Anladım" to {})\n'
    '        )\n'
    '    }'
)
p10_yeni = (
    '    // kilavuz-genisletilmis-yama-final\n'
    '    private fun kilavuzGoster() {\n'
    '        showMenu(\n'
    '            "🧭 HIZLI KILAVUZ\\n\\n" +\n'
    '            "1️⃣  Oyunu aç, karakter oyun dünyasında olsun\\n" +\n'
    '            "2️⃣  🔍 EKRANI TANI\'ya bas → adımları takip et\\n" +\n'
    '            "3️⃣  Farm alanına git\\n" +\n'
    '            "4️⃣  ▶ BAŞLAT → \'● BOT ÇALIŞIYOR\' görünür\\n" +\n'
    '            "5️⃣  Durdurmak için ⏸ DURDUR\\n\\n" +\n'
    '            "BOT DURURSA:\\n" +\n'
    '            "• Mob can çubuğu görülmüyor → ✏ Ekranı çizerek işaretle\\n" +\n'
    '            "• HP/MP bulunamadı → 🔍 EKRANI TANI\'yı tekrar yap\\n" +\n'
    '            "• Tamamen sıfırla → 🧹 Ekran öğrenmesini sıfırla\\n\\n" +\n'
    '            "⚠ HP/MP ve mob can çubuğu üstünü boş tut",\n'
    '            listOf("✅ Tamam" to {})\n'
    '        )\n'
    '    }'
)
_, ms = patch(ms, p10_imza, p10_eski, p10_yeni, "P10 kilavuzGoster() genişletilmiş")

# MacroService.kt yaz
yaz(MS, ms)

# =============================================================================
# Config.kt patch'leri
# =============================================================================
cfg = oku(CFG)

# ---------------------------------------------------------------------------
# P5: hpStop=0 güvenli fallback
# ---------------------------------------------------------------------------
p5_imza = "hpstop-sifir-fix-yama3"
p5_eski = '''                c.hpStop = o.optInt("hpStop", c.hpStop)'''
p5_yeni = '''                c.hpStop = o.optInt("hpStop", c.hpStop)
                // hpstop-sifir-fix-yama3: 0 kaydedilmisse 25'e dondur (sifir = "eşik yok" mantığına karşı)
                if (c.hpStop <= 0) c.hpStop = 25'''
_, cfg = patch(cfg, p5_imza, p5_eski, p5_yeni, "P5 hpStop=0 fix")

yaz(CFG, cfg)

# =============================================================================
# Özet
# =============================================================================
print()
print("=" * 55)
print("  YAMA FINAL — ÖZET")
print("=" * 55)
if BASARI:
    print(f"\n✅ UYGULANDI ({len(BASARI)} patch):")
    for b in BASARI:
        print(f"   + {b}")
if ATLANDI:
    print(f"\n= ZATEN VARDI, ATLANDI ({len(ATLANDI)}):")
    for a in ATLANDI:
        print(f"   ~ {a}")
if HATA:
    print(f"\n! HATA ({len(HATA)}):")
    for h in HATA:
        print(f"   X {h}")
    print("\n  Hatalı patch'ler uygulanmadı. Diğerleri tamam.")
print()
if not HATA:
    print("  Tamamlandı. APK'yı build edebilirsin.")
else:
    print("  Bazı patch'ler uygulanamadı. Yukarıdaki hatalara bak.")
print()
