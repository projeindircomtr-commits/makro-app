<?php
// Cron ile calisir: GitHub'daki en son Release'i kontrol eder, yeni surum varsa
// APK'yi indirip panelde secili klasore koyar. Tarayicidan acilamaz.
if (PHP_SAPI !== 'cli') { http_response_code(403); exit; }
define('MAKRO', 1);
require __DIR__ . '/ortak.php';

const REPO = 'projeindircomtr-commits/makro-app';
const APK_ADI = 'ProjeindirBot.apk';

function kayit(string $s): void {
    $f = GIZLI . '/cek.log';
    if (is_file($f) && filesize($f) > 100000) @rename($f, $f . '.eski');
    file_put_contents($f, date('Y-m-d H:i:s') . "  $s\n", FILE_APPEND);
}

/** URL'yi indirir. $dosya verilirse oraya yazar, yoksa icerigi dondurur. Hata: null */
function indir(string $url, ?string $dosya = null): ?string {
    $ch = curl_init($url);
    $fp = null;
    curl_setopt_array($ch, [
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 5,
        CURLOPT_CONNECTTIMEOUT => 20,
        CURLOPT_TIMEOUT => 300,
        CURLOPT_USERAGENT => 'ProjeindirBot-Cek',
        CURLOPT_HTTPHEADER => ['Accept: application/vnd.github+json'],
    ]);
    if ($dosya !== null) {
        $fp = fopen($dosya, 'wb');
        if (!$fp) return null;
        curl_setopt($ch, CURLOPT_FILE, $fp);
    } else {
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    }
    $sonuc = curl_exec($ch);
    $kod = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $hata = curl_error($ch);
    curl_close($ch);
    if ($fp) fclose($fp);
    if ($sonuc === false || $kod !== 200) {
        kayit("HATA indirme ($kod) $url $hata");
        return null;
    }
    return $dosya !== null ? $dosya : (string)$sonuc;
}

// Ayni anda iki kere calismasin
$kilit = fopen(GIZLI . '/cek.lock', 'c');
if (!$kilit || !flock($kilit, LOCK_EX | LOCK_NB)) exit;

try {
    sema_guncelle();

    $json = indir('https://api.github.com/repos/' . REPO . '/releases/latest');
    if ($json === null) exit;
    $r = json_decode($json, true);
    if (!is_array($r) || !preg_match('~^v(\d+)$~', (string)($r['tag_name'] ?? ''), $m)) {
        kayit('HATA release okunamadi: ' . substr($json, 0, 200));
        exit;
    }
    $surum = (int)$m[1];
    $mevcut = (int)ayar_al('en_yeni_surum', '0');
    if ($surum <= $mevcut) exit;   // zaten guncel

    $url = null;
    foreach (($r['assets'] ?? []) as $a) {
        if (($a['name'] ?? '') === APK_ADI) { $url = (string)$a['browser_download_url']; break; }
    }
    if ($url === null) { kayit("HATA v$surum icinde " . APK_ADI . ' yok'); exit; }

    $rel = apk_klasor_al();
    $klasor = dirname(__DIR__) . '/' . $rel;
    if (!is_dir($klasor)) {
        if (!mkdir($klasor, 0755, true)) { kayit("HATA klasor olusturulamadi: $klasor"); exit; }
        file_put_contents($klasor . '/index.html', '');
    }
    $hedef = $klasor . '/' . APK_ADI;
    $gec = $hedef . '.tmp';
    if (indir($url, $gec) === null) { @unlink($gec); exit; }

    $boyut = (int)filesize($gec);
    $f = fopen($gec, 'rb'); $imza = $f ? fread($f, 4) : ''; if ($f) fclose($f);
    if ($boyut < 50000 || $boyut > 60 * 1024 * 1024 || $imza !== "PK\x03\x04") {
        @unlink($gec);
        kayit("HATA v$surum dosyasi gecersiz ($boyut bayt)");
        exit;
    }
    @chmod($gec, 0644);
    if (!rename($gec, $hedef)) { @unlink($gec); kayit('HATA dosya yerine konamadi'); exit; }

    ayar_yaz('apk_url', 'https://projeindir.com.tr/' . $rel . '/' . APK_ADI);
    ayar_yaz('en_yeni_surum', (string)$surum);
    ayar_yaz('yeni_surum_zaman', (string)time());
    kayit("OK v$surum yuklendi ($boyut bayt) -> $rel/" . APK_ADI);
} catch (Throwable $e) {
    kayit('HATA ' . $e->getMessage());
}
