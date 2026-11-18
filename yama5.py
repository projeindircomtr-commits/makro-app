#!/usr/bin/env python3
# v95 - Yanlis termal alarmi duzeltildi: OS termal durumu botu durdurmaz, sadece gercek pil sicakligi (50C)
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
MS = os.path.join(KOK, "app/src/main/java/com/kanka/makro/MacroService.kt")
if not os.path.exists(MS): print("! MacroService.kt yok"); sys.exit(1)
s=io.open(MS,encoding="utf-8").read()
old='''        // Koruma: sistem agir kisitlamaya girdiyse (SEVERE ve ustu) ya da pil cok sicaksa dur
        if ((termal >= 3 || (!sicak.isNaN() && sicak >= 46f)) && (running || pazarCalisiyor)) {
            val neden = if (termal >= 3) "Telefon aşırı ısındı (termal seviye $termal)"
            else "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")'''
new='''        // Koruma: SADECE pil GERCEKTEN cok sicaksa dur. OS termal durumu (termal>=3) Poco'da
        // telefon soguk olsa bile yanlis tetikliyordu; kaldirildi. Pil sensoru gercek sinyal.
        if ((!sicak.isNaN() && sicak >= 50f) && (running || pazarCalisiyor)) {
            val neden = "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")'''
if "OS termal durumu (termal>=3) Poco'da" in s: print("= zaten var"); sys.exit(0)
if s.count(old)!=1: print("! capa",s.count(old),"kez"); sys.exit(1)
io.open(MS,"w",encoding="utf-8").write(s.replace(old,new)); print("+ uygulandi: termal duzeltildi"); 
