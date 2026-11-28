package github.idrisokys.myl.ocr

import github.idrisokys.myl.common.Notice
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import github.idrisokys.myl.L

/**
 * Arayüzsüz (şeffaf) başlatıcı: sistemin ekran yakalama onayını ister, görüntüyü alır, okur ve
 * [TextSelectActivity]'yi açar. Hızlı Ayarlar kutucuğundan ve MyL menüsünden açılır.
 */
class TextGrabActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) { finish(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: "tek uygulama" seçeneğini gizle, yalnızca tüm ekran
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
        try { startActivityForResult(i, 1) } catch (e: Exception) { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc != 1 || res != RESULT_OK || data == null) { finish(); return }
        CaptureService.onShot = { bmp -> CaptureService.onShot = null; if (!isDestroyed) handle(bmp) }
        CaptureService.start(this, res, data)
    }

    private fun handle(bmp: Bitmap?) {
        if (bmp == null) { Notice.showThenFinish(this, L("Ekran alınamadı", "Could not capture the screen"), false); return }
        OcrEngine.run(bmp) { words ->
            if (isDestroyed) return@run
            if (words.isEmpty()) { Notice.showThenFinish(this, L("Metin bulunamadı", "No text found"), false); return@run }
            OcrHolder.bmp = bmp; OcrHolder.words = words
            startActivity(Intent(this, TextSelectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }


    override fun onDestroy() { CaptureService.onShot = null; super.onDestroy() }
}
