#!/usr/bin/env python3
# v92 - Otomatik oyun tanima: ondeki desteklenen oyuna bot kendi gecer
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
MS = os.path.join(KOK, "app/src/main/java/com/kanka/makro/MacroService.kt")
if not os.path.exists(MS): print("! MacroService.kt yok:", MS); sys.exit(1)
s = io.open(MS, encoding="utf-8").read()
IMZA = "Otomatik oyun tanima: bot duruyorsa"
old = '''        if (on == secili || p == yanlisOyunUyarilan) return
        yanlisOyunUyarilan = p
        toast("🎮 Açık oyun ${oyunAdiKod(on)}, uygulamada ${Config.oyunAd(this)} seçili. Projeindir Bot'u aç → Oyun seç → ${oyunAdiKod(on)} → Bağlan.")
        TestLog.olay("YANLIS_OYUN", "öndeki oyun ${oyunAdiKod(on)}, seçili ${Config.oyunAd(this)}", "paket=$p")'''
new = '''        if (on == secili || p == yanlisOyunUyarilan) return
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
if IMZA in s: print("= zaten var, atlandi"); sys.exit(0)
n = s.count(old)
if n != 1: print("! capa", n, "kez bulundu, durdu"); sys.exit(1)
io.open(MS, "w", encoding="utf-8").write(s.replace(old, new))
print("+ uygulandi: otomatik oyun tanima")
