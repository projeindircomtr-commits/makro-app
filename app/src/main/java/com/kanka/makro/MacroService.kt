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
import android.os.Environment
import android.provider.MediaStore
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
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
    private var cfg = Config()

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
    private var olcek: Preset.Olcek? = null
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

    // Bar dolulugu ve mob kilidi (otomatik tanimadan gelir)
    private var hpBar = IntArray(3)
    private var mpBar = IntArray(3)
    private var isimRect = IntArray(4)
    private var kilitKotu = 0
    private var kilitUyarildi = false
    @Volatile private var kilitBekleUntil = 0L   // kilitli mob yok: bu ana kadar hicbir sey yapma

    // Guvenlik: oyun onde mi, ekran acik mi, goruntu taze mi
    @Volatile private var sonPaket = ""      // en son one gelen uygulama
    @Volatile private var oyunPaketi = ""    // ▶'a basildiginda ondeki uygulama
    @Volatile private var bekleNeden = ""    // panelde gosterilecek bekleme sebebi

    // Banka turu
    private var innT: Sablon? = null
    private var bankaT: Sablon? = null
    @Volatile private var bankaIstendi = false
    private var bankaSonraki = 0L   // basarisiz denemeden sonra tekrar deneme zamani
    // "Inn hostess" yazisinin sekli (izgara cozunurlugunde, renk maskesi)
    private var etiketMaske: BooleanArray? = null
    private var etiketW = 0
    private var etiketH = 0
    @Volatile private var bankaCalisiyor = false

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
        Lisans.yukle(this)
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
        sonPaket = p
    }

    /** Bu paketler one gelse de "oyundan cikildi" sayilmaz */
    private fun gormezdenGel(p: String): Boolean =
        p == packageName || p == "com.android.systemui" || p == "android" ||
            p.contains("inputmethod") || p.contains("keyboard") || p.contains(".ime") ||
            p.contains("permissioncontroller") || p.contains("securitycenter")

    override fun onInterrupt() {
        stopMacro()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    override fun onDestroy() {
        instance = null
        kartKapat()
        closeEditor()
        stopMacro()
        removeOverlay()
        panel?.let { safeRemove(it) }
        panel = null
        worker.quitSafely()
        ah.removeCallbacksAndMessages(null)
        agThread.quitSafely()
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

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun btn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(9), dp(11), dp(9))
        setOnClickListener { onClick() }
    }

    private fun menuBtn(text: String, onClick: () -> Unit) =
        btn(text, onClick).apply { background = rounded(0xFF3A3A3A.toInt()) }

    // ================= Yuzen panel =================

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panel != null) return
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = dp(8); y = dp(60) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xCC202020.toInt())
        }

        val drag = btn("⠿") {}
        drag.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = sx + (e.rawX - tx).toInt()
                        params.y = sy + (e.rawY - ty).toInt()
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (ex: Exception) {
                        }
                    }
                }
                return true
            }
        })

        val play = btn("▶") { toggle() }
        playBtn = play
        val st = TextView(this).apply {
            text = "Hazır"
            setTextColor(0xFFB0FFB0.toInt())
            textSize = 12f
            setPadding(dp(6), 0, dp(10), 0)
        }
        statusTv = st

        root.addView(drag)
        root.addView(play)
        root.addView(btn("⋯") { showMainMenu() })
        root.addView(st)

        try {
            wm.addView(root, params)
            panel = root
        } catch (e: Exception) {
            toast("Panel açılamadı: ${e.message}")
        }
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
            background = rounded(0xEE202020.toInt())
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

    private fun showMainMenu() {
        if (running) {
            toast("Menü için önce makroyu durdur (⏸)"); return
        }
        if (!Lisans.gecerliSimdi()) {
            toast("Önce uygulamadan üye girişi yap")
            openApp()
            return
        }
        showMenu(
            "Menü",
            listOf(
                "⚙ Ayarlar" to { ayarKarti() },
                "🏦 Banka" to { bankaKarti() },
                "🛠 Tuşları düzenle" to { openEditor() },
                "🎨 Bar kaydet (HP/MP/hedef)" to { showBarChooser() },
                "📦 Kutu butonu kaydet" to { showLootChooser() },
                "🧪 Ekran testi" to { ekranTesti() },
                "🎯 Seçili mobu kilitle" to { mobuKilitle() },
                "🔓 Mob kilitlerini kaldır" to {
                    val c = Config.load(this)
                    c.kilitler.clear()
                    c.save(this)
                    toast("Kilitler kaldırıldı, her moba vurulacak")
                },
                "⚙ Uygulamayı aç" to {
                    try {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                }
            )
        )
    }

    // ================= Oyun icinde ayarlar (uygulamaya gecmeden) =================

    private fun ayarKarti() {
        removeOverlay()
        val c = Config.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01E2A3A.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "⚙ Ayarlar"
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

        satir("❤ HP potu", { "%" + Config.load(this).hpYuzde },
            { kaydet { it.hpYuzde = (it.hpYuzde - 5).coerceIn(10, 95) } },
            { kaydet { it.hpYuzde = (it.hpYuzde + 5).coerceIn(10, 95) } })
        satir("💧 MP potu", { "%" + Config.load(this).mpYuzde },
            { kaydet { it.mpYuzde = (it.mpYuzde - 5).coerceIn(5, 95) } },
            { kaydet { it.mpYuzde = (it.mpYuzde + 5).coerceIn(5, 95) } })
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

    // ================= Banka turu =================
    // Town -> joystick ile Inn hostess'e yuru (Open gorunce dur) -> Open -> Inn Hostes Open
    // -> envanterin ust 3 sirasina cift dokun (otomatik bankaya gider) -> X -> Town -> kasmaya devam

    private fun uyu(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    private fun anlikDokun(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y); lineTo(x + 1, y + 1) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, rand(50, 80))).build(), null, null)
        } catch (e: Exception) {
        }
    }

    /** Iki kez hizli dokunus (esyayi bankaya atar) */
    private fun ciftDokun(x: Float, y: Float) {
        val a = Path().apply { moveTo(x, y); lineTo(x + 1, y) }
        val b = Path().apply { moveTo(x + 1, y + 1); lineTo(x, y + 1) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(a, 0, 45))
                .addStroke(GestureDescription.StrokeDescription(b, rand(120, 150), 45))
                .build(), null, null)
        } catch (e: Exception) {
        }
    }

    private fun joyMerkez(): FloatArray = floatArrayOf(screenW * 0.194f, screenH * 0.625f)

    /**
     * Joystick'i yon kadar itip sure boyunca basili tutar. Parmak hedefe ~0.1 sn'de varir,
     * kalan surede orada kucuk titremelerle bekler (yonu degismez).
     */
    private fun joystick(yon: Int, sureMs: Long) = joystickAci(Math.toRadians(yon * 45.0), sureMs)

    /** Joystick'i verilen aciyla (0 = ileri/yukari, saat yonunde) iter */
    private fun joystickAci(a: Double, sureMs: Long) {
        val m = joyMerkez()
        val r = 110f * (olcek?.s ?: 1f)
        val ex = (m[0] + r * Math.sin(a)).toFloat()
        val ey = (m[1] - r * Math.cos(a)).toFloat()
        val sure = sureMs.coerceIn(200L, 59_000L)
        val p = Path().apply {
            moveTo(m[0], m[1])
            lineTo(ex, ey)
            // Kalan sure icin titreme: toplam yol, ilk hareket ~0.1 sn surecek kadar uzun
            val titreme = (r * (sure / 100f - 1f)).coerceAtLeast(0f)
            val adim = 4f
            var yol = 0f
            var k = 0
            while (yol < titreme && k < 40_000) {
                if (k % 2 == 0) lineTo(ex + adim, ey) else lineTo(ex, ey)
                yol += adim
                k++
            }
        }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, sure)).build(), null, null)
        } catch (e: Exception) {
        }
    }

    private fun bul(t: Sablon?): IntArray? {
        if (t == null) return null
        val f = ScreenSampler.grab() ?: return null
        return findT(f, t)
    }

    private fun bekleBul(t: Sablon?, ms: Long): IntArray? {
        val son = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < son) {
            bul(t)?.let { return it }
            uyu(150)
        }
        return null
    }

    private fun sablonMerkez(t: Sablon, pos: IntArray) = floatArrayOf(
        ScreenSampler.toScreen(pos[0] + t.w / 2f), ScreenSampler.toScreen(pos[1] + t.h / 2f)
    )

    /** Town'a bas ve oyun ekrani yeniden gorunene kadar bekle */
    private fun townaGit(o: Preset.Olcek): Boolean {
        val t = Preset.altOrta(o, 1663, 1176)
        anlikDokun(t[0].toFloat(), t[1].toFloat())
        uyu(3000)
        val son = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < son) {
            if (Preset.olcekBul(this) != null) {
                uyu(800)
                return true
            }
            uyu(700)
        }
        return false
    }

    // ---------- "Inn hostess" yazisini gorerek yurume ----------

    private val ETIKET_RENK = (150 shl 16) or (207 shl 8) or 227   // acik mavi isim

    private fun etiketHazirla(s: Float) {
        try {
            val b = assets.open("inn_etiket.png").use { android.graphics.BitmapFactory.decodeStream(it) } ?: return
            // Yari cozunurluge olcekle, renk maskesi cikar, 2x2 -> izgara (herhangi biri)
            val hw = (b.width * s * ScreenSampler.SCALE).toInt().coerceAtLeast(4)
            val hh = (b.height * s * ScreenSampler.SCALE).toInt().coerceAtLeast(4)
            val sc = Bitmap.createScaledBitmap(b, hw, hh, true)
            val px = IntArray(hw * hh)
            sc.getPixels(px, 0, hw, 0, 0, hw, hh)
            val gw = hw / 2
            val gh = hh / 2
            val m = BooleanArray(gw * gh)
            for (y in 0 until gh) for (x in 0 until gw) {
                var v = false
                for (dy in 0..1) for (dx in 0..1) {
                    if (ScreenSampler.diff(px[(y * 2 + dy) * hw + x * 2 + dx] and 0xFFFFFF, ETIKET_RENK) < 110) v = true
                }
                m[y * gw + x] = v
            }
            etiketMaske = m
            etiketW = gw
            etiketH = gh
        } catch (e: Exception) {
        }
    }

    /**
     * Ekranin ust %70'inde "Inn hostess" yazisini arar (harf sekli + renk).
     * Oyuncu isimleri de acik mavi oldugu icin sekle bakilir. Bulursa ekran merkezi.
     */
    private fun etiketBul(): FloatArray? {
        val t = etiketMaske ?: return null
        val r = ScreenSampler.bolge(0, 0, screenW - 1, (screenH * 0.7f).toInt()) ?: return null
        val (hw, hh, px) = r
        val gw = hw / 2
        val gh = hh / 2
        // Izgara maskesi + integral (pencere toplami icin)
        val g = BooleanArray(gw * gh)
        val ig = IntArray((gw + 1) * (gh + 1))
        for (y in 0 until gh) {
            var satir = 0
            for (x in 0 until gw) {
                var v = false
                for (dy in 0..1) for (dx in 0..1) {
                    if (ScreenSampler.diff(px[(y * 2 + dy) * hw + x * 2 + dx] and 0xFFFFFF, ETIKET_RENK) < 110) v = true
                }
                g[y * gw + x] = v
                if (v) satir++
                ig[(y + 1) * (gw + 1) + x + 1] = ig[y * (gw + 1) + x + 1] + satir
            }
        }
        val ty = ArrayList<Int>(); val tx = ArrayList<Int>()
        for (y in 0 until etiketH) for (x in 0 until etiketW) if (t[y * etiketW + x]) { ty.add(y); tx.add(x) }
        val n = ty.size
        if (n < 20) return null
        var best = 0f
        var bx = -1
        var by = -1
        for (y in 0..(gh - etiketH)) {
            for (x in (gw * 5 / 100)..(gw * 95 / 100 - etiketW)) {
                val top = ig[(y + etiketH) * (gw + 1) + x + etiketW] - ig[y * (gw + 1) + x + etiketW] -
                    ig[(y + etiketH) * (gw + 1) + x] + ig[y * (gw + 1) + x]
                if (top < n / 2) continue   // hizli eleme: yeterince mavi piksel yok
                var hit = 0
                for (k in 0 until n) if (g[(y + ty[k]) * gw + x + tx[k]]) hit++
                val skor = (hit - 0.5f * (top - hit)) / n
                if (skor > best) { best = skor; bx = x; by = y }
            }
        }
        if (best < 0.68f || bx < 0) return null
        return floatArrayOf(
            ScreenSampler.toScreen(bx + etiketW / 2f), ScreenSampler.toScreen(by + etiketH / 2f)
        )
    }

    /**
     * Insan gibi yurur: yazi gorunmuyorsa tarif edilen yonde kisa adimlarla ilerler ve bakinir;
     * "Inn hostess" yazisini gorunce ona dogru yonelir, her adimda yonunu duzeltir.
     * Open cikinca durur.
     */
    private fun npcyeYuru(): IntArray? {
        val rota = if (cfg.rota.isEmpty()) listOf(RotaAdim(0, 25f)) else cfg.rota
        val rotaMs = rota.sumOf { (it.sure * 1000).toLong() }
        val son = SystemClock.uptimeMillis() + rotaMs + 20_000
        var rotaIdx = 0
        var adimKalan = (rota[0].sure * 1000).toLong()
        val karX = screenW / 2f
        val karY = screenH * 0.5f
        val BURST = 650L
        while (SystemClock.uptimeMillis() < son) {
            if (!running && !bankaCalisiyor) return null
            bul(cfg.openT)?.let { pos ->
                val m = joyMerkez()
                anlikDokun(m[0], m[1])   // dur
                uyu(400)
                return bul(cfg.openT) ?: pos
            }
            val e = etiketBul()
            if (e != null) {
                // Yaziya dogru: ekranda karakterden yaziya olan yon
                bekleNeden = "🏦👀"
                val aci = Math.atan2((e[0] - karX).toDouble(), (karY - e[1]).toDouble())
                joystickAci(aci, BURST)
            } else {
                bekleNeden = "🏦"
                val a = if (rotaIdx < rota.size) rota[rotaIdx] else RotaAdim(0, 1f)
                joystick(a.yon, BURST)
                if (rotaIdx < rota.size) {
                    adimKalan -= BURST
                    if (adimKalan <= 0) {
                        rotaIdx++
                        if (rotaIdx < rota.size) adimKalan = (rota[rotaIdx].sure * 1000).toLong()
                    }
                }
            }
            // Adim suresince Open'a da bak
            val adimSon = SystemClock.uptimeMillis() + BURST - 50
            while (SystemClock.uptimeMillis() < adimSon) {
                uyu(200)
                if (bul(cfg.openT) != null) break
            }
        }
        return null
    }

    /** tam=false: sadece Town + yuruyus testi */
    private fun bankaRutini(tam: Boolean): String? {
        val o = olcek ?: return "Ekran tanınmadı"
        if (etiketMaske == null) etiketHazirla(o.s)
        if (!townaGit(o)) return "Town sonrası oyun ekranı gelmedi"

        val openPos = npcyeYuru() ?: return "Inn hostess'e ulaşılamadı (Open çıkmadı). Yolu kontrol et"
        val op = cfg.openT!!
        val oc = sablonMerkez(op, openPos)
        if (!tam) return null   // yuruyus testi basarili

        anlikDokun(oc[0], oc[1])
        val innPos = bekleBul(innT, 4000) ?: return "NPC menüsü açılmadı"
        val ic = sablonMerkez(innT!!, innPos)
        anlikDokun(ic[0], ic[1])
        val bPos = bekleBul(bankaT, 5000) ?: return "Banka penceresi açılmadı"
        val bc = sablonMerkez(bankaT!!, bPos)
        val s = o.s

        // Envanterin ust 3 sirasi (en alt sira dokunulmaz): cift dokunus = bankaya
        for (r in 0 until 3) {
            for (c in 0 until 7) {
                ciftDokun(bc[0] + (-322.5f + c * 105.2f) * s, bc[1] + (716.5f + r * 102f) * s)
                uyu(rand(260, 340))
            }
        }
        uyu(500)
        anlikDokun(bc[0] + 319.5f * s, bc[1] - 4.5f * s)   // X ile kapat
        uyu(900)

        if (!townaGit(o)) return "Dönüşte Town sonrası ekran gelmedi"
        return null
    }

    /** Banka turunu calistirir (worker thread'de, bloklayarak) */
    private fun bankaTuru(tam: Boolean) {
        bankaCalisiyor = true
        bekleNeden = "🏦"
        toast(if (tam) "🏦 Envanter dolu: bankaya gidiliyor" else "🏦 Yürüyüş testi")
        val hata = try {
            bankaRutini(tam)
        } catch (e: Exception) {
            hataKaydet("banka", e)
            "Beklenmedik hata"
        }
        bankaCalisiyor = false
        bekleNeden = ""
        // Kasma durumlarini sifirla
        lootPhase = Loot.BOS
        collectStreak = 0
        doluKutuAt = 0L
        nextTarget = 0L
        kilitBekleUntil = 0L
        oncekiCanli = false
        if (hata == null) {
            bankaSonraki = 0L
            toast(if (tam) "🏦 Banka tamam, kasmaya devam" else "✓ Test başarılı")
        } else {
            // Takilip kalma: pencereleri kapat, Town ile mob alanina don, kasmaya devam et
            toast("🏦 Banka olmadı: $hata. Kasmaya devam, 15 dk sonra tekrar denenecek")
            bankaSonraki = SystemClock.uptimeMillis() + 15 * 60_000L
            try {
                bul(bankaT)?.let { bp ->
                    val bc = sablonMerkez(bankaT!!, bp)
                    val s0 = olcek?.s ?: 1f
                    anlikDokun(bc[0] + 319.5f * s0, bc[1] - 4.5f * s0)
                    uyu(600)
                }
                olcek?.let { if (tam && running) townaGit(it) }
            } catch (e: Exception) {
            }
        }
    }

    /** Makro dururken test: once ekrani tani, sonra turu calistir */
    private fun bankaTesti(tam: Boolean) {
        if (running) {
            toast("Önce makroyu durdur"); return
        }
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı. Önce ▶ ile bir kere başlat"); return
        }
        h.post {
            cfg = Config.load(this)
            otoMod = true
            val o = olcek ?: try { Preset.olcekBul(this) } catch (e: Exception) { null }
            if (o == null) {
                toast("Oyun ekranı tanınamadı"); return@post
            }
            if (olcek == null) olcekUygula(o)
            bankaTuru(tam)
        }
    }

    private fun bankaKarti() {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01E2A3A.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "🏦 Banka"
            setTextColor(0xFFE0B04A.toInt()); textSize = 16f
        })
        box.addView(TextView(this).apply {
            text = "Envanter dolunca makro kendiliğinden bankaya gider: Town → Inn hostess → " +
                "üst 3 sıra bankaya → Town → kasmaya devam. Ayar gerekmez."
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })
        box.addView(btn("🏦 Şimdi test et") { removeOverlay(); bankaTesti(true) }
            .apply { background = rounded(0xFF8A6D1F.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        box.addView(btn("Kapat ✓") { removeOverlay() }.apply { background = rounded(0xFF2E9E5B.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ortadaGoster(box, dp(420))
    }

    // ================= Oyun icinde tus duzenleme =================

    private fun closeEditor() {
        editor?.let { safeRemove(it) }
        editor = null
    }

    private fun etiket(p: Nokta): String = when (p.type) {
        "saldiri" -> "⚔"
        "hedef" -> "🎯"
        "hp_pot" -> "HP"
        "mp_pot" -> "MP"
        else -> {
            val no = p.name.removePrefix("Skill ").trim()
            val sure = if (p.cd <= 0f) "∞" else (if (p.cd == p.cd.toLong().toFloat()) "${p.cd.toLong()}s" else "${p.cd}s")
            "S$no\n$sure"
        }
    }

    private fun renk(p: Nokta): Int = when {
        p.type == "saldiri" -> 0xE0C0392B.toInt()
        p.type == "hedef" -> 0xE0D35400.toInt()
        p.type == "hp_pot" -> 0xE0B03030.toInt()
        p.type == "mp_pot" -> 0xE02E5BBA.toInt()
        !p.on -> 0xC0555555.toInt()
        else -> 0xE0208A4E.toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openEditor() {
        if (running) {
            toast("Önce makroyu durdur"); return
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
                setOnClickListener { if (overlay != null) removeOverlay() else editorPointMenu(i) }
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
            text = "Boş slota dokun: ekle  •  Etikete dokun: değiştir"
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

    private fun turListesi(onPick: (String) -> Unit): List<Pair<String, () -> Unit>> = listOf(
        "✨ Skill" to { onPick("skill") },
        "❤ HP pot" to { onPick("hp_pot") },
        "💧 MP pot" to { onPick("mp_pot") },
        "🎯 Mob seç" to { onPick("hedef") }
    )

    private fun cdMenu(onPick: (Float) -> Unit) {
        showMenu(
            "Skill ne sıklıkla basılsın?",
            listOf(
                "∞ Sürekli (beklemeden)" to { onPick(0f) },
                "1 sn" to { onPick(1f) },
                "2 sn" to { onPick(2f) },
                "3 sn" to { onPick(3f) },
                "5 sn" to { onPick(5f) },
                "10 sn" to { onPick(10f) },
                "30 sn" to { onPick(30f) },
                "60 sn" to { onPick(60f) }
            )
        )
    }

    /** Bos yere dokunuldu: yeni tus ekle */
    private fun editorAdd(x: Int, y: Int) {
        showMenu("Bu slot ne olsun?", turListesi { type ->
            if (type == "skill") {
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
        if (p.type == "skill") {
            items.add("⏱ Bekleme süresi" to {
                cdMenu { cd ->
                    val c = Config.load(this)
                    if (i < c.points.size) {
                        c.points[i].cd = cd
                    c.save(this)
                    }
                    editorRefresh()
                }
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
            val kayit = if (bmp != null) pngKaydet(bmp, "pedal_test_${System.currentTimeMillis() / 1000}.png") else false
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
            ui.post { if (running) stopMacro("Üyelik aktif değil. Makro durdu") }
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
            bildirim(o.optInt("id"), "📢 Projeindirpedal • $saat", metin)
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
        if (kart == null) siradakiMesaj()
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
            background = rounded(0xF01E2A3A.toInt())
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
        try {
            val iz = e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            val metin = "$yer: ${e.javaClass.simpleName}: ${e.message ?: ""} @ $iz".take(480)
            getSharedPreferences("hata", MODE_PRIVATE).edit()
                .putString("son", metin).putLong("zaman", System.currentTimeMillis() / 1000)
                .putBoolean("gonderildi", false).commit()
        } catch (x: Exception) {
        }
    }

    /** Dokunmak guvenli mi? Degilse sebebini dondurur */
    private fun dokunmaEngeli(now: Long): String? {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return "🌙"
        if (oyunPaketi.isNotEmpty() && sonPaket.isNotEmpty() && sonPaket != oyunPaketi) return "⏸"
        if (ScreenSampler.running && now - ScreenSampler.lastFrameAt > 3000) return "📷"
        return null
    }

    private fun showBarChooser() {
        if (running) {
            toast("Önce makroyu durdur"); return
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
            toast("Önce makroyu durdur"); return
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
            listOf("hedef", "skill", "hp_pot", "mp_pot").map { t ->
                Config.label(t) to { savePoint(t, x, y) }
            }
        )
    }

    // ================= Kayit =================

    @SuppressLint("ClickableViewAccessibility")
    private fun startRecord(mode: Mod) {
        if (running) {
            toast("Önce makroyu durdur"); return
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

    private fun toggle() {
        when {
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
                    toast("Ekran izni verilmedi, makro başlamadı")
                    return
                }
                ui.postDelayed(this, 400)
            }
        }, 400)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val left = ((endAt - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0)
            // Uyelik: 10 dakikada bir sunucudan tekrar kontrol
            val simdi = SystemClock.uptimeMillis()
            if (!Lisans.gecerliSimdi()) {
                stopMacro("Üyelik doğrulanamadı ya da süresi doldu. Makro durdu")
                return
            }
            if (!lisansKontrolde && simdi - sonLisansKontrol > 10 * 60 * 1000L) {
                sonLisansKontrol = simdi
                lisansKontrolde = true
                Lisans.arkaPlanKontrol(this@MacroService) { r ->
                    lisansKontrolde = false
                    // Internet gecici koptuysa hemen durdurma; sadece sunucu "hayir" derse dur
                    if (!r.ok && !r.ag && running) stopMacro("Üyelik: ${r.mesaj}. Makro durdu")
                }
            }
            val ne = when {
                bekleNeden.isNotEmpty() -> bekleNeden
                otoMod && olcek == null -> "🔍"
                simdi < kilitBekleUntil -> "💤"
                lootPhase != Loot.BOS -> "📦"
                else -> "⚔"
            }
            statusTv?.text = "$ne %d:%02d".format(left / 60, left % 60) +
                "  🗡$kesilen 📦$toplanan 🧪$basilanPot"
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
        // Hic ayar yoksa MykoMobile hazir ayarini otomatik yukle
        if (cfg.points.isEmpty()) {
            Preset.apply(this)
            cfg = Config.load(this)
            toast("MykoMobile ayarları otomatik yüklendi")
        }
        val wantsScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (wantsScreen && !ScreenSampler.running) {
            requestCaptureThenStart()
            return
        }
        if (cfg.points.none { it.type == "saldiri" || it.type == "hedef" || (it.type == "skill" && it.on) }) {
            toast("Önce + ile saldırı / mob seç / skill tuşu kaydet"); return
        }
        val needScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (needScreen && !ScreenSampler.running) {
            toast("Uyarı: ekran okuma kapalı. HP/MP, hedef barı ve kutu çalışmayacak")
        }
        updateScreenSize()
        tgtStrip = Preset.tgtStrip(this, cfg.tgtBar)
        otoMod = cfg.otoArayuz
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
        kilitBekleUntil = 0L
        hpBar = IntArray(3)
        mpBar = IntArray(3)
        dcT = if (cfg.otoArayuz) null
        else Preset.loadTemplateF(this, "disconnect.png", Preset.landscapeSize(this).first / 2712f, 20, 0.5f, 0.5f)
        hpSol = intArrayOf(-1, -1)
        taramaHata = 0
        h.removeCallbacksAndMessages(null)
        h.post {
            val now = SystemClock.uptimeMillis()
            endAt = now + cfg.minutes * 60_000L
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
        oyunPaketi = sonPaket   // su an ondeki uygulama = oyun
        bekleNeden = ""
        // Envanter doldu: banka turu (bu sirada kasma durur)
        if (bankaIstendi && !bankaCalisiyor) {
            bankaIstendi = false
            bankaTuru(true)
            if (running) h.postDelayed(stepR, 300)
            return
        }
        sonLisansKontrol = SystemClock.uptimeMillis()
        playBtn?.text = "⏸"
        h.postDelayed(stepR, 700)
        h.postDelayed(kutuGozcu, 900)
        ui.removeCallbacks(statusTick)
        ui.post(statusTick)
    }

    private fun stopMacro(reason: String? = null) {
        running = false
        h.removeCallbacksAndMessages(null)
        ui.removeCallbacks(statusTick)
        ui.post {
            playBtn?.text = "▶"
            statusTv?.text = "Durdu"
        }
        if (reason != null) {
            toast(reason)
            vibrate()
        }
    }

    /** worker thread'inde calisir; her adimda tek dokunus, bitince sonraki planlanir */
    private fun step() {
        if (!running) return
        try {
            stepIc()
        } catch (e: Exception) {
            // Makro olmesin: hatayi kaydet, 1 sn sonra devam et
            hataKaydet("adım", e)
            tapping = false
            if (running) h.postDelayed(stepR, 1000)
        }
    }

    private fun stepIc() {
        if (!running) return
        // Oyun arkadaysa / ekran kapaliysa / goruntu yoksa DOKUNMA
        val engel = dokunmaEngeli(SystemClock.uptimeMillis())
        if (engel != null) {
            bekleNeden = engel
            h.postDelayed(stepR, 500)
            return
        }
        bekleNeden = ""
        val now = SystemClock.uptimeMillis()
        if (now >= endAt) {
            stopMacro("Süre bitti, makro durdu"); return
        }
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
                    // Takilip kalma: eski (genislige gore) ayarla devam et
                    olcekUygula(Preset.varsayilanOlcek(this), yedek = true)
                } else if (cfg.tuslarOto) {
                    // Hazir tuslar bu ekranda yanlis olabilir: oyun gorunene kadar dokunma
                    h.postDelayed(stepR, 1500)
                    return
                }
            } else if (cfg.tuslarOto) {
                h.postDelayed(stepR, 300)
                return
            }
        }
        if (korumaKontrol(now)) return
        if (safetyStop(now)) return

        // Potlar beklemeden, hemen ardindan skill/saldiri/kutu. Oyun ayni anda gelen
        // coklu dokunuslari yok saydigi icin arka arkaya (cok kisa aralikla) basilir.
        val r = cfg.radius.toFloat()
        val pots = potActions(now).map { Hedef(it.x.toFloat(), it.y.toFloat(), r) }
        val p = pickTarget(now)
        val list = pots + listOfNotNull(p)
        if (list.isEmpty()) {
            // Kutu toplarken cok sik kontrol et, normalde biraz bekle
            h.postDelayed(stepR, if (lootPhase != Loot.BOS) 60L else 200L)
            return
        }
        tapping = true
        tapSeq(list, 0) {
            tapping = false
            val hizli = p?.fast == true || lootPhase != Loot.BOS
            if (running) h.postDelayed(stepR, if (hizli) rand(40, 90) else nextDelay())
        }
    }

    /**
     * Kutu gozcusu: saldiridan bagimsiz, 0.12 sn'de bir ekrana bakar. Open/Collect All
     * gorunce bekleyen saldiri adimini iptal edip hemen basar.
     */
    private val kutuGozcu = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                gozcuIc()
            } catch (e: Exception) {
                hataKaydet("kutu", e)
            }
            h.postDelayed(this, 120)
        }

        private fun gozcuIc() {
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
                        if (running) h.postDelayed(stepR, rand(40, 90))
                    }
                }
            }
        }
    }

    private fun isLow(cp: RenkNokta): Boolean {
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
                    stopMacro("Bağlantı koptu (Disconnect). Makro durdu")
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
                    stopMacro("Karakter ölmüş görünüyor. Makro durdu")
                    return true
                }
            } else {
                hpBosSince = 0L
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
                stopMacro("HP ${cfg.hpStop} sn boyunca düşük kaldı: pot bitmiş ya da ölmüş olabilirsin. Makro durdu")
                return true
            }
        } else {
            hpLowSince = 0L
        }
        return false
    }

    private fun pickTarget(now: Long): Hedef? {
        val r = cfg.radius.toFloat()
        // 1) Kutu (Open / Collect All) - potlar ayrica ayni anda basilir
        lootAction(now)?.let { return it }
        // Kutu toplarken de saldiri/skill devam eder; kutu butonu gorununce araya girer
        // 3) Hedef / skill / saldiri
        val p = pick(now) ?: return null
        return Hedef(p.x.toFloat(), p.y.toFloat(), r)
    }

    /** Gereken potlar (HP, MP ya da ikisi) - beklemeden, saldiriyla birlikte basilir */
    private fun potActions(now: Long): List<Nokta> {
        val out = ArrayList<Nokta>(2)
        val hp = cfg.hp
        if (hp != null && now - lastHpPot > cfg.potCd && isLow(hp)) {
            cfg.points.firstOrNull { it.type == "hp_pot" }?.let {
                basilanPot++
                lastHpPot = now
                out.add(it)
            }
        }
        val mp = cfg.mp
        if (mp != null && now - lastMpPot > cfg.potCd && isLow(mp)) {
            cfg.points.firstOrNull { it.type == "mp_pot" }?.let {
                basilanPot++
                lastMpPot = now
                out.add(it)
            }
        }
        return out
    }

    /** Bulunan olcege gore barlari, hedef barini, kutulari ve tuslari yerlestir (sadece bellekte) */
    private fun olcekUygula(o: Preset.Olcek, yedek: Boolean = false) {
        olcek = o
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
        innT = Preset.loadTemplateF(this, "inn_open.png", o.s, 20, 0.5f, 0.5f)
        bankaT = Preset.loadTemplateF(this, "banka_baslik.png", o.s, 17, 0.5f, 0.5f)
        hpSol = Preset.solUst(o, 72, 26)
        val h1 = Preset.solUst(o, 62, 26)
        val h2 = Preset.solUst(o, 405, 26)
        hpBar = intArrayOf(h1[0], h2[0], h1[1])
        val m1 = Preset.solUst(o, 62, 70)
        val m2 = Preset.solUst(o, 407, 70)
        mpBar = intArrayOf(m1[0], m2[0], m1[1])
        isimRect = isimAlani(o)
        if (cfg.tuslarOto) {
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

    // ---------- Mob kilidi (isim sekli) ----------

    private fun isimAlani(o: Preset.Olcek): IntArray {
        val a = Preset.ustOrta(o, 1100, 0)
        val b = Preset.ustOrta(o, 1640, 44)
        return intArrayOf(a[0], a[1], b[0], b[1])
    }

    private fun renkFark(a: Int, b: Int) = ScreenSampler.diff(a, b)

    /** Bolgedeki isim renginde olan pikseller */
    private fun isimMaske(px: IntArray, renk: Int): BooleanArray =
        BooleanArray(px.size) { renkFark(px[it] and 0xFFFFFF, renk) < 90 }

    /** Hedefin ismi kilitli moblardan biri mi? */
    private fun isimUygun(): Boolean {
        val r = ScreenSampler.bolge(isimRect[0], isimRect[1], isimRect[2], isimRect[3]) ?: return true
        val (w, h, px) = r
        var olcuUyan = false
        for (k in cfg.kilitler) {
            if (k.w != w || k.h != h) continue
            olcuUyan = true
            val m = isimMaske(px, k.renk)
            var best = 0f
            for (dx in -3..3) {
                var kesisim = 0
                var birlesim = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val a = k.bits[y * w + x] == '1'
                        val xx = x - dx
                        val b = xx in 0 until w && m[y * w + xx]
                        if (a && b) kesisim++
                        if (a || b) birlesim++
                    }
                }
                if (birlesim > 0) best = maxOf(best, kesisim.toFloat() / birlesim)
            }
            if (best >= 0.65f) return true
        }
        if (!olcuUyan) {
            if (!kilitUyarildi) {
                kilitUyarildi = true
                toast("Mob kilidi başka bir ekranda yapılmış. ⋯ menüsünden yeniden kilitle")
            }
            return true
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
            val o = olcek ?: try { Preset.olcekBul(this) } catch (e: Exception) { null }
            if (o == null) {
                toast("Oyun ekranı tanınamadı"); return@post
            }
            val a = isimAlani(o)
            val r = ScreenSampler.bolge(a[0], a[1], a[2], a[3])
            if (r == null) {
                toast("Ekran görüntüsü alınamadı"); return@post
            }
            val (w, hh, px) = r
            // Ismin rengi: bolgedeki canli (doygun) piksellerin ortancasi
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (c in px) {
                val rr = (c shr 16) and 0xff
                val gg = (c shr 8) and 0xff
                val bb = c and 0xff
                val mx = maxOf(rr, gg, bb)
                val mn = minOf(rr, gg, bb)
                if (mx - mn > 80 && mx > 120) { rs.add(rr); gs.add(gg); bs.add(bb) }
            }
            if (rs.size < 20) {
                toast("İsim bulunamadı. Önce oyunda bir mob seç, ismi üstte görünsün"); return@post
            }
            rs.sort(); gs.sort(); bs.sort()
            val renk = (rs[rs.size / 2] shl 16) or (gs[gs.size / 2] shl 8) or bs[bs.size / 2]
            val m = isimMaske(px, renk)
            val bits = StringBuilder(m.size)
            for (b in m) bits.append(if (b) '1' else '0')
            val c = Config.load(this)
            c.kilitler.add(Kilit(w, hh, renk, bits.toString()))
            c.save(this)
            toast("🎯 Mob kilitlendi. Toplam ${c.kilitler.size} mob, sadece bunlara vurulacak")
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
            return false
        }
        return !isLow(bar)
    }

    private fun pick(now: Long): Nokta? {
        val pts = cfg.points
        val target = pts.firstOrNull { it.type == "hedef" }
        val bar = cfg.tgtBar

        if (target != null && bar != null && ScreenSampler.running) {
            // Akilli mod: hedef barina bak
            val alive = targetAlive(bar)
            if (oncekiCanli && !alive) kesilen++
            if (alive && !oncekiCanli) {
                // Yeni hedef: ilerleme sayacini baslat
                sonYuzde = hedefYuzde()
                ilerlemeAt = now
            }
            oncekiCanli = alive
            if (alive) {
                // Takili hedef: 12 sn boyunca cani azalmiyorsa birak, yenisini sec
                val y = hedefYuzde()
                if (y < sonYuzde - 0.02f) {
                    sonYuzde = y
                    ilerlemeAt = now
                } else if (now - ilerlemeAt > 12_000) {
                    ilerlemeAt = now
                    sonYuzde = 1f
                    val ol = olcek
                    if (ol != null) {
                        // Once hedefi iptal et (X), sonraki adimda yeni mob sec
                        val x = Preset.sagAlt(ol, 2632, 926)
                        iptalBekliyor = true
                        nextTarget = 0L
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                    nextTarget = 0L
                    return target
                }
            }
            // Mob kilidi
            val o = olcek
            if (cfg.kilitler.isNotEmpty() && o != null) {
                // Bekleme modu: etrafta kilitli mob yok, skill/saldiri/secim tamamen durur
                if (now < kilitBekleUntil) return null
                if (alive) {
                    if (isimUygun()) {
                        kilitKotu = 0
                    } else {
                        // Yanlis mob: karakter ona yurumesin diye hemen hedefi iptal et (X)
                        kilitKotu++
                        if (kilitKotu >= 6) {
                            // Ust uste 6 yanlis mob: kilitli mob kalmadi, 8 sn bekle
                            kilitBekleUntil = now + 8000
                            kilitKotu = 5   // bekleme sonrasi tek deneme; tutmazsa yine bekle
                        }
                        nextTarget = now + rand(350, 500)
                        val x = Preset.sagAlt(o, 2632, 926)
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                }
            }
            if (!alive) {
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
    /** Collect All penceresini ara: once tum pencere, olmazsa sadece buton */
    private fun findCollect(f: ScreenSampler.Frame): Pair<Sablon, IntArray>? {
        cfg.collectT?.let { t -> findT(f, t)?.let { return t to it } }
        cfg.collectT2?.let { t -> findT(f, t)?.let { return t to it } }
        return null
    }

    /** Ayni Open'a, kutu toplanmadan tekrar basma */
    private fun openBlocked(now: Long, op: Sablon, pos: IntArray): Boolean {
        // Dolu envanter yuzunden kapatilan kutu: 45 sn ayni yerdeki Open'a basma
        if (now - doluKutuAt < 45_000) {
            val d = sablonHedef(op, pos)
            if (kotlin.math.abs(d.x - doluKutuX) < 80 && kotlin.math.abs(d.y - doluKutuY) < 80) return true
        }
        if (collectedSinceOpen) return false
        if (now - lastOpenAt > 6000) return false
        val t = sablonHedef(op, pos)
        return kotlin.math.abs(t.x - lastOpenX) < 80 && kotlin.math.abs(t.y - lastOpenY) < 80
    }

    /**
     * Kutu akisi:
     *  BOS           -> Open gorulurse BIR KERE bas, COLLECT_BEKLE'ye gec
     *  COLLECT_BEKLE -> sadece Collect All ara (Open'a basma), cikinca bas
     *  SONRAKI_KUTU  -> kisa sure yeni kutu var mi bak (seri toplama), yoksa saldiriya don
     */
    private fun lootAction(now: Long): Hedef? {
        val op = cfg.openT
        val hasCollect = cfg.collectT != null || cfg.collectT2 != null
        if (op == null && !hasCollect) return null
        if (!cfg.lootOn || !ScreenSampler.running || now < lootPauseUntil) {
            lootPhase = Loot.BOS
            return null
        }
        if (now < nextLootScan) return null
        val f = ScreenSampler.grab() ?: return null

        when (lootPhase) {
            Loot.COLLECT_BEKLE -> {
                findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (now > phaseUntil) {
                    lootPhase = Loot.BOS
                    nextLootScan = now + scanGap()
                    if (!warnedCollect) {
                        warnedCollect = true
                        toast("Collect All penceresi tanınmadı. Uygulamada Gelişmiş → ⚡ ayarları yeniden yükle")
                    }
                } else {
                    nextLootScan = now + rand(60, 100)
                }
                return null
            }
            Loot.SONRAKI_KUTU -> {
                findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (op != null) {
                    val pos = findT(f, op)
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
            val pos = findT(f, op)
            if (pos != null && !openBlocked(now, op, pos)) return openHit(now, op, pos)
        }
        return null
    }

    private fun openHit(now: Long, op: Sablon, pos: IntArray): Hedef {
        collectStreak = 0
        lootPhase = Loot.COLLECT_BEKLE
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
        lootPhase = Loot.SONRAKI_KUTU
        phaseUntil = now + 700
        nextLootScan = now + rand(200, 260)
        if (collectStreak >= 3) {
            // 2 kez Collect All'a rağmen acik: envanter dolu, Close ile kapat ve devam et
            doluKutuX = lastOpenX
            doluKutuY = lastOpenY
            doluKutuAt = now
            // Envanter dolu: bankaya git (ayar yok, her zaman). Basarisiz olduysa bir sure bekle
            if (now >= bankaSonraki) bankaIstendi = true
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
        val lo = cfg.minDelay.coerceAtLeast(50)
        val hi = cfg.maxDelay.coerceAtLeast(lo + 1)
        // Iki rastgele sayinin ortalamasi: ortaya yakin, dogal dagilim
        var d = (rand(lo, hi) + rand(lo, hi)) / 2
        if (rnd.nextInt(100) < cfg.pauseChance.coerceIn(0, 100)) d += rand(cfg.pauseMin, cfg.pauseMax)
        return d
    }

    private fun stroke(x: Float, y: Float, radius: Float, start: Long): GestureDescription.StrokeDescription {
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
        return GestureDescription.StrokeDescription(path, start, basmaSuresi())
    }

    /** Birden fazla noktaya ayni anda (farkli parmaklarla) dokun */
    private fun tapMulti(list: List<Hedef>, done: () -> Unit) {
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
