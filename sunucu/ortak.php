<?php
// Ortak yardimcilar. Dogrudan acilmaz.
if (!defined('MAKRO')) { http_response_code(403); exit; }

$CFG = require __DIR__ . '/config.php';
define('GIZLI', __DIR__ . '/gizli');

function db(): PDO {
    static $pdo = null;
    global $CFG;
    if ($pdo === null) {
        $pdo = new PDO(
            'mysql:host=' . $CFG['db_host'] . ';dbname=' . $CFG['db_name'] . ';charset=utf8mb4',
            $CFG['db_user'], $CFG['db_pass'],
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
        );
    }
    return $pdo;
}

function e($s): string { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

function rastgele_sifre(int $n = 8): string {
    $h = 'abcdefghjkmnpqrstuvwxyz23456789';
    $s = '';
    for ($i = 0; $i < $n; $i++) $s .= $h[random_int(0, strlen($h) - 1)];
    return $s;
}

function kalan_yazi(int $bitis): string {
    $k = $bitis - time();
    if ($k <= 0) return 'Süresi doldu';
    $g = intdiv($k, 86400);
    $s = intdiv($k % 86400, 3600);
    return $g > 0 ? "$g gün $s saat" : "$s saat " . intdiv($k % 3600, 60) . " dk";
}

/** Mesaj ve surum ozelligi icin gereken tablo/sutunlari bir kere ekler */
function sema_guncelle(): void {
    $kilit = GIZLI . '/sema6.lock';
    if (file_exists($kilit)) return;
    $pdo = db();
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN token VARCHAR(64) NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN surum INT NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN son_hata VARCHAR(500) NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN son_hata_zaman INT NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN yonetici TINYINT NOT NULL DEFAULT 0"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN ozellik VARCHAR(40) NULL"); } catch (Throwable $e) {}
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_mesajlar (
        id INT AUTO_INCREMENT PRIMARY KEY,
        uye_id INT NULL,
        metin VARCHAR(500) NOT NULL,
        zaman INT NOT NULL,
        INDEX (uye_id)
    ) CHARACTER SET utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_ayar (
        anahtar VARCHAR(40) PRIMARY KEY,
        deger TEXT NOT NULL
    ) CHARACTER SET utf8mb4");
    file_put_contents($kilit, date('c'));
}

const OZELLIKLER = ['farm', 'pk', 'pazar', 'genie'];

/** "farm,pk" gibi metni temizler: sadece bilinen bolumler kalir (farm da artik panelden verilir) */
function ozellik_temizle(string $s): string {
    $ist = array_map('trim', explode(',', strtolower($s)));
    $son = [];
    foreach (OZELLIKLER as $o) { if (in_array($o, $ist, true)) $son[] = $o; }
    return implode(',', $son);
}

/** Yeni eklenen uyelerin baslayacagi bolumler (admin panelinden secilir) */
function varsayilan_ozellik(): string {
    return ozellik_temizle(ayar_al('varsayilan_ozellik', 'farm'));
}

function ayar_al(string $k, string $vars = ''): string {
    $q = db()->prepare('SELECT deger FROM makro_ayar WHERE anahtar = ?');
    $q->execute([$k]);
    $v = $q->fetchColumn();
    return $v === false ? $vars : (string)$v;
}

function ayar_yaz(string $k, string $v): void {
    db()->prepare('REPLACE INTO makro_ayar (anahtar, deger) VALUES (?, ?)')->execute([$k, $v]);
}

/** APK'nin yuklenecegi klasor (site kokune gore, ornek: makro/indir). Gecersizse varsayilan */
function apk_klasor_al(): string {
    $k = trim(ayar_al('apk_klasor', 'makro/indir'), " \t\n\r\0\x0B/");
    if (!preg_match('~^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$~', $k) || in_array('gizli', explode('/', strtolower($k)), true)) {
        return 'makro/indir';
    }
    return $k;
}

/** Uygulama surumu yetersizse guncelleme bilgisini dondurur, yoksa null */
function surum_engeli(int $surum): ?array {
    $min = (int)ayar_al('min_surum', '0');
    if ($min <= 0 || $surum >= $min) return null;
    return [
        'ok' => 0,
        'guncelle' => ayar_al('apk_url', ''),
        'mesaj' => ayar_al('surum_mesaj', '') ?: 'Yeni sürüm çıktı. Devam etmek için güncelle.',
        'min' => $min,
    ];
}

/**
 * GitHub'daki en son Release'i kontrol eder; yeni surum varsa APK'yi indirip
 * panelde secili klasore koyar. Admin panelindeki butonla calistirilir.
 * Sonucu panelde gosterilecek mesaj olarak dondurur.
 */
function github_cek(): string {
    $repo = 'projeindircomtr-commits/makro-app';
    $apkAdi = 'ProjeindirBot.apk';
    @set_time_limit(300);

    $istek = function (string $url, ?string $dosya = null) {
        $ch = curl_init($url);
        $fp = null;
        curl_setopt_array($ch, [
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS => 5,
            CURLOPT_CONNECTTIMEOUT => 20,
            CURLOPT_TIMEOUT => 240,
            CURLOPT_USERAGENT => 'ProjeindirBot-Panel',
            CURLOPT_HTTPHEADER => ['Accept: application/vnd.github+json'],
        ]);
        if ($dosya !== null) {
            $fp = fopen($dosya, 'wb');
            if (!$fp) return [false, 0, 'dosya acilamadi'];
            curl_setopt($ch, CURLOPT_FILE, $fp);
        } else {
            curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        }
        $s = curl_exec($ch);
        $kod = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $hata = curl_error($ch);
        curl_close($ch);
        if ($fp) fclose($fp);
        return [$s, $kod, $hata];
    };

    try {
        [$json, $kod, $hata] = $istek("https://api.github.com/repos/$repo/releases/latest");
        if ($json === false || $kod !== 200) {
            return $kod === 404 ? "GitHub'da henüz yayınlanmış sürüm yok. Commit mesajına [yayinla] ekleyip derlemenin bitmesini bekle."
                                : "GitHub'a ulaşılamadı (kod $kod) $hata";
        }
        $r = json_decode((string)$json, true);
        if (!is_array($r) || !preg_match('~^v(\d+)$~', (string)($r['tag_name'] ?? ''), $m)) {
            return 'GitHub cevabı okunamadı.';
        }
        $surum = (int)$m[1];
        $mevcut = (int)ayar_al('en_yeni_surum', '0');
        if ($surum <= $mevcut) return "Zaten güncel (sürüm $mevcut). GitHub'daki en son: $surum.";

        $url = null;
        foreach (($r['assets'] ?? []) as $a) {
            if (($a['name'] ?? '') === $apkAdi) { $url = (string)$a['browser_download_url']; break; }
        }
        if ($url === null) return "Sürüm $surum içinde $apkAdi bulunamadı.";

        $rel = apk_klasor_al();
        $klasor = dirname(__DIR__) . '/' . $rel;
        if (!is_dir($klasor)) {
            if (!mkdir($klasor, 0755, true)) return 'Klasör oluşturulamadı.';
            file_put_contents($klasor . '/index.html', '');
        }
        $hedef = $klasor . '/' . $apkAdi;
        $gec = $hedef . '.tmp';
        [$ok, $kod, $hata] = $istek($url, $gec);
        if ($ok === false || $kod !== 200) { @unlink($gec); return "APK indirilemedi (kod $kod) $hata"; }

        $boyut = (int)filesize($gec);
        $f = fopen($gec, 'rb'); $imza = $f ? fread($f, 4) : ''; if ($f) fclose($f);
        if ($boyut < 50000 || $boyut > 60 * 1024 * 1024 || $imza !== "PK\x03\x04") {
            @unlink($gec);
            return "İndirilen dosya geçersiz ($boyut bayt).";
        }
        @chmod($gec, 0644);
        if (!rename($gec, $hedef)) { @unlink($gec); return 'Dosya yerine konamadı.'; }

        apk_klasor_koru($klasor);
        ayar_yaz('apk_url', indirme_sayfasi_url());
        ayar_yaz('en_yeni_surum', (string)$surum);
        ayar_yaz('yeni_surum_zaman', (string)time());
        return "✅ Sürüm $surum siteye alındı (" . round($boyut / 1048576, 1) . " MB). Eskileri durdurmak için \"zorunlu yap\"a bas.";
    } catch (Throwable $e) {
        return 'Hata: ' . $e->getMessage();
    }
}

// ================= Indirme sayfasi (projeindir.com.tr/bot/) =================
const APK_ADI = 'ProjeindirBot.apk';

/** Uyelerin gordugu indirme sayfasinin adresi (APK'nin dogrudan linki gizli kalir) */
function indirme_sayfasi_url(): string {
    return 'https://' . ($_SERVER['HTTP_HOST'] ?? 'projeindir.com.tr') . '/bot/';
}

/** APK klasorune dogrudan erisimi kapatir; dosya sadece indirme sayfasindan verilir */
function apk_klasor_koru(string $klasor): void {
    if (!is_dir($klasor)) return;
    $h = $klasor . '/.htaccess';
    if (!is_file($h)) {
        file_put_contents($h, "<IfModule mod_authz_core.c>\nRequire all denied\n</IfModule>\n<IfModule !mod_authz_core.c>\nDeny from all\n</IfModule>\n");
    }
    if (!is_file($klasor . '/index.html')) file_put_contents($klasor . '/index.html', '');
}
