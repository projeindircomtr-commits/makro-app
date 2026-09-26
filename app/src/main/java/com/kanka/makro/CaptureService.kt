package com.kanka.makro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/** HP/MP barini okumak icin ekrani yari cozunurlukte, seyrek kareyle yakalar. */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var lastKeep = 0L
    private var curW = 0
    private var curH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (projection != null || intent == null) return START_NOT_STICKY

        val code = intent.getIntExtra("code", 0)
        val data = getData(intent)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                stopSelf()
            }

            // Android 14+: yakalanan icerigin boyutu degisti
            override fun onCapturedContentResize(width: Int, height: Int) {
                handler.post {
                    if (projection != null) {
                        val (w, h) = realSize()
                        if (w != curW || h != curH) rebuild()
                    }
                }
            }
        }, handler)

        handler.post {
            createDisplay()
            ScreenSampler.running = true
        }
        // Yon degisimini kacirmamak icin saniyede bir kontrol (dik -> yatay oyun)
        handler.postDelayed(yonKontrol, 1000)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
        else i.getParcelableExtra("data")

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Ekran okuma", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "cap")
            .setContentTitle("Projeindirpedal")
            .setContentText("HP/MP okunuyor")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** handler thread'inde calisir */
    private fun createDisplay() {
        val p = projection ?: return
        val (w, h) = realSize()
        curW = w
        curH = h
        val vw = (w * ScreenSampler.SCALE).toInt().coerceAtLeast(1)
        val vh = (h * ScreenSampler.SCALE).toInt().coerceAtLeast(1)

        val r = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            val now = SystemClock.uptimeMillis()
            // Saniyede en fazla ~10 kare tut, gerisini hemen at -> kasma yok
            if (now - lastKeep >= 90) {
                lastKeep = now
                ScreenSampler.offer(img)
            } else {
                img.close()
            }
        }, handler)
        reader = r

        val dpi = resources.displayMetrics.densityDpi
        val vd = vDisplay
        if (vd == null) {
            vDisplay = p.createVirtualDisplay(
                "makro", vw, vh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            vd.resize(vw, vh, dpi)
            vd.surface = r.surface
        }
    }

    private val yonKontrol = object : Runnable {
        override fun run() {
            if (projection == null) return
            val (w, h) = realSize()
            if (w != curW || h != curH) rebuild()
            handler.postDelayed(this, 1000)
        }
    }

    /** Ekran donunce (dikey/yatay) yakalama boyutunu yenile */
    private fun rebuild() {
        val old = reader
        createDisplay()
        ScreenSampler.clear()
        old?.setOnImageAvailableListener(null, null)
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({
            if (projection == null) return@postDelayed
            val (w, h) = realSize()
            if (w != curW || h != curH) rebuild()
        }, 300)
    }

    private fun release() {
        handler.removeCallbacks(yonKontrol)
        ScreenSampler.running = false
        ScreenSampler.clear()
        vDisplay?.release()
        vDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection = null
    }

    override fun onDestroy() {
        handler.post {
            val p = projection
            release()
            try {
                p?.stop()
            } catch (e: Exception) {
            }
        }
        thread.quitSafely()
        super.onDestroy()
    }
}
