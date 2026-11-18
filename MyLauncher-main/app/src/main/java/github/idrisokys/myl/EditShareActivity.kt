package github.idrisokys.myl

import github.idrisokys.myl.common.Notice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.fossify.editor.activities.EditActivity

/**
 * Arayüzsüz: Paylaş menüsündeki "Resmi Editle". Paylaşılan resmi Galerim'deki sihirli değneğin
 * açtığı temel resim düzenleyicisinde açar. Düzenleyiciden çıkınca paylaşımı başlatan uygulamaya dönülür.
 */
class EditShareActivity : Activity() {
    private val editable = setOf("image/jpeg", "image/png", "image/webp")   // Galerim'deki EDITABLE ile aynı türler

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data ?: if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null) { Notice.showThenFinish(this, "Resim bulunamadı", false); return }

        val type = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (type != null && type.startsWith("image/") && type !in editable) {
            Notice.showThenFinish(this, "Bu resim türü düzenlenemez (jpg, png, webp)", false)
            return
        }
        try {
            // Okuma izni düzenleyiciye de aktarılır (paylaşan uygulamanın content:// adresi için gerekli)
            startActivity(EditActivity.newIntent(this, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish()
        } catch (e: Throwable) {
            Notice.showThenFinish(this, "Düzenleyici açılamadı", false)
        }
    }
}
