package com.kanka.makro

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Ekran videosunu (H.264 / MP4) botun zaten sahip oldugu ekran izniyle (MediaProjection)
 * kaydeder. Ikinci bir izin penceresi CIKMAZ: CaptureService'in projeksiyonuna, ikinci bir
 * VirtualDisplay olarak "biner". Boylece bot kendi (kucuk/hizli) goruntusunu almaya devam
 * ederken, video AYRICA gercek ekran cozunurlugunde kaydedilir.
 *
 * Kisitlama: Sadece bot en az bir kez NORMAL modda (Kayit uyumlu KAPALI) baslatilip
 * CaptureService calisirken kullanilabilir; kendi basina yeni bir ekran izni istemez.
 */
object VideoKaydedici {

    @Volatile var kayitta = false
        private set

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var vDisplay: VirtualDisplay? = null
    private var inputSurface: android.view.Surface? = null
    private var trackIndex = -1
    private var muxerBasladi = false
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var cikanUri: Uri? = null
    private var cikanDosya: File? = null
    private var baslangicMs = 0L

    private val thread = HandlerThread("video-kayit")
    private val h: Handler by lazy { Handler(thread.looper) }
    @Volatile private var drainDevam = false

    /** true = basladi. Hata olursa false ve nedeni toast ile bildirilir (ctx uzerinden). */
    fun baslat(ctx: Context): Boolean {
        if (kayitta) return true
        val cs = CaptureService.instance
        val projection = cs?.canliProjeksiyon()
        if (cs == null || projection == null) {
            return false   // cagiran taraf uygun mesaji gosterir
        }
        return try {
            baslatIc(ctx, projection, cs.gercekBoyut())
            true
        } catch (e: Exception) {
            temizle()
            false
        }
    }

    private fun baslatIc(ctx: Context, projection: MediaProjection, boyut: Pair<Int, Int>) {
        if (!thread.isAlive) thread.start()
        var (w, h0) = boyut
        // Kodlayicilar genelde ciftesayi genislik/yukseklik ister
        w -= w % 2
        h0 -= h0 % 2
        val pikseller = w.toLong() * h0.toLong()
        val bitRate = when {
            pikseller >= 1920L * 1080L -> 10_000_000
            pikseller >= 1280L * 720L -> 6_000_000
            else -> 3_500_000
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h0).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        codec = enc
        inputSurface = surface

        val ad = "Projeindirpedal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".mp4"
        val (mx, uri, dosya, pf) = muxerOlustur(ctx, ad)
        muxer = mx
        cikanUri = uri
        cikanDosya = dosya
        pfd = pf
        muxerBasladi = false
        trackIndex = -1

        val dpi = ctx.resources.displayMetrics.densityDpi
        vDisplay = projection.createVirtualDisplay(
            "makro_video", w, h0, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, this.h
        )

        baslangicMs = android.os.SystemClock.elapsedRealtime()
        drainDevam = true
        kayitta = true
        this.h.post(drainGorevi)
    }

    /** API 29+: MediaStore uzerinden dogrudan Movies/Projeindirpedal klasorune (Galeri'de gorunur) */
    private fun muxerOlustur(ctx: Context, ad: String): DortluResult {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, ad)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Projeindirpedal")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("MediaStore insert basarisiz")
            val pf = ctx.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Dosya tanimlayici alinamadi")
            val mx = MediaMuxer(pf.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return DortluResult(mx, uri, null, pf)
        } else {
            val klasor = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Projeindirpedal")
            if (!klasor.exists()) klasor.mkdirs()
            val dosya = File(klasor, ad)
            val mx = MediaMuxer(dosya.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return DortluResult(mx, null, dosya, null)
        }
    }

    private data class DortluResult(
        val muxer: MediaMuxer, val uri: Uri?, val dosya: File?, val pfd: android.os.ParcelFileDescriptor?
    )

    private val drainGorevi = object : Runnable {
        override fun run() {
            val enc = codec
            val mx = muxer
            if (!drainDevam || enc == null || mx == null) return
            try {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, 0)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = mx.addTrack(enc.outputFormat)
                            mx.start()
                            muxerBasladi = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)
                            if (buf != null && info.size > 0 && muxerBasladi) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mx.writeSampleData(trackIndex, buf, info)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                bitir()
                                return
                            }
                        }
                        else -> return   // TRY_AGAIN_LATER / OUTPUT_BUFFERS_CHANGED: bir sonraki turda bak
                    }
                }
            } catch (e: Exception) {
                bitir()
                return
            }
            if (drainDevam) h.postDelayed(this, 20)
        }
    }

    /** Kaydi durdurur; dosyayi Galeri'de gorunur hale getirir. */
    fun durdur(ctx: Context) {
        if (!kayitta) return
        try {
            codec?.signalEndOfInputStream()
        } catch (e: Exception) {
            bitir()
        }
        // drainGorevi EOS'u gorunce bitir()'i kendi cagirir; guvence icin bir sinir da koy
        h.postDelayed({ if (kayitta) bitir() }, 1500)
    }

    private fun bitir() {
        if (!kayitta) return
        drainDevam = false
        kayitta = false
        try { vDisplay?.release() } catch (e: Exception) {}
        try { inputSurface?.release() } catch (e: Exception) {}
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { if (muxerBasladi) muxer?.stop() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        val uri = cikanUri
        val pf = pfd
        try { pf?.close() } catch (e: Exception) {}
        if (uri != null) {
            try {
                val ctx = MacroService.instance
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                ctx?.contentResolver?.update(uri, cv, null, null)
            } catch (e: Exception) {
            }
        } else {
            cikanDosya?.let { f ->
                try {
                    MacroService.instance?.sendBroadcast(
                        android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))
                    )
                } catch (e: Exception) {
                }
            }
        }
        vDisplay = null
        inputSurface = null
        codec = null
        muxer = null
        pfd = null
        cikanUri = null
        cikanDosya = null
        muxerBasladi = false
    }

    /** CaptureService kapanirsa (bot durdu, izin gitti) yarim kalan videoyu guvenle bitir */
    fun projeksiyonKesildi() {
        if (kayitta) h.post { bitir() }
    }

    fun sureSaniye(): Long =
        if (kayitta) (android.os.SystemClock.elapsedRealtime() - baslangicMs) / 1000 else 0

    private fun temizle() {
        drainDevam = false
        kayitta = false
        try { vDisplay?.release() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
        vDisplay = null; codec = null; muxer = null; pfd = null; cikanUri = null; cikanDosya = null
    }
}
