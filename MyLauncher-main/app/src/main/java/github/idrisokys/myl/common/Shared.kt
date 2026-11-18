package github.idrisokys.myl.common

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
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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

/**
 * Silmek yerine dosyayı ana dizindeki gizli ".onemsiz" klasörüne taşır (aynı ad varsa ad_1, ad_2…).
 * Klasörde .nomedia olduğundan taşınan resimler hiçbir galeride görünmez; Galerim de gizli klasörleri taramaz.
 */
fun moveToOnemsiz(f: File): Boolean = try {
    val d = File(Environment.getExternalStorageDirectory(), ".onemsiz")
    d.mkdirs()
    try { File(d, ".nomedia").createNewFile() } catch (e: Exception) { }
    val t = uniqueFile(d, f.name)
    if (f.renameTo(t)) true
    else if (f.isDirectory) {
        // Aynı bölümde değilse klasör içeriğiyle kopyalanır, başarılıysa kaynak silinir
        if (f.copyRecursively(t, overwrite = false)) { f.deleteRecursively(); true } else { t.deleteRecursively(); false }
    } else {
        val lm = f.lastModified()
        f.copyTo(t, overwrite = false)
        t.setLastModified(lm)
        if (f.delete()) true else { t.delete(); false }
    }
} catch (e: Exception) { false }

/** Dosya zaten ana dizindeki .onemsiz klasörünün içinde mi? (Orada "Sil" kalıcı siler; yoksa sonsuz taşıma olurdu.) */
fun isInOnemsiz(f: File): Boolean =
    f.absolutePath.startsWith(File(Environment.getExternalStorageDirectory(), ".onemsiz").absolutePath + File.separator)

/** Tek satırlık metin girişi diyaloğu. */
fun Activity.promptText(title: String, init: String, ok: (String) -> Unit) {
    val e = EditText(this).apply { setText(init); setSingleLine() }
    AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Tamam") { _, _ -> ok(e.text.toString().trim()) }.setNegativeButton("İptal", null).show()
}

/**
 * Tüm bildirim mesajları (eski Toast yerine): arka planı ŞEFFAF, sadece okunur kalması için gölgeli beyaz yazı.
 * Ekranın altında, açık olan bölümün (Activity) üstünde belirir ve kendiliğinden kaybolur.
 * Sistem Toast'ının arka planı değiştirilemediği için (Android 11+) kendi katmanımızı çiziyoruz.
 */
object Notice {
    private val handler = Handler(Looper.getMainLooper())
    private var shown: TextView? = null
    private var remove: Runnable? = null

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
        return null
    }

    /** Mesaj süresi (ms). */
    fun duration(long: Boolean) = if (long) 3500L else 2000L

    fun show(c: Context, msg: CharSequence, long: Boolean = false) {
        val a = activityOf(c)
        val host = a?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById<ViewGroup>(android.R.id.content)
        if (host == null) { // ekran yoksa son çare sistem Toast'ı
            Toast.makeText(c.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show(); return
        }
        val act: Activity = a!!
        val d = c.resources.displayMetrics.density
        clear()
        val tv = TextView(act).apply {
            text = msg; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f * d, 0f, 1f * d, Color.BLACK)   // şeffaf zeminde her arka planda okunsun
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            maxWidth = (c.resources.displayMetrics.widthPixels * 0.92f).toInt()
            isClickable = false; isFocusable = false
            translationZ = 100f * d
        }
        host.addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (96 * d).toInt() })
        tv.bringToFront()
        shown = tv
        val r = Runnable { tv.animate().alpha(0f).setDuration(250).withEndAction { (tv.parent as? ViewGroup)?.removeView(tv); if (shown === tv) shown = null }.start() }
        remove = r
        handler.postDelayed(r, duration(long))
    }

    private fun clear() {
        remove?.let { handler.removeCallbacks(it) }
        shown?.let { (it.parent as? ViewGroup)?.removeView(it) }
        shown = null; remove = null
    }

    /** Başsız (arayüzsüz) bölümler için: mesajı gösterip süre dolunca activity'yi kapatır. */
    fun showThenFinish(a: Activity, msg: CharSequence, long: Boolean = false) {
        show(a, msg, long)
        Handler(Looper.getMainLooper()).postDelayed({ if (!a.isFinishing) a.finish() }, duration(long) + 150)
    }
}
