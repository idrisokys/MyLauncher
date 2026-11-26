package github.idrisokys.myl.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/**
 * Tek bir ekran görüntüsü alır ve kapanır. Erişilebilirlik izni gerekmez: yalnızca sistemin
 * "ekranı kaydet/yayınla" onayı (MediaProjection) kullanılır. Android 14+ bu işlem için ön plan servisi ister.
 */
class CaptureService : Service() {
    private val h = Handler(Looper.getMainLooper())

    companion object {
        /** Görüntü hazır olunca (ya da başarısız olursa null) ana iş parçacığında çağrılır. */
        @Volatile var onShot: ((Bitmap?) -> Unit)? = null
        private const val CH = "myl_ocr"
        private const val EX_CODE = "code"
        private const val EX_DATA = "data"

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).putExtra(EX_CODE, code).putExtra(EX_DATA, data))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val code = i?.getIntExtra(EX_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>(EX_DATA)
        startFg()
        if (data == null) { fail(); return START_NOT_STICKY }
        // Onay penceresinin kapanma animasyonu görüntüye girmesin diye kısa bekleme
        h.postDelayed({ try { capture(code, data) } catch (e: Exception) { fail() } }, 550)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Metin seç", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CH).setSmallIcon(github.idrisokys.myl.R.drawable.ic_text_select).build()
        startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun capture(code: Int, data: Intent) {
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            ?: run { fail(); return }
        val wm = getSystemService(WindowManager::class.java)
        val w: Int; val hgt: Int
        if (Build.VERSION.SDK_INT >= 30) { val b = wm.maximumWindowMetrics.bounds; w = b.width(); hgt = b.height() }
        else { val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); w = m.widthPixels; hgt = m.heightPixels }
        val dpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        var done = false
        fun finish(b: Bitmap?) {
            if (done) return
            done = true
            try { vd?.release() } catch (e: Exception) { }
            try { reader.close() } catch (e: Exception) { }
            try { mp.stop() } catch (e: Exception) { }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            onShot?.invoke(b)
        }
        // Android 14+: sanal ekran oluşturmadan önce geri çağrı kaydı zorunlu
        mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() {} }, h)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            val bmp = try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
            finish(bmp)
        }, h)
        vd = mp.createVirtualDisplay("myl-ocr", w, hgt, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, h)
        h.postDelayed({ finish(null) }, 4000)   // zaman aşımı
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        if (pad == 0) return full
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    }

    private fun fail() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); onShot?.invoke(null) }
}
