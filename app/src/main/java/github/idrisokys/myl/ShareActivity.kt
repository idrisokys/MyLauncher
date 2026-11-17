package github.idrisokys.myl

import github.idrisokys.myl.common.Notice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val app = applicationContext
        Thread {
            val root = Store.root(app)
            val msg = when {
                text.isBlank() -> L("Paylaşılacak bir şey bulunamadı", "Nothing to share")
                root == null -> L("Önce Notlarım’ı açıp depolama izni verin", "Open My Notes and grant storage permission first")
                Store.submit(app, root, text, subject) -> L("Kaydedildi", "Saved")
                else -> L("Kaydedilemedi", "Could not save")
            }
            runOnUiThread {
                Notice.showThenFinish(this, msg, false)
            }
        }.start()
    }
}
