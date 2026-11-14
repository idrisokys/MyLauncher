package io.github.idrisokys.myl.common

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.EditText
import java.io.File

/** Dosyalarım ve Galerim için ortak depolama izni (MyL'nin Notlarım/Link klasörü izniyle aynı: tüm dosyalara erişim). */
object StoragePerm {
    fun has(a: Activity): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** İzin yoksa sistemin izin ekranını açar. */
    fun request(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}")))
        else a.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }
}

/** [name] adı klasörde varsa ad_1, ad_2… diye çakışmayan bir dosya yolu döndürür. */
fun uniqueFile(d: File, name: String): File {
    var t = File(d, name); var n = 1
    val base = name.substringBeforeLast('.', name); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    while (t.exists()) { t = File(d, "${base}_$n$ext"); n++ }
    return t
}

/** Tek satırlık metin girişi diyaloğu. */
fun Activity.promptText(title: String, init: String, ok: (String) -> Unit) {
    val e = EditText(this).apply { setText(init); setSingleLine() }
    AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Tamam") { _, _ -> ok(e.text.toString().trim()) }.setNegativeButton("İptal", null).show()
}
