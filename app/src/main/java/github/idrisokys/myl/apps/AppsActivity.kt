package github.idrisokys.myl.apps

import android.app.Activity
import github.idrisokys.myl.common.Notice
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * Uygulamalar: yüklü uygulamaların listesi (en son yüklenen en üstte; ad, paket adı ve boyut).
 * Dokununca uygulama bilgisi, basılı tutunca kaldırma ekranı açılır.
 * Dosyalarım'dan ayrılıp kendi simgesi ve görevi olan bağımsız bir bölüm oldu (Notlarım/Galerim gibi).
 */
class AppsActivity : Activity() {
    private val bg = 0xFF121212.toInt(); private val bar = 0xFF212121.toInt()
    private class AppRow(val label: String, val pkg: String, val size: Long, val time: Long, val ai: ApplicationInfo)
    private var apps = listOf<AppRow>()
    private var adapter: BaseAdapter? = null
    private val iconCache = HashMap<String, Drawable>()
    private var loading = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun tv(t: String, s: Float, c: Int) = TextView(this).apply { text = t; textSize = s; setTextColor(c) }
    private fun sz(b: Long): String {
        var v = b.toDouble(); var i = 0; val u = arrayOf("B", "KB", "MB", "GB")
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format("%.1f %s", v, u[i])
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = bg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val tb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(16), 0, dp(16), 0) }
        tb.addView(tv("Uygulamalar", 21f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val count = tv("", 15f, 0xFF999999.toInt()).also { tb.addView(it, -2, -2) }

        val lv = ListView(this).apply { divider = ColorDrawable(0xFF2E2E2E.toInt()); dividerHeight = dp(1); setBackgroundColor(bg) }
        val ad = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val a = apps[p]
                val row = LinearLayout(this@AppsActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
                val ic = iconCache.getOrPut(a.pkg) { a.ai.loadIcon(packageManager) }
                row.addView(ImageView(this@AppsActivity).apply { setImageDrawable(ic) }, dp(50), dp(50))
                val c = LinearLayout(this@AppsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                c.addView(tv(a.label, 19f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                c.addView(tv("${a.pkg} • ${sz(a.size)}", 14f, 0xFF999999.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
                return row
            }
            override fun notifyDataSetChanged() { super.notifyDataSetChanged(); count.text = if (apps.isEmpty()) "" else "${apps.size}" }
        }
        adapter = ad; lv.adapter = ad
        lv.setOnItemClickListener { _, _, p, _ ->
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${apps[p].pkg}"))) }
            catch (e: Exception) { Notice.show(this, "Uygulama bilgisi açılamadı", false) }
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${apps[p].pkg}"))) }
            catch (e: Exception) { Notice.show(this, "Kaldırılamadı", false) }
            true
        }
        col.addView(tb, -1, dp(54)); col.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
    }

    /** Kaldırma/yükleme sonrası geri dönüldüğünde liste yenilenir. */
    override fun onResume() { super.onResume(); loadApps() }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        if (loading) return
        loading = true
        Thread {
            val l = try {
                val pm = packageManager
                pm.getInstalledPackages(0).mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    var size = try { File(ai.sourceDir).length() } catch (e: Exception) { 0L }
                    ai.splitSourceDirs?.forEach { size += File(it).length() }
                    AppRow(ai.loadLabel(pm).toString(), pi.packageName, size, pi.firstInstallTime, ai)
                }.sortedByDescending { it.time }
            } catch (e: Exception) { null }
            runOnUiThread {
                loading = false
                if (l != null) { apps = l; iconCache.keys.retainAll(l.map { it.pkg }.toSet()); adapter?.notifyDataSetChanged() }
            }
        }.start()
    }
}
