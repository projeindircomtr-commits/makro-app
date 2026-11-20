package com.kanka.makro

// net-konum-yama4: Rootless Android VPN servisi — oyun trafiğini yakalar,
// NetKonum singleton'una iletir.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

class NetKonumService : VpnService() {

    companion object {
        const val ACTION_BASLAT = "com.kanka.makro.NET_KONUM_BASLAT"
        const val ACTION_DURDUR = "com.kanka.makro.NET_KONUM_DURDUR"
        private const val KANAL_ID = "net_konum"
        private const val BILDIRIM_ID = 9201
    }

    private var vpnFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DURDUR -> {
                durdur()
                stopSelf()
            }
            else -> baslatVpn()
        }
        return START_NOT_STICKY
    }

    private fun baslatVpn() {
        kanalOlustur()
        val bildirim = Notification.Builder(this, KANAL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("NetKonum aktif")
            .setContentText("Oyun koordinatları ağdan okunuyor")
            .build()
        startForeground(BILDIRIM_ID, bildirim)

        val builder = Builder()
            .setSession("NetKonum")
            .addAddress("10.99.0.1", 24)
            .addDnsServer("8.8.8.8")
            // Sadece oyun sunucusu trafiğini tünele al
            .addRoute("82.38.10.210", 32)
            .addRoute("82.38.10.201", 32)
            .setMtu(32768)
        // Kendi uygulamamızı tünelden hariç tut (sonsuz döngüyü önler)
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val fd = builder.establish() ?: run {
            stopSelf()
            return
        }
        vpnFd = fd
        NetKonum.baslatFd(fd)
    }

    private fun durdur() {
        NetKonum.durdur()
        try { vpnFd?.close() } catch (_: Exception) {}
        vpnFd = null
        stopForeground(true)
    }

    override fun onDestroy() {
        durdur()
        super.onDestroy()
    }

    private fun kanalOlustur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(KANAL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAL_ID, "NetKonum", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
