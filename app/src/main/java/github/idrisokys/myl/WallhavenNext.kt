package github.idrisokys.myl

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Wallhaven'dan yalnızca 1080x1920, doğa temalı (genel + çizim/anime, insan kategorisi yok) en beğenilen (favorites) görsellerden
 * RASTGELE birini uygular. Rastgele bir sayfa seçilir; daha önce uygulanan görseller (son [SEEN_MAX] adet) atlanır,
 * böylece aynı resimler tekrar tekrar gelmez.
 */
object WallhavenNext {
    private val busy = AtomicBoolean(false)
    private const val API = "https://wallhaven.cc/api/v1/search" +
        "?q=nature&sorting=favorites&resolutions=1080x1920&categories=110&purity=100&page="
    private const val MAX_PAGE = 40     // en beğenilen ilk ~40 sayfa içinden seç (kalite + çeşitlilik)
    private const val SEEN_MAX = 300    // hatırlanan son görsel sayısı
    private const val TRIES = 5

    private class Item(val id: String, val path: String)
    private class Page(val items: List<Item>, val lastPage: Int)

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
        val p = app.getSharedPreferences("wallhaven_fav", 0)
        val seen = ArrayList((p.getString("seen", "") ?: "").split("\n").filter { it.isNotEmpty() })
        var maxPage = p.getInt("max_page", MAX_PAGE).coerceIn(1, MAX_PAGE)

        var pick: Item? = null
        var anyFetched = false
        for (attempt in 0 until TRIES) {
            val page = Random.nextInt(1, maxPage + 1)
            val res = try { fetchPage(page) } catch (e: Exception) { null } ?: continue
            anyFetched = true
            if (res.lastPage in 1 until maxPage) maxPage = res.lastPage
            val fresh = res.items.filter { it.id !in seen }
            if (fresh.isNotEmpty()) { pick = fresh.random(); break }
        }
        if (pick == null) {
            if (!anyFetched) return "Duvar kağıdı alınamadı (bağlantı?)"
            // Hepsi daha önce görülmüş: geçmişi sıfırla, bir dahaki seferde yine rastgele devam eder
            p.edit().remove("seen").apply()
            return "Tüm görseller denendi, liste sıfırlandı; tekrar dene"
        }

        val bmp = download(pick.path) ?: return "Görsel indirilemedi"
        val wm = WallpaperManager.getInstance(app)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return "Bu cihaz duvar kağıdı değiştirmeyi desteklemiyor"
        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)

        seen.add(pick.id)
        while (seen.size > SEEN_MAX) seen.removeAt(0)
        p.edit().putString("seen", seen.joinToString("\n")).putInt("max_page", maxPage).apply()
        return "Duvar kağıdı güncellendi"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "MyL-Launcher/1.0")
        }

    private fun fetchPage(page: Int): Page? {
        val c = open(API + page)
        try {
            if (c.responseCode != 200) return null
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val data = json.getJSONArray("data")
            val out = ArrayList<Item>()
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                if (o.optString("resolution") == "1080x1920") out.add(Item(o.getString("id"), o.getString("path")))
            }
            val last = json.optJSONObject("meta")?.optInt("last_page", 0) ?: 0
            return Page(out, last)
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
