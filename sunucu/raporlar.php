<?php
// Panel: uyelerden otomatik gelen bot raporlari (admin.php oturumu gerekir)
define('MAKRO', 1);
define('RAPOR_SADECE_TABLO', 1);
require_once __DIR__ . '/ortak.php';
require_once __DIR__ . '/rapor.php';
session_start();
if (empty($_SESSION['admin'])) { header('Location: admin.php'); exit; }

$pdo = db();
rapor_tablo($pdo);
$csrf = $_SESSION['csrf'] ?? '';
$mesaj = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($csrf, $_POST['csrf'] ?? '')) exit('Oturum hatası, sayfayı yenile.');
    $is = $_POST['is'] ?? '';
    if ($is === 'sil') {
        $pdo->prepare('DELETE FROM makro_raporlar WHERE id = ?')->execute([(int)($_POST['id'] ?? 0)]);
        $mesaj = '✅ Rapor silindi.';
    } elseif ($is === 'uye_sil') {
        $n = $pdo->prepare('DELETE FROM makro_raporlar WHERE uye_id = ?');
        $n->execute([(int)($_POST['uye'] ?? 0)]);
        $mesaj = '✅ ' . $n->rowCount() . ' rapor silindi.';
    }
}

$id = (int)($_GET['id'] ?? 0);
if ($id > 0) {
    $q = $pdo->prepare('SELECT r.*, u.kullanici FROM makro_raporlar r LEFT JOIN makro_uyeler u ON u.id = r.uye_id WHERE r.id = ?');
    $q->execute([$id]);
    $r = $q->fetch();
    if ($r && isset($_GET['indir'])) {
        header('Content-Type: text/plain; charset=utf-8');
        header('Content-Disposition: attachment; filename="rapor-' . preg_replace('/[^a-zA-Z0-9_.]/', '', (string)$r['kullanici']) . '-' . date('Ymd-His', (int)$r['zaman']) . '.txt"');
        echo $r['metin'];
        exit;
    }
}

$uye = (int)($_GET['uye'] ?? 0);
$uyeler = $pdo->query('SELECT u.id, u.kullanici, COUNT(r.id) AS adet, MAX(r.zaman) AS son
    FROM makro_raporlar r JOIN makro_uyeler u ON u.id = r.uye_id GROUP BY u.id, u.kullanici ORDER BY son DESC')->fetchAll();
if ($uye > 0) {
    $q = $pdo->prepare('SELECT r.id, r.uye_id, r.zaman, r.tur, r.surum, r.oyun, r.neden, LENGTH(r.metin) AS boyut, u.kullanici
        FROM makro_raporlar r LEFT JOIN makro_uyeler u ON u.id = r.uye_id WHERE r.uye_id = ? ORDER BY r.id DESC LIMIT 100');
    $q->execute([$uye]);
} else {
    $q = $pdo->query('SELECT r.id, r.uye_id, r.zaman, r.tur, r.surum, r.oyun, r.neden, LENGTH(r.metin) AS boyut, u.kullanici
        FROM makro_raporlar r LEFT JOIN makro_uyeler u ON u.id = r.uye_id ORDER BY r.id DESC LIMIT 100');
}
$liste = $q->fetchAll();
$turAd = ['durdu' => '⏹ Bot durdu', 'periyodik' => '⏱ 30 dk', 'sorun' => '🩺 Sorun bildir', 'diger' => '•'];
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Raporlar</title>
<style>
body{background:#12161c;color:#fff;font-family:sans-serif;padding:12px;margin:0}
.k{background:#1c232d;padding:14px;border-radius:14px;margin:10px 0}
a{color:#e0b04a}.gri{color:#9aa4b2;font-size:13px}
button{color:#fff;border:0;padding:9px 12px;border-radius:9px;font-size:14px;background:#b33a3a}
select{width:100%;padding:11px;border-radius:9px;border:0;font-size:15px}
.m{background:#2e9e5b33;border:1px solid #2e9e5b;padding:12px;border-radius:10px}
.satir{border-top:1px solid #2a3442;padding:9px 0;display:block;text-decoration:none;color:#fff}
pre{white-space:pre-wrap;word-break:break-word;font-size:12px;background:#0d1117;padding:10px;border-radius:10px}
form{display:inline}
</style></head><body>
<h2 style="margin:6px 0">📋 Raporlar <a href="admin.php" style="font-size:14px;float:right">← Panel</a></h2>
<?php if ($mesaj): ?><div class="m"><?= e($mesaj) ?></div><?php endif; ?>

<?php if ($id > 0 && !empty($r)): ?>
<div class="k">
<b><?= e($r['kullanici'] ?? '?') ?></b> • <?= e(date('d.m.Y H:i', (int)$r['zaman'])) ?> • <?= e($turAd[$r['tur']] ?? $r['tur']) ?><br>
<span class="gri">sürüm <?= (int)$r['surum'] ?> • <?= e($r['oyun']) ?><?= $r['neden'] !== '' && $r['neden'] !== null ? ' • ' . e($r['neden']) : '' ?></span><br>
<a href="?id=<?= (int)$r['id'] ?>&indir=1">⬇ .txt indir</a> •
<a href="?uye=<?= (int)$r['uye_id'] ?>">Bu üyenin raporları</a>
<form method="post" onsubmit="return confirm('Rapor silinsin mi?')"><input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="sil"><input type="hidden" name="id" value="<?= (int)$r['id'] ?>"> <button>Sil</button></form>
<pre><?= e($r['metin']) ?></pre>
</div>
<?php else: ?>
<div class="k">
<form method="get" style="display:block"><select name="uye" onchange="this.form.submit()">
<option value="0">Tüm üyeler</option>
<?php foreach ($uyeler as $u): ?><option value="<?= (int)$u['id'] ?>" <?= $uye === (int)$u['id'] ? 'selected' : '' ?>><?= e($u['kullanici']) ?> (<?= (int)$u['adet'] ?>)</option><?php endforeach; ?>
</select></form>
<p class="gri">Bot durunca ve çalışırken 30 dakikada bir gelir. 30 günden eski raporlar kendiliğinden silinir.</p>
<?php if ($uye > 0): ?>
<form method="post" onsubmit="return confirm('Bu üyenin tüm raporları silinsin mi?')"><input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="uye_sil"><input type="hidden" name="uye" value="<?= $uye ?>"><button>Bu üyenin tüm raporlarını sil</button></form>
<?php endif; ?>
<?php if (!$liste): ?><p class="gri">Henüz rapor yok.</p><?php endif; ?>
<?php foreach ($liste as $x): ?>
<a class="satir" href="?id=<?= (int)$x['id'] ?>">
<b><?= e($x['kullanici'] ?? '?') ?></b> • <?= e(date('d.m H:i', (int)$x['zaman'])) ?> • <?= e($turAd[$x['tur']] ?? $x['tur']) ?><br>
<span class="gri">sürüm <?= (int)$x['surum'] ?> • <?= e($x['oyun']) ?> • <?= round((int)$x['boyut'] / 1024) ?> KB<?= $x['neden'] ? ' • ' . e($x['neden']) : '' ?></span>
</a>
<?php endforeach; ?>
</div>
<?php endif; ?>
</body></html>
