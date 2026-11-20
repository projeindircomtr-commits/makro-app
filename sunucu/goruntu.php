<?php
// Uygulamadan gelen oyun ekranı görüntüleri (eğitim verisi).
// SADECE panelde "📸 Veri" verilen ve uygulamada "Kabul" diyen üyeden gelir. Dosyalar gizli/ altında (web'den erişilemez).
if (!defined('MAKRO')) define('MAKRO', 1);
require_once __DIR__ . '/ortak.php';

const GORUNTU_DIZIN = GIZLI . '/goruntu';
const GORUNTU_GUN = 30;          // 30 gün sonra silinir
const GORUNTU_SAAT_SINIR = 30;   // üye başına saatte en fazla
const GORUNTU_MAX = 600000;      // tek görüntü en fazla ~600 KB
const GORUNTU_TOPLAM_MB = 2000;  // hosting dolmasın: toplam bunu aşarsa en eskiler silinir

function goruntu_tablo(PDO $pdo): void {
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_goruntuler (
        id INT AUTO_INCREMENT PRIMARY KEY,
        uye_id INT NOT NULL,
        zaman INT NOT NULL,
        oyun VARCHAR(10) NULL,
        etiket VARCHAR(40) NULL,
        boyut INT NOT NULL,
        etiketler TEXT NULL,
        INDEX (uye_id, zaman),
        INDEX (zaman)
    ) CHARACTER SET utf8mb4");
    // v76: eski tabloya "etiketler" (kullanıcının çizdiği kutular, JSON) kolonu eklenir
    static $bakildi = false;
    if (!$bakildi) {
        $bakildi = true;
        try { $pdo->query('SELECT etiketler FROM makro_goruntuler LIMIT 0'); }
        catch (Throwable $e) { $pdo->exec('ALTER TABLE makro_goruntuler ADD COLUMN etiketler TEXT NULL'); }
    }
}

/** Uygulamadan gelen etiket JSON'unu doğrular: {"w":..,"h":..,"kutular":{"hp":[x1,y1,x2,y2],...}} */
function etiket_temizle(string $ham): ?string {
    $j = json_decode($ham, true);
    if (!is_array($j) || !is_array($j['kutular'] ?? null)) return null;
    $w = (int)($j['w'] ?? 0); $h = (int)($j['h'] ?? 0);
    if ($w < 10 || $h < 10 || $w > 5000 || $h > 5000) return null;
    $k = [];
    foreach ($j['kutular'] as $ad => $r) {
        if (!in_array($ad, ['hp', 'mp', 'hedef', 'collect', 'koord', 'open', 'kutu'], true) || !is_array($r) || count($r) !== 4) continue;
        $r = array_map('intval', array_values($r));
        if ($r[2] <= $r[0] || $r[3] <= $r[1]) continue;
        $k[$ad] = [max(0, $r[0]), max(0, $r[1]), min($w, $r[2]), min($h, $r[3])];
    }
    return $k ? json_encode(['w' => $w, 'h' => $h, 'kutular' => $k]) : null;
}

function goruntu_yol(int $id): string { return GORUNTU_DIZIN . '/' . $id . '.jpg'; }

/** 30 günden eskileri (kayıt + dosya) siler */
function goruntu_temizle(PDO $pdo): void {
    $q = $pdo->prepare('SELECT id FROM makro_goruntuler WHERE zaman < ?');
    $q->execute([time() - GORUNTU_GUN * 86400]);
    foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $gid) {
        @unlink(goruntu_yol((int)$gid));
        $pdo->prepare('DELETE FROM makro_goruntuler WHERE id = ?')->execute([(int)$gid]);
    }
    // Toplam boyut sınırı: aşılırsa en eskiler (önce çizilmemiş/etiketsiz olanlar) sınırın altına inene kadar silinir
    $sinir = (int)(GORUNTU_TOPLAM_MB * 1048576);
    $fazla = (int)$pdo->query('SELECT COALESCE(SUM(boyut), 0) FROM makro_goruntuler')->fetchColumn() - $sinir;
    if ($fazla > 0) {
        $q = $pdo->query('SELECT id, boyut FROM makro_goruntuler ORDER BY (etiketler IS NULL) DESC, id ASC LIMIT 2000');
        foreach ($q->fetchAll() as $r) {
            if ($fazla <= 0) break;
            @unlink(goruntu_yol((int)$r['id']));
            $pdo->prepare('DELETE FROM makro_goruntuler WHERE id = ?')->execute([(int)$r['id']]);
            $fazla -= (int)$r['boyut'];
        }
    }
}

if (defined('GORUNTU_SADECE_TABLO')) return;

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
function goruntu_cevap(bool $ok, string $m = ''): void {
    echo json_encode(['ok' => $ok ? 1 : 0, 'mesaj' => $m], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') goruntu_cevap(false, 'Geçersiz istek');
$token = preg_replace('/[^a-f0-9]/', '', strtolower((string)($_POST['token'] ?? '')));
if (strlen($token) < 32) goruntu_cevap(false, 'Oturum yok');
$jpg = base64_decode((string)($_POST['resim'] ?? ''), true);
if ($jpg === false || strlen($jpg) < 1000 || strlen($jpg) > GORUNTU_MAX) goruntu_cevap(false, 'Görüntü geçersiz');
if (substr($jpg, 0, 3) !== "\xFF\xD8\xFF") goruntu_cevap(false, 'JPEG değil');
$bilgi = @getimagesizefromstring($jpg);
if (!$bilgi || ($bilgi[2] ?? 0) !== IMAGETYPE_JPEG) goruntu_cevap(false, 'JPEG değil');
$oyun = substr(preg_replace('/[^a-z]/', '', strtolower((string)($_POST['oyun'] ?? ''))), 0, 10);
$etiket = substr(preg_replace('/[^a-zA-Z0-9_\-]/', '', (string)($_POST['etiket'] ?? '')), 0, 40);
$etiketler = etiket_temizle(substr((string)($_POST['etiketler'] ?? ''), 0, 4000));

try {
    $pdo = db();
    goruntu_tablo($pdo);
    $q = $pdo->prepare('SELECT * FROM makro_uyeler WHERE token = ?');
    $q->execute([$token]);
    $u = $q->fetch();
    if (!$u || !(int)$u['aktif']) goruntu_cevap(false, 'Oturum geçersiz');
    // Sunucu tarafı da kontrol eder: panelde "Veri" verilmemiş üyenin görüntüsü kabul edilmez
    if (!in_array('veri', explode(',', uye_ozellik($u)), true)) goruntu_cevap(false, 'Bu hesapta kapalı');
    $q = $pdo->prepare('SELECT COUNT(*) FROM makro_goruntuler WHERE uye_id = ? AND zaman > ?');
    $q->execute([(int)$u['id'], time() - 3600]);
    if ((int)$q->fetchColumn() >= GORUNTU_SAAT_SINIR) goruntu_cevap(false, 'Çok sık');
    if (!is_dir(GORUNTU_DIZIN)) mkdir(GORUNTU_DIZIN, 0700, true);
    $pdo->prepare('INSERT INTO makro_goruntuler (uye_id, zaman, oyun, etiket, boyut, etiketler) VALUES (?, ?, ?, ?, ?, ?)')
        ->execute([(int)$u['id'], time(), $oyun, $etiket, strlen($jpg), $etiketler]);
    $gid = (int)$pdo->lastInsertId();
    if (file_put_contents(goruntu_yol($gid), $jpg) === false) {
        $pdo->prepare('DELETE FROM makro_goruntuler WHERE id = ?')->execute([$gid]);
        goruntu_cevap(false, 'Kaydedilemedi');
    }
    goruntu_temizle($pdo);
    goruntu_cevap(true);
} catch (Throwable $ex) {
    goruntu_cevap(false, 'Sunucu hatası');
}
