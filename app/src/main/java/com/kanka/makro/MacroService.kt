package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ContentValues
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.BatteryManager
import android.os.PowerManager
import android.content.IntentFilter
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Random
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class MacroService : AccessibilityService() {

    companion object {
        /** Uygulama ekranindan servise ulasmak icin (ayni surec) */
        @Volatile
        var instance: MacroService? = null
    }

    private enum class Mod { BUTON, HP, MP, HEDEF_BAR, OPEN, COLLECT }

    // Oyun icinde tus duzenleme ekrani
    private var editor: FrameLayout? = null

    private class Hedef(val x: Float, val y: Float, val r: Float, val fast: Boolean = false)

    private enum class Loot { BOS, COLLECT_BEKLE, SONRAKI_KUTU }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("macro").apply { start() }
    private val h = Handler(worker.looper)

    // Kayit uyumlu mod: erisilebilirlik ekran goruntusu dongusu

    // Ag islemleri (mesaj / surum kontrolu) icin ayri thread
    private val agThread = HandlerThread("ag").apply { start() }
    private val ah = Handler(agThread.looper)

    // Mesaj karti (oyunun ustunde)
    private var kart: View? = null
    private val mesajKuyrugu = ArrayDeque<Pair<String, String>>()
    private val rnd = Random()

    private var panel: View? = null
    private var overlay: View? = null
    private var playBtn: TextView? = null
    private var statusTv: TextView? = null

    @Volatile
    private var running = false

    // Adim dongusu ve dokunus durumu (kutu gozcusu araya girebilsin diye)
    private val stepR = Runnable { step() }
    private var tapping = false
    private var pendingStart = false
    private var lisansKontrolde = false
    private var sonLisansKontrol = 0L
    @Volatile private var cfg = Config()

    // Motor durumu (sadece worker thread'inde degisir)
    private var endAt = 0L
    private var nextTarget = 0L
    private var lastHpPot = 0L
    private var lastMpPot = 0L
    private var hpLowSince = 0L
    private val skillReady = HashMap<Int, Long>()
    private val skillLast = HashMap<Int, Long>()
    private var tgtStrip = IntArray(3)

    // Otomatik ekran tanima
    private var otoMod = false
    @Volatile private var olcek: Preset.Olcek? = null
    private var sonTarama = 0L

    // Koruma
    private var dcT: Sablon? = null
    private var dcSay = 0
    private var sonDcKontrol = 0L
    private var hpSol = IntArray(2)
    private var hpBosSince = 0L

    // Takili hedef
    private var oncekiCanli = false
    private var sonYuzde = 1f
    private var ilerlemeAt = 0L
    private var iptalBekliyor = false
    // PK modu
    @Volatile private var pkAktif = false
    @Volatile private var koAktif = false   // KO Mobile Farm
    private var koBarAt = 0L                // KO: barlar en son ne zaman arandi
    private var koSonKutu = 0L
    private var pkSira = 0
    private var pkSonSkill = 0L    // PK: son skill basilma ani (skill arasi icin)
    private var pkSonIksir = 0L
    private val pkIksirHazir = HashMap<Int, Long>()
    private var pkMinorBasildi = false
    private var pkMinorHazir = 0L

    /** PK "sadece tus basma": ekran okuma YOK (can, mana, hedef, koruma). Potlar/minor sureyle basilir. */
    private fun pkSaf(): Boolean = pkAktif && !cfg.pkOkuma

    /** HP/MP potu: kullanicinin girdigi sure (sn) dolunca basilir; sure 0 ise "Iksir arasi". Ikisi ayni anda basilmaz. */
    private fun pkZamanliPot(now: Long): List<Nokta> {
        val pts = cfg.points
        val aralik = cfg.potCd.coerceIn(300, 5000)
        if (now - pkSonIksir < aralik) return emptyList()
        val hazir = pts.indices.filter {
            (pts[it].type == "hp_pot" || pts[it].type == "mp_pot") && pts[it].on && (pkIksirHazir[it] ?: 0L) <= now
        }
        if (hazir.isEmpty()) return emptyList()
        val sira = Config.oncelikListe(cfg.oncelik)
        val secilen = hazir.minByOrNull { sira.indexOf(if (pts[it].type == "hp_pot") "hp" else "mp") } ?: return emptyList()
        val p = pts[secilen]
        pkIksirHazir[secilen] = now + (if (p.cd > 0f) (p.cd * 1000).toLong() else aralik.toLong())
        pkSonIksir = now
        basilanPot++
        return listOf(p)
    }

    /** Minor: sure 0 ise calisma basinda BIR kez basilir (acik kalir); sure girilirse o aralikla basilir. */
    private fun pkMinorZamanli(m: Nokta, now: Long): Nokta? {
        if (m.cd <= 0f) {
            if (pkMinorBasildi) return null
            pkMinorBasildi = true
            return m
        }
        if (now < pkMinorHazir) return null
        pkMinorHazir = now + (m.cd * 1000).toLong()
        return m
    }
    private var pkSonKilic = 0L    // PK: kilica en son basilma ani

    // Minor ac-kapa durumu (1. basis acar ve mana yer, 2. basis kapatir)
    @Volatile private var minorAcik = false
    private var minorSon = 0L
    private var modBtn: TextView? = null

    // Mesafe siniri sonrasi bekleme
    @Volatile private var menzilBekleUntil = 0L
    private var menzilXAt = 0L
    private var menzilArdArda = 0
    private var iptalAt = 0L             // son X (iptal) zamani: iptal edilen mob "kesildi" sayilmasin

    // Alan siniri
    private var alanBitti = false        // bu hedef icin alan kontrolu tamamlandi
    private var alanArdArda = 0          // ust uste alan disi hedef sayisi
    @Volatile private var alanBekleUntil = 0L
    private var alanView: View? = null
    // Halka tespiti icin yeniden kullanilan tamponlar (her cagrida MB'larca dizi ayirma)
    private var alanMask = BooleanArray(0)
    private var alanVis = BooleanArray(0)
    private var alanStack = IntArray(0)

    // Mesafe siniri
    private var hedefBaslangic = 0L
    private var ilkVurus = false

    // Bar dolulugu ve mob kilidi (otomatik tanimadan gelir)
    private var hpBar = IntArray(3)
    private var mpBar = IntArray(3)
    private var isimRect = IntArray(4)
    private var kilitKotu = 0
    private var kilitUyarildi = false

    // ---- Dayanıklılık + test modu durumu ----
    private var testCalisiyor = false                  // bu oturumda test modu etkin mi
    @Volatile private var endAtReal = 0L               // elapsedRealtime tabanlı bitiş (derin uykuda da sayar)
    @Volatile private var sonIlerleme = 0L             // adım döngüsünün son canlılık zamanı (uptime)
    private var hataPenceresi = 0L
    private var hataSayisi = 0
    private var duraklamaBas = 0L
    private var yakalamaVardi = false
    private var yakalamaYokSince = 0L
    private var koHpSifirSince = 0L
    private var koHpDusukSince = 0L
    private var koHpGordu = false
    private var koHpBak = 0L
    private var dcBekleBas = 0L
    private var dcBekleSonBak = 0L
    private var dcOk = 0
    private var stallSn = 0
    private var stallRef = 0
    private var agKopmaAt = 0L
    private var sonAgTuru = ""
    private var kontrolPencereBas = 0L
    private var agCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var kilitBekleUntil = 0L   // kilitli mob yok: bu ana kadar hicbir sey yapma

    // Guvenlik: oyun onde mi, ekran acik mi, goruntu taze mi
    @Volatile private var sonPaket = ""      // en son one gelen uygulama
    @Volatile private var oyunPaketi = ""    // ▶'a basildiginda ondeki uygulama
    @Volatile private var bekleNeden = ""    // panelde gosterilecek bekleme sebebi


    // Istatistik
    @Volatile private var kesilen = 0
    @Volatile private var toplanan = 0
    @Volatile private var basilanPot = 0
    private var taramaHata = 0
    private var nextLootScan = 0L
    private var lootPhase = Loot.BOS
    private var phaseUntil = 0L
    private var lootPauseUntil = 0L
    private var collectStreak = 0
    private var lastOpenX = -9999f
    private var lastOpenY = -9999f
    private var lastOpenAt = 0L
    private var collectedSinceOpen = true
    // Envanter doluyken Close ile kapatilan kutu: ayni kutuya bir sure tekrar basma
    private var doluKutuX = -9999f
    private var doluKutuY = -9999f
    private var doluKutuAt = 0L
    private var warnedCollect = false

    @Volatile
    private var screenW = 1080

    @Volatile
    private var screenH = 2400

    // ================= Yasam dongusu =================

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        ui.postDelayed(sistemIzle, 30_000)
        // Ilk asama koruma: mod onceden PK/Pazar'da kalmissa Farm'a zorla dondur
        if (yetkisizModdaMi()) {
            Config.modDegistir(this, false)
        }
        Lisans.yukle(this)
        TestLog.hazirla(this)
        rotaYukle()
        TestLog.sistem = Lisans.testHesabi() && Config.load(this).let { it.testModu || it.townAcik }
        if (TestLog.sistem) {
            TestLog.olay("SERVIS_BASLADI", "erişilebilirlik servisi bağlandı", "sürüm=${surumAdi()}")
            val pr = getSharedPreferences("test_durum", MODE_PRIVATE)
            if (pr.getBoolean("oturum_acik", false)) {
                TestLog.olay("ONCEKI_OTURUM_KESILDI", "önceki oturum normal bitmedi (servis/uygulama sistem tarafından sonlandırılmış olabilir)", "")
                pr.edit().putBoolean("oturum_acik", false).apply()
            }
        }
        // Beklenmedik cokmeleri kaydet (panele raporlanir)
        val onceki = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            hataKaydet("ÇÖKME", e)
            onceki?.uncaughtException(t, e)
        }
        updateScreenSize()
        showPanel()
        ah.postDelayed(mesajDongu, 3000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (gormezdenGel(p)) return
        val sinif = event.className?.toString() ?: ""
        if (sinif.contains("notif", true) || sinif.contains("headsup", true) || sinif.contains("toast", true) ||
            sinif.contains("popup", true) || sinif.contains("floating", true)) return
        if (oyunPaketiOnde() && p != sonPaket && !oyunPaketiMi(p)) {
            // Oyun öndeyken başka bir pencere belirdi (bildirim, balon, arama çubuğu...). Hemen "oyundan çıkıldı"
            // sayma: yarım saniye sonra gerçekten aktif pencere hâlâ o mu diye bak.
            ui.postDelayed({
                val aktif = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
                if (aktif != null && !gormezdenGel(aktif)) {
                    sonPaket = aktif
                    oyunGorunurluk()
                }
            }, 600)
            return
        }
        sonPaket = p
        ui.post { oyunGorunurluk() }
    }

    /**
     * Geri tusu: makronun acik bir penceresi varsa onu kapatir (panele doner) ve tusu yutar.
     * Acik pencere yoksa tus oyuna gider.
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null || event.keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
        if (overlay == null && editor == null && kart == null) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            ui.post {
                when {
                    overlay != null -> removeOverlay()
                    editor != null -> closeEditor()
                    kart != null -> kartKapat()
                }
            }
        }
        return true
    }

    /** MykoMobile'in paket adi (yuklu uygulamalardan bulunur, bir kez) */
    private var oyunPaketAdi: String? = null
    private var oyunPaketArandi = ""   // hangi oyun icin arandi ("" = hic)

    /** Secili oyunun paket adi (yuklu uygulamalardan bulunur, oyun basina bir kez) */
    @Suppress("DEPRECATION")
    private fun oyunPaketiBul(): String? {
        val oyun = if (Config.koMu(this)) "ko" else "myko"
        if (oyunPaketArandi == oyun) return oyunPaketAdi
        oyunPaketArandi = oyun
        if (oyun == "ko") {
            val secili = Config.koPaket(this)
            if (secili.isNotEmpty()) { oyunPaketAdi = secili; return secili }
        }
        oyunPaketAdi = try {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, 0).firstOrNull {
                val pk = it.activityInfo.packageName
                val ad = it.loadLabel(pm).toString()
                if (oyun == "ko") pk.contains("komobile", true) || ad.contains("ko mobile", true) ||
                    ad.contains("komobile", true)
                else pk.contains("myko", true) || ad.contains("myko", true)
            }?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        return oyunPaketAdi
    }

    /** Uygulamadan oyun degisince: ayarlari ve oyun paketini yeniden yukle */
    fun oyunDegisti() {
        ui.post {
            if (running) stopMacro()
            oyunPaketArandi = ""
            oyunPaketi = ""
            cfg = Config.load(this)
            modBtn?.text = modYazi()
            statusTv?.text = Config.oyunAd(this) + " hazır"
            oyunGorunurluk()
        }
    }

    private fun oyundaMi(): Boolean {
        val p = sonPaket
        if (p.isEmpty()) return true   // henuz bilgi yok: gizleme
        val oyun = oyunPaketiBul()
        if (oyun != null && p == oyun) return true
        if (oyunPaketi.isNotEmpty() && p == oyunPaketi) return true
        return if (Config.koMu(this)) p.contains("komobile", true) else p.contains("myko", true)
    }

    /**
     * Makro sadece oyunun icinde: oyundan cikinca panel gizlenir ve acik kalan
     * butun pencereler (duzenleme, kayit, menu) kapanir; baska yerde dokunus engellenmez.
     */
    private fun oyunGorunurluk() {
        if (oyundaMi()) {
            panel?.visibility = View.VISIBLE
            // Oyun disindayken gelen mesajlari simdi goster
            if (kart == null && mesajKuyrugu.isNotEmpty()) siradakiMesaj()
        } else {
            removeOverlay()
            closeEditor()
            kartKapat()
            panel?.visibility = View.GONE
        }
    }

    private fun oyunPaketiMi(p: String): Boolean {
        val oyun = oyunPaketiBul()
        return (oyun != null && p == oyun) || (oyunPaketi.isNotEmpty() && p == oyunPaketi)
    }

    /** Panel durum yazısı: çalışırken yeşil, dururken kırmızımsı */
    private fun durumYaz(t: String, calisiyor: Boolean) {
        statusTv?.text = t
        statusTv?.setTextColor(if (calisiyor) 0xFF7CFF7C.toInt() else 0xFFFF9E9E.toInt())
    }

    /** Ekran tanınamadıysa nedenini yazıdan anlayıp kullanıcıya ne yapacağını söyler */
    private fun ekranUyari() {
        val bm = try { ScreenSampler.tamKare() } catch (e: Exception) { null }
        if (bm == null) {
            toast("🎮 Ekran görüntüsü alınamıyor. Ekran okuma iznini kontrol et.")
            return
        }
        BotKontrol.metin(bm) { t ->
            val k = (t ?: "").lowercase()
            val mesaj = when {
                k.contains("homepage") || (k.contains("start") && k.contains("option")) ->
                    "🎮 Şu an oyun başlatıcı ekranındasın. 'Start'a bas, karakterinle oyuna gir; bot kendisi devam eder."
                k.contains("server") || k.contains("select") || k.contains("login") || k.contains("create") ->
                    "🎮 Karakter/sunucu seçim ekranındasın. Karakterinle oyunun içine gir; bot kendisi devam eder."
                else ->
                    "🎮 Oyun ekranı bulunamadı. Karakterinle oyunun içine gir (sol üstte can/mana barı görünsün); bot kendisi devam eder."
            }
            toast(mesaj)
            TestLog.olay("EKRAN_TANINMADI", mesaj, "")
        }
    }

    private fun oyunPaketiOnde(): Boolean = sonPaket.isNotEmpty() && oyunPaketiMi(sonPaket)

    /** Bu paketler one gelse de "oyundan cikildi" sayilmaz */
    private fun gormezdenGel(p: String): Boolean =
        p == packageName || p == "com.android.systemui" || p == "android" ||
            p.contains("systemui") || p.contains("notification") || p.contains("miui.notification") ||
            p.contains("xmsf") || p.contains("cocktail") || p.contains("edgepanel") || p.contains("assistant") ||
            p.contains("inputmethod") || p.contains("keyboard") || p.contains(".ime") ||
            p.contains("permissioncontroller") || p.contains("securitycenter") ||
            // Ekran goruntusu / kayit onizlemesi one gelince panel kaybolmasin
            p.contains("screenshot") || p.contains("screenrecord") || p.contains("contentcatcher") ||
            p == "com.google.android.as" || p.contains("freeform")

    override fun onInterrupt() {
        TestLog.olay("SERVIS_KESINTI", "onInterrupt", "")
        if (running) stopMacro("Servis kesintiye uğradı") else stopMacro()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    override fun onDestroy() {
        TestLog.olay("SERVIS_DURDU", "onDestroy", "")
        konumDonguAktif = false
        yuruAktif = false
        jAktif = false
        instance = null
        ui.removeCallbacksAndMessages(null)   // ana thread'de bekleyen post/postDelayed cagrilari (statusTick, mesaj kartlari vb.)
        alanView?.let { safeRemove(it) }
        alanView = null
        kartKapat()
        closeEditor()
        stopMacro()
        removeOverlay()
        panel?.let { safeRemove(it) }
        panel = null
        worker.quitSafely()
        ah.removeCallbacksAndMessages(null)
        agThread.quitSafely()
        pazarCalisiyor = false
        pzThread.quitSafely()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val dm = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    // ================= Yardimcilar =================

    // ================= Sistem kaydi (pil / sicaklik / termal durum / bellek) =================
    // Bot calisirken 30 sn'de bir bir satir yazar. Telefon aniden kapanirsa son satir
    // kapanmadan hemen onceki durumu gosterir. commit() ile aninda kalici yazilir.

    private fun sistemIzle_ornekle() {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val seviye = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val olcek100 = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pil = if (seviye >= 0 && olcek100 > 0) seviye * 100 / olcek100 else -1
        val ham = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val sicak = if (ham == Int.MIN_VALUE) Float.NaN else ham / 10f
        val durum = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val sarjda = durum == BatteryManager.BATTERY_STATUS_CHARGING || durum == BatteryManager.BATTERY_STATUS_FULL
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val termal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val bot = when {
            pazarCalisiyor -> "pazar"
            running -> if (pkAktif) "pk" else "farm"
            else -> "-"
        }
        val saat = java.text.SimpleDateFormat("dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val satir = "$saat $bot pil%$pil${if (sarjda) "⚡" else ""} " +
            (if (sicak.isNaN()) "?°C" else "%.1f°C".format(sicak)) + " termal$termal heap${heapMb}MB"
        val pr = getSharedPreferences("sistem", MODE_PRIVATE)
        val eski = pr.getString("log", "") ?: ""
        val yeni = (eski.split("\n").filter { it.isNotBlank() } + satir).takeLast(40).joinToString("\n")
        pr.edit().putString("log", yeni).commit()

        // Koruma: sistem agir kisitlamaya girdiyse (SEVERE ve ustu) ya da pil cok sicaksa dur
        if ((termal >= 3 || (!sicak.isNaN() && sicak >= 46f)) && (running || pazarCalisiyor)) {
            val neden = if (termal >= 3) "Telefon aşırı ısındı (termal seviye $termal)"
            else "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")
            if (pazarCalisiyor) pazarDurdur()
            if (running) stopMacro("$neden. Bot durdu")
        }
    }

    private val sistemIzle: Runnable = object : Runnable {
        override fun run() {
            try {
                if (running || pazarCalisiyor) sistemIzle_ornekle()
            } catch (e: Exception) {
                hataKaydet("sistem", e)
            }
            ui.postDelayed(this, 30_000)
        }
    }

    // ================= Alan siniri =================

    /** Alan dikdortgeni: yatayda ekranin ortasi, dikeyde alanCy, boyutlar ekran yuzdesi */
    private fun alanIcinde(x: Float, y: Float): Boolean {
        val cx = screenW * 0.5f
        val cy = screenH * cfg.alanCy / 100f
        val hw = screenW * cfg.alanW / 200f
        val hh = screenH * cfg.alanH / 200f
        return kotlin.math.abs(x - cx) <= hw && kotlin.math.abs(y - cy) <= hh
    }

    /**
     * Secili mobun ayagindaki sari secim halkasini arar (renk + bos elips sekli).
     * Bulursa halkanin ekran merkezi (x, y), bulamazsa null. HUD bolgeleri disarida birakilir.
     */
    private fun secimHalkasi(): FloatArray? {
        val s = olcek?.s ?: (screenW / 2712f)
        val yUst = (screenH * 0.10f).toInt()
        val yAlt = (screenH * 0.88f).toInt()
        val kr = ScreenSampler.bolge(0, yUst, screenW - 1, yAlt) ?: return null
        val w = kr.first
        val h = kr.second
        val px = kr.third
        if (w < 8 || h < 8) return null
        val sc = ScreenSampler.SCALE
        val n = w * h
        if (alanMask.size != n) {
            alanMask = BooleanArray(n)
            alanVis = BooleanArray(n)
            alanStack = IntArray(n)
        } else {
            alanMask.fill(false)
            alanVis.fill(false)
        }
        val mask = alanMask
        for (y in 0 until h) {
            val sy = (y / sc + yUst) / screenH
            for (x in 0 until w) {
                val sx = (x / sc) / screenW
                if (sx < 0.24f && sy < 0.36f) continue    // sol ust: can/mana + gorev listesi
                if (sx < 0.18f && sy > 0.60f) continue    // sol alt: sohbet / bilgi yazilari
                if (sx > 0.64f && sy > 0.42f) continue    // sag alt: skill dugmeleri
                if (sx > 0.85f && sy < 0.45f) continue    // sag ust: kanal + kamera dugmeleri
                val c = px[y * w + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (r > 185 && g > 165 && r - b > 30) mask[y * w + x] = true
            }
        }
        val vis = alanVis
        val stack = alanStack
        var best: FloatArray? = null
        var bestD = Float.MAX_VALUE
        val cxS = screenW * 0.5f
        val cyS = screenH * (cfg.alanCy / 100f)
        val minW = 40f * s * sc
        val maxW = 450f * s * sc
        for (start in 0 until w * h) {
            if (!mask[start] || vis[start]) continue
            var sp = 0
            stack[sp++] = start
            vis[start] = true
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                // 2 piksellik bosluklari da birlestir (ince halka cizgisi)
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && !vis[ni]) {
                        vis[ni] = true
                        stack[sp++] = ni
                    }
                }
            }
            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat()
            if (bw < minW || bw > maxW || bh < minW * 0.3f) continue
            val asp = bw / bh
            if (asp < 0.6f || asp > 4.5f) continue
            if (cnt < 25 || cnt / (bw * bh) > 0.55f) continue   // dolu yuzey degil, bos halka olmali
            val sxC = ((minX + maxX) / 2f) / sc
            val syC = ((minY + maxY) / 2f) / sc + yUst
            val d = (sxC - cxS) * (sxC - cxS) + (syC - cyS) * (syC - cyS)
            if (d < bestD) {
                bestD = d
                best = floatArrayOf(sxC, syC)
            }
        }
        return best
    }

    /** Alan dikdortgenini (ve varsa bulunan halka noktasini) 4 sn ekranda gosterir; dokunuslari engellemez */
    private fun alanCerceveGoster(nokta: FloatArray? = null) {
        ui.post {
            alanView?.let { safeRemove(it) }
            alanView = null
            val c = Config.load(this)
            val cx = screenW * 0.5f
            val cy = screenH * c.alanCy / 100f
            val hw = screenW * c.alanW / 200f
            val hh = screenH * c.alanH / 200f
            val cerceve = android.graphics.Paint().apply {
                color = 0xFFFF7A2E.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            val dolu = android.graphics.Paint().apply {
                color = 0xFFFFEB3B.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            val v = object : View(this@MacroService) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, cerceve)
                    if (nokta != null) canvas.drawCircle(nokta[0], nokta[1], dp(10).toFloat(), dolu)
                }
            }
            val p = lp(screenW, screenH).apply {
                x = 0
                y = 0
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                wm.addView(v, p)
                alanView = v
                ui.postDelayed({
                    if (alanView === v) {
                        safeRemove(v)
                        alanView = null
                    }
                }, 4000)
            } catch (e: Exception) {
                alanView = null
            }
        }
    }

    /** Menuden: bir mobu sec, sonra bas. Halkayi bulup alanin icinde/disinda oldugunu soyler */
    private fun alanTesti() {
        removeOverlay()
        if (running || pazarCalisiyor) { toast("Önce botu durdur"); return }
        if (!ScreenSampler.running) { toast("Ekran okuma kapalı: önce ▶ ile bir kere başlatıp durdur"); return }
        if (!oyundaMi()) { toast("Önce oyunu aç"); return }
        cfg = Config.load(this)
        h.post {
            val halka = try { secimHalkasi() } catch (e: Exception) { null }
            if (halka == null) {
                toast("📐 Seçim halkası bulunamadı. Bir mobu seçili tutup tekrar dene")
            } else {
                val ic = alanIcinde(halka[0], halka[1])
                toast(if (ic) "📐 Halka bulundu: alanın İÇİNDE ✓ (saldırırdı)" else "📐 Halka bulundu: alanın DIŞINDA ✗ (bırakırdı)")
            }
            alanCerceveGoster(halka)
        }
    }

    // ================= Kamera testi =================
    // Sagdaki bos bir noktadan yatay surukleyip geri getirir. Amac: bu hareketin senin
    // telefonunda oyun kamerasini gercekten cevirip cevirmedigini (ve hangi yone) gormek.

    private fun kameraSurukle(x1: Float, y: Float, x2: Float, done: () -> Unit) {
        val p = Path().apply { moveTo(x1, y); lineTo(x2, y + 2f) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done() }
        }, h)
        if (!ok) done()
    }

    private fun kameraTesti() {
        removeOverlay()
        if (running || pazarCalisiyor) { toast("Önce botu durdur"); return }
        if (!oyundaMi()) { toast("Önce oyunu aç"); return }
        val y = screenH * 0.30f
        val x0 = screenW * 0.62f
        val x1 = x0 + screenW * 0.16f
        toast("🎥 Kamera testi: önce SAĞA, sonra SOLA sürükleniyor. Kameranın ne yaptığına bak")
        h.postDelayed({
            kameraSurukle(x0, y, x1) {
                h.postDelayed({ kameraSurukle(x1, y, x0) {} }, 800)
            }
        }, 600)
    }

    private fun sistemKaydiGoster() {
        val satirlar = (getSharedPreferences("sistem", MODE_PRIVATE).getString("log", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.takeLast(8)
        val liste = ArrayList<Pair<String, () -> Unit>>()
        if (satirlar.isEmpty()) liste.add("Henüz kayıt yok (bot çalışırken 30 sn'de bir yazılır)" to {})
        satirlar.forEach { liste.add(it to {}) }
        liste.add("🗑 Kaydı temizle" to {
            getSharedPreferences("sistem", MODE_PRIVATE).edit().remove("log").commit()
            toast("Sistem kaydı temizlendi")
        })
        showMenu("🌡 Son sistem kayıtları", liste)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rand(a: Int, b: Int): Long {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return (lo + rnd.nextInt((hi - lo + 1).coerceAtLeast(1))).toLong()
    }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        } catch (e: Exception) {
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun lp(w: Int, hh: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, hh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    // Knight Online arayuzu: siyah panel + metalik gri cerceve, celik degrade butonlar
    private val KO_LACI = 0xFF2A3240.toInt()          // buton (celik-lacivert)
    private val KO_CERCEVE = 0xFF8C7A55.toInt()       // bronz cerceve (oyundaki paneller)
    private val KO_BEJ = 0xFFC9AE78.toInt()           // buton kenari (oyundaki gibi)

    private fun acik(c: Int, oran: Float): Int {
        val a = (c ushr 24) and 0xff
        val r = ((c shr 16) and 0xff); val g = ((c shr 8) and 0xff); val b = (c and 0xff)
        fun k(v: Int) = (v + (255 - v) * oran).toInt().coerceIn(0, 255)
        return (a shl 24) or (k(r) shl 16) or (k(g) shl 8) or k(b)
    }

    /**
     * Koyu renkler (panel/pencere) -> siyah zemin + gri metal cerceve.
     * Diger renkler (butonlar) -> yukaridan asagi koyulasan degrade + ince bej kenar.
     */
    private fun rounded(color: Int): GradientDrawable {
        val r = (color shr 16) and 0xff; val g = (color shr 8) and 0xff; val b = color and 0xff
        val panelMi = r < 40 && g < 40 && b < 40
        return if (panelMi) GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(3), KO_CERCEVE)
        } else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(acik(color, 0.28f), color)).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(2), KO_BEJ)
        }
    }

    /** Oyundaki gibi buton zemini: 0 = celik, 1 = altin (onay), 2 = koyu kirmizi (iptal) */
    private fun koButon(tur: Int = 0): GradientDrawable = when (tur) {
        1 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFB8955A.toInt(), 0xFF7A5E32.toInt(), 0xFF4A3A20.toInt())).apply {
            cornerRadius = dp(4).toFloat(); setStroke(dp(2), 0xFFF0D59A.toInt())
        }
        2 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF7A3A34.toInt(), 0xFF4E1F1B.toInt(), 0xFF2E110F.toInt())).apply {
            cornerRadius = dp(4).toFloat(); setStroke(dp(2), KO_BEJ)
        }
        else -> rounded(KO_LACI)
    }

    private fun btn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        setTextColor(0xFFF3E6C4.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(9), dp(11), dp(9))
        setOnClickListener { onClick() }
    }

    private fun menuBtn(text: String, onClick: () -> Unit) =
        btn(text, onClick).apply { background = rounded(KO_LACI) }

    // ================= Yuzen panel =================

    /** Panel daraltılmış (sadece ikon) mı? */
    @Volatile private var panelDar = false
    private var panelGenisIcerik: View? = null
    private var panelIkonBtn: View? = null
    private var panelRootBg: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panel != null) return
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = dp(8); y = dp(60) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE60E0E0E.toInt())
        }
        panelRootBg = root

        // Sürükleme + ikon tık
        fun dragListener(hedef: View) = object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                        params.x = sx + dx; params.y = sy + dy
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && v === panelIkonBtn) {
                            // Bot çalışıyorsa ikon = DURDUR; duruyorsa panel açılsın
                            if (running) stopMacro()
                            else panelDaralt(false)
                        }
                    }
                }
                return true
            }
        }

        // --- Sol ikon: Projeindir Bot logosu (şeffaf) ---
        val ikonBoy = dp(44)
        val ikon = ImageView(this).apply {
            val bmp = try {
                assets.open("panel_icon.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            if (bmp != null) setImageBitmap(bmp)
            else setImageResource(resources.getIdentifier("ic_launcher", "mipmap", packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(ikonBoy, ikonBoy)
        }
        panelIkonBtn = ikon
        ikon.setOnTouchListener(dragListener(ikon))

        // --- Geniş panel (ikonun sağında) ---
        val genis = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drag = btn("⠿") {}
        drag.setOnTouchListener(dragListener(drag))
        val play = btn("▶ BAŞLAT") { toggle() }
        playBtn = play
        val st = TextView(this).apply {
            text = "Hazır"
            setTextColor(0xFFB0FFB0.toInt())
            textSize = 12f
            setPadding(dp(6), 0, dp(10), 0)
            maxWidth = dp(260)
            setSingleLine(true)
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusTv = st
        val mb = btn("Farm") { modMenu() }.apply { setTextColor(0xFFE0B04A.toInt()) }
        modBtn = mb
        val daraltBtn = btn("—") { panelDaralt(true) }
        for (v in listOf(drag, play, mb, st, daraltBtn)) {
            v.setSingleLine(true)
            v.includeFontPadding = false
        }
        genis.addView(drag)
        genis.addView(play)
        genis.addView(mb)
        genis.addView(btn("☰ Menü") { showMainMenu() })
        genis.addView(st)
        genis.addView(daraltBtn)
        panelGenisIcerik = genis

        // İkon solda, panel sağda
        root.addView(ikon)
        root.addView(genis)
        ikon.visibility = View.GONE

        try {
            wm.addView(root, params)
            panel = root
            panelDar = false
        } catch (e: Exception) {
            toast("Panel açılamadı: ${e.message}")
        }
    }

    /** Panel ↔ ikon geçişi. Daraltınca arka plan şeffaf, sadece logo görünür. */
    private fun panelDaralt(dar: Boolean) {
        panelDar = dar
        val g = panelGenisIcerik
        val i = panelIkonBtn
        val root = panelRootBg
        if (g == null || i == null) return
        g.visibility = if (dar) View.GONE else View.VISIBLE
        i.visibility = if (dar) View.VISIBLE else View.GONE
        root?.background = if (dar) null else rounded(0xE60E0E0E.toInt())
    }

    // ================= Menuler =================

    private fun removeOverlay() {
        overlay?.let { safeRemove(it) }
        overlay = null
    }

    /**
     * Kutuyu sabit genislikte, olculmus yukseklikte (ekrana sigmazsa kaydirilabilir)
     * ekranin ortasinda gosterir. WRAP_CONTENT bazi cihazlarda pencereyi ezdigi icin.
     */
    private fun ortadaGoster(box: View, genislikPx: Int) {
        updateScreenSize()
        val sc = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(box, ViewGroup.LayoutParams(genislikPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(genislikPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ekranH = minOf(screenW, screenH)
        val yukseklik = minOf(box.measuredHeight, ekranH - dp(24))
        overlay = sc
        try {
            wm.addView(sc, lp(genislikPx, yukseklik).apply { gravity = Gravity.CENTER })
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        val cols = if (items.size > 5) 2 else 1
        val bw = if (cols == 2) dp(170) else dp(220)
        var rowView: LinearLayout? = null
        items.forEachIndexed { idx, (label, action) ->
            if (idx % cols == 0) {
                val nr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowView = nr
                box.addView(nr)
                box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            }
            val rv = rowView!!
            if (idx % cols == 1) rv.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            rv.addView(menuBtn(label) { removeOverlay(); action() },
                LinearLayout.LayoutParams(bw, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        box.addView(btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) },
            LinearLayout.LayoutParams(bw, LinearLayout.LayoutParams.WRAP_CONTENT))
        ortadaGoster(box, cols * bw + (cols - 1) * dp(6) + dp(16))
    }

    private fun kilavuzGoster() {
        showMenu(
            "KISA KILAVUZ\n\n1) Oyunu aç, 'Start'a bas, karakterinle oyuna gir\n2) Farm yapacağın yere git\n3) ▶ BAŞLAT'a bas: üstte '● BOT ÇALIŞIYOR' yazar\n4) Durdurmak için ⏸ DURDUR\n\nÇanta dolunca otomatik boşaltmak için: ☰ Menü → Otomatik çanta boşaltma",
            listOf("✅ Anladım" to {})
        )
    }

    private fun tamKapatSor() {
        showMenu(
            "Bot tamamen kapatılsın mı?\n\nPanel kaybolur, ekran okuma durur ve erişilebilirlik servisi kapanır. Tekrar kullanmak için uygulamadan açman gerekir.",
            listOf(
                "✅ Evet, tamamen kapat" to { botuTamamenKapat() },
                "↩ Vazgeç" to {}
            )
        )
    }

    private fun botuTamamenKapat() {
        try { if (pazarCalisiyor) pazarDurdur() } catch (_: Exception) {}
        try { if (running) stopMacro() } catch (_: Exception) {}
        try { stopService(Intent(this, CaptureService::class.java)) } catch (_: Exception) {}
        try { ScreenSampler.clear() } catch (_: Exception) {}
        removeOverlay()
        toast("⏻ Bot tamamen kapatıldı")
        ui.postDelayed({
            try { panel?.let { safeRemove(it) }; panel = null } catch (_: Exception) {}
            try { disableSelf() } catch (_: Exception) {}
        }, 600)
    }

    private fun showMainMenu() {
        if (running) {
            toast("Menü için önce botu durdur (⏸)"); return
        }
        if (!Lisans.gecerliSimdi()) {
            toast("Önce uygulamadan üye girişi yap")
            openApp()
            return
        }
        // KURAL: Uyeler sadece Ayarlar, Tuslari duzenle, Mob kilitle/kaldir ve Uygulamayi ac'i gorur.
        // Bar/kutu kaydi ve tum test/kayit ekranlari SADECE YONETICI. Yeni menu ogesi eklerken
        // teknik/test olanlari yoneticiListesi'ne koy.
        val yonetici = Lisans.yoneticiMi()
        val testListesi: List<Pair<String, () -> Unit>> = if (Lisans.testHesabi())
            listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }) else emptyList()
        // Bar/kutu kaydı ve test ekranları menüden kaldırıldı (ekran otomatik tanınıyor)
        val yoneticiListesi: List<Pair<String, () -> Unit>> = emptyList()
        showMenu(
            "Menü",
            if (Config.genieMi(this)) listOf<Pair<String, () -> Unit>>(
                // Genie Hizlandir: sadece ayar, kilic tusu ve uygulama
                "⚙ Ayarlar" to { ayarKarti() },
                "🛠 Tuşları düzenle" to { openEditor() },
                "⚙ Uygulamayı aç" to { openApp() }
            ) else listOf<Pair<String, () -> Unit>>(
                "⚙ Ayarlar" to { ayarKarti() },
                "🛠 Tuşları düzenle" to { openEditor() }
            ) + yoneticiListesi + testListesi + listOf<Pair<String, () -> Unit>>(
                "🎯 Seçili mobu kilitle" to { mobuKilitle() },
                "🔓 Mob kilitlerini kaldır" to {
                    val c = Config.load(this)
                    c.kilitler.clear()
                    c.save(this)
                    toast("Kilitler kaldırıldı, her moba vurulacak")
                },
                "❓ Kısa kılavuz" to { kilavuzGoster() },
                "⚙ Uygulamayı aç" to {
                    try {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                },
                "⏻ Botu tamamen kapat" to { tamKapatSor() }
            )
        )
    }

    // ================= Oyun icinde ayarlar (uygulamaya gecmeden) =================

    private fun ayarKarti() {
        removeOverlay()
        val c = Config.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "⚙ " + (if (Config.genieMi(this@MacroService)) "Genie Hızlandır"
                else if (Config.pkMi(this@MacroService)) "PK Bot • " + Config.sinifAd(this@MacroService) else "Farm Bot") + " ayarları"
            setTextColor(0xFFE0B04A.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(6))
        })

        fun kaydet(degis: (Config) -> Unit) {
            val cc = Config.load(this)
            degis(cc)
            cc.save(this)
        }

        // - deger + satiri
        fun satir(ad: String, deger: () -> String, eksi: () -> Unit, arti: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(TextView(this).apply {
                text = ad
                setTextColor(Color.WHITE)
                textSize = 14f
            }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            val v = TextView(this).apply {
                text = deger()
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
            r.addView(menuBtn("−") { eksi(); v.text = deger() })
            r.addView(v, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(menuBtn("+") { arti(); v.text = deger() })
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        }

        // Genie Hizlandir modu: menude sadece kilic hizi
        if (Config.genieMi(this)) {
            box.addView(TextView(this).apply {
                text = "Oyunun Genie'sini aç. Bot sadece kılıca seri basar, başka hiçbir şey yapmaz."
                setTextColor(Color.WHITE); textSize = 13f
                setPadding(0, 0, 0, dp(6))
            })
            satir("🗡 Kılıç aralığı", { "${Config.load(this).genieKilicMs} ms" },
                { kaydet { it.genieKilicMs = (it.genieKilicMs - 50).coerceIn(50, 2000) } },
                { kaydet { it.genieKilicMs = (it.genieKilicMs + 50).coerceIn(50, 2000) } })
            box.addView(btn("Kapat ✓") { removeOverlay() }.apply { background = rounded(0xFF2E9E5B.toInt()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            ortadaGoster(box, dp(440))
            return
        }

        val pkSafAyar = Config.pkMi(this) && !Config.load(this).pkOkuma
        if (!pkSafAyar) {
            satir("❤ HP potu", { "%" + Config.load(this).hpYuzde },
                { kaydet { it.hpYuzde = (it.hpYuzde - 5).coerceIn(10, 95) } },
                { kaydet { it.hpYuzde = (it.hpYuzde + 5).coerceIn(10, 95) } })
            satir("💧 MP potu", { "%" + Config.load(this).mpYuzde },
                { kaydet { it.mpYuzde = (it.mpYuzde - 5).coerceIn(5, 95) } },
                { kaydet { it.mpYuzde = (it.mpYuzde + 5).coerceIn(5, 95) } })
        }
        if (Config.minorVar(this) && !pkSafAyar) {
            val minorBtn = menuBtn("") {}
            fun minorYaz() {
                val ak = Config.load(this).minorAktif
                minorBtn.text = if (ak) "💚 Minor: AKTİF" else "💚 Minor: PASİF"
                minorBtn.background = if (ak) koButon(1) else koButon()
            }
            minorBtn.setOnClickListener {
                val c = Config.load(this)
                c.minorAktif = !c.minorAktif
                c.save(this)
                cfg.minorAktif = c.minorAktif
                // Pasife alinirken Minor aciksa bir kez basip kapat (mana yemesin)
                if (!c.minorAktif && minorAcik) {
                    minorAcik = false
                    cfg.points.firstOrNull { it.type == "minor" }?.let { m ->
                        try {
                            val p = Path().apply { moveTo(m.x.toFloat(), m.y.toFloat()); lineTo(m.x + 1f, m.y + 1f) }
                            dispatchGesture(GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
                        } catch (e: Exception) {
                        }
                    }
                }
                minorYaz()
            }
            minorYaz()
            box.addView(minorBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            satir("💚 Minor, can altında", { "%" + Config.load(this).minorYuzde },
                { kaydet { it.minorYuzde = (it.minorYuzde - 5).coerceIn(20, 99) } },
                { kaydet { it.minorYuzde = (it.minorYuzde + 5).coerceIn(20, 99) } })
        }
        if (!Config.pkMi(this) && !Config.pazarMi(this)) {
            // AKTIF/PASIF anahtari: sure/boyut ayarlari kapatinca da saklanir
            fun anahtar(baslik: String, oku: (Config) -> Boolean, yaz: (Config, Boolean) -> Unit, acilinca: () -> Unit = {}) {
                val b = menuBtn("") {}
                fun goster() {
                    val ak = oku(Config.load(this))
                    b.text = baslik + ": " + (if (ak) "AKTİF" else "PASİF")
                    b.background = if (ak) koButon(1) else koButon()
                }
                b.setOnClickListener {
                    kaydet { yaz(it, !oku(it)) }
                    goster()
                    if (oku(Config.load(this))) acilinca()
                }
                goster()
                box.addView(b, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            }

            // --- Alan siniri (ekranda karakter etrafinda dikdortgen) ---
            anahtar("📐 Alan sınırı", { it.alanAktif }, { c, v -> c.alanAktif = v }) { alanCerceveGoster() }
            satir("↔ Alan genişliği", { "%" + Config.load(this).alanW },
                { kaydet { it.alanW = (it.alanW - 5).coerceIn(10, 95) }; alanCerceveGoster() },
                { kaydet { it.alanW = (it.alanW + 5).coerceIn(10, 95) }; alanCerceveGoster() })
            satir("↕ Alan yüksekliği", { "%" + Config.load(this).alanH },
                { kaydet { it.alanH = (it.alanH - 5).coerceIn(10, 95) }; alanCerceveGoster() },
                { kaydet { it.alanH = (it.alanH + 5).coerceIn(10, 95) }; alanCerceveGoster() })
            satir("⇅ Alan konumu (üst/alt)", { "%" + Config.load(this).alanCy },
                { kaydet { it.alanCy = (it.alanCy - 2).coerceIn(20, 80) }; alanCerceveGoster() },
                { kaydet { it.alanCy = (it.alanCy + 2).coerceIn(20, 80) }; alanCerceveGoster() })

            // --- Mesafe siniri (sure: bu surede vurulmaya baslanmazsa birak) ---
            // Mesafe siniri kaldirildi: skiller mesafe gozetmeksizin basilir (tum oyun ve modlar)
            satir("⏳ Uzak mob sonrası bekleme", { "${Config.load(this).menzilBekleSn} sn" },
                { kaydet { it.menzilBekleSn = (it.menzilBekleSn - 1).coerceIn(1, 15) } },
                { kaydet { it.menzilBekleSn = (it.menzilBekleSn + 1).coerceIn(1, 15) } })
        }
        if (Config.koMu(this) || Config.pkMi(this)) {
            // HP ve MP iksiri ortak bekleme kullanir (oyunda pota basinca geri sayim)
            satir("🧪 İksir arası (HP/MP)", { "%.1f sn".format(Config.load(this).potCd / 1000f) },
                { kaydet { it.potCd = (it.potCd - 100).coerceIn(300, 5000) } },
                { kaydet { it.potCd = (it.potCd + 100).coerceIn(300, 5000) } })
        }
        if (Config.pkMi(this)) {
            // Oyunun iki skill arasindaki ortak beklemesi: bu biter bitmez siradaki skill basilir
            satir("⚔ Skill arası", { "%.1f sn".format(Config.load(this).skillAraMs / 1000f) },
                { kaydet { it.skillAraMs = (it.skillAraMs - 100).coerceIn(0, 5000) } },
                { kaydet { it.skillAraMs = (it.skillAraMs + 100).coerceIn(0, 5000) } })
            // Ekran okuma: kapaliyken bot sadece tus basar (can/mana/hedef okumaz); potlar sureyle basilir
            satir("🕹 Yön joystick'i (yürü + dön)", { if (Config.load(this).padAcik) "AÇIK" else "KAPALI" },
                { kaydet { it.padAcik = false } },
                { kaydet { it.padAcik = true } })
            satir("🕹 Yürürken skill", { if (Config.load(this).padSkillDurur) "DURSUN (akıcı yürü)" else "DEVAM ETSİN" },
                { kaydet { it.padSkillDurur = false } },
                { kaydet { it.padSkillDurur = true } })
            satir("👁 Ayrı kamera joystick'i", { if (Config.load(this).padKamera) "AÇIK" else "KAPALI" },
                { kaydet { it.padKamera = false } },
                { kaydet { it.padKamera = true } })
            satir("🖐 Ekran okuma", { if (Config.load(this).pkOkuma) "AÇIK" else "KAPALI (sadece tuş)" },
                { kaydet { it.pkOkuma = false } },
                { kaydet { it.pkOkuma = true } })
        }
        satir("⏱ Süre", { "${Config.load(this).minutes} dk" },
            { kaydet { it.minutes = (it.minutes - 10).coerceIn(10, 600) } },
            { kaydet { it.minutes = (it.minutes + 10).coerceIn(10, 600) } })

        // Hiz secimi
        val hizSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hizSatir.addView(TextView(this).apply {
            text = "⚡ Hız"
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        val hizlar = ArrayList<TextView>()
        fun hizMod(cc: Config) = when {
            cc.maxDelay <= 150 -> 2
            cc.maxDelay <= 320 -> 1
            else -> 0
        }
        fun hizBoya() {
            val m = hizMod(Config.load(this))
            hizlar.forEachIndexed { i, b ->
                b.background = rounded(if (i == m) 0xFFE0B04A.toInt() else 0xFF3A3A3A.toInt())
            }
        }
        listOf("Normal", "Hızlı", "Seri").forEachIndexed { i, ad ->
            val b = btn(ad) {
                kaydet {
                    when (i) {
                        2 -> { it.minDelay = 50; it.maxDelay = 110; it.pauseChance = 0; it.skMin = 0; it.skMax = 150 }
                        1 -> { it.minDelay = 120; it.maxDelay = 300; it.pauseChance = 2; it.skMin = 150; it.skMax = 700 }
                        else -> { it.minDelay = 180; it.maxDelay = 550; it.pauseChance = 3; it.skMin = 250; it.skMax = 1500 }
                    }
                }
                hizBoya()
            }
            hizlar.add(b)
            hizSatir.addView(b)
            hizSatir.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        }
        hizBoya()
        box.addView(hizSatir)
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))

        // Oncelik sirasi: neye once basilsin
        box.addView(menuBtn("📋 Öncelik sırası (neye önce basılsın)") { oncelikMenu() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))

        // Kutu toplama ac/kapa
        val kutuBtn = menuBtn("") {}
        fun kutuYaz() {
            kutuBtn.text = if (Config.load(this).lootOn) "📦 Kutu toplama: AÇIK" else "📦 Kutu toplama: KAPALI"
        }
        kutuBtn.setOnClickListener {
            kaydet { it.lootOn = !it.lootOn }
            kutuYaz()
        }
        kutuYaz()
        box.addView(kutuBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))

        box.addView(TextView(this).apply {
            text = if (c.kilitler.isEmpty()) "🎯 Mob kilidi yok (her moba vurur)"
            else "🎯 ${c.kilitler.size} mob kilitli"
            setTextColor(0xFFB0B8C4.toInt())
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })

        box.addView(btn("Kapat ✓") { removeOverlay() }.apply { background = rounded(0xFF2E9E5B.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        ortadaGoster(box, dp(440))
    }

    // ================= Oyun icinde tus duzenleme =================

    private fun closeEditor() {
        editor?.let { safeRemove(it) }
        editor = null
    }

    private fun etiket(p: Nokta): String = when (p.type) {
        "saldiri" -> "⚔"
        "iptal" -> "❌"
        "hedef" -> "🎯"
        "hp_pot" -> "HP"
        "kutu" -> "📦"
        "joy" -> "🕹"
        "minor" -> "💚"
        "mp_pot" -> "MP"
        "heal" -> "💗\n%${p.yuzde}"
        "buff" -> "🛡\n${if (p.cd == p.cd.toLong().toFloat()) "${p.cd.toLong()}" else "${p.cd}"}s"
        else -> {
            val no = p.name.removePrefix("Skill ").trim()
            val sure = if (p.cd <= 0f) "∞" else (if (p.cd == p.cd.toLong().toFloat()) "${p.cd.toLong()}s" else "${p.cd}s")
            "S$no\n$sure"
        }
    }

    private fun renk(p: Nokta): Int = when {
        p.type == "saldiri" -> 0xE0C0392B.toInt()
        p.type == "iptal" -> 0xE0616161.toInt()
        p.type == "hedef" -> 0xE0D35400.toInt()
        p.type == "hp_pot" -> 0xE0B03030.toInt()
        p.type == "mp_pot" -> 0xE02E5BBA.toInt()
        !p.on -> 0xC0555555.toInt()
        else -> 0xE0208A4E.toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openEditor() {
        if (running) {
            toast("Önce botu durdur"); return
        }
        removeOverlay()
        closeEditor()
        updateScreenSize()
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = true
        }
        editor = root
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                if (overlay != null) {
                    removeOverlay()     // acik menuyu kapat
                } else {
                    val x = e.rawX.toInt()
                    val y = e.rawY.toInt()
                    editorAdd(x, y)
                }
            }
            true
        }
        try {
            wm.addView(root, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            editor = null
            toast("Düzenleme ekranı açılamadı"); return
        }
        editorRefresh()
    }

    /** Isaretleri ve ust cubugu yeniden ciz */
    private fun editorRefresh() {
        val root = editor ?: return
        root.removeAllViews()
        val cf = Config.load(this)
        val size = dp(44)
        cf.points.forEachIndexed { i, p ->
            val m = TextView(this).apply {
                text = etiket(p)
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(renk(p))
                    setStroke(dp(2), Color.WHITE)
                }
            }
            // Dokun: menu  •  Surukle: tusu saga/sola/yukari/asagi kaydir (birakinca kaydedilir)
            var dx0 = 0f; var dy0 = 0f; var lm0 = 0; var tm0 = 0; var surukle = false
            val esik = dp(8)
            m.setOnTouchListener { v, e ->
                val lpm = v.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dx0 = e.rawX; dy0 = e.rawY; lm0 = lpm.leftMargin; tm0 = lpm.topMargin; surukle = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val ddx = e.rawX - dx0; val ddy = e.rawY - dy0
                        if (!surukle && ddx * ddx + ddy * ddy > esik * esik) surukle = true
                        if (surukle) {
                            lpm.leftMargin = (lm0 + ddx).toInt().coerceIn(0, maxOf(0, screenW - size))
                            lpm.topMargin = (tm0 + ddy).toInt().coerceIn(0, maxOf(0, screenH - size))
                            v.layoutParams = lpm
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (surukle) {
                            val c = Config.load(this)
                            if (i < c.points.size) {
                                c.points[i].x = lpm.leftMargin + size / 2
                                c.points[i].y = lpm.topMargin + size / 2
                                c.tuslarOto = false
                                c.save(this)
                            }
                        } else if (overlay != null) removeOverlay() else editorPointMenu(i)
                    }
                }
                true
            }
            root.addView(m, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (p.x - size / 2).coerceAtLeast(0)
                topMargin = (p.y - size / 2).coerceAtLeast(0)
            })
        }

        // Ust cubuk: bilgi + Hepsini sil + Bitti
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE0202020.toInt())
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }
        bar.addView(TextView(this).apply {
            text = if (Config.genieMi(this@MacroService)) "Genie Hızlandır: sadece Kılıç tuşunu kaydet  •  sürükleyerek yerine oturt"
                else "Boş slota dokun: ekle  •  Etikete dokun: değiştir  •  Etiketi sürükle: kaydır" +
                (Config.sinifOnerisi(this@MacroService)?.let { "\n" + it } ?: "")
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, dp(10), 0)
        })
        bar.addView(btn("Hepsini sil") {
            if (overlay != null) removeOverlay()
            showMenu("Tüm tuşlar silinsin mi?", listOf("Evet, hepsini sil" to {
                val cf2 = Config.load(this)
                cf2.points.clear()
                cf2.tuslarOto = false
                    cf2.save(this)
                editorRefresh()
            }))
        }.apply { background = rounded(0xCC8B0000.toInt()) })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(btn("Bitti ✓") {
            removeOverlay()
            closeEditor()
            toast("Kaydedildi")
        }.apply { background = rounded(0xFF2E9E5B.toInt()) })
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START
        ).apply { leftMargin = dp(12) })
    }

    private fun turListesi(onPick: (String) -> Unit): List<Pair<String, () -> Unit>> =
        if (Config.genieMi(this)) listOf("⚔ Kılıç" to { onPick("saldiri") }) else listOf(
        "✨ Skill" to { onPick("skill") },
        "❤ HP pot" to { onPick("hp_pot") },
        "💧 MP pot" to { onPick("mp_pot") },
        "🎯 Mob seç" to { onPick("hedef") },
        "⚔ Kılıç (PK)" to { onPick("saldiri") },
        "❌ İptal (X)" to { onPick("iptal") },
        "💗 Can skilli (Heal)" to { onPick("heal") },
        "📦 Kutu butonu (KO)" to { onPick("kutu") },
        "🕹 Oyunun yürüme joystick'i (halkanın ortasına)" to { onPick("joy") },
        "🛡 Buff (süreyle)" to { onPick("buff") }
    ) + (if (Config.minorVar(this)) listOf("💚 Minor" to { onPick("minor") }) else emptyList())

    // ---- Genie hizlandirma ----
    private var genieKilicAt = 0L

    /** Genie modu: ayar acik + Farm modu + panelde "Genie hizlandir" yetkisi (yonetici her zaman) */
    private fun genieAktif(): Boolean = genieMod
    @Volatile private var genieMod = false

    /** Oyunun Genie'si acikken: Genie mob secer, yurur, skilleri kullanir; bot sadece kilica seri basar. */
    private fun genieAdim(now: Long) {
        // Sadece kilic: ekran okuma, skill/pot/minor/kutu/mob secme ve otomatik cevaplama yok.
        // Seri vurus KO ve MykoMobile yonetimlerinin izniyle; aralik oyuncunun ayari.
        val kilic = cfg.points.firstOrNull { it.type == "saldiri" && it.on }
        if (kilic == null) {
            // Kilic yoksa her adimda uyari yagdirma: botu durdur
            stopMacro("Kılıç tuşu yok: ⋯ → Tuşları düzenle")
            return
        }
        val bekle = cfg.genieKilicMs.toLong() - (now - genieKilicAt)
        if (bekle > 0) { stepPlanla(bekle.coerceAtMost(500)); return }
        genieKilicAt = now
        val r = cfg.radius.toFloat()
        tapping = true
        tapMulti(listOf(Hedef(kilic.x.toFloat(), kilic.y.toFloat(), r))) {
            tapping = false
            if (running) stepPlanla(10L)
        }
    }

    /** Oncelik sirasi: bir ogeye dokununca bir ust siraya cikar; liste yeniden acilir */
    private fun oncelikMenu() {
        val c = Config.load(this)
        val l = Config.oncelikListe(c.oncelik)
        showMenu("Öncelik (dokun = yukarı al)",
            l.mapIndexed { i, k ->
                "${i + 1}. ${Config.ONCELIK_AD[k]}" to {
                    if (i > 0) {
                        val cf = Config.load(this)
                        val ll = Config.oncelikListe(cf.oncelik)
                        ll.removeAt(i); ll.add(i - 1, k)
                        cf.oncelik = ll.joinToString(",")
                        cf.mpOncelik = ll.indexOf("mp") < ll.indexOf("hp")
                        cf.save(this)
                        cfg.oncelik = cf.oncelik
                        cfg.mpOncelik = cf.mpOncelik
                    }
                    oncelikMenu()
                }
            } + listOf("↺ Varsayılana dön" to {
                val cf = Config.load(this)
                cf.oncelik = Config.ONCELIK_VARSAYILAN; cf.mpOncelik = false; cf.save(this)
                cfg.oncelik = cf.oncelik; cfg.mpOncelik = false
                oncelikMenu()
            }, "✓ Tamam" to { ayarKarti() }))
    }

    /** Heal icin can yuzdesi secici */
    private fun yuzdeMenu(onPick: (Int) -> Unit) {
        showMenu("Can yüzde kaçın altına inince?",
            listOf(20, 30, 40, 50, 60, 70, 80, 90).map { y -> "%$y" to { onPick(y) } })
    }

    /**
     * Sure secici (oyun icinde): istedigin saniyeyi - / + ile ayarla, hazir secenekler de var.
     * 0 = surekli (beklemeden).
     */
    private fun cdMenu(baslangic: Float = 1f, onPick: (Float) -> Unit) {
        removeOverlay()
        var deger = baslangic.coerceIn(0f, 3600f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "⏱ Bu tuş kaç saniyede bir basılsın?"
            setTextColor(0xFFE0B04A.toInt()); textSize = 15f
            setPadding(0, 0, 0, dp(6))
        })
        val goster = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        fun yaz() {
            goster.text = if (deger <= 0f) "∞ sürekli" else {
                val r = Math.round(deger * 10) / 10f
                (if (r == r.toLong().toFloat()) "${r.toLong()}" else "$r") + " sn"
            }
        }
        yaz()
        box.addView(goster, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun satir(adimlar: List<Pair<String, () -> Unit>>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            adimlar.forEachIndexed { i, (ad, is_) ->
                if (i > 0) r.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
                r.addView(menuBtn(ad) { is_(); yaz() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(5)))
        }
        fun ekle(d: Float) {
            deger = (Math.round((deger + d) * 10) / 10f).coerceIn(0f, 3600f)
        }
        satir(listOf("−10" to { ekle(-10f) }, "−1" to { ekle(-1f) }, "−0.1" to { ekle(-0.1f) },
            "+0.1" to { ekle(0.1f) }, "+1" to { ekle(1f) }, "+10" to { ekle(10f) }))
        satir(listOf("∞" to { deger = 0f }, "0.5" to { deger = 0.5f }, "1" to { deger = 1f },
            "2" to { deger = 2f }, "5" to { deger = 5f }, "60" to { deger = 60f }, "100" to { deger = 100f }))

        val alt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alt.addView(btn("✓ Tamam") { removeOverlay(); onPick(deger) }.apply { background = rounded(0xFF2E9E5B.toInt()) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        alt.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        alt.addView(btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) },
            LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(alt)
        ortadaGoster(box, dp(430))
    }

    /** Bos yere dokunuldu: yeni tus ekle */
    private fun editorAdd(x: Int, y: Int) {
        showMenu("Bu slot ne olsun?", turListesi { type ->
            if (type == "heal") {
                cdMenu(3f) { cd ->
                    yuzdeMenu { yz ->
                        val cf = Config.load(this)
                        cf.points.add(Nokta("Heal", "heal", x, y, cd, true, yz))
                        cf.tuslarOto = false
                        cf.save(this)
                        editorRefresh()
                    }
                }
            } else if (type == "buff") {
                cdMenu(60f) { cd ->
                    val cf = Config.load(this)
                    cf.points.add(Nokta("Buff", "buff", x, y, cd, true))
                    cf.tuslarOto = false
                    cf.save(this)
                    editorRefresh()
                }
            } else if (type == "minor") {
                cdMenu { cd ->
                    val cf = Config.load(this)
                    cf.points.removeAll { it.type == "minor" }
                    cf.points.add(Nokta("Minor", "minor", x, y, cd, true))
                    cf.tuslarOto = false
                    cf.save(this)
                    editorRefresh()
                }
            } else if (type == "skill") {
                cdMenu { cd ->
                    val cf = Config.load(this)
                    cf.points.add(Nokta("Skill", "skill", x, y, cd, true))
                    renumber(cf)
                    cf.tuslarOto = false
                    cf.save(this)
                    editorRefresh()
                }
            } else {
                val cf = Config.load(this)
                cf.points.removeAll { it.type == type }
                cf.points.add(Nokta(Config.label(type), type, x, y))
                cf.tuslarOto = false
                    cf.save(this)
                editorRefresh()
            }
        })
    }

    /** Var olan isarete dokunuldu */
    private fun editorPointMenu(i: Int) {
        val cf = Config.load(this)
        if (i >= cf.points.size) return
        val p = cf.points[i]
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (p.type == "heal" || p.type == "buff" ||
            (Config.pkMi(this) && (p.type == "hp_pot" || p.type == "mp_pot" || p.type == "minor"))) {
            items.add("⏱ Süre (${p.cd} sn)" to {
                cdMenu(p.cd) { cd ->
                    val c = Config.load(this)
                    if (i < c.points.size) { c.points[i].cd = cd; c.save(this) }
                    editorRefresh()
                }
            })
            if (p.type == "heal") items.add("❤ Can yüzdesi (%${p.yuzde})" to {
                yuzdeMenu { yz ->
                    val c = Config.load(this)
                    if (i < c.points.size) { c.points[i].yuzde = yz; c.save(this) }
                    editorRefresh()
                }
            })
            items.add((if (p.on) "⏸ Kapat" else "▶ Aç") to {
                val c = Config.load(this)
                if (i < c.points.size) { c.points[i].on = !c.points[i].on; c.save(this) }
                editorRefresh()
            })
        }
        if (p.type == "skill") {
            val skiller = cf.points.indices.filter { cf.points[it].type == "skill" }
            val sira = skiller.indexOf(i)
            items.add("⏱ Süre (${if (p.cd <= 0f) "∞" else "${p.cd} sn"})" to {
                cdMenu(p.cd) { cd ->
                    val c = Config.load(this)
                    if (i < c.points.size) {
                        c.points[i].cd = cd
                    c.save(this)
                    }
                    editorRefresh()
                }
            })
            if (sira > 0) items.add("◀ Sırada öne al" to {
                val c = Config.load(this)
                val j = skiller[sira - 1]
                if (i < c.points.size && j < c.points.size) {
                    val t = c.points[i]; c.points[i] = c.points[j]; c.points[j] = t
                    renumber(c); c.tuslarOto = false; c.save(this)
                }
                editorRefresh()
            })
            if (sira < skiller.size - 1) items.add("▶ Sırada arkaya al" to {
                val c = Config.load(this)
                val j = skiller[sira + 1]
                if (i < c.points.size && j < c.points.size) {
                    val t = c.points[i]; c.points[i] = c.points[j]; c.points[j] = t
                    renumber(c); c.tuslarOto = false; c.save(this)
                }
                editorRefresh()
            })
            items.add((if (p.on) "⏸ Kapat" else "▶ Aç") to {
                val c = Config.load(this)
                if (i < c.points.size) {
                    c.points[i].on = !c.points[i].on
                    c.save(this)
                }
                editorRefresh()
            })
        }
        items.add("🔁 Türünü değiştir" to {
            showMenu("Yeni tür", turListesi { type ->
                val uygula = { cd: Float ->
                    val c = Config.load(this)
                    if (i < c.points.size) {
                        val old = c.points[i]
                        c.points.removeAt(i)
                        if (type != "skill") c.points.removeAll { it.type == type }
                        c.points.add(Nokta(Config.label(type), type, old.x, old.y, cd, true))
                        renumber(c)
                        c.tuslarOto = false
                    c.save(this)
                    }
                    editorRefresh()
                }
                if (type == "skill") cdMenu { cd -> uygula(cd) } else uygula(0f)
            })
        })
        items.add("🗑 Sil" to {
            val c = Config.load(this)
            if (i < c.points.size) {
                c.points.removeAt(i)
                renumber(c)
                c.tuslarOto = false
                    c.save(this)
            }
            editorRefresh()
        })
        showMenu("${p.name}", items)
    }

    private fun renumber(c: Config) {
        var n = 1
        c.points.forEach { if (it.type == "skill") it.name = "Skill ${n++}" }
    }

    /** Uygulamanin ekrandan gordugu goruntuyu Indirilenler'e kaydeder + sonucu yazar */
    private fun ekranTesti() {
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı. Önce ▶'a bas ya da uygulamadan izin ver"); return
        }
        toast("Test ediliyor…")
        h.post {
            val o = try {
                Preset.olcekBul(this)
            } catch (e: Exception) {
                null
            }
            ScreenSampler.bekle(600)
            val yas = SystemClock.uptimeMillis() - ScreenSampler.lastFrameAt
            val bmp = ScreenSampler.tamKare()
            val kayit = if (bmp != null) pngKaydet(bmp, "ekran_test_${System.currentTimeMillis() / 1000}.png") else false
            val (w, hh) = Preset.landscapeSize(this)
            val kare = if (bmp != null) "${bmp.width}x${bmp.height}" else "yok"
            val sonuc = if (o != null) "BULUNDU ölçek %.2f".format(o.s)
            else "bulunamadı (fark ${Preset.sonFark}, en yakın %.2f)".format(Preset.sonOlcekDegeri)
            toast("Ekran ${w}x$hh • kare $kare (${yas} ms önce) • aslan $sonuc" +
                if (kayit) " • İndirilenler'e kaydedildi" else " • resim kaydedilemedi")
        }
    }

    private fun pngKaydet(bmp: Bitmap, ad: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, ad)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ================= Panelden mesaj ve surum kontrolu =================

    /** Dakikada bir: yeni mesaj var mi, surum yeterli mi? */
    private val mesajDongu = object : Runnable {
        override fun run() {
            try {
                mesajKontrol()
            } catch (e: Exception) {
            }
            ah.postDelayed(this, 60_000)
        }
    }

    private fun mesajKontrol() {
        val token = Lisans.token(this)
        val url = Lisans.yanUrl(this, "mesaj.php") ?: return
        if (token.isEmpty()) return
        val pr = getSharedPreferences("mesaj", MODE_PRIVATE)
        val son = pr.getInt("son", 0)
        val hp = getSharedPreferences("hata", MODE_PRIVATE)
        val hataMetni = if (!hp.getBoolean("gonderildi", true)) hp.getString("son", "") ?: "" else ""
        val tam = url + "?token=" + URLEncoder.encode(token, "UTF-8") +
            "&son=" + son + "&surum=" + Lisans.surumKodu(this) +
            (if (hataMetni.isNotEmpty()) "&hata=" + URLEncoder.encode(hataMetni, "UTF-8") +
                "&hz=" + hp.getLong("zaman", 0L) else "")
        val bag = URL(tam).openConnection() as HttpURLConnection
        bag.connectTimeout = 8000
        bag.readTimeout = 8000
        val cevap = bag.inputStream.bufferedReader().use { it.readText() }
        bag.disconnect()
        val j = JSONObject(cevap)
        if (j.optInt("ok") != 1) return
        if (hataMetni.isNotEmpty()) hp.edit().putBoolean("gonderildi", true).apply()

        // Eski surum: makroyu durdur, guncelleme iste
        val g = j.optJSONObject("guncelle")
        if (g != null) {
            val ilk = !Lisans.guncelleGerekli
            Lisans.guncelleIsaretle(g.optString("guncelle"), g.optString("mesaj"))
            if (ilk) bildirim(999_999, "⬆ Güncelleme gerekli", Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." })
            ui.post {
                if (running) stopMacro()
                guncelleKarti()
            }
            return
        }
        // Uyelik kapatildi/bitti
        if (!j.optBoolean("aktif", true)) {
            ui.post { if (running) stopMacro("Üyelik aktif değil. Bot durdu") }
        }
        // Yeni mesajlar
        val m = j.optJSONArray("mesajlar") ?: return
        var enSon = son
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            enSon = maxOf(enSon, o.optInt("id"))
            val saat = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
                .format(java.util.Date(o.optLong("zaman") * 1000))
            val metin = o.optString("metin")
            bildirim(o.optInt("id"), "📢 Projeindir Bot • $saat", metin)
            ui.post { mesajGoster("📢 Mesaj • $saat", metin) }
        }
        if (enSon != son) pr.edit().putInt("son", enSon).apply()
    }

    /** Bildirim cubugu + kilit ekrani + ses */
    private fun bildirim(id: Int, baslik: String, metin: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("mesaj", "Mesajlar", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Panelden gelen mesajlar"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val pi = PendingIntent.getActivity(
                this, id,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, "mesaj")
                .setSmallIcon(R.drawable.ic_bildirim)
                .setContentTitle(baslik)
                .setContentText(metin)
                .setStyle(Notification.BigTextStyle().bigText(metin))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(10_000 + id, n)
        } catch (e: Exception) {
        }
    }

    private fun mesajGoster(baslik: String, metin: String) {
        mesajKuyrugu.addLast(baslik to metin)
        // Oyun disinda kart acma; bildirim zaten gitti, oyuna donunce gosterilir
        if (kart == null && oyundaMi()) siradakiMesaj()
    }

    private fun siradakiMesaj() {
        val (b, m) = mesajKuyrugu.removeFirstOrNull() ?: return
        kartGoster(b, m, listOf("Tamam" to { kartKapat(); siradakiMesaj() }))
        vibrate()
    }

    private fun guncelleKarti() {
        val url = Lisans.guncelleUrl
        val metin = Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." }
        val butonlar = ArrayList<Pair<String, () -> Unit>>()
        if (url.isNotEmpty()) {
            butonlar.add("⬇ İndir" to {
                kartKapat()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    toast("Link açılamadı")
                }
            })
        }
        butonlar.add("Kapat" to { kartKapat() })
        kartGoster("⬆ Güncelleme gerekli", metin, butonlar)
        statusTv?.text = "Güncelle"
        vibrate()
    }

    private fun kartKapat() {
        kart?.let { safeRemove(it) }
        kart = null
    }

    /** Oyunun ustunde buyuk bilgi karti */
    private fun kartGoster(baslik: String, metin: String, butonlar: List<Pair<String, () -> Unit>>) {
        kartKapat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(TextView(this).apply {
            text = baslik
            setTextColor(0xFFE0B04A.toInt())
            textSize = 14f
        })
        box.addView(TextView(this).apply {
            text = metin
            setTextColor(Color.WHITE)
            textSize = 19f
            setPadding(0, dp(6), 0, dp(12))
        }, LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT))
        val satir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        for ((ad, is_) in butonlar) {
            satir.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            satir.addView(btn(ad) { is_() }.apply { background = rounded(0xFF2E9E5B.toInt()) })
        }
        box.addView(satir, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Sabit genislik + olculmus yukseklik (WRAP_CONTENT bazi cihazlarda eziliyor)
        val gen = dp(376)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(gen, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        kart = box
        try {
            wm.addView(box, lp(gen, minOf(box.measuredHeight, minOf(screenW, screenH) - dp(24)))
                .apply { gravity = Gravity.CENTER })
        } catch (e: Exception) {
            kart = null
        }
    }

    /** Hatayi kisa haliyle kaydeder; dakikalik kontrolde sunucuya gonderilir */
    private fun hataKaydet(yer: String, e: Throwable) {
        TestLog.olay("HATA", yer, "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200))
        try {
            val iz = e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            val metin = "$yer: ${e.javaClass.simpleName}: ${e.message ?: ""} @ $iz".take(480)
            val zaman = System.currentTimeMillis() / 1000
            val pr = getSharedPreferences("hata", MODE_PRIVATE)
            // Sunucuya giden format (mesajKontrol'un okudugu "son"/"zaman"/"gonderildi") degismez
            pr.edit().putString("son", metin).putLong("zaman", zaman).putBoolean("gonderildi", false).commit()
            // Ayrica son 5 hatayi kaybetmeyen yerel bir gecmis tut (araliklarla tekrarlayan
            // bir hatanin ilk tetikleyicisi "son" tarafindan ezilip kaybolmasin)
            try {
                val arr = org.json.JSONArray(pr.getString("gecmis", "[]"))
                val ekle = org.json.JSONObject().put("t", zaman).put("m", metin)
                val yeni = org.json.JSONArray()
                for (i in maxOf(0, arr.length() - 4) until arr.length()) yeni.put(arr.get(i))
                yeni.put(ekle)
                pr.edit().putString("gecmis", yeni.toString()).apply()
            } catch (x2: Exception) {
            }
        } catch (x: Exception) {
        }
    }

    /** Dokunmak guvenli mi? Degilse sebebini dondurur */
    private fun dokunmaEngeli(now: Long): String? {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return "🌙"
        if (!oyundaMi()) return "⏸"   // sadece secili oyun ekrandayken dokun
        if (ScreenSampler.running && now - ScreenSampler.lastFrameAt > 3000) return "📷"
        return null
    }

    // ================= 🏪 Pazar Bot =================
    // Pazar kur: Commands -> OPEN MERCHANT -> ogretilen esyalara 2 kez dokun -> fiyat -> OK -> OK
    // Kontrol (her N dk): Commands -> SLAVE MERCHANT -> satilanlarin parasini al ->
    //   hepsi satildiysa Close Slave Merchant -> pazari yeniden kur.

    private val pzThread = HandlerThread("pazar").apply { start() }
    private val pzH = Handler(pzThread.looper)
    @Volatile private var pazarCalisiyor = false
    private var pzSonrakiKontrol = 0L
    private var pzArdArdaHata = 0   // ust uste basarisiz kurulum: bir sinir sonra guvenli dur

    private class PzSablonlar(
        val commands: Sablon, val openM: Sablon, val slaveM: Sablon, val create: Sablon,
        val fiyat: Sablon, val slave: Sablon, val satilmadi: Sablon, val slaveKapat: Sablon
    )
    private var pzT: PzSablonlar? = null

    private fun pzSablonYukle(s: Float): PzSablonlar? {
        fun y(ad: String, tol: Int) = Preset.loadTemplateF(this, ad, s, tol, 0.5f, 0.5f)
        return PzSablonlar(
            y("pz_commands.png", 24) ?: return null,
            y("pz_open_merchant.png", 18) ?: return null,
            y("pz_slave_merchant.png", 19) ?: return null,
            y("pz_create_baslik.png", 19) ?: return null,
            y("pz_fiyat_baslik.png", 17) ?: return null,
            y("pz_slave_baslik.png", 29) ?: return null,
            y("pz_satilmadi.png", 23) ?: return null,
            y("pz_slave_kapat.png", 21) ?: return null
        )
    }

    private fun pzUyu(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    /** Pazar thread'inden bekleyerek dokun (cift = iki kez hizli) */
    private fun pzDokun(x: Float, y: Float, cift: Boolean = false) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }, 0, 50))
        if (cift) b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x + 1f, y) }, 170, 50))
        ui.post {
            try {
                dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                    override fun onCancelled(d: GestureDescription?) { latch.countDown() }
                }, null)
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
    }

    /** Sablonu en fazla ms boyunca arar; bulursa sol-ust kosesinin ekran konumu */
    private fun pzBekle(t: Sablon, ms: Long): FloatArray? {
        val son = SystemClock.uptimeMillis() + ms
        while (pazarCalisiyor || pzOgretme) {
            ScreenSampler.grab()?.let { f ->
                findT(f, t)?.let { p ->
                    return floatArrayOf(ScreenSampler.toScreen(p[0].toFloat()), ScreenSampler.toScreen(p[1].toFloat()))
                }
            }
            if (SystemClock.uptimeMillis() > son) return null
            pzUyu(150)
        }
        return null
    }

    private fun pzS() = olcek?.s ?: (screenW / 2712f)

    /** Bir noktanin etrafindaki ortalama parlaklik (0-255) */
    private fun pzParlaklik(x: Float, y: Float, r: Float): Int {
        val k = ScreenSampler.bolge((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt()) ?: return 0
        var t = 0L
        for (c in k.third) t += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
        return if (k.third.isEmpty()) 0 else (t / k.third.size).toInt()
    }

    /** Envanter kutusundaki ikon (sag-alt adet sayisi haric), yari cozunurluk */
    private fun pzIkon(cx: Float, cy: Float): Triple<Int, Int, IntArray>? {
        val s = pzS()
        return ScreenSampler.bolge((cx - 38 * s).toInt(), (cy - 38 * s).toInt(), (cx + 20 * s).toInt(), (cy + 20 * s).toInt())
    }

    private fun pzIkonUyar(k: Triple<Int, Int, IntArray>, e: PazarEsya): Boolean {
        val (w, hh, px) = k
        if (kotlin.math.abs(w - e.w) > 1 || kotlin.math.abs(hh - e.h) > 1) return false
        val ww = minOf(w, e.w)
        val h2 = minOf(hh, e.h)
        var best = Long.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            var top = 0L
            var n = 0
            for (y in 1 until h2 - 1) for (x in 1 until ww - 1) {
                val yy = y + dy
                val xx = x + dx
                if (yy !in 0 until hh || xx !in 0 until w) continue
                top += ScreenSampler.diff(px[yy * w + xx] and 0xFFFFFF, e.px[y * e.w + x] and 0xFFFFFF)
                n++
            }
            if (n > 0) best = minOf(best, top / n)
        }
        return best < 75
    }

    /** Oyunun fiyat alanina yazar (erisilebilirlik ile); olmazsa klavyenin rakam tuslarina basar */
    private fun pzFiyatYaz(fiyat: Long) {
        var yazildi = false
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            try {
                val root = rootInActiveWindow
                var n = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                if (n == null && root != null) n = pzYaziAlaniBul(root)
                if (n != null) {
                    val b = android.os.Bundle()
                    b.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        fiyat.toString()
                    )
                    yazildi = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                }
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        if (!yazildi) {
            // Yedek: sayi klavyesi (ekran oranlari, oyunun acildigi klavye duzeni)
            val tus = mapOf(
                '1' to (432f to 758f), '2' to (1039f to 758f), '3' to (1647f to 758f),
                '4' to (432f to 890f), '5' to (1039f to 890f), '6' to (1647f to 890f),
                '7' to (432f to 1019f), '8' to (1039f to 1019f), '9' to (1647f to 1019f),
                '0' to (1039f to 1148f)
            )
            for (ch in fiyat.toString()) {
                val (kx, ky) = tus[ch] ?: continue
                pzDokun(kx / 2712f * screenW, ky / 1220f * screenH)
                pzUyu(120)
            }
        }
        pzUyu(300)
        // Klavyedeki TAMAM / onay
        val tamam = java.util.concurrent.CountDownLatch(1)
        var basildi = false
        ui.post {
            try {
                val root = rootInActiveWindow
                for (ad in listOf("TAMAM", "Tamam", "OK", "Done", "Bitti")) {
                    val l = root?.findAccessibilityNodeInfosByText(ad) ?: continue
                    val d = l.firstOrNull { it.isClickable } ?: l.firstOrNull()?.parent
                    if (d != null && d.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        basildi = true; break
                    }
                }
            } catch (e: Exception) {
            }
            tamam.countDown()
        }
        try { tamam.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        if (!basildi) pzDokun(2253f / 2712f * screenW, 1148f / 1220f * screenH)   // klavyedeki ✓
    }

    private fun pzYaziAlaniBul(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            pzYaziAlaniBul(c)?.let { return it }
        }
        return null
    }

    private fun pzDurum(t: String) = ui.post { statusTv?.text = t }

    /** Pazari kurar; konan esya sayisi (-1 = hata) */
    private fun pazarKur(): Int {
        val t = pzT ?: return -1
        val s = pzS()
        val esyalar = Config.pazarEsyalar(this)
        if (esyalar.isEmpty()) return -1
        pzDurum("🏪 Pazar kuruluyor")
        val cm = pzBekle(t.commands, 2500) ?: return -1
        pzDokun(cm[0] + 77 * s, cm[1] + 24 * s)
        val om = pzBekle(t.openM, 3000) ?: return -1
        pzDokun(om[0] + 135 * s, om[1] + 30 * s)
        val cr = pzBekle(t.create, 3500) ?: return -1
        pzUyu(500)
        var konan = 0
        loop@ for (r in 0 until 4) for (c in 0 until 7) {
            if (!pazarCalisiyor) return konan
            if (konan >= 12) break@loop
            val cx = cr[0] + (-144 + 100 * c) * s
            val cy = cr[1] + (395 + 100.67f * r) * s
            val ikon = pzIkon(cx, cy) ?: continue
            val e = esyalar.firstOrNull { pzIkonUyar(ikon, it) } ?: continue
            pzDokun(cx, cy, cift = true)
            val fp = pzBekle(t.fiyat, 2500) ?: continue
            pzDokun(fp[0] + 276 * s, fp[1] + 371 * s)      // fiyat alani
            pzUyu(900)
            pzFiyatYaz(e.fiyat)
            pzUyu(700)
            val fp2 = pzBekle(t.fiyat, 1500)
            if (fp2 != null) pzDokun(fp2[0] + 163 * s, fp2[1] + 464 * s)   // OK
            pzUyu(900)
            konan++
            pzDurum("🏪 $konan eşya kondu")
        }
        if (konan == 0) {
            pzDokun(cr[0] + 253 * s, cr[1] + 804 * s)   // CANCEL
            return 0
        }
        pzDokun(cr[0] + 66 * s, cr[1] + 804 * s)        // OK: pazar acilir
        pzUyu(1500)
        return konan
    }

    /** Satislari kontrol eder: paralari alir, hepsi satildiysa pazari yeniden kurar */
    private fun pazarKontrol() {
        val t = pzT ?: return
        val s = pzS()
        pzDurum("🏪 Kontrol")
        val cm = pzBekle(t.commands, 2500) ?: return
        pzDokun(cm[0] + 77 * s, cm[1] + 24 * s)
        val sm = pzBekle(t.slaveM, 3000) ?: return
        pzDokun(sm[0] + 135 * s, sm[1] + 29 * s)
        val sb = pzBekle(t.slave, 3500)
        if (sb == null) {
            // Pazar yok gibi: dogrudan kur
            kurVeBildir(); return
        }
        pzUyu(600)
        var alinan = 0
        for (k in 0 until 6) {
            val ry = sb[1] + (209 + 104 * k) * s
            val ikonVar = pzParlaklik(sb[0] - 239 * s, ry, 20 * s) > 35
            if (!ikonVar) continue
            val cx = sb[0] + 513 * s
            if (pzParlaklik(cx, ry, 22 * s) > 45) {   // para isareti
                pzDokun(cx, ry)
                alinan++
                pzUyu(800)
            }
        }
        pzUyu(1200)
        val f = ScreenSampler.grab()
        val kalanVar = f != null && findT(f, t.satilmadi) != null
        if (alinan > 0) bildirim(4100, "🏪 Pazar", "$alinan eşya satıldı, paraları toplandı")
        if (!kalanVar) {
            // Hepsi satildi: pazari kapat ve yeniden kur
            val kp = pzBekle(t.slaveKapat, 2000)
            if (kp != null) pzDokun(kp[0] + 210 * s, kp[1] + 25 * s)
            else pzDokun(sb[0] + 561 * s, sb[1] + 23 * s)
            pzUyu(2000)
            kurVeBildir()
        } else {
            pzDokun(sb[0] + 561 * s, sb[1] + 23 * s)    // X ile kapat
            pzUyu(600)
        }
    }

    private fun kurVeBildir() {
        val n = pazarKur()
        when {
            n > 0 -> {
                pzArdArdaHata = 0
                bildirim(4101, "🏪 Pazar kuruldu", "$n eşya satışa kondu")
            }
            n == 0 -> {
                pzArdArdaHata = 0
                bildirim(4102, "🏪 Pazar", "Satılacak eşya kalmadı, Pazar Bot durdu")
                ui.post { pazarDurdur() }
            }
            else -> {
                pzArdArdaHata++
                if (pzArdArdaHata >= 3) {
                    bildirim(4103, "🏪 Pazar Bot durdu",
                        "Commands/Merchant ekranı $pzArdArdaHata kez art arda tanınamadı. " +
                            "Oyun ekranını kontrol et, gerekirse tekrar başlat.")
                    ui.post { pazarDurdur() }
                } else {
                    toast("🏪 Pazar kurulamadı ($pzArdArdaHata/3), tekrar denenecek")
                }
            }
        }
    }

    private val pazarDongu = Runnable {
        try {
            // Ekrani tani ve sablonlari yukle
            val o = olcek ?: Preset.olcekBul(this)
            if (o == null) {
                toast("Oyun ekranı tanınamadı"); ui.post { pazarDurdur() }; return@Runnable
            }
            olcek = o
            pzT = pzSablonYukle(o.s)
            if (pzT == null) {
                toast("Pazar görüntüleri yüklenemedi"); ui.post { pazarDurdur() }; return@Runnable
            }
            // Ilk tur: once kontrol et (pazar zaten aciksa bozma), gerekirse kur
            pazarKontrol()
            while (pazarCalisiyor) {
                pzSonrakiKontrol = SystemClock.uptimeMillis() + Config.pazarDk(this) * 60_000L
                while (pazarCalisiyor && SystemClock.uptimeMillis() < pzSonrakiKontrol) {
                    val kalan = (pzSonrakiKontrol - SystemClock.uptimeMillis()) / 1000
                    pzDurum("🏪 %d:%02d".format(kalan / 60, kalan % 60))
                    pzUyu(1000)
                }
                if (!pazarCalisiyor) break
                // Oyun ekranda degilse bekle
                while (pazarCalisiyor && !oyundaMi()) {
                    pzDurum("🏪 ⏸"); pzUyu(2000)
                }
                if (pazarCalisiyor) pazarKontrol()
            }
        } catch (e: Exception) {
            hataKaydet("pazar", e)
        }
    }

    private fun pazarBaslat() {
        pzArdArdaHata = 0
        if (Config.pazarEsyalar(this).isEmpty()) {
            toast("Önce satılacak eşyaları ekle: ⋯ Bot seç → 🏪 Pazar Bot → ➕ Eşya ekle")
            pazarKarti(); return
        }
        pazarCalisiyor = true
        playBtn?.text = "⏸ DURDUR"
        pzDurum("🏪 Başlıyor")
        pzH.post(pazarDongu)
    }

    private fun pazarDurdur() {
        pazarCalisiyor = false
        pzH.removeCallbacksAndMessages(null)
        playBtn?.text = "▶ BAŞLAT"
        statusTv?.text = "🏪 Durdu"
    }

    // ---------- Pazar ayarlari ve esya ogretme ----------
    @Volatile private var pzOgretme = false

    private fun pazarKarti() {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF00E0D08.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "🏪 Pazar Bot ayarları"
            setTextColor(0xFFD2A866.toInt()); textSize = 17f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Pazarı kurar, satışları her ${Config.pazarDk(this@MacroService)} dakikada kontrol edip paraları toplar, " +
                "hepsi satılınca aynı yerde yeniden kurar. Sadece listedeki eşyaları satar."
            setTextColor(0xFFB0B8C4.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        val esyalar = Config.pazarEsyalar(this)
        if (esyalar.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Henüz eşya yok. Commands → OPEN MERCHANT'ı aç, sonra ➕ Eşya ekle."
                setTextColor(Color.WHITE); textSize = 13f
                setPadding(0, 0, 0, dp(6))
            })
        }
        esyalar.forEachIndexed { i, e ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val ikon = ImageView(this).apply {
                setImageBitmap(Bitmap.createBitmap(e.px.map { it or (0xFF shl 24) }.toIntArray(), e.w, e.h, Bitmap.Config.ARGB_8888))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            r.addView(ikon, LinearLayout.LayoutParams(dp(34), dp(34)))
            r.addView(TextView(this).apply {
                text = "  " + "%,d".format(e.fiyat).replace(',', '.') + " coin"
                setTextColor(Color.WHITE); textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(menuBtn("✎") { pzFiyatSor(e.fiyat) { yeni -> val l = Config.pazarEsyalar(this); if (i < l.size) { l[i].fiyat = yeni; Config.pazarEsyalarYaz(this, l) }; pazarKarti() } })
            r.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            r.addView(menuBtn("🗑") { val l = Config.pazarEsyalar(this); if (i < l.size) l.removeAt(i); Config.pazarEsyalarYaz(this, l); pazarKarti() })
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        }
        box.addView(menuBtn("➕ Eşya ekle (OPEN MERCHANT açıkken)") { pzEsyaOgret() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        val dr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dr.addView(TextView(this).apply { text = "⏱ Kontrol aralığı"; setTextColor(Color.WHITE); textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dr.addView(menuBtn("−") { Config.pazarDkYaz(this, (Config.pazarDk(this) - 5).coerceIn(5, 240)); pazarKarti() })
        dr.addView(TextView(this).apply {
            text = "${Config.pazarDk(this@MacroService)} dk"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        dr.addView(menuBtn("+") { Config.pazarDkYaz(this, (Config.pazarDk(this) + 5).coerceIn(5, 240)); pazarKarti() })
        box.addView(dr)
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ar.addView(btn("▶ BAŞLAT") { removeOverlay(); startMacro() }.apply { background = koButon(1) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        ar.addView(btn("Kapat") { removeOverlay() }.apply { background = koButon() },
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(ar)
        ortadaGoster(box, dp(430))
    }

    /** Create a Merchant acikken envanterdeki bir esyaya dokunarak listeye ekle */
    @SuppressLint("ClickableViewAccessibility")
    private fun pzEsyaOgret() {
        removeOverlay()
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı: önce ▶ ile bir kere başlatıp durdur"); return
        }
        pzOgretme = true
        pzH.post {
            try {
                val o = olcek ?: Preset.olcekBul(this)
                if (o == null) { toast("Oyun ekranı tanınamadı"); return@post }
                olcek = o
                if (pzT == null) pzT = pzSablonYukle(o.s)
                val t = pzT ?: return@post
                val cr = pzBekle(t.create, 800)
                if (cr == null) {
                    toast("Önce Commands → OPEN MERCHANT'ı aç, eşyalar görünürken ➕'ya bas"); return@post
                }
                ui.post { pzDokunusYakala(cr) }
            } finally {
                pzOgretme = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pzDokunusYakala(cr: FloatArray) {
        removeOverlay()
        val v = FrameLayout(this).apply { setBackgroundColor(0x33000000); isClickable = true }
        v.addView(TextView(this).apply {
            text = "Satmak istediğin eşyaya dokun"
            setTextColor(Color.WHITE); textSize = 14f
            background = rounded(0xE6000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(24) })
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val tx = e.rawX
                val ty = e.rawY
                removeOverlay()
                val s = pzS()
                // En yakin envanter kutusu
                var en = Float.MAX_VALUE
                var bx = 0f
                var by = 0f
                for (r in 0 until 4) for (c in 0 until 7) {
                    val cx = cr[0] + (-144 + 100 * c) * s
                    val cy = cr[1] + (395 + 100.67f * r) * s
                    val d = (cx - tx) * (cx - tx) + (cy - ty) * (cy - ty)
                    if (d < en) { en = d; bx = cx; by = cy }
                }
                ui.postDelayed({
                    val k = pzIkon(bx, by)
                    if (k == null) {
                        toast("İkon okunamadı, tekrar dene")
                    } else {
                        pzFiyatSor(0L) { fiyat ->
                            val l = Config.pazarEsyalar(this)
                            l.add(PazarEsya(k.first, k.second, k.third, fiyat))
                            Config.pazarEsyalarYaz(this, l)
                            toast("🏪 Eklendi: " + "%,d".format(fiyat).replace(',', '.') + " coin")
                            pazarKarti()
                        }
                    }
                }, 400)
            }
            true
        }
        overlay = v
        try {
            wm.addView(v, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            overlay = null
        }
    }

    /** Fiyat girisi: klavye acilabilen pencere */
    private fun pzFiyatSor(baslangic: Long, sonra: (Long) -> Unit) {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF00E0D08.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "💰 Bu eşyanın fiyatı (adet başı)"
            setTextColor(0xFFD2A866.toInt()); textSize = 15f
            setPadding(0, 0, 0, dp(6))
        })
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); textSize = 20f
            hint = "ör. 9599866"
            setHintTextColor(0xFF777777.toInt())
            if (baslangic > 0) setText(baslangic.toString())
        }
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        ar.addView(btn("✓ Kaydet") {
            val f = et.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
            if (f <= 0L) { toast("Fiyat gir"); return@btn }
            removeOverlay(); sonra(f)
        }.apply { background = koButon(1) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        ar.addView(btn("İptal") { removeOverlay(); pazarKarti() }.apply { background = koButon(2) },
            LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(ar)
        // Klavye acilabilsin diye odaklanabilir pencere
        val p = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(20)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        overlay = box
        try {
            wm.addView(box, p)
            et.requestFocus()
        } catch (e: Exception) {
            overlay = null
        }
    }

    // ================= Ekran kaydi uyumlu mod =================

    /**
     * Acikken ekran paylasimi (MediaProjection) kullanilmaz; ekran goruntusu erisilebilirlik
     * izniyle alinir (Android 11+, saniyede ~3). Boylece ekran kaydi ile makro ayni anda calisir.
     */
    /**
     * Sistem ekran paylasimini tamamen kesti (baska bir uygulama ekrani aldi, izin geri cekildi vb.).
     * Makro artik goremiyor: kor kor dokunmaya devam etmek yerine calisan botu guvenle durdurur.
     */
    fun projeksiyonKesildi() {
        ui.post {
            if (!(running || pazarCalisiyor)) return@post
            val neden = "Ekran paylaşımı kesildi (başka bir kayıt/yayın uygulaması ekranı almış olabilir)."
            if (pazarCalisiyor) pazarDurdur()
            if (running) stopMacro(neden)
            bildirim(4300, "📷 Bot durduruldu", "$neden Oyunu kontrol edip ▶ ile tekrar başlat.")
        }
    }

    private fun showBarChooser() {
        if (running) {
            toast("Önce botu durdur"); return
        }
        showMenu(
            "Hangi bar? (bar DOLUYKEN kaydet)",
            listOf(
                "HP barı" to { startRecord(Mod.HP) },
                "MP barı" to { startRecord(Mod.MP) },
                "Hedef mobun can barı" to { startRecord(Mod.HEDEF_BAR) }
            )
        )
    }

    private fun showLootChooser() {
        if (running) {
            toast("Önce botu durdur"); return
        }
        showMenu(
            "Buton ekranda GÖRÜNÜRKEN kaydet",
            listOf(
                "'Open' butonu" to { startRecord(Mod.OPEN) },
                "'Collect All' butonu" to { startRecord(Mod.COLLECT) }
            )
        )
    }

    private fun showTypeChooser(x: Int, y: Int) {
        showMenu(
            "Bu tuş ne? ($x, $y)",
            (listOf("hedef", "skill", "hp_pot", "mp_pot", "saldiri") +
                (if (Config.minorVar(this)) listOf("minor") else emptyList())).map { t ->
                Config.label(t) to { savePoint(t, x, y) }
            }
        )
    }

    // ================= Kayit =================

    @SuppressLint("ClickableViewAccessibility")
    private fun startRecord(mode: Mod) {
        if (running) {
            toast("Önce botu durdur"); return
        }
        if (mode != Mod.BUTON && !ScreenSampler.running) {
            toast("Önce uygulamadan 'Ekran okumayı başlat'a bas"); return
        }
        removeOverlay()
        updateScreenSize()

        // Tam ekran, boyutu elle verilen kayit katmani (MATCH_PARENT bazi cihazlarda sorun cikariyor)
        val v = FrameLayout(this).apply {
            setBackgroundColor(0x44000000)
            isClickable = true
        }
        val info = TextView(this).apply {
            text = when (mode) {
                Mod.BUTON -> "Kaydedilecek tuşa dokun"
                Mod.HP -> "HP barı DOLUYKEN, pot basılacak seviyeye dokun"
                Mod.MP -> "MP barı DOLUYKEN, pot basılacak seviyeye dokun"
                Mod.HEDEF_BAR -> "Bir mob seçiliyken, onun can barının SOL ucuna yakın kırmızı kısma dokun"
                Mod.OPEN -> "'Open' butonunun SOL ÜST köşesine dokun (biraz içinden)"
                Mod.COLLECT -> "'Collect All' butonunun SOL ÜST köşesine dokun (biraz içinden)"
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(0xDD000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val cancel = btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) }
        // Talimat kutusu: sabit genislik, ekranin sol ortasinda (oyun tuslarini kapatmaz)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        top.addView(info, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        top.addView(cancel, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        v.addView(
            top,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { leftMargin = dp(16) }
        )

        val iki = mode == Mod.OPEN || mode == Mod.COLLECT
        var fx = -1
        var fy = -1
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                if (iki && fx < 0) {
                    fx = x
                    fy = y
                    info.text = "Şimdi aynı butonun SAĞ ALT köşesine dokun (biraz içinden)"
                } else {
                    removeOverlay()
                    when {
                        iki -> onTemplate(mode, fx, fy, x, y)
                        mode == Mod.BUTON -> showTypeChooser(x, y)
                        else -> onColorPoint(mode, x, y)
                    }
                }
            }
            true
        }

        overlay = v
        try {
            wm.addView(v, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            overlay = null
            toast("Kayıt ekranı açılamadı")
        }
    }

    private fun onColorPoint(mode: Mod, x: Int, y: Int) {
        // Kayit ekrani kapandiktan sonra temiz kareyi bekle
        ui.postDelayed({
            val c = ScreenSampler.readPixel(x, y)
            if (c < 0) {
                toast("Renk okunamadı, tekrar dene")
            } else {
                val cf = Config.load(this)
                cf.otoArayuz = false   // elle kayit: otomatik tanima kapanir
                val rn = RenkNokta(x, y, c)
                val ad = when (mode) {
                    Mod.HP -> { cf.hp = rn; "HP" }
                    Mod.MP -> { cf.mp = rn; "MP" }
                    else -> { cf.tgtBar = rn; "Hedef barı" }
                }
                cf.save(this)
                toast("$ad kaydedildi (%d,%d) #%06X".format(x, y, c))
            }
        }, 500)
    }

    private fun onTemplate(mode: Mod, x1: Int, y1: Int, x2: Int, y2: Int) {
        ui.postDelayed({
            val f = ScreenSampler.grab()
            val t = if (f == null) null else ScreenSampler.crop(f, x1, y1, x2, y2)
            if (t == null) {
                toast("Kaydedilemedi. Butonun köşelerine daha geniş dokun")
            } else {
                val cf = Config.load(this)
                cf.otoArayuz = false   // elle kayit: otomatik tanima kapanir
                if (mode == Mod.OPEN) {
                    cf.openT = t
                } else {
                    cf.collectT = t
                    cf.collectT2 = null
                }
                cf.save(this)
                toast(if (mode == Mod.OPEN) "Open kaydedildi ✓" else "Collect All kaydedildi ✓")
            }
        }, 500)
    }

    private fun savePoint(type: String, x: Int, y: Int) {
        val cf = Config.load(this)
        if (type == "skill") {
            val n = cf.points.count { it.type == "skill" } + 1
            cf.points.add(Nokta("Skill $n", "skill", x, y, 10f))
            toast("Skill $n kaydedildi. Cooldown'u uygulamadan ayarla")
        } else {
            cf.points.removeAll { it.type == type }
            cf.points.add(Nokta(Config.label(type), type, x, y))
            toast("${Config.label(type)} kaydedildi")
        }
        cf.save(this)
    }

    // ================= Motor =================

    /** Uygulamadaki buyuk BASLAT butonu: oyun acildiktan sonra baslat */
    fun startFromApp(delayMs: Long) {
        ui.postDelayed({
            if (!running && !pendingStart) startMacro()
        }, delayMs)
    }

    fun isRunning() = running

    private fun modYazi() = when {
        Config.pazarMi(this) -> "🏪 Pazar ▾"
        Config.koMu(this) -> "🌾 Farm • KO ▾"
        else -> "🌾 Farm ▾"
    }

    /** Oyun ici ana menu: Farm Bot / PK Bot / Pazar Bot */
    /** Hesabin gorme yetkisi olmayan bir modda (PK/Pazar) kalmis mi? */
    private fun yetkisizModdaMi(): Boolean = Lisans.gecerliSimdi() &&
        ((Config.pkMi(this) && !Config.botGorunur("pk")) || (Config.pazarMi(this) && !Config.botGorunur("pazar")))

    private fun modMenu() {
        val farmAcik = Config.botGorunur("farm")
        val pkAcik = Config.botGorunur("pk")
        val pazarAcik = Config.botGorunur("pazar")
        val genieAcik = Config.botGorunur("genie")
        val say = listOf(farmAcik, pkAcik, pazarAcik, genieAcik).count { it }
        if (say <= 1) {
            // Tek mod acik: secenek yok, dogrudan o modun ayarlari
            Config.modDuzelt(this)
            cfg = Config.load(this)
            modBtn?.text = modYazi()
            ayarKarti()
            return
        }
        val pk = Config.pkMi(this)
        val genie = Config.genieMi(this)
        val liste = ArrayList<Pair<String, () -> Unit>>()
        if (farmAcik) liste.add((if (!pk && !genie && !Config.pazarMi(this)) "✓ " else "") + "🌾 Farm Bot" to {
            modSec(false)
            ui.postDelayed({ ayarKarti() }, 250)
        })
        if (genieAcik) liste.add((if (genie) "✓ " else "") + "🧞 Genie Hızlandır" to {
            genieSecServis()
            ui.postDelayed({ ayarKarti() }, 250)
        })
        if (pkAcik) liste.add((if (pk) "✓ " else "") + "⚔ PK Bot" to { pkKarakterMenu() })
        if (pazarAcik) liste.add((if (Config.pazarMi(this)) "✓ " else "") + "🏪 Pazar Bot" to {
            if (running) stopMacro()
            Config.pazarSec(this)
            modBtn?.text = modYazi()
            statusTv?.text = "🏪 Pazar hazır"
            pazarKarti()
        })
        showMenu("Bot seç", liste)
    }

    /** PK Bot: once karakter, sonra o karakterin ayarlari */
    private fun pkKarakterMenu() {
        val pk = Config.pkMi(this)
        val sn = Config.sinif(this)
        val liste = ArrayList<Pair<String, () -> Unit>>()
        for ((kod, ad) in Config.SINIFLAR) {
            liste.add((if (pk && sn == kod) "✓ " else "") + ad to {
                modSec(true, kod)
                ui.postDelayed({ ayarKarti() }, 250)
            })
        }
        showMenu("⚔ PK Bot • karakter seç", liste)
    }

    /** Genie Hizlandir moduna gec (uygulamadan da cagrilir) */
    fun genieSecServis() {
        ui.post {
            if (Config.genieMi(this)) return@post
            if (running) stopMacro()
            if (pazarCalisiyor) pazarDurdur()
            Config.genieSec(this)
            cfg = Config.load(this)
            modBtn?.text = modYazi()
            statusTv?.text = "Genie hazır"
            toast("🧞 Genie Hızlandır")
        }
    }

    /** Modu secer; her modun kendi tus duzeni ve ayarlari yuklenir (uygulamadan da cagrilir) */
    fun modSec(pk: Boolean, sinif: String? = null) {
        ui.post {
            if (!Config.pazarMi(this) && !Config.genieMi(this) && Config.pkMi(this) == pk && (!pk || sinif == null || sinif == Config.sinif(this))) {
                return@post
            }
            if (running) stopMacro()
            if (pazarCalisiyor) pazarDurdur()
            Config.modDegistir(this, pk, sinif)
            cfg = Config.load(this)
            modBtn?.text = modYazi()
            statusTv?.text = if (pk) "PK hazır" else "Farm hazır"
            toast(if (pk) "⚔ PK Bot • ${Config.sinifAd(this)}" else "🌾 Farm Bot")
        }
    }

    private fun toggle() {
        when {
            pazarCalisiyor -> pazarDurdur()
            running -> stopMacro()
            pendingStart -> {
                pendingStart = false
                statusTv?.text = "Hazır"
            }
            else -> startMacro()
        }
    }

    /** Ekran izni yoksa izni iste, verilince makroyu kendiliginden baslat */
    private fun requestCaptureThenStart() {
        pendingStart = true
        statusTv?.text = "İzin..."
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTO, true)
            )
        } catch (e: Exception) {
            pendingStart = false
            statusTv?.text = "Hazır"
            toast("Uygulamayı açıp 'Ekran okumayı başlat'a bas")
            return
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!pendingStart) return
                if (ScreenSampler.running) {
                    // Oyuna donulmesini ve ilk karelerin gelmesini bekle
                    ui.postDelayed({
                        if (pendingStart) {
                            pendingStart = false
                            startMacro()
                        }
                    }, 1500)
                    return
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    pendingStart = false
                    statusTv?.text = "Hazır"
                    toast("Ekran izni verilmedi, bot başlamadı")
                    return
                }
                ui.postDelayed(this, 400)
            }
        }, 400)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val left = ((endAtReal - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            // Uyelik: 10 dakikada bir sunucudan tekrar kontrol
            val simdi = SystemClock.uptimeMillis()
            bekciKontrol(simdi)
            if (!running) return
            TestLog.tik(kesilen, toplanan, basilanPot)
            if (!Lisans.gecerliSimdi()) {
                stopMacro("Üyelik doğrulanamadı ya da süresi doldu. Bot durdu")
                return
            }
            if (!lisansKontrolde && simdi - sonLisansKontrol > 10 * 60 * 1000L) {
                sonLisansKontrol = simdi
                lisansKontrolde = true
                Lisans.arkaPlanKontrol(this@MacroService) { r ->
                    lisansKontrolde = false
                    // Internet gecici koptuysa hemen durdurma; sadece sunucu "hayir" derse dur
                    if (!r.ok && !r.ag && running) stopMacro("Üyelik: ${r.mesaj}. Bot durdu")
                }
            }
            val ne = when {
                bekleNeden.isNotEmpty() -> bekleNeden
                otoMod && olcek == null -> "🔍"
                simdi < kilitBekleUntil || simdi < alanBekleUntil || simdi < menzilBekleUntil -> "💤"
                lootPhase != Loot.BOS -> "📦"
                else -> "⚔"
            }
            val neYazi = if (ne == "🔍") "🔍 oyun ekranı aranıyor" else ne
            durumYaz("● ÇALIŞIYOR " + townDurum() + (if (pkAktif) "PK " else "") + "$neYazi %d:%02d".format(left / 60, left % 60) +
                "  🗡$kesilen 📦$toplanan", true)
            ui.postDelayed(this, 1000)
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    private fun startMacro() {
        removeOverlay()
        closeEditor()
        // Yonetici olmayan hesap onceki oturumdan PK/Pazar'da kaldiysa Farm'a don
        if (yetkisizModdaMi()) {
            Config.modDegistir(this, false)
            cfg = Config.load(this)
            modBtn?.text = modYazi()
        }
        if (!oyundaMi()) {
            toast("Önce oyunu (${Config.oyunAd(this)}) aç, sonra ▶")
            return
        }
        // 🏪 Pazar Bot: ekran okuma gerekli, sonra pazar dongusu
        if (Config.pazarMi(this)) {
            if (!ScreenSampler.running) {
                requestCaptureThenStart(); return
            }
            pazarBaslat(); return
        }
        if (Lisans.guncelleGerekli) {
            guncelleKarti()
            return
        }
        // Uyelik kontrolu
        if (!Lisans.gecerliSimdi()) {
            if (lisansKontrolde) return
            lisansKontrolde = true
            statusTv?.text = "Lisans..."
            Lisans.arkaPlanKontrol(this) { r ->
                lisansKontrolde = false
                if (r.ok) {
                    startMacro()
                } else {
                    statusTv?.text = "Giriş yok"
                    toast(r.mesaj)
                    openApp()
                }
            }
            return
        }
        cfg = Config.load(this)
        // Mod yetkisi panelden: secili mod verilmemisse verilen moda gec
        Config.modDuzelt(this)
        cfg = Config.load(this)
        modBtn?.text = modYazi()
        val secili = Config.bot(this)
        if (secili != "pazar" && !Lisans.ozellikVar(secili)) {
            toast("Bu mod için yetkin yok. Yöneticiden Farm, PK ya da Genie yetkisi iste.")
            return
        }
        genieMod = Config.genieMi(this)
        if (genieMod && cfg.points.none { it.type == "saldiri" }) {
            toast("Genie Hızlandır için Kılıç tuşunu kaydet")
            openEditor()
            return
        }
        // Tuslar oyuncunun kendisinden: hic tus yoksa ekran ayarini yukle, tus duzenleyiciyi ac
        if (cfg.points.isEmpty()) {
            Config.hazirAyar(this)
            cfg = Config.load(this)
            toast("Önce tuşlarını kaydet: saldırı, mob seç, HP/MP pot ve skillerin yerine dokun")
            openEditor()
            return
        }
        if (!Config.pkMi(this) && !Config.genieMi(this) && cfg.points.none { it.type == "hedef" || it.type == "saldiri" }) {
            toast("Farm için en az bir Mob seç ya da Saldırı tuşu kaydet (⋯ → Tuşları düzenle)")
            openEditor()
            return
        }
        val wantsScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (wantsScreen && !ScreenSampler.running) {
            requestCaptureThenStart()
            return
        }
        pkAktif = false   // PK bu sürümde tamamen kapalı — sadece Farm
        koAktif = Config.koMu(this)
        koSonKutu = 0L
        if (koAktif) {
            // Yonetici sarti: KO'da seri basma yok (hiz ayari ne olursa olsun)
            cfg.minDelay = cfg.minDelay.coerceAtLeast(700)
            cfg.maxDelay = cfg.maxDelay.coerceAtLeast(cfg.minDelay + 600)
        }
        // Test modu: SADECE yönetimin "Test" yetkisi verdiği hesapta ve modu açıkken
        testCalisiyor = Lisans.testHesabi() && (cfg.testModu || cfg.townAcik)
        if (testCalisiyor) testAyarlariUygula()
        alanBitti = false
        alanArdArda = 0
        alanBekleUntil = 0L
        menzilBekleUntil = 0L
        menzilArdArda = 0
        pkSira = 0
        pkSonSkill = 0L
        pkSonKilic = 0L
        destekHazir.clear()
        collectVaryant = null
        minorAcik = false
        minorSon = 0L
        if (pkAktif && cfg.points.none { it.type == "saldiri" }) {
            toast("PK için ⚔ Kılıç tuşunu ata: ⋯ → 🛠 Tuşları düzenle"); return
        }
        if (!pkAktif && cfg.points.none { it.type == "hedef" || (it.type == "skill" && it.on) }) {
            toast("Önce + ile saldırı / mob seç / skill tuşu kaydet"); return
        }
        val needScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (needScreen && !ScreenSampler.running) {
            toast("Uyarı: ekran okuma kapalı. HP/MP, hedef barı ve kutu çalışmayacak")
        }
        updateScreenSize()
        if (koAktif && !pkSaf()) {
            isimRect = KoOyun.isimAlani(screenW, screenH)
            KoOyun.sifirla()
            KoOyun.barYukle(this, screenW, screenH)   // onceki seferde olculen bar boyu
            val ctx = applicationContext; val bw = screenW; val bh = screenH
            KoOyun.barlariBulArka(bw, bh) { KoOyun.barKaydet(ctx, bw, bh) }
            koBarAt = System.currentTimeMillis()
        }
        tgtStrip = Preset.tgtStrip(this, cfg.tgtBar)
        otoMod = cfg.otoArayuz && !koAktif && !pkSaf()   // KO: Myko otomatik tanimasi yok; PK "sadece tus": okuma yok
        pkSonIksir = 0L; pkIksirHazir.clear(); pkMinorBasildi = false; pkMinorHazir = 0L
        olcek = null
        sonTarama = 0L
        dcSay = 0
        sonDcKontrol = 0L
        hpBosSince = 0L
        oncekiCanli = false
        sonYuzde = 1f
        ilerlemeAt = 0L
        iptalBekliyor = false
        kesilen = 0
        toplanan = 0
        basilanPot = 0
        kilitKotu = 0
        kilitUyarildi = false
        kilitKanonCache.clear()
        isimBilinmiyorSince = 0L
        kilitBekleUntil = 0L
        hpBar = IntArray(3)
        mpBar = IntArray(3)
        dcT = if (koAktif) Preset.loadTemplateF(this, "ko_disconnect.png", Preset.landscapeSize(this).first / 2576f, 18, 0.5f, 0.5f)
        else if (cfg.otoArayuz) null
        else Preset.loadTemplateF(this, "disconnect.png", Preset.landscapeSize(this).first / 2712f, 20, 0.5f, 0.5f)
        hpSol = intArrayOf(-1, -1)
        taramaHata = 0
        h.removeCallbacksAndMessages(null)
        h.post {
            val now = SystemClock.uptimeMillis()
            endAt = now + cfg.minutes * 60_000L
            endAtReal = SystemClock.elapsedRealtime() + cfg.minutes * 60_000L
            nextTarget = 0L
            lastHpPot = 0L
            lastMpPot = 0L
            hpLowSince = 0L
            skillReady.clear()
            skillLast.clear()
            nextLootScan = 0L
            lootPhase = Loot.BOS
            phaseUntil = 0L
            lootPauseUntil = 0L
            collectStreak = 0
            lastOpenAt = 0L
            collectedSinceOpen = true
            doluKutuAt = 0L
        }
        running = true
        oturumBaslat()
        cantaDoluBayrak = false
        if (testCalisiyor && (cfg.rotaAcik || cfg.townAcik) && !koAktif) {
            rotaYukle()
            slotYukle()
            konumBaslat()
            townSonKontrol = SystemClock.elapsedRealtime()
            if (cfg.townAcik) rh.postDelayed({
                if (running && cfg.townAcik && !townHazirMi()) toast("⚠ Otomatik boşaltma açık ama yollar kayıtlı değil: ⋯ → Otomatik çanta boşaltma")
            }, 1500)
        }
        if (pkAktif && cfg.padAcik) padKur()
        oyunPaketi = sonPaket   // su an ondeki uygulama = oyun
        bekleNeden = ""

        sonLisansKontrol = SystemClock.uptimeMillis()
        playBtn?.text = "⏸ DURDUR"
        // Panel küçülmez: "● BOT ÇALIŞIYOR" yazısı ve ⏸ DURDUR düğmesi hep görünür
        ui.post {
            panelDaralt(false)
            durumYaz("● BOT BAŞLADI", true)
        }
        toast("▶ BOT BAŞLADI")
        stepPlanla( 700)
        h.postDelayed(kutuGozcu, 900)
        h.postDelayed(botKontrolGozcu, 3000)
        ui.removeCallbacks(statusTick)
        ui.post(statusTick)
    }

    // ================= Dayanıklılık + test modu =================

    private fun surumAdi(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Test modu hız merdiveni ve süre. Alt sınır A hiçbir basamakta aşılmaz. */
    private fun testAyarlariUygula() {
        if (!cfg.testModu) return   // hız merdiveni yalnızca eski test modunda; çanta döngüsü hızı değiştirmez
        if (cfg.testSureDk > 0) cfg.minutes = cfg.testSureDk
        val b = cfg.testBasamak
        if (b in 1..4) {
            val a = cfg.testAltMs
            var mn = 350
            var mx = 900
            when (b) {
                1 -> { mn = 350; mx = 900; cfg.skMin = 150; cfg.skMax = 700; cfg.pauseChance = 2 }
                2 -> { mn = 250; mx = 600; cfg.skMin = 100; cfg.skMax = 400; cfg.pauseChance = 1 }
                3 -> { mn = 180; mx = 400; cfg.skMin = 50; cfg.skMax = 250; cfg.pauseChance = 0 }
                else -> { mn = a; mx = a + 120; cfg.skMin = 0; cfg.skMax = 150; cfg.pauseChance = 0 }
            }
            cfg.minDelay = maxOf(mn, a)
            cfg.maxDelay = maxOf(mx, cfg.minDelay + 60)
        }
    }

    private fun testAyarOzeti(): String =
        "oyun=${Config.oyunAd(this)} sure=${cfg.minutes}dk basamak=${cfg.testBasamak} A=${cfg.testAltMs}ms " +
            "minDelay=${cfg.minDelay} maxDelay=${cfg.maxDelay} skMin=${cfg.skMin} skMax=${cfg.skMax} " +
            "mola=%${cfg.pauseChance} bekci=${cfg.testStallDk}dk dcBekle=${cfg.testDcSn}sn " +
            "kilit=${cfg.kilitler.size} cihaz=${Build.MODEL} android=${Build.VERSION.SDK_INT} " +
            "ekran=${screenW}x${screenH} surum=${surumAdi()}"

    private fun oturumBaslat() {
        sonIlerleme = SystemClock.uptimeMillis()
        hataSayisi = 0
        hataPenceresi = 0L
        duraklamaBas = 0L
        yakalamaVardi = ScreenSampler.running
        yakalamaYokSince = 0L
        koHpSifirSince = 0L
        koHpDusukSince = 0L
        koHpGordu = false
        koHpBak = 0L
        dcBekleBas = 0L
        dcBekleSonBak = 0L
        dcOk = 0
        stallSn = 0
        stallRef = kesilen + toplanan + basilanPot
        kontrolPencereBas = 0L
        if (testCalisiyor) {
            TestLog.oturumBasla(this, testAyarOzeti())
            agIzlemeBasla()
            toast("🏙 Çanta döngüsü açık: olaylar kaydediliyor")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", true).apply()
        }
    }

    private fun oturumKapat(reason: String?) {
        agIzlemeDurdur()
        if (testCalisiyor) {
            TestLog.oturumBitir(reason ?: "kullanıcı durdurdu")
            getSharedPreferences("test_durum", MODE_PRIVATE).edit().putBoolean("oturum_acik", false).apply()
        }
        testCalisiyor = false
    }

    /** Duraklama / bağlantı geçişlerinden sonra süre sayaçları haksız durdurmasın */
    private fun gecisSayaclariniSifirla() {
        hpLowSince = 0L
        hpBosSince = 0L
        dcSay = 0
        koHpSifirSince = 0L
        koHpDusukSince = 0L
        isimBilinmiyorSince = 0L
        kilitKotu = 0
    }

    private fun engelAd(e: String): String = when (e) {
        "🌙" -> "ekran kapalı"
        "⏸" -> "oyun ön planda değil"
        "📷" -> "ekran görüntüsü gelmiyor"
        else -> e
    }

    /** Art arda tekrarlayan hata: 60 sn içinde 8 hata olursa kontrollü dur */
    private fun hataPatlamasi() {
        val simdi = SystemClock.uptimeMillis()
        if (simdi - hataPenceresi > 60_000L) {
            hataPenceresi = simdi
            hataSayisi = 0
        }
        hataSayisi++
        if (hataSayisi >= 8) {
            TestLog.olay("HATA_PATLAMASI", "60 sn içinde $hataSayisi hata", "")
            stopMacro("Tekrarlayan hata: bot güvenli durduruldu")
        }
    }

    /** Saniyede bir: donma, izin kaybı ve ilerleme kontrolü */
    private fun bekciKontrol(simdi: Long) {
        if (!running) return
        // 1) Adım döngüsü yanıt veriyor mu? (dokunuş geri bildirimi hiç gelmezse bot sessizce donardı)
        if (sonIlerleme != 0L && simdi - sonIlerleme > 25_000L) {
            TestLog.olay("DONMA", "adım döngüsü yanıt vermiyor", "sessiz_ms=${simdi - sonIlerleme}")
            stopMacro("Bot yanıt vermiyor, güvenli durduruldu")
            return
        }
        // 2) Ekran paylaşımı çalışırken kapandıysa kör dokunma: dur
        if (yakalamaVardi && !ScreenSampler.running) {
            if (yakalamaYokSince == 0L) {
                yakalamaYokSince = simdi
            } else if (simdi - yakalamaYokSince > 5000L) {
                TestLog.olay("IZIN_KAYBI", "ekran paylaşımı kapandı", "")
                stopMacro("Ekran paylaşımı kapandı. Bot güvenli durduruldu")
                return
            }
        } else {
            yakalamaYokSince = 0L
        }
        // 3) Test modunda: uzun süre hiç mob/kutu/pot ilerlemesi yoksa (pop-up, ölüm, şehir, harita...) dur
        val ilerleme = kesilen + toplanan + basilanPot
        if (ilerleme != stallRef) {
            stallRef = ilerleme
            stallSn = 0
        } else if (bekleNeden.isEmpty()) {
            stallSn++
        }
        if (testCalisiyor && cfg.testStallDk > 0 && stallSn > cfg.testStallDk * 60) {
            TestLog.olay("ILERLEME_YOK", "uzun süre mob/kutu/pot ilerlemesi yok", "sn=$stallSn")
            stopMacro("${cfg.testStallDk} dk boyunca ilerleme yok (mob bulunamadı / ekran engeli?). Bot durdu")
        }
    }

    /** Test modu: kopma penceresi bekleniyor. true = bu adımda dokunma */
    private fun baglantiBekle(now: Long): Boolean {
        val d = dcT
        if (d != null && ScreenSampler.running && now - dcBekleSonBak > 1500L) {
            dcBekleSonBak = now
            val f = ScreenSampler.grab()
            if (f != null) {
                if (findT(f, d) == null) dcOk++ else dcOk = 0
            }
            if (dcOk >= 2) {
                val sure = now - dcBekleBas
                TestLog.olay("BAGLANTI_TOPARLANDI", "kopma penceresi kayboldu, bot devam ediyor", "sure_ms=$sure")
                dcBekleBas = 0L
                dcOk = 0
                gecisSayaclariniSifirla()
                return false
            }
        }
        if (now - dcBekleBas > cfg.testDcSn * 1000L) {
            TestLog.olay("BAGLANTI_GELMEDI", "bağlantı beklenen sürede gelmedi", "sure_sn=${cfg.testDcSn}")
            dcBekleBas = 0L
            stopMacro("Bağlantı ${cfg.testDcSn} sn içinde gelmedi. Bot durdu")
            return true
        }
        bekleNeden = "🔌"
        stepPlanla(500)
        return true
    }

    private fun agIzlemeBasla() {
        if (!testCalisiyor || agCallback != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val sure = if (agKopmaAt != 0L) SystemClock.elapsedRealtime() - agKopmaAt else 0L
                    agKopmaAt = 0L
                    TestLog.olay("AG_GELDI", "ağ bağlantısı geldi", if (sure > 0L) "kopukluk_ms=$sure" else "")
                }

                override fun onLost(network: android.net.Network) {
                    agKopmaAt = SystemClock.elapsedRealtime()
                    TestLog.olay("AG_KOPTU", "ağ bağlantısı kayboldu", "")
                }

                override fun onCapabilitiesChanged(network: android.net.Network, nc: android.net.NetworkCapabilities) {
                    val tur = if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) "wifi"
                    else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) "mobil" else "diger"
                    if (tur != sonAgTuru) {
                        sonAgTuru = tur
                        TestLog.olay("AG_TURU", tur, "")
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            agCallback = cb
        } catch (e: Exception) {
            hataKaydet("ag-izleme", e)
        }
    }

    private fun agIzlemeDurdur() {
        val cb = agCallback ?: return
        agCallback = null
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
    }

    /** Seçilen dokunuşları kategori ve nedenleriyle günlüğe yaz */
    private fun eylemLogla(sec: List<Pair<String, Hedef>>) {
        for ((kat, hd) in sec) {
            val neden = when (kat) {
                "hp" -> "can eşiği %${cfg.hpYuzde}"
                "mp" -> "mana eşiği %${cfg.mpYuzde}"
                "kutu" -> "kutu fazı=$lootPhase"
                "atak" -> "hedefCanlı=$oncekiCanli"
                else -> ""
            }
            TestLog.eylem(kat, "x=${hd.x.toInt()} y=${hd.y.toInt()}", neden)
        }
    }

    // Eski test menüsü kaldırıldı: ⋯ menüsünde yalnızca "Çanta döngüsü" kalır (Test yetkili hesap)
    private fun testMenu() {
        townSihirbaz()
    }

    private fun isaretMenu() {
        val etiketler = listOf(
            "Karakter öldü", "Şehir / güvenli bölge", "Işınlanma / harita değişimi", "Pop-up çıktı",
            "Bağlantı elle kesildi", "Bağlantı elle açıldı", "Mob yok / alan boş", "Diğer"
        )
        val maddeler = ArrayList<Pair<String, () -> Unit>>()
        for (e in etiketler) {
            maddeler.add(e to { TestLog.olay("ISARET", e, "test uzmanı işaretledi"); toast("📌 $e") })
        }
        maddeler.add("Geri" to { testMenu() })
        showMenu("📌 Olay işaretle", maddeler)
    }

    private fun raporKopyala() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Test raporu", TestLog.rapor(surumAdi())))
            toast("📋 Rapor panoya kopyalandı")
        } catch (e: Exception) {
            hataKaydet("rapor-kopya", e)
            toast("Rapor kopyalanamadı")
        }
    }

    private fun raporPaylas() {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_SUBJECT, "Projeindir Bot test raporu")
            i.putExtra(Intent.EXTRA_TEXT, TestLog.rapor(surumAdi()))
            val c = Intent.createChooser(i, "Raporu paylaş")
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(c)
        } catch (e: Exception) {
            hataKaydet("rapor-paylas", e)
            toast("Rapor paylaşılamadı")
        }
    }

    // ================= Çanta rotası (MykoMobile, yalnızca "Test" yetkili hesap) =================
    // Canlı X,Y koordinatı (HP/MP barının altı) okunur. Kaydedilen iz boyunca joystick ile Inn Hostes'e
    // yürünür; Open → "Inn Hostes Open" → envanterde eşyaya iki kez dokunarak bankaya atılır → kapat →
    // yürünen iz tersten izlenerek slota dönülür. Her aşamada zaman aşımı ve doğrulama vardır;
    // doğrulanamazsa kör basış yapılmaz, kontrollü durulur ve nedeni günlüğe yazılır.

    private val rotaThread = HandlerThread("rota").apply { start() }
    private val rh = Handler(rotaThread.looper)

    private val rotaIz = ArrayList<IntArray>()          // kayıtlı rota: slot -> Inn Hostes
    private val rotaYeni = ArrayList<IntArray>()        // kayıt sırasında biriken iz
    private var rotaKayitSon: IntArray? = null
    @Volatile private var rotaKayit = false
    @Volatile private var konumX = -1
    @Volatile private var konumY = -1
    @Volatile private var konumAt = 0L
    private var konumOkunuyor = false
    private var konumDonguAktif = false
    private var konumAdayX = -1
    private var konumAdayY = -1
    private var konumAdaySay = 0
    private var konumHataSay = 0
    private var konumLogAt = 0L
    private val konumGecmis = ArrayList<DoubleArray>()
    @Volatile private var rotaMesgul = false            // true: farm adımları dokunmaz
    @Volatile private var rotaCalisiyor = false
    private var cantaDoluBayrak = false
    private var rotaAsama = 0
    private var rotaMod = 0                              // 0 = tam çanta rotası, 1 = sadece yürüme testi
    private var rotaBas = 0L
    private var rotaTekrar = false
    private var rotaSayisi = 0
    private var rotaIsaret = 0                           // 0 ölçülmedi; +1 sağ itme yön açısını artırır, -1 azaltır
    private var rotaHizliDonus = false
    private var rotaBasKonum: IntArray? = null
    private val rotaYurunen = ArrayList<IntArray>()      // giderken izlenen gerçek yol (dönüş için)
    private var rotaYurunenSon: IntArray? = null
    private var bankaT: Sablon? = null
    private var miktarT: Sablon? = null
    private var rotaOpenT: Sablon? = null
    private var bIdx = 0
    private var bTasinan = 0

    // ---- yürüme durumu ----
    private var yuruAktif = false
    private var yuruYol: List<IntArray> = emptyList()
    private var yuruIdx = 0
    private var yuruMod = 0                              // 0 = kalibre-1 (ileri), 1 = kalibre-2 (ileri+sağ), 2 = izle
    private var yuruBas = 0L
    private var yuruZamanAsimi = 150_000L
    private var yuruTol = 4.0
    private var yuruKonumYokSay = 0
    private var yuruModBas = 0L
    private var yuruP0: IntArray? = null
    private var yuruV0 = 0.0
    private var yuruOnceMesafe = 0.0
    private var yuruOnceAt = 0L
    private var yuruKurtarma = 0
    private var yuruGeriBitis = 0L
    private var yuruSonuc: ((Boolean, String) -> Unit)? = null
    private var yuruS = 1                                // joystick açısı -> dünya açısı yön işareti (+1/-1)
    private var yuruC = 0.0                              // kamera/dünya ofseti (rad)
    private var yuruAKomut = 0.0
    private var yuruADegis = 0L

    // ---- joystick (tek dokunuş akışı: parça parça sürdürülen jest) ----
    private var jStroke: GestureDescription.StrokeDescription? = null
    private var jLx = 0f
    private var jLy = 0f
    private var jUcusta = false
    @Volatile private var jAktif = false
    private var jHata = 0
    @Volatile private var jDx = 0f
    @Volatile private var jDy = 0f

    // ---------------- kalıcı iz ----------------
    private fun rotaYukle() {
        val s = getSharedPreferences("rota", MODE_PRIVATE).getString("iz", "") ?: ""
        val liste = ArrayList<IntArray>()
        for (p in s.split(";")) {
            val a = p.split(",")
            if (a.size == 2) {
                val x = a[0].toIntOrNull()
                val y = a[1].toIntOrNull()
                if (x != null && y != null) liste.add(intArrayOf(x, y))
            }
        }
        rh.post {
            rotaIz.clear()
            rotaIz.addAll(liste)
        }
    }

    private fun rotaKaydet() {
        val sb = StringBuilder()
        for (p in rotaIz) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(p[0]).append(',').append(p[1])
        }
        getSharedPreferences("rota", MODE_PRIVATE).edit().putString("iz", sb.toString()).apply()
    }

    private fun rotaHazirMi(): Boolean = rotaIz.size >= 2 && !Config.koMu(this) && ScreenSampler.running

    // ---------------- canlı koordinat okuma ----------------
    private val konumDongusu = object : Runnable {
        override fun run() {
            if (!konumDonguAktif) return
            try {
                konumOku()
            } catch (e: Exception) {
                hataKaydet("konum", e)
            }
            rh.postDelayed(this, 250)
        }
    }

    private fun konumBaslat() {
        if (konumDonguAktif) return
        konumDonguAktif = true
        konumHataSay = 0
        rh.post(konumDongusu)
    }

    private fun konumDurdur() {
        konumDonguAktif = false
    }

    private fun konumGerekliMi(): Boolean =
        rotaKayit || rotaCalisiyor || (running && testCalisiyor && (cfg.rotaAcik || cfg.townAcik) && !Config.koMu(this))

    private fun konumOkunamadi(neden: String) {
        konumHataSay++
        if (konumHataSay % 20 == 1) TestLog.olay("KONUM_OKUNAMADI", neden, "")
    }

    private fun konumOku() {
        if (konumOkunuyor || !ScreenSampler.running || screenW <= 0) return
        val x1 = (screenW * 0.0745f).toInt()
        val y1 = (screenH * 0.1245f).toInt()
        val x2 = (screenW * 0.1400f).toInt()
        val y2 = (screenH * 0.1660f).toInt()
        val r = ScreenSampler.bolge(x1, y1, x2, y2) ?: return
        val w = r.first
        val hh = r.second
        val px = r.third
        if (w < 8 || hh < 4) return
        val sc = IntArray(px.size)
        var mx = 0
        for (i in px.indices) {
            val c = px[i]
            val rr = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val v = if (g - b > 20 && g >= rr - 10) g else 0
            sc[i] = v
            if (v > mx) mx = v
        }
        if (mx < 150) {
            konumOkunamadi("yazı bulunamadı")
            return
        }
        val mn = (mx * 0.35f).toInt()
        val out = IntArray(px.size)
        for (i in px.indices) {
            val n = ((sc[i] - mn).toFloat() / (mx - mn).toFloat()).coerceIn(0f, 1f)
            val gri = (255f - 255f * n).toInt()
            out[i] = (0xFF shl 24) or (gri shl 16) or (gri shl 8) or gri
        }
        val bm = Bitmap.createBitmap(out, w, hh, Bitmap.Config.ARGB_8888)
        val buyuk = Bitmap.createScaledBitmap(bm, w * 5, hh * 5, true)
        konumOkunuyor = true
        BotKontrol.metin(buyuk) { metin ->
            rh.post {
                konumOkunuyor = false
                konumIsle(metin)
            }
        }
    }

    private val konumRegex = Regex("(\\d{2,4})\\D{1,3}(\\d{2,4})")

    private fun konumIsle(metin: String?) {
        if (metin == null) {
            konumOkunamadi("OCR boş döndü")
            return
        }
        val t = metin.replace('\n', ' ').replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        var x = -1
        var y = -1
        val m = konumRegex.find(t)
        if (m != null) {
            x = m.groupValues[1].toIntOrNull() ?: -1
            y = m.groupValues[2].toIntOrNull() ?: -1
        } else {
            val d = t.filter { it.isDigit() }
            if (d.length == 6) {
                x = d.substring(0, 3).toIntOrNull() ?: -1
                y = d.substring(3).toIntOrNull() ?: -1
            }
        }
        if (x < 0 || y < 0 || x > 1999 || y > 1999) {
            konumOkunamadi("ayrıştırılamadı: $t")
            return
        }
        val simdi = SystemClock.uptimeMillis()
        val bx = konumX
        val by = konumY
        if (bx >= 0 && simdi - konumAt < 3000L && (Math.abs(x - bx) > 40 || Math.abs(y - by) > 40)) {
            // ani sıçrama: aynı aday iki kez gelirse kabul et (okuma hatasına karşı)
            if (x == konumAdayX && y == konumAdayY) {
                konumAdaySay++
            } else {
                konumAdayX = x
                konumAdayY = y
                konumAdaySay = 1
            }
            if (konumAdaySay < 2) {
                TestLog.olay("KONUM_SUPHELI", "$x,$y (önceki $bx,$by)", "")
                return
            }
        }
        konumAdayX = -1
        konumAdaySay = 0
        konumKabul(x, y, simdi)
    }

    private fun konumKabul(x: Int, y: Int, simdi: Long) {
        konumX = x
        konumY = y
        konumAt = simdi
        konumHataSay = 0
        konumGecmis.add(doubleArrayOf(simdi.toDouble(), x.toDouble(), y.toDouble()))
        while (konumGecmis.size > 40) konumGecmis.removeAt(0)
        if (simdi - konumLogAt > 5000L) {
            konumLogAt = simdi
            TestLog.olay("KONUM", "$x,$y", "")
        }
        if (rotaKayit) {
            val son = rotaKayitSon
            if (son == null || Math.hypot((x - son[0]).toDouble(), (y - son[1]).toDouble()) >= 3.0) {
                val p = intArrayOf(x, y)
                rotaYeni.add(p)
                rotaKayitSon = p
            }
        }
        if (yuruAktif && rotaAsama == 2) {
            val son = rotaYurunenSon
            if (son == null || Math.hypot((x - son[0]).toDouble(), (y - son[1]).toDouble()) >= 3.0) {
                val p = intArrayOf(x, y)
                rotaYurunen.add(p)
                rotaYurunenSon = p
            }
        }
    }

    /** Karakterin yönü (harita koordinatında atan2(dy, dx)); hareket yoksa null */
    private fun yonHesapla(): Double? {
        val simdi = SystemClock.uptimeMillis().toDouble()
        val son = konumGecmis.lastOrNull() ?: return null
        var ilk: DoubleArray? = null
        for (p in konumGecmis) {
            if (simdi - p[0] <= 1500.0) {
                ilk = p
                break
            }
        }
        if (ilk == null) return null
        val dx = son[1] - ilk[1]
        val dy = son[2] - ilk[2]
        if (Math.hypot(dx, dy) < 2.5) return null
        return Math.atan2(dy, dx)
    }

    // ---------------- rota kaydı (elle yürü, bot koordinatları iz olarak biriktirir) ----------------
    private fun rotaKayitBasla(hedef: Int = 0) {
        rh.post {
            if (!ScreenSampler.running) {
                toast("Ekran okuma kapalı: önce botu bir kez başlat (ekran izni), sonra durdur")
                return@post
            }
            kayitHedef = hedef
            rotaYeni.clear()
            rotaKayitSon = null
            rotaKayit = true
            kayitSonKonum = null
            kayitSonHareketAt = SystemClock.uptimeMillis()
            rh.postDelayed(kayitIzleyici, 2000)
            konumBaslat()
            TestLog.olay("ROTA_KAYIT", "kayıt başladı", "")
            toast(if (hedef == 1) "🔴 Kayıt başladı: önce Town'a bas, Town'da doğunca slota yürü, sonra ⋯ → Test modu → Town döngüsü → Yolu kaydet (bitir)" else "🔴 Rota kaydı başladı: Town'a bas, Town'da doğunca Inn Hostes'e yürü, sonra ⋯ → Test modu → Town döngüsü → Yolu kaydet (bitir)")
        }
    }

    // ---- kayıt otomatik bitirme: yeterince yürüdükten sonra karakter durunca kayıt kendiliğinden biter ----
    private var kayitSonKonum: IntArray? = null
    private var kayitSonHareketAt = 0L
    private val kayitIzleyici = object : Runnable {
        override fun run() {
            if (!rotaKayit) return
            try {
                kayitKontrol()
            } catch (e: Exception) {
                hataKaydet("kayit-izle", e)
            }
            if (rotaKayit) rh.postDelayed(this, 500)
        }
    }

    private fun kayitKontrol() {
        val simdi = SystemClock.uptimeMillis()
        if (konumX < 0 || simdi - konumAt > 2500L) return
        val son = kayitSonKonum
        if (son == null || Math.hypot((konumX - son[0]).toDouble(), (konumY - son[1]).toDouble()) > 1.5) {
            kayitSonKonum = intArrayOf(konumX, konumY)
            kayitSonHareketAt = simdi
            return
        }
        // yürünen yol uzunluğu (Town sıçraması sayılmaz)
        var uz = 0.0
        for (i in 1 until rotaYeni.size) {
            val d = Math.hypot((rotaYeni[i][0] - rotaYeni[i - 1][0]).toDouble(), (rotaYeni[i][1] - rotaYeni[i - 1][1]).toDouble())
            if (d < 40.0) uz += d
        }
        if (uz < 25.0) return
        val durdu = simdi - kayitSonHareketAt
        val hedef = kayitHedef
        var bitir = false
        if (hedef == 1) {
            bitir = durdu >= 4000L
        } else if (durdu >= 3000L) {
            // Inn yolu: Open butonu görününce (ya da 9 sn durunca) biter
            if (rotaOpenT == null) {
                updateScreenSize()
                rotaOpenT = Preset.loadTemplateF(this, "open.png", screenW / 2712f, 20, 0.5f, 0.5f)
            }
            val op = rotaOpenT
            val f = ScreenSampler.grab()
            val gordu = op != null && f != null && findT(f, op) != null
            bitir = gordu || durdu >= 9000L
        }
        if (bitir) {
            TestLog.olay("KAYIT_OTOMATIK_BITTI", "karakter durdu, kayıt bitiriliyor", "hedef=$hedef uzunluk=${uz.toInt()}")
            rotaKayitBitir()
            ui.postDelayed({ townSihirbaz() }, 1200)
        }
    }

    private fun rotaKayitBitir() {
        rh.post {
            rotaKayit = false
            // Town ışınlanması: iz içinde ani büyük sıçrama varsa, öncesini at (yol Town'dan başlasın)
            var atla = 0
            for (i in 1 until rotaYeni.size) {
                if (Math.hypot((rotaYeni[i][0] - rotaYeni[i - 1][0]).toDouble(), (rotaYeni[i][1] - rotaYeni[i - 1][1]).toDouble()) > 40.0) atla = i
            }
            if (atla > 0) {
                TestLog.olay("IZ_TEMIZLENDI", "Town sıçraması bulundu, ilk $atla nokta atıldı", "")
                for (k in 0 until atla) rotaYeni.removeAt(0)
            }
            if (rotaYeni.size >= 3 && kayitHedef == 1) {
                slotIz.clear()
                slotIz.addAll(rotaYeni)
                slotKaydet()
                val ilk = slotIz[0]
                val son = slotIz[slotIz.size - 1]
                TestLog.olay("SLOT_YOLU_KAYDEDILDI", "${slotIz.size} nokta", "town=${ilk[0]},${ilk[1]} slot=${son[0]},${son[1]}")
                toast("✅ Town→slot yolu kaydedildi: ${slotIz.size} nokta (slot ${son[0]},${son[1]})")
            } else if (rotaYeni.size >= 3) {
                rotaIz.clear()
                rotaIz.addAll(rotaYeni)
                rotaKaydet()
                var uz = 0.0
                for (i in 1 until rotaIz.size) {
                    uz += Math.hypot((rotaIz[i][0] - rotaIz[i - 1][0]).toDouble(), (rotaIz[i][1] - rotaIz[i - 1][1]).toDouble())
                }
                val ilk = rotaIz[0]
                val son = rotaIz[rotaIz.size - 1]
                TestLog.olay("ROTA_KAYDEDILDI", "${rotaIz.size} nokta", "uzunluk=${uz.toInt()} baslangic=${ilk[0]},${ilk[1]} bitis=${son[0]},${son[1]}")
                toast("✅ Rota kaydedildi: ${rotaIz.size} nokta, yaklaşık ${uz.toInt()} birim")
            } else {
                toast("Kayıt çok kısa (${rotaYeni.size} nokta), rota değişmedi")
            }
            if (!konumGerekliMi()) konumDurdur()
        }
    }

    private fun rotaSil() {
        rh.post {
            rotaIz.clear()
            rotaKaydet()
            TestLog.olay("ROTA_SILINDI", "kayıtlı rota silindi", "")
            toast("🗑 Rota silindi")
        }
    }

    private fun konumTesti() {
        rh.post {
            if (!ScreenSampler.running) {
                toast("Ekran okuma kapalı: önce botu bir kez başlat (ekran izni), sonra durdur")
                return@post
            }
            konumBaslat()
            rh.postDelayed({
                val simdi = SystemClock.uptimeMillis()
                if (konumX >= 0 && simdi - konumAt < 2000L) {
                    toast("📍 Okunan konum: $konumX,$konumY — ekrandakiyle aynı mı?")
                } else {
                    toast("📍 Konum okunamadı. Günlükte KONUM_OKUNAMADI satırına bak")
                }
                if (!konumGerekliMi()) konumDurdur()
            }, 1500)
        }
    }

    // ---------------- joystick sürücüsü ----------------
    private fun jTaban(): FloatArray = floatArrayOf(screenW * 0.22f, screenH * 0.60f)

    private fun jBaslat() {
        jAktif = true
        jStroke = null
        jHata = 0
        jDx = 0f
        jDy = 0f
        rh.post { jPompa() }
    }

    private fun jSur(dx: Float, dy: Float) {
        jDx = dx
        jDy = dy
    }

    private fun jBirak() {
        jAktif = false
        rh.post { jPompa() }
    }

    private fun jPompa() {
        if (jUcusta) return
        val onceki = jStroke
        val devam = jAktif
        if (onceki == null && !devam) return
        val tb = jTaban()
        var tx = (tb[0] + jDx).coerceIn(2f, screenW - 3f)
        var ty = (tb[1] + jDy).coerceIn(2f, screenH - 3f)
        val yol = Path()
        var st: GestureDescription.StrokeDescription? = null
        try {
            if (onceki == null) {
                if (Math.abs(tx - tb[0]) < 0.6f && Math.abs(ty - tb[1]) < 0.6f) tx = tb[0] + 0.7f
                yol.moveTo(tb[0], tb[1])
                yol.lineTo(tx, ty)
                st = GestureDescription.StrokeDescription(yol, 0, 60, true)
            } else {
                if (Math.abs(tx - jLx) < 0.6f && Math.abs(ty - jLy) < 0.6f) tx = jLx + 0.7f
                yol.moveTo(jLx, jLy)
                yol.lineTo(tx, ty)
                st = onceki.continueStroke(yol, 0, 60, devam)
            }
        } catch (e: Exception) {
            hataKaydet("joystick", e)
            jStroke = null
            jHata++
            if (jHata >= 6) rotaIptal("joystick jesti oluşturulamadı")
            return
        }
        val stf = st ?: return
        jLx = tx
        jLy = ty
        jStroke = if (devam) stf else null
        jUcusta = true
        val g = try {
            GestureDescription.Builder().addStroke(stf).build()
        } catch (e: Exception) {
            null
        }
        if (g == null) {
            jUcusta = false
            jStroke = null
            return
        }
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                jUcusta = false
                jHata = 0
                if (jAktif || jStroke != null) rh.post { jPompa() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                jUcusta = false
                jStroke = null
                jHata++
                if (jHata >= 6) {
                    jAktif = false
                    rotaIptal("joystick dokunuşu sürekli iptal oluyor (çoklu dokunuş desteklenmiyor olabilir)")
                } else if (jAktif) {
                    rh.postDelayed({ jPompa() }, 60)
                }
            }
        }, rh)
        if (!ok) {
            jUcusta = false
            jStroke = null
            jHata++
            if (jHata >= 6) rotaIptal("joystick jesti gönderilemedi") else if (jAktif) rh.postDelayed({ jPompa() }, 80)
        }
    }

    // ---------------- yürüme denetleyicisi (iz takibi) ----------------
    private val yuruDongusu = object : Runnable {
        override fun run() {
            if (!yuruAktif) return
            try {
                yuruAdimi()
            } catch (e: Exception) {
                hataKaydet("yurume", e)
                yuruBitir(false, "hata: ${e.javaClass.simpleName}")
            }
            if (yuruAktif) rh.postDelayed(this, 150)
        }
    }

    private fun yuruBaslat(yol: List<IntArray>, tol: Double, zamanAsimi: Long, sonuc: (Boolean, String) -> Unit) {
        yuruYol = yol
        yuruIdx = 0
        yuruTol = tol
        yuruBas = SystemClock.uptimeMillis()
        yuruZamanAsimi = zamanAsimi
        yuruMod = 0                                      // her yürüyüşte yön kalibrasyonu (kamera açısı değişmiş olabilir)
        yuruModBas = SystemClock.uptimeMillis()
        yuruADegis = 0L
        yuruP0 = null
        yuruKonumYokSay = 0
        yuruOnceAt = 0L
        yuruOnceMesafe = 0.0
        yuruKurtarma = 0
        yuruGeriBitis = 0L
        yuruSonuc = sonuc
        yuruAktif = true
        jBaslat()
        rh.post(yuruDongusu)
    }

    private fun yuruBitir(basari: Boolean, neden: String) {
        if (!yuruAktif) return
        yuruAktif = false
        jBirak()
        TestLog.olay(if (basari) "YURU_BITTI" else "YURU_HATA", neden, "konum=$konumX,$konumY")
        val cb = yuruSonuc
        yuruSonuc = null
        cb?.invoke(basari, neden)
    }

    private fun yuruAdimi() {
        val now = SystemClock.uptimeMillis()
        if (now - yuruBas > yuruZamanAsimi) {
            yuruBitir(false, "yürüme zaman aşımı")
            return
        }
        if (!oyundaMi()) {
            yuruBitir(false, "oyun ön planda değil")
            return
        }
        val kx = konumX
        val ky = konumY
        if (kx < 0 || now - konumAt > 2500L) {
            yuruKonumYokSay++
            jSur(0f, 0f)
            if (yuruKonumYokSay > 40) yuruBitir(false, "konum 6 sn okunamadı")
            return
        }
        yuruKonumYokSay = 0
        val rr = screenH * 0.10f
        if (yuruMod == 0) {
            // Kalibrasyon 1: joystick YUKARI; karakterin dünya yönünü ölç (joystick kamera yönüne göre yürütür)
            jSur(0f, -rr)
            if (yuruP0 == null) {
                if (now - yuruModBas >= 600L) yuruP0 = intArrayOf(kx, ky)
                return
            }
            val p0 = yuruP0 ?: return
            val d = Math.hypot((kx - p0[0]).toDouble(), (ky - p0[1]).toDouble())
            if (d >= 6.0) {
                yuruV0 = Math.atan2((ky - p0[1]).toDouble(), (kx - p0[0]).toDouble())
                yuruP0 = null
                yuruMod = 1
                yuruModBas = now
            } else if (now - yuruModBas > 7000L) {
                yuruBitir(false, "kalibrasyon: karakter yürümüyor (joystick çalışmıyor olabilir)")
            }
            return
        }
        if (yuruMod == 1) {
            // Kalibrasyon 2: joystick SAĞA; ayna yönünü (işaret) ve kamera ofsetini ölç
            jSur(rr, 0f)
            if (yuruP0 == null) {
                if (now - yuruModBas >= 1200L) yuruP0 = intArrayOf(kx, ky)
                return
            }
            val p0 = yuruP0 ?: return
            val d = Math.hypot((kx - p0[0]).toDouble(), (ky - p0[1]).toDouble())
            if (d >= 5.0) {
                val w2 = Math.atan2((ky - p0[1]).toDouble(), (kx - p0[0]).toDouble())
                val dl = aciSar(w2 - yuruV0)
                if (Math.abs(dl) < 0.8 || Math.abs(dl) > 2.3) {
                    yuruBitir(false, "kalibrasyon: yön ölçülemedi (fark ${"%.2f".format(dl)} rad)")
                } else {
                    yuruS = if (dl > 0) 1 else -1
                    yuruC = yuruV0 + yuruS * Math.PI / 2
                    yuruADegis = now
                    TestLog.olay("KALIBRASYON", "işaret=$yuruS ofset=${"%.2f".format(yuruC)} rad", "yukari_yon=${"%.2f".format(yuruV0)} sag_yon=${"%.2f".format(w2)}")
                    yuruMod = 2
                }
            } else if (now - yuruModBas > 7000L) {
                yuruBitir(false, "kalibrasyon: yön ölçülemedi (zaman)")
            }
            return
        }
        // Normal iz takibi
        val yol = yuruYol
        val son = yol[yol.size - 1]
        val dSon = Math.hypot((son[0] - kx).toDouble(), (son[1] - ky).toDouble())
        if (dSon <= yuruTol) {
            yuruBitir(true, "hedefe varıldı (kalan ${dSon.toInt()} birim)")
            return
        }
        // izin ilerisindeki en yakın noktayı bul (en fazla 12 nokta ileri; geri dönmez)
        var enYakin = yuruIdx
        var enD = 1e9
        val bit = minOf(yol.size - 1, yuruIdx + 12)
        for (i in yuruIdx..bit) {
            val dd = Math.hypot((yol[i][0] - kx).toDouble(), (yol[i][1] - ky).toDouble())
            if (dd < enD) {
                enD = dd
                enYakin = i
            }
        }
        yuruIdx = enYakin
        var hi = yuruIdx
        while (hi < yol.size - 1 && Math.hypot((yol[hi][0] - kx).toDouble(), (yol[hi][1] - ky).toDouble()) < 8.0) hi++
        val tx = yol[hi][0]
        val ty = yol[hi][1]
        // takılma: 8 sn'de en az 1.5 birim yaklaşmadıysa kısa geri + dönüş dene
        if (now - yuruOnceAt > 8000L) {
            if (yuruOnceAt != 0L && yuruOnceMesafe - dSon < 1.5) {
                yuruKurtarma++
                yuruGeriBitis = now + 1300L
                TestLog.olay("YURU_TAKILDI", "ilerleme yok, kurtarma $yuruKurtarma", "konum=$kx,$ky kalan=${dSon.toInt()}")
                if (yuruKurtarma > 3) {
                    yuruBitir(false, "yürürken takıldı (3 kurtarma denemesi)")
                    return
                }
            }
            yuruOnceAt = now
            yuruOnceMesafe = dSon
        }
        if (now < yuruGeriBitis) {
            // takılma kurtarma: hedef yönüne dik, dönüşümlü iki yana kısa adım
            val ph = Math.atan2((ty - ky).toDouble(), (tx - kx).toDouble())
            jDunya(ph + (if (yuruKurtarma % 2 == 0) 1.57 else -1.57), rr)
            return
        }
        val phi = Math.atan2((ty - ky).toDouble(), (tx - kx).toDouble())
        val a = jDunya(phi, rr)
        if (Math.abs(aciSar(a - yuruAKomut)) > 0.35) yuruADegis = now
        yuruAKomut = a
        // kamera ofsetini hareketten yavaşça düzelt (komut en az 1.8 sn sabit kaldıysa)
        val th = yonHesapla()
        if (th != null && now - yuruADegis > 1800L) {
            val cm = th - yuruS * a
            yuruC += 0.15 * aciSar(cm - yuruC)
        }
    }

    private fun aciSar(x: Double): Double {
        var v = x
        while (v > Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }

    /** Dünya yönüne (atan2(dy,dx)) gitmek için joystick'i uygun açıya iter; kullanılan joystick açısını döndürür */
    private fun jDunya(w: Double, rr: Float): Double {
        val a = aciSar(yuruS * (w - yuruC))
        jSur((Math.cos(a) * rr).toFloat(), (Math.sin(a) * rr).toFloat())
        return a
    }

    // ---------------- çanta rotası akışı ----------------
    private fun rotaBaslat(mod: Int) {
        rh.post { rotaBaslatIc(mod) }
    }

    private fun rotaBaslatIc(mod: Int) {
        if (rotaCalisiyor) {
            toast("Rota zaten çalışıyor")
            return
        }
        if (Config.koMu(this)) {
            toast("Çanta rotası şimdilik sadece MykoMobile için")
            return
        }
        if (rotaIz.size < 2) rotaYukle()
        if (rotaIz.size < 2) {
            toast("Önce rota kaydet: ⋯ → Test modu → Rota kaydını başlat")
            return
        }
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı: önce botu bir kez başlat")
            return
        }
        if (!oyundaMi()) {
            toast("Önce oyunu aç")
            return
        }
        if (!running) cfg = Config.load(this)
        updateScreenSize()
        val g0 = screenW
        rotaOpenT = Preset.loadTemplateF(this, "open.png", g0 / 2712f, 20, 0.5f, 0.5f)
        bankaT = Preset.loadTemplateF(this, "bank_baslik.png", g0 / 2576f, 18, 0.5f, 0.5f)
        miktarT = Preset.loadTemplateF(this, "miktar_onay.png", g0 / 1280f, 22, 0.5f, 0.5f)
        if (mod == 0 && (rotaOpenT == null || bankaT == null)) {
            toast("Şablon dosyaları eksik (open.png / bank_baslik.png)")
            return
        }
        rotaMod = mod
        rotaCalisiyor = true
        rotaMesgul = true
        rotaBas = SystemClock.uptimeMillis()
        rotaTekrar = false
        bTasinan = 0
        bIdx = 0
        rotaYurunen.clear()
        rotaYurunenSon = null
        rotaAsama = 1
        TestLog.olay("ROTA_BASLADI", if (mod == 0) "çanta rotası" else "yürüme testi", "iz_noktasi=${rotaIz.size}")
        konumBaslat()
        rotaKonumBekle(0)
    }

    private fun rotaKonumBekle(n: Int) {
        if (!rotaCalisiyor) return
        val simdi = SystemClock.uptimeMillis()
        if (konumX >= 0 && simdi - konumAt < 1500L) {
            val bk = intArrayOf(konumX, konumY)
            rotaBasKonum = bk
            rotaYurunen.add(bk)
            rotaYurunenSon = bk
            // farm adımının son dokunuşu bitsin
            rh.postDelayed({ rotaGit() }, 700)
            return
        }
        if (n > 16) {
            rotaIptal("konum okunamadı (HP/MP barının altındaki koordinat)")
            return
        }
        rh.postDelayed({ rotaKonumBekle(n + 1) }, 250)
    }

    private fun rotaGit() {
        if (!rotaCalisiyor) return
        val bk = rotaBasKonum ?: return
        var k = 0
        var kd = 1e9
        for (i in rotaIz.indices) {
            val d = Math.hypot((rotaIz[i][0] - bk[0]).toDouble(), (rotaIz[i][1] - bk[1]).toDouble())
            if (d < kd) {
                kd = d
                k = i
            }
        }
        if (kd > 60.0) {
            rotaIptal("kayıtlı rotadan çok uzaktasın (en yakın nokta ${kd.toInt()} birim)")
            return
        }
        val yol = ArrayList<IntArray>()
        yol.add(bk)
        for (i in k until rotaIz.size) yol.add(rotaIz[i])
        rotaAsama = 2
        TestLog.olay("ROTA_GIDIS", "Inn Hostes'e yürünüyor", "baslangic=${bk[0]},${bk[1]} ilk_nokta=$k nokta=${yol.size}")
        yuruBaslat(yol, 4.0, 150_000L) { ok, neden ->
            rh.post {
                if (!rotaCalisiyor) return@post
                if (!ok) {
                    rotaIptal("gidiş: $neden")
                } else if (rotaMod == 1) {
                    TestLog.olay("YURU_TESTI_TAMAM", "hedefe varıldı", "sure_ms=${SystemClock.uptimeMillis() - rotaBas}")
                    toast("✅ Yürüme testi tamam: Inn Hostes noktasına varıldı")
                    rotaBitirSessiz()
                } else {
                    rh.postDelayed({ rotaOpenAra(0) }, 500)
                }
            }
        }
    }

    private fun rotaOpenAra(deneme: Int) {
        if (!rotaCalisiyor) return
        rotaAsama = 3
        val op = rotaOpenT
        val f = ScreenSampler.grab()
        if (op != null && f != null) {
            val pos = findT(f, op)
            if (pos != null) {
                val t = sablonHedef(op, pos)
                TestLog.olay("ROTA_OPEN", "Open görüldü, basılıyor", "deneme=$deneme")
                tap(t.x, t.y, t.r) { rh.postDelayed({ rotaMenuAc() }, 1200) }
                return
            }
        }
        if (deneme > 24) {
            rotaIptal("Open butonu görünmedi (Inn Hostes yakında değil mi?)")
            return
        }
        rh.postDelayed({ rotaOpenAra(deneme + 1) }, 300)
    }

    private fun rotaMenuAc() {
        if (!rotaCalisiyor) return
        rotaAsama = 4
        TestLog.olay("ROTA_MENU", "Inn Hostes Open basılıyor", "")
        tap(screenW * 0.9006f, screenH * 0.4185f, 0f) { rh.postDelayed({ rotaPencereBekle(0) }, 600) }
    }

    private fun bankaPencereVar(): Boolean {
        val t = bankaT ?: return false
        val f = ScreenSampler.grab() ?: return false
        val gx = (screenW * 0.6405f * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        val gy = (screenH * 0.0475f * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        return ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, gx, gy, 24) != null
    }

    private fun rotaPencereBekle(deneme: Int) {
        if (!rotaCalisiyor) return
        rotaAsama = 5
        if (bankaPencereVar()) {
            bIdx = 0
            bTasinan = 0
            rotaAsama = 6
            TestLog.olay("ROTA_BANKA_ACIK", "banka penceresi açıldı", "")
            rh.postDelayed({ bankaBosaltAdim() }, 400)
            return
        }
        if (deneme == 8 && !rotaTekrar) {
            // 2.4 sn'dir pencere yok: Open/menü basışı kaçmış olabilir, bir kez baştan dene
            rotaTekrar = true
            TestLog.olay("ROTA_TEKRAR", "banka açılmadı, Open'dan yeniden deneniyor", "")
            rotaOpenAra(0)
            return
        }
        if (deneme > 20) {
            rotaIptal("banka penceresi açılmadı")
            return
        }
        rh.postDelayed({ rotaPencereBekle(deneme + 1) }, 300)
    }

    /** Envanter hücresinde eşya var mı: boş hücre düz koyu (değişim ~1), dolu hücrede belirgin (>=30) */
    private fun hucreDolu(r: Int, c: Int): Boolean = hucreDoluX(1605f, r, c)

    private fun hucreDoluX(x0: Float, r: Int, c: Int): Boolean {
        val cx = screenW * ((x0 + 100f * c) / 2576f)
        val cy = screenH * ((770f + 95f * r) / 1159f)
        val yar = screenW * (26f / 2576f)
        val reg = ScreenSampler.bolge((cx - yar).toInt(), (cy - yar).toInt(), (cx + yar).toInt(), (cy + yar).toInt()) ?: return false
        val px = reg.third
        if (px.isEmpty()) return false
        val lum = DoubleArray(px.size)
        var top = 0.0
        for (i in px.indices) {
            val p = px[i]
            val l = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3.0
            lum[i] = l
            top += l
        }
        val ort = top / px.size
        var v = 0.0
        for (l in lum) v += (l - ort) * (l - ort)
        return Math.sqrt(v / px.size) > 8.0
    }

    private fun bankaBosaltAdim() {
        if (!rotaCalisiyor) return
        val satir = cfg.rotaSatir.coerceIn(1, 4)
        if (!bankaPencereVar()) {
            rotaIptal("banka penceresi boşaltırken kapandı")
            return
        }
        while (bIdx < satir * 7) {
            val r = bIdx / 7
            val c = bIdx % 7
            bIdx++
            if (hucreDolu(r, c)) {
                val x = screenW * ((1605f + 100f * c) / 2576f)
                val y = screenH * ((770f + 95f * r) / 1159f)
                bTasinan++
                // eşyaya iki kez dokunmak doğrudan bankaya atar
                tap(x, y, 0f) {
                    rh.postDelayed({
                        tap(x, y, 0f) { rh.postDelayed({ miktarKontrol() }, 550) }
                    }, 110)
                }
                return
            }
        }
        TestLog.olay("ROTA_BOSALTILDI", "taşınan=$bTasinan satir=$satir", "")
        rotaKapat()
    }

    /** İstiflenebilir eşyada çıkan "miktar gir" penceresinde Confirm'e bas */
    private fun miktarKontrol() {
        if (!rotaCalisiyor) return
        val t = miktarT
        val f = ScreenSampler.grab()
        if (t != null && f != null) {
            val gx = (screenW * 0.6500f * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
            val gy = (screenH * 0.5000f * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
            val pos = ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, gx, gy, 20)
            if (pos != null) {
                val hd = sablonHedef(t, pos)
                TestLog.olay("ROTA_MIKTAR", "miktar penceresi: Confirm basılıyor", "")
                tap(hd.x, hd.y, hd.r) { rh.postDelayed({ bankaBosaltAdim() }, 450) }
                return
            }
        }
        rh.postDelayed({ bankaBosaltAdim() }, 120)
    }

    private fun rotaKapat() {
        if (!rotaCalisiyor) return
        rotaAsama = 7
        TestLog.olay("ROTA_KAPAT", "banka penceresi kapatılıyor", "")
        tap(screenW * 0.8603f, screenH * 0.0707f, 0f) { rh.postDelayed({ rotaKapatDogrula(0) }, 600) }
    }

    private fun rotaKapatDogrula(deneme: Int) {
        if (!rotaCalisiyor) return
        if (!bankaPencereVar()) {
            rotaDon()
            return
        }
        if (deneme == 3) {
            tap(screenW * 0.8603f, screenH * 0.0707f, 0f) { }
        }
        if (deneme > 8) {
            rotaIptal("banka penceresi kapanmadı")
            return
        }
        rh.postDelayed({ rotaKapatDogrula(deneme + 1) }, 300)
    }

    private fun rotaDon() {
        if (!rotaCalisiyor) return
        if (rotaMod == 2) {
            townAt(true)
            return
        }
        val bk = rotaBasKonum ?: return
        val geri = ArrayList<IntArray>()
        for (i in rotaYurunen.indices.reversed()) geri.add(rotaYurunen[i])
        geri.add(bk)
        if (geri.size < 2) geri.add(intArrayOf(bk[0], bk[1]))
        rotaAsama = 8
        TestLog.olay("ROTA_DONUS", "slota dönülüyor", "nokta=${geri.size} hedef=${bk[0]},${bk[1]}")
        yuruBaslat(geri, 5.0, 150_000L) { ok, neden ->
            rh.post {
                if (!rotaCalisiyor) return@post
                if (ok) rotaBitir() else rotaIptal("dönüş: $neden")
            }
        }
    }

    private fun rotaBitir() {
        val sure = SystemClock.uptimeMillis() - rotaBas
        TestLog.olay("ROTA_TAMAM", "çanta rotası tamamlandı", "sure_ms=$sure tasinan=$bTasinan")
        rotaSayisi++
        rotaBitirSessiz()
        toast("🎒 Çanta boşaltıldı ($bTasinan eşya), farma devam")
    }

    private fun rotaBitirSessiz() {
        rotaCalisiyor = false
        rotaMesgul = false
        cantaDoluBayrak = false
        doluKutuAt = 0L
        collectStreak = 0
        lootPhase = Loot.BOS
        if (!konumGerekliMi()) konumDurdur()
    }

    private fun rotaIptal(neden: String) {
        if (!rotaCalisiyor && !yuruAktif && !jAktif) return
        TestLog.olay("ROTA_IPTAL", neden, "asama=$rotaAsama konum=$konumX,$konumY")
        yuruAktif = false
        yuruSonuc = null
        jBirak()
        rotaCalisiyor = false
        rotaMesgul = false
        if (running) stopMacro("Çanta rotası durdu: $neden") else toast("Çanta rotası durdu: $neden")
    }

    // ================= Town merkezli otonom çanta döngüsü (MykoMobile, yalnızca "Test" yetkili hesap) =================
    // Akış: her N dakikada Envanter'e bas → 28 hücreyi oku → doluysa: Town → kayıtlı Town→Inn yolunu yürü → Open → Inn Hostes Open → eşyalara iki kez dokun (mevcut banka akışı) →
    // bankayı kapat → Town → kayıtlı Town→slot yolunu yürü → slot sapması içinde ise farma devam.
    // Her aşamada doğrulama + zaman aşımı vardır; doğrulanamazsa kör basış yapılmaz, kontrollü durulur.
    private val slotIz = ArrayList<IntArray>()           // kayıtlı yol: Town noktası -> slot (son nokta = slot)
    @Volatile private var kayitHedef = 0                  // 0 = Inn rotası, 1 = Town→slot yolu
    private var townSonKontrol = 0L
    private var townCantaBiliniyor = false
    private var townAtDeneme = 0
    private var townOnceki: IntArray? = null
    private var townDonusDeneme = 0
    private var townEnvSay = 0
    private var townPanelOnce: IntArray? = null
    private var townPanelAcik: IntArray? = null
    private var townInnDeneme = 0

    private val TOWN_DUGME_Y = 0.9635f
    private val TOWN_TOL = 15.0
    private val TOWN_BOS_ESIK = 3

    private fun slotYukle() {
        val s = getSharedPreferences("rota", MODE_PRIVATE).getString("slotiz", "") ?: ""
        val liste = ArrayList<IntArray>()
        for (p in s.split(";")) {
            val a = p.split(",")
            if (a.size == 2) {
                val x = a[0].toIntOrNull()
                val y = a[1].toIntOrNull()
                if (x != null && y != null) liste.add(intArrayOf(x, y))
            }
        }
        rh.post {
            slotIz.clear()
            slotIz.addAll(liste)
        }
    }

    private fun slotKaydet() {
        val sb = StringBuilder()
        for (p in slotIz) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(p[0]).append(',').append(p[1])
        }
        getSharedPreferences("rota", MODE_PRIVATE).edit().putString("slotiz", sb.toString()).apply()
    }

    private fun townHazirMi(): Boolean = slotIz.size >= 2 && rotaIz.size >= 2 && !Config.koMu(this) && ScreenSampler.running

    private fun townDurum(): String {
        if (!testCalisiyor || !cfg.townAcik) return ""
        if (rotaCalisiyor) return "🏙boşaltma "
        val kalan = ((townSonKontrol + cfg.townDk * 60_000L - SystemClock.elapsedRealtime()) / 60_000L).toInt()
        return "🏙%dk ".format(maxOf(0, kalan))
    }

    private fun townVakit(): Boolean =
        cfg.townDk > 0 && SystemClock.elapsedRealtime() - townSonKontrol >= cfg.townDk * 60_000L

    private fun townBaslat(cantaBiliniyor: Boolean) {
        rh.post { townBaslatIc(cantaBiliniyor) }
    }

    private fun townBaslatIc(cantaBiliniyor: Boolean) {
        if (rotaCalisiyor) return
        if (Config.koMu(this) || !ScreenSampler.running || !oyundaMi()) {
            townSonKontrol = SystemClock.elapsedRealtime()
            return
        }
        if (slotIz.size < 2) slotYukle()
        if (rotaIz.size < 2) rotaYukle()
        if (slotIz.size < 2 || rotaIz.size < 2) {
            toast("Önce iki yolu kaydet (Town→Inn ve Town→slot): ⋯ → Test modu → Town döngüsü")
            return
        }
        if (!running) cfg = Config.load(this)
        updateScreenSize()
        val g0 = screenW
        rotaOpenT = Preset.loadTemplateF(this, "open.png", g0 / 2712f, 20, 0.5f, 0.5f)
        bankaT = Preset.loadTemplateF(this, "bank_baslik.png", g0 / 2576f, 18, 0.5f, 0.5f)
        miktarT = Preset.loadTemplateF(this, "miktar_onay.png", g0 / 1280f, 22, 0.5f, 0.5f)
        if (rotaOpenT == null || bankaT == null) {
            toast("Şablon dosyaları eksik (open.png / bank_baslik.png)")
            return
        }
        rotaMod = 2
        rotaCalisiyor = true
        rotaMesgul = true
        rotaBas = SystemClock.uptimeMillis()
        rotaTekrar = false
        bTasinan = 0
        bIdx = 0
        townAtDeneme = 0
        townDonusDeneme = 0
        townInnDeneme = 0
        townCantaBiliniyor = cantaBiliniyor
        rotaAsama = 11
        TestLog.olay("TOWN_BASLADI", if (cantaBiliniyor) "çanta dolu işareti" else "periyodik envanter kontrolü", "slot=${slotIz.last()[0]},${slotIz.last()[1]} sapma=${cfg.slotSapma}")
        konumBaslat()
        if (cantaBiliniyor) rh.postDelayed({ townAt(false) }, 700)
        else rh.postDelayed({ townEnvanterAc() }, 700)
    }

    // ---------------- envanter kontrolü ----------------
    private fun townPanelImza(): IntArray? {
        val r = ScreenSampler.bolge((screenW * 0.62f).toInt(), (screenH * 0.10f).toInt(), (screenW * 0.92f).toInt(), (screenH * 0.55f).toInt()) ?: return null
        val px = r.third
        val n = px.size / 7
        if (n <= 0) return null
        val o = IntArray(n)
        for (i in 0 until n) {
            val p = px[i * 7]
            o[i] = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3
        }
        return o
    }

    private fun townFark(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return -1.0
        var t = 0L
        for (i in a.indices) t += Math.abs(a[i] - b[i])
        return t.toDouble() / a.size
    }

    private fun townEnvanterAc() {
        if (!rotaCalisiyor) return
        rotaAsama = 11
        townEnvSay = 0
        townPanelOnce = townPanelImza()
        tap(screenW * 0.7313f, screenH * TOWN_DUGME_Y, 0f) { rh.postDelayed({ townEnvAcikMi() }, 900) }
    }

    private fun townEnvAcikMi() {
        if (!rotaCalisiyor) return
        val s1 = townPanelImza()
        val fark = townFark(townPanelOnce, s1)
        if (fark >= 18.0) {
            rh.postDelayed({
                if (!rotaCalisiyor) return@postDelayed
                val s2 = townPanelImza()
                val kararli = townFark(s1, s2)
                if (kararli in 0.0..10.0) {
                    townPanelAcik = s2
                    townEnvOlc()
                } else {
                    townEnvSay++
                    townEnvBekle()
                }
            }, 250)
            return
        }
        townEnvSay++
        townEnvBekle()
    }

    private fun townEnvBekle() {
        if (!rotaCalisiyor) return
        if (townEnvSay == 3) {
            // 2.7 sn'dir açılmadı: basış kaçmış olabilir, bir kez daha bas
            tap(screenW * 0.7313f, screenH * TOWN_DUGME_Y, 0f) { rh.postDelayed({ townEnvAcikMi() }, 900) }
            return
        }
        if (townEnvSay > 6) {
            TestLog.olay("ENVANTER_ACILMADI", "envanter penceresi doğrulanamadı, farma devam", "")
            townBitirDolmadan(false)
            return
        }
        rh.postDelayed({ townEnvAcikMi() }, 700)
    }

    private fun townEnvOlc() {
        if (!rotaCalisiyor) return
        val satir = cfg.rotaSatir.coerceIn(1, 4)
        var dolu = 0
        for (r in 0 until satir) for (c in 0 until 7) if (hucreDoluX(1710f, r, c)) dolu++
        val bos = satir * 7 - dolu
        val tam = bos <= TOWN_BOS_ESIK
        TestLog.olay("ENVANTER_OLCUM", "dolu=$dolu/${satir * 7} bos=$bos", "esik_bos<=$TOWN_BOS_ESIK karar=${if (tam) "DOLU" else "yer var"}")
        townEnvKapat(0, tam)
    }

    private fun townEnvKapat(n: Int, tam: Boolean) {
        if (!rotaCalisiyor) return
        tap(screenW * 0.9065f, screenH * 0.0833f, 0f) {
            rh.postDelayed({
                if (!rotaCalisiyor) return@postDelayed
                val simdi = townPanelImza()
                val acikKaldi = townFark(townPanelAcik, simdi).let { it in 0.0..8.0 }
                if (acikKaldi && n < 2) {
                    townEnvKapat(n + 1, tam)
                } else if (acikKaldi) {
                    rotaIptal("envanter penceresi kapanmadı")
                } else if (tam) {
                    townAt(false)
                } else {
                    townBitirDolmadan(true)
                }
            }, 700)
        }
    }

    private fun townBitirDolmadan(olculdu: Boolean) {
        townSonKontrol = SystemClock.elapsedRealtime()
        if (olculdu) TestLog.olay("TOWN_ATLANDI", "çanta dolu değil, farma devam", "sonraki_kontrol_dk=${cfg.townDk}")
        rotaBitirSessiz()
    }

    // ---------------- Town ----------------
    private fun townAt(donus: Boolean) {
        if (!rotaCalisiyor) return
        rotaAsama = if (donus) 16 else 12
        townAtDeneme = 0
        townOnceki = if (konumX >= 0) intArrayOf(konumX, konumY) else null
        TestLog.olay("TOWN_BASILDI", if (donus) "slota dönmek için" else "bankaya gitmek için", "konum=$konumX,$konumY")
        tap(screenW * 0.6133f, screenH * TOWN_DUGME_Y, 0f) { rh.postDelayed({ townDogrula(0, donus) }, 2500) }
    }

    private fun townDogrula(n: Int, donus: Boolean) {
        if (!rotaCalisiyor) return
        val simdi = SystemClock.uptimeMillis()
        if (konumX >= 0 && simdi - konumAt < 1500L) {
            // Town noktası: kayıtlı yolların başlangıcına yakınsa VEYA Town'dan önceki konumdan belirgin uzaklaştıysa
            var dMin = 1e9
            for (yol in listOf(slotIz, rotaIz)) {
                if (yol.isNotEmpty()) dMin = minOf(dMin, Math.hypot((konumX - yol[0][0]).toDouble(), (konumY - yol[0][1]).toDouble()))
            }
            val on = townOnceki
            val tasindi = on != null && Math.hypot((konumX - on[0]).toDouble(), (konumY - on[1]).toDouble()) >= 15.0
            if (dMin <= TOWN_TOL || (tasindi && n >= 3)) {
                TestLog.olay("TOWN_VARILDI", "Town noktasında", "konum=$konumX,$konumY yol_basina_uzaklik=${dMin.toInt()} tasindi=$tasindi")
                if (donus) rh.postDelayed({ townSlotaYuru() }, 500) else rh.postDelayed({ townInneYuru() }, 500)
                return
            }
        }
        if (n == 6 && townAtDeneme == 0) {
            townAtDeneme = 1
            TestLog.olay("TOWN_TEKRAR", "Town noktasına varılmadı, bir kez daha basılıyor", "konum=$konumX,$konumY")
            tap(screenW * 0.6133f, screenH * TOWN_DUGME_Y, 0f) { rh.postDelayed({ townDogrula(7, donus) }, 2500) }
            return
        }
        if (n > 16) {
            rotaIptal("Town'a gidilemedi (konum $konumX,$konumY; Town butonu çalışmadı ya da koordinat okunamadı)")
            return
        }
        rh.postDelayed({ townDogrula(n + 1, donus) }, 600)
    }

    // ---------------- Town noktasından Inn hostess'e yürüyüş (kayıtlı koordinat yolu) ----------------
    private fun townInneYuru() {
        if (!rotaCalisiyor) return
        rotaAsama = 13
        val yol = ArrayList<IntArray>(rotaIz)
        TestLog.olay("TOWN_INN_YURU", "Town→Inn yolu yürünüyor", "nokta=${yol.size} konum=$konumX,$konumY")
        yuruBaslat(yol, 4.0, 150_000L) { ok, neden ->
            rh.post {
                if (!rotaCalisiyor) return@post
                if (ok) {
                    rh.postDelayed({ rotaOpenAra(0) }, 500)
                } else if (townInnDeneme < 1) {
                    townInnDeneme++
                    TestLog.olay("TOWN_INN_TEKRAR", "Inn'e yürüme başarısız, Town'dan yeniden: $neden", "konum=$konumX,$konumY")
                    jBirak()
                    townAt(false)
                } else {
                    rotaIptal("Inn'e yürüme: $neden")
                }
            }
        }
    }

    // ---------------- slota dönüş ----------------
    private fun townSlotaYuru() {
        if (!rotaCalisiyor) return
        rotaAsama = 15
        val yol = ArrayList<IntArray>(slotIz)
        val tol = cfg.slotSapma.coerceIn(2, 20).toDouble()
        TestLog.olay("TOWN_SLOTA_YURU", "Town→slot yolu yürünüyor", "nokta=${yol.size} sapma=$tol")
        yuruBaslat(yol, tol, 150_000L) { ok, neden ->
            rh.post {
                if (!rotaCalisiyor) return@post
                if (ok) townSlotDogrula() else townDonusHata(neden)
            }
        }
    }

    private fun townSlotDogrula() {
        if (!rotaCalisiyor) return
        val s = slotIz.last()
        val sapma = cfg.slotSapma.coerceIn(2, 20) + 2.0
        val taze = konumX >= 0 && SystemClock.uptimeMillis() - konumAt < 2500L
        val d = if (taze) Math.hypot((konumX - s[0]).toDouble(), (konumY - s[1]).toDouble()) else 1e9
        if (d <= sapma) {
            TestLog.olay("TOWN_TAMAM", "slota varıldı, farma devam", "konum=$konumX,$konumY sapma=${d.toInt()} sure_ms=${SystemClock.uptimeMillis() - rotaBas} tasinan=$bTasinan")
            rotaSayisi++
            townSonKontrol = SystemClock.elapsedRealtime()
            rotaBitirSessiz()
            toast("🎒 Çanta boşaltıldı ($bTasinan eşya), slota dönüldü")
        } else {
            townDonusHata("slot sapması aşıldı (${if (taze) d.toInt().toString() else "konum yok"} birim)")
        }
    }

    private fun townDonusHata(neden: String) {
        if (!rotaCalisiyor) return
        if (townDonusDeneme < 1) {
            townDonusDeneme++
            TestLog.olay("TOWN_DONUS_TEKRAR", "slota yürüme başarısız, Town'dan yeniden: $neden", "konum=$konumX,$konumY")
            jBirak()
            townAt(true)
        } else {
            rotaIptal("slota dönüş: $neden")
        }
    }

    // ---------------- kolay kurulum sihirbazı ----------------
    private fun townSihirbaz() {
        val c = Config.load(this)
        val innOk = rotaIz.size >= 2
        val slotOk = slotIz.size >= 2
        val acik = c.townAcik && innOk && slotOk
        val baslik = "🏙 Otomatik çanta boşaltma\n" +
            (if (innOk) "✅ 1) Inn yolu kayıtlı" else "⬜ 1) Inn yolu") + "\n" +
            (if (slotOk) "✅ 2) Slot yolu kayıtlı" else "⬜ 2) Slot yolu") + "\n" +
            (if (acik) "✅ 3) AÇIK: her ${c.townDk} dk çanta kontrolü" else "⬜ 3) Açık değil")
        val maddeler = ArrayList<Pair<String, () -> Unit>>()
        if (!innOk) maddeler.add("▶ 1) Inn yolunu kaydet" to { townRehber(0) })
        else if (!slotOk) maddeler.add("▶ 2) Slot yolunu kaydet" to { townRehber(1) })
        else if (!acik) maddeler.add("▶ 3) Süreyi seç ve AÇ" to { townSureSec() })
        else {
            maddeler.add("⏹ Otomatik boşaltmayı KAPAT" to {
                val cf = Config.load(this)
                cf.townAcik = false
                cf.save(this)
                toast("⏹ Otomatik boşaltma kapatıldı")
            })
            maddeler.add("🔁 Şimdi bir tur dene" to { townBaslat(false) })
            maddeler.add("⏱ Süreyi değiştir" to { townSureSec() })
        }
        maddeler.add("🔄 Yolları baştan kaydet" to {
            rh.post {
                rotaIz.clear(); rotaKaydet()
                slotIz.clear(); slotKaydet()
                ui.post { townSihirbaz() }
            }
        })
        maddeler.add("⚙ Gelişmiş ayarlar" to { townMenu() })
        showMenu(baslik, maddeler)
    }

    private fun townRehber(hedef: Int) {
        val metin = if (hedef == 0)
            "ADIM 1/3 • Inn yolu\n\n1) Karakter slotta dursun\n2) Aşağıdaki tuşa bas\n3) Oyunda TOWN'a bas\n4) Doğunca joystick ile Inn Hostes'in önüne yürü ve dur\n\nBot kendisi anlar ve kaydı bitirir."
        else
            "ADIM 2/3 • Slot yolu\n\n1) Aşağıdaki tuşa bas\n2) Oyunda TOWN'a bas\n3) Doğunca joystick ile farm yaptığın SLOTA yürü ve dur\n\nBot kendisi anlar ve kaydı bitirir."
        showMenu(metin, listOf(
            "✅ Hazırım, kaydı başlat" to {
                if (!ScreenSampler.running) toast("Önce ▶ ile botu başlat, 3 sn sonra ⏸ ile durdur (ekran izni gerekli), sonra tekrar dene")
                else rotaKayitBasla(hedef)
            },
            "⬅ Geri" to { townSihirbaz() }
        ))
    }

    private fun townSureSec() {
        val liste = listOf(10, 20, 30, 50, 60)
        val maddeler = ArrayList<Pair<String, () -> Unit>>()
        for (dk in liste) {
            maddeler.add((if (dk == 30) "$dk dk (önerilen)" else "$dk dk") to {
                val cf = Config.load(this)
                cf.townDk = dk
                cf.townAcik = true
                cf.save(this)
                toast("✅ Hazır! Karakter slotta farm yapsın, ▶ ile başlat. Her $dk dk çantayı bot kontrol eder.")
            })
        }
        showMenu("ADIM 3/3 • Çanta ne sıklıkla kontrol edilsin?", maddeler)
    }

    private fun townMenu() {
        val c = Config.load(this)
        val dkListe = listOf(10, 20, 30, 40, 50, 60, 90, 120)
        val sapmaListe = listOf(2, 3, 4, 6, 8, 12)
        fun sonraki(liste: List<Int>, simdi: Int): Int {
            val i = liste.indexOf(simdi)
            return liste[(if (i < 0) 0 else i + 1) % liste.size]
        }
        fun ayar(degis: (Config) -> Unit) {
            val cf = Config.load(this)
            degis(cf)
            cf.save(this)
            townMenu()
        }
        val maddeler = listOf<Pair<String, () -> Unit>>(
            (if (rotaKayit && kayitHedef == 0) "⏹ 1) Yolu kaydet (bitir) — Inn'e vardın mı?" else "🔴 1) Town→Inn yolunu kaydet (${rotaIz.size} nokta)") to {
                if (rotaKayit) rotaKayitBitir() else rotaKayitBasla(0)
                removeOverlay()
            },
            (if (rotaKayit && kayitHedef == 1) "⏹ 2) Yolu kaydet (bitir) — slota vardın mı?" else "🔴 2) Town→slot yolunu kaydet (${slotIz.size} nokta)") to {
                if (rotaKayit) rotaKayitBitir() else rotaKayitBasla(1)
                removeOverlay()
            },
            "🗑 Town→slot yolunu sil" to { slotSil() },
            "⏱ 3) Envanter kontrolü: her ${c.townDk} dk" to { ayar { it.townDk = sonraki(dkListe, it.townDk) } },
            "🎯 Slot sapma payı: ${c.slotSapma} birim" to { ayar { it.slotSapma = sonraki(sapmaListe, it.slotSapma) } },
            "🎒 Boşaltılacak satır: ${c.rotaSatir}" to { ayar { it.rotaSatir = (it.rotaSatir % 4) + 1 } },
            (if (c.townAcik) "✅ 4) Otomatik çanta döngüsü: AÇIK" else "⬜ 4) Otomatik çanta döngüsü: KAPALI") to { ayar { it.townAcik = !it.townAcik } },
            "▶ Döngüyü şimdi dene (envanteri kontrol et)" to { removeOverlay(); townBaslat(false) },
            "📋 Raporu kopyala" to { raporKopyala() },
            "⬅ Kolay kurulum" to { townSihirbaz() }
        )
        showMenu("🏙 Çanta döngüsü", maddeler)
    }

    private fun slotSil() {
        rh.post {
            slotIz.clear()
            slotKaydet()
            TestLog.olay("SLOT_YOLU_SILINDI", "kayıtlı Town→slot yolu silindi", "")
            toast("🗑 Town→slot yolu silindi")
        }
    }

    private fun stopMacro(reason: String? = null) {
        val calisiyordu = running
        if (koAktif) try { KoOyun.barKaydet(this, screenW, screenH) } catch (_: Exception) {}
        // Minor acik kaldiysa kapat (mana akip gitmesin) - sadece oyun gercekten ondeyse dokun,
        // yoksa (oyun kapandi/arka planda) baska bir uygulamaya yanlislikla dokunmus oluruz
        if (minorAcik && oyundaMi()) {
            minorAcik = false
            cfg.points.firstOrNull { it.type == "minor" }?.let { m ->
                try {
                    val p = Path().apply { moveTo(m.x.toFloat(), m.y.toFloat()); lineTo(m.x + 1f, m.y + 1f) }
                    dispatchGesture(GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
                } catch (e: Exception) {
                }
            }
        }
        running = false
        if (rotaCalisiyor) {
            TestLog.olay("ROTA_IPTAL", "bot durduruldu", "asama=$rotaAsama")
            yuruAktif = false
            yuruSonuc = null
            jBirak()
            rotaCalisiyor = false
            rotaMesgul = false
        }
        if (calisiyordu) oturumKapat(reason)
        padKaldir()
        h.removeCallbacksAndMessages(null)
        ui.removeCallbacks(statusTick)
        ui.post {
            playBtn?.text = "▶ BAŞLAT"
            durumYaz("■ BOT DURDU", false)
            // Durunca panel tekrar açılsın (ayar / menü için)
            panelDaralt(false)
        }
        if (reason == null && calisiyordu) toast("⏹ BOT DURDU")
        if (reason != null) {
            toast(reason)
            vibrate()
        }
    }

    /** worker thread'inde calisir; her adimda tek dokunus, bitince sonraki planlanir */
    /** Adimi planla; ayni anda tek adim bekler (kutu araya girince cift dongu olusmasin) */
    private fun stepPlanla(ms: Long) {
        h.removeCallbacks(stepR)
        h.postDelayed(stepR, ms)
    }

    private fun step() {
        if (!running) return
        try {
            stepIc()
        } catch (e: Throwable) {
            // Makro olmesin (OOM gibi Error'lar dahil): hatayi kaydet, 1 sn sonra devam et
            hataKaydet("adım", e)
            tapping = false
            hataPatlamasi()
            if (running) stepPlanla( 1000)
        }
    }

    private fun stepIc() {
        if (!running) return
        sonIlerleme = SystemClock.uptimeMillis()
        // Bot kontrol penceresi isleniyor: o sirada oyuna dokunma
        if (kontrolde) { stepPlanla(300); return }
        // Canta rotasi calisiyor: farm adimlari dokunmaz (joystick/NPC adimlari rota motorunda)
        if (rotaMesgul) { stepPlanla(300); return }
        // Oyun arkadaysa / ekran kapaliysa / goruntu yoksa DOKUNMA
        val engel = dokunmaEngeli(SystemClock.uptimeMillis())
        if (engel != null) {
            if (duraklamaBas == 0L) {
                duraklamaBas = SystemClock.uptimeMillis()
                TestLog.olay("DURAKLAMA", engelAd(engel), "")
            }
            // Duraklama sirasinda gecen sure "can dusuk kaldi" gibi sayaclara islemesin: donuste haksiz durma olmasin
            gecisSayaclariniSifirla()
            bekleNeden = engel
            stepPlanla( 500)
            return
        }
        if (duraklamaBas != 0L) {
            val sure = SystemClock.uptimeMillis() - duraklamaBas
            duraklamaBas = 0L
            TestLog.olay("TOPARLANDI", "duraklama bitti, bot devam ediyor", "duraklama_ms=$sure")
        }
        bekleNeden = ""
        val now = SystemClock.uptimeMillis()
        if (SystemClock.elapsedRealtime() >= endAtReal) {
            TestLog.olay("SURE_BITTI", "ayarlanan çalışma süresi doldu", "dk=${cfg.minutes}")
            stopMacro("Süre bitti, bot durdu"); return
        }
        // Town döngüsü: her N dk envanteri kontrol et (ya da çanta dolu işareti), doluysa Town→Inn→banka→Town→slot
        if (testCalisiyor && cfg.townAcik && !rotaCalisiyor && !tapping && lootPhase == Loot.BOS &&
            townHazirMi() && (cantaDoluBayrak || townVakit())) {
            val bil = cantaDoluBayrak
            TestLog.olay("TOWN_TETIK", if (bil) "çanta dolu işareti" else "süre doldu: ${cfg.townDk} dk", "")
            townSonKontrol = SystemClock.elapsedRealtime()
            cantaDoluBayrak = false
            townBaslat(bil)
            stepPlanla(300); return
        }
        // Çanta doldu + rota kayıtlı + test hesabı: Inn Hostes'e git, boşalt, dön
        if (testCalisiyor && cfg.rotaAcik && !cfg.townAcik && cantaDoluBayrak && !rotaCalisiyor && !tapping &&
            lootPhase == Loot.BOS && rotaHazirMi()) {
            TestLog.olay("CANTA_ROTA_TETIK", "çanta dolu, rota başlatılıyor", "")
            rotaBaslat(0)
            stepPlanla(300); return
        }
        // Genie hizlandirma: ekran okuma yok, pot/minor/kutu/mob secme yok; sadece skill + seri kilic
        if (genieAktif()) { genieAdim(now); return }
        // Otomatik ekran tanima: once olcegi bul, sonra her seyi ona gore yerlestir
        if (otoMod && olcek == null) {
            if (now - sonTarama >= 1500) {
                sonTarama = now
                val o = try {
                    Preset.olcekBul(this)
                } catch (e: Exception) {
                    null
                }
                if (o != null) {
                    olcekUygula(o)
                } else if (++taramaHata >= 3) {
                    // Yedek olcek SADECE HP bari o olcekte gercekten gorunuyorsa kullanilir.
                    // Gorunmuyorsa yanlis yerlere dokunmamak icin bekle ve tekrar tara.
                    val yo = Preset.varsayilanOlcek(this)
                    if (yedekOlcekMakul(yo)) {
                        olcekUygula(yo, yedek = true)
                    } else {
                        bekleNeden = "🔍"
                        if (taramaHata % 10 == 3) ekranUyari()
                        stepPlanla(1500)
                        return
                    }
                } else if (cfg.tuslarOto) {
                    // Hazir tuslar bu ekranda yanlis olabilir: oyun gorunene kadar dokunma
                    stepPlanla( 1500)
                    return
                }
            } else if (cfg.tuslarOto) {
                stepPlanla( 300)
                return
            }
        }
        if (!pkSaf()) {   // PK "sadece tus basma": ekran okuyan korumalar kapali
            if (dcBekleBas != 0L && baglantiBekle(now)) return
            if (korumaKontrol(now)) return
            if (safetyStop(now)) return
        }

        // Pot, kutu (Open/Collect) ve skill/saldiri BIRLIKTE, ayni anda basilir (iki el gibi):
        // biri digerini bekletmez, digeri de onu bekletmez.
        val r = cfg.radius.toFloat()
        // Her aday bir oncelik kategorisiyle: kullanicinin Oncelik sirasina gore dizilir
        fun kat(n: Nokta) = when (n.type) {
            "hp_pot" -> "hp"; "mp_pot" -> "mp"; "minor" -> "minor"; "heal" -> "heal"; "buff" -> "buff"; else -> "atak"
        }
        val adaylar = ArrayList<Pair<String, Hedef>>()
        for (n in potActions(now) + listOfNotNull(minorAction(now)) + destekActions(now))
            adaylar.add(kat(n) to Hedef(n.x.toFloat(), n.y.toFloat(), r))
        lootAction(now)?.let { adaylar.add("kutu" to it) }
        pick(now)?.let { adaylar.add("atak" to Hedef(it.x.toFloat(), it.y.toFloat(), r, fast = adaylar.any { a -> a.first == "kutu" })) }
        val sira = Config.oncelikListe(cfg.oncelik)
        adaylar.sortBy { sira.indexOf(it.first) }
        val hedefler = adaylar.filter { it.first == "kutu" || it.first == "atak" }.map { it.second }
        val list = when {
            // KO PK: destekler (iksir, minor, heal, buff) birlikte, yanina en fazla bir saldiri/kutu
            koAktif && pkAktif -> {
                val destek = adaylar.filter { it.first != "kutu" && it.first != "atak" }.map { it.second }
                destek + hedefler.take(1)
            }
            // KO Farm: her adimda tek dokunus, oncelik sirasindaki ilk is
            koAktif -> adaylar.take(1).map { it.second }
            else -> adaylar.map { it.second }
        }
        if (TestLog.acik && list.isNotEmpty()) eylemLogla(if (koAktif) adaylar.take(1) else adaylar)
        if (list.isEmpty()) {
            // Kutu toplarken cok sik kontrol et, normalde biraz bekle
            stepPlanla( if (lootPhase != Loot.BOS) 60L else if (pkAktif) 70L else 200L)
            return
        }
        tapping = true
        tapMulti(list) {
            tapping = false
            val hizli = !koAktif && (hedefler.any { it.fast } || lootPhase != Loot.BOS)
            if (running) stepPlanla( if (hizli) rand(40, 90) else nextDelay())
        }
    }

    /**
     * Kutu gozcusu: saldiridan bagimsiz, 0.12 sn'de bir ekrana bakar. Open/Collect All
     * gorunce bekleyen saldiri adimini iptal edip hemen basar.
     */
    // ---- MykoMobile bot kontrolu (sadece MykoMobile Farm, tum uyeler, MykoMobile yonetiminin izniyle) ----
    @Volatile private var kontrolde = false
    @Volatile private var kontrolOkunuyor = false
    @Volatile private var kontrolBekleBitis = 0L
    private val kontrolIs by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    private val botKontrolGozcu = object : Runnable {
        override fun run() {
            if (!running) return
            if (rotaMesgul) { h.postDelayed(this, 2500); return }
            try {
                // Sadece MykoMobile + Farm modu, Genie kapaliyken (tum uyeler; MykoMobile yonetiminin izniyle)
                if (!koAktif && !pkAktif && !genieAktif() && !kontrolde && !kontrolOkunuyor && ScreenSampler.running &&
                    SystemClock.uptimeMillis() >= kontrolBekleBitis &&
                    pencereVarMi()) {
                    // Pencereler hep ekranin ortasinda cikar: sadece orta bolgeyi oku (hizli)
                    val x1 = (screenW * 0.25f).toInt(); val y1 = (screenH * 0.25f).toInt()
                    val x2 = (screenW * 0.75f).toInt(); val y2 = (screenH * 0.82f).toInt()
                    kontrolOkunuyor = true
                    // Ekran kopyalama ve okuma arka planda: botun adim dongusu hic yavaslamaz
                    kontrolIs.execute {
                        val bm = ScreenSampler.kirp(x1, y1, x2, y2)
                        if (bm == null) { kontrolOkunuyor = false; return@execute }
                        BotKontrol.oku(bm, x1, y1, 1f / ScreenSampler.SCALE) { r ->
                            ui.post {
                                kontrolOkunuyor = false
                                // Karanlik harita yanlis alarm verirse yazi tanimayi sik calistirma: 6 sn dinlen
                                if (r is BotKontrol.Sonuc.Yok) kontrolBekleBitis = SystemClock.uptimeMillis() + 6000
                                kontrolIsle(r)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                kontrolOkunuyor = false
                hataKaydet("kontrol", e)
            }
            h.postDelayed(this, 2500)
        }
    }

    /**
     * Ucuz on kontrol: kontrol pencereleri ekranin ortasinda koyu bir kutu. Ortadaki 24 noktanin
     * cogu koyu degilse pencere yoktur, yazi tanima hic calismaz (pil/isinma yok).
     */
    private fun pencereVarMi(): Boolean {
        var koyu = 0; var say = 0
        for (i in 0 until 6) for (j in 0 until 4) {
            val x = (screenW * (0.40f + i * 0.04f)).toInt()
            val y = (screenH * (0.36f + j * 0.09f)).toInt()
            val c = ScreenSampler.readPixel(x, y)
            if (c < 0) continue
            say++
            val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
            if (r < 60 && g < 60 && b < 60) koyu++
        }
        return say >= 12 && koyu * 100 >= say * 55
    }

    private fun kontrolKaydet(ne: String) {
        val sure = if (kontrolPencereBas != 0L) SystemClock.uptimeMillis() - kontrolPencereBas else 0L
        kontrolPencereBas = 0L
        TestLog.olay("KONTROL_CEVAPLANDI", ne, "pencere_ms=$sure")
        val p = getSharedPreferences("botkontrol", MODE_PRIVATE)
        val n = p.getInt("sayi", 0) + 1
        p.edit().putInt("sayi", n).putString("son", "$ne @ ${System.currentTimeMillis() / 1000}").apply()
    }

    private fun kontrolIsle(r: BotKontrol.Sonuc) {
        if (!running) return
        if (r !is BotKontrol.Sonuc.Yok && kontrolPencereBas == 0L) {
            kontrolPencereBas = SystemClock.uptimeMillis()
            TestLog.olay("KONTROL_PENCERESI", "bot kontrol penceresi görüldü", if (r is BotKontrol.Sonuc.Soru) "soru" else "ok")
        }
        when (r) {
            is BotKontrol.Sonuc.Ok -> {
                // 1. pencere: botu durdur, OK'a bas, 5 sn bekle, sonra 2. pencereyi (soru) ara
                kontrolde = true
                statusTv?.text = "Kontrol: OK"
                tap(r.x, r.y, 0f) {
                    kontrolKaydet("ok")
                    ui.postDelayed({ soruAra(0) }, 5000)
                }
            }
            is BotKontrol.Sonuc.Soru -> {
                kontrolde = true
                statusTv?.text = "Kontrol: ${r.cevap}"
                // Yazi okunamazsa pencerenin sabit yerleri (ekran orani, MykoMobile ekran goruntusunden)
                val kutu = r.kutu ?: floatArrayOf(screenW * 0.500f, screenH * 0.507f)
                // Cevap kutusunun hemen altindaki buton (I'm); okunamazsa kutunun altina bas
                val im = r.im ?: floatArrayOf(kutu[0], kutu[1] + screenH * 0.10f)
                val yaz = {
                    yaziYaz(r.cevap.toString())
                    ui.postDelayed({ imBas(im, r.cevap) }, 900)
                }
                tap(kutu[0], kutu[1], 0f) { ui.postDelayed({ yaz() }, 900) }
            }
            else -> {}
        }
    }

    /** OK'tan sonra soru penceresini ara: bulunca coz; ~10 sn icinde cikmazsa bota devam */
    private fun soruAra(deneme: Int) {
        if (!running) { kontrolde = false; return }
        val x1 = (screenW * 0.25f).toInt(); val y1 = (screenH * 0.25f).toInt()
        val bm = ScreenSampler.kirp(x1, y1, (screenW * 0.75f).toInt(), (screenH * 0.82f).toInt())
        if (bm == null) { kontrolde = false; return }
        BotKontrol.oku(bm, x1, y1, 1f / ScreenSampler.SCALE) { r ->
            ui.post {
                when {
                    r is BotKontrol.Sonuc.Soru -> kontrolIsle(r)
                    deneme < 6 -> ui.postDelayed({ soruAra(deneme + 1) }, 1500)
                    else -> { kontrolde = false; statusTv?.text = "Kontrol bitti" }
                }
            }
        }
    }

    /** Orta bolgeyi arka planda oku, sonucu ana kolda ver */
    private fun ortaOku(sonuc: (BotKontrol.Sonuc) -> Unit) {
        val x1 = (screenW * 0.25f).toInt(); val y1 = (screenH * 0.25f).toInt()
        val x2 = (screenW * 0.75f).toInt(); val y2 = (screenH * 0.82f).toInt()
        kontrolIs.execute {
            val bm = ScreenSampler.kirp(x1, y1, x2, y2)
            if (bm == null) { ui.post { sonuc(BotKontrol.Sonuc.Yok) }; return@execute }
            BotKontrol.oku(bm, x1, y1, 1f / ScreenSampler.SCALE) { r -> ui.post { sonuc(r) } }
        }
    }

    /** Klavye acik mi (acik klavye butonun ustunu kapatir) */
    private fun klavyeAcik(): Boolean = try {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (e: Exception) { false }

    /**
     * Cevap yazildi: "I'm not a robot" butonuna bas ve pencerenin kapandigini dogrula.
     * Klavye aciksa once kapatir. Pencere kapanmazsa 5 kez dener, sonra bota devam eder.
     */
    private fun imBas(im: FloatArray?, cevap: Int, deneme: Int = 0) {
        if (!running || deneme >= 5) { kontrolde = false; return }
        if (klavyeAcik()) {
            performGlobalAction(GLOBAL_ACTION_BACK)   // sadece klavyeyi kapatir
            ui.postDelayed({ imBas(im, cevap, deneme + 1) }, 900)
            return
        }
        val hedef = im ?: floatArrayOf(screenW * 0.500f, screenH * 0.612f)
        tap(hedef[0], hedef[1], 0f) {
            ui.postDelayed({
                ortaOku { r ->
                    if (r is BotKontrol.Sonuc.Soru) {
                        // Pencere hala acik: butonun okunan yeriyle tekrar dene
                        imBas(r.im ?: hedef, cevap, deneme + 1)
                    } else {
                        kontrolKaydet("soru $cevap")
                        statusTv?.text = "Kontrol tamam"
                        ui.postDelayed({ kontrolde = false }, 800)
                    }
                }
            }, 1300)
        }
    }

    /** Odaktaki yazi kutusuna metni yaz (oyunun acilan klavye kutusu) */
    private fun yaziYaz(metin: String) {
        try {
            val kok = rootInActiveWindow ?: return
            var kutu = kok.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (kutu == null || !kutu.isEditable) kutu = duzenlenebilirBul(kok)
            kutu ?: return
            val b = android.os.Bundle()
            b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, metin)
            kutu.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b)
            if (Build.VERSION.SDK_INT >= 30) {
                kutu.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        } catch (e: Exception) {
            hataKaydet("yazi", e)
        }
    }

    private fun duzenlenebilirBul(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        n ?: return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) duzenlenebilirBul(n.getChild(i))?.let { return it }
        return null
    }

    private val kutuGozcu = object : Runnable {
        override fun run() {
            if (!running) return
            if (rotaMesgul) { h.postDelayed(this, 300); return }   // rota: NPC'deki Open'a kutu gozcusu basmasin
            try {
                if (!genieAktif()) gozcuIc()   // Genie modunda kutu toplama yok
            } catch (e: Exception) {
                hataKaydet("kutu", e)
            }
            h.postDelayed(this, 120)
        }

        private fun gozcuIc() {
            // Kutu skilden oncelikli. step() zaten ayni anda basiyor; bu gozcu sadece
            // step() bir sonraki adimi beklerken (uzun bekleme sirasinda) devreye girer.
            if (!tapping && (!otoMod || olcek != null) &&
                dokunmaEngeli(SystemClock.uptimeMillis()) == null
            ) {
                val now = SystemClock.uptimeMillis()
                val t = lootAction(now)
                if (t != null) {
                    h.removeCallbacks(stepR)
                    tapping = true
                    tap(t.x, t.y, t.r) {
                        tapping = false
                        if (running) stepPlanla(if (koAktif) nextDelay() else rand(40, 90))
                    }
                }
            }
        }
    }

    private fun isLow(cp: RenkNokta): Boolean {
        if (koAktif) {
            // KO: barin yaklasik dolulugu, uygulamadaki HP/MP % ayarina gore
            val mp = cp === cfg.mp
            var d = KoOyun.doluluk(screenW, screenH, mp)
            val simdi = System.currentTimeMillis()
            if (d < 0f && simdi - koBarAt > 3000) {
                // Bar okunamadi: ekranda yeniden ara (en fazla 3 sn'de bir)
                koBarAt = simdi
                KoOyun.barlariBulArka(screenW, screenH)
                d = KoOyun.doluluk(screenW, screenH, mp)
            }
            if (d < 0f) return false   // bar gorunmuyor: pot basma
            return d < (if (mp) cfg.mpYuzde else cfg.hpYuzde) / 100f
        }
        val c = ScreenSampler.readPixel(cp.x, cp.y)
        if (c < 0) return false
        if (otoMod && olcek != null) {
            // Barin ne kadari dolu? (ortadaki "565/692" yazisindan etkilenmez)
            return if (cp === cfg.mp) barDoluluk(mpBar, false) < cfg.mpYuzde / 100f
            else barDoluluk(hpBar, true) < cfg.hpYuzde / 100f
        }
        return ScreenSampler.diff(c, cp.color) > cfg.tol
    }

    /** Disconnect penceresi ve olum kontrolu. Durdurduysa true */
    private fun korumaKontrol(now: Long): Boolean {
        if (!ScreenSampler.running) return false
        // Disconnect: ~1.5 sn'de bir, ust uste 2 kez gorulurse dur
        val d = dcT
        if (d != null && now - sonDcKontrol > 1500) {
            sonDcKontrol = now
            val f = ScreenSampler.grab()
            if (f != null && findT(f, d) != null) {
                dcSay++
                if (dcSay >= 2) {
                    if (testCalisiyor && cfg.testDcSn > 0) {
                        // Test modu: hemen durma; pencere kaybolursa devam et, gelmezse kontrollü dur
                        if (dcBekleBas == 0L) {
                            dcBekleBas = now
                            dcBekleSonBak = now
                            dcOk = 0
                            TestLog.olay("BAGLANTI_KOPTU", "kopma penceresi görüldü", "bekleme_sn=${cfg.testDcSn}")
                        }
                        return false
                    }
                    TestLog.olay("BAGLANTI_KOPTU", "kopma penceresi görüldü", "bot durduruldu")
                    stopMacro("Bağlantı koptu (Disconnect). Bot durdu")
                    return true
                }
            } else {
                dcSay = 0
            }
        }
        // Olum: HP barinin en solu bile bossa can sifirdir
        if (hpSol[0] >= 0) {
            val c = ScreenSampler.readPixel(hpSol[0], hpSol[1])
            if (c >= 0 && !isRed(c)) {
                if (hpBosSince == 0L) hpBosSince = now
                else if (now - hpBosSince > 3000) {
                    stopMacro("Karakter ölmüş görünüyor. Bot durdu")
                    return true
                }
            } else {
                hpBosSince = 0L
            }
        }
        // KO: can 0 (ölüm) ve uzun süre düşük can. Bar okunamıyorsa (-1) ya da bu oturumda hiç sağlıklı can
        // görülmediyse hiçbir şey yapma (yanlış bar yeri yüzünden haksız durma olmasın).
        if (koAktif && now - koHpBak > 1000L) {
            koHpBak = now
            val hpD = KoOyun.doluluk(screenW, screenH, false)
            if (hpD > 0.05f) koHpGordu = true
            if (hpD >= 0f && koHpGordu) {
                if (hpD < 0.01f) {
                    if (koHpSifirSince == 0L) koHpSifirSince = now
                    else if (now - koHpSifirSince > 5000L) {
                        TestLog.olay("OLUM", "can 0 görüldü", "sure_ms=${now - koHpSifirSince}")
                        stopMacro("Karakter ölmüş görünüyor (can 0). Bot durdu")
                        return true
                    }
                } else {
                    koHpSifirSince = 0L
                }
                if (cfg.hpStop > 0 && hpD < cfg.hpYuzde / 100f) {
                    if (koHpDusukSince == 0L) koHpDusukSince = now
                    else if (now - koHpDusukSince > cfg.hpStop * 1000L) {
                        TestLog.olay("CAN_DUSUK", "uzun süre düşük can", "sure_ms=${now - koHpDusukSince}")
                        stopMacro("Can ${cfg.hpStop} sn boyunca düşük kaldı: pot bitmiş ya da ölmüş olabilirsin. Bot durdu")
                        return true
                    }
                } else {
                    koHpDusukSince = 0L
                }
            }
        }
        return false
    }

    /** HP uzun sure dusuk kaldiysa (pot bitti / oldun) durdur */
    private fun safetyStop(now: Long): Boolean {
        val hp = cfg.hp ?: return false
        if (cfg.hpStop <= 0 || !ScreenSampler.running) return false
        if (isLow(hp)) {
            if (hpLowSince == 0L) hpLowSince = now
            else if (now - hpLowSince > cfg.hpStop * 1000L) {
                stopMacro("HP ${cfg.hpStop} sn boyunca düşük kaldı: pot bitmiş ya da ölmüş olabilirsin. Bot durdu")
                return true
            }
        } else {
            hpLowSince = 0L
        }
        return false
    }

    /** Ayni adimda hem kutu hem skill/saldiri gerekiyorsa ikisi de doner (birlikte, ayni anda basilir) */
    private fun pickTargets(now: Long): List<Hedef> {
        val r = cfg.radius.toFloat()
        val out = ArrayList<Hedef>(2)
        lootAction(now)?.let { out.add(it) }
        // Kutu varken de skill/saldiri secimi devam eder: hicbiri digerini beklemez
        pick(now)?.let { out.add(Hedef(it.x.toFloat(), it.y.toFloat(), r, fast = out.isNotEmpty())) }
        return out
    }

    /** Gereken potlar (HP, MP ya da ikisi) - beklemeden, saldiriyla birlikte basilir */
    /**
     * KO iksirleri: HP ve MP ortak bekleme kullanir (ayni anda icilmez). Ikisi de dusukse
     * MP onceligi aciksa MP (minor manayla calisir), kapaliysa sirayla (HP, MP, HP...).
     * Can cok dusukse HP her zaman once. Minor bundan bagimsiz.
     */
    private var koSonIksirHp = false
    private fun koPotlar(now: Long): List<Nokta> {
        if (now - maxOf(lastHpPot, lastMpPot) < cfg.potCd.coerceIn(300, 5000)) return emptyList()
        if (koAktif && KoOyun.doluluk(screenW, screenH, false) < 0f && now - koBarAt > 3000) {
            koBarAt = now
            KoOyun.barlariBulArka(screenW, screenH)   // bar okunamadi: yeniden ara
        }
        val hpD = dolulukOku(false)
        val hpDusuk = cfg.hp?.let { isLow(it) } ?: false
        val mpDusuk = cfg.mp?.let { isLow(it) } ?: false
        val hpKritik = hpDusuk && hpD >= 0f && hpD < cfg.hpYuzde / 100f * 0.6f
        val hpSec = when {
            // Ikisi de dusuk: can kritikse HP; degilse MP onceligi aciksa MP, kapaliysa sirayla
            hpDusuk && mpDusuk -> hpKritik || run {
                // Oncelik sirasinda MP HP'den ustteyse once MP
                val o = Config.oncelikListe(cfg.oncelik)
                o.indexOf("hp") < o.indexOf("mp")
            }
            hpDusuk -> true
            mpDusuk -> false
            else -> return emptyList()
        }
        val n = cfg.points.firstOrNull { it.type == if (hpSec) "hp_pot" else "mp_pot" } ?: return emptyList()
        basilanPot++
        if (hpSec) lastHpPot = now else lastMpPot = now
        koSonIksirHp = hpSec
        return listOf(n)
    }

    /** Barin dolulugu (0..1), okunamiyorsa -1. KO: KoOyun; MykoMobile: otomatik tanima acikken */
    private fun dolulukOku(mp: Boolean): Float = when {
        koAktif -> KoOyun.doluluk(screenW, screenH, mp)
        otoMod && olcek != null -> if (mp) barDoluluk(mpBar, false) else barDoluluk(hpBar, true)
        else -> -1f
    }

    /**
     * Sinifa ozel tuslar:
     * - Heal (orn. Priest): can o tusun kendi yuzdesinin altina inince, suresi dolmussa basilir.
     * - Buff: hedeften bagimsiz, suresi doldukca basilir (PK'da skill arasina uyar).
     * Iksir beklemesinden etkilenmez.
     */
    private val destekHazir = HashMap<Int, Long>()
    private fun destekActions(now: Long): List<Nokta> {
        val pts = cfg.points
        if (pts.none { it.on && (it.type == "heal" || it.type == "buff") }) return emptyList()
        val hp = if (pkSaf()) -1f else dolulukOku(false)
        for (i in pts.indices) {
            val p = pts[i]
            if (!p.on || p.type != "heal" || now < (destekHazir[i] ?: 0L)) continue
            // PK "sadece tus": can okunmaz, heal kendi suresi dolunca basilir
            val dusuk = pkSaf() || (if (hp >= 0f) hp < p.yuzde / 100f else (cfg.hp?.let { isLow(it) } ?: false))
            if (dusuk) {
                destekHazir[i] = now + (p.cd * 1000).toLong().coerceAtLeast(500)
                pkSonSkill = now
                return listOf(p)
            }
        }
        for (i in pts.indices) {
            val p = pts[i]
            if (!p.on || p.type != "buff" || now < (destekHazir[i] ?: 0L)) continue
            if (pkAktif && now - pkSonSkill < cfg.skillAraMs) continue
            destekHazir[i] = now + (p.cd * 1000).toLong().coerceAtLeast(1000)
            pkSonSkill = now
            return listOf(p)
        }
        return emptyList()
    }

    private fun potActions(now: Long): List<Nokta> {
        // KO (Farm+PK) ve MykoMobile PK ayni mantik: HP/MP ortak iksir arasi, MP onceligi, kritikte HP
        if (pkSaf()) return pkZamanliPot(now)
        if (koAktif || pkAktif) return koPotlar(now)
        val out = ArrayList<Nokta>(2)
        val hp = cfg.hp
        val potBekle = if (pkAktif) 450 else cfg.potCd
        if (hp != null && now - lastHpPot > potBekle && isLow(hp)) {
            cfg.points.firstOrNull { it.type == "hp_pot" }?.let {
                basilanPot++
                lastHpPot = now
                out.add(it)
            }
        }
        val mp = cfg.mp
        // KO: HP ve MP iksiri ortak bekleme suresi kullanir, ayni anda icilmez. HP onceliklidir:
        // HP potu yeni basildiysa MP bu adimda beklesin (minor ve skill bundan etkilenmez).
        val koIksirBekle = koAktif && now - lastHpPot < 900
        if (mp != null && !koIksirBekle && now - lastMpPot > potBekle && isLow(mp)) {
            cfg.points.firstOrNull { it.type == "mp_pot" }?.let {
                basilanPot++
                lastMpPot = now
                out.add(it)
            }
        }
        return out
    }

    /** Bulunan olcege gore barlari, hedef barini, kutulari ve tuslari yerlestir (sadece bellekte) */
    /** Yedek olcekte HP barinin sol kismi kirmizi mi? (barin gercekten orada oldugunu dogrular) */
    private fun yedekOlcekMakul(o: Preset.Olcek): Boolean {
        if (!ScreenSampler.hasFrame()) return false
        val y = Preset.solUst(o, 0, 26)[1]
        for (xr in intArrayOf(75, 100, 125)) {
            val c = ScreenSampler.readPixel(Preset.solUst(o, xr, 26)[0], y)
            if (c >= 0 && isRed(c)) return true
        }
        return false
    }

    private fun olcekUygula(o: Preset.Olcek, yedek: Boolean = false) {
        olcek = o
        collectAnkorHazirla(o)
        openBandHazirla(o)
        val hp = Preset.solUst(o, 315, 26)
        val mp = Preset.solUst(o, 150, 68)
        val tb = Preset.ustOrta(o, 1262, 60)
        cfg.hp = RenkNokta(hp[0], hp[1], 0)
        cfg.mp = RenkNokta(mp[0], mp[1], 0)
        cfg.tgtBar = RenkNokta(tb[0], tb[1], 0)
        val a = Preset.ustOrta(o, 1195, 66)
        val b = Preset.ustOrta(o, 1560, 66)
        tgtStrip = intArrayOf(a[0], b[0], a[1])
        Preset.loadTemplateF(this, "open.png", o.s, 20, 0.5f, 0.5f)?.let { cfg.openT = it }
        Preset.loadTemplateF(this, "collect.png", o.s, 25, 0.5f, 0.22f)?.let { cfg.collectT = it }
        Preset.loadTemplateF(this, "collect_btn.png", o.s, 19, 0.5f, 0.5f)?.let { cfg.collectT2 = it }
        dcT = Preset.loadTemplateF(this, "disconnect.png", o.s, 20, 0.5f, 0.5f)
        hpSol = Preset.solUst(o, 72, 26)
        val h1 = Preset.solUst(o, 62, 26)
        val h2 = Preset.solUst(o, 405, 26)
        hpBar = intArrayOf(h1[0], h2[0], h1[1])
        val m1 = Preset.solUst(o, 62, 70)
        val m2 = Preset.solUst(o, 407, 70)
        mpBar = intArrayOf(m1[0], m2[0], m1[1])
        isimRect = isimAlani(o)
        // Tuslar oyuncudan: kaydettigi yerlere asla dokunma (eski "hazir tuslari tasima" kapali)
        if (false && cfg.tuslarOto) {
            val yeni = Preset.otoTuslar(o, cfg.points)
            cfg.points.clear()
            cfg.points.addAll(yeni)
            skillReady.clear()
            skillLast.clear()
        }
        if (yedek) {
            toast("Ekran tanınamadı, varsayılan ayarla devam. ⋯ → 🧪 Ekran testi ile kontrol edebilirsin")
        } else {
            toast("Ekran tanındı ✓ (ölçek %.2f)".format(o.s))
        }
    }

    private fun isBlue(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return b >= 110 && b > r * 1.6f && b > g * 1.2f
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return r >= 90 && r > g * 2 && r > b * 2
    }

    /** Barin doluluk orani (0..1): rengin barda ne kadar saga uzandigi */
    private fun barDoluluk(bar: IntArray, kirmizi: Boolean): Float {
        val x1 = bar[0]
        val x2 = bar[1]
        if (x2 <= x1) return 1f
        val n = 40
        var son = -1
        for (k in 0 until n) {
            val c = ScreenSampler.readPixel(x1 + (x2 - x1) * k / (n - 1), bar[2])
            if (c >= 0 && (if (kirmizi) isRed(c) else isBlue(c))) son = k
        }
        return (son + 1).toFloat() / n
    }

    // ---------- Mob kilidi: sadece KIRMIZI hedef ismi (üstteki kırmızı yazı) ----------

    private fun isimAlani(o: Preset.Olcek): IntArray {
        // Üst ortadaki kırmızı mob ismi — barın üstündeki ince şerit
        val a = Preset.ustOrta(o, 1050, 2)
        val b = Preset.ustOrta(o, 1690, 40)
        return intArrayOf(a[0], a[1], b[0], b[1])
    }

    private fun renkFark(a: Int, b: Int) = ScreenSampler.diff(a, b)

    /** Seçili hedefin kırmızı isim pikseli mi? (R baskın, yeşil/mavi düşük) */
    private fun kirmiziIsimPiksel(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return r > 140 && r - g > 40 && r - b > 30 && g < 180
    }

    /** Bölgedeki kırmızı pikseller (isim yazısı + varsa bar/buton kenarı) */
    private fun kirmiziIsimMaske(px: IntArray): BooleanArray =
        BooleanArray(px.size) { kirmiziIsimPiksel(px[it] and 0xFFFFFF) }

    // ---- İsim şekli: ayıkla -> sabit boyuta normalleştir -> karşılaştır ----
    // Ölçüm (gerçek ekran görüntüleri): kırmızı maskeye isim dışında HP barı kenarı ve sandık butonu da
    // giriyordu; genişlik/IoU bozuluyordu. Burada yalnızca isim yazısı ayıklanır, ölçekten bağımsız
    // 96x16 şekle çevrilir. Aynı isim 0.81-1.00, farklı isim (Small/Wild Bulcan, Kecoon fighter...) <= 0.61.
    private class IsimSekil(val bits: BooleanArray, val en: Float)

    private val ISIM_CW = 96
    private val ISIM_CH = 16

    /** Maskeden isim yazısını ayıkla: yazı bandı + küme; dolu çubuk (HP barı) ve kenar parçaları atılır. İsim yoksa null. */
    private fun isimTemizle(m: BooleanArray, w: Int, h: Int): BooleanArray? {
        val rc = IntArray(h)
        for (y in 0 until h) { var c = 0; for (x in 0 until w) if (m[y * w + x]) c++; rc[y] = c }
        val bands = ArrayList<IntArray>()
        var yy = 0
        while (yy < h) {
            if (rc[yy] >= 2) {
                val y0 = yy
                while (yy < h && rc[yy] >= 2) yy++
                if (bands.isNotEmpty() && y0 - bands.last()[1] <= 2) bands.last()[1] = yy
                else bands.add(intArrayOf(y0, yy))
            } else yy++
        }
        var bestPx = 0; var by0 = 0; var by1 = 0; var bx0 = 0; var bx1 = 0; var found = false
        for (bd in bands) {
            val y0 = bd[0]; val y1 = bd[1]
            val cc = IntArray(w)
            for (r in y0 until y1) for (x in 0 until w) if (m[r * w + x]) cc[x]++
            val gap = maxOf(8, y1 - y0)
            var x = 0
            while (x < w) {
                if (cc[x] > 0) {
                    val xs = x; var last = x
                    while (x < w && (cc[x] > 0 || x - last <= gap)) { if (cc[x] > 0) last = x; x++ }
                    val xe = last + 1
                    var px = 0
                    for (c in xs until xe) px += cc[c]
                    val fill = px.toFloat() / ((y1 - y0) * (xe - xs))
                    if ((xe - xs) >= 0.06f * w && fill <= 0.70f && px >= 25 && px > bestPx) {
                        bestPx = px; by0 = y0; by1 = y1; bx0 = xs; bx1 = xe; found = true
                    }
                } else x++
            }
        }
        if (!found) return null
        val out = BooleanArray(w * h)
        for (r in by0 until by1) for (x in bx0 until bx1) out[r * w + x] = m[r * w + x]
        return out
    }

    /** Ayıklanmış isim maskesini bbox'a kırp, sabit 96x16'ya küçült (ölçek/cihaz farkından bağımsız) */
    private fun isimKanon(t: BooleanArray, w: Int, h: Int): IsimSekil? {
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (t[y * w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < x0 || y1 < y0) return null
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        if (bw < 8 || bh < 4) return null
        val out = BooleanArray(ISIM_CW * ISIM_CH)
        for (cy in 0 until ISIM_CH) {
            val sy0 = y0 + bh * cy / ISIM_CH
            val sy1 = maxOf(sy0 + 1, y0 + bh * (cy + 1) / ISIM_CH)
            for (cx in 0 until ISIM_CW) {
                val sx0 = x0 + bw * cx / ISIM_CW
                val sx1 = maxOf(sx0 + 1, x0 + bw * (cx + 1) / ISIM_CW)
                var on = 0; var all = 0
                for (r in sy0 until sy1) for (c in sx0 until sx1) { all++; if (t[r * w + c]) on++ }
                out[cy * ISIM_CW + cx] = on * 100 >= all * 35
            }
        }
        return IsimSekil(out, bw.toFloat() / bh)
    }

    /** İki isim şekli aynı mı? Toplam benzerlik + 3 parçanın HER BİRİ eşleşmeli (aynı önek + farklı son reddedilir) */
    private fun isimKiyasla(a: BooleanArray, b: BooleanArray): Boolean {
        var best = 0f; var bsy = 0
        for (sy in -1..1) for (sx in -1..1) {
            var kes = 0; var bir = 0
            for (y in 0 until ISIM_CH) {
                val yy = y + sy
                for (x in 0 until ISIM_CW) {
                    val xx = x + sx
                    val aa = a[y * ISIM_CW + x]
                    val bb = yy in 0 until ISIM_CH && xx in 0 until ISIM_CW && b[yy * ISIM_CW + xx]
                    if (aa && bb) kes++
                    if (aa || bb) bir++
                }
            }
            if (bir > 0) { val v = kes.toFloat() / bir; if (v > best) { best = v; bsy = sy } }
        }
        if (best < 0.70f) return false
        for (pz in 0 until 3) {
            val s0 = ISIM_CW * pz / 3
            val s1 = ISIM_CW * (pz + 1) / 3
            var pbest = 0f
            for (sx in -1..1) {
                var kes = 0; var bir = 0
                for (y in 0 until ISIM_CH) {
                    val yy = y + bsy
                    for (x in s0 until s1) {
                        val xx = x + sx
                        val aa = a[y * ISIM_CW + x]
                        val bb = yy in 0 until ISIM_CH && xx in 0 until ISIM_CW && b[yy * ISIM_CW + xx]
                        if (aa && bb) kes++
                        if (aa || bb) bir++
                    }
                }
                if (bir > 0) pbest = maxOf(pbest, kes.toFloat() / bir)
            }
            if (pbest < 0.60f) return false
        }
        return true
    }

    private val kilitKanonCache = java.util.IdentityHashMap<Kilit, IsimSekil?>()

    /** Kayıtlı kilidin (eski/yeni kayıt fark etmez) ayıklanmış, normalleştirilmiş şekli */
    private fun kilitKanon(k: Kilit): IsimSekil? {
        if (kilitKanonCache.containsKey(k)) return kilitKanonCache[k]
        var sonuc: IsimSekil? = null
        if (k.w >= 4 && k.h >= 2 && k.bits.length == k.w * k.h) {
            val m = BooleanArray(k.w * k.h) { k.bits[it] == '1' }
            val t = isimTemizle(m, k.w, k.h)
            if (t != null) sonuc = isimKanon(t, k.w, k.h)
        }
        kilitKanonCache[k] = sonuc
        return sonuc
    }

    @Volatile private var isimBilinmiyor = false
    private var isimBilinmiyorSince = 0L
    private var kilitOnayli = false

    /**
     * Seçili hedefin ismi kilitli moblardan biri mi?
     * Bölgedeki kırmızı yazıdan isim ayıklanır, sabit boyuta normalleştirilip kayıtlı kilitlerle karşılaştırılır.
     */
    private fun isimUygun(): Boolean {
        isimBilinmiyor = false
        val r = ScreenSampler.bolge(isimRect[0], isimRect[1], isimRect[2], isimRect[3])
        if (r == null) { isimBilinmiyor = true; return true }
        val (w, h, px) = r
        if (w < 6 || h < 3 || cfg.kilitler.isEmpty()) { isimBilinmiyor = true; return true }
        val t = isimTemizle(kirmiziIsimMaske(px), w, h)
        if (t == null) { isimBilinmiyor = true; return true }          // isim henüz görünmüyor
        val cur = isimKanon(t, w, h)
        if (cur == null) { isimBilinmiyor = true; return true }

        var uyanVar = false
        for (k in cfg.kilitler) {
            // Ekran ölçeği başka cihaz/oturumda çok farklıysa bu kilit bu ekran için geçersiz
            val oranW = w.toFloat() / k.w
            val oranH = h.toFloat() / k.h
            if (oranW < 0.75f || oranW > 1.33f || oranH < 0.75f || oranH > 1.33f) continue
            val lk = kilitKanon(k) ?: continue
            uyanVar = true
            val enOran = maxOf(cur.en / lk.en, lk.en / cur.en)
            if (enOran > 1.18f) continue                                // yazı oranı çok farklı = farklı isim
            if (isimKiyasla(lk.bits, cur.bits)) return true
        }
        if (!uyanVar && !kilitUyarildi) {
            kilitUyarildi = true
            toast("Mob kilidi başka bir ekranda yapılmış. ⋯ menüsünden yeniden kilitle")
        }
        return false
    }

    /** Su an secili mobun ismini kilide ekle */
    private fun mobuKilitle() {
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı. Önce ▶ ile bir kere başlat"); return
        }
        toast("Mob ismi okunuyor…")
        h.post {
            val a = if (Config.koMu(this)) {
                val (lw, lh) = Preset.landscapeSize(this)
                KoOyun.isimAlani(lw, lh)
            } else {
                val o = olcek ?: try { Preset.olcekBul(this) } catch (e: Exception) { null }
                if (o == null) {
                    toast("Oyun ekranı tanınamadı"); return@post
                }
                isimAlani(o)
            }
            val r = ScreenSampler.bolge(a[0], a[1], a[2], a[3])
            if (r == null) {
                toast("Ekran görüntüsü alınamadı"); return@post
            }
            val (w, hh, px) = r
            // Sadece isim yazısı kaydedilir (bar/buton kenarı ayıklanır)
            val t = isimTemizle(kirmiziIsimMaske(px), w, hh)
            if (t == null) {
                toast("Kırmızı isim bulunamadı. Önce mobu seç — üstte kırmızı isim görünsün"); return@post
            }
            if (isimKanon(t, w, hh) == null) {
                toast("Kırmızı isim net değil. Mob seçiliyken tekrar dene"); return@post
            }
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            val bits = StringBuilder(t.size)
            for (k in t.indices) {
                if (t[k]) {
                    val rgb = px[k] and 0xFFFFFF
                    rs.add((rgb shr 16) and 0xff); gs.add((rgb shr 8) and 0xff); bs.add(rgb and 0xff)
                    bits.append('1')
                } else bits.append('0')
            }
            rs.sort(); gs.sort(); bs.sort()
            val renk = (rs[rs.size / 2] shl 16) or (gs[gs.size / 2] shl 8) or bs[bs.size / 2]
            val c = Config.load(this)
            c.kilitler.add(Kilit(w, hh, renk, bits.toString()))
            c.save(this)
            toast("🎯 Kırmızı isim kilitlendi (${c.kilitler.size}). Sadece bu isme vurulacak")
        }
    }

    /** Hedefin kalan cani (0..1): barda kirmizinin ne kadar saga uzandigi */
    private fun hedefYuzde(): Float {
        val x1 = tgtStrip[0]
        val x2 = tgtStrip[1]
        val y = tgtStrip[2]
        if (x2 <= x1) return 1f
        val n = 30
        var son = -1
        for (k in 0 until n) {
            val c = ScreenSampler.readPixel(x1 + (x2 - x1) * k / (n - 1), y)
            if (c >= 0 && isRed(c)) son = k
        }
        return (son + 1).toFloat() / n
    }

    /** Hedef barinin herhangi bir yerinde kirmizi var mi? */
    private fun targetAlive(bar: RenkNokta): Boolean {
        // KO: once Farm'daki sabit yer; orada yoksa ekranin ust ortasinda ara
        if (koAktif) return sabitHedef(bar) || KoOyun.hedefVar(screenW, screenH)
        return sabitHedef(bar)
    }

    private fun sabitHedef(bar: RenkNokta): Boolean {
        val x1 = tgtStrip[0]
        val x2 = tgtStrip[1]
        val y = tgtStrip[2]
        if (x2 > x1) {
            val n = 12
            for (k in 0 until n) {
                val x = x1 + (x2 - x1) * k / (n - 1)
                val c = ScreenSampler.readPixel(x, y)
                if (c >= 0 && isRed(c)) return true
            }
            // Mob olmek uzereyken kirmizi sadece barin SOL ucunda ~20 px kalir ve 12 noktali kaba
            // tarama arasindan kacabilir (skiller o anda duruyordu). Sol %35'i sik (adim ~3 px) ve
            // 3 satirda tara; en az 2 kirmizi nokta yeter. Sadece kaba tarama bos donunce calisir.
            val sonSol = x1 + ((x2 - x1) * 0.35f).toInt()
            val adim = maxOf(2, (x2 - x1) / 110)
            val dy = maxOf(2, screenH / 330)
            var kirmizi = 0
            for (satir in intArrayOf(-dy, 0, dy)) {
                var x = x1
                while (x <= sonSol) {
                    val c = ScreenSampler.readPixel(x, y + satir)
                    if (c >= 0 && isRed(c) && ++kirmizi >= 2) return true
                    x += adim
                }
            }
            return false
        }
        return !isLow(bar)
    }

    /**
     * Minor ac-kapa calisir: can esigin altina inince BIR KEZ basip acar (HP dolar, mana yer),
     * can %97'ye gelince BIR KEZ basip kapatir (mana yemeyi birakir). Arada dokunmaz.
     */
    private fun minorAction(now: Long): Nokta? {
        return null   // Minor bu sürümde tamamen kapalı
        if (pkAktif && Config.sinif(this) != "asas") return null
        if (!cfg.minorAktif) return null
        val m = cfg.points.firstOrNull { it.type == "minor" && it.on } ?: return null
        if (pkSaf()) return pkMinorZamanli(m, now)
        if (koAktif) {
            // KO: Minor ac/kapa calisir (bir basis acar, bir basis kapatir).
            // Can ayardaki %'nin altina inince ac, %97'ye cikinca kapat.
            if (now - minorSon < 400) return null
            val hp = KoOyun.doluluk(screenW, screenH, false)
            if (hp < 0f) return null
            if (!minorAcik && hp < cfg.minorYuzde / 100f) {
                minorAcik = true; minorSon = now; return m
            }
            if (minorAcik && hp >= 0.97f) {
                minorAcik = false; minorSon = now; return m
            }
            return null
        }
        if (olcek == null || hpBar[1] <= hpBar[0]) return null
        // Minor aninda tepki veriyor; sadece ayni ekran karesine iki kez basmamak icin kisa bekleme
        if (now - minorSon < 150) return null
        val hp = barDoluluk(hpBar, true)
        if (!minorAcik && hp < cfg.minorYuzde / 100f) {
            minorAcik = true
            minorSon = now
            return m
        }
        if (minorAcik && hp >= 0.97f) {
            minorAcik = false
            minorSon = now
            return m
        }
        return null
    }

    /**
     * PK: kilicla hedef sec; hedef varken skilleri SENIN SIRANLA bas (1-2-3-...-1).
     * Siradaki kisa sure icinde hazir olacaksa onu bekler (sira bozulmaz), uzun bekleyecekse atlar.
     */
    private fun pkSec(now: Long): Nokta? {
        val pts = cfg.points
        val kilic = pts.firstOrNull { it.type == "saldiri" }
        val bar = cfg.tgtBar
        // PK "sadece tus": hedef barina bakilmaz, her zaman "hedef var" kabul edilir
        val alive = pkSaf() || (bar != null && ScreenSampler.running && targetAlive(bar))
        if (!alive) {
            if (kilic != null && now >= nextTarget) {
                nextTarget = now + rand(300, 450)
                return kilic
            }
            return null
        }
        // Skiller: her skillin kendi suresi (sn) + iki skill arasi ortak bekleme (Skill arasi).
        // Ortak bekleme biter bitmez, sirayla ilk hazir skill basilir; hazir olan yoksa kilic.
        // Pot ve minor bundan bagimsiz, ayni adimda ayrica basilir.
        val skills = pts.indices.filter { pts[it].type == "skill" && pts[it].on }
        if (skills.isNotEmpty() && now - pkSonSkill >= cfg.skillAraMs) {
            for (deneme in skills.indices) {
                val sira = (pkSira + deneme) % skills.size
                val i = skills[sira]
                if ((skillReady[i] ?: 0L) <= now) {
                    skillReady[i] = now + (pts[i].cd * 1000).toLong()
                    pkSonSkill = now
                    pkSira = (sira + 1) % skills.size
                    return pts[i]
                }
            }
        }
        // Kilic surekli: skill zamani degilse araliklarla kilica bas
        if (kilic != null && now - pkSonKilic >= rand(450, 650)) {
            pkSonKilic = now
            return kilic
        }
        return null
    }

    private fun pick(now: Long): Nokta? {
        if (pkAktif) return pkSec(now)
        val pts = cfg.points
        val target = pts.firstOrNull { it.type == "hedef" }
        val bar = cfg.tgtBar

        if (target != null && bar != null && ScreenSampler.running) {
            // Akilli mod: hedef barina bak
            val alive = targetAlive(bar)
            if (oncekiCanli && !alive) {
                TestLog.olay("HEDEF_KAYBI", if (now - iptalAt > 2500) "hedef öldü/kayboldu" else "hedef X ile bırakıldı", "sure_ms=${now - hedefBaslangic}")
                if (now - iptalAt > 2500) kesilen++   // X ile birakilan mob kesildi sayilmaz
                if (now >= menzilBekleUntil) nextTarget = 0L   // mob oldu: yeni hedefi hemen sec (kutu paralel toplanir)
            }
            if (alive && !oncekiCanli) {
                TestLog.olay("HEDEF_ALINDI", "yeni hedef", "")
                // Yeni hedef: ilerleme sayacini ve mesafe sayacini baslat
                sonYuzde = hedefYuzde()
                ilerlemeAt = now
                hedefBaslangic = now
                ilkVurus = false
                alanBitti = false
                kilitOnayli = false
                isimBilinmiyorSince = 0L
            }
            oncekiCanli = alive
            // Uzak mob birakildiktan sonra bekleme: skill/mob secimi yok (potlar ayri calisir).
            // Hedef hala secili kalmissa X'i tekrar gonder.
            if (now < menzilBekleUntil) {
                if (alive && now - menzilXAt > 900) {
                    menzilXAt = now
                    val ol = olcek
                    if (ol != null) {
                        val x = iptalNoktasi(ol)
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                }
                return null
            }
            if (alive) {
                // Takili hedef: 12 sn boyunca cani azalmiyorsa birak, yenisini sec
                val y = hedefYuzde()
                val o0 = olcek
                if (y < sonYuzde - 0.02f) {
                    sonYuzde = y
                    ilerlemeAt = now
                    ilkVurus = true
                    menzilArdArda = 0
                } else if (false && !ilkVurus && cfg.menzilAktif && (o0 != null || koAktif) &&
                    now - hedefBaslangic > cfg.menzilSn * 1000L
                ) {
                    // Mesafe siniri: bu surede vurulmaya baslanmadi -> mob uzakta, birak
                    // Kapali: skiller mesafe gozetmeksizin basilir (kullanici istegi)
                    hedefBaslangic = now
                    ilkVurus = true
                    // Ayni uzak moba tekrar kosmamak icin bekle; ust uste olursa bekleme uzar
                    menzilArdArda++
                    val bekleSn = minOf(cfg.menzilBekleSn * menzilArdArda, 20)
                    menzilBekleUntil = now + bekleSn * 1000L
                    menzilXAt = now
                    iptalAt = now
                    nextTarget = menzilBekleUntil
                    val x = if (o0 != null) iptalNoktasi(o0) else koIptal()
                    return Nokta("İptal", "iptal", x[0], x[1])
                } else if (now - ilerlemeAt > 12_000) {
                    ilerlemeAt = now
                    sonYuzde = 1f
                    val ol = olcek
                    if (ol != null || koAktif) {
                        // Once hedefi iptal et (X), sonraki adimda yeni mob sec
                        val x = if (ol != null) iptalNoktasi(ol) else koIptal()
                        iptalBekliyor = true
                        iptalAt = now
                        nextTarget = 0L
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                    nextTarget = 0L
                    return target
                }
            }
            // Alan siniri: secili mobun halkasi dikdortgenin disindaysa X ile birak.
            // Halka bulunamazsa engelleme (guvenli taraf: normal calismaya devam).
            if (alive && cfg.alanAktif && !alanBitti) {
                val halka = secimHalkasi()
                if (halka != null) {
                    alanBitti = true
                    if (alanIcinde(halka[0], halka[1])) {
                        alanArdArda = 0
                    } else {
                        alanArdArda++
                        if (alanArdArda >= 4) {
                            // Ust uste 4 hedef alan disi: alanda mob yok, biraz bekle
                            alanBekleUntil = now + 4000
                            alanArdArda = 0
                        }
                        nextTarget = now + rand(350, 500)
                        iptalAt = now
                        val ol = olcek
                        if (ol != null || koAktif) {
                            val x = if (ol != null) iptalNoktasi(ol) else koIptal()
                            return Nokta("İptal", "iptal", x[0], x[1])
                        }
                    }
                } else if (now - hedefBaslangic > 1800) {
                    alanBitti = true
                }
            }
            // Alan kontrolu bitmeden (en fazla 0,7 sn) skill basma: karakter uzak moba kosmaya baslamasin
            if (alive && cfg.alanAktif && !alanBitti && now - hedefBaslangic < 700) return null
            // Mob kilidi
            val o = olcek
            if (cfg.kilitler.isNotEmpty() && (o != null || koAktif)) {
                // Bekleme modu: etrafta kilitli mob yok, skill/saldiri/secim tamamen durur
                if (now < kilitBekleUntil) return null
                if (alive) {
                    var uygun = isimUygun()
                    if (isimBilinmiyor) {
                        // İsim henüz okunamadı: kısa süre bekle (seç-bırak döngüsü olmasın)
                        if (isimBilinmiyorSince == 0L) isimBilinmiyorSince = now
                        if (now - isimBilinmiyorSince < 1500L) return null
                        uygun = false   // 1,5 sn okunamadı: doğrulanamayan mobu bırak, kilitsiz mob vurulmasın
                    } else {
                        isimBilinmiyorSince = 0L
                    }
                    if (uygun) {
                        kilitKotu = 0
                        // İsim en az 200 ms tutarlı eşleşsin, sonra skill/saldırı
                        if (!kilitOnayli) {
                            if (now - hedefBaslangic < 200) return null
                            kilitOnayli = true
                        }
                    } else {
                        // Hedef yeni seçildi, isim yazısı henüz belirmemiş/solgun olabilir: ilk 250 ms yanlış sayma
                        if (now - hedefBaslangic < 250) return null
                        // Tek kare yanlış = hemen X yok (skill durur). 2 üst üste yanlışta bırak.
                        kilitKotu++
                        if (kilitKotu < 2) return null
                        kilitOnayli = false
                        if (kilitKotu >= 8) {
                            kilitBekleUntil = now + 8000
                            kilitKotu = 6
                            TestLog.olay("KILIT_BEKLE", "kilitli mob bulunamadı, 8 sn bekleniyor", "")
                        }
                        TestLog.olay("KILIT_RED", "yanlış mob, hedef bırakıldı", "kilitKotu=$kilitKotu")
                        nextTarget = now + rand(400, 600)
                        iptalAt = now
                        val x = if (o != null) iptalNoktasi(o) else koIptal()
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                }
            }
            if (!alive) {
                // Alan icinde mob yok: kisa sure bekle (potlar ayri calismaya devam eder)
                if (cfg.alanAktif && now < alanBekleUntil) return null
                // Hedef yok / oldu: sadece yeni mob sec, skill harcama
                if (now >= nextTarget) {
                    nextTarget = now + rand(cfg.tgtFast, cfg.tgtFast + 400)
                    return target
                }
                return null
            }
        } else if (target != null && now >= nextTarget) {
            // Basit mod: belirli araliklarla mob sec
            nextTarget = now + rand(cfg.tgtMin, cfg.tgtMax)
            return target
        }

        // Hazir skiller arasindan en uzun suredir basilmayani sec (sirayla doner).
        // Esitlikte listedeki sira onceliklidir. Bekleme 0 = surekli.
        var best = -1
        var bestLast = Long.MAX_VALUE
        for (i in pts.indices) {
            val p = pts[i]
            if (p.type != "skill" || !p.on) continue
            if (now < (skillReady[i] ?: 0L)) continue
            val last = skillLast[i] ?: 0L
            if (last < bestLast) {
                bestLast = last
                best = i
            }
        }
        if (best >= 0) {
            val p = pts[best]
            skillLast[best] = now
            skillReady[best] = now + if (p.cd > 0f) {
                (p.cd * 1000).toLong() + rand(cfg.skMin, cfg.skMax)
            } else 0L
            return p
        }

        // Capraz kilic (saldiri) tusu kullanilmaz: mob secimi + skiller yeterli
        return null
    }

    // ---------- Kutu ----------

    private fun findT(f: ScreenSampler.Frame, t: Sablon): IntArray? =
        ScreenSampler.find(f, t, if (t.tol > 0) t.tol else cfg.ttol)

    private fun sablonHedef(t: Sablon, pos: IntArray): Hedef {
        val cx = ScreenSampler.toScreen(pos[0] + t.w * t.tx)
        val cy = ScreenSampler.toScreen(pos[1] + t.h * t.ty)
        val r = minOf(
            cfg.radius.toFloat(),
            ScreenSampler.toScreen(t.w.toFloat()) * 0.15f,
            ScreenSampler.toScreen(t.h.toFloat()) * 0.07f
        )
        return Hedef(cx, cy, r)
    }

    private fun scanGap(): Long {
        // Kutu taramasi her zaman seri: en fazla 150 ms
        val b = minOf(cfg.lootEvery, 150).coerceAtLeast(100)
        return rand((b * 0.8).toInt(), (b * 1.2).toInt())
    }

    /**
     * Kutu akisi:
     *  BOS           -> Open gorulurse bas, COLLECT_BEKLE'ye gec
     *  COLLECT_BEKLE -> Collect All cikinca bas, SONRAKI_KUTU'ya gec
     *  SONRAKI_KUTU  -> kisa sure baska Open var mi bak (seri toplama), yoksa saldiriya don
     */
    // Collect All penceresi oyunda EKRANDA SABIT bir yerde acilir (karakterin/kameranin
    // yerine gore degismez). Bunu her karede tum ekranda aramak yerine, sadece o sabit
    // noktanin cevresinde arayarak hem cok hizlanir hem de kutu ile saldiri birbirini
    // geciktirmez. Sabit nokta, referans ekranda (2712x1220) olculmustur: (398, 540).
    private var collectAnkorGX = -1
    private var collectAnkorGY = -1

    /** X (iptal) noktasi: kullanici ogrettiyse onu kullan, yoksa hazir preset konumu */
    /** KO: kullanicinin kaydettigi Iptal tusu, yoksa hazir kirmizi X */
    private fun koIptal(): IntArray {
        cfg.points.firstOrNull { it.type == "iptal" }?.let { return intArrayOf(it.x, it.y) }
        return KoOyun.iptalNokta(screenW, screenH)
    }

    private fun iptalNoktasi(o: Preset.Olcek): IntArray {
        cfg.points.firstOrNull { it.type == "iptal" }?.let { return intArrayOf(it.x, it.y) }
        return Preset.sagAlt(o, 2632, 926)
    }

    private fun collectAnkorHazirla(o: Preset.Olcek) {
        collectKesin = false
        val p = Preset.solUst(o, 398, 540)
        collectAnkorGX = (p[0] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        collectAnkorGY = (p[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
    }

    // Open (kutu/mob dusunca cikan "Open" yazisi) HUD'a gore degil kameraya/karaktere gore
    // hareket eder, ama olculdugune gore ekranin daima ayni dikey bandinda kalir: ustte
    // can/mana/gorev seridi, altta menu cubugu asilmaz. Sadece bu bantta aramak yeter.
    private var openBandGY1 = -1
    private var openBandGY2 = -1

    private fun openBandHazirla(o: Preset.Olcek) {
        val ust = Preset.solUst(o, 0, 358)
        val alt = Preset.solUst(o, 0, 1114)
        openBandGY1 = (ust[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        openBandGY2 = (alt[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
    }

    /** Open sablonunu sadece dikey bantta arar (tam ekran degil) */
    private fun findOpen(f: ScreenSampler.Frame, op: Sablon): IntArray? {
        if (openBandGY1 < 0) return findT(f, op)
        return ScreenSampler.findBand(f, op, if (op.tol > 0) op.tol else cfg.ttol, openBandGY1, openBandGY2)
    }

    /** Collect All penceresini ara: sabit konumun cevresinde, once tum pencere, olmazsa sadece buton */
    private fun findCollect(f: ScreenSampler.Frame, genis: Boolean = false, bekleme: Boolean = false): Pair<Sablon, IntArray>? {
        if (collectAnkorGX < 0) return null
        // BILINEN YER: Collect All bir kere bulununca yeri hatirlanir; sonraki kutularda sadece
        // o noktanin hemen cevresine (~16 px) bakilir. Genis tarama yok, bot hizli kalir.
        if (collectKesin) {
            for (t in listOfNotNull(cfg.collectT, cfg.collectT2)) {
                ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, collectAnkorGX, collectAnkorGY, 8)
                    ?.let { return t to collectOgren(it) }
            }
            // Pencere acilmasi bekleniyorsa (Open'dan hemen sonra) genis arama yapma, sadece bilinen yere bak
            if (bekleme) return null
        }
        val m = 60   // izgara birimi (~120 ekran pikseli): kucuk cihaz/olcek sapmalarini tolere eder
        cfg.collectT?.let { t ->
            ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, collectAnkorGX, collectAnkorGY, m)
                ?.let { return t to collectOgren(it) }
        }
        cfg.collectT2?.let { t ->
            ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, collectAnkorGX, collectAnkorGY, m)
                ?.let { return t to collectOgren(it) }
        }
        // Tum ekran taramasi AGIR: sadece (1) Open'a basildiktan sonra pencere bekleniyorsa ve
        // (2) cihaz tablet gibi (kare'ye yakin ekran) ise yapilir. Telefonlarda hic calismaz,
        // bot hizi etkilenmez. Bulunca yerini ogrenir, sonraki seferler yine hizli olur.
        if (!genis) return null
        val uzun = maxOf(screenW, screenH).toFloat(); val kisa = minOf(screenW, screenH).toFloat()
        if (uzun / kisa >= 1.85f) return null
        val simdi = System.currentTimeMillis()
        if (simdi - collectGenisAt < 1200) return null
        collectGenisAt = simdi
        // Tablette arayuz yukseklige gore buyur: sablonun farkli boylarini da dene (bir kez hazirlanir)
        val varyant = collectVaryant ?: collectVaryantHazirla().also { collectVaryant = it }
        val adaylar = listOfNotNull(cfg.collectT?.let { false to it }, cfg.collectT2?.let { true to it }) + varyant
        for ((dugme, t) in adaylar) {
            findT(f, t)?.let { pos ->
                collectOgren(pos)
                // Tutan boyu hatirla: sonraki kutularda hizli yakin arama bununla yapilir
                if (dugme) cfg.collectT2 = t else cfg.collectT = t
                return t to pos
            }
        }
        return null
    }
    private var collectGenisAt = 0L
    private var collectKesin = false   // Collect All'in tam yeri ogrenildi mi

    private fun collectOgren(pos: IntArray): IntArray {
        collectAnkorGX = pos[0]; collectAnkorGY = pos[1]
        collectKesin = true
        return pos
    }
    private var collectVaryant: List<Pair<Boolean, Sablon>>? = null

    /** Collect sablonunun farkli boylari: (dugme mi, sablon) */
    private fun collectVaryantHazirla(): List<Pair<Boolean, Sablon>> {
        val gen = olcek?.s ?: (screenW / 2712f)
        val yuk = screenH / 1220f
        val olcekler = listOf(yuk, gen * 1.15f, gen * 1.3f, yuk * 1.15f, gen * 0.87f, yuk * 0.87f)
            .map { Math.round(it * 100) / 100f }.distinct()
        val out = ArrayList<Pair<Boolean, Sablon>>()
        for (sc in olcekler) {
            try {
                Preset.loadTemplateF(this, "collect.png", sc, 25, 0.5f, 0.22f)?.let { out.add(false to it) }
                Preset.loadTemplateF(this, "collect_btn.png", sc, 19, 0.5f, 0.5f)?.let { out.add(true to it) }
            } catch (e: Exception) {
                hataKaydet("collect", e)
            }
        }
        return out
    }

    /** Ayni Open'a, kutu toplanmadan tekrar basma */
    private fun openBlocked(now: Long, op: Sablon, pos: IntArray): Boolean {
        // Dolu envanter yuzunden kapatilan kutu: 45 sn ayni yerdeki Open'a basma
        if (now - doluKutuAt < 45_000) {
            val d = sablonHedef(op, pos)
            if (kotlin.math.abs(d.x - doluKutuX) < 80 && kotlin.math.abs(d.y - doluKutuY) < 80) return true
        }
        if (collectedSinceOpen) return false
        if (now - lastOpenAt > 1200) return false   // sadece cift basmayi onle
        val t = sablonHedef(op, pos)
        return kotlin.math.abs(t.x - lastOpenX) < 80 && kotlin.math.abs(t.y - lastOpenY) < 80
    }

    /**
     * Kutu akisi:
     *  BOS           -> Open gorulurse BIR KERE bas, COLLECT_BEKLE'ye gec
     *  COLLECT_BEKLE -> sadece Collect All ara (Open'a basma), cikinca bas
     *  SONRAKI_KUTU  -> kisa sure yeni kutu var mi bak (seri toplama), yoksa saldiriya don
     */
    /** KO Mobile: tek butonlu kutu toplama (koyu daire + altin sandik) */
    private fun koLootAction(now: Long): Hedef? {
        if (!cfg.lootOn || !ScreenSampler.running || now < lootPauseUntil) return null
        if (now < nextLootScan) return null
        nextLootScan = now + rand(140, 220)
        if (now - koSonKutu < 900) return null   // ayni kutuya ust uste basma
        // Oyuncu kutu butonunun yerini kaydettiyse orasi (tablet/farkli ekran), yoksa telefon olcusu
        val kayitli = cfg.points.firstOrNull { it.type == "kutu" }?.let { floatArrayOf(it.x.toFloat(), it.y.toFloat()) }
        if (!KoOyun.kutuVar(screenW, screenH, kayitli)) return null
        koSonKutu = now
        toplanan++
        val p = kayitli ?: KoOyun.kutuNokta(screenW, screenH)
        return Hedef(p[0], p[1], cfg.radius.toFloat())
    }

    private fun lootAction(now: Long): Hedef? {
        if (pkSaf()) return null   // PK "sadece tus": kutu icin ekran taramasi yok
        if (koAktif) return if (pkAktif) null else koLootAction(now)
        val op = cfg.openT
        val hasCollect = cfg.collectT != null || cfg.collectT2 != null
        if (op == null && !hasCollect) return null
        if (pkAktif) return null
        if (!cfg.lootOn || !ScreenSampler.running || now < lootPauseUntil) {
            lootPhase = Loot.BOS
            return null
        }
        if (now < nextLootScan) return null
        val f = ScreenSampler.grab() ?: return null

        when (lootPhase) {
            Loot.COLLECT_BEKLE -> {
                findCollect(f, genis = true, bekleme = now - lastOpenAt < 600)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (now > phaseUntil) {
                    TestLog.olay("KUTU_ZAMAN_ASIMI", "Collect All penceresi çıkmadı", "bekleme_ms=${cfg.collectWait}")
                    lootPhase = Loot.BOS
                    nextLootScan = now + scanGap()
                } else {
                    nextLootScan = now + rand(60, 100)
                }
                return null
            }
            Loot.SONRAKI_KUTU -> {
                findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (op != null) {
                    val pos = findOpen(f, op)
                    if (pos != null && !openBlocked(now, op, pos)) return openHit(now, op, pos)
                }
                if (now > phaseUntil) {
                    lootPhase = Loot.BOS
                    nextLootScan = now + scanGap()
                } else {
                    nextLootScan = now + rand(60, 110)
                }
                return null
            }
            Loot.BOS -> {}
        }

        nextLootScan = now + scanGap()
        // Acik kalmis kutu penceresi varsa once onu topla
        findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
        if (op != null) {
            val pos = findOpen(f, op)
            if (pos != null && !openBlocked(now, op, pos)) return openHit(now, op, pos)
        }
        return null
    }

    private fun openHit(now: Long, op: Sablon, pos: IntArray): Hedef {
        collectStreak = 0
        lootPhase = Loot.COLLECT_BEKLE
        TestLog.olay("KUTU_OPEN", "Open basıldı", "")
        phaseUntil = now + cfg.collectWait
        nextLootScan = now + rand(100, 150)
        val t = sablonHedef(op, pos)
        lastOpenX = t.x
        lastOpenY = t.y
        lastOpenAt = now
        collectedSinceOpen = false
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    /** Collect All penceresindeki Close butonunun yeri (eslesen sablona gore) */
    private fun closeHedef(co: Sablon, pos: IntArray): Hedef {
        // Tum pencere sablonunda Close alt kisimda (%74), sadece-buton sablonunda butonun altinda
        val ty = if (co === cfg.collectT2) 2.15f else 0.74f
        val cx = ScreenSampler.toScreen(pos[0] + co.w * 0.5f)
        val cy = ScreenSampler.toScreen(pos[1] + co.h * ty)
        return Hedef(cx, cy, minOf(cfg.radius.toFloat(), ScreenSampler.toScreen(co.w.toFloat()) * 0.15f, dp(5).toFloat()), fast = true)
    }

    /**
     * Collect All: envanter doluysa bile ayni esyalar ust uste eklenebildigi icin once
     * iki kez Collect All denenir; pencere yine kapanmazsa Close ile kapatilip devam edilir.
     */
    private fun collectHit(now: Long, co: Sablon, pos: IntArray): Hedef? {
        collectStreak++
        TestLog.olay("KUTU_COLLECT", "Collect All", "seri=$collectStreak")
        lootPhase = Loot.SONRAKI_KUTU
        phaseUntil = now + 700
        nextLootScan = now + rand(130, 190)
        if (collectStreak >= 3) {
            // 2 kez Collect All'a rağmen acik: envanter dolu, Close ile kapat ve devam et
            doluKutuX = lastOpenX
            doluKutuY = lastOpenY
            doluKutuAt = now
            cantaDoluBayrak = true
            TestLog.olay("CANTA_DOLU", "Collect All kapanmadı: envanter dolu", "")
            collectedSinceOpen = true   // bu Open icin islem bitti; tutarlilik (openBlocked icin)
            return closeHedef(co, pos)
        }
        collectedSinceOpen = true
        toplanan++
        val t = sablonHedef(co, pos)
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    // ---------- Zamanlama ve dokunus ----------

    /** Parmagin ekranda kalma suresi: Seri modda kisa */
    private fun basmaSuresi(): Long = if (cfg.maxDelay <= 150) rand(35, 70) else rand(55, 140)

    private fun nextDelay(): Long {
        if (pkAktif) return if (koAktif) rand(150, 240) else rand(40, 90)
        val lo = cfg.minDelay.coerceAtLeast(50)
        val hi = cfg.maxDelay.coerceAtLeast(lo + 1)
        // Iki rastgele sayinin ortalamasi: ortaya yakin, dogal dagilim
        var d = (rand(lo, hi) + rand(lo, hi)) / 2
        if (rnd.nextInt(100) < cfg.pauseChance.coerceIn(0, 100)) d += rand(cfg.pauseMin, cfg.pauseMax)
        return d
    }

    private fun stroke(x: Float, y: Float, radius: Float, start: Long, sure: Long = -1L): GestureDescription.StrokeDescription {
        val r = radius.coerceAtLeast(0f)
        val maxX = (screenW - 2).toFloat().coerceAtLeast(2f)
        val maxY = (screenH - 2).toFloat().coerceAtLeast(2f)
        val dx = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val dy = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val sx = (x + dx).coerceIn(1f, maxX)
        val sy = (y + dy).coerceIn(1f, maxY)
        val ex = (sx + rnd.nextInt(7) - 3).coerceIn(1f, maxX)
        val ey = (sy + rnd.nextInt(7) - 3).coerceIn(1f, maxY)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        return GestureDescription.StrokeDescription(path, start, if (sure > 0) sure else basmaSuresi())
    }

    /** Birden fazla noktaya ayni anda (farkli parmaklarla) dokun */
    private fun tapMulti(list: List<Hedef>, done: () -> Unit) {
        // Yon pedleri kullaniliyorsa botun dokunuslari pedlerin akisina eklenir
        if (padKullaniliyor()) { padBotEkle(list, done); return }
        if (list.size == 1) {
            tap(list[0].x, list[0].y, list[0].r, done); return
        }
        val g = try {
            val b = GestureDescription.Builder()
            list.forEachIndexed { i, t ->
                // Diger parmaklar cok hafif gecikmeli, insan gibi
                b.addStroke(stroke(t.x, t.y, t.r, if (i == 0) 0L else rand(0, 40)))
            }
            b.build()
        } catch (e: Exception) {
            null
        }
        if (g == null) {
            // Coklu dokunus desteklenmezse sirayla hizlica bas
            tapSeq(list, 0, done)
            return
        }
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done()
            }
        }, h)
        if (!ok) tapSeq(list, 0, done)
    }

    // ================= Yon joystick'leri (PK) =================
    // Sorun: Android'de dispatchGesture, o an devam eden TUM dokunuslari (senin parmagin dahil) iptal eder.
    // Bu yuzden bot tuslara basarken oyunun kendi joystick'iyle yuruyemezdin. Cozum: ekranda iki JOYSTICK
    // goster; parmagini joystick'te tutup oynattikca hareket oyundaki yurume/kamera noktasina AYNI dokunus
    // akisi icinde aktarilir, botun tuslari da ayni akisa eklenir. Boylece parmaklar birbirini iptal etmez.
    //  - Sol joystick: yurume (her yone, analog).
    //  - Sag joystick: kamera (sola/saga cevirir; ne kadar itersen o kadar hizli doner).
    private class Pad(val tur: Int) {   // 0 = yurume, 1 = kamera
        var view: View? = null
        var down = false
        var dx = 0f; var dy = 0f              // topuzun merkezden kaymasi (piksel)
        var yaricap = 1f                      // joystick'in kullanilabilir yaricapi (piksel)
        var ofs = 0f                          // kamera: oyundaki toplam kaydirma
        var stroke: GestureDescription.StrokeDescription? = null
        var lx = 0f; var ly = 0f              // oyuna son aktarilan nokta
        var birak = false
    }

    private val padlar = ArrayList<Pad>()
    private val padKilit = Any()
    private var padUcusta = false
    private val padBotSirasi = ArrayList<Pair<List<Hedef>, () -> Unit>>()
    private var padHata = 0
    private val PAD_SEG = 45L

    private fun padKullaniliyor(): Boolean = synchronized(padKilit) {
        padlar.isNotEmpty() && padlar.any { it.down || it.stroke != null || it.birak } || padUcusta
    }

    /** Oyundaki yurume (sol yari) ve kamera (sag yari) baslangic noktalari; joystick'lerin altinda DEGIL */
    private fun padTaban(p: Pad): FloatArray {
        if (p.tur == 0) {
            // Oyunun yurume joystick'inin halkasi: kayitli "Joystick" tusu varsa orasi, yoksa olculen varsayilan
            // (video: soldan %24.6, yukaridan %61.3). Oyunun joystick'i hem yurutur hem sag/sol ile cevirir.
            cfg.points.firstOrNull { it.type == "joy" }?.let { return floatArrayOf(it.x.toFloat(), it.y.toFloat()) }
            return floatArrayOf(screenW * 0.246f, screenH * 0.613f)
        }
        return floatArrayOf(screenW * 0.60f, screenH * 0.25f)
    }

    /** -1..1 arasi normallestirilmis itme (olu bolge dahil) */
    private fun padItme(p: Pad): FloatArray {
        var nx = p.dx / p.yaricap
        var ny = p.dy / p.yaricap
        val d = Math.hypot(nx.toDouble(), ny.toDouble()).toFloat()
        if (d > 1f) { nx /= d; ny /= d }
        if (d < 0.12f) { nx = 0f; ny = 0f }
        return floatArrayOf(nx, ny)
    }

    private fun padHedef(p: Pad): FloatArray {
        val tb = padTaban(p)
        if (p.tur != 0) return floatArrayOf((tb[0] + p.ofs).coerceIn(2f, screenW - 3f), tb[1])
        val itme = padItme(p)
        val menzil = screenH * 0.11f   // oyundaki joystick topuzunun azami yolu (videoda ~%10-12)
        return floatArrayOf(
            (tb[0] + itme[0] * menzil).coerceIn(2f, screenW - 3f),
            (tb[1] + itme[1] * menzil).coerceIn(2f, screenH - 3f)
        )
    }

    /** Ekranda gorunen joystick: taban daire + oynayan topuz */
    private fun joystickGorunumu(p: Pad, etiket: String): View {
        val taban = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x70000000 }
        val halka = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = 0xDDE0B04A.toInt() }
        val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = 0x55E0B04A }
        val topuz = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xEEE0B04A.toInt() }
        val yazi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
        return object : View(this) {
            override fun onDraw(c: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = width / 2f - dp(3)
                c.drawCircle(cx, cy, r, taban)
                c.drawCircle(cx, cy, r, halka)
                c.drawCircle(cx, cy, r * 0.62f, ic)
                if (p.tur == 0) {
                    c.drawText("▲", cx, cy - r * 0.78f + yazi.textSize / 3, yazi)
                    c.drawText("▼", cx, cy + r * 0.78f + yazi.textSize / 3, yazi)
                    c.drawText("◀", cx - r * 0.78f, cy + yazi.textSize / 3, yazi)
                    c.drawText("▶", cx + r * 0.78f, cy + yazi.textSize / 3, yazi)
                } else {
                    c.drawText("◀", cx - r * 0.78f, cy + yazi.textSize / 3, yazi)
                    c.drawText("▶", cx + r * 0.78f, cy + yazi.textSize / 3, yazi)
                }
                c.drawText(etiket, cx, cy + r * 0.45f, yazi)
                c.drawCircle(cx + p.dx, cy + p.dy, r * 0.34f, topuz)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                val cx = width / 2f
                val cy = height / 2f
                val sinir = width / 2f * 0.80f
                p.yaricap = sinir
                fun kaydir() {
                    var x = e.x - cx
                    var y = e.y - cy
                    val d = Math.hypot(x.toDouble(), y.toDouble()).toFloat()
                    if (d > sinir) { x *= sinir / d; y *= sinir / d }
                    synchronized(padKilit) { p.dx = x; p.dy = y }
                    invalidate()
                }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        synchronized(padKilit) { p.down = true; p.birak = false; p.stroke = null; p.ofs = 0f }
                        kaydir()
                        padPompa()
                    }
                    MotionEvent.ACTION_MOVE -> kaydir()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        synchronized(padKilit) {
                            p.down = false
                            p.birak = p.stroke != null
                            p.dx = 0f; p.dy = 0f
                        }
                        invalidate()
                        padPompa()
                    }
                }
                return true
            }
        }
    }

    private fun padKur() {
        ui.post {
            padKaldirUi()
            if (!pkAktif || !cfg.padAcik || screenW <= 0) return@post
            val yBoy = (screenH * 0.38f).toInt()
            val kBoy = (screenH * 0.30f).toInt()
            fun ekle(p: Pad, x: Int, y: Int, boy: Int, etiket: String) {
                p.yaricap = boy / 2f * 0.80f
                val v = joystickGorunumu(p, etiket)
                // Panelle AYNI pencere turu (erisilebilirlik katmani): "ustte gosterme" izni gerekmez
                val par = lp(boy, boy).apply { this.x = x; this.y = y }
                wm.addView(v, par)
                p.view = v
                synchronized(padKilit) { padlar.add(p) }
            }
            try {
                // Sol alt: oyunun joystick'i ile AYNI isi yapar (yurut + sag/sol cevir). Oyunun kendi halkasinin
                // ustunu ortmez (halka soldan %24.6'da).
                ekle(Pad(0), (screenW * 0.012f).toInt(), (screenH * 0.58f).toInt(), yBoy, "YÜRÜ / DÖN")
                if (cfg.padKamera) ekle(Pad(1), (screenW * 0.34f).toInt(), (screenH * 0.66f).toInt(), kBoy, "KAMERA")
                toast("🕹 Joystick hazır: ileri/geri yürür, sağ/sol çevirir")
            } catch (e: Exception) {
                hataKaydet("pad", e)
                toast("Joystick açılamadı: ${e.message}")
                padKaldirUi()
            }
        }
    }

    private fun padKaldir() { ui.post { padKaldirUi() } }

    private fun padKaldirUi() {
        synchronized(padKilit) {
            for (p in padlar) { p.view?.let { safeRemove(it) }; p.view = null; p.down = false; p.stroke = null; p.birak = false }
            padlar.clear()
            padBotSirasi.forEach { it.second() }   // bekleyen botu serbest birak
            padBotSirasi.clear()
        }
    }

    private fun padBotEkle(list: List<Hedef>, done: () -> Unit) {
        synchronized(padKilit) { padBotSirasi.add(list to done) }
        padPompa()
    }

    /** Joystick'lerin ve botun dokunuslarini TEK jestte gonderir; jest bitince tekrar calisir */
    private fun padPompa() {
        val strokes = ArrayList<GestureDescription.StrokeDescription>()
        var bot: Pair<List<Hedef>, () -> Unit>? = null
        synchronized(padKilit) {
            if (padUcusta || !running) {
                if (!running) { padBotSirasi.forEach { it.second() }; padBotSirasi.clear() }
                return
            }
            val camAdim = screenW * 0.0075f
            val camSinir = screenW * 0.10f
            for (p in padlar) {
                val tb = padTaban(p)
                val onceki = p.stroke
                try {
                    if (p.down) {
                        var kes = false
                        if (p.tur == 1 && onceki != null) {
                            p.ofs += padItme(p)[0] * camAdim            // ne kadar itersen o kadar hizli doner
                            if (Math.abs(p.ofs) >= camSinir) kes = true  // sinira ulasti: kaldir, tabandan tekrar basla
                        }
                        val h2 = padHedef(p)
                        var tx = h2[0]; var ty = h2[1]
                        val yol = Path()
                        if (onceki == null) {
                            yol.moveTo(tb[0], tb[1])
                            if (Math.abs(tx - tb[0]) < 0.6f && Math.abs(ty - tb[1]) < 0.6f) tx += 0.7f
                            yol.lineTo(tx, ty)
                            p.stroke = GestureDescription.StrokeDescription(yol, 0, PAD_SEG, true)
                            p.lx = tx; p.ly = ty
                            strokes.add(p.stroke!!)
                        } else {
                            yol.moveTo(p.lx, p.ly)
                            if (Math.abs(tx - p.lx) < 0.6f && Math.abs(ty - p.ly) < 0.6f) tx = p.lx + 0.7f
                            yol.lineTo(tx, ty)
                            if (kes) {
                                strokes.add(onceki.continueStroke(yol, 0, PAD_SEG, false))
                                p.stroke = null; p.ofs = 0f
                            } else {
                                p.stroke = onceki.continueStroke(yol, 0, PAD_SEG, true)
                                p.lx = tx; p.ly = ty
                                strokes.add(p.stroke!!)
                            }
                        }
                    } else if (p.birak && onceki != null) {
                        val h2 = padHedef(p)
                        var tx = h2[0]; var ty = h2[1]
                        val yol = Path()
                        yol.moveTo(p.lx, p.ly)
                        if (Math.abs(tx - p.lx) < 0.6f && Math.abs(ty - p.ly) < 0.6f) tx = p.lx + 0.7f
                        yol.lineTo(tx, ty)
                        strokes.add(onceki.continueStroke(yol, 0, PAD_SEG, false))
                        p.stroke = null; p.birak = false
                    }
                } catch (e: Exception) {
                    p.stroke = null; p.birak = false
                    hataKaydet("pad-jest", e)
                }
            }
            // Bazi telefonlarda joystick + tus ayni jestte gercek coklu dokunus gibi islenmiyor ve yuruyus bozuluyor.
            // "Yururken skill durur" aciksa joystick'e dokunulduğu surece bot tuslari bekler, birakinca devam eder.
            val joyMesgul = cfg.padSkillDurur && padlar.any { it.down || it.stroke != null }
            if (padBotSirasi.isNotEmpty() && !joyMesgul) {
                bot = padBotSirasi.removeAt(0)
                val liste = bot!!.first
                liste.forEachIndexed { i, t ->
                    strokes.add(stroke(t.x, t.y, t.r, if (i == 0) 0L else rand(0, 30), rand(50, 75)))
                }
            }
            if (strokes.isEmpty()) return
            while (strokes.size > 9) strokes.removeAt(strokes.size - 1)
            padUcusta = true
        }
        val botDone = bot?.second
        val g = try {
            val b = GestureDescription.Builder()
            strokes.forEach { b.addStroke(it) }
            b.build()
        } catch (e: Exception) { null }
        fun bitti(basarili: Boolean) {
            synchronized(padKilit) {
                padUcusta = false
                if (basarili) padHata = 0 else {
                    padHata++
                    for (p in padlar) p.stroke = null   // zincir koptu: parmak hala joystick'te ise yeniden baslar
                }
            }
            botDone?.invoke()
            if (padHata >= 5) {
                toast("Joystick'ler çalışmadı, kapatıldı. Telefon çoklu dokunuşu desteklemiyor olabilir")
                padKaldir(); return
            }
            if (basarili) padPompa() else h.postDelayed({ padPompa() }, 40)
        }
        val ok = g != null && dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = bitti(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = bitti(false)
        }, h)
        if (!ok) bitti(false)
    }

    private fun tapSeq(list: List<Hedef>, i: Int, done: () -> Unit) {
        if (i >= list.size) {
            done(); return
        }
        tap(list[i].x, list[i].y, list[i].r) {
            if (i + 1 >= list.size) done()
            else h.postDelayed({ tapSeq(list, i + 1, done) }, rand(35, 80))
        }
    }

    private fun tap(x: Float, y: Float, radius: Float, done: () -> Unit) {
        val r = radius.coerceAtLeast(0f)
        val maxX = (screenW - 2).toFloat().coerceAtLeast(2f)
        val maxY = (screenH - 2).toFloat().coerceAtLeast(2f)
        val dx = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val dy = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val sx = (x + dx).coerceIn(1f, maxX)
        val sy = (y + dy).coerceIn(1f, maxY)
        val ex = (sx + rnd.nextInt(7) - 3).coerceIn(1f, maxX)
        val ey = (sy + rnd.nextInt(7) - 3).coerceIn(1f, maxY)

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val g = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, basmaSuresi()))
                .build()
        } catch (e: Exception) {
            h.postDelayed({ done() }, 300); return
        }
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done()
            }
        }, h)
        if (!ok) h.postDelayed({ done() }, 300)
    }
}
