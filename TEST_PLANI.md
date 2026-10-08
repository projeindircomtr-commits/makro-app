# Projeindir Bot — Test Sunucusu Zorlama Testi Planı

Bu doküman, yönetimin onayladığı **test sunucusu** çalışması içindir. Amaç: botun oyunun normal farm
akışında neler yapabildiğini, hangi koşullarda nasıl davrandığını ve nerede durduğunu **ölçmek ve raporlamak**.

## 0. Kapsam ve sınırlar

**Kapsamda:** uzun süreli kullanım, farklı basış hızları, eş zamanlı hesaplar, kontrol pencereleri,
bağlantı kopması/geri gelmesi, duraklama/izin kaybı/servis yeniden başlama, farm akışındaki uç durumlar.

**Kapsam dışı (hiçbir koşulda yapılmaz):**
- Bot tespitinden gizlenme ya da kaçınma
- Cihaz/uygulama kimliği taklidi
- Güvenlik mekanizmalarını aşma
- Sunucuya yük bindirme, oyun açıklarını istismar etme

Uyarı, kısıtlama ya da atılma görülürse **o seviyede durulur ve kaydedilir**; aşmak için ayar değiştirilmez.

## 1. Kurulum

1. Admin panelinde her test hesabı için **Yeni üye** oluştur (örn. `ugtest1` … `ugtest6`).
2. Hesabın **Bölümler** kısmında **🌾 Farm** ve **🧪 Test** işaretli olsun, **Bölümleri kaydet**.
   - 🧪 Test yetkisi **yalnızca bu hesaplarda** test modunu açar. Yönetici olmak yetmez.
3. Süre ekle (test boyunca yetecek kadar). Test bitince **Süreyi 0 yap** ya da **Hesabı kapat**.
4. Test hesaplarının listesi yönetime verilir.
5. Uygulamada giriş yap, tuşları kaydet (Saldırı/Mob seç, HP/MP pot, skiller), gerekirse mobu kilitle.
6. Oyunda panel ⋯ → **🧪 Test modu** → **Test modu: AÇIK**.

Test modu açıkken: panelde **🧪TEST** yazar, başlangıçta uyarı çıkar, oturum günlüğü kaydedilir.

## 2. Test modu ayarları (⋯ → 🧪 Test modu)

| Ayar | Ne yapar |
|---|---|
| Test modu | Açıkken oturum günlüğü ve aşağıdaki ayarlar etkin |
| Hız | Normal ayar / Basamak 1–4. Basamak 4 = alt sınır **A** |
| Alt sınır A (ms) | Basış aralığının inebileceği en düşük değer. Hiçbir basamak A'nın altına inmez. Varsayılan 700 |
| Test süresi | Ayardaki süre ya da 1, 4, 12, 24, 48 saat (normalde üst sınır 10 saat) |
| İlerleme bekçisi | N dakika hiç mob/kutu/pot ilerlemesi yoksa botu kontrollü durdurur |
| Bağlantı bekleme | Kopma penceresi görülünce en fazla N sn bekler; pencere kaybolursa devam eder, gelmezse durur |
| Olay işaretle | Elle tetiklenen durumları (ölüm, şehir, ışınlanma, pop-up…) günlüğe zaman damgasıyla işler |
| Raporu kopyala / paylaş | Rapor metnini panoya alır ya da paylaşım menüsünü açar |
| Günlüğü temizle | Yeni test öncesi sıfırlama |

Hız basamakları (ms, minimum/maksimum bekleme): 1 → 350–900, 2 → 250–600, 3 → 180–400, 4 → A–A+120.
Her basamakta A uygulanır (KO'daki 700 ms kuralı varsayılan A=700 ile aynen korunur).

## 3. Günlük ve rapor

Her satır: `tarih saat | geçen süre | TİP | neden | ayrıntı`.

| Tip | Anlamı |
|---|---|
| OTURUM_BASLADI / OTURUM_BITTI | Ayar özeti ve durma nedeni + özet |
| DAKIKA | Dakika başına basış, atak, pot/destek, kutu, kesilen/toplanan/pot farkı |
| EYLEM | Dokunuş kategorisi (ilk 150 ve her 100.; hepsi dakika sayacında) |
| HEDEF_ALINDI / HEDEF_KAYBI | Mob seçimi ve hedef kaybı (süre ms) |
| KILIT_RED / KILIT_BEKLE | Kilitli olmayan mobun bırakılması, kilitli mob yok bekleme |
| KUTU_OPEN / KUTU_COLLECT / KUTU_ZAMAN_ASIMI | Kutu akışı ve zaman aşımı |
| DURAKLAMA / TOPARLANDI | Ekran kapalı, oyun arka planda, görüntü yok ve geri dönüş süresi |
| BAGLANTI_KOPTU / TOPARLANDI / GELMEDI | Oyun içi kopma penceresi ve toparlanma süresi |
| AG_KOPTU / AG_GELDI / AG_TURU | Telefon ağ durumu, kopukluk süresi, wifi ↔ mobil |
| OLUM / CAN_DUSUK | KO'da can 0 ya da uzun süre düşük can |
| KONTROL_PENCERESI / KONTROL_CEVAPLANDI | MykoMobile bot kontrolü ve pencere süresi |
| DONMA / IZIN_KAYBI / HATA / HATA_PATLAMASI | Yanıt vermeme, ekran paylaşımı kaybı, hata ve hata yağmuru |
| ILERLEME_YOK / SURE_BITTI | Bekçi ya da süre ile kontrollü durma |
| SERVIS_BASLADI / SERVIS_DURDU / SERVIS_KESINTI / ONCEKI_OTURUM_KESILDI | Servis yaşam döngüsü |
| ISARET | Test uzmanının elle işaretlediği olay |

**Toparlanma süreleri:** DURAKLAMA→TOPARLANDI (`duraklama_ms`), BAGLANTI_KOPTU→BAGLANTI_TOPARLANDI (`sure_ms`),
AG_KOPTU→AG_GELDI (`kopukluk_ms`), KONTROL_PENCERESI→KONTROL_CEVAPLANDI (`pencere_ms`).

Günlük telefonda uygulamanın özel dizininde tutulur (3 MB üstünde döner). Rapor ⋯ → 🧪 Test modu → **Raporu paylaş**.

## 4. Test matrisi

Her test için kaydedilecekler: başlangıç koşulları, ayarlar, çalışma süresi, dakika başına basış, kontrol/uyarılar,
atılma/kısıtlama, bağlantı olayları, sona erme nedeni, zaman damgaları, sonuç/gözlem. Hepsi rapordan okunur.

| No | Test | Yöntem | Ölçülen |
|---|---|---|---|
| T1 | Uzun süreli kullanım | Hız normal; 1 → 4 → 12 → 24 saat (S'ye kadar) | İlk olay zamanı, ilk saat ile son saat arası basış farkı |
| T2 | Hız merdiveni | Normal → 1 → 2 → 3 → 4; her basamak 30 dk, 3 tekrar, aynı harita ve mob | Her basamakta uyarı/kısıtlama/atılma var mı |
| T3 | Süre × hız | Süre: 30 dk, 2, 6, 12 saat × hız: Normal, 2, 4 | Hangi hücrede ilk olay çıktı |
| T4 | Eş zamanlı hesaplar | N = 1, 2, 4, 6 hesap, 2 saat | Tek hesaba göre fark; ağ ortaklığı notu |
| T5 | Kontrol pencereleri | T1–T4 sırasında | Çıkış sıklığı, ilk çıkış zamanı, cevap süresi (ort./en uzun) |
| T6 | Bağlantı | Kontrollü kopma: 10 sn, 60 sn, 5 dk × 5 tekrar (elle işaretle) | Oyunun yeniden bağlanması, botun toparlanma süresi |
| T7 | Kararlılık | T1 sırasında 5 dk'da bir | Basış/dk trendi, 24. saat ile 1. saat farkı |
| T8 | Tutarlılık | T2'nin kritik iki basamağı 3 kez, farklı gün ve hesap | Sonuçlar tutarlı mı |

Kural: uyarı/kısıtlama çıkarsa o hücrede durulur; aşılmaz. Yönetimin verdiği A ve S sınırları aşılmaz.

## 5. Ek senaryolar (normal farm akışının uç durumları)

Kodda gözlemlenen boşluklar kapatıldı; her biri bu senaryolarla doğrulanır.

| No | Senaryo | Beklenen davranış / bakılacak olay |
|---|---|---|
| F1 | **KO bağlantı kopma penceresi** ("Disconnected from server") | BAGLANTI_KOPTU; test modunda bekleme ve TOPARLANDI, aksi hâlde kontrollü durma |
| F2 | **KO'da ölüm / uzun düşük can** | OLUM (can 0 → 5 sn) ya da CAN_DUSUK; bot durur |
| F3 | **Duraklama sonrası** (oyunu arka plana al, ekranı kapat, 2 dk sonra dön) | DURAKLAMA→TOPARLANDI; haksız "can düşük/ölmüş" durması olmamalı |
| F4 | **Süre sayacı** (ekran kapalı/uykuda) | Gerçek süre = ayarlanan süre (elapsedRealtime) |
| F5 | **Donma bekçisi** (bildirim çubuğu çek, çağrı al) | Bot 25 sn yanıtsız kalırsa DONMA ile güvenli durur |
| F6 | **Pop-up'lar** (envanter dolu, ticaret/parti daveti, PM, seviye atlama) | Pop-up algılama yok: ISARET ile işaretle; ilerleme bekçisi ILERLEME_YOK ile durdurur |
| F7 | **Kutu uç durumları** (Collect All çıkmadı, birden çok kutu, envanter dolu) | KUTU_ZAMAN_ASIMI, toparlanma; kutu akışı BOS'a döner |
| F8 | **Mob kilidi** (kilitli mob yok, aynı isimli birden çok mob, oyuncu/NPC hedefi) | KILIT_RED, KILIT_BEKLE; kilitli olmayan moba vurulmaz |
| F9 | **Pot/mana biter** | Basış sayacı ve CAN_DUSUK/ILERLEME_YOK ile durma |
| F10 | **Yeniden başlatma** (30 kez durdur/başlat; servisi öldür; ekran paylaşımı iznini kapat) | ONCEKI_OTURUM_KESILDI, IZIN_KAYBI (5 sn kör dokunma yok), temiz başlangıç |
| F11 | **Ağ değişimi** (wifi ↔ mobil, 30+ dk ağsız) | AG_TURU, AG_KOPTU/AG_GELDI; lisans kontrolü ağ yokken botu durdurmaz |
| F12 | **Harita/durum** (şehir/güvenli bölge, ışınlanma, harita yükleme, gece/hava, kamera açısı) | ISARET + ILERLEME_YOK; isim/bar okuma etkisi gözlenir |

## 6. Botun kendiliğinden durduğu durumlar

| Durum | Olay | Not |
|---|---|---|
| Ayarlanan süre doldu | SURE_BITTI | |
| Üyelik süresi/doğrulama | (toast) | Ağ yokken durmaz |
| Adım döngüsü 25 sn yanıtsız | DONMA | |
| Ekran paylaşımı çalışırken 5 sn kapandı | IZIN_KAYBI | Kör dokunma yok |
| 60 sn içinde 8 hata | HATA_PATLAMASI | |
| KO can 0 (5 sn) / uzun süre düşük can | OLUM / CAN_DUSUK | Bar hiç okunmadıysa devreye girmez |
| Kopma penceresi (normal mod) | BAGLANTI_KOPTU | Test modunda bekleme + toparlanma |
| İlerleme yok (test modu) | ILERLEME_YOK | |

## 7. Bilinen sınırlar (dürüst not)

- Oyunun **sunucu tarafındaki** uyarı/kısıtlamalarını bot göremez; bunlar yönetimin kayıtlarıyla eşleştirilmelidir
  (günlükteki zaman damgaları bunun için tutulur).
- Pop-up, harita yükleme ve şehir durumları için ayrı algılama yoktur; **ISARET** ve **İlerleme bekçisi** kullanılır.
- KO bağlantı penceresi şablonu tek ekran görüntüsünden üretildi; farklı çözünürlükte eşleşme **test edilmelidir** (F1).
- Bu doküman ve kod, test ortamında doğrulanana kadar "hazır" sayılmaz; her senaryo sonucu rapora işlenir.

## 8. Sonuç tablosu (şablon)

| Test | Hesap | Ayar | Süre | Basış/dk (ort./min/maks.) | Uyarı/kontrol | Atılma/kısıtlama | Bağlantı olayı | Bitiş nedeni | Gözlem |
|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | |


## 9. Çanta rotası projesi (MykoMobile, yalnızca Test yetkili hesap)

Amaç: çanta dolunca botun kendisi Inn Hostes'e gidip eşyaları bankaya bırakması ve slotuna dönmesi.
**Durum: ilk sürüm yazıldı, telefonda henüz denenmedi.** Her aşama ayrı doğrulanır; doğrulanamayan aşamada bot
kör basış yapmaz, kontrollü durur ve nedenini günlüğe yazar.

### 9.1 Nasıl çalışır
1. **Canlı koordinat:** HP/MP barının altındaki X,Y, ekran görüntüsünden ML Kit OCR ile okunur (4 Hz). Ani sıçramalar
   (okuma hatası) iki kez aynı değer gelmeden kabul edilmez.
2. **Rota kaydı:** Test menüsünden kayıt başlatılır, slottan Inn Hostes'e elle yürünür, kayıt bitirilir. Bot koordinatları
   3 birimde bir iz olarak saklar (satır satır "çizgi").
3. **Yürüme:** Bot oyunun joystick'ini kendisi sürer. İlk adımda kısa bir **kalibrasyon** yapar (ileri, sonra ileri+sağ):
   sağ itmenin yönü hangi tarafa çevirdiğini ölçer. Sonra izdeki sıradaki noktaya doğru yön hatasına göre direksiyon verir.
   Takılırsa kısa geri + dönüş dener (3 deneme), süre aşımı 150 sn.
4. **Inn Hostes:** Open butonunu şablonla bulup basar → sağdaki "Inn Hostes Open" → banka penceresinin gelmesini
   doğrular (başlık şablonu).
5. **Boşaltma:** Envanterin ilk N satırında (varsayılan 3; son satır kalır) dolu hücreleri bulur (boş hücre düz koyu),
   eşyaya **iki kez dokunur**, istiflenebilirde çıkan miktar penceresinde **Confirm**'e basar.
6. **Kapat ve dön:** X ile kapatır (kapandığını doğrular), giderken izlediği yolu tersten izleyip başlangıç noktasına döner.
7. **Tetik:** Collect All kapanmazsa (envanter dolu) otomatik; ayrıca menüden elle çalıştırılabilir.

### 9.2 Aşamalı doğrulama (bu sırayla)
| No | Test | Menü | Beklenen |
|---|---|---|---|
| A1 | Koordinat okuma | 📍 Konum testi | Okunan değer ekrandakiyle aynı (günlükte KONUM/KONUM_OKUNAMADI) |
| A2 | Rota kaydı | 🔴 Rota kaydını başlat → yürü → ⏹ bitir | ROTA_KAYDEDILDI, nokta sayısı ve uzunluk makul |
| A3 | Yürüme | 🚶 Yürüme testi | KALIBRASYON, YURU_BITTI (hedefe varıldı); izden sapma ve süre not edilir |
| A4 | Banka | NPC'nin yanında 🎒 Çanta rotasını çalıştır | ROTA_OPEN, ROTA_BANKA_ACIK, ROTA_BOSALTILDI (taşınan sayısı), ROTA_TAMAM |
| A5 | Otomatik döngü | Otomatik çanta rotası: AÇIK + farm | CANTA_DOLU → CANTA_ROTA_TETIK → ROTA_TAMAM → farma devam |

### 9.3 Olay sözlüğü (ek)
KONUM, KONUM_OKUNAMADI, KONUM_SUPHELI, ROTA_KAYIT, ROTA_KAYDEDILDI, ROTA_SILINDI, ROTA_BASLADI, KALIBRASYON,
ROTA_GIDIS, YURU_BITTI, YURU_HATA, YURU_TAKILDI, YURU_TESTI_TAMAM, ROTA_OPEN, ROTA_MENU, ROTA_BANKA_ACIK, ROTA_TEKRAR,
ROTA_MIKTAR, ROTA_BOSALTILDI, ROTA_KAPAT, ROTA_DONUS, ROTA_TAMAM, ROTA_IPTAL, CANTA_DOLU, CANTA_ROTA_TETIK.

### 9.4 Bilinen riskler (dürüst not)
- **OCR doğruluğu doğrulanmadı.** Rakam okuma ML Kit'e bırakıldı; sıkıştırılmış videodan şablon tabanlı okuma güvenilir
  çıkmadığı için bu yol seçildi. A1 başarısızsa günlükteki ham OCR metni ile yeniden ayar yapılır.
- **Joystick'i botun sürmesi** bu telefonda ilk kez deneniyor. Android, bot jesti sırasında başka dokunuşu iptal edebilir;
  rota sırasında farm adımları durdurulur. Jest sürekli iptal olursa (6 kez) rota kontrollü durur.
- Dönüş yönü **kalibrasyonla** ölçülür; denetleyici simülasyonda 0,4–3,5 rad/sn dönüş hızlarında hedefe vardı, gerçek
  oyunda **sapma ve çarpışma** olabilir (kalabalık, duvar). Takılma kurtarması 3 denemeyle sınırlı.
- Banka penceresi ve envanter konumları tek ekran çözünürlüğünden (2576×1159) oranlanmıştır; farklı cihazda **A4 ile
  doğrulanmalıdır**. Miktar penceresi şablonu videodan çıkarıldı.
- Eşyaya "iki kez dokun = bankaya" bilgisi kullanıcıdan alındı; her eşya için taşınıp taşınmadığı raporda sayılır.
- Banka dolarsa ya da eşya kilitliyse bot eşyayı geçer, sayıyı raporlar.
