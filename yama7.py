#!/usr/bin/env python3
# v97 BIRLESIK - ozel oyun listede secilebilir + oto-yakala myko-benzeri kabul + (v96) ozel fix + isi kapali
# Hangi surumde olursan ol son hale getirir. Her parca guard'li/idempotent.
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
P = os.path.join(KOK, "app/src/main/java/com/kanka/makro")
yap=atla=hata=0
def oku(d): return io.open(os.path.join(P,d),encoding="utf-8").read()
def yaz(d,s): io.open(os.path.join(P,d),"w",encoding="utf-8").write(s)
def degis(dosya,eski,yeni,imza):
    global yap,atla,hata
    s=oku(dosya)
    if imza in s: print("=",dosya,imza[:38]); atla+=1; return
    if s.count(eski)!=1: print("! capa",s.count(eski),dosya,imza[:38]); hata+=1; return
    yaz(dosya,s.replace(eski,yeni)); print("+",dosya,imza[:38]); yap+=1

MS="MacroService.kt"; MA="MainActivity.kt"

# ---- (v96) ISI durdurma kapat: v94 / v95 / zaten kapali ----
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
_s=oku(MS)
if ISI_IMZA in _s: print("= isı zaten kapali"); atla+=1
elif _s.count(ISI_V95)==1: yaz(MS,_s.replace(ISI_V95,ISI_YENI)); print("+ isı kapatildi (v95)"); yap+=1
elif _s.count(ISI_V94)==1: yaz(MS,_s.replace(ISI_V94,ISI_YENI)); print("+ isı kapatildi (v94)"); yap+=1
else: print("! isı blogu yok"); hata+=1

# ---- (v96) oyundaMi ozel ----
degis(MS,
'''        else if (Config.nowaMi(this)) p.contains("nowa", true)
        else p.contains("myko", true)
    }''',
'''        else if (Config.nowaMi(this)) p.contains("nowa", true)
        else if (Config.ozelMi(this)) false   // ozel oyun: paket eslesmediyse oyunda sayma (myko'ya dusme)
        else p.contains("myko", true)
    }''', "ozel oyun: paket eslesmediyse oyunda sayma")

# ---- (v96) secili ozel ----
degis(MS,
'''        val secili = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else "myko"''',
'''        val secili = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else if (Config.ozelMi(this)) "ozel" else "myko"''',
'else if (Config.ozelMi(this)) "ozel" else "myko"')

# ---- ozelPaketYakala: yoksa ekle (RELAXED exclusion); varsa exclusion'i gevset ----
_s=oku(MS)
if "fun ozelPaketYakala()" not in _s:
    degis(MS,
'''    private fun oyunGorunurluk() {
        if (oyundaMi()) {''',
'''    /** Ozel oyun secili ve paketi henuz yoksa: actigi gercek oyunu otomatik kaydet. */
    private fun ozelPaketYakala() {
        if (running || !Config.ozelMi(this) || Config.ozelPaket(this).isNotEmpty()) return
        val p = sonPaket
        if (p.isEmpty() || gormezdenGel(p)) return
        val k = p.lowercase()
        if (k.contains("launcher") || k.contains("home") || k.contains("mykomobile") ||
            k.contains("komobile") || k.contains("revenger") || k.contains("nowaonline")) return
        Config.ozelPaketYaz(this, p)
        oyunDegisti()
        toast("🎮 ${Config.ozelAd(this)} kaydedildi (açık oyun paketi)")
        TestLog.olay("OZEL_PAKET_YAKALA", "ozel oyun paketi otomatik kaydedildi", "paket=$p")
    }

    private fun oyunGorunurluk() {
        ozelPaketYakala()
        if (oyundaMi()) {''', "fun ozelPaketYakala()")
else:
    # v96 eski exclusion'i gevset (myko->mykomobile, nowa->nowaonline)
    degis(MS,
'''        if (k.contains("launcher") || k.contains("home") || k.contains("myko") ||
            k.contains("komobile") || k.contains("revenger") || k.contains("nowa")) return''',
'''        if (k.contains("launcher") || k.contains("home") || k.contains("mykomobile") ||
            k.contains("komobile") || k.contains("revenger") || k.contains("nowaonline")) return''',
      'k.contains("mykomobile") ||')

# ---- (v97) MainActivity: ozel oyun listede secilebilir ----
degis(MA,
'''        val oyunlar = listOf("MykoMobile" to "myko", "RevengerOnline" to "rev", "NowaOnline" to "nowa", "KO Mobile" to "ko", "➕ Özel oyun ekle" to "ozel")
        AlertDialog.Builder(this)
            .setTitle("Oyun seç")
            .setItems(oyunlar.map { it.first }.toTypedArray()) { _, i ->
                val kod = oyunlar[i].second
                if (kod == "ozel") { ozelKur(); return@setItems }
                if (Config.oyun(this) != kod) sunucuOnayYaz(false)
                oyunSec(kod)
                lisansYenile()
                refresh()
            }''',
'''        val oyunlar = mutableListOf("MykoMobile" to "myko", "RevengerOnline" to "rev", "NowaOnline" to "nowa", "KO Mobile" to "ko")
        val ozelVar = Config.ozelPaket(this).isNotEmpty()
        if (ozelVar) oyunlar.add(Config.ozelAd(this) to "ozel")
        oyunlar.add((if (ozelVar) "➕ Özel oyunu değiştir" else "➕ Özel oyun ekle") to "ozel_ekle")
        AlertDialog.Builder(this)
            .setTitle("Oyun seç")
            .setItems(oyunlar.map { it.first }.toTypedArray()) { _, i ->
                val kod = oyunlar[i].second
                if (kod == "ozel_ekle") { ozelKur(); return@setItems }
                if (Config.oyun(this) != kod) sunucuOnayYaz(false)
                oyunSec(kod)
                lisansYenile()
                refresh()
            }''', 'if (ozelVar) oyunlar.add(Config.ozelAd(this) to "ozel")')

print(f"== OZET == yapildi={yap} atlandi={atla} hata={hata}")
sys.exit(1 if hata else 0)
