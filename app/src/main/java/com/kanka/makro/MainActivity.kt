package com.kanka.makro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTO = "autoCapture"
    }

    /** Panelden ▶ ile acildiysa: izni al, sonra oyuna geri don */
    private var autoMode = false

    /** Ayar ekranindaki sayi alanlari: tek listeden uretiliyor */
    private class Alan(
        val label: String,
        val get: (Config) -> Int,
        val set: (Config, Int) -> Unit,
        val min: Int,
        val max: Int
    )

    private val gruplar: List<Pair<String, List<Alan>>> = listOf(
        "Genel" to listOf(
            Alan("Çalışma süresi (dakika)", { it.minutes }, { c, v -> c.minutes = v }, 1, 1440),
            Alan("Dokunma sapması (piksel)", { it.radius }, { c, v -> c.radius = v }, 0, 200),
            Alan("HP/MP/hedef renk toleransı", { it.tol }, { c, v -> c.tol = v }, 5, 700),
            Alan("Kutu arama toleransı", { it.ttol }, { c, v -> c.ttol = v }, 5, 120)
        ),
        "Dokunuşlar arası bekleme" to listOf(
            Alan("En kısa (ms)", { it.minDelay }, { c, v -> c.minDelay = v }, 50, 10000),
            Alan("En uzun (ms)", { it.maxDelay }, { c, v -> c.maxDelay = v }, 60, 20000)
        ),
        "Mola" to listOf(
            Alan("Mola ihtimali (%)", { it.pauseChance }, { c, v -> c.pauseChance = v }, 0, 50),
            Alan("En kısa mola (ms)", { it.pauseMin }, { c, v -> c.pauseMin = v }, 100, 60000),
            Alan("En uzun mola (ms)", { it.pauseMax }, { c, v -> c.pauseMax = v }, 100, 120000)
        ),
        "Mob seçme" to listOf(
            Alan("Hedef barı yokken: en kısa aralık (ms)", { it.tgtMin }, { c, v -> c.tgtMin = v }, 300, 60000),
            Alan("Hedef barı yokken: en uzun aralık (ms)", { it.tgtMax }, { c, v -> c.tgtMax = v }, 300, 60000),
            Alan("Hedef barı varken: tekrar deneme (ms)", { it.tgtFast }, { c, v -> c.tgtFast = v }, 200, 10000)
        ),
        "Pot ve skill" to listOf(
            Alan("Aynı pot için en az bekleme (ms)", { it.potCd }, { c, v -> c.potCd = v }, 300, 30000),
            Alan("Skill'e eklenen gecikme: en az (ms)", { it.skMin }, { c, v -> c.skMin = v }, 0, 10000),
            Alan("Skill'e eklenen gecikme: en çok (ms)", { it.skMax }, { c, v -> c.skMax = v }, 0, 20000)
        ),
        "Kutu toplama" to listOf(
            Alan("Ekran tarama aralığı (ms)", { it.lootEvery }, { c, v -> c.lootEvery = v }, 200, 10000),
            Alan("Open'dan sonra Collect All bekleme (ms)", { it.collectWait }, { c, v -> c.collectWait = v }, 500, 15000)
        ),
        "Güvenlik" to listOf(
            Alan("HP bu kadar sn düşük kalırsa dur (0 = kapalı)", { it.hpStop }, { c, v -> c.hpStop = v }, 0, 600)
        )
    )

    private val alanEdits = ArrayList<Pair<Alan, EditText>>()
    private val cdEdits = HashMap<Int, EditText>()
    private val onChecks = HashMap<Int, CheckBox>()

    private lateinit var infoTv: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var profBox: LinearLayout
    private lateinit var profName: EditText
    private var cfg = Config()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }

        root.addView(title("Makro"))
        infoTv = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(12)) }
        root.addView(infoTv)

        root.addView(button("1) Erişilebilirlik iznini aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2) Ekran okumayı başlat") { askCapture() })
        root.addView(button("⚡ MykoMobile ayarlarını otomatik yükle") {
            AlertDialog.Builder(this)
                .setMessage("Tüm tuşlar, HP/MP/hedef barları ve kutu butonları MykoMobile için otomatik ayarlansın mı? Şu anki kayıtlı tuşların yerine geçer.")
                .setPositiveButton("Yükle") { _, _ ->
                    saveAll()
                    Preset.apply(this)
                    refresh()
                    toast("Yüklendi. Oyunda ▶'a basman yeterli")
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        root.addView(button("Ekran okumayı durdur") {
            stopService(Intent(this, CaptureService::class.java))
            infoTv.postDelayed({ refreshInfo() }, 400)
        })

        // Kayitli tuslar
        root.addView(title("Kayıtlı tuşlar"))
        root.addView(hint("Skill'ler listedeki sırayla öncelik alır. ▲▼ ile sırala, kutucukla aç/kapat."))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        // Ayarlar
        for ((grup, alanlar) in gruplar) {
            root.addView(subTitle(grup))
            for (a in alanlar) alanEdits.add(a to numField(root, a.label))
        }

        root.addView(button("KAYDET") { saveAll(); toast("Kaydedildi") })

        // Profiller
        root.addView(title("Profiller"))
        root.addView(hint("Tüm tuşlar, barlar, kutu şablonları ve ayarlar birlikte kaydedilir."))
        val pRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        profName = EditText(this).apply {
            hint = "Profil adı (örn. M_SALMAN)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        pRow.addView(profName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pRow.addView(button("Kaydet") {
            val n = profName.text.toString().trim()
            if (n.isEmpty()) {
                toast("Profil adı yaz")
            } else {
                saveAll()
                Config.saveProfile(this, n, Config.load(this))
                profName.setText("")
                buildProfiles()
                toast("'$n' kaydedildi")
            }
        })
        root.addView(pRow)
        profBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(profBox)

        // Tehlikeli islemler
        root.addView(subTitle("Temizlik"))
        root.addView(button("Tüm tuşları, barları ve şablonları sil") {
            AlertDialog.Builder(this)
                .setMessage("Kayıtlı tüm tuşlar, bar noktaları ve kutu şablonları silinsin mi? (Ayarlar ve profiller kalır)")
                .setPositiveButton("Sil") { _, _ ->
                    saveAll()
                    val c = Config.load(this)
                    c.points.clear(); c.hp = null; c.mp = null; c.tgtBar = null
                    c.openT = null; c.collectT = null
                    c.save(this)
                    refresh()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
            text = "Nasıl kullanılır:\n" +
                "• Erişilebilirlikte 'Makro'yu aç. Açılmıyorsa: Ayarlar > Uygulamalar > Makro > ⋮ > Kısıtlı ayarlara izin ver.\n" +
                "• 'Ekran okumayı başlat'a bas ve TÜM EKRAN seç (HP/MP, hedef barı ve kutu için gerekli).\n" +
                "• Yüzen panel: ⠿ sürükle, ▶ başlat/durdur, + tuş kaydet, 🎨 HP/MP/hedef barı, 📦 kutu butonları.\n" +
                "• Hedef barı: bir mob seçiliyken, üstteki kırmızı can barının SOL ucuna yakın bir yere dokun. Mob ölünce hemen yenisini seçer.\n" +
                "• Tuşları oyunu oynadığın yönde (yatay) kaydet. Paneli oyun tuşlarının üstüne koyma."
        })

        setContentView(ScrollView(this).apply { addView(root) })
        handleAuto(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuto(intent)
    }

    private fun handleAuto(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            i.removeExtra(EXTRA_AUTO)
            autoMode = true
            askCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }

    // ================= UI yardimcilari =================

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 21f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun subTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(0xFF7FC8FF.toInt())
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.GRAY)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun small(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun numField(parent: LinearLayout, label: String): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply { text = label; textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.END
        }
        row.addView(e, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        return e
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun trimFloat(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    // ================= Veri =================

    private fun refresh() {
        cfg = Config.load(this)
        for ((a, e) in alanEdits) e.setText(a.get(cfg).toString())
        buildList()
        buildProfiles()
        refreshInfo()
    }

    private fun refreshInfo() {
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        fun d(ok: Boolean, s: String) = (if (ok) "✅ " else "⚪ ") + s
        infoTv.text = listOf(
            (if (acc) "✅ " else "❌ ") + "Erişilebilirlik",
            d(ScreenSampler.running, "Ekran okuma"),
            d(cfg.hp != null, "HP barı") + "    " + d(cfg.mp != null, "MP barı"),
            d(cfg.tgtBar != null, "Hedef barı"),
            d(cfg.openT != null, "Open") + "    " + d(cfg.collectT != null, "Collect All")
        ).joinToString("\n")
    }

    private fun buildList() {
        listBox.removeAllViews()
        cdEdits.clear()
        onChecks.clear()
        if (cfg.points.isEmpty()) {
            listBox.addView(hint("Henüz tuş yok. Oyunda paneldeki + ile kaydet."))
            return
        }
        cfg.points.forEachIndexed { i, p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            if (p.type == "skill") {
                val cb = CheckBox(this).apply { isChecked = p.on }
                onChecks[i] = cb
                row.addView(cb)
            }
            row.addView(TextView(this).apply {
                text = "${p.name}\n(${p.x}, ${p.y})"
                textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (p.type == "skill") {
                row.addView(TextView(this).apply { text = "CD sn"; textSize = 12f })
                val e = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(trimFloat(p.cd))
                    gravity = Gravity.END
                }
                cdEdits[i] = e
                row.addView(e, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            row.addView(small("▲") { move(i, -1) })
            row.addView(small("▼") { move(i, 1) })
            row.addView(small("Sil") { removeAt(i) })
            listBox.addView(row)
            listBox.addView(
                View(this).apply { setBackgroundColor(0x22FFFFFF) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            )
        }
    }

    private fun renumberSkills(c: Config) {
        var n = 1
        c.points.forEach { if (it.type == "skill") it.name = "Skill ${n++}" }
    }

    private fun move(i: Int, dir: Int) {
        saveAll()
        val j = i + dir
        if (j < 0 || j >= cfg.points.size) return
        val tmp = cfg.points[i]
        cfg.points[i] = cfg.points[j]
        cfg.points[j] = tmp
        renumberSkills(cfg)
        cfg.save(this)
        buildList()
    }

    private fun removeAt(i: Int) {
        saveAll()
        if (i >= cfg.points.size) return
        cfg.points.removeAt(i)
        renumberSkills(cfg)
        cfg.save(this)
        buildList()
    }

    private fun buildProfiles() {
        profBox.removeAllViews()
        val names = Config.profiles(this)
        if (names.isEmpty()) {
            profBox.addView(hint("Kayıtlı profil yok."))
            return
        }
        for (n in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply { text = n; textSize = 15f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(small("Yükle") {
                AlertDialog.Builder(this)
                    .setMessage("'$n' yüklensin mi? Şu anki ayarların yerine geçer.")
                    .setPositiveButton("Yükle") { _, _ ->
                        if (Config.loadProfile(this, n)) {
                            refresh(); toast("'$n' yüklendi")
                        } else toast("Profil okunamadı")
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            })
            row.addView(small("Sil") {
                AlertDialog.Builder(this)
                    .setMessage("'$n' profili silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Config.deleteProfile(this, n); buildProfiles() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            })
            profBox.addView(row)
        }
    }

    /** Ekrandaki degerleri en guncel kayda yazar */
    private fun saveAll() {
        if (alanEdits.isEmpty()) return
        // Servis arada yeni tus kaydetmis olabilir; en guncel hali yukle
        val latest = Config.load(this)
        for ((a, e) in alanEdits) {
            val v = e.text.toString().trim().toIntOrNull() ?: a.get(latest)
            a.set(latest, v.coerceIn(a.min, a.max))
        }
        if (latest.maxDelay <= latest.minDelay) latest.maxDelay = latest.minDelay + 1
        if (latest.pauseMax < latest.pauseMin) latest.pauseMax = latest.pauseMin
        if (latest.tgtMax < latest.tgtMin) latest.tgtMax = latest.tgtMin
        if (latest.skMax < latest.skMin) latest.skMax = latest.skMin

        // Liste ekranda gosterilen cfg ile ayni sirada; indeks eslesmesi icin kontrol
        val sameList = latest.points.size >= cfg.points.size &&
            cfg.points.indices.all { latest.points[it].x == cfg.points[it].x && latest.points[it].y == cfg.points[it].y }
        if (sameList) {
            for ((i, e) in cdEdits) {
                val v = e.text.toString().replace(',', '.').toFloatOrNull() ?: continue
                if (latest.points[i].type == "skill") latest.points[i].cd = v.coerceIn(0f, 3600f)
            }
            for ((i, cb) in onChecks) {
                if (latest.points[i].type == "skill") latest.points[i].on = cb.isChecked
            }
        }
        latest.save(this)
        cfg = latest
    }

    // ================= Ekran yakalama izni =================

    @Suppress("DEPRECATION")
    private fun askCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        if (ScreenSampler.running) {
            toast("Zaten çalışıyor")
            if (autoMode) {
                autoMode = false
                moveTaskToBack(true)
            }
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode != RESULT_OK || data == null) {
            toast("İzin verilmedi")
            if (autoMode) {
                autoMode = false
                moveTaskToBack(true)
            }
            return
        }
        val i = Intent(this, CaptureService::class.java)
            .putExtra("code", resultCode)
            .putExtra("data", data)
        startForegroundService(i)
        infoTv.postDelayed({ refreshInfo() }, 800)
        if (autoMode) {
            // Oyuna geri don; makro kendiliginden baslayacak
            autoMode = false
            moveTaskToBack(true)
        }
    }
}
