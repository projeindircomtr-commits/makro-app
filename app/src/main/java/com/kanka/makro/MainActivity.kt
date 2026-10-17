package com.kanka.makro

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    companion object {
        /** Google Play "açık bilgilendirme": erişilebilirlik için ayrı ekran (başka veri bilgilendirmesiyle birleştirilmez) */
        const val ERISIM_BILGI =
            "Projeindir Bot, Android'in Erişilebilirlik hizmetini (AccessibilityService) kullanır. Bu bir engelli erişim aracı değildir; " +
            "oyun içi makro aracıdır (senin belirlediğin tuşlara otomatik dokunur).\n\n" +
            "Bu izinle uygulama ŞUNLARI YAPAR:\n" +
            "• Senin kaydettiğin noktalara (oyun tuşlarına) dokunma ve kaydırma hareketleri yapar. Bot yalnızca sen ▶ BAŞLAT'a bastığında çalışır, DURDUR ile durur.\n" +
            "• Ekranda hangi uygulamanın açık olduğunu (uygulama paket adı) görür: panel yalnızca seçtiğin oyun açıkken görünür.\n" +
            "• Oyun ekranının görüntüsünü alır: HP/MP çubuğu, mob can çubuğu, kutu (Open / Collect All) gibi öğeleri bulmak için. Görüntüler telefonda işlenir, " +
            "saklanmaz ve hiçbir yere gönderilmez (yalnızca sen 'Ekran testi'ni kullanırsan bir görüntü telefonunun İndirilenler klasörüne kaydedilir).\n" +
            "• Ekrandaki oyun yazılarını (koordinat, düğme adları) telefonda okur.\n" +
            "• Oyunun açtığı yazı kutularına (ör. pazar fiyatı, oyunun kontrol penceresi) yazı yazar ve klavyedeki Tamam'a basabilir.\n\n" +
            "Bu izinle uygulama ŞUNLARI YAPMAZ:\n" +
            "• Şifre, mesaj, kişi, fotoğraf veya ödeme bilgisi okumaz, toplamaz.\n" +
            "• Seçtiğin oyun ve oyunun açtığı klavye dışındaki uygulamalara dokunmaz.\n\n" +
            "İzni istediğin an Ayarlar → Erişilebilirlik → Projeindir Bot'tan kapatabilirsin; o zaman bot çalışmaz."

        /** Tanılama raporu bilgilendirmesi (erişilebilirlik bilgilendirmesinden ayrı) */
        const val RAPOR_BILGI =
            "Destek verebilmemiz için bot, üyelik hesabınla birlikte projeindir.com.tr'ye otomatik tanılama raporu gönderir: " +
            "bot durduğunda ve çalışırken 30 dakikada bir.\n\n" +
            "Raporda: uygulama sürümü, telefon markası/modeli, Android sürümü, ekran boyutu, seçili oyun ve mod, botun olay kaydı " +
            "(zaman, durma nedeni, bastığı tuşların ekran konumu, oyun içi koordinat, kutu/rota adımları).\n\n" +
            "Raporda YOK: ekran görüntüsü, şifre, mesaj, kişi veya ödeme bilgisi.\n\n" +
            "Raporlar yalnızca yönetim tarafından destek için görülür, üçüncü kişilerle paylaşılmaz ve 30 gün sonra silinir."
        const val EXTRA_AUTO = "autoCapture"

        /** AdMob reklam birimi (giriş ekranı banner'ı). Uygulama kimliği AndroidManifest.xml'de (APPLICATION_ID). */
        const val ADMOB_BANNER = "ca-app-pub-1035543413650045/3046925537"

        // Knight Online arayuzu (oyundaki CHAR INFO / SKILL PAGE pencereleri gibi)
        private const val BG = 0xFF070707.toInt()          // siyah
        private const val CARD = 0xFF121212.toInt()        // panel
        private const val ACCENT = 0xFFD2A866.toInt()      // mat altin (oyundaki etiket yazisi)
        private const val GREEN = 0xFFB8862B.toInt()       // baslat: altin degrade
        private const val LACI = 0xFF2A3240.toInt()        // celik-lacivert buton
        private const val PARSOMEN = 0xFFEDE3C8.toInt()    // krem yazi
        private const val CERCEVE = 0xFF8C7A55.toInt()     // bronz cerceve (oyundaki paneller)
        private const val BEJ = 0xFFC9AE78.toInt()         // buton kenari (oyundaki gibi)
        private const val RED = 0xFFB33A3A.toInt()
        private const val MUTED = 0xFF9AA4B2.toInt()
    }

    /** Panelden ▶ ile acildiysa: izni al, sonra oyuna geri don */
    private var autoMode = false

    /** Buyuk butondan: izin gelince oyunu ac ve baslat */
    private var launchAfterCapture = false

    // ---------- Gelismis ayar alanlari ----------
    private class Alan(
        val label: String,
        val get: (Config) -> Int,
        val set: (Config, Int) -> Unit,
        val min: Int,
        val max: Int
    )

    private val gruplar: List<Pair<String, List<Alan>>> = listOf(
        "Genel" to listOf(
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
            Alan("Pot bekleme (ms) • KO'da HP/MP ortak iksir arası", { it.potCd }, { c, v -> c.potCd = v }, 300, 30000),
                        Alan("Minor açılsın: can şunun altına inince (%)", { it.minorYuzde }, { c, v -> c.minorYuzde = v }, 20, 99),
            Alan("Skill'e eklenen gecikme: en az (ms)", { it.skMin }, { c, v -> c.skMin = v }, 0, 10000),
            Alan("Skill'e eklenen gecikme: en çok (ms)", { it.skMax }, { c, v -> c.skMax = v }, 0, 20000)
        ),
        "Kutu toplama" to listOf(
            Alan("Ekran tarama aralığı (ms)", { it.lootEvery }, { c, v -> c.lootEvery = v }, 100, 10000),
            Alan("Open'dan sonra Collect All bekleme (ms)", { it.collectWait }, { c, v -> c.collectWait = v }, 500, 15000)
        ),
        "Güvenlik" to listOf(
            Alan("HP bu kadar sn düşük kalırsa dur (0 = kapalı)", { it.hpStop }, { c, v -> c.hpStop = v }, 0, 600)
        )
    )

    private val alanEdits = ArrayList<Pair<Alan, EditText>>()
    private val cdEdits = HashMap<Int, EditText>()
    private val onChecks = HashMap<Int, CheckBox>()

    // ---------- Gorunumler ----------
    private lateinit var lisansDurum: TextView
    private lateinit var girisBox: LinearLayout
    private lateinit var cikisBtn: Button
    private lateinit var guncelleBtn: Button
    private lateinit var girisBtn: Button
    private lateinit var kEdit: EditText
    private lateinit var sEdit: EditText
    private lateinit var step3: TextView
    private lateinit var btn3: Button
    private lateinit var startBtn: Button
    private lateinit var minutesTv: TextView
    private lateinit var speedNormal: Button
    private lateinit var speedFast: Button
    private lateinit var speedSeri: Button
    private lateinit var lootSwitch: Switch
    private lateinit var modFarmBtn: Button
    private lateinit var modPkBtn: Button
    private lateinit var modGenieBtn: Button
    private val normalSatirlar = ArrayList<View>()
    private lateinit var genieSatir: LinearLayout
    private lateinit var genieMsTv: TextView
    private lateinit var ayarBaslik: TextView
    private lateinit var sinifSatir: LinearLayout
    private val sinifBtnler = HashMap<String, Button>()
    private lateinit var otoSwitch: Switch
    private lateinit var hpTv: TextView
    private lateinit var mpTv: TextView
    private lateinit var kilitTv: TextView
    private lateinit var advBox: LinearLayout
    private lateinit var advToggle: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var profBox: LinearLayout
    private lateinit var profName: EditText
    private var cfg = Config()
    private var web: WebView? = null
    private var reklam: com.google.android.gms.ads.AdView? = null
    private lateinit var reklamKutu: FrameLayout
    private var reklamBasladi = false
    private lateinit var reklamGizlilikBtn: Button
    private lateinit var reklamDurumTv: TextView

    /** Giris yapilmadan gizli kalan bolumler */
    private val uyeBolumleri = ArrayList<View>()
    private lateinit var modKartView: LinearLayout
    private lateinit var oyunKartView: LinearLayout
    private lateinit var baglanBtn: Button
    private lateinit var sunucuDegistirBtn: TextView
    private fun sunucuOnayli(): Boolean = getSharedPreferences("genel", MODE_PRIVATE).getBoolean("sunucuOnay", false)
    private fun sunucuOnayYaz(v: Boolean) { getSharedPreferences("genel", MODE_PRIVATE).edit().putBoolean("sunucuOnay", v).apply() }
    /** Sunucu kartı görünüyorsa (birden fazla sunucu seçilebiliyorsa) önce "Bağlan" gerekir; tek sunucuda doğrudan açık */
    private fun ayarlarAcik(): Boolean = !(Lisans.gecerliSimdi() && Config.botGorunur("ko")) || sunucuOnayli()
    private lateinit var oyunMykoBtn: Button
    private lateinit var oyunKoBtn: Button
    private lateinit var oyunRevBtn: Button
    private lateinit var oyunNowaBtn: Button
    private lateinit var oyunSecBtn: Button
    private lateinit var koPaketBtn: Button
    private lateinit var anaScroll: ScrollView
    private lateinit var izinScroll: ScrollView
    private lateinit var izinIcerik: LinearLayout
    private var gelismisAcik = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ================= Kurulum =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        Lisans.yukle(this)

        // Ilk acilista secili oyunun ayarlari hazir gelsin
        if (Config.load(this).points.isEmpty()) Config.hazirAyar(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
            setBackgroundColor(BG)
        }

        // Baslik
        root.addView(TextView(this).apply {
            text = "Projeindir Bot"
            textSize = 32f
            typeface = SERIF_BOLD
            setTextColor(ACCENT)
            setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        })
        root.addView(TextView(this).apply {
            text = "MykoMobile  •  RevengerOnline  •  NowaOnline  •  KO Mobile      Yapımcı: Muhammed Salman"
            textSize = 14f
            setTextColor(ACCENT)
            setPadding(0, 0, 0, dp(14))
        })

        // --- Reklam: yalnızca uygulamanın giriş (ana) ekranında, tek banner; oyunun içinde reklam yok ---
        reklamKutu = FrameLayout(this)
        root.addView(reklamKutu, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        // Sadece yöneticiye: reklam neden görünmüyor (AdMob hata kodu)
        reklamDurumTv = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            visibility = View.GONE
        }
        root.addView(reklamDurumTv)

        // --- Uyelik karti ---
        val lk = card(root)
        lk.addView(cardTitle("Üyelik"))
        lisansDurum = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, 0, dp(6))
        }
        lk.addView(lisansDurum)
        girisBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kEdit = EditText(this).apply {
            hint = "Kullanıcı adı"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        sEdit = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        girisBox.addView(kEdit)
        girisBox.addView(sEdit)
        girisBtn = smallButton("Giriş yap") { girisYap() }
        girisBox.addView(girisBtn)
        girisBox.addView(TextView(this).apply {
            text = "Cihaz kodu: " + Lisans.cihazKodu(this@MainActivity)
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, 0)
        })
        lk.addView(girisBox)
        cikisBtn = smallButton("Çıkış yap") {
            Lisans.cikis(this)
            sunucuOnayYaz(false)
            lisansYenile()
        }
        lk.addView(cikisBtn)
        guncelleBtn = smallButton("⬇ Yeni sürümü indir") { siteAc(Uri.parse(Lisans.guncelleUrl)) }
        lk.addView(guncelleBtn)

        // --- Mod secimi: Farm / PK (her modun kendi ayarlari) ---
        val modKart = card(root)
        modKartView = modKart
        uyeBolumleri.add(modKart)
        modKart.addView(cardTitle("Mod"))
        val modSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modFarmBtn = Button(this).apply {
            text = "🌾  FARM"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { modSec(false) }
        }
        modPkBtn = Button(this).apply {
            text = "⚔  PK"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { modSec(true) }
        }
        modGenieBtn = Button(this).apply {
            text = "🧞  GENIE"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { genieModSec() }
        }
        modSatir.addView(modFarmBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modSatir.addView(modPkBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        modSatir.addView(modGenieBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        modKart.addView(modSatir)
        // PK karakter secimi
        sinifSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        Config.SINIFLAR.forEachIndexed { i, (kod, ad) ->
            val b = Button(this).apply {
                text = ad
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { modSec(true, kod) }
            }
            sinifBtnler[kod] = b
            if (i > 0) sinifSatir.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            sinifSatir.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        modKart.addView(sinifSatir)
        modKart.addView(TextView(this).apply {
            text = "🌾 Farm Bot: mob seçer, skill basar, HP/MP pot kullanır, kutu toplar. " +
                "Oyunda paneldeki ▶ ile istediğin zaman başlat / durdur."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, 0)
        })

        // --- Oyun secimi: MykoMobile / KO Mobile (KO simdilik sadece yonetici) ---
        val oyunKart = card(root)
        oyunKartView = oyunKart
        uyeBolumleri.add(oyunKart)
        oyunKart.addView(cardTitle("Oyun seç"))
        // Tek satır: [Oyun seç ▾] [Bağlan] [Değiştir]. Oyun listesi açılır pencerede (ayarlar oyuna göre ayrı saklanır)
        val oyunSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun oyunBtn(t: String, kod: String) = Button(this).apply {
            text = t
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { oyunSec(kod) }
        }
        oyunMykoBtn = oyunBtn("MykoMobile", "myko")
        oyunRevBtn = oyunBtn("RevengerOnline", "rev")
        oyunKoBtn = oyunBtn("KO Mobile", "ko")
        oyunNowaBtn = oyunBtn("NowaOnline", "nowa")
        // (Eski oyun düğmeleri ekrana eklenmez; refresh() içindeki renk güncellemeleri zararsız kalır)
        oyunSecBtn = Button(this).apply {
            textSize = 16f
            isAllCaps = false
            setTextColor(0xFF12161C.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(ACCENT, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { oyunListesi() }
        }
        oyunSatir.addView(oyunSecBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        oyunKart.addView(oyunSatir)
        baglanBtn = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                sunucuOnayYaz(true)
                lisansYenile()
                refresh()
            }
        }
        oyunSatir.addView(baglanBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        sunucuDegistirBtn = smallButton("↩ Değiştir") {
            sunucuOnayYaz(false)
            lisansYenile()
            refresh()
        }
        oyunSatir.addView(sunucuDegistirBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        oyunKart.addView(TextView(this).apply {
            text = "Her oyunun tuşları ve ayarları ayrı saklanır. Oyunu seç, sonra Bağlan'a bas."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, 0)
        })
        koPaketBtn = smallButton("📱 KO Mobile uygulamasını seç") { koPaketSec() }
        oyunKart.addView(koPaketBtn)

        // --- Kurulum karti ---
        // Mod karti oyun seciminin altinda dursun
        root.removeView(modKart)
        root.addView(modKart, root.indexOfChild(oyunKart) + 1)

        val setup = card(root)
        uyeBolumleri.add(setup)
        setup.addView(cardTitle("Durum"))
        // İzinler (arka plan, kısıtlı ayarlar, erişilebilirlik) uygulama açılışındaki zorunlu izin ekranında
        val r3 = stepRow(setup, "Oyun yüklü", "Kontrol") { refreshSteps() }
        step3 = r3.first; btn3 = r3.second
        setup.addView(smallButton("🎬 Kurulum videosu") { siteAc(Uri.parse(Lisans.kurulumVideoUrl(this))) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setup.addView(smallButton("📄 Gizlilik politikası") { siteAc(Uri.parse(gizlilikUrl())) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        reklamGizlilikBtn = smallButton("Reklam gizlilik ayarları") {
            try {
                com.google.android.ump.UserMessagingPlatform.showPrivacyOptionsForm(this) { _ -> }
            } catch (e: Throwable) {
            }
        }
        reklamGizlilikBtn.visibility = View.GONE
        setup.addView(reklamGizlilikBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // --- Buyuk baslat ---
        startBtn = Button(this).apply {
            text = "▶  OYUNU AÇ VE BAŞLAT"
            textSize = 20f
            isAllCaps = false
            setTextColor(0xFF1A1206.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(GREEN, 16)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { startAll() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16); bottomMargin = dp(6) })
        uyeBolumleri.add(startBtn)
        root.addView(TextView(this).apply {
            uyeBolumleri.add(this)
            text = "Oyunda sol üstteki panelden ⏸ ile durdurabilirsin."
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })

        // --- Basit ayarlar ---
        val quick = card(root)
        uyeBolumleri.add(quick)
        ayarBaslik = cardTitle("Ayarlar")
        quick.addView(ayarBaslik)

        val mRow = row()
        mRow.addView(label("Çalışma süresi"), weight1())
        mRow.addView(smallButton("−") { changeMinutes(-10) })
        minutesTv = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = degerKutusu()
            setPadding(0, dp(6), 0, dp(6))
        }
        mRow.addView(minutesTv, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        mRow.addView(smallButton("+") { changeMinutes(10) })
        quick.addView(mRow)
        normalSatirlar.add(mRow)

        // Pot esikleri
        val hRow = row()
        hRow.addView(label("❤ HP potu, can şunun altına inince"), weight1())
        hRow.addView(smallButton("−") { yuzdeDegis(true, -5) })
        hpTv = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = degerKutusu(); setPadding(0, dp(6), 0, dp(6))
        }
        hRow.addView(hpTv, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        hRow.addView(smallButton("+") { yuzdeDegis(true, 5) })
        quick.addView(hRow)
        normalSatirlar.add(hRow)

        val pRow2 = row()
        pRow2.addView(label("💧 MP potu, mana şunun altına inince"), weight1())
        pRow2.addView(smallButton("−") { yuzdeDegis(false, -5) })
        mpTv = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = degerKutusu(); setPadding(0, dp(6), 0, dp(6))
        }
        pRow2.addView(mpTv, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        pRow2.addView(smallButton("+") { yuzdeDegis(false, 5) })
        quick.addView(pRow2)
        normalSatirlar.add(pRow2)

        // Mob kilidi
        val kRow = row()
        kilitTv = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        kRow.addView(kilitTv, weight1())
        kRow.addView(smallButton("Kaldır") {
            val c = Config.load(this)
            c.kilitler.clear()
            c.save(this)
            refresh()
            toast("Kilitler kaldırıldı")
        })
        quick.addView(kRow)
        normalSatirlar.add(kRow)
        quick.addView(TextView(this).apply {
            normalSatirlar.add(this)
            text = "Mob kilitlemek için: oyunda mobu seç → panelde ⋯ → 🎯 Seçili mobu kilitle. Birden fazla mob kilitleyebilirsin."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, dp(6))
        })

        val sRow = row()
        sRow.addView(label("Hız"), weight1())
        speedNormal = smallButton("Normal") { setSpeed(0) }
        speedFast = smallButton("Hızlı") { setSpeed(1) }
        speedSeri = smallButton("Seri") { setSpeed(2) }
        sRow.addView(speedNormal)
        sRow.addView(speedFast)
        sRow.addView(speedSeri)
        quick.addView(sRow)
        normalSatirlar.add(sRow)

        val lRow = row()
        lRow.addView(label("Kutuları topla"), weight1())
        lootSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on ->
                val c = Config.load(this@MainActivity)
                if (c.lootOn != on) {
                    c.lootOn = on
                    c.save(this@MainActivity)
                }
            }
        }
        lRow.addView(lootSwitch)
        quick.addView(lRow)
        normalSatirlar.add(lRow)

        val oRow = row()
        oRow.addView(label("Ekranı otomatik tanı"), weight1())
        otoSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on ->
                val c = Config.load(this@MainActivity)
                if (c.otoArayuz != on) {
                    c.otoArayuz = on
                    c.save(this@MainActivity)
                }
            }
        }
        oRow.addView(otoSwitch)
        quick.addView(oRow)
        normalSatirlar.add(oRow)
        quick.addView(TextView(this).apply {
            normalSatirlar.add(this)
            text = "Açıkken HP/MP barı, mobun can barı ve kutular her telefon ve tablette kendiliğinden bulunur."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, 0)
        })

        // Genie Hizlandir: tek ayar, kilic araligi
        genieSatir = row()
        genieSatir.addView(label("🗡 Kılıç aralığı"), weight1())
        genieSatir.addView(smallButton("−") { genieMsDegis(-50) })
        genieMsTv = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = degerKutusu(); setPadding(0, dp(6), 0, dp(6))
        }
        genieSatir.addView(genieMsTv, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        genieSatir.addView(smallButton("+") { genieMsDegis(50) })
        quick.addView(genieSatir)

        // --- Site onizlemesi ---
        val site = card(root)
        site.addView(cardTitle("projeindir.com.tr"))
        try {
            val w = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(CARD)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                        // Yonlendirmeler (http->https vb.) icinde kalsin
                        if (req.isRedirect) return false
                        // Dokunulan linkler tarayicida acilsin
                        siteAc(req.url)
                        return true
                    }
                }
                loadUrl("https://projeindir.com.tr")
            }
            web = w
            site.addView(w, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
            ))
        } catch (e: Exception) {
            // Cihazda WebView yoksa sadece buton kalsin
        }

        // --- Gelismis ---
        advToggle = TextView(this).apply {
            text = "Gelişmiş ayarlar  ▾"
            textSize = 16f
            setTextColor(ACCENT)
            setPadding(dp(4), dp(20), dp(4), dp(10))
            setOnClickListener { toggleAdvanced() }
        }
        root.addView(advToggle)
        uyeBolumleri.add(advToggle)
        advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(advBox)
        buildAdvanced(advBox)

        root.addView(TextView(this).apply {
            text = "Projeindir Bot  •  Sürüm ${Lisans.surumKodu(this@MainActivity)}  •  Yapımcı: Muhammed Salman"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        anaScroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }
        izinIcerik = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(30))
        }
        izinScroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(izinIcerik)
        }
        setContentView(FrameLayout(this).apply {
            addView(anaScroll)
            addView(izinScroll)
        })
        izinGuncelle()
        handleAuto(intent)

        // Panel mesajlari bildirim olarak gelebilsin (Android 13+ izin ister)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuto(intent)
    }

    override fun onResume() {
        super.onResume()
        izinGuncelle()
        reklam?.resume()
        web?.onResume()
        refresh()
        lisansYenile()
        // Kayitli giris varsa sessizce dogrula
        if (!Lisans.gecerliSimdi() && Lisans.kayitliKullanici(this).isNotEmpty()) {
            lisansDurum.text = "Kontrol ediliyor..."
            Lisans.arkaPlanKontrol(this) { r ->
                if (!r.ok) toast(r.mesaj)
                lisansYenile()
            }
        }
    }

    private fun lisansYenile() {
        val ok = Lisans.gecerliSimdi()
        val gnc = Lisans.guncelleGerekli
        if (ok) {
            val k = Lisans.kalanSn()
            val g = k / 86400
            val sa = (k % 86400) / 3600
            val kalan = if (g > 0) "$g gün $sa saat" else "$sa saat ${(k % 3600) / 60} dk"
            lisansDurum.text = "✅ ${Lisans.isim}  •  $kalan kaldı" +
                (if (Lisans.testHesabi()) "\n🧪 YÖNETİM TEST HESABI (oyunda ⋯ → Test modu)" else "")
        } else if (gnc) {
            lisansDurum.text = "⬆ " + Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." }
        } else {
            lisansDurum.text = "❌ Giriş yapılmadı"
        }
        guncelleBtn.visibility = if (gnc && Lisans.guncelleUrl.isNotEmpty()) View.VISIBLE else View.GONE
        girisBox.visibility = if (ok) View.GONE else View.VISIBLE
        cikisBtn.visibility = if (ok) View.VISIBLE else View.GONE
        // Uye degilse hicbir ayar/secenek gorunmesin
        val g = if (ok) View.VISIBLE else View.GONE
        val acik = ayarlarAcik()
        for (v in uyeBolumleri) v.visibility = if (ok && (acik || v === oyunKartView)) View.VISIBLE else View.GONE
        // Mod karti (Farm/PK): sadece yonetici gorur. Diger uyelerde giris sonrasi da gizli kalir
        // Tek mod (Farm) varken seçilecek bir şey yok: kart gizli
        modKartView.visibility = if (ok && Config.izinliModlar().size > 1) View.VISIBLE else View.GONE
        oyunKartView.visibility = if (ok && Config.botGorunur("ko")) View.VISIBLE else View.GONE
        advBox.visibility = if (ok && gelismisAcik && acik) View.VISIBLE else View.GONE
        baglanBtn.visibility = if (ok && !acik) View.VISIBLE else View.GONE
        baglanBtn.text = "🔗 Bağlan"
        baglanBtn.background = rounded(GREEN, 14)
        sunucuDegistirBtn.visibility = if (ok && acik && Config.botGorunur("ko")) View.VISIBLE else View.GONE
        if (kEdit.text.isEmpty()) kEdit.setText(Lisans.kayitliKullanici(this))
    }

    private fun girisYap() {
        val k = kEdit.text.toString().trim()
        val s = sEdit.text.toString()
        if (k.isEmpty() || s.isEmpty()) {
            toast("Kullanıcı adı ve şifre yaz"); return
        }
        lisansDurum.text = "Giriş yapılıyor..."
        Lisans.giris(this, k, s) { r ->
            toast(r.mesaj)
            if (r.ok) sEdit.setText("")
            lisansYenile()
        }
    }

    /** Reklam izni (AB/İngiltere'de Google'ın rıza penceresi), sonra banner. Hata olursa uygulama etkilenmez. */
    private var reklamIstendi = false

    private fun reklamBaslat() {
        if (reklamIstendi) return
        reklamIstendi = true
        try {
            val ci = com.google.android.ump.UserMessagingPlatform.getConsentInformation(this)
            val prm = com.google.android.ump.ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
            ci.requestConsentInfoUpdate(this, prm, {
                com.google.android.ump.UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
                    if (ci.canRequestAds()) reklamYukle()
                    // AB/İngiltere: rızayı sonradan değiştirebilmek için düğme (Google şartı)
                    reklamGizlilikBtn.visibility = if (ci.getPrivacyOptionsRequirementStatus() ==
                        com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) View.VISIBLE else View.GONE
                }
            }, { hata ->
                if (ci.canRequestAds()) reklamYukle() else reklamDurum("Reklam rızası alınamadı: ${hata.message}")
            })
            // Önceki açılışta izin alınmışsa beklemeden yükle
            if (ci.canRequestAds()) reklamYukle()
        } catch (e: Throwable) {
        }
    }

    /** Yöneticiye reklam durumunu gösterir (üyeler görmez) */
    private fun reklamDurum(t: String) {
        runOnUiThread {
            reklamDurumTv.text = t
            reklamDurumTv.visibility = if (t.isNotEmpty() && Lisans.yoneticiMi()) View.VISIBLE else View.GONE
        }
    }

    private fun reklamYukle() {
        if (reklamBasladi || isFinishing) return
        reklamBasladi = true
        reklamDurum("Reklam yükleniyor...")
        try {
            com.google.android.gms.ads.MobileAds.initialize(this) {}
            val dm = resources.displayMetrics
            val genDp = ((dm.widthPixels / dm.density).toInt() - 40).coerceIn(320, 728)
            val av = com.google.android.gms.ads.AdView(this)
            av.setAdSize(com.google.android.gms.ads.AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, genDp))
            av.adUnitId = ADMOB_BANNER
            av.adListener = object : com.google.android.gms.ads.AdListener() {
                override fun onAdLoaded() { reklamDurum("") }
                override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                    val neden = when (e.code) {
                        0 -> "AdMob iç hata"
                        1 -> "geçersiz istek (uygulama/reklam kimliği?)"
                        2 -> "internet bağlantısı"
                        3 -> "gösterilecek reklam yok (yeni hesap/uygulama: AdMob onayı ve ilk reklamlar birkaç saat-gün sürebilir)"
                        else -> "kod ${e.code}"
                    }
                    reklamDurum("Reklam yüklenemedi: $neden • ${e.message}")
                }
            }
            reklamKutu.addView(av, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            av.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
            reklam = av
        } catch (e: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        reklam?.pause()
        saveAll()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        reklam?.destroy()
        reklam = null
        super.onDestroy()
    }

    private fun siteAc(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (e: Exception) {
            toast("Tarayıcı açılamadı")
        }
    }

    private fun handleAuto(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            i.removeExtra(EXTRA_AUTO)
            autoMode = true
            askCapture()
        }
    }

    // ================= UI yardimcilari =================

    private fun acik(c: Int, oran: Float): Int {
        val a = (c ushr 24) and 0xff
        fun k(v: Int) = (v + (255 - v) * oran).toInt().coerceIn(0, 255)
        return (a shl 24) or (k((c shr 16) and 0xff) shl 16) or (k((c shr 8) and 0xff) shl 8) or k(c and 0xff)
    }

    /** Panel (koyu) -> gri metal cerceve; buton -> celik degrade + bej kenar */
    private fun rounded(color: Int, r: Int): GradientDrawable {
        val panelMi = ((color shr 16) and 0xff) < 40 && ((color shr 8) and 0xff) < 40 && (color and 0xff) < 40
        return if (panelMi) GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(3), CERCEVE)
        } else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(acik(color, 0.28f), color)).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(2), BEJ)
        }
    }

    /** Oyundaki deger kutulari gibi: siyah zemin, beyaz ince cerceve */
    private fun degerKutusu() = GradientDrawable().apply {
        setColor(0xFF000000.toInt())
        cornerRadius = dp(2).toFloat()
        setStroke(dp(2), 0xFFD8D8D8.toInt())
    }

    private val SERIF_BOLD = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        parent.addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        return c
    }

    private fun cardTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        typeface = SERIF_BOLD
        setTextColor(ACCENT)
        setPadding(dp(4), 0, 0, dp(8))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.WHITE)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), 0, dp(4))
    }

    private fun weight1() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        typeface = SERIF_BOLD
        setTextColor(PARSOMEN)
        background = rounded(LACI, 10)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
    }

    private fun stepRow(parent: LinearLayout, title: String, action: String, onClick: () -> Unit): Pair<TextView, Button> {
        val r = row()
        val tv = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        r.addView(tv, weight1())
        val b = smallButton(action, onClick)
        r.addView(b)
        parent.addView(r)
        return tv to b
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ================= Durum =================

    private fun accEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) ||
            getSharedPreferences("genel", MODE_PRIVATE).getBoolean("pilAtla", false)

    private fun setStep(tv: TextView, b: Button, base: String, ok: Boolean) {
        tv.text = (if (ok) "✅  " else "❌  ") + base
        b.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun refreshSteps() {
        setStep(step3, btn3, "Oyun yüklü (${Config.oyunAd(this)})", findGame() != null)
    }

    private fun refresh() {
        Config.modDuzelt(this)   // uyeye verilmeyen mod secili kalmasin
        cfg = Config.load(this)
        refreshSteps()
        minutesTv.text = "${cfg.minutes} dk"
        val mod = when {
            cfg.maxDelay <= 150 -> 2
            cfg.maxDelay <= 320 -> 1
            else -> 0
        }
        val kapali = LACI
        speedNormal.background = rounded(if (mod == 0) ACCENT else kapali, 10)
        speedFast.background = rounded(if (mod == 1) ACCENT else kapali, 10)
        speedSeri.background = rounded(if (mod == 2) ACCENT else kapali, 10)
        lootSwitch.isChecked = cfg.lootOn
        val ko = Config.koMu(this)
        val rev = Config.revMi(this)
        val nowa = Config.nowaMi(this)
        val myko = !ko && !rev && !nowa
        oyunSecBtn.text = "🎮  " + Config.oyunAd(this) + "  ▾"
        koPaketBtn.visibility = if (ko) View.VISIBLE else View.GONE
        oyunNowaBtn.background = rounded(if (nowa) ACCENT else LACI, 14)
        oyunNowaBtn.text = (if (nowa) "✓ " else "") + "NowaOnline"
        oyunNowaBtn.setTextColor(if (nowa) 0xFF12161C.toInt() else Color.WHITE)
        oyunMykoBtn.background = rounded(if (myko) ACCENT else LACI, 14)
        oyunKoBtn.background = rounded(if (ko) ACCENT else LACI, 14)
        oyunRevBtn.background = rounded(if (rev) ACCENT else LACI, 14)
        oyunMykoBtn.text = (if (myko) "✓ " else "") + "MykoMobile"
        oyunRevBtn.text = (if (rev) "✓ " else "") + "RevengerOnline"
        oyunKoBtn.text = (if (ko) "✓ " else "") + "KO Mobile"
        oyunMykoBtn.setTextColor(if (myko) 0xFF12161C.toInt() else Color.WHITE)
        oyunKoBtn.setTextColor(if (ko) 0xFF12161C.toInt() else Color.WHITE)
        oyunRevBtn.setTextColor(if (rev) 0xFF12161C.toInt() else Color.WHITE)
        val pk = Config.pkMi(this)
        val genie = Config.genieMi(this)
        val farm = !pk && !genie
        modFarmBtn.visibility = if (Config.botGorunur("farm")) View.VISIBLE else View.GONE
        modPkBtn.visibility = if (Config.botGorunur("pk")) View.VISIBLE else View.GONE
        modGenieBtn.visibility = if (Config.botGorunur("genie")) View.VISIBLE else View.GONE
        modFarmBtn.background = rounded(if (farm) ACCENT else LACI, 14)
        modPkBtn.background = rounded(if (pk) ACCENT else LACI, 14)
        modGenieBtn.background = rounded(if (genie) ACCENT else LACI, 14)
        modFarmBtn.setTextColor(if (farm) 0xFF12161C.toInt() else Color.WHITE)
        modPkBtn.setTextColor(if (pk) 0xFF12161C.toInt() else Color.WHITE)
        modGenieBtn.setTextColor(if (genie) 0xFF12161C.toInt() else Color.WHITE)
        ayarBaslik.text = if (genie) "Ayarlar • Genie Hızlandır" + (if (ko) " • KO Mobile" else "")
        else if (pk) "Ayarlar • PK • " + Config.sinifAd(this)
        else if (ko) "Ayarlar • Farm • KO Mobile" else "Ayarlar • Farm"
        for (v in normalSatirlar) v.visibility = if (genie) View.GONE else View.VISIBLE
        genieSatir.visibility = if (genie) View.VISIBLE else View.GONE
        genieMsTv.text = "${cfg.genieKilicMs} ms"
        sinifSatir.visibility = if (pk) View.VISIBLE else View.GONE
        val sn = Config.sinif(this)
        for ((kod, b) in sinifBtnler) {
            val sec = pk && kod == sn
            b.background = rounded(if (sec) ACCENT else LACI, 12)
            b.setTextColor(if (sec) 0xFF12161C.toInt() else Color.WHITE)
        }
        hpTv.text = "%${cfg.hpYuzde}"
        mpTv.text = "%${cfg.mpYuzde}"
        kilitTv.text = if (cfg.kilitler.isEmpty()) "🎯 Mob kilidi: yok (her moba vurur)"
        else "🎯 Kilitli mob: ${cfg.kilitler.size}"
        otoSwitch.isChecked = cfg.otoArayuz
        for ((a, e) in alanEdits) e.setText(a.get(cfg).toString())
        buildList()
        buildProfiles()
    }

    // ================= Basit ayar islemleri =================

    private fun yuzdeDegis(hp: Boolean, d: Int) {
        saveAll()
        val c = Config.load(this)
        if (hp) c.hpYuzde = (c.hpYuzde + d).coerceIn(10, 95)
        else c.mpYuzde = (c.mpYuzde + d).coerceIn(5, 95)
        c.save(this)
        refresh()
    }

    private fun oyunSec(kod: String) {
        if (Config.oyun(this) == kod && (kod != "ko" || Config.koMu(this)) && (kod != "rev" || Config.revMi(this)) && (kod != "nowa" || Config.nowaMi(this))) return
        saveAll()
        Config.oyunSec(this, kod)
        if (Config.load(this).points.isEmpty()) Config.hazirAyar(this)
        MacroService.instance?.oyunDegisti()
        toast(Config.oyunAd(this) + " seçildi")
        oyunSecBtn.postDelayed({ refresh(); lisansYenile() }, 150)
    }

    /** "Oyun seç" listesi: seçilen oyun için yeniden "Bağlan" gerekir */
    private fun oyunListesi() {
        val oyunlar = listOf("MykoMobile" to "myko", "RevengerOnline" to "rev", "NowaOnline" to "nowa", "KO Mobile" to "ko")
        AlertDialog.Builder(this)
            .setTitle("Oyun seç")
            .setItems(oyunlar.map { it.first }.toTypedArray()) { _, i ->
                val kod = oyunlar[i].second
                if (Config.oyun(this) != kod) sunucuOnayYaz(false)
                oyunSec(kod)
                lisansYenile()
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** KO Mobile otomatik bulunamazsa: yuklu uygulamalardan elle sec */
    @Suppress("DEPRECATION")
    private fun koPaketSec() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val liste = try {
            pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
        if (liste.isEmpty()) { toast("Uygulama listesi alınamadı"); return }
        AlertDialog.Builder(this)
            .setTitle("KO Mobile hangisi?")
            .setItems(liste.map { it.first }.toTypedArray()) { _, i ->
                Config.koPaketYaz(this, liste[i].second)
                MacroService.instance?.oyunDegisti()
                toast("${liste[i].first} seçildi")
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun genieModSec() {
        if (Config.genieMi(this)) return
        saveAll()
        val servis = MacroService.instance
        if (servis != null) servis.genieSecServis() else Config.genieSec(this)
        modFarmBtn.postDelayed({ refresh() }, 150)
    }

    private fun genieMsDegis(d: Int) {
        saveAll()
        val c = Config.load(this)
        c.genieKilicMs = (c.genieKilicMs + d).coerceIn(50, 2000)
        c.save(this)
        refresh()
    }

    private fun modSec(pk: Boolean, sinif: String? = null) {
        if (!Config.pazarMi(this) && !Config.genieMi(this) && Config.pkMi(this) == pk && (!pk || sinif == null || sinif == Config.sinif(this))) return
        saveAll()
        val servis = MacroService.instance
        if (servis != null) servis.modSec(pk, sinif) else Config.modDegistir(this, pk, sinif)
        // Servis ui thread'inde degistirir; kisa gecikmeyle yenile
        modFarmBtn.postDelayed({ refresh() }, 150)
    }

    private fun changeMinutes(d: Int) {
        saveAll()
        val c = Config.load(this)
        c.minutes = (c.minutes + d).coerceIn(10, 600)
        c.save(this)
        refresh()
    }

    private fun setSpeed(mod: Int) {
        saveAll()
        val c = Config.load(this)
        when (mod) {
            2 -> { // Seri: beklemesiz, molasiz
                c.minDelay = 50; c.maxDelay = 110; c.pauseChance = 0
                c.skMin = 0; c.skMax = 150
            }
            1 -> {
                c.minDelay = 120; c.maxDelay = 300; c.pauseChance = 2
                c.skMin = 150; c.skMax = 700
            }
            else -> {
                c.minDelay = 180; c.maxDelay = 550; c.pauseChance = 3
                c.skMin = 250; c.skMax = 1500
            }
        }
        c.save(this)
        refresh()
    }

    private fun toggleAdvanced() {
        val show = advBox.visibility != View.VISIBLE
        gelismisAcik = show
        advBox.visibility = if (show) View.VISIBLE else View.GONE
        advToggle.text = if (show) "Gelişmiş ayarlar  ▴" else "Gelişmiş ayarlar  ▾"
    }

    // ================= Zorunlu izinler (sırayla): 1) arka plan 2) kısıtlı ayarlar 3) erişilebilirlik =================

    private fun kisitliGerekli(): Boolean = Build.VERSION.SDK_INT >= 33
    private fun kisitliTamam(): Boolean =
        !kisitliGerekli() || accEnabled() || getSharedPreferences("genel", MODE_PRIVATE).getBoolean("kisitliOnay", false)

    private fun markaAd(): String {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        fun var_(vararg k: String) = k.any { m.contains(it) || b.contains(it) }
        return when {
            var_("xiaomi", "redmi", "poco") -> "xiaomi"
            var_("samsung") -> "samsung"
            var_("huawei", "honor") -> "huawei"
            var_("oppo", "realme", "oneplus") -> "oppo"
            var_("vivo", "iqoo") -> "vivo"
            else -> "diger"
        }
    }

    /** Markaya göre arka plan (pil / otomatik başlatma) anlatımı */
    private fun markaPilMetni(): String = when (markaAd()) {
        "xiaomi" -> "Xiaomi / Redmi / POCO:\n• Açılan pencerede 'İzin ver'e bas.\n• Sonra 'Marka ayarı'na bas: 'Otomatik başlatma'yı AÇ.\n• Uygulama bilgisi → Pil tasarrufu → 'Kısıtlama yok' seç."
        "samsung" -> "Samsung:\n• Açılan pencerede 'İzin ver'e bas.\n• Ayarlar → Pil → Arka plan kullanım sınırları → 'Hiç uyumayan uygulamalar'a Projeindir Bot'u ekle.\n• Uygulama bilgisi → Pil → 'Kısıtlanmamış' seç."
        "huawei" -> "Huawei / Honor:\n• Açılan pencerede 'İzin ver'e bas.\n• 'Marka ayarı'na bas: Uygulama başlatma → Projeindir Bot → 'Elle yönet' → üç seçeneği de AÇ."
        "oppo" -> "Oppo / Realme / OnePlus:\n• Açılan pencerede 'İzin ver'e bas.\n• 'Marka ayarı'na bas: Otomatik başlatmayı AÇ.\n• Uygulama bilgisi → Pil → 'Arka planda çalışmaya izin ver'."
        "vivo" -> "Vivo / iQOO:\n• Açılan pencerede 'İzin ver'e bas.\n• 'Marka ayarı'na bas: Arka planda başlatmayı AÇ.\n• Pil → 'Arka planda yüksek güç tüketimi'ne izin ver."
        else -> "• Açılan pencerede 'İzin ver'e bas.\n• Telefonunda pil tasarrufu / otomatik başlatma ayarı varsa Projeindir Bot için kısıtlamayı kaldır."
    }

    /** Markanın otomatik başlatma / pil sayfası (yoksa uygulama bilgisi) */
    private fun markaAyari() {
        val adaylar = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        for ((pk, cls) in adaylar) {
            try {
                startActivity(Intent().setComponent(ComponentName(pk, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast("Listede 'Projeindir Bot'u bul ve izin ver")
                return
            } catch (e: Exception) {
            }
        }
        openAppDetails()
    }

    private fun genelPref() = getSharedPreferences("genel", MODE_PRIVATE)
    private fun gizlilikUrl(): String = Lisans.yanUrl(this, "gizlilik.html") ?: "https://projeindir.com.tr/makro/gizlilik.html"

    /**
     * İzinler tamam değilse ana ekran yerine bu ekran gösterilir (şart). Üç adım SIRAYLA:
     * 1) pil / arka plan  2) kısıtlı ayarları kaldır  3) erişilebilirlik izni.
     * Üç adım da ekranda görünür; yalnızca sıradaki adımın düğmeleri aktiftir.
     * Erişilebilirlik ayarına İLK gidişte (2. veya 3. adımda) Google Play kuralı gereği açık bilgilendirme + onay penceresi çıkar.
     */
    private fun izinGuncelle() {
        if (!::izinScroll.isInitialized) return
        val pil = batteryOk()
        val kisitli = kisitliTamam()
        val acc = accEnabled()
        val tamam = pil && kisitli && acc
        izinScroll.visibility = if (tamam) View.GONE else View.VISIBLE
        anaScroll.visibility = if (tamam) View.VISIBLE else View.GONE
        if (tamam) {
            raporBilgisiGoster()
            reklamBaslat()   // reklam yalnızca ana ekran görünürken yüklenir
            return
        }
        val adim = if (!pil) 1 else if (!kisitli) 2 else 3
        val k = izinIcerik
        k.removeAllViews()
        k.addView(TextView(this).apply {
            text = "Projeindir Bot • Kurulum izinleri"
            textSize = 26f
            typeface = SERIF_BOLD
            setTextColor(ACCENT)
        })
        k.addView(TextView(this).apply {
            text = "3 adımı SIRAYLA tamamla. Sıradaki adımın düğmeleri açılır; adımlar bitmeden bot çalışmaz.\n" +
                "Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(12))
        })

        /** Bir adım kartı: tamamlandıysa ✅, sıradaysa düğmeler aktif, değilse soluk ve kapalı */
        fun adimKarti(no: Int, baslik: String, aciklama: String, ok: Boolean, dugmeler: List<Pair<String, () -> Unit>>) {
            val kart = card(k)
            val sirada = no == adim
            kart.alpha = if (ok || sirada) 1f else 0.45f
            if (sirada) kart.background = rounded(0xFF1E2A1E.toInt(), 16)
            kart.addView(cardTitle((if (ok) "✅ " else if (sirada) "👉 " else "") + "Adım $no/3 • $baslik"))
            if (ok) {
                kart.addView(label("Tamamlandı."))
                return
            }
            kart.addView(label(aciklama))
            for ((ad, is_) in dugmeler) {
                kart.addView(smallButton(ad) { if (sirada) is_() else toast("Önce ${adim}. adımı tamamla") }.apply {
                    isEnabled = sirada
                    alpha = if (sirada) 1f else 0.5f
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) })
            }
        }

        val pilDugmeler = ArrayList<Pair<String, () -> Unit>>()
        pilDugmeler.add("🔋 Pil izni ver" to {
            genelPref().edit().putInt("pilDeneme", genelPref().getInt("pilDeneme", 0) + 1).apply()
            fixBattery()
        })
        pilDugmeler.add("⚙ Marka ayarı (otomatik başlatma)" to { markaAyari() })
        // Bazı cihazlar bu izni hiç vermiyor: iki denemeden sonra kilitlenip kalmasın
        if (genelPref().getInt("pilDeneme", 0) >= 2) pilDugmeler.add("Bu telefonda verilemiyor, devam et" to {
            genelPref().edit().putBoolean("pilAtla", true).apply()
            izinGuncelle()
        })
        adimKarti(1, "Pil izni (arka planda kapanmasın)",
            "Telefon pil tasarrufu için botu arka planda kapatabilir. Bunu engelliyoruz.\n\n" + markaPilMetni(), pil, pilDugmeler)

        adimKarti(2, "Kısıtlamaları kaldır",
            if (!kisitliGerekli()) "Bu Android sürümünde gerekmiyor." else
                "Android 13 ve üstünde, Play Store dışından yüklenen uygulamalarda erişilebilirlik kilitlidir. Kilidi açmak için:\n\n" +
                "1) 'Erişilebilirliği bir kez dene'ye bas. Listede Projeindir Bot'a dokun; gri / 'kısıtlı ayar' uyarısı çıkarsa normaldir, geri gel.\n" +
                "2) 'Uygulama bilgisini aç'a bas → sağ üstteki ⋮ (üç nokta) → 'Kısıtlı ayarlara izin ver' → onayla.\n" +
                "3) Buraya dönüp 'Yaptım, devam'a bas.\n\n" +
                "Not: Bazı telefonlarda bu kilit yoktur. ⋮ menüsünde seçenek yoksa direkt 'Yaptım, devam'a bas.",
            kisitli, listOf(
                "① Erişilebilirliği bir kez dene" to { erisimOnayliAc() },
                "② Uygulama bilgisini aç" to { openAppDetails() },
                "✅ Yaptım, devam" to {
                    genelPref().edit().putBoolean("kisitliOnay", true).apply()
                    izinGuncelle()
                }))

        val accDugmeler = ArrayList<Pair<String, () -> Unit>>()
        accDugmeler.add("♿ Erişilebilirliği aç" to { erisimOnayliAc() })
        if (kisitliGerekli()) accDugmeler.add("↩ 2. adıma dön" to {
            genelPref().edit().putBoolean("kisitliOnay", false).apply()
            izinGuncelle()
        })
        adimKarti(3, "Erişilebilirlik izni",
            "Bot oyundaki tuşlara bu izinle dokunur.\n\n" +
                "• 'Erişilebilirliği aç'a bas, çıkan bilgilendirmeyi oku ve onayla.\n" +
                "• Listede 'Projeindir Bot'u bul (bazı telefonlarda 'Yüklü uygulamalar' / 'İndirilen uygulamalar' altında).\n" +
                "• Aç → 'İzin ver'.\n\nGri ve açılmıyorsa 2. adım tamamlanmamış demektir.",
            acc, accDugmeler)
        k.addView(smallButton("📄 Gizlilik politikası") { siteAc(Uri.parse(gizlilikUrl())) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    /**
     * Erişilebilirlik ayarına gitmeden önce açık bilgilendirme + onay (Google Play kuralı). Onay bir kez alınır;
     * "Kabul etmiyorum" derse ayara gidilmez.
     */
    private fun erisimOnayliAc() {
        if (genelPref().getBoolean("erisimOnay", false)) { openAccessibility(); return }
        AlertDialog.Builder(this)
            .setTitle("Erişilebilirlik izni hakkında")
            .setMessage(ERISIM_BILGI)
            .setCancelable(false)
            .setPositiveButton("Okudum, kabul ediyorum") { _, _ ->
                genelPref().edit().putBoolean("erisimOnay", true).putLong("erisimOnayZaman", System.currentTimeMillis()).apply()
                openAccessibility()
            }
            .setNegativeButton("Kabul etmiyorum") { _, _ ->
                toast("Bu izin olmadan bot çalışamaz. İstediğin zaman tekrar deneyebilirsin.")
            }
            .setNeutralButton("Gizlilik politikası") { _, _ -> siteAc(Uri.parse(gizlilikUrl())) }
            .show()
    }

    /** Tanılama raporu bilgisi: izinler bittikten sonra bir kez (erişilebilirlik bilgilendirmesinden ayrı) */
    private fun raporBilgisiGoster() {
        if (genelPref().getBoolean("raporBilgi", false)) return
        genelPref().edit().putBoolean("raporBilgi", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Tanılama raporları")
            .setMessage(RAPOR_BILGI)
            .setPositiveButton("Anladım", null)
            .setNeutralButton("Gizlilik politikası") { _, _ -> siteAc(Uri.parse(gizlilikUrl())) }
            .show()
    }

    // ================= Kurulum adimlari =================

    private fun openAccessibility() {
        toast("Listeden 'Projeindir Bot'u bul ve aç")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            toast("Sağ üstte ⋮ → Kısıtlı ayarlara izin ver")
        } catch (e: Exception) {
        }
    }

    private fun fixBattery() {
        // 1) Android pil optimizasyonu
        if (!batteryOk()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
                return
            } catch (e: Exception) {
            }
        }
        // 2) Xiaomi otomatik baslatma sayfasi (varsa)
        try {
            startActivity(Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ))
            toast("Listede 'Projeindir Bot'u aç")
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    @Suppress("DEPRECATION")
    private fun findGame(): Intent? {
        return try {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            if (Config.koMu(this)) {
                val secili = Config.koPaket(this)
                if (secili.isNotEmpty()) return pm.getLaunchIntentForPackage(secili)
            }
            val ko = Config.koMu(this)
            val hit = pm.queryIntentActivities(q, 0).firstOrNull {
                val pk = it.activityInfo.packageName
                val ad = it.loadLabel(pm).toString()
                if (ko) pk.contains("komobile", true) || ad.contains("ko mobile", true) || ad.contains("komobile", true)
                else if (Config.revMi(this)) pk.contains("revenger", true) || ad.contains("revenger", true)
                else if (Config.nowaMi(this)) pk.contains("nowa", true) || ad.contains("nowa", true)
                else pk.contains("myko", true) || ad.contains("myko", true)
            } ?: return null
            pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
        } catch (e: Exception) {
            null
        }
    }

    // ================= Buyuk baslat =================

    private fun startAll() {
        saveAll()
        if (!Lisans.gecerliSimdi()) {
            toast("Önce üyelik girişi yap")
            return
        }
        if (!accEnabled() || MacroService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("Önce izin gerekli")
                .setMessage("Kurulum kartındaki 1. adımı yap: \"İzin ver\" → listeden Projeindir Bot → aç.")
                .setPositiveButton("İzin ver") { _, _ -> openAccessibility() }
                .setNegativeButton("Kapat", null)
                .show()
            return
        }
        if (Config.load(this).points.isEmpty()) Config.hazirAyar(this)
        if (!ScreenSampler.running) {
            launchAfterCapture = true
            toast("Açılan pencerede \"Tüm ekran\"ı seç ve başlat")
            askCapture()
            return
        }
        launchGameAndStart()
    }

    private fun launchGameAndStart() {
        val game = findGame()
        if (game == null) {
            AlertDialog.Builder(this)
                .setMessage("${Config.oyunAd(this)} bulunamadı. Oyunu kendin aç, karakterin oyuna girince sol üstteki panelde ▶'a bas.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        try {
            startActivity(game)
        } catch (e: Exception) {
            toast("Oyun açılamadı"); return
        }
        // Bot otomatik başlamaz: kullanıcı oyunda ▶'a basınca başlar
        toast("Oyun açılıyor. Hazır olunca paneldeki ▶ ile botu başlat")
    }

    // ================= Gelismis bolum =================

    private fun buildAdvanced(box: LinearLayout) {
        // Hazir ayar
        val pre = card(box)
        pre.addView(cardTitle("Hazır ayar"))
        pre.addView(smallButton("⚡ Ekran ayarlarını yeniden yükle") {
            AlertDialog.Builder(this)
                .setMessage("Barlar, kutu butonu ve süreler ${Config.oyunAd(this)} için yeniden ayarlansın mı? Kaydettiğin tuşlar silinmez.")
                .setPositiveButton("Yükle") { _, _ ->
                    saveAll(); Config.hazirAyar(this); refresh(); toast("Yüklendi")
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        pre.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), dp(4), 0)
            text = "Kendi tuşlarını kaydetmek için oyunda panelde ⋯ menüsünü kullan."
        })

        // Tuslar
        val keys = card(box)
        keys.addView(cardTitle("Kayıtlı tuşlar"))
        keys.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, dp(6))
            text = "Skill'ler sırayla öncelik alır. ▲▼ ile sırala, kutucukla aç/kapat."
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keys.addView(listBox)

        // Sureler
        val times = card(box)
        times.addView(cardTitle("Süreler"))
        for ((grup, alanlar) in gruplar) {
            times.addView(TextView(this).apply {
                text = grup
                textSize = 14f
                setTextColor(ACCENT)
                setPadding(dp(4), dp(10), 0, dp(2))
            })
            for (a in alanlar) alanEdits.add(a to numField(times, a.label))
        }
        times.addView(smallButton("Kaydet") { saveAll(); toast("Kaydedildi") })

        // Profiller
        val prof = card(box)
        prof.addView(cardTitle("Profiller"))
        val pRow = row()
        profName = EditText(this).apply {
            hint = "Profil adı"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        pRow.addView(profName, weight1())
        pRow.addView(smallButton("Kaydet") {
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
        prof.addView(pRow)
        profBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        prof.addView(profBox)

        // Temizlik
        val clean = card(box)
        clean.addView(cardTitle("Temizlik"))
        clean.addView(smallButton("Tüm tuşları ve şablonları sil") {
            AlertDialog.Builder(this)
                .setMessage("Kayıtlı tüm tuşlar, bar noktaları ve kutu şablonları silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    saveAll()
                    val c = Config.load(this)
                    c.points.clear(); c.hp = null; c.mp = null; c.tgtBar = null
                    c.openT = null; c.collectT = null; c.collectT2 = null
                    c.save(this)
                    refresh()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        clean.addView(smallButton("Ekran okumayı durdur") {
            stopService(Intent(this, CaptureService::class.java))
        })
    }

    private fun numField(parent: LinearLayout, labelText: String): EditText {
        val r = row()
        r.addView(TextView(this).apply {
            text = labelText
            textSize = 14f
            setTextColor(Color.WHITE)
        }, weight1())
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.END
            setTextColor(Color.WHITE)
        }
        r.addView(e, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(r)
        return e
    }

    private fun trimFloat(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    private fun buildList() {
        listBox.removeAllViews()
        cdEdits.clear()
        onChecks.clear()
        if (cfg.points.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Henüz tuş yok."
                setTextColor(MUTED)
            })
            return
        }
        cfg.points.forEachIndexed { i, p ->
            val r = row()
            if (p.type == "skill") {
                val cb = CheckBox(this).apply { isChecked = p.on }
                onChecks[i] = cb
                r.addView(cb)
            }
            r.addView(TextView(this).apply {
                text = p.name
                textSize = 14f
                setTextColor(Color.WHITE)
            }, weight1())
            if (p.type == "skill" || (Config.pkMi(this) && p.type in listOf("hp_pot", "mp_pot", "minor", "heal", "buff"))) {
                r.addView(TextView(this).apply { text = "sn"; textSize = 12f; setTextColor(MUTED) })
                val e = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(trimFloat(p.cd))
                    gravity = Gravity.END
                    setTextColor(Color.WHITE)
                }
                cdEdits[i] = e
                r.addView(e, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            r.addView(smallButton("▲") { move(i, -1) })
            r.addView(smallButton("▼") { move(i, 1) })
            r.addView(smallButton("✕") { removeAt(i) })
            listBox.addView(r)
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
            profBox.addView(TextView(this).apply {
                text = "Kayıtlı profil yok."
                setTextColor(MUTED)
            })
            return
        }
        for (n in names) {
            val r = row()
            r.addView(TextView(this).apply {
                text = n
                textSize = 15f
                setTextColor(Color.WHITE)
            }, weight1())
            r.addView(smallButton("Yükle") {
                if (Config.loadProfile(this, n)) {
                    refresh(); toast("'$n' yüklendi")
                } else toast("Profil okunamadı")
            })
            r.addView(smallButton("Sil") {
                AlertDialog.Builder(this)
                    .setMessage("'$n' profili silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Config.deleteProfile(this, n); buildProfiles() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            })
            profBox.addView(r)
        }
    }

    /** Ekrandaki degerleri en guncel kayda yazar */
    private fun saveAll() {
        if (alanEdits.isEmpty()) return
        val latest = Config.load(this)
        for ((a, e) in alanEdits) {
            val v = e.text.toString().trim().toIntOrNull() ?: a.get(latest)
            a.set(latest, v.coerceIn(a.min, a.max))
        }
        if (latest.maxDelay <= latest.minDelay) latest.maxDelay = latest.minDelay + 1
        if (latest.pauseMax < latest.pauseMin) latest.pauseMax = latest.pauseMin
        if (latest.tgtMax < latest.tgtMin) latest.tgtMax = latest.tgtMin
        if (latest.skMax < latest.skMin) latest.skMax = latest.skMin

        val sameList = latest.points.size >= cfg.points.size &&
            cfg.points.indices.all { latest.points[it].x == cfg.points[it].x && latest.points[it].y == cfg.points[it].y }
        if (sameList) {
            for ((i, e) in cdEdits) {
                val v = e.text.toString().replace(',', '.').toFloatOrNull() ?: continue
                if (latest.points[i].type in listOf("skill", "hp_pot", "mp_pot", "minor", "heal", "buff")) latest.points[i].cd = v.coerceIn(0f, 3600f)
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
            afterCapture(true)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    private fun afterCapture(ok: Boolean) {
        if (autoMode) {
            // Panelden gelindi: oyuna geri don, servis kendisi baslatir
            autoMode = false
            moveTaskToBack(true)
            return
        }
        if (launchAfterCapture) {
            launchAfterCapture = false
            if (ok) {
                // Yakalama servisinin acilmasi icin kisa bekle
                startBtn.postDelayed({ launchGameAndStart() }, 700)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode != RESULT_OK || data == null) {
            toast("İzin verilmedi")
            afterCapture(false)
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
        )
        afterCapture(true)
    }
}
