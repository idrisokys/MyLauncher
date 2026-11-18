package github.idrisokys.myl.gallery

import github.idrisokys.myl.common.*

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.io.File
import java.util.concurrent.Executors
import org.fossify.editor.activities.EditActivity

/**
 * Galerim: telefondaki tüm resim ve videoları tek ızgarada gösterir.
 * - Kalem simgesi: seçim modu (bu modda basılı tutup silme duraklatılır)
 * - Seçim modunda: tümünü seç, klasöre taşı, çöp (seçilenleri .onemsiz klasörüne taşı)
 */
class GalleryActivity : Activity() {
    private val bg = 0xFF121212.toInt(); private val bar = 0xFF212121.toInt(); private val accent = 0xFF4FA3F0.toInt()
    private lateinit var titleV: TextView
    private lateinit var selAllV: ImageView; private lateinit var moveV: ImageView; private lateinit var trashV: ImageView; private lateinit var penV: ImageView
    private lateinit var gv: GridView; private lateinit var emptyV: TextView
    private var gridAd: BaseAdapter? = null
    private val root: File = Environment.getExternalStorageDirectory()
    private val prefs by lazy { getSharedPreferences("gallery", 0) }
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
    private val VID = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private var media = listOf<File>()          // tüm resim + video, en yeni başta
    private var loaded = false; private var scanning = false; private var lastScan = 0L
    private var selMode = false
    private val sel = linkedSetOf<File>()
    private var asked = false
    private var viewer: Viewer? = null
    private val EDITABLE = setOf("jpg", "jpeg", "png", "webp")   // sihirli değnekle düzenlenebilen türler
    private val REQ_EDIT = 4711
    private var editing: File? = null

    companion object {
        /** Dosyalarım'dan bir resme dokunulunca: bu dosya doğrudan tam ekran gezginde açılır. */
        const val EXTRA_FILE = "github.idrisokys.myl.gallery.FILE"
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun tv(t: String, s: Float, c: Int) = TextView(this).apply { text = t; textSize = s; setTextColor(c) }
    private fun toast(m: String) = Notice.show(this, m, false)
    private fun uri(f: File) = KFileProvider.uriFor(this, f)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = bg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val tb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(12), 0, dp(6), 0) }
        titleV = tv("Galerim", 21f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        fun ib(ic: Drawable, desc: String, vis: Int, click: () -> Unit) = ImageView(this).apply {
            setImageDrawable(ic); setColorFilter(Color.WHITE); contentDescription = desc
            setPadding(dp(9), dp(9), dp(9), dp(9)); visibility = vis; setOnClickListener { click() }
        }
        selAllV = ib(Icons.selectAll(), "Tümünü seç", View.GONE) { selectAll() }
        moveV = ib(Icons.move(), "Klasöre taşı", View.GONE) { moveSelected() }
        trashV = ib(Icons.delete(), "Seçilenleri .onemsiz klasörüne taşı", View.GONE) { deleteSelected() }   // kalemin hemen solunda
        penV = ib(Icons.rename(), "Seç", View.VISIBLE) { setSelMode(!selMode) }
        tb.addView(titleV, LinearLayout.LayoutParams(0, -2, 1f))
        for (b in listOf(selAllV, moveV, trashV, penV)) tb.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)))

        gv = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(2); verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH; setBackgroundColor(bg)
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        emptyV = tv("", 16f, 0xFF999999.toInt()).apply { gravity = Gravity.CENTER }
        gv.emptyView = emptyV
        val ad = object : BaseAdapter() {
            override fun getCount() = media.size
            override fun getItem(p: Int) = media[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val c = (v as? Cell) ?: Cell()
                val f = media[p]
                c.badge.visibility = if (isVid(f)) View.VISIBLE else View.GONE
                c.setSel(selMode, f in sel)
                c.img.tag = f.path
                val hit = thumbs.get(f.path)
                if (hit != null) c.img.setImageBitmap(hit)
                else {
                    c.img.setImageDrawable(null)
                    pool.execute {
                        if (c.img.tag == f.path) {
                            val b = loadThumb(f)
                            if (b != null) runOnUiThread { if (c.img.tag == f.path) c.img.setImageBitmap(b) }
                        }
                    }
                }
                return c
            }
        }
        gridAd = ad; gv.adapter = ad
        gv.setOnItemClickListener { _, _, p, _ ->
            if (p !in media.indices) return@setOnItemClickListener
            val f = media[p]
            if (selMode) { if (!sel.remove(f)) sel.add(f); selectionChanged() }
            else Viewer(media.toMutableList(), p) { gridAd?.notifyDataSetChanged(); updateChrome(); updateEmpty() }
        }
        gv.setOnItemLongClickListener { _, _, p, _ ->
            // Kalem (seçim) modu açıkken duraklatılır; kapalıyken basılı tutulan resim .onemsiz klasörüne taşınır.
            if (!selMode && p in media.indices) removeMedia(media[p])
            true
        }
        val body = FrameLayout(this)
        body.addView(gv, FrameLayout.LayoutParams(-1, -1)); body.addView(emptyV, FrameLayout.LayoutParams(-1, -1))
        col.addView(tb, -1, dp(54)); col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        updateChrome(); updateEmpty()
        openRequested(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequested(intent)
    }

    /**
     * Dosyalarım'dan gelen resmi hemen tam ekranda açar (tüm depolamanın taranmasını beklemez):
     * aynı klasördeki resim/videolar kaydırılabilir. Gezgin kapanınca Galerim de kapanır ve Dosyalarım'a dönülür.
     */
    private fun openRequested(i: Intent?) {
        val p = i?.getStringExtra(EXTRA_FILE) ?: return
        i.removeExtra(EXTRA_FILE)
        val f = File(p)
        if (!f.isFile) { toast("Dosya bulunamadı"); return }
        val sibs = (f.parentFile?.listFiles() ?: emptyArray<File>())
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase().let { e -> e in IMG || e in VID } }
            .sortedByDescending { it.lastModified() }.toMutableList()
        if (sibs.none { it.path == f.path }) sibs.add(0, f)
        viewer?.dlg?.dismiss()
        Viewer(sibs, sibs.indexOfFirst { it.path == f.path }) {
            gridAd?.notifyDataSetChanged(); updateChrome(); updateEmpty()
            if (!isFinishing) finish()
        }
    }

    private fun hasPerm() = StoragePerm.has(this)

    override fun onResume() {
        super.onResume()
        if (hasPerm()) { if (!loaded || System.currentTimeMillis() - lastScan > 20_000) scan() }
        else if (!asked) {
            asked = true
            StoragePerm.request(this)
        }
        updateEmpty()
    }

    /** Depolamadaki tüm resim/videoları bulur (Android klasörü ve gizli klasörler hariç), en yeni başta. */
    private fun scan() {
        if (scanning) return
        scanning = true
        Thread {
            val found = ArrayList<Pair<File, Long>>()
            try {
                val skip = File(root, "Android")
                root.walkTopDown().onEnter { it != skip && (it == root || !it.name.startsWith(".")) }
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { f -> val e = f.extension.lowercase(); if (e in IMG || e in VID) found.add(f to f.lastModified()) }
            } catch (e: Exception) {}
            val sorted = found.sortedByDescending { it.second }.map { it.first }
            runOnUiThread {
                scanning = false; loaded = true; lastScan = System.currentTimeMillis()
                media = sorted
                sel.retainAll(sorted.toSet())
                gridAd?.notifyDataSetChanged(); updateChrome(); updateEmpty()
            }
        }.start()
    }

    private fun updateEmpty() {
        emptyV.text = when {
            !hasPerm() -> "Tüm dosyalara erişim izni gerekli"
            !loaded -> "Yükleniyor…"
            else -> "Görüntü veya video bulunamadı"
        }
    }

    private fun updateChrome() {
        titleV.text = if (selMode) "${sel.size} seçili" else "Galerim"
        val vis = if (selMode) View.VISIBLE else View.GONE
        selAllV.visibility = vis; moveV.visibility = vis; trashV.visibility = vis
        val a = if (sel.isEmpty()) 0.4f else 1f
        moveV.alpha = a; trashV.alpha = a
        penV.setColorFilter(if (selMode) accent else Color.WHITE)
    }

    private fun selectionChanged() { updateChrome(); gridAd?.notifyDataSetChanged() }

    private fun setSelMode(on: Boolean) {
        selMode = on
        if (!on) sel.clear()
        selectionChanged()
    }

    private fun selectAll() {
        if (media.isNotEmpty() && sel.containsAll(media)) sel.clear() else sel.addAll(media)
        selectionChanged()
    }

    // ======================= SİL / TAŞI =======================

    /** Seçilenleri onay sormadan ana dizindeki gizli .onemsiz klasörüne taşır. */
    private fun deleteSelected() {
        if (sel.isEmpty()) { toast("Önce taşınacak resimleri seçin"); return }
        val items = sel.toList(); sel.clear()
        Thread {
            val gone = HashSet<String>()
            items.forEach { f -> if (moveToOnemsiz(f)) gone.add(f.path) }
            if (gone.isNotEmpty()) try { MediaScannerConnection.scanFile(this, gone.toTypedArray(), null, null) } catch (e: Exception) {}
            runOnUiThread {
                media = media.filter { it.path !in gone }
                gone.forEach { thumbs.remove(it) }
                if (gone.size < items.size) toast("${items.size - gone.size} öğe taşınamadı")
                selectionChanged(); updateEmpty()
            }
        }.start()
    }

    private fun moveSelected() {
        if (sel.isEmpty()) { toast("Önce taşınacak resimleri seçin"); return }
        val items = sel.toList()
        FolderPicker(items.size) { dest -> doMove(dest, items) }
    }

    private fun doMove(dest: File, items: List<File>) {
        if (!dest.isDirectory || !dest.canWrite()) { toast("Bu klasöre yazılamıyor"); return }
        prefs.edit().putString("lastDest", dest.path).apply()
        sel.clear(); selectionChanged()
        toast("Taşınıyor…")
        Thread {
            val moved = HashMap<String, File>()
            var failed = 0
            items.forEach { f ->
                if (!f.exists() || f.parentFile?.path == dest.path) return@forEach
                val t = uniqueFile(dest, f.name)
                var ok = false
                try {
                    if (f.renameTo(t)) ok = true
                    else {
                        f.copyTo(t, overwrite = false)
                        t.setLastModified(f.lastModified())
                        if (f.delete()) ok = true else t.delete()
                    }
                } catch (e: Exception) { try { t.delete() } catch (x: Exception) {} }
                if (ok) moved[f.path] = t else failed++
            }
            if (moved.isNotEmpty()) try {
                MediaScannerConnection.scanFile(this, (moved.keys + moved.values.map { it.path }).toTypedArray(), null, null)
            } catch (e: Exception) {}
            runOnUiThread {
                media = media.map { moved[it.path] ?: it }
                moved.keys.forEach { thumbs.remove(it) }
                toast(
                    if (moved.isEmpty() && failed == 0) "Taşınacak öğe yok"
                    else if (failed > 0) "${moved.size} öğe taşındı, $failed öğe taşınamadı"
                    else "${moved.size} öğe ${dest.name} klasörüne taşındı"
                )
                selectionChanged()
            }
        }.start()
    }

    /** Hedef klasör seçici: klasörlerde gezin, yeni klasör oluştur, "Buraya taşı". */
    private inner class FolderPicker(val count: Int, val onPick: (File) -> Unit) {
        val dlg = Dialog(this@GalleryActivity, android.R.style.Theme_Black_NoTitleBar)
        var dir: File = root
        var kids = listOf<File>()
        val pathV = tv("", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        val moveB = Button(this@GalleryActivity)
        val lv = ListView(this@GalleryActivity).apply { divider = ColorDrawable(0xFF2E2E2E.toInt()); dividerHeight = dp(1); setBackgroundColor(bg) }

        fun hasUp() = dir.path != root.path && dir.parentFile != null

        val ad = object : BaseAdapter() {
            override fun getCount() = kids.size + (if (hasUp()) 1 else 0)
            override fun getItem(p: Int): Any = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val row = LinearLayout(this@GalleryActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
                if (hasUp() && p == 0) {
                    row.addView(ImageView(this@GalleryActivity).apply { setImageDrawable(Icons.back()); setColorFilter(0xFFBBBBBB.toInt()); rotation = 90f; setPadding(dp(10), dp(10), dp(10), dp(10)) }, dp(48), dp(48))
                    row.addView(tv("Üst klasör", 18f, 0xFFBBBBBB.toInt()).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                } else {
                    val f = kids[p - (if (hasUp()) 1 else 0)]
                    row.addView(ImageView(this@GalleryActivity).apply { setImageDrawable(Icons.folder()) }, dp(48), dp(48))
                    row.addView(tv(f.name, 18f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                return row
            }
        }

        fun load(d: File) {
            dir = d
            kids = (d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()).sortedBy { it.name.lowercase() }
            pathV.text = if (d.path == root.path) "Ana bellek" else "Ana bellek" + d.path.removePrefix(root.path).replace("/", " › ")
            moveB.text = "Buraya taşı ($count)"
            ad.notifyDataSetChanged()
            lv.setSelection(0)
        }

        fun newFolderHere() {
            promptText("Yeni klasör", "") { n ->
                val safe = n.replace('/', '_').replace('\\', '_').trim()
                if (safe.isEmpty() || safe == "." || safe == "..") return@promptText
                val nd = File(dir, safe)
                if (nd.isDirectory || nd.mkdirs()) load(nd) else toast("Klasör oluşturulamadı")
            }
        }

        fun pick() {
            if (!dir.canWrite()) { toast("Bu klasöre yazılamıyor"); return }
            dlg.dismiss(); onPick(dir)
        }

        init {
            val top = LinearLayout(this@GalleryActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(4), 0, dp(12), 0) }
            top.addView(iconBtn(Icons.close(), "Vazgeç", 0f) { dlg.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            top.addView(pathV, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, 0, 0) })
            val bottom = LinearLayout(this@GalleryActivity).apply { setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)) }
            bottom.addView(Button(this@GalleryActivity).apply { text = "Yeni klasör"; setOnClickListener { newFolderHere() } }, LinearLayout.LayoutParams(0, -2, 1f))
            moveB.setOnClickListener { pick() }
            bottom.addView(moveB, LinearLayout.LayoutParams(0, -2, 1.3f))
            val rootV = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
            rootV.addView(top, LinearLayout.LayoutParams(-1, dp(54)))
            rootV.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
            rootV.addView(bottom, LinearLayout.LayoutParams(-1, -2))
            lv.adapter = ad
            lv.setOnItemClickListener { _, _, p, _ ->
                if (hasUp() && p == 0) dir.parentFile?.let { load(it) }
                else { val i = p - (if (hasUp()) 1 else 0); if (i in kids.indices) load(kids[i]) }
            }
            dlg.setContentView(rootV)
            dlg.window?.apply { setLayout(-1, -1); setBackgroundDrawable(ColorDrawable(bg)) }
            val last = File(prefs.getString("lastDest", null) ?: "")
            load(if (last.isDirectory) last else root)
            dlg.show()
        }
    }

    // ======================= GALERİ (resim + video) =======================
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val fullPool = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<String, Bitmap>(maxOf(4096, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun isVid(f: File) = f.extension.lowercase() in VID

    /** fit=true: en büyük kenar maxDim'i geçmesin (tam ekran). fit=false: en küçük kenar >= maxDim (küçük resim). */
    private fun decodeImg(f: File, maxDim: Int, fit: Boolean): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            if (fit) { while (maxOf(o.outWidth, o.outHeight) / s > maxDim) s *= 2 }
            else { while (minOf(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2 }
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val deg = try {
                when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) { 0 }
            if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        } catch (e: Throwable) { null }
    }

    private fun videoFrame(f: File, target: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val bm = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0) ?: return null
            val sc = target.toFloat() / minOf(bm.width, bm.height)
            if (sc < 1f) Bitmap.createScaledBitmap(bm, maxOf(1, (bm.width * sc).toInt()), maxOf(1, (bm.height * sc).toInt()), true) else bm
        } catch (e: Throwable) { null } finally { try { r.release() } catch (e: Exception) {} }
    }

    private fun loadThumb(f: File): Bitmap? {
        thumbs.get(f.path)?.let { return it }
        val b = if (isVid(f)) videoFrame(f, 360) else decodeImg(f, 360, false)
        if (b != null) thumbs.put(f.path, b)
        return b
    }

    /** Basılı tutulan resim/videoyu silmez: ana dizindeki gizli ".onemsiz" klasörüne taşır ve listeden kaldırır. Onay sormaz. */
    private fun removeMedia(f: File): Boolean {
        val ok = moveToOnemsiz(f)
        if (ok) {
            media = media.filter { it.path != f.path }
            sel.remove(f); thumbs.remove(f.path)
            try { MediaScannerConnection.scanFile(this, arrayOf(f.path), null, null) } catch (e: Exception) {}
            gridAd?.notifyDataSetChanged(); updateChrome(); updateEmpty()
        } else toast("Taşınamadı: ${f.name}")
        return ok
    }

    /** Sihirli değnek: resmi temel düzenleyicide açar. Düzenleyicinin geri okuyla Galerim'e (tam ekran gezgine) dönülür. */
    private fun editFile(f: File) {
        if (!f.exists()) { toast("Dosya bulunamadı"); return }
        try {
            editing = f
            startActivityForResult(EditActivity.newIntent(this, Uri.fromFile(f), realFilePath = f.path), REQ_EDIT)
        } catch (e: Throwable) { editing = null; toast("Düzenleyici açılamadı") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_EDIT) return
        val f = editing; editing = null
        if (res == RESULT_OK) {
            if (f != null) {
                thumbs.remove(f.path)             // üzerine yazıldıysa eski küçük resmi at
                viewer?.reload(f)                 // tam ekran gezginde yeni hâlini göster
            }
            gridAd?.notifyDataSetChanged()
            lastScan = 0L                         // onResume'da yeniden tara: "farklı kaydet" ile oluşan yeni dosya listede görünsün
        }
    }

    private fun shareOne(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: if (isVid(f)) "video/*" else "image/*"
        try {
            val u = uri(f)
            val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("", u)
            startActivity(Intent.createChooser(i, "Paylaş"))
        } catch (e: Exception) { Notice.show(this, "Paylaşılamadı", false) }
    }

    private fun iconBtn(ic: Drawable, desc: String, rot: Float, click: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageDrawable(ic); setColorFilter(Color.WHITE); rotation = rot; contentDescription = desc
            setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { click() }
        }

    /** Kare hücre: küçük resim + videolarda oynat rozeti + seçim işareti. */
    private inner class Cell : FrameLayout(this@GalleryActivity) {
        val img = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2A2A2A.toInt()) }
        val badge = ImageView(this@GalleryActivity).apply {
            setImageDrawable(Icons.play()); setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x99000000.toInt()) }
        }
        val shade = View(this@GalleryActivity).apply { setBackgroundColor(0x553C8DFF); visibility = View.GONE }
        val chk = ImageView(this@GalleryActivity).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); visibility = View.GONE }
        init {
            addView(img, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
            addView(chk, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }
        fun setSel(mode: Boolean, on: Boolean) {
            shade.visibility = if (mode && on) View.VISIBLE else View.GONE
            chk.visibility = if (mode) View.VISIBLE else View.GONE
            chk.setImageDrawable(if (on) Icons.check() else null)
            chk.background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                if (on) { setColor(accent); setStroke(dp(2), Color.WHITE) } else { setColor(0x44000000); setStroke(dp(2), 0xCCFFFFFF.toInt()) }
            }
        }
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
    }

    /** Tam ekran sayfa: resim ya da video. Video sayfa seçilince otomatik başlar. */
    private inner class Pg(val f: File, val onLong: () -> Unit) {
        val vid = isVid(f)
        val root = FrameLayout(this@GalleryActivity).apply { setBackgroundColor(Color.BLACK) }
        val img = ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val play = ImageView(this@GalleryActivity).apply {
            setImageDrawable(Icons.play()); setPadding(dp(20), dp(20), dp(20), dp(20)); visibility = View.GONE
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x99000000.toInt()) }
        }
        val seek = SeekBar(this@GalleryActivity).apply { max = 100 }
        var vv: VideoView? = null
        var dead = false
        var active = false
        var seeking = false
        val tick: Runnable = object : Runnable {
            override fun run() {
                val v = vv
                if (v == null || dead || !active) return
                val d = v.duration
                if (d > 0 && !seeking) { seek.max = d; seek.progress = v.currentPosition }
                ui.postDelayed(this, 250)
            }
        }

        fun activate() {
            if (!vid || dead) return
            active = true
            val v = vv ?: VideoView(this@GalleryActivity).also { vv = it; root.addView(it, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)) }
            img.visibility = View.VISIBLE; play.visibility = View.GONE; seek.progress = 0
            v.setOnPreparedListener { mp ->
                mp.isLooping = false
                if (active) { v.start(); ui.postDelayed({ if (active && !dead) img.visibility = View.GONE }, 500) }
            }
            v.setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) img.visibility = View.GONE; false }
            v.setOnCompletionListener { play.visibility = View.VISIBLE }
            v.setOnErrorListener { _, _, _ ->
                Notice.show(this@GalleryActivity, "Video oynatılamadı", false)
                img.visibility = View.VISIBLE; true
            }
            v.setVideoURI(Uri.fromFile(f))
            ui.removeCallbacks(tick); ui.post(tick)
        }

        fun deactivate() {
            active = false; ui.removeCallbacks(tick)
            vv?.let { try { it.stopPlayback() } catch (e: Exception) {}; root.removeView(it) }
            vv = null
            if (vid) { img.visibility = View.VISIBLE; play.visibility = View.GONE; seek.progress = 0 }
        }

        fun dispose() { dead = true; deactivate() }

        /** Düzenleyiciden dönünce resmi diskten yeniden yükler. */
        fun reload() {
            if (vid || dead) return
            fullPool.execute {
                val b = decodeImg(f, 2400, true)
                runOnUiThread { if (!dead && b != null) { img.setPadding(0, 0, 0, 0); img.setImageBitmap(b) } }
            }
        }

        fun pause() {
            val v = vv ?: return
            if (active && v.isPlaying) { v.pause(); play.visibility = View.VISIBLE }
        }

        fun toggle() {
            if (!vid || !active) return
            val v = vv ?: return
            if (v.isPlaying) { v.pause(); play.visibility = View.VISIBLE }
            else { v.start(); play.visibility = View.GONE; ui.removeCallbacks(tick); ui.post(tick) }
        }

        init {
            root.addView(img, FrameLayout.LayoutParams(-1, -1))
            if (vid) {
                root.addView(play, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
                root.addView(seek, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(16)) })
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) vv?.seekTo(p) }
                    override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) { seeking = false }
                })
            }
            root.isClickable = true
            root.setOnClickListener { toggle() }
            root.setOnLongClickListener { onLong(); true }
            fullPool.execute {
                val b = if (vid) loadThumb(f) else decodeImg(f, 2400, true)
                runOnUiThread {
                    if (!dead) {
                        if (b != null) img.setImageBitmap(b)
                        else if (!vid) { img.setPadding(dp(110), dp(110), dp(110), dp(110)); img.setImageDrawable(Icons.file()) }
                    }
                }
            }
        }
    }

    /** Tam ekran gezgin: parmakla kaydırma + üst barda geri/ileri + paylaş. */
    private inner class Viewer(val data: MutableList<File>, start: Int, val changed: () -> Unit) {
        val dlg = Dialog(this@GalleryActivity, android.R.style.Theme_Black_NoTitleBar)
        val holders = HashMap<Int, Pg>()
        var cur = start
        val pager = ViewPager(this@GalleryActivity)
        val nameV = tv("", 16f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD }
        val countV = tv("", 12f, 0xFFAAAAAA.toInt())
        val prevB = iconBtn(Icons.chevron(), "Önceki", 180f) { go(-1) }
        val nextB = iconBtn(Icons.chevron(), "Sonraki", 0f) { go(1) }
        val editB = iconBtn(Icons.magic(), "Düzenle", 0f) { if (data.isNotEmpty()) editFile(data[cur]) }

        val adapter = object : PagerAdapter() {
            override fun getCount() = data.size
            override fun isViewFromObject(v: View, o: Any) = v === o
            override fun getItemPosition(o: Any) = PagerAdapter.POSITION_NONE
            override fun instantiateItem(c: ViewGroup, p: Int): Any {
                val f = data[p]
                val pg = Pg(f) { delFile(f) }
                holders[p] = pg
                c.addView(pg.root, -1, -1)
                if (p == cur) pg.activate()
                return pg.root
            }
            override fun destroyItem(c: ViewGroup, p: Int, o: Any) {
                val v = o as View
                val e = holders.entries.firstOrNull { it.value.root === v }
                if (e != null) { e.value.dispose(); holders.remove(e.key) }
                c.removeView(v)
            }
        }

        fun refreshBar() {
            if (data.isEmpty()) return
            nameV.text = data[cur].name
            countV.text = "${cur + 1} / ${data.size}"
            prevB.alpha = if (cur > 0) 1f else 0.3f
            nextB.alpha = if (cur < data.size - 1) 1f else 0.3f
            editB.visibility = if (data[cur].extension.lowercase() in EDITABLE) View.VISIBLE else View.GONE   // video / gif / svg düzenlenmez
        }

        fun reload(f: File) { holders.values.firstOrNull { it.f.path == f.path }?.reload() }

        fun go(d: Int) { val n = cur + d; if (n in data.indices) pager.setCurrentItem(n, true) }

        fun delFile(f: File) {
            val i = data.indexOf(f)
            if (i < 0 || !removeMedia(f)) return
            data.removeAt(i)
            if (data.isEmpty()) { dlg.dismiss(); return }
            cur = minOf(i, data.size - 1)
            adapter.notifyDataSetChanged()
            pager.setCurrentItem(cur, false)
            refreshBar()
        }

        fun pauseVideo() { holders[cur]?.pause() }

        init {
            val top = LinearLayout(this@GalleryActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(4), 0, dp(4), 0) }
            top.addView(iconBtn(Icons.back(), "Geri", 0f) { dlg.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val col = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
            col.addView(nameV); col.addView(countV)
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(prevB, LinearLayout.LayoutParams(dp(44), dp(44)))
            top.addView(nextB, LinearLayout.LayoutParams(dp(44), dp(44)))
            top.addView(editB, LinearLayout.LayoutParams(dp(44), dp(44)))   // Paylaş'ın hemen solunda
            top.addView(iconBtn(Icons.share(), "Paylaş", 0f) { if (data.isNotEmpty()) shareOne(data[cur]) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val rootV = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
            rootV.addView(top, LinearLayout.LayoutParams(-1, dp(54)))
            rootV.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
            pager.adapter = adapter
            pager.setCurrentItem(cur, false)
            pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(p: Int) {
                    if (p != cur) { holders[cur]?.deactivate(); cur = p; holders[p]?.activate() }
                    refreshBar()
                }
            })
            dlg.setContentView(rootV)
            dlg.window?.apply {
                setLayout(-1, -1); setBackgroundDrawable(ColorDrawable(Color.BLACK))
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            dlg.setOnDismissListener {
                holders.values.toList().forEach { it.dispose() }; holders.clear()
                if (this@GalleryActivity.viewer === this@Viewer) this@GalleryActivity.viewer = null
                changed()
            }
            this@GalleryActivity.viewer = this@Viewer
            refreshBar()
            dlg.show()
        }
    }

    override fun onPause() { super.onPause(); viewer?.pauseVideo() }
    override fun onDestroy() { viewer?.dlg?.dismiss(); pool.shutdownNow(); fullPool.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (selMode) setSelMode(false) else super.onBackPressed() }
}
