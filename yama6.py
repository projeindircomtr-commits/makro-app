#!/usr/bin/env python3
# v96 - Ozel oyun duzeltmesi (paket otomatik yakala, myko'ya dusme) + isi durdurmasi tamamen kapatildi
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
MS = os.path.join(KOK, "app/src/main/java/com/kanka/makro/MacroService.kt")
if not os.path.exists(MS): print("! MacroService.kt yok"); sys.exit(1)
s=io.open(MS,encoding="utf-8").read()
yap=atla=hata=0
def degis(eski,yeni,imza):
    global s,yap,atla,hata
    if imza in s: print("= zaten var:",imza[:40]); atla+=1; return
    if s.count(eski)!=1: print("! capa",s.count(eski),"kez:",imza[:40]); hata+=1; return
    s=s.replace(eski,yeni); print("+ uygulandi:",imza[:40]); yap+=1

ISI_YENI='''        // Isı nedeniyle otomatik durdurma KAPALI (kullanıcı isteği). Cihazın kendi donanım
        // koruması aşırı ısıda zaten devreye girer; sıcaklık yalnızca sistem kaydına yazılır.'''
ISI_IMZA="Isı nedeniyle otomatik durdurma KAPALI"
ISI_V95='''        // Koruma: SADECE pil GERCEKTEN cok sicaksa dur. OS termal durumu (termal>=3) Poco'da
        // telefon soguk olsa bile yanlis tetikliyordu; kaldirildi. Pil sensoru gercek sinyal.
        if ((!sicak.isNaN() && sicak >= 50f) && (running || pazarCalisiyor)) {
            val neden = "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")
            if (pazarCalisiyor) pazarDurdur()
            if (running) stopMacro("$neden. Bot durdu")
        }'''
ISI_V94='''        // Koruma: sistem agir kisitlamaya girdiyse (SEVERE ve ustu) ya da pil cok sicaksa dur
        if ((termal >= 3 || (!sicak.isNaN() && sicak >= 46f)) && (running || pazarCalisiyor)) {
            val neden = if (termal >= 3) "Telefon aşırı ısındı (termal seviye $termal)"
            else "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")
            if (pazarCalisiyor) pazarDurdur()
            if (running) stopMacro("$neden. Bot durdu")
        }'''
if ISI_IMZA in s:
    print("= zaten var: isı durdurma kapali"); atla+=1
elif s.count(ISI_V95)==1:
    s=s.replace(ISI_V95,ISI_YENI); print("+ uygulandi: isı durdurma kapatildi (v95)"); yap+=1
elif s.count(ISI_V94)==1:
    s=s.replace(ISI_V94,ISI_YENI); print("+ uygulandi: isı durdurma kapatildi (v94)"); yap+=1
else:
    print("! isı blogu bulunamadi"); hata+=1

# oyundaMi else: ozel myko'ya dusmesin
degis('''        else if (Config.nowaMi(this)) p.contains("nowa", true)
        else p.contains("myko", true)
    }''',
'''        else if (Config.nowaMi(this)) p.contains("nowa", true)
        else if (Config.ozelMi(this)) false   // ozel oyun: paket eslesmediyse oyunda sayma (myko'ya dusme)
        else p.contains("myko", true)
    }''', "ozel oyun: paket eslesmediyse oyunda sayma")

# yanlisOyunUyari secili
degis('''        val secili = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else "myko"''',
'''        val secili = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else if (Config.ozelMi(this)) "ozel" else "myko"''',
'else if (Config.ozelMi(this)) "ozel" else "myko"')

# ozelPaketYakala
degis('''    private fun oyunGorunurluk() {
        if (oyundaMi()) {''',
'''    /** Ozel oyun secili ve paketi henuz yoksa: actigi gercek oyunu otomatik kaydet. */
    private fun ozelPaketYakala() {
        if (running || !Config.ozelMi(this) || Config.ozelPaket(this).isNotEmpty()) return
        val p = sonPaket
        if (p.isEmpty() || gormezdenGel(p)) return
        val k = p.lowercase()
        if (k.contains("launcher") || k.contains("home") || k.contains("myko") ||
            k.contains("komobile") || k.contains("revenger") || k.contains("nowa")) return
        Config.ozelPaketYaz(this, p)
        oyunDegisti()
        toast("🎮 ${Config.ozelAd(this)} kaydedildi (açık oyun paketi)")
        TestLog.olay("OZEL_PAKET_YAKALA", "ozel oyun paketi otomatik kaydedildi", "paket=$p")
    }

    private fun oyunGorunurluk() {
        ozelPaketYakala()
        if (oyundaMi()) {''', "fun ozelPaketYakala()")

io.open(MS,"w",encoding="utf-8").write(s)
print(f"== OZET == yapildi={yap} atlandi={atla} hata={hata}")
sys.exit(1 if hata else 0)
