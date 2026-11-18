#!/usr/bin/env python3
# v93 - Pazar/Upgrade admin kontrollu (sadece Farm gorunur) + panel baslat gecikmesi + surukleme takilmasi
import os, sys, io
KOK = os.path.expanduser(sys.argv[1]) if len(sys.argv) > 1 else "."
MS = os.path.join(KOK, "app/src/main/java/com/kanka/makro/MacroService.kt")
if not os.path.exists(MS): print("! MacroService.kt yok"); sys.exit(1)
s = io.open(MS, encoding="utf-8").read()
yap=atla=hata=0
def degis(eski, yeni, imza):
    global s,yap,atla,hata
    if imza in s: print("= zaten var:", imza[:45]); atla+=1; return
    n=s.count(eski)
    if n!=1: print("! capa", n, "kez:", imza[:45]); hata+=1; return
    s=s.replace(eski,yeni); print("+ uygulandi:", imza[:45]); yap+=1

# 1) panel komut araligi 30->10sn
degis("            ah.postDelayed(this, 30_000)   // panelden başlat/durdur en geç 30 sn'de gelsin",
      "            ah.postDelayed(this, 10_000)   // panelden başlat/durdur en geç 10 sn'de gelsin",
      "en geç 10 sn'de gelsin")
# 2) Upgrade'i admin ozelligine bagla (Pazar zaten ozellikVar ile admin kontrollu)
degis('''        val testListesi: List<Pair<String, () -> Unit>> = if (Config.koMu(this)) emptyList() else
            listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }, "⚒ Upgrade Bot" to { upgradeMenu() })''',
'''        val testListesi: List<Pair<String, () -> Unit>> = if (Config.koMu(this)) emptyList() else
            (listOf<Pair<String, () -> Unit>>("🏙 Otomatik çanta boşaltma" to { townSihirbaz() }) +
                if (Lisans.ozellikVar("upgrade")) listOf("⚒ Upgrade Bot" to { upgradeMenu() }) else emptyList())''',
      'if (Lisans.ozellikVar("upgrade")) listOf("⚒ Upgrade')
# 3) surukleme alani + throttle (uc parca)
degis("            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false",
      "            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false; var sonHareket = 0L",
      "var moved = false; var sonHareket = 0L")
degis('''                        params.x = (sx + dx).coerceIn(0, maxOf(0, screenW - genisPx))
                        params.y = (sy + dy).coerceIn(0, maxOf(0, screenH - yukPx))
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}''',
'''                        params.x = (sx + dx).coerceIn(0, maxOf(0, screenW - genisPx))
                        params.y = (sy + dy).coerceIn(0, maxOf(0, screenH - yukPx))
                        // Her dokunus olayinda relayout janka yol aciyordu: kare basina en fazla bir kez guncelle
                        val simdi = android.os.SystemClock.uptimeMillis()
                        if (simdi - sonHareket >= 16L) { sonHareket = simdi; try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }''',
      "kare basina en fazla bir kez guncelle")
degis('''                    MotionEvent.ACTION_UP -> {
                        if (!moved && v === panelIkonBtn) {''',
'''                    MotionEvent.ACTION_UP -> {
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                        if (!moved && v === panelIkonBtn) {''',
      "ACTION_UP -> {\n                        try { wm.updateViewLayout(root, params) }")

io.open(MS,"w",encoding="utf-8").write(s)
print(f"== OZET == yapildi={yap} atlandi={atla} hata={hata}")
if hata: sys.exit(1)
