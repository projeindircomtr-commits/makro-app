<?php
// Uygulamanin otomatik gonderdigi bot raporlarini alir (uye oturum anahtariyla).
if (!defined('MAKRO')) define('MAKRO', 1);
require_once __DIR__ . '/ortak.php';

function rapor_cevap(bool $ok, string $m = ''): void {
    echo json_encode(['ok' => $ok ? 1 : 0, 'mesaj' => $m], JSON_UNESCAPED_UNICODE);
    exit;
}

/** Tabloyu bir kere olusturur (admin raporlar sayfasi da cagirir) */
function rapor_tablo(PDO $pdo): void {
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_raporlar (
        id INT AUTO_INCREMENT PRIMARY KEY,
        uye_id INT NOT NULL,
        zaman INT NOT NULL,
        tur VARCHAR(12) NOT NULL,
        surum INT NULL,
        oyun VARCHAR(10) NULL,
        neden VARCHAR(200) NULL,
        metin MEDIUMTEXT NOT NULL,
        INDEX (uye_id, zaman),
        INDEX (zaman)
    ) CHARACTER SET utf8mb4");
}

if (defined('RAPOR_SADECE_TABLO')) return;   // raporlar.php sadece rapor_tablo() icin dahil eder

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') rapor_cevap(false, 'Geçersiz istek');

$token = preg_replace('/[^a-f0-9]/', '', strtolower((string)($_POST['token'] ?? '')));
if (strlen($token) < 32) rapor_cevap(false, 'Oturum yok');

$tur = (string)($_POST['tur'] ?? '');
if (!in_array($tur, ['durdu', 'periyodik', 'sorun'], true)) $tur = 'diger';
$surum = (int)($_POST['surum'] ?? 0);
$oyun = substr(preg_replace('/[^a-z]/', '', strtolower((string)($_POST['oyun'] ?? ''))), 0, 10);
$neden = mb_substr(trim((string)($_POST['neden'] ?? '')), 0, 200);
$metin = (string)($_POST['metin'] ?? '');
if ($metin === '') rapor_cevap(false, 'Boş rapor');
// En fazla ~300 KB: bas kismi (cihaz/ekran bilgisi) ve son olaylar korunur
$SINIR = 300000;
if (strlen($metin) > $SINIR) $metin = substr($metin, 0, 5000) . "\n...(kırpıldı)...\n" . substr($metin, -($SINIR - 5100));
if (!mb_check_encoding($metin, 'UTF-8')) $metin = mb_convert_encoding($metin, 'UTF-8', 'UTF-8');

$simdi = time();
try {
    $pdo = db();
    rapor_tablo($pdo);
    $q = $pdo->prepare('SELECT id, aktif FROM makro_uyeler WHERE token = ?');
    $q->execute([$token]);
    $u = $q->fetch();
    if (!$u || !(int)$u['aktif']) rapor_cevap(false, 'Oturum geçersiz');
    // Sunucu dolmasin: uye basina saatte en fazla 20 rapor
    $q = $pdo->prepare('SELECT COUNT(*) FROM makro_raporlar WHERE uye_id = ? AND zaman > ?');
    $q->execute([(int)$u['id'], $simdi - 3600]);
    if ((int)$q->fetchColumn() >= 20) rapor_cevap(false, 'Çok sık rapor');
    $pdo->prepare('INSERT INTO makro_raporlar (uye_id, zaman, tur, surum, oyun, neden, metin) VALUES (?, ?, ?, ?, ?, ?, ?)')
        ->execute([(int)$u['id'], $simdi, $tur, $surum, $oyun, $neden, $metin]);
    // 30 gunden eski raporlar silinir
    $pdo->prepare('DELETE FROM makro_raporlar WHERE zaman < ?')->execute([$simdi - 30 * 86400]);
    rapor_cevap(true);
} catch (Throwable $ex) {
    rapor_cevap(false, 'Sunucu hatası');
}
