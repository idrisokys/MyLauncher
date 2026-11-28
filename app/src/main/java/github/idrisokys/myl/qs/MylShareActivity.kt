package github.idrisokys.myl.qs

import github.idrisokys.myl.common.Notice
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import github.idrisokys.myl.L
import java.io.File

/**
 * Sistemin Paylaş menüsündeki "MyL ile paylaş" simgesi.
 * Paylaşılan dosyalar / metin, Notlarım ve Dosyalarım'daki cihaz seçme penceresiyle ([SendPopup]) aynı pencerede
 * gösterilir; cihaza dokununca içerik Hızlı Paylaş ile o cihaza gönderilir. Arka plan şeffaftır, pencere kapanınca etkinlik de kapanır.
 */
class MylShareActivity : Activity() {
    private var popup: SendPopup? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Identity.init(this)
        val uris = collect()
        if (uris.isEmpty()) {
            Notice.showThenFinish(this, L("Paylaşılacak bir şey bulunamadı", "Nothing to share"), false)
            return
        }
        // Gönderim için "Tüm dosyalara erişim" gerekmez (içerik doğrudan okunur); alıcı servisi açık değilse
        // SendPopup kendi cihaz aramasını yapar.
        val p = SendPopup(this, { window.decorView }, center = true)
        p.onClosed = { finish() }
        popup = p
        window.decorView.post { if (!isFinishing) p.showForUris(uris) }
    }

    @Suppress("DEPRECATION")
    private fun collect(): List<Uri> {
        val out = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) out.add(u)
            }
        }
        // Dosya yoksa ama metin/bağlantı paylaşıldıysa: geçici bir .txt dosyası olarak gönderilir
        if (out.isEmpty()) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isNotBlank()) {
                try {
                    val dir = File(cacheDir, "myl_share").apply { mkdirs() }
                    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
                    val title = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString().orEmpty()
                    val base = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifBlank { "Not" }
                    var f = File(dir, "$base.txt"); var n = 1
                    while (f.exists()) { f = File(dir, "$base ($n).txt"); n++ }
                    f.writeText(text)
                    out.add(Uri.fromFile(f))
                } catch (e: Exception) { }
            }
        }
        return out
    }

    override fun onDestroy() { popup?.dispose(); super.onDestroy() }
}
