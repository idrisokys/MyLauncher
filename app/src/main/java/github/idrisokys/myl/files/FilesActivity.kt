package github.idrisokys.myl.files

import github.idrisokys.myl.L
import github.idrisokys.myl.Native
import github.idrisokys.myl.common.*

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import android.widget.*
import github.idrisokys.myl.R
import android.graphics.drawable.Drawable
import java.io.*
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import java.util.*

open class FilesActivity : Activity() {
    companion object { const val EXTRA_PATH = "github.idrisokys.myl.files.PATH" }

    private val PINNED_RED = 0xFFB71C1C.toInt()   // sabitlenmiş öğenin üç noktası
    private val bg = 0xFF121212.toInt(); private val bar = 0xFF092C29.toInt(); private val card = 0xFF1E1E1E.toInt()
    private lateinit var titleV: TextView; private lateinit var closeV: ImageView; private lateinit var titleRow: LinearLayout; private lateinit var body: FrameLayout; private lateinit var crumbS: HorizontalScrollView; private lateinit var crumbL: LinearLayout
    private var cur: File? = null; private var list: List<File>? = null; private var listTitle = ""
    private var editMode = false
    private lateinit var editV: ImageView   // sağ üstte seçim (düzenleme) modu düğmesi
    private var picking = false; private val sel = linkedSetOf<File>()
    private var clip = listOf<File>(); private var cut = false
    private var showHidden = true; private var asked = false
    private var pendingShare: List<Uri>? = null   // dışarıdan paylaşılan, kaydedilmeyi bekleyen dosyalar
    private val root: File = Environment.getExternalStorageDirectory()
    private val prefs by lazy { getSharedPreferences("files", 0) }
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
    private val TXT = setOf("txt", "md", "log", "json", "xml", "ini", "conf", "cfg", "yml", "yaml", "properties", "js", "css", "kt", "kts", "java", "py", "sh", "gradle", "srt", "tex")
    private val df = SimpleDateFormat("dd HH:mm", Locale.getDefault())

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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar) }
        titleV = tv("", 21f, Color.WHITE).apply { typeface = Fonts.bold(context); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        crumbL = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        crumbS = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(crumbL, -2, -1) }
        editV = ImageView(this).apply {
            setImageDrawable(Icons.editMode()); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
            contentDescription = "Seçim modu"
            setOnClickListener { toggleEditMode() }
            // Basılı tutunca: seçim modunu açar ve tümünü seçer; hepsi zaten seçiliyse seçimi temizler
            setOnLongClickListener {
                val all = items()
                if (editMode && all.isNotEmpty() && sel.containsAll(all)) sel.clear() else { editMode = true; sel.addAll(all) }
                refresh(true)
                true
            }
        }
        closeV = ImageView(this).apply {
            setImageDrawable(Icons.close()); setColorFilter(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
            contentDescription = "Seçimi kapat"
            setOnClickListener { sel.clear(); editMode = false; refresh(true) }
        }
        titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleV, LinearLayout.LayoutParams(-2, -2)); addView(closeV, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val center = FrameLayout(this).apply {
            addView(titleRow, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            addView(crumbS, FrameLayout.LayoutParams(-1, -1))
        }
        tb.setPadding(dp(12), 0, dp(12), 0); tb.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        tb.addView(editV, LinearLayout.LayoutParams(dp(40), dp(40)))
        body = FrameLayout(this)
        col.addView(tb, -1, dp(54)); col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        // Arka plan resmi: tüm ekranı kaplar (centerCrop), içerik üstünde
        val root = FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(ImageView(this@FilesActivity).apply { setImageResource(R.drawable.files_bg); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root); home()
        if (s == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleShare(intent)
        if (s == null) handlePick(intent)
        if (s == null) openRequestedPath(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handlePick(intent)
        openRequestedPath(intent)
    }

    /** Başka bir bölümden (örn. ana ekran) istenen klasörü doğrudan açar. */
    private fun openRequestedPath(i: Intent?) {
        val p = i?.getStringExtra(EXTRA_PATH) ?: return
        i.removeExtra(EXTRA_PATH)
        val d = File(p)
        if (d.isDirectory) { pendingShare = null; picking = false; browse(d) }
        else Notice.show(this, "Klasör bulunamadı: ${d.name}", false)
    }

    private fun hasPerm() = StoragePerm.has(this)

    override fun onDestroy() {
        sendPopup?.dispose()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (hasPerm()) { if (cur != null || list != null) refresh(true) }
        else if (!asked) {
            asked = true
            StoragePerm.request(this)
        }
    }

    private fun chrome(t: String, crumbs: Boolean = false) {
        titleV.text = t
        titleRow.visibility = if (crumbs) View.GONE else View.VISIBLE
        closeV.visibility = if (editMode) View.VISIBLE else View.GONE
        crumbS.visibility = if (crumbs) View.VISIBLE else View.GONE
        if (crumbs) buildCrumbs()
        // Seçim modu düğmesi: etkinken açık mor, değilken beyaz; paylaşım kaydı / klasör seçme ekranlarında ve ana listede yok
        val canEdit = pendingShare == null && !picking && (cur != null || list != null)
        editV.visibility = if (canEdit) View.VISIBLE else View.GONE
        editV.setColorFilter(if (editMode) 0xFFD0BCFF.toInt() else Color.WHITE)
    }

    private fun buildCrumbs() {
        crumbL.removeAllViews()
        fun chev() = ImageView(this).apply { setImageDrawable(Icons.chevron()); setColorFilter(0xFF888888.toInt()) }
        val last = cur ?: return
        crumbL.addView(ImageView(this).apply { setImageDrawable(Icons.homeColor()); setOnClickListener { if (pendingShare != null) browse(root) else home() } }, dp(34), dp(34))
        crumbL.addView(chev(), dp(24), dp(24))
        crumbL.addView(ImageView(this).apply { setImageDrawable(Icons.folder()); setOnClickListener { browse(root) } }, dp(34), dp(34))
        val chain = ArrayList<File>(); var f: File? = last
        while (f != null && f.path != root.path) { chain.add(0, f); f = f.parentFile }
        chain.forEach { d ->
            crumbL.addView(chev(), dp(24), dp(24))
            crumbL.addView(tv(d.name, 19f, if (d == last) Color.WHITE else 0xFFBBBBBB.toInt()).apply {
                maxLines = 1; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0); setOnClickListener { if (d != last) browse(d) }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        crumbS.post { crumbS.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun home() {
        cur = null; list = null; sel.clear(); editMode = false; picking = false; pendingShare = null; body.removeAllViews()
        chrome("Dosyalarım")
        val g = GridLayout(this).apply { columnCount = 3; setPadding(dp(6), dp(16), dp(6), dp(16)) }
        fun tile(ic: String, name: String, sub: String, click: () -> Unit, long: (() -> Unit)? = null, res: Drawable? = null, dashed: Boolean = false, big: Boolean = false) {
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(8), dp(2), dp(12)) }
            val i: View = when {
                res != null -> ImageView(this).apply { setImageDrawable(res); if (!big) setPadding(dp(2), dp(2), dp(2), dp(2)) }
                dashed -> ImageView(this).apply {
                    setImageDrawable(Icons.add()); setColorFilter(0xFF888888.toInt()); setPadding(dp(30), dp(30), dp(30), dp(30))
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(26).toFloat(); setStroke(dp(2), 0xFF555555.toInt(), dp(6).toFloat(), dp(4).toFloat()) }
                }
                else -> tv(ic, 36f, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(card); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF3A3A3A.toInt()) }
                }
            }
            val isz = if (big) 100 else 84
            val slot = FrameLayout(this).apply { addView(i, FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER)) }
            t.addView(slot, dp(100), dp(100))
            t.addView(tv(name, 17f, if (dashed) 0xFF888888.toInt() else 0xFFEEEEEE.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            t.setOnClickListener { click() }
            if (long != null) t.setOnLongClickListener { long(); true }
            g.addView(t, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        tile("", "Ana bellek", "", { browse(root) }, null, Icons.homeColor())
        tile("📥", "İndirilenler", "", { browse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }, null, Icons.folder())
        pinList().filter { it.exists() }.forEach { f ->
            tile("", f.name, "", {
                if (f.isDirectory) browse(f) else openAny(f)
            }, {
                savePins(pinList().filter { it.path != f.path })
                Notice.show(this, "Sabitleme kaldırıldı: ${f.name}", false); home()
            }, if (f.isDirectory) Icons.folder() else Icons.file())
        }
        body.addView(ScrollView(this).apply { addView(g) })
    }


    private fun pinList(): List<File> = (prefs.getString("hpins", "") ?: "").split("\n").filter { it.isNotEmpty() }.map { File(it) }
    private fun savePins(l: List<File>) { prefs.edit().putString("hpins", l.joinToString("\n") { it.path }).apply() }
    /** "İğnele": verilen öğeleri ana ekrana sabitler. */
    private fun pinToHome(x: List<File>) {
        if (x.isEmpty()) return
        val l = pinList().toMutableList(); var n = 0
        x.forEach { f -> if (l.none { it.path == f.path }) { l.add(f); n++ } }
        savePins(l); sel.clear(); editMode = false
        Notice.show(this, if (n > 0) "$n öğe Dosyalarım ana ekranına iğnelendi" else "Zaten ana ekranda iğneli", false)
        refresh()
    }

    private fun browse(d: File) { cur = d; list = null; if (!(pickReq && pickMulti)) sel.clear(); editMode = false; refresh() }

    /** Açık klasörün Rust'tan gelen meta verisi (boyut/tarih/alt öğe sayısı); arama listelerinde null. */
    private var metaMap: Map<String, Native.Meta>? = null

    private fun items(): List<File> {
        val c = cur
        var metas: Map<String, Native.Meta>? = null
        val base: List<File> = list ?: run {
            metas = if (c != null) Native.listDirMeta(c) else null
            val m = metas
            if (c != null && m != null) m.keys.map { File(c, it) } else (c?.listFiles()?.toList() ?: emptyList())
        }
        metaMap = metas
        var l = if (showHidden) base else base.filter { !it.name.startsWith(".") }
        if (picking) l = l.filter { it.isDirectory }
        val sorted = l.map { it to (metas?.get(it.name)?.mtime ?: it.lastModified()) }.sortedByDescending { it.second }.map { it.first }   // tarih dosya başına tek kez okunur
        // Sabitlenenler (üç nokta → Sabitle) listenin başında durur; kendi aralarında tarih sırası korunur
        val pins = topPins()
        return if (pins.isEmpty()) sorted else sorted.filter { it.path in pins } + sorted.filter { it.path !in pins }
    }

    /** Listede başa sabitlenen öğelerin yolları (ana ekrana sabitlemeden ayrıdır). */
    private fun topPins(): Set<String> = HashSet(prefs.getStringSet("lpins", emptySet()) ?: emptySet())
    private fun toggleTopPin(x: List<File>) {
        val pins = topPins().toMutableSet()
        if (x.all { it.path in pins }) pins.removeAll(x.map { it.path }.toSet()) else pins.addAll(x.map { it.path })
        prefs.edit().putStringSet("lpins", pins).apply()
        sel.clear(); editMode = false; refresh(true)
    }

    private fun iconView(f: File): View =
        ImageView(this).apply { setImageDrawable(if (f.isDirectory) Icons.folder() else if (f.extension.lowercase() in TXT) Icons.docBlue() else Icons.file()); setPadding(dp(1), dp(1), dp(1), dp(1)) }

    private var curLv: ListView? = null
    private var curAdapter: BaseAdapter? = null

    private fun updateChrome() {
        chrome(if (editMode) "${sel.size} seçili" else if (list != null) listTitle else if (cur == root) "Ana bellek" else cur?.name ?: "", cur != null && list == null && !editMode)
    }

    /** Seçim değişince listeyi yeniden kurmadan yerinde günceller (kaydırma bozulmaz, titreme olmaz). */
    private fun selectionChanged() {
        val ad = curAdapter
        if (ad == null || curLv?.isAttachedToWindow != true) { refresh(true); return }
        updateChrome()
        pickCountV?.text = pickText()
        ad.notifyDataSetChanged()
    }

    /** keepScroll=true: aynı listede kalındığında (seçim, tümünü seç, sil...) kaydırma konumunu korur. */
    private fun refresh(keepScroll: Boolean = false) {
        val oldLv = curLv
        var sPos = 0; var sTop = 0
        if (keepScroll && oldLv != null && oldLv.isAttachedToWindow) {
            sPos = oldLv.firstVisiblePosition
            sTop = oldLv.getChildAt(0)?.top ?: 0
        }
        curLv = null; curAdapter = null
        val l = items()
        val topPinSet = topPins()
        // Boyut/öğe sayısı ve tarih metinleri bir kez hesaplanır; satırlar kaydırmada yeniden kurulurken diske gidilmez
        val subTexts = ArrayList<String>(l.size); val dateTexts = ArrayList<String>(l.size)
        val mm = metaMap
        l.forEach { f ->
            val m = mm?.get(f.name)
            subTexts.add(if (m?.isDir ?: f.isDirectory) "${m?.children ?: (f.list()?.size ?: 0)} öğe" else sz(m?.size ?: f.length()))
            dateTexts.add(df.format(Date(m?.mtime ?: f.lastModified())))
        }
        updateChrome()
        val lv = ListView(this).apply { divider = ColorDrawable(0xFF457467.toInt()); dividerHeight = dp(1); setBackgroundColor(Color.TRANSPARENT) }
        curLv = lv
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = l.size
            override fun getItem(p: Int) = l[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: android.view.ViewGroup?): View {
                val f = l[p]
                val row = LinearLayout(this@FilesActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)); if (f in sel) setBackgroundColor(bar) }
                if (editMode) {
                    // Soldaki boş alan: sadece seç/kaldır
                    val hit = android.widget.FrameLayout(this@FilesActivity).apply {
                        setOnClickListener { toggle(f) }
                    }
                    row.addView(hit, LinearLayout.LayoutParams(dp(56), dp(56)))
                }
                row.addView(iconView(f), dp(48), dp(48))
                val c = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                // Ad: satır sonuna gelince alta devam eder (kısaltma yok)
                c.addView(tv(f.name, 19f, Color.WHITE))
                val sub = subTexts[p]
                // Altta: en solda boyut, en sağda tarih (gün + saat, örn. 09 14:54)
                val info = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.HORIZONTAL }
                info.addView(tv(sub, 14f, 0xFF999999.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                info.addView(tv(dateTexts[p], 14f, 0xFF999999.toInt()))
                c.addView(info, LinearLayout.LayoutParams(-1, -2))
                row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
                if (pendingShare == null && !picking && !pickReq) {
                    // Üç nokta: tek dokunuş menü (Sabitle / Taşı / Paylaş / Sil / Yeni klasör); seçim modu sağ üstteki düğmeden açılır
                    row.addView(ImageView(this@FilesActivity).apply {
                        // Sabitlenmiş öğe: üç nokta koyu kırmızı
                        setImageDrawable(Icons.more()); setColorFilter(if (f.path in topPinSet) PINNED_RED else Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
                        contentDescription = "Menü"
                        setOnClickListener { itemMenu(f, it) }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                return row
            }
        }
        curAdapter = lv.adapter as? BaseAdapter
        lv.setOnItemClickListener { _, _, p, _ -> val f = l[p]; if (editMode) Unit else if (f.isDirectory) browse(f) else if (pickReq) pickTap(f) else openAny(f) }
        lv.setOnItemLongClickListener { _, v, p, _ ->
            if (pendingShare != null || pickReq) return@setOnItemLongClickListener true
            val f = l[p]
            // seçim modunda satırın kendisi etkisiz (seçme sadece kutucuktan); aksi halde basılı tutunca ad değiştir
            if (!editMode) renameItem(f)
            true
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        if (pendingShare != null) wrap.addView(saveBar(), -1, dp(52))
        else if (pickReq) wrap.addView(pickBar(), -1, dp(52))
        else if (clip.isNotEmpty() && cur != null && list == null && !editMode) wrap.addView(pasteBar(), -1, dp(52))
        if (keepScroll && (sPos > 0 || sTop != 0)) lv.setSelectionFromTop(minOf(sPos, maxOf(0, l.size - 1)), sTop)
        curLv = lv
        body.removeAllViews(); body.addView(wrap)
    }


    /** Basılı tutulan dosya/klasörün adını değiştirir. */
    private fun renameItem(f: File) {
        // Dosyalarda uzantı gösterilmez/değiştirilmez; sadece ad düzenlenir (klasörlerde tüm ad)
        val dot = f.name.lastIndexOf('.')
        val ext = if (f.isFile && dot > 0) f.name.substring(dot) else ""
        val baseName = if (ext.isEmpty()) f.name else f.name.substring(0, dot)
        promptText("Yeni ad", baseName) { n ->
            val name = n.trim() + ext
            if (n.trim().isEmpty() || name == f.name) return@promptText
            if (name.contains('/')) { Notice.show(this, "Ad '/' içeremez", false); return@promptText }
            val t = File(f.parentFile, name)
            if (t.exists()) { Notice.show(this, "Bu adda bir öğe zaten var", false); return@promptText }
            if (!f.renameTo(t)) { Notice.show(this, "Yeniden adlandırılamadı", false); return@promptText }
            try { MediaScannerConnection.scanFile(this, arrayOf(f.path, t.path), null, null) } catch (e: Exception) {}
            val lp = topPins()
            if (f.path in lp) prefs.edit().putStringSet("lpins", (lp - f.path + t.path)).apply()
            sel.clear()
            list = list?.map { if (it.path == f.path) t else it }
            refresh(true)
        }
    }


    /** Sağ üstteki düğme: düzenleme (seçim) modunu açar / kapatır. */
    private fun toggleEditMode() { editMode = !editMode; sel.clear(); refresh(true) }

    /** Üç nokta menüsü. Öğe, birden çok seçilenin içindeyse işlem tüm seçilenlere uygulanır. */
    private fun itemMenu(f: File, anchor: View) {
        val targets = if (f in sel && sel.size > 1) sel.toList() else listOf(f)
        val pm = PopupMenu(this, anchor)
        // Sıra: Yeni klasör, Sil, Taşı, Paylaş, Ziple, Sabitle, İğnele
        pm.menu.add(0, 4, 0, "Yeni klasör")
        pm.menu.add(0, 3, 1, "Sil")
        pm.menu.add(0, 1, 2, "Taşı")
        pm.menu.add(0, 2, 3, "Paylaş")
        pm.menu.add(0, 7, 4, "Ziple")
        pm.menu.add(0, 5, 5, if (targets.all { it.path in topPins() }) "Sabiti kaldır" else "Sabitle")
        pm.menu.add(0, 6, 6, "İğnele")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                5 -> toggleTopPin(targets)
                6 -> pinToHome(targets)
                7 -> zipItems(targets)
                1 -> moveItems(targets); 2 -> shareFiles(targets)
                // Arama/özel listedeyken öğenin bulunduğu klasörde oluşturulur
                4 -> newFolder(if (list != null) f.parentFile else null)
                else -> deleteItems(targets)
            }
            true
        }
        pm.show()
    }

    private fun moveItems(x: List<File>) {
        clip = x; cut = true; sel.clear(); editMode = false; refresh()
        Notice.show(this, "Hedef klasöre gidip Yapıştır'a basın", false)
    }

    /** "Sil": öğeleri silmez, ana dizindeki gizli .onemsiz klasörüne taşır. .onemsiz içindekiler onayla kalıcı silinir. */
    private fun deleteItems(x: List<File>) {
        sel.clear()
        val purge = x.filter { isInOnemsiz(it) }
        val soft = x.filter { !isInOnemsiz(it) }
        fun done(msg: String) {
            if (list != null) list = list!!.filter { it.exists() }
            refresh(true)
            if (msg.isNotEmpty()) Notice.show(this, msg, false)
        }
        fun purgeAsk() {
            if (purge.isEmpty()) return
            AlertDialog.Builder(this).setMessage("${purge.size} öğe kalıcı olarak silinsin mi?").setPositiveButton("Sil") { _, _ ->
                Thread { purge.forEach { it.deleteRecursively() }; runOnUiThread { done("${purge.size} öğe silindi") } }.start()
            }.setNegativeButton("İptal", null).show()
        }
        if (soft.isEmpty()) { done(""); purgeAsk(); return }
        Thread {
            val n = soft.count { moveToOnemsiz(it) }
            runOnUiThread {
                done(if (n == soft.size) "$n öğe .onemsiz klasörüne taşındı" else "$n/${soft.size} öğe .onemsiz klasörüne taşınabildi")
                purgeAsk()
            }
        }.start()
    }

    private fun pasteBar(): View {
        val b = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(dp(16), 0, dp(8), 0) }
        b.addView(ImageView(this).apply { setImageDrawable(Icons.paste()); setColorFilter(Color.WHITE) }, dp(24), dp(24))
        b.addView(tv("Buraya ${if (cut) "taşı" else "kopyala"} (${clip.size})", 15f, Color.WHITE).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        b.setOnClickListener { paste() }
        b.addView(ImageView(this).apply { setImageDrawable(Icons.newFolder()); setColorFilter(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Yeni klasör"; setOnClickListener { newFolder() } }, dp(44), dp(44))
        b.addView(ImageView(this).apply { setImageDrawable(Icons.close()); setColorFilter(0xFFBBBBBB.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { clip = listOf(); refresh() } }, dp(44), dp(44))
        return b
    }

    /** Şu an açık olan klasörde yeni klasör oluşturur. */
    private fun newFolder(base: File? = null) {
        val d = base ?: cur
        if (d == null || (base == null && list != null)) { Notice.show(this, "Yeni klasör için bir klasörün içine girin", false); return }
        promptText("Yeni klasör", "Yeni klasör") { n ->
            val name = n.replace("/", "_")
            if (name.isNotEmpty()) {
                val t = uniqueFile(d, name)
                if (t.mkdirs()) refresh(true) else Notice.show(this, "Klasör oluşturulamadı", false)
            }
        }
    }

    private fun unzip(z: File) {
        val parent = z.parentFile ?: return
        val dest = uniqueFile(parent, z.nameWithoutExtension)
        Notice.show(this, "Çıkarılıyor…", false)
        Thread {
            var ok = true
            try {
                dest.mkdirs()
                val n = Native.unzip(z, dest)   // Rust (paralel, zip-slip korumalı); null = kütüphane yok → eski yol
                if (n != null) { if (n < 0) throw IOException("unzip") }
                else {
                    val dp = dest.canonicalPath + File.separator
                    ZipInputStream(BufferedInputStream(FileInputStream(z))).use { zin ->
                        var e = zin.nextEntry
                        while (e != null) {
                            val o = File(dest, e.name)
                            if (!o.canonicalPath.startsWith(dp)) throw SecurityException("zip-slip")
                            if (e.isDirectory) o.mkdirs() else { o.parentFile?.mkdirs(); FileOutputStream(o).use { zin.copyTo(it) } }
                            zin.closeEntry(); e = zin.nextEntry
                        }
                    }
                }
            } catch (e: Exception) { ok = false; dest.deleteRecursively() }
            runOnUiThread {
                Notice.show(this, if (ok) "Çıkarıldı: ${dest.name}" else "Zip açılamadı", true)
                if (list != null) list = list!! + (if (ok) listOf(dest) else emptyList())
                refresh()
            }
        }.start()
    }

    /** "Ziple": seçilen dosya/klasörleri anında ZIP'e paketler (aynı klasöre; tek öğede onun adıyla, çoklu seçimde klasör adıyla). */
    private fun zipItems(x: List<File>) {
        if (x.isEmpty()) return
        val parent = x[0].parentFile ?: return
        if (!parent.canWrite()) { Notice.show(this, "Bu klasöre yazılamıyor", false); return }
        val base = when {
            x.size == 1 -> if (x[0].isDirectory) x[0].name else x[0].nameWithoutExtension
            x.all { it.parentFile == parent } && parent.path != root.path -> parent.name
            else -> "Arsiv"
        }.ifEmpty { "Arsiv" }
        val dest = uniqueFile(parent, "$base.zip")
        sel.clear(); editMode = false; refresh(true)
        Notice.show(this, "Ziplenyor…", false)
        Thread {
            var ok = true
            try {
                val n = Native.zip(x, dest)   // Rust; null = kütüphane yok → eski yol
                if (n != null) { if (n < 0) throw IOException("zip") }
                else {
                    java.util.zip.ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zo ->
                        val skip = dest.canonicalPath
                        fun add(f: File, name: String) {
                            if (f.canonicalPath == skip) return
                            if (f.isDirectory) {
                                zo.putNextEntry(java.util.zip.ZipEntry("$name/")); zo.closeEntry()
                                f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") }
                            } else if (f.isFile) {
                                zo.putNextEntry(java.util.zip.ZipEntry(name))
                                FileInputStream(f).use { it.copyTo(zo) }
                                zo.closeEntry()
                            }
                        }
                        x.forEach { add(it, it.name) }
                    }
                }
            } catch (e: Exception) { ok = false; try { dest.delete() } catch (t: Exception) {} }
            runOnUiThread {
                Notice.show(this, if (ok) "Ziplendi: ${dest.name}" else "Zip oluşturulamadı", true)
                if (ok) {
                    if (list != null) list = list!! + dest
                    try { MediaScannerConnection.scanFile(this, arrayOf(dest.path), null, null) } catch (e: Exception) {}
                }
                refresh(true)
            }
        }.start()
    }

    private fun toggle(f: File) { if (!sel.remove(f)) sel.add(f); selectionChanged() }

    private fun uri(f: File) = KFileProvider.uriFor(this, f)

    private fun openAny(f: File) {
        if (f.extension.lowercase() == "zip") unzip(f) else if (f.extension.lowercase() == "apk") installApk(f)
        else if (f.extension.lowercase() in IMG) openInGallery(f)
        else if (f.extension.lowercase() == "txt") openInNotes(f) else open(f)
    }

    /** .txt dosyaları varsayılan olarak Notlarım'ın metin görüntüleme ekranında açılır. */
    private fun openInNotes(f: File) {
        try {
            startActivity(Intent(this, github.idrisokys.myl.NotesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(github.idrisokys.myl.EXTRA_OPEN_TXT, f.absolutePath))
        } catch (e: Exception) { open(f) }
    }

    /** Resimler varsayılan olarak Galerim'in tam ekran gezgininde açılır. */
    private fun openInGallery(f: File) {
        try {
            startActivity(Intent(this, github.idrisokys.myl.gallery.GalleryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(github.idrisokys.myl.gallery.GalleryActivity.EXTRA_FILE, f.absolutePath))
        } catch (e: Exception) { open(f) }
    }

    /** APK'ya dokununca yükleme ekranını açar; "bilinmeyen kaynak" izni yoksa önce o ayar açılır. */
    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Notice.show(this, "Önce bu uygulamaya yükleme izni verin, sonra APK'ya tekrar dokunun", true)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) { Notice.show(this, "Yükleyici bulunamadı", false) }
    }

    private fun open(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: ActivityNotFoundException) { Notice.show(this, "Açacak uygulama yok", false) }
    }

    /** "Paylaş": Notlarım ile aynı — seçili dosyalar Hızlı Paylaş ile paylaşıma açık cihazlara gönderilir; klasörler gönderilemez. */
    private fun shareFiles(x: List<File>) {
        val files = x.filter { it.isFile }
        if (files.isEmpty()) { Notice.show(this, L("Klasörler paylaşılamaz", "Folders cannot be shared"), false); return }
        sel.clear(); editMode = false; refresh(true)
        quickShareDialog(files)
    }

    private var sendPopup: github.idrisokys.myl.qs.SendPopup? = null

    /** Ana ekrandaki paylaşım penceresiyle aynı kod ([SendPopup]): cihaz arama, gönderme ve "Nasıl eşitlensin?". */
    private fun quickShareDialog(files: List<File>) {
        val p = sendPopup ?: github.idrisokys.myl.qs.SendPopup(this, { window.decorView }, center = true).also { sendPopup = it }
        p.showFor(files)
    }

    private fun paste() {
        val d = cur ?: return; val its = clip; val mv = cut; clip = listOf()
        Thread {
            its.forEach { f ->
                var t = File(d, f.name); var n = 1
                while (t.exists()) { t = File(d, "${f.nameWithoutExtension}_kopya$n" + (if (f.extension.isEmpty()) "" else "." + f.extension)); n++ }
                if (f.isDirectory && (t.path + "/").startsWith(f.path + "/")) return@forEach
                try { if (mv) { if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() } } else f.copyRecursively(t) } catch (e: Exception) {}
            }
            runOnUiThread { refresh() }
        }.start()
    }

    // ======================= DOSYA SEÇİCİ (sistem "Şuradan aç" listesinde Dosyalarım olarak görünür) =======================
    private var pickReq = false; private var pickMulti = false; private var pickMime: List<String> = emptyList()
    private var pickCountV: TextView? = null

    private fun handlePick(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_GET_CONTENT && i.action != Intent.ACTION_OPEN_DOCUMENT)) return
        pickReq = true; pickMulti = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val m = ArrayList<String>()
        i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { m.addAll(it) }
        i.type?.let { if (m.isEmpty()) m.add(it) }
        pickMime = m.filter { it != "*/*" }
        list = null; sel.clear(); editMode = false
        val last = File(prefs.getString("lastPick", null) ?: "")
        browse(if (last.isDirectory) last else root)
    }

    private fun pickAllowed(f: File): Boolean {
        if (pickMime.isEmpty()) return true
        val t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        return pickMime.any { p -> if (p.endsWith("/*")) t.startsWith(p.dropLast(1)) else t == p }
    }

    private fun pickText() = if (pickMulti) "Seç (${sel.size})" else "Bir dosya seçin"

    private fun pickTap(f: File) {
        if (!pickAllowed(f)) { Notice.show(this, "Bu dosya türü seçilemez", false); return }
        if (pickMulti) toggle(f) else finishPick(listOf(f))
    }

    private fun finishPick(files: List<File>) {
        val ok = files.filter { it.isFile }
        if (ok.isEmpty()) { Notice.show(this, "Önce bir dosya seçin", false); return }
        val uris = ok.map { uri(it) }
        val r = Intent().setData(uris[0]).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cd = ClipData.newUri(contentResolver, "dosya", uris[0])
        for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
        r.clipData = cd
        ok.first().parentFile?.let { prefs.edit().putString("lastPick", it.path).apply() }
        setResult(RESULT_OK, r); finish()
    }

    /** Seçici çubuğu: yeşil onay = seçilenleri gönder, kırmızı çarpı = vazgeç. */
    private fun pickBar(): View {
        val b = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(dp(16), 0, dp(12), 0) }
        val t = tv(pickText(), 15f, Color.WHITE); pickCountV = t
        b.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (pickMulti) b.addView(NoticeIcon(this, true).apply { contentDescription = "Seç"; setOnClickListener { finishPick(sel.toList()) } }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        b.addView(NoticeIcon(this, false).apply { contentDescription = "İptal"; setOnClickListener { setResult(RESULT_CANCELED); finish() } }, dp(40), dp(40))
        return b
    }

    // ======================= PAYLAŞIMLA GELEN DOSYALARI KAYDET =======================
    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val us = LinkedHashSet<Uri>()
        try {
            if (i.action == Intent.ACTION_SEND) i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { us.add(it) }
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { us.addAll(it) }
        } catch (e: Exception) {}
        i.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { us.add(it) } }
        if (us.isEmpty()) { Notice.show(this, "Paylaşılan dosya bulunamadı", false); return }
        pendingShare = us.toList()
        picking = true; list = null; sel.clear(); editMode = false
        val last = File(prefs.getString("lastSave", null) ?: "")
        browse(if (last.isDirectory) last else root)
    }

    private fun cancelShare() { pendingShare = null; picking = false; finish() }

    /** Paylaşımla gelen dosyaların kaydedileceği çubuk: taşıma çubuğuyla aynı görünür (Buraya kaydet (n) · yeni klasör · kapat). */
    private fun saveBar(): View {
        val n = pendingShare?.size ?: 0
        val b = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(dp(16), 0, dp(8), 0) }
        b.addView(ImageView(this).apply { setImageDrawable(Icons.paste()); setColorFilter(Color.WHITE) }, dp(24), dp(24))
        b.addView(tv("Buraya kaydet ($n)", 15f, Color.WHITE).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        b.setOnClickListener { saveShared(cur ?: root) }
        b.addView(ImageView(this).apply {
            setImageDrawable(Icons.newFolder()); setColorFilter(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Yeni klasör"
            setOnClickListener {
                val d = cur ?: root
                promptText("Yeni klasör", "") { nm ->
                    val safe = nm.replace('/', '_').replace('\\', '_').trim()
                    if (safe.isEmpty() || safe == "." || safe == "..") return@promptText
                    val nd = File(d, safe)
                    if (nd.isDirectory || nd.mkdirs()) browse(nd) else Notice.show(this@FilesActivity, "Klasör oluşturulamadı", false)
                }
            }
        }, dp(44), dp(44))
        b.addView(ImageView(this).apply {
            setImageDrawable(Icons.close()); setColorFilter(0xFFBBBBBB.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "İptal"
            setOnClickListener { cancelShare() }
        }, dp(44), dp(44))
        return b
    }

    private fun shareName(u: Uri): String {
        var name: String? = null
        if (u.scheme == "content") try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        } catch (e: Exception) {}
        var s: String = name?.takeIf { it.isNotBlank() } ?: u.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "dosya_${System.currentTimeMillis()}"
        s = s.replace('/', '_').replace('\\', '_').trim()
        if (s.isEmpty() || s == "." || s == "..") s = "dosya_${System.currentTimeMillis()}"
        if (!s.contains('.')) {
            val ext = try { contentResolver.getType(u)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } } catch (e: Exception) { null }
            if (ext != null) s += ".$ext"
        }
        return s
    }

    private fun saveShared(dest: File) {
        val us = pendingShare ?: return
        if (!dest.isDirectory || !dest.canWrite()) { Notice.show(this, "Bu klasöre yazılamıyor", false); return }
        pendingShare = null; picking = false
        prefs.edit().putString("lastSave", dest.path).apply()
        Notice.show(this, "Kaydediliyor…", false)
        Thread {
            val saved = ArrayList<String>()
            us.forEach { u ->
                var out: File? = null
                try {
                    val o = uniqueFile(dest, shareName(u)); out = o
                    val ins = contentResolver.openInputStream(u) ?: throw IOException("açılamadı")
                    ins.use { i -> FileOutputStream(o).use { i.copyTo(it) } }
                    saved.add(o.path)
                } catch (e: Exception) { try { out?.delete() } catch (x: Exception) {} }
            }
            runOnUiThread {
                val msg = when {
                    saved.isEmpty() -> "Dosya kaydedilemedi"
                    saved.size == us.size -> "${saved.size} dosya ${dest.name} klasörüne kaydedildi"
                    else -> "${saved.size}/${us.size} dosya kaydedildi"
                }
                Notice.show(this, msg, true)
                browse(dest)
            }
            if (saved.isNotEmpty()) try { MediaScannerConnection.scanFile(this, saved.toTypedArray(), null, null) } catch (e: Exception) {}
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            pendingShare != null -> { val c = cur; if (c == null || c == root || c.parentFile?.canRead() != true) cancelShare() else browse(c.parentFile!!) }
            editMode -> { sel.clear(); editMode = false; refresh() }
            list != null -> { list = null; if (cur != null) refresh() else home() }
            cur != null -> { val c = cur!!; if (c == root || c.parentFile?.canRead() != true) home() else browse(c.parentFile!!) }
            else -> super.onBackPressed()
        }
    }
}
