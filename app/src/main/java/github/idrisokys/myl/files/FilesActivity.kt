package github.idrisokys.myl.files

import github.idrisokys.myl.common.*

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.view.DragEvent
import android.graphics.Color
import android.graphics.Typeface
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
import android.graphics.drawable.Drawable
import java.io.*
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import java.util.*

class FilesActivity : Activity() {
    companion object { const val EXTRA_PATH = "github.idrisokys.myl.files.PATH" }

    private val dockBg = 0xFF1A1A1A.toInt(); private val bg = 0xFF121212.toInt(); private val bar = 0xFF212121.toInt(); private val card = 0xFF1E1E1E.toInt()
    private lateinit var titleV: TextView; private lateinit var pinV: ImageView; private lateinit var titleRow: LinearLayout; private lateinit var cancelV: TextView; private lateinit var body: FrameLayout; private lateinit var crumbS: HorizontalScrollView; private lateinit var crumbL: LinearLayout; private lateinit var dock: LinearLayout
    private var cur: File? = null; private var list: List<File>? = null; private var listTitle = ""
    private var editMode = false
    private lateinit var selAllV: ImageView
    private var picking = false; private var slotPick = -1; private val sel = linkedSetOf<File>()
    private var clip = listOf<File>(); private var cut = false
    private var showHidden = true; private var asked = false
    private var pendingShare: List<Uri>? = null   // dışarıdan paylaşılan, kaydedilmeyi bekleyen dosyalar
    private val root: File = Environment.getExternalStorageDirectory()
    private val prefs by lazy { getSharedPreferences("files", 0) }
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
    private val TXT = setOf("txt", "md", "log", "json", "xml", "ini", "conf", "cfg", "yml", "yaml", "properties", "js", "css", "kt", "kts", "java", "py", "sh", "gradle", "srt", "tex")
    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

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
        val tb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar) }
        titleV = tv("", 21f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        crumbL = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        crumbS = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(crumbL, -2, -1) }
        cancelV = tv("İptal", 18f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(12), 0, 0, 0); setOnClickListener { sel.clear(); editMode = false; refresh(true) } }
        selAllV = ImageView(this).apply {
            setImageDrawable(Icons.selectAll()); setColorFilter(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
            contentDescription = "Tümünü seç"
            setOnClickListener {
                val all = items()
                if (all.isNotEmpty() && sel.containsAll(all)) sel.clear() else sel.addAll(all)
                selectionChanged()
            }
        }
        pinV = ImageView(this).apply {
            setImageDrawable(Icons.pin()); setColorFilter(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
            contentDescription = "Ana ekrana sabitle"; setOnClickListener { pinSelected() }
        }
        titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleV, LinearLayout.LayoutParams(-2, -2)); addView(pinV, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val center = FrameLayout(this).apply {
            addView(titleRow, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            addView(crumbS, FrameLayout.LayoutParams(-1, -1))
        }
        tb.setPadding(dp(12), 0, dp(12), 0); tb.addView(center, LinearLayout.LayoutParams(0, -1, 1f))
        tb.addView(selAllV, LinearLayout.LayoutParams(dp(40), dp(40)))
        tb.addView(cancelV, LinearLayout.LayoutParams(-2, -1))
        body = FrameLayout(this)
        dock = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(dockBg) }
        col.addView(tb, -1, dp(54)); col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(View(this).apply { setBackgroundColor(0xFF333333.toInt()) }, -1, dp(1))
        col.addView(dock, -1, dp(100))
        setContentView(col); migratePins(); updateDock(); home()
        if (s == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleShare(intent)
        if (s == null) openRequestedPath(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
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
        pinV.visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE
        crumbS.visibility = if (crumbs) View.VISIBLE else View.GONE
        if (crumbs) buildCrumbs()
        cancelV.visibility = if (editMode) View.VISIBLE else View.GONE
        selAllV.visibility = if (editMode) View.VISIBLE else View.GONE
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

    private fun updateDock() {
        dock.removeAllViews()
        for (n in 0 until 4) {
            val p = prefs.getString("slot$n", null)
            val f = if (p == null) null else File(p)
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (f == null) {
                v.addView(ImageView(this).apply {
                    setImageDrawable(Icons.add()); setColorFilter(0xFF888888.toInt()); setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat(); setStroke(dp(1), 0xFF555555.toInt(), dp(4).toFloat(), dp(3).toFloat()) }
                }, dp(54), dp(54))
                v.addView(tv("Ekle", 14f, 0xFF888888.toInt()).apply { gravity = Gravity.CENTER })
                v.setOnClickListener { if (pendingShare != null) return@setOnClickListener; slotPick = n; picking = true; browse(root) }
            } else {
                v.addView(ImageView(this).apply { setImageDrawable(Icons.folder()) }, dp(54), dp(54))
                v.addView(tv(f.name, 14f, 0xFFCCCCCC.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), 0) })
                v.setOnClickListener { if (f.isDirectory) { if (pendingShare == null) { picking = false; list = null }; browse(f) } else Notice.show(this, "Klasör bulunamadı", false) }
                v.setOnLongClickListener { if (pendingShare == null) slotMenu(n); true }
                // dosyaları bu klasöre sürükleyip bırakınca taşı
                v.setOnDragListener { view, e ->
                    when (e.action) {
                        DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.hasMimeType("text/plain") == true
                        DragEvent.ACTION_DRAG_ENTERED -> { view.setBackgroundColor(0xFF3B5568.toInt()); true }
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.setBackgroundColor(Color.TRANSPARENT); true }
                        DragEvent.ACTION_DROP -> {
                            val t = e.clipData?.getItemAt(0)?.text?.toString()
                            if (t != null) moveInto(f, t.split("\n").filter { it.isNotEmpty() }.map { File(it) })
                            true
                        }
                        else -> true
                    }
                }
            }
            dock.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun moveInto(dest: File, files: List<File>) {
        if (!dest.isDirectory) { Notice.show(this, "Hedef klasör bulunamadı", false); return }
        Thread {
            var n = 0
            files.forEach { f ->
                if (!f.exists() || f.parentFile == dest) return@forEach
                if (f.isDirectory && (dest.path + "/").startsWith(f.path + "/")) return@forEach
                var t = File(dest, f.name); var i = 1
                while (t.exists()) { t = File(dest, "${f.nameWithoutExtension}_$i" + (if (f.extension.isEmpty() || f.isDirectory) "" else "." + f.extension)); i++ }
                try { if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() }; n++ } catch (e: Exception) {}
            }
            runOnUiThread {
                Notice.show(this, if (n > 0) "$n öğe ${dest.name} klasörüne taşındı" else "Taşınacak öğe yok", false)
                sel.clear()
                if (list != null) list = list!!.filter { it.exists() }
                if (cur != null || list != null) refresh()
            }
        }.start()
    }

    private fun slotMenu(n: Int) {
        val f = File(prefs.getString("slot$n", null) ?: return)
        AlertDialog.Builder(this).setTitle(f.name).setItems(arrayOf("Başka klasör seç", "Kaldır")) { _, w ->
            if (w == 0) { slotPick = n; picking = true; browse(root) } else removeSlot(n)
        }.setNegativeButton("İptal", null).show()
    }

    private fun removeSlot(n: Int) { prefs.edit().remove("slot$n").apply(); slotsChanged() }
    private fun slotsChanged() { updateDock(); if (cur == null && list == null && !picking) home() }

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
    private fun pinSelected() {
        if (sel.isEmpty()) return
        val l = pinList().toMutableList(); var n = 0
        sel.forEach { f -> if (l.none { it.path == f.path }) { l.add(f); n++ } }
        savePins(l); sel.clear(); editMode = false
        Notice.show(this, if (n > 0) "$n öğe ana ekrana sabitlendi" else "Zaten sabitlenmiş", false)
        refresh()
    }

    private fun migratePins() {
        if (prefs.getBoolean("mig", false)) return
        val old = (prefs.getStringSet("pins", emptySet()) ?: emptySet()).sorted().take(4)
        val e = prefs.edit(); old.forEachIndexed { i, p -> e.putString("slot$i", p) }; e.putBoolean("mig", true).apply()
    }

    private fun setSlot(n: Int, f: File) { prefs.edit().putString("slot$n", f.path).apply(); slotsChanged() }

    private fun browse(d: File) { cur = d; list = null; sel.clear(); editMode = false; refresh() }

    private fun items(): List<File> {
        val base = list ?: (cur?.listFiles()?.toList() ?: emptyList())
        var l = if (showHidden) base else base.filter { !it.name.startsWith(".") }
        if (picking) l = l.filter { it.isDirectory }
        val sorted = l.sortedByDescending { it.lastModified() }
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
        updateChrome()
        val lv = ListView(this).apply { divider = ColorDrawable(0xFF2E2E2E.toInt()); dividerHeight = dp(1); setBackgroundColor(bg) }
        curLv = lv
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = l.size
            override fun getItem(p: Int) = l[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: android.view.ViewGroup?): View {
                val f = l[p]
                val row = LinearLayout(this@FilesActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)); if (f in sel) setBackgroundColor(0xFF2C3E50.toInt()) }
                if (editMode) {
                    // Soldaki boş alan: seç/kaldır ve (seçiliyse) sürükle SADECE buradan yapılır. Seçili satır renkli görünür.
                    val hit = android.widget.FrameLayout(this@FilesActivity).apply {
                        setOnClickListener { toggle(f) }
                        setOnLongClickListener {
                            // sadece seçili bir kutucuğa basılı tutunca seçilenleri alttaki sabit klasörlere sürükle
                            if (f in sel && pendingShare == null) {
                                val payload = sel.joinToString("\n") { it.absolutePath }
                                try { row.startDragAndDrop(ClipData.newPlainText("dosya", payload), View.DragShadowBuilder(row), null, 0) } catch (e: Exception) {}
                            }
                            true
                        }
                    }
                    row.addView(hit, LinearLayout.LayoutParams(dp(56), dp(56)))
                }
                row.addView(iconView(f), dp(48), dp(48))
                val c = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                c.addView(tv(f.name, 19f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val sub = if (f.isDirectory) "${f.listFiles()?.size ?: 0} öğe" else sz(f.length())
                c.addView(tv("$sub • ${df.format(Date(f.lastModified()))}", 14f, 0xFF999999.toInt()))
                row.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
                if (pendingShare == null && !picking) {
                    // Üç nokta: tek dokunuş menü (Taşı / Paylaş / Sil / Yeni klasör ekle), basılı tutunca düzenleme modu açılır/kapanır
                    row.addView(ImageView(this@FilesActivity).apply {
                        setImageDrawable(Icons.more()); setColorFilter(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
                        contentDescription = "Menü"
                        setOnClickListener { itemMenu(f, it) }
                        setOnLongClickListener { toggleEditMode(); true }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                // Sabitlenmiş öğe: üç noktanın sağında minik yuvarlak
                if (f.path in topPinSet) {
                    val dot = View(this@FilesActivity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF8AB4F8.toInt()) }
                    }
                    row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(2); marginEnd = dp(2) })
                }
                return row
            }
        }
        curAdapter = lv.adapter as? BaseAdapter
        lv.setOnItemClickListener { _, _, p, _ -> val f = l[p]; if (editMode) Unit else if (f.isDirectory) browse(f) else openAny(f) }
        lv.setOnItemLongClickListener { _, v, p, _ ->
            if (pendingShare != null) return@setOnItemLongClickListener true
            val f = l[p]
            // seçim modunda satırın kendisi etkisiz (seç/sürükle sadece kutucuktan); aksi halde basılı tutunca ad değiştir
            if (!editMode) renameItem(f)
            true
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        if (pendingShare != null) wrap.addView(saveBar(), -1, -2)
        else if (picking) wrap.addView(Button(this).apply { text = "Bu klasörü seç"; setOnClickListener { if (slotPick in 0..3) setSlot(slotPick, cur!!); slotPick = -1; home() } }, -1, -2)
        else if (clip.isNotEmpty() && cur != null && list == null && !editMode) wrap.addView(pasteBar(), -1, dp(52))
        if (keepScroll && (sPos > 0 || sTop != 0)) lv.setSelectionFromTop(minOf(sPos, maxOf(0, l.size - 1)), sTop)
        curLv = lv
        body.removeAllViews(); body.addView(wrap)
    }


    /** Basılı tutulan dosya/klasörün adını değiştirir. */
    private fun renameItem(f: File) {
        promptText("Yeni ad", f.name) { n ->
            val name = n.trim()
            if (name.isEmpty() || name == f.name) return@promptText
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


    /** Üç noktaya basılı tutunca düzenleme (seçim) modu açılır, tekrar basılı tutunca kapanır. */
    private fun toggleEditMode() { editMode = !editMode; sel.clear(); refresh(true) }

    /** Üç nokta menüsü. Öğe, birden çok seçilenin içindeyse işlem tüm seçilenlere uygulanır. */
    private fun itemMenu(f: File, anchor: View) {
        val targets = if (f in sel && sel.size > 1) sel.toList() else listOf(f)
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 5, 0, if (targets.all { it.path in topPins() }) "Sabitlemeyi kaldır" else "Sabitle")
        pm.menu.add(0, 1, 0, "Taşı")
        pm.menu.add(0, 2, 1, "Paylaş")
        pm.menu.add(0, 3, 2, "Sil")
        pm.menu.add(0, 4, 3, "Yeni klasör ekle")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                5 -> toggleTopPin(targets)
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
                dest.mkdirs(); val dp = dest.canonicalPath + File.separator
                ZipInputStream(BufferedInputStream(FileInputStream(z))).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val o = File(dest, e.name)
                        if (!o.canonicalPath.startsWith(dp)) throw SecurityException("zip-slip")
                        if (e.isDirectory) o.mkdirs() else { o.parentFile?.mkdirs(); FileOutputStream(o).use { zin.copyTo(it) } }
                        zin.closeEntry(); e = zin.nextEntry
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

    private fun zipDialog(f: File) {
        AlertDialog.Builder(this).setTitle(f.name).setItems(arrayOf("Buraya çıkar", "Başka uygulamayla aç")) { _, w -> if (w == 0) unzip(f) else open(f) }
            .setNegativeButton("İptal", null).show()
    }

    private fun toggle(f: File) { if (!sel.remove(f)) sel.add(f); selectionChanged() }

    private fun uri(f: File) = KFileProvider.uriFor(this, f)

    private fun openAny(f: File) {
        if (f.extension.lowercase() == "zip") zipDialog(f) else if (f.extension.lowercase() == "apk") installApk(f)
        else if (f.extension.lowercase() in IMG) openInGallery(f) else open(f)
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

    private fun shareFiles(x: List<File>) {
        val us = ArrayList(x.filter { it.isFile }.map { uri(it) })
        if (us.isEmpty()) { Notice.show(this, "Klasörleri paylaşmak için önce sıkıştırın", false); return }
        sel.clear(); editMode = false; refresh(true)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, us).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş"))
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
        slotPick = -1; picking = true; list = null; sel.clear(); editMode = false
        val last = File(prefs.getString("lastSave", null) ?: "")
        browse(if (last.isDirectory) last else root)
    }

    private fun cancelShare() { pendingShare = null; picking = false; finish() }

    private fun saveBar(): View {
        val n = pendingShare?.size ?: 0
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        box.addView(tv("$n dosya kaydedilecek • klasör seçin", 14f, 0xFFCCCCCC.toInt()).apply { setPadding(dp(4), 0, 0, dp(4)) })
        val row = LinearLayout(this)
        row.addView(Button(this).apply { text = "İptal"; setOnClickListener { cancelShare() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Yeni klasör"
            setOnClickListener {
                val d = cur ?: root
                promptText("Klasör adı", "") { nm ->
                    val safe = nm.replace('/', '_').replace('\\', '_').trim()
                    if (safe.isEmpty() || safe == "." || safe == "..") return@promptText
                    val nd = File(d, safe)
                    if (nd.isDirectory || nd.mkdirs()) browse(nd) else Notice.show(this@FilesActivity, "Klasör oluşturulamadı", false)
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "Buraya kaydet"; setOnClickListener { saveShared(cur ?: root) } }, LinearLayout.LayoutParams(0, -2, 1.3f))
        box.addView(row)
        return box
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
