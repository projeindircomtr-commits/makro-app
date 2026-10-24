<?php
// Panel: üyelerden gelen oyun ekranı görüntüleri (admin oturumu gerekir)
define('MAKRO', 1);
define('GORUNTU_SADECE_TABLO', 1);
require_once __DIR__ . '/ortak.php';
require_once __DIR__ . '/goruntu.php';
oturum_baslat();
if (empty($_SESSION['admin'])) { header('Location: admin.php'); exit; }

$pdo = db();
goruntu_tablo($pdo);
$csrf = $_SESSION['csrf'] ?? '';
$mesaj = '';

// Eğitim için: etiketli (çizilmiş) tüm görüntülerin kutuları tek JSON dosyası
if (isset($_GET['etiket_indir'])) {
    $q = $pdo->query("SELECT g.id, g.zaman, g.oyun, g.etiketler, u.kullanici FROM makro_goruntuler g LEFT JOIN makro_uyeler u ON u.id = g.uye_id WHERE g.etiketler IS NOT NULL ORDER BY g.id");
    $out = [];
    foreach ($q->fetchAll() as $r) {
        $out[] = ['id' => (int)$r['id'], 'dosya' => $r['id'] . '.jpg', 'oyun' => $r['oyun'], 'uye' => $r['kullanici'], 'zaman' => (int)$r['zaman'], 'etiket' => json_decode($r['etiketler'], true)];
    }
    header('Content-Type: application/json; charset=utf-8');
    header('Content-Disposition: attachment; filename="etiketler-' . date('Ymd-His') . '.json"');
    echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
    exit;
}

// Tek görüntüyü göster / indir (dosyalar web'den doğrudan açılamaz, buradan verilir)
$g = (int)($_GET['g'] ?? 0);
if ($g > 0) {
    $q = $pdo->prepare('SELECT g.*, u.kullanici FROM makro_goruntuler g LEFT JOIN makro_uyeler u ON u.id = g.uye_id WHERE g.id = ?');
    $q->execute([$g]);
    $r = $q->fetch();
    $yol = goruntu_yol($g);
    if (!$r || !is_file($yol)) { http_response_code(404); exit('Yok'); }
    header('Content-Type: image/jpeg');
    header('Cache-Control: private, max-age=86400');
    if (isset($_GET['indir'])) {
        header('Content-Disposition: attachment; filename="' . preg_replace('/[^a-zA-Z0-9_.]/', '', (string)$r['kullanici']) . '-' . date('Ymd-His', (int)$r['zaman']) . '-' . $g . '.jpg"');
    }
    readfile($yol);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($csrf, $_POST['csrf'] ?? '')) exit('Oturum hatası, sayfayı yenile.');
    $is = $_POST['is'] ?? '';
    if ($is === 'sil') {
        $gid = (int)($_POST['id'] ?? 0);
        @unlink(goruntu_yol($gid));
        $pdo->prepare('DELETE FROM makro_goruntuler WHERE id = ?')->execute([$gid]);
        $mesaj = '✅ Görüntü silindi.';
    } elseif ($is === 'uye_sil') {
        $q = $pdo->prepare('SELECT id FROM makro_goruntuler WHERE uye_id = ?');
        $q->execute([(int)($_POST['uye'] ?? 0)]);
        $n = 0;
        foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $gid) {
            @unlink(goruntu_yol((int)$gid));
            $pdo->prepare('DELETE FROM makro_goruntuler WHERE id = ?')->execute([(int)$gid]);
            $n++;
        }
        $mesaj = "✅ $n görüntü silindi.";
    }
}
goruntu_temizle($pdo);

$uye = (int)($_GET['uye'] ?? 0);
$sayfa = max(0, (int)($_GET['s'] ?? 0));
$uyeler = $pdo->query('SELECT u.id, u.kullanici, COUNT(g.id) AS adet, MAX(g.zaman) AS son, SUM(g.boyut) AS toplam
    FROM makro_goruntuler g JOIN makro_uyeler u ON u.id = g.uye_id GROUP BY u.id, u.kullanici ORDER BY son DESC')->fetchAll();
$sql = 'SELECT g.id, g.uye_id, g.zaman, g.oyun, g.etiket, g.boyut, g.etiketler, u.kullanici FROM makro_goruntuler g LEFT JOIN makro_uyeler u ON u.id = g.uye_id';
if ($uye > 0) {
    $q = $pdo->prepare($sql . ' WHERE g.uye_id = ? ORDER BY g.id DESC LIMIT 60 OFFSET ' . ($sayfa * 60));
    $q->execute([$uye]);
} else {
    $q = $pdo->query($sql . ' ORDER BY g.id DESC LIMIT 60 OFFSET ' . ($sayfa * 60));
}
$liste = $q->fetchAll();
$ETR = ['hp' => '#ff4d4d', 'mp' => '#4da3ff', 'hedef' => '#ff9f1a', 'collect' => '#39d98a', 'koord' => '#ffe14d', 'open' => '#ff66ff', 'kutu' => '#ffffff'];
$etiketSay = (int)$pdo->query('SELECT COUNT(*) FROM makro_goruntuler WHERE etiketler IS NOT NULL')->fetchColumn();
$toplamMb = 0; foreach ($uyeler as $u) $toplamMb += (int)$u['toplam'];
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Görüntüler</title>
<style>
body{background:#12161c;color:#fff;font-family:sans-serif;padding:12px;margin:0}
.k{background:#1c232d;padding:14px;border-radius:14px;margin:10px 0}
a{color:#e0b04a}.gri{color:#9aa4b2;font-size:13px}
button{color:#fff;border:0;padding:8px 11px;border-radius:9px;font-size:13px;background:#b33a3a}
select{width:100%;padding:11px;border-radius:9px;border:0;font-size:15px}
.m{background:#2e9e5b33;border:1px solid #2e9e5b;padding:12px;border-radius:10px}
.izgara{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}
.izgara div{background:#0d1117;border-radius:10px;padding:6px;font-size:12px}
.izgara img{width:100%;border-radius:6px;display:block}
.res{position:relative}.kt{position:absolute;border:2px solid;box-sizing:border-box;font-size:10px;font-weight:bold;text-shadow:0 0 3px #000}
.rozet{background:#e0b04a;color:#000;border-radius:6px;padding:1px 6px;font-size:11px;font-weight:bold}
form{display:inline}
</style></head><body>
<h2 style="margin:6px 0">📸 Görüntüler <a href="admin.php" style="font-size:14px;float:right">← Panel</a></h2>
<?php if ($mesaj): ?><div class="m"><?= e($mesaj) ?></div><?php endif; ?>
<div class="k">
<form method="get" style="display:block"><select name="uye" onchange="this.form.submit()">
<option value="0">Tüm üyeler</option>
<?php foreach ($uyeler as $u): ?><option value="<?= (int)$u['id'] ?>" <?= $uye === (int)$u['id'] ? 'selected' : '' ?>><?= e($u['kullanici']) ?> (<?= (int)$u['adet'] ?>)</option><?php endforeach; ?>
</select></form>
<p class="gri">Sadece panelde "📸 Veri" verilen ve uygulamada kabul eden üyelerden gelir. Bot çalışırken 5 dakikada bir. <?= GORUNTU_GUN ?> günden eskiler kendiliğinden silinir. Toplam: <?= round($toplamMb / 1048576, 1) ?> MB<br>
✏ Çizilmiş (etiketli) görüntü: <b><?= $etiketSay ?></b><?php if ($etiketSay): ?> • <a href="?etiket_indir=1">⬇ Etiketleri indir (JSON)</a><?php endif; ?></p>
<?php if ($uye > 0): ?>
<form method="post" onsubmit="return confirm('Bu üyenin tüm görüntüleri silinsin mi?')"><input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="uye_sil"><input type="hidden" name="uye" value="<?= $uye ?>"><button>Bu üyenin tüm görüntülerini sil</button></form>
<?php endif; ?>
<?php if (!$liste): ?><p class="gri">Henüz görüntü yok.</p><?php endif; ?>
<div class="izgara">
<?php foreach ($liste as $x): ?>
<?php $et = $x['etiketler'] ? json_decode($x['etiketler'], true) : null; ?>
<div><a class="res" style="display:block" href="?g=<?= (int)$x['id'] ?>" target="_blank"><img loading="lazy" src="?g=<?= (int)$x['id'] ?>" alt="">
<?php if ($et): foreach ($et['kutular'] as $ad => $r): $renk = $ETR[$ad] ?? '#fff'; ?>
<span class="kt" style="border-color:<?= $renk ?>;color:<?= $renk ?>;left:<?= round($r[0] / $et['w'] * 100, 2) ?>%;top:<?= round($r[1] / $et['h'] * 100, 2) ?>%;width:<?= round(($r[2] - $r[0]) / $et['w'] * 100, 2) ?>%;height:<?= round(($r[3] - $r[1]) / $et['h'] * 100, 2) ?>%"><?= e($ad) ?></span>
<?php endforeach; endif; ?></a>
<?php if ($et): ?><span class="rozet">✏ çizildi</span> <?php endif; ?>
<b><?= e($x['kullanici'] ?? '?') ?></b> • <?= e(date('d.m H:i', (int)$x['zaman'])) ?> • <?= e($x['oyun']) ?> • <?= round((int)$x['boyut'] / 1024) ?> KB<br>
<a href="?g=<?= (int)$x['id'] ?>&indir=1">⬇ indir</a>
<form method="post" onsubmit="return confirm('Silinsin mi?')"><input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="sil"><input type="hidden" name="id" value="<?= (int)$x['id'] ?>"> <button>Sil</button></form>
</div>
<?php endforeach; ?>
</div>
<p><?php if ($sayfa > 0): ?><a href="?uye=<?= $uye ?>&s=<?= $sayfa - 1 ?>">← Yeni</a> &nbsp;<?php endif; ?>
<?php if (count($liste) === 60): ?><a href="?uye=<?= $uye ?>&s=<?= $sayfa + 1 ?>">Eski →</a><?php endif; ?></p>
</div>
</body></html>
