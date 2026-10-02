<?php
// GitHub Actions derlemeden sonra APK'yi buraya yukler (yukleme anahtariyla korunur).
// min_surum'a dokunmaz: eski surumleri durdurmak icin admin panelinde "zorunlu yap"a basarsin.
define('MAKRO', 1);
require __DIR__ . '/ortak.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function son(int $kod, array $a): void {
    http_response_code($kod);
    echo json_encode($a, JSON_UNESCAPED_UNICODE);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') son(405, ['ok' => 0, 'mesaj' => 'Sadece POST']);

try {
    sema_guncelle();

    // 1) Anahtar (veritabaninda sadece ozeti saklanir)
    $hash = ayar_al('yukle_anahtar_hash', '');
    $anahtar = (string)($_POST['anahtar'] ?? '');
    if ($hash === '' || $anahtar === '' || !hash_equals($hash, hash('sha256', $anahtar))) {
        sleep(2);
        son(403, ['ok' => 0, 'mesaj' => 'Anahtar gecersiz']);
    }

    // 2) Surum (eskisinin ustune yazilmaz, ayni surum tekrar yuklenebilir)
    $surum = (int)($_POST['surum'] ?? 0);
    if ($surum < 1) son(400, ['ok' => 0, 'mesaj' => 'Surum gerekli']);
    $mevcut = (int)ayar_al('en_yeni_surum', '0');
    if ($surum < $mevcut) son(409, ['ok' => 0, 'mesaj' => "Daha eski surum yuklenmez ($surum < $mevcut)"]);

    // 3) Dosya
    if (!isset($_FILES['apk']) || (int)$_FILES['apk']['error'] !== UPLOAD_ERR_OK) {
        son(400, ['ok' => 0, 'mesaj' => 'Dosya yuklenemedi (kod ' . (int)($_FILES['apk']['error'] ?? -1) . ')']);
    }
    $gecici = $_FILES['apk']['tmp_name'];
    $boyut = (int)$_FILES['apk']['size'];
    if (!is_uploaded_file($gecici) || $boyut < 50000 || $boyut > 60 * 1024 * 1024) {
        son(400, ['ok' => 0, 'mesaj' => 'Dosya boyutu gecersiz']);
    }
    $f = fopen($gecici, 'rb');
    $imza = $f ? fread($f, 4) : '';
    if ($f) fclose($f);
    if ($imza !== "PK\x03\x04") son(400, ['ok' => 0, 'mesaj' => 'Bu bir APK dosyasi degil']);

    // 4) Kaydet: klasor admin panelinden secilir (site kokune gore), dosya adi sabittir
    $rel = apk_klasor_al();
    $klasor = dirname(__DIR__) . '/' . $rel;
    if (!is_dir($klasor)) {
        if (!mkdir($klasor, 0755, true)) son(500, ['ok' => 0, 'mesaj' => 'Klasor olusturulamadi']);
        file_put_contents($klasor . '/index.html', '');   // klasor listelenmesin
    }
    $hedef = $klasor . '/ProjeindirBot.apk';
    $gec = $hedef . '.tmp';
    if (!move_uploaded_file($gecici, $gec)) son(500, ['ok' => 0, 'mesaj' => 'Dosya kaydedilemedi']);
    @chmod($gec, 0644);
    if (!rename($gec, $hedef)) { @unlink($gec); son(500, ['ok' => 0, 'mesaj' => 'Dosya yerine konamadi']); }

    // 5) Yayinla: link ve surum admin paneline otomatik yazilir
    $url = 'https://' . ($_SERVER['HTTP_HOST'] ?? 'projeindir.com.tr') . '/' . $rel . '/ProjeindirBot.apk';
    ayar_yaz('apk_url', $url);
    ayar_yaz('en_yeni_surum', (string)$surum);
    ayar_yaz('yeni_surum_zaman', (string)time());

    son(200, ['ok' => 1, 'surum' => $surum, 'url' => $url, 'boyut' => $boyut]);
} catch (Throwable $e) {
    son(500, ['ok' => 0, 'mesaj' => 'Sunucu hatasi']);
}
