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
