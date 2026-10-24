<?php
// Projeindir Bot indirme sayfasi: 15 sn bekleme, gizli dosya yolu, IP basina indirme siniri.
define('MAKRO', 1);
require dirname(__DIR__) . '/makro/ortak.php';

const BEKLE = 15;        // saniye
const SAAT_SINIR = 3;    // ayni IP saatte en fazla kac kez indirebilir
const TOKEN_OMUR = 600;  // sayfa acildiktan sonra link kac saniye gecerli

header('X-Robots-Tag: noindex');
$ip = $_SERVER['REMOTE_ADDR'] ?? '0';
$pdo = db();

if (!file_exists(GIZLI . '/indir1.lock')) {
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_indirme (
        id INT AUTO_INCREMENT PRIMARY KEY, ip VARCHAR(45) NOT NULL, zaman INT NOT NULL, INDEX (ip, zaman)
    ) CHARACTER SET utf8mb4");
    file_put_contents(GIZLI . '/indir1.lock', date('c'));
}
$gizli = ayar_al('indir_gizli', '');
if ($gizli === '') { $gizli = bin2hex(random_bytes(32)); ayar_yaz('indir_gizli', $gizli); }

$klasor = dirname(__DIR__) . '/' . apk_klasor_al();
$yol = $klasor . '/' . APK_ADI;
apk_klasor_koru($klasor);
$var = is_file($yol);

function imza(string $t, string $ip, string $g): string { return substr(hash_hmac('sha256', "$t|$ip", $g), 0, 32); }
function son_bir_saat(PDO $pdo, string $ip): int {
    $q = $pdo->prepare('SELECT COUNT(*) FROM makro_indirme WHERE ip = ? AND zaman > ?');
    $q->execute([$ip, time() - 3600]);
    return (int)$q->fetchColumn();
}

$hata = '';

// ---- Indirme istegi ----
if (isset($_GET['al'])) {
    [$t, $s] = array_pad(explode('.', (string)$_GET['al'], 2), 2, '');
    $gecen = time() - (int)$t;
    if (!$var) $hata = 'Şu an indirilebilecek sürüm yok.';
    elseif (!ctype_digit($t) || !hash_equals(imza($t, $ip, $gizli), $s) || $gecen > TOKEN_OMUR) $hata = 'Linkin süresi dolmuş, tekrar dene.';
    elseif ($gecen < BEKLE) $hata = 'Lütfen bekleme süresini tamamla.';
    elseif (son_bir_saat($pdo, $ip) >= SAAT_SINIR) $hata = 'Çok fazla indirme yaptın. Biraz sonra tekrar dene.';
    else {
        $pdo->prepare('INSERT INTO makro_indirme (ip, zaman) VALUES (?, ?)')->execute([$ip, time()]);
        if (random_int(1, 50) === 1) $pdo->prepare('DELETE FROM makro_indirme WHERE zaman < ?')->execute([time() - 86400]);
        @set_time_limit(0);
        while (ob_get_level()) ob_end_clean();
        header('Content-Type: application/vnd.android.package-archive');
        header('Content-Disposition: attachment; filename="' . APK_ADI . '"');
        header('Content-Length: ' . filesize($yol));
        header('Cache-Control: no-store');
        readfile($yol);
        exit;
    }
}

$surum = (int)ayar_al('en_yeni_surum', '0');
$boyut = $var ? round(filesize($yol) / 1048576, 1) : 0;
$t = (string)time();
$link = '?al=' . $t . '.' . imza($t, $ip, $gizli);
$limitDolu = son_bir_saat($pdo, $ip) >= SAAT_SINIR;
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>Projeindir Bot İndir</title>
<style>
body{margin:0;background:#12161c;color:#fff;font-family:sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:16px;box-sizing:border-box}
.k{background:#1c222b;border-radius:18px;padding:28px 22px;max-width:380px;width:100%;text-align:center}
.logo{width:72px;height:72px;border-radius:18px;background:#2e9e5b;font-size:40px;font-weight:bold;line-height:72px;margin:0 auto 14px}
h1{font-size:22px;margin:0 0 4px}.gri{color:#9aa4b2;font-size:14px}
.sayac{font-size:54px;font-weight:bold;margin:22px 0 6px}
.cubuk{height:8px;background:#2a323d;border-radius:4px;overflow:hidden;margin:10px 0 20px}
.cubuk div{height:100%;width:0;background:#2e9e5b;transition:width 1s linear}
a.btn{display:block;background:#2e9e5b;color:#fff;text-decoration:none;padding:16px;border-radius:12px;font-size:18px}
a.btn.kapali{background:#3a424e;pointer-events:none;color:#9aa4b2}
.hata{background:#3a1f1f;border-left:3px solid #d05050;padding:10px;border-radius:8px;margin:12px 0;text-align:left}
</style></head><body><div class="k">
<div class="logo">P</div>
<h1>Projeindir Bot</h1>
<div class="gri"><?= $var ? 'Sürüm ' . ($surum ?: '-') . ' • ' . $boyut . ' MB' : 'Henüz yayınlanmış sürüm yok' ?></div>
<?php if ($hata): ?><div class="hata"><?= e($hata) ?></div><?php endif; ?>
<?php if ($var && !$limitDolu): ?>
<div class="sayac" id="s"><?= BEKLE ?></div>
<div class="gri" id="y">İndirme hazırlanıyor...</div>
<div class="cubuk"><div id="c"></div></div>
<a class="btn kapali" id="b" href="<?= e($link) ?>">Lütfen bekle</a>
<script>
(function(){var n=<?= BEKLE ?>,s=document.getElementById('s'),c=document.getElementById('c'),b=document.getElementById('b'),y=document.getElementById('y');
var i=setInterval(function(){n--;s.textContent=n;c.style.width=((<?= BEKLE ?>-n)/<?= BEKLE ?>*100)+'%';
if(n<=0){clearInterval(i);s.textContent='✓';y.textContent='İndirme hazır';b.className='btn';b.textContent='⬇ İndir';}},1000);})();
</script>
<?php elseif ($limitDolu): ?>
<div class="hata">Bu saat içinde indirme sınırına ulaştın. Biraz sonra tekrar dene.</div>
<?php endif; ?>
<p class="gri" style="margin-top:18px">Yapımcı: Muhammed Salman</p>
</div></body></html>
