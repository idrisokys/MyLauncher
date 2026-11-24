package github.idrisokys.myl

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wallhaven'dan yalnızca 1080x1920, en popüler (toplist) görselleri sırayla uygular.
 * Her çağrı bir sonraki görsele geçer; sıra ve sayfa SharedPreferences'ta saklanır.
 */
object WallhavenNext {
    private val busy = AtomicBoolean(false)
    private const val API = "https://wallhaven.cc/api/v1/search" +
        "?sorting=toplist&topRange=1y&resolutions=1080x1920&categories=111&purity=100&page="

    /** Ağ ve duvar kağıdı işi arka planda yapılır; [onStatus] ana thread dışından çağrılır. */
    fun next(ctx: Context, onStatus: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) { onStatus("Önceki görsel hâlâ yükleniyor"); return }
        val app = ctx.applicationContext
        Thread {
            val msg = try { run(app) } catch (e: Exception) { "Duvar kağıdı alınamadı" }
            busy.set(false)
            onStatus(msg)
        }.start()
    }

    private fun run(app: Context): String {
        val p = app.getSharedPreferences("wallhaven", 0)
        var page = p.getInt("page", 1)
        var idx = p.getInt("idx", 0)
        var list = (p.getString("list", "") ?: "").split("\n").filter { it.isNotEmpty() }
        var cachedPage = p.getInt("list_page", 0)

        if (list.isEmpty() || cachedPage != page) {
            list = fetchPage(page)
            if (list.isEmpty() && page > 1) { page = 1; idx = 0; list = fetchPage(1) }
            if (list.isEmpty()) return "Uygun görsel bulunamadı"
            cachedPage = page
        }
        if (idx >= list.size) {
            page++
            idx = 0
            list = fetchPage(page)
            if (list.isEmpty()) { page = 1; list = fetchPage(1) }
            if (list.isEmpty()) return "Uygun görsel bulunamadı"
            cachedPage = page
        }

        val bmp = download(list[idx]) ?: return "Görsel indirilemedi"
        val wm = WallpaperManager.getInstance(app)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return "Bu cihaz duvar kağıdı değiştirmeyi desteklemiyor"
        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)

        p.edit()
            .putInt("page", page).putInt("idx", idx + 1)
            .putInt("list_page", cachedPage).putString("list", list.joinToString("\n"))
            .apply()
        return "Duvar kağıdı güncellendi"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "MyL-Launcher/1.0")
        }

    private fun fetchPage(page: Int): List<String> {
        val c = open(API + page)
        try {
            if (c.responseCode != 200) return emptyList()
            val data = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("data")
            val out = ArrayList<String>()
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                if (o.optString("resolution") == "1080x1920") out.add(o.getString("path"))
            }
            return out
        } finally { c.disconnect() }
    }

    private fun download(url: String): android.graphics.Bitmap? {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            return c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { c.disconnect() }
    }
}
