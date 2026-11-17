<?php
// Uzaktan kontrol: her üyenin botunu panelden başlat / durdur, son durumunu gör (yönetici girişi gerekir)
define('MAKRO', 1);
require __DIR__ . '/ortak.php';
oturum_baslat();
if (empty($_SESSION['admin'])) { header('Location: admin.php'); exit; }
sema_guncelle();
$pdo = db();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($_SESSION['csrf'] ?? '', $_POST['csrf'] ?? '')) exit('Oturum hatası, sayfayı yenile.');
    $id = (int)($_POST['id'] ?? 0);
    $komut = in_array($_POST['komut'] ?? '', ['basla', 'dur'], true) ? $_POST['komut'] : null;
    if ($id > 0 && $komut) $pdo->prepare('UPDATE makro_uyeler SET komut = ? WHERE id = ?')->execute([$komut, $id]);
    header('Location: kontrol.php'); exit;
}

$csrf = e($_SESSION['csrf']);
$uyeler = $pdo->query('SELECT id, kullanici, isim, durum, durum_zaman, komut FROM makro_uyeler
    WHERE durum_zaman IS NOT NULL ORDER BY durum_zaman DESC LIMIT 50')->fetchAll();
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="30">
<title>Bot Kontrol</title>
<style>body{background:#12161c;color:#fff;font-family:sans-serif;padding:12px;margin:0}
.k{background:#1c222b;border-radius:12px;padding:14px;margin:10px 0}
.d{color:#9fb3c8;font-size:14px;margin:6px 0 10px}
.r{display:flex;gap:8px}form{flex:1;margin:0}
button{width:100%;padding:16px;border:0;border-radius:10px;font-size:18px;color:#fff}
.b{background:#2e9e5b}.s{background:#c0392b}a{color:#7cb8ff}</style></head><body>
<h2>🎮 Bot Kontrol</h2>
<p class="d">Komut bota en geç 30 sn içinde ulaşır. Sayfa 30 sn'de bir yenilenir. <a href="admin.php">← Panel</a></p>
<?php if (!$uyeler) echo '<p>Henüz durum bildiren bot yok.</p>'; ?>
<?php foreach ($uyeler as $u):
    $once = time() - (int)$u['durum_zaman'];
    $cevrimici = $once < 120;
?>
<div class="k">
  <b><?= e($u['isim'] ?: $u['kullanici']) ?></b>
  <div class="d"><?= $cevrimici ? '🟢 ' . e($u['durum']) : '⚪ Çevrimdışı (' . ($once < 3600 ? intdiv($once, 60) . ' dk' : ($once < 86400 ? intdiv($once, 3600) . ' saat' : intdiv($once, 86400) . ' gün')) . ' önce)' ?>
    <?= $u['komut'] ? ' • ⏳ gönderiliyor: ' . e($u['komut'] === 'basla' ? 'başlat' : 'durdur') : '' ?></div>
  <div class="r">
    <form method="post"><input type="hidden" name="csrf" value="<?= $csrf ?>"><input type="hidden" name="id" value="<?= (int)$u['id'] ?>">
      <button class="b" name="komut" value="basla">▶ Başlat</button></form>
    <form method="post"><input type="hidden" name="csrf" value="<?= $csrf ?>"><input type="hidden" name="id" value="<?= (int)$u['id'] ?>">
      <button class="s" name="komut" value="dur">⏸ Durdur</button></form>
  </div>
</div>
<?php endforeach; ?>
</body></html>
