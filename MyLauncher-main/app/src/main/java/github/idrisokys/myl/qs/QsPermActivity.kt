package github.idrisokys.myl.qs

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Arayüzsüz izin ekranı (yalnızca Hızlı Paylaş düğmesine ilk dokunuşta, izin eksikse açılır):
 * "Tüm dosyalara erişim" → alıcıyı başlatır ve kapanır.
 */
class QsPermActivity : Activity() {
    private var settingsOpened = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        storageStep()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 1) storageStep() else finishIfReady()
    }

    private fun storageStep() {
        if (SaveDir.direct(this)) { done(); return }
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            return
        }
        settingsOpened = true
        val pkg = Uri.parse("package:$packageName")
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
        catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { finish() }
        }
    }

    /** Ayarlar ekranından dönülünce izin verildiyse alıcıyı başlatır. */
    override fun onResume() {
        super.onResume()
        if (settingsOpened) finishIfReady()
    }

    private fun finishIfReady() { if (SaveDir.direct(this)) done() else finish() }

    private fun done() { ReceiverService.start(this); finish() }
}
