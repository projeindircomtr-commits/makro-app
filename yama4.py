#!/usr/bin/env python3
# v94 - Ozel oyun ekleme: isimli+paketli 5. oyun slotu. Myko motoru, AYRI veri alani.
# Mevcut 4 oyun (myko/ko/rev/nowa) hic degismez. 3 dosya: Config, MacroService, MainActivity.
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
P = os.path.join(KOK, "app/src/main/java/com/kanka/makro")
yap=atla=hata=0
def degis(dosya, eski, yeni, imza):
    global yap,atla,hata
    f=os.path.join(P,dosya)
    if not os.path.exists(f): print("! yok:",dosya); hata+=1; return
    s=io.open(f,encoding="utf-8").read()
    if imza in s: print("= zaten var:",dosya,"|",imza[:38]); atla+=1; return
    if s.count(eski)!=1: print("! capa",s.count(eski),"kez:",dosya,"|",imza[:38]); hata+=1; return
    io.open(f,"w",encoding="utf-8").write(s.replace(eski,yeni)); print("+ uygulandi:",dosya,"|",imza[:38]); yap+=1

# ---- Config.kt ----
degis("Config.kt",
'''            genieMi(ctx) -> if (oyun(ctx) == "ko") "cfg_ko_genie" else if (revMi(ctx)) "cfg_rev_genie" else if (nowaMi(ctx)) "cfg_nowa_genie" else "cfg_genie"
            revMi(ctx) && pkMi(ctx) -> "cfg_rev_pk_" + sinif(ctx)
            revMi(ctx) && !pazarMi(ctx) -> "cfg_rev"
            nowaMi(ctx) && pkMi(ctx) -> "cfg_nowa_pk_" + sinif(ctx)
            nowaMi(ctx) -> "cfg_nowa"
            koMu(ctx) && pkMi(ctx) -> "cfg_ko_pk_" + sinif(ctx)''',
'''            genieMi(ctx) -> if (oyun(ctx) == "ko") "cfg_ko_genie" else if (revMi(ctx)) "cfg_rev_genie" else if (nowaMi(ctx)) "cfg_nowa_genie" else if (ozelMi(ctx)) "cfg_ozel_genie" else "cfg_genie"
            revMi(ctx) && pkMi(ctx) -> "cfg_rev_pk_" + sinif(ctx)
            revMi(ctx) && !pazarMi(ctx) -> "cfg_rev"
            nowaMi(ctx) && pkMi(ctx) -> "cfg_nowa_pk_" + sinif(ctx)
            nowaMi(ctx) -> "cfg_nowa"
            ozelMi(ctx) && pkMi(ctx) -> "cfg_ozel_pk_" + sinif(ctx)
            ozelMi(ctx) -> "cfg_ozel"
            koMu(ctx) && pkMi(ctx) -> "cfg_ko_pk_" + sinif(ctx)''',
"cfg_ozel")
degis("Config.kt",
'''        fun nowaMi(ctx: Context): Boolean = oyun(ctx) == "nowa" && !pazarMi(ctx)''',
'''        fun nowaMi(ctx: Context): Boolean = oyun(ctx) == "nowa" && !pazarMi(ctx)

        /** Ozel oyun: kullanicinin ekledigi oyun. Myko motorunu kullanir, ayri veri alani. Pazar yok. */
        fun ozelMi(ctx: Context): Boolean = oyun(ctx) == "ozel" && !pazarMi(ctx)
        fun ozelAd(ctx: Context): String { val a = genel(ctx).getString("ozel_ad", "") ?: ""; return if (a.isBlank()) "Özel Oyun" else a }
        fun ozelAdYaz(ctx: Context, a: String) { genel(ctx).edit().putString("ozel_ad", a).commit() }
        fun ozelPaket(ctx: Context): String = genel(ctx).getString("ozel_paket", "") ?: ""
        fun ozelPaketYaz(ctx: Context, p: String) { genel(ctx).edit().putString("ozel_paket", p).commit() }''',
"fun ozelMi(ctx: Context)")
degis("Config.kt",
'''        fun oyunAd(ctx: Context): String = if (koMu(ctx)) "KO Mobile" else if (revMi(ctx)) "RevengerOnline" else if (nowaMi(ctx)) "NowaOnline" else "MykoMobile"''',
'''        fun oyunAd(ctx: Context): String = if (koMu(ctx)) "KO Mobile" else if (revMi(ctx)) "RevengerOnline" else if (nowaMi(ctx)) "NowaOnline" else if (ozelMi(ctx)) ozelAd(ctx) else "MykoMobile"''',
"else if (ozelMi(ctx)) ozelAd(ctx)")
degis("Config.kt",
'''            if ((o == "ko" || o == "rev" || o == "nowa") && pazarMi(ctx)) modDegistir(ctx, false)''',
'''            if ((o == "ko" || o == "rev" || o == "nowa" || o == "ozel") && pazarMi(ctx)) modDegistir(ctx, false)''',
'o == "ozel") && pazarMi')
degis("Config.kt",
'''                val k = (if (ko) "cfg_ko_pk_" else if (oyun(ctx) == "rev") "cfg_rev_pk_" else if (oyun(ctx) == "nowa") "cfg_nowa_pk_" else "cfg_pk_") + sinif(ctx)''',
'''                val k = (if (ko) "cfg_ko_pk_" else if (oyun(ctx) == "rev") "cfg_rev_pk_" else if (oyun(ctx) == "nowa") "cfg_nowa_pk_" else if (oyun(ctx) == "ozel") "cfg_ozel_pk_" else "cfg_pk_") + sinif(ctx)''',
'oyun(ctx) == "ozel") "cfg_ozel_pk_"')

# ---- MacroService.kt ----
degis("MacroService.kt",
'''        val oyun = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else "myko"
        if (oyunPaketArandi == oyun) return oyunPaketAdi
        oyunPaketArandi = oyun
        if (oyun == "ko") {''',
'''        val oyun = if (Config.koMu(this)) "ko" else if (Config.revMi(this)) "rev" else if (Config.nowaMi(this)) "nowa" else if (Config.ozelMi(this)) "ozel" else "myko"
        if (oyunPaketArandi == oyun) return oyunPaketAdi
        oyunPaketArandi = oyun
        if (oyun == "ozel") {
            val s = Config.ozelPaket(this)
            oyunPaketAdi = if (s.isNotEmpty()) s else null
            return oyunPaketAdi
        }
        if (oyun == "ko") {''',
'if (oyun == "ozel") {')
degis("MacroService.kt",
'''    private fun ondekiOyunKodu(p: String): String? {
        val k = p.lowercase()''',
'''    private fun ondekiOyunKodu(p: String): String? {
        val oz = Config.ozelPaket(this)
        if (oz.isNotEmpty() && p == oz) return "ozel"
        val k = p.lowercase()''',
'if (oz.isNotEmpty() && p == oz) return "ozel"')
degis("MacroService.kt",
'''    private fun oyunAdiKod(k: String): String = when (k) {
        "rev" -> "RevengerOnline"; "nowa" -> "NowaOnline"; "ko" -> "KO Mobile"; else -> "MykoMobile"
    }''',
'''    private fun oyunAdiKod(k: String): String = when (k) {
        "rev" -> "RevengerOnline"; "nowa" -> "NowaOnline"; "ko" -> "KO Mobile"; "ozel" -> Config.ozelAd(this); else -> "MykoMobile"
    }''',
'"ozel" -> Config.ozelAd(this)')

# ---- MainActivity.kt ----
degis("MainActivity.kt",
'''        val oyunlar = listOf("MykoMobile" to "myko", "RevengerOnline" to "rev", "NowaOnline" to "nowa", "KO Mobile" to "ko")
        AlertDialog.Builder(this)
            .setTitle("Oyun seç")
            .setItems(oyunlar.map { it.first }.toTypedArray()) { _, i ->
                val kod = oyunlar[i].second
                if (Config.oyun(this) != kod) sunucuOnayYaz(false)
                oyunSec(kod)
                lisansYenile()
                refresh()
            }''',
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
'if (kod == "ozel") { ozelKur();')
degis("MainActivity.kt",
'''            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** KO Mobile otomatik bulunamazsa: yuklu uygulamalardan elle sec */''',
'''            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Ozel oyun ekle: ad gir, yuklu uygulamadan paketi sec. Myko motoru, ayri veri. */
    private fun ozelKur() {
        val input = EditText(this).apply { setText(Config.ozelAd(this@MainActivity)); hint = "Oyun adı" }
        AlertDialog.Builder(this)
            .setTitle("Özel oyun adı")
            .setView(input)
            .setPositiveButton("Devam") { _, _ ->
                val ad = input.text.toString().ifBlank { "Özel Oyun" }
                Config.ozelAdYaz(this, ad)
                ozelPaketSec(ad)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun ozelPaketSec(ad: String) {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val liste = try {
            pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        } catch (e: Exception) { emptyList() }
        if (liste.isEmpty()) { toast("Uygulama listesi alınamadı"); return }
        AlertDialog.Builder(this)
            .setTitle("$ad hangi uygulama?")
            .setItems(liste.map { it.first }.toTypedArray()) { _, i ->
                Config.ozelPaketYaz(this, liste[i].second)
                Config.oyunSec(this, "ozel")
                if (Config.load(this).points.isEmpty()) Config.hazirAyar(this)
                MacroService.instance?.oyunDegisti()
                sunucuOnayYaz(false)
                toast("$ad seçildi")
                lisansYenile(); refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** KO Mobile otomatik bulunamazsa: yuklu uygulamalardan elle sec */''',
"private fun ozelKur()")

print(f"== OZET == yapildi={yap} atlandi={atla} hata={hata}")
sys.exit(1 if hata else 0)
