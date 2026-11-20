#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YAMA2_FINAL — Pazar kaldır + Upgrade panelden + Menü temizliği + Rota open fix
Çalıştır: python3 yama2_final.py

Yamalar (idempotent — iki kez çalıştırılabilir):
  P1: Menüden "Ekranı çizerek işaretle" kaldır
  P2: Kılavuz metninden "Ekranı çizerek işaretle" satırını kaldır
  P3: Upgrade Bot → sadece Lisans.ozellikVar("upgrade") olanlara göster
  P4: Pazar Bot modMenu()'den kaldır (botGorunur"pazar" false yapıldı)
  P5: rotaMenuAc() — Open basıldıktan sonra fazla tap() kaldır
      (rotaOpenAra zaten Open'a basıyor, rotaMenuAc tekrar basmasın)
"""

import re, sys, shutil
from pathlib import Path

BASE = Path(__file__).parent
KT   = BASE / "app/src/main/java/com/kanka/makro/MacroService.kt"
CFG  = BASE / "app/src/main/java/com/kanka/makro/Config.kt"

def oku(p: Path) -> str:
    return p.read_text(encoding="utf-8")

def yaz(p: Path, icerik: str):
    p.write_text(icerik, encoding="utf-8")

def yama(dosya: Path, imza: str, eski: str, yeni: str, aciklama: str) -> bool:
    """İmza zaten varsa atla. Yoksa eski→yeni değiştir."""
    icerik = oku(dosya)
    if imza in icerik:
        print(f"  ✓ zaten var, atlandı: {aciklama}")
        return False
    sayi = icerik.count(eski)
    if sayi == 0:
        print(f"  ✗ ANCHOR BULUNAMADI ({sayi} kez): {aciklama}")
        print(f"    Aranan:\n{repr(eski[:120])}")
        return False
    if sayi > 1:
        print(f"  ⚠ Birden fazla eşleşme ({sayi}), ilk oluşum değiştiriliyor: {aciklama}")
    yaz(dosya, icerik.replace(eski, yeni, 1))
    print(f"  ✅ uygulandı: {aciklama}")
    return True

results = []

# ─── P1: Menüden "Ekranı çizerek işaretle" kaldır ───────────────────────────
# Satır: "✏ Ekranı çizerek işaretle" to { ... cizimEditoru() },
IMZA_P1 = "// p1-cizim-menu-kaldirildi-yama2final"
ESKI_P1 = (
    '                "✏ Ekranı çizerek işaretle" to { if (Config.koMu(this)) toast("KO\'da gerekmiyor: barları bot kendisi buluyor") else cizimEditoru() },\n'
)
YENI_P1 = (
    '                // p1-cizim-menu-kaldirildi-yama2final\n'
)
results.append(yama(KT, IMZA_P1, ESKI_P1, YENI_P1, "P1: Menüden çizim editörü kaldırıldı"))

# ─── P2: Kılavuz metninden "Ekranı çizerek işaretle" satırını kaldır ─────────
IMZA_P2 = "// p2-kilavuz-cizim-satirlari-kaldirildi-yama2final"
ESKI_P2 = (
    '"• Mob can çubuğu görülmüyor → ✏ Ekranı çizerek işaretle\\n" +\n'
)
YENI_P2 = (
    '// p2-kilavuz-cizim-satirlari-kaldirildi-yama2final\n'
)
results.append(yama(KT, IMZA_P2, ESKI_P2, YENI_P2, "P2: Kılavuzdan çizim satırı kaldırıldı"))

# ─── P3: testListesi — Upgrade Bot koşullu göster ────────────────────────────
# Mevcut:
#   listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }, "⚒ Upgrade Bot" to { upgradeMenu() })
# Yeni: Upgrade Bot sadece Lisans.ozellikVar("upgrade") olanlara
IMZA_P3 = "// p3-upgrade-bot-lisansli-yama2final"
ESKI_P3 = (
    '            listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }, "⚒ Upgrade Bot" to { upgradeMenu() })'
)
YENI_P3 = (
    '            listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }) +\n'
    '            (if (Lisans.ozellikVar("upgrade")) listOf<Pair<String, () -> Unit>>("⚒ Upgrade Bot" to { upgradeMenu() }) else emptyList()) // p3-upgrade-bot-lisansli-yama2final'
)
results.append(yama(KT, IMZA_P3, ESKI_P3, YENI_P3, "P3: Upgrade Bot lisanslılara gösterildi"))

# ─── P4: modMenu() — Pazar Bot satırlarını kaldır ────────────────────────────
# pazarAcik satırı ve if(pazarAcik) bloğunu kaldır
IMZA_P4 = "// p4-pazar-mod-menu-kaldirildi-yama2final"
ESKI_P4 = (
    '        val pazarAcik = Config.botGorunur("pazar") && !Config.revMi(this) && !Config.nowaMi(this)   // RevengerOnline\'da Pazar yok\n'
    '        val genieAcik = Config.botGorunur("genie")\n'
    '        val say = listOf(farmAcik, pkAcik, pazarAcik, genieAcik).count { it }'
)
YENI_P4 = (
    '        // p4-pazar-mod-menu-kaldirildi-yama2final\n'
    '        val genieAcik = Config.botGorunur("genie")\n'
    '        val say = listOf(farmAcik, pkAcik, genieAcik).count { it }'
)
results.append(yama(KT, IMZA_P4, ESKI_P4, YENI_P4, "P4a: pazarAcik satırı ve say listesinden kaldırıldı"))

# P4b: if (pazarAcik) bloğunu kaldır
IMZA_P4B = "// p4b-pazar-liste-kaldirildi-yama2final"
ESKI_P4B = (
    '        if (pazarAcik) liste.add((if (Config.pazarMi(this)) "✓ " else "") + "🏪 Pazar Bot" to {\n'
    '            if (running) stopMacro()\n'
    '            Config.pazarSec(this)\n'
    '            modBtn?.text = modYazi()\n'
    '            statusTv?.text = "🏪 Pazar hazır"\n'
    '            pazarKarti()\n'
    '        })\n'
)
YENI_P4B = '        // p4b-pazar-liste-kaldirildi-yama2final\n'
results.append(yama(KT, IMZA_P4B, ESKI_P4B, YENI_P4B, "P4b: Pazar Bot menü seçeneği kaldırıldı"))

# ─── P5: rotaMenuAc() — Open zaten basıldı, fazla tap kaldır ─────────────────
# rotaOpenAra() tap() ile Open'a basıp rotaMenuAc()'i çağırıyor.
# rotaMenuAc() şimdi tekrar tap(uiX(2320f)...) yapıyor — bu fazla.
# Çözüm: rotaMenuAc() direkt rotaPencereBekle'ye gitsin, tap etmesin.
IMZA_P5 = "// p5-rota-menu-ac-cift-tap-duzeltildi-yama2final"
ESKI_P5 = (
    '        if (Config.revMi(this) && bankaPencereVar()) {\n'
    '            // Rev: Open doğrudan Warehouse açmış olabilir, menü dokunuşu gereksiz\n'
    '            rh.postDelayed({ rotaPencereBekle(0) }, 100)\n'
    '            return\n'
    '        }\n'
    '        tap(uiX(2320f), uiYOrta(485f), 0f) { rh.postDelayed({ rotaPencereBekle(0) }, 600) }'
)
YENI_P5 = (
    '        // p5-rota-menu-ac-cift-tap-duzeltildi-yama2final\n'
    '        // rotaOpenAra() zaten Open\'a bastı; burada tekrar basma, sadece pencereyi bekle\n'
    '        rh.postDelayed({ rotaPencereBekle(0) }, 800)'
)
results.append(yama(KT, IMZA_P5, ESKI_P5, YENI_P5, "P5: rotaMenuAc çift tap düzeltildi"))

# ─── Config.kt: botGorunur("pazar") → false ──────────────────────────────────
IMZA_CFG = "// p4c-pazar-gorunur-false-yama2final"
ESKI_CFG = (
    '        fun botGorunur(kod: String): Boolean = when (kod) {\n'
    '            "farm" -> true\n'
    '            "pk", "genie" -> false\n'
    '            else -> Lisans.ozellikVar(kod)\n'
    '        }'
)
YENI_CFG = (
    '        fun botGorunur(kod: String): Boolean = when (kod) {\n'
    '            "farm" -> true\n'
    '            "pk", "genie", "pazar" -> false // p4c-pazar-gorunur-false-yama2final\n'
    '            else -> Lisans.ozellikVar(kod)\n'
    '        }'
)
results.append(yama(CFG, IMZA_CFG, ESKI_CFG, YENI_CFG, "P4c: Config.kt botGorunur pazar=false"))

# ─── Özet ────────────────────────────────────────────────────────────────────
print()
uygulanan = sum(1 for r in results if r)
print(f"{'─'*50}")
print(f"Toplam: {len(results)} yama | {uygulanan} uygulandı | {len(results)-uygulanan} zaten vardı/atlandı")
if uygulanan > 0:
    print("Şimdi git add + commit + push yap → Actions APK build başlar")
