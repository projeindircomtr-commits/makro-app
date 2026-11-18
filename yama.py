#!/usr/bin/env python3
# Projeindir Bot - 3 yeni is: imza guvenligi + izinsiz ekran okuma (yedek) + ekran ustu durum bandi
# Her degisiklik tek tek, zaten varsa atlanir. Idempotent.
import os, sys, io

KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
def yol(*p): return os.path.join(KOK, *p)

def oku(f):
    with io.open(f, encoding="utf-8") as h: return h.read()
def yaz(f, s):
    with io.open(f, "w", encoding="utf-8") as h: h.write(s)

sayac = {"yapildi":0, "atlandi":0, "hata":0}
def degis(dosya, eski, yeni, imza):
    """imza zaten dosyada varsa atla; yoksa eski->yeni (tek sefer)."""
    f = yol(dosya)
    if not os.path.exists(f):
        print("  ! yok:", dosya); sayac["hata"]+=1; return
    s = oku(f)
    if imza in s:
        print("  = zaten var:", dosya, "|", imza[:40]); sayac["atlandi"]+=1; return
    n = s.count(eski)
    if n != 1:
        print("  ! capa", n, "kez:", dosya, "|", eski[:50].replace("\n"," ")); sayac["hata"]+=1; return
    yaz(f, s.replace(eski, yeni)); print("  + uygulandi:", dosya, "|", imza[:40]); sayac["yapildi"]+=1

MS = "app/src/main/java/com/kanka/makro/MacroService.kt"

# --- (G) yeni kod blogu: izinsiz ekran okuma + durum bandi ---
BLOK = r'''    // ================= İzin penceresiz ekran okuma (Android 11+) =================
    @Volatile private var erisimEkran = false
    private val erisimYurutucu by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    private fun erisimEkranBaslat(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (ScreenSampler.running) return true
        if (erisimEkran) return true
        erisimEkran = true
        ScreenSampler.running = true
        TestLog.olay("ERISIM_EKRAN", "izin penceresiz ekran okuma basladi (yedek, saniyede ~3 kare)", "")
        ui.post { erisimKareAl() }
        return true
    }

    private fun erisimEkranDurdur() {
        if (!erisimEkran) return
        erisimEkran = false
        if (CaptureService.instance == null) { ScreenSampler.running = false; ScreenSampler.clear() }
        TestLog.olay("ERISIM_EKRAN_DUR", "izin penceresiz ekran okuma durdu", "")
    }

    @android.annotation.TargetApi(30)
    private fun erisimKareAl() {
        if (!erisimEkran) return
        if (CaptureService.instance != null) { erisimEkran = false; return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, erisimYurutucu, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(s: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                        if (hw != null) {
                            val yazilim = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            val w = (yazilim.width * ScreenSampler.SCALE).toInt().coerceAtLeast(1)
                            val h = (yazilim.height * ScreenSampler.SCALE).toInt().coerceAtLeast(1)
                            val kucuk = Bitmap.createScaledBitmap(yazilim, w, h, true)
                            if (kucuk !== yazilim) yazilim.recycle()
                            if (erisimEkran) ScreenSampler.offerBitmap(kucuk) else kucuk.recycle()
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { s.hardwareBuffer.close() } catch (_: Exception) { }
                    }
                    ui.postDelayed({ erisimKareAl() }, 340)
                }
                override fun onFailure(code: Int) { ui.postDelayed({ erisimKareAl() }, 700) }
            })
        } catch (e: Exception) {
            ui.postDelayed({ erisimKareAl() }, 1000)
        }
    }

    // ================= Ekran üstü durum bandı (dokunuşu engellemez) =================
    private var durumBandi: TextView? = null
    private val bantGuncelle: Runnable = object : Runnable {
        override fun run() {
            try { bantYenile() } catch (_: Exception) { }
            ui.postDelayed(this, 1000)
        }
    }

    private fun bantYenile() {
        val kaynak = if (erisimEkran) " • 📷 yedek" else ""
        val metin = when {
            upgCalisiyor -> "⚒ Upgrade • +$upgHedef → +${upgHedef + 1} • gecen $upgGecen • yanan $upgYanan$kaynak"
            running && rotaCalisiyor -> "🔵 Banka turu$kaynak"
            running -> "🟢 Farm • mob $kesilen • kutu $toplanan$kaynak"
            else -> null
        }
        if (metin == null) {
            durumBandi?.let { safeRemove(it) }
            durumBandi = null
            return
        }
        var v = durumBandi
        if (v == null) {
            val tv = TextView(this).apply {
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            try {
                wm.addView(tv, lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    x = dp(4)
                    y = (screenH * 0.60f).toInt()
                })
            } catch (e: Exception) { return }
            durumBandi = tv
            v = tv
        }
        v.text = metin
    }

    // ================= Upgrade Bot: Farm'dan bağımsız, Anvil'de seviye seviye basar =================
'''
degis(MS,
      "    // ================= Upgrade Bot: Farm'dan bağımsız, Anvil'de seviye seviye basar =================\n",
      BLOK, "fun erisimEkranBaslat()")

# --- (A) panel basla: izinsiz ekranla basla ---
degis(MS,
'''                else { TestLog.olay("PANEL_BASLAT", "panelden başlatıldı", ""); startMacro() }''',
'''                else {
                    TestLog.olay("PANEL_BASLAT", "panelden baslatildi", "ekran_paylasimi=${ScreenSampler.running}")
                    if (!ScreenSampler.running && erisimEkranBaslat()) ui.postDelayed({ if (!running) startMacro() }, 1500)
                    else startMacro()
                }''', "if (!running) startMacro() }, 1500)")

# --- (B) bekciKontrol: izin kaybinda yedege gec ---
degis(MS,
'''        // 2) Ekran paylaşımı çalışırken kapandıysa kör dokunma: dur
        if (yakalamaVardi && !ScreenSampler.running) {''',
'''        // 2) Ekran paylasimi kapandiysa once izinsiz ekran okumaya gec, olmazsa dur
        if (yakalamaVardi && !ScreenSampler.running && erisimEkranBaslat()) {
            TestLog.olay("IZIN_KAYBI_YEDEK", "ekran paylasimi kapandi, izinsiz ekran okumaya gecildi", "")
        }
        if (yakalamaVardi && !ScreenSampler.running) {''', "IZIN_KAYBI_YEDEK")

# --- (C) upgrade toast'lari: yedek ekranla devam ---
degis(MS,
'''        if (!ScreenSampler.running) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver"); return }''',
'''        if (!ScreenSampler.running && !erisimEkranBaslat()) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver"); return }''',
      '''!erisimEkranBaslat()) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver"); return }''')
degis(MS,
'''        if (!ScreenSampler.running) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver, sonra durdur"); return }''',
'''        if (!ScreenSampler.running && !erisimEkranBaslat()) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver, sonra durdur"); return }''',
      '''!erisimEkranBaslat()) { toast("Ekran okuma kapalı: önce bir kez ▶ BAŞLAT'a basıp ekran iznini ver, sonra durdur"); return }''')

# --- (D) onServiceConnected: bandi baslat ---
degis(MS,
'''        instance = this
        ui.postDelayed(sistemIzle, 30_000)''',
'''        instance = this
        ui.postDelayed(sistemIzle, 30_000)
        ui.postDelayed(bantGuncelle, 3000)''', "ui.postDelayed(bantGuncelle, 3000)")

# --- (E) upgBitir: yedek ekrani kapat ---
degis(MS,
'''    private fun upgBitir(neden: String) {
        if (!upgCalisiyor) return
        upgCalisiyor = false''',
'''    private fun upgBitir(neden: String) {
        if (!upgCalisiyor) return
        upgCalisiyor = false
        ui.post { if (!running) erisimEkranDurdur() }''', "if (!running) erisimEkranDurdur() }")

# --- (F) stopMacro: yedek ekrani kapat ---
degis(MS,
'''    private fun stopMacro(reason: String? = null) {
        val calisiyordu = running''',
'''    private fun stopMacro(reason: String? = null) {
        val calisiyordu = running
        ui.post { if (!upgCalisiyor) erisimEkranDurdur() }''', "if (!upgCalisiyor) erisimEkranDurdur() }")

print("\n== OZET ==", sayac)
if sayac["hata"]>0:
    print(">> HATA var, dur. Degisiklikler kismi olabilir ama capa bulunamayanlar atlandi.")

# ====== (a) IMZA GUVENLIGI: build.gradle + workflow'lar + .gitignore ======
degis("app/build.gradle",
'''    signingConfigs {
        sabit {
            storeFile file('makro.keystore')
            storePassword 'makro123'
            keyAlias 'makro'
            keyPassword 'makro123'
        }
    }''',
'''    // Imza anahtari artik repoda sifreyle durmuyor; GitHub Secrets'ten gelir.
    def ksDosya = file('makro.keystore')
    def ksSifre = System.getenv('KEYSTORE_PASS')
    def ksAd    = System.getenv('KEY_ALIAS')
    def ksAnahtarSifre = System.getenv('KEY_PASS')
    def imzaVar = ksDosya.exists() && ksSifre?.trim() && ksAd?.trim() && ksAnahtarSifre?.trim()
    signingConfigs {
        sabit {
            if (imzaVar) {
                storeFile ksDosya
                storePassword ksSifre
                keyAlias ksAd
                keyPassword ksAnahtarSifre
            }
        }
    }''', "def imzaVar = ksDosya.exists()")
degis("app/build.gradle",
      "        debug { signingConfig signingConfigs.sabit }",
      "        debug { if (imzaVar) signingConfig signingConfigs.sabit }",
      "debug { if (imzaVar) signingConfig")
degis("app/build.gradle",
'''            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
            signingConfig signingConfigs.sabit''',
'''            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
            signingConfig imzaVar ? signingConfigs.sabit : signingConfigs.debug''',
      "signingConfig imzaVar ? signingConfigs.sabit")

_COZ = '''      - name: Imza anahtarini coz
        env:
          KEYSTORE_B64: ${{ secrets.KEYSTORE_B64 }}
        run: |
          if [ -n "$KEYSTORE_B64" ]; then echo "$KEYSTORE_B64" | base64 -d > app/makro.keystore && echo "imza anahtari hazir"; else echo "KEYSTORE_B64 secret yok -> debug imza ile derlenecek"; fi
'''
degis(".github/workflows/build.yml",
'''      - name: Derle
        run: gradle assembleRelease --no-daemon --stacktrace''',
_COZ+'''      - name: Derle
        env:
          KEYSTORE_PASS: ${{ secrets.KEYSTORE_PASS }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASS: ${{ secrets.KEY_PASS }}
        run: gradle assembleRelease --no-daemon --stacktrace''', "Imza anahtarini coz")
degis(".github/workflows/build-apk.yml",
'''      - name: Debug APK Derle
        run: ./gradlew assembleDebug --stacktrace''',
_COZ+'''      - name: Debug APK Derle
        env:
          KEYSTORE_PASS: ${{ secrets.KEYSTORE_PASS }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASS: ${{ secrets.KEY_PASS }}
        run: ./gradlew assembleDebug --stacktrace''', "Imza anahtarini coz")

# .gitignore: keystore'u dogrudan ekle (capa aramadan)
import os as _os
_gi = yol(".gitignore")
if _os.path.exists(_gi):
    _g = oku(_gi)
    if "*.keystore" not in _g:
        yaz(_gi, _g.rstrip()+"\n*.keystore\n*.jks\n"); print("  + uygulandi: .gitignore | *.keystore"); sayac["yapildi"]+=1
    else:
        print("  = zaten var: .gitignore | *.keystore"); sayac["atlandi"]+=1
print("== SON OZET ==", sayac)

# ====== (seri) coken 'Seri' hiz secenegini kaldir + eski seri ayarini Hizli'ya cek ======
degis(MS,
'''        fun hizMod(cc: Config) = when {
            cc.maxDelay <= 150 -> 2
            cc.maxDelay <= 320 -> 1
            else -> 0
        }''',
'''        fun hizMod(cc: Config) = when {   // seri kaldirildi
            cc.maxDelay <= 320 -> 1
            else -> 0
        }''',
      "// seri kaldirildi")
degis(MS,
'''        listOf("Normal", "Hızlı", "Seri").forEachIndexed { i, ad ->''',
'''        // Eski 'Seri' ayari botu cokertiyordu: acilista Hizli'ya cek, secenegi gizle
        if (Config.load(this).maxDelay <= 150) kaydet { it.minDelay = 120; it.maxDelay = 300; it.pauseChance = 2; it.skMin = 150; it.skMax = 700 }
        listOf("Normal", "Hızlı").forEachIndexed { i, ad ->''',
      'listOf("Normal", "Hızlı").forEachIndexed')
print("== SERI OZET ==", sayac)
