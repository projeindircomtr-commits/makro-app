package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Random

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
    private val rnd = Random()

    private var panel: View? = null
    private var overlay: View? = null
    private var playBtn: TextView? = null
    private var statusTv: TextView? = null

    @Volatile
    private var running = false
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
        updateScreenSize()
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopMacro()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    override fun onDestroy() {
        instance = null
        closeEditor()
        stopMacro()
        removeOverlay()
        panel?.let { safeRemove(it) }
        panel = null
        worker.quitSafely()
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
        overlay = box
        try {
            wm.addView(
                box,
                lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.CENTER }
            )
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun showMainMenu() {
        if (running) {
            toast("Menü için önce makroyu durdur (⏸)"); return
        }
        showMenu(
            "Menü",
            listOf(
                "🛠 Tuşları düzenle" to { openEditor() },
                "🎨 Bar kaydet (HP/MP/hedef)" to { showBarChooser() },
                "📦 Kutu butonu kaydet" to { showLootChooser() },
                "🧪 Ekran testi" to { ekranTesti() },
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
        "⚔ Saldırı" to { onPick("saldiri") },
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
            listOf("saldiri", "hedef", "skill", "hp_pot", "mp_pot").map { t ->
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
            val ne = if (otoMod && olcek == null) "🔍" else if (lootPhase != Loot.BOS) "📦" else "⚔"
            statusTv?.text = "$ne %d:%02d".format(left / 60, left % 60)
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
        }
        running = true
        sonLisansKontrol = SystemClock.uptimeMillis()
        playBtn?.text = "⏸"
        h.postDelayed({ step() }, 700)
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
                    h.postDelayed({ step() }, 1500)
                    return
                }
            } else if (cfg.tuslarOto) {
                h.postDelayed({ step() }, 300)
                return
            }
        }
        if (safetyStop(now)) return

        // Potlar beklemeden, hemen ardindan skill/saldiri/kutu. Oyun ayni anda gelen
        // coklu dokunuslari yok saydigi icin arka arkaya (cok kisa aralikla) basilir.
        val r = cfg.radius.toFloat()
        val pots = potActions(now).map { Hedef(it.x.toFloat(), it.y.toFloat(), r) }
        val p = pickTarget(now)
        val list = pots + listOfNotNull(p)
        if (list.isEmpty()) {
            // Kutu toplarken cok sik kontrol et, normalde biraz bekle
            h.postDelayed({ step() }, if (lootPhase != Loot.BOS) 60L else 200L)
            return
        }
        tapSeq(list, 0) {
            if (running) h.postDelayed({ step() }, if (p?.fast == true) rand(60, 140) else nextDelay())
        }
    }

    private fun isLow(cp: RenkNokta): Boolean {
        val c = ScreenSampler.readPixel(cp.x, cp.y)
        if (c < 0) return false
        if (otoMod && olcek != null) {
            // Renk turune bak: HP kirmizi, MP mavi (cihazin renk ayarindan etkilenmez)
            return if (cp === cfg.mp) !isBlue(c) else !isRed(c)
        }
        return ScreenSampler.diff(c, cp.color) > cfg.tol
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
        // Kutu toplama suruyorsa saldiri/skill yapma
        if (lootPhase != Loot.BOS) return null
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
                lastHpPot = now
                out.add(it)
            }
        }
        val mp = cfg.mp
        if (mp != null && now - lastMpPot > cfg.potCd && isLow(mp)) {
            cfg.points.firstOrNull { it.type == "mp_pot" }?.let {
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

        return pts.firstOrNull { it.type == "saldiri" }
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
        val b = cfg.lootEvery.coerceAtLeast(200)
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
                    nextLootScan = now + rand(90, 160)
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
                    nextLootScan = now + rand(100, 170)
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
        nextLootScan = now + rand(140, 220)
        val t = sablonHedef(op, pos)
        lastOpenX = t.x
        lastOpenY = t.y
        lastOpenAt = now
        collectedSinceOpen = false
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    /** Pencere kapanmadan ust uste Collect All cikiyorsa envanter dolu demektir */
    private fun collectHit(now: Long, co: Sablon, pos: IntArray): Hedef? {
        collectStreak++
        if (collectStreak >= 4) {
            collectStreak = 0
            lootPhase = Loot.BOS
            lootPauseUntil = now + 30_000
            toast("Collect All işe yaramıyor, envanter dolu olabilir. 30 sn kutu atlanıyor")
            return null
        }
        collectedSinceOpen = true
        lootPhase = Loot.SONRAKI_KUTU
        // Pencerenin kapanmasina firsat ver, sonra siradaki kutuya bak
        phaseUntil = now + 900
        nextLootScan = now + rand(330, 420)
        val t = sablonHedef(co, pos)
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    // ---------- Zamanlama ve dokunus ----------

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
        return GestureDescription.StrokeDescription(path, start, rand(55, 140))
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
                .addStroke(GestureDescription.StrokeDescription(path, 0, rand(55, 140)))
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
