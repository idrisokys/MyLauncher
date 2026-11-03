package com.example.myl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class MainActivity : Activity() {

    // ---------- Veri modelleri ----------
    class Folder(var name: String, val children: MutableList<String>)
    class Sc(val pkg: String, val id: String, var label: String)
    class Row(
        val title: String,
        val sub: String?,
        val icon: Bitmap?,
        val onLong: (() -> Unit)? = null,   // basılı tutma (kişilerde: WhatsApp sohbeti)
        val spacer: Boolean = false,        // sonuçlar arasındaki dokunulmaz boş satır
        val action: () -> Unit
    )

    /** Arka planda hazırlanan, ana thread'e hazır halde teslim edilen uygulama listesi. */
    private class Snapshot(
        val apps: LinkedHashMap<String, LauncherActivityInfo>,
        val labels: HashMap<String, String>,   // uygulama adları (arka planda okunur)
        val norms: HashMap<String, String>,    // arama için sadeleştirilmiş adlar
        val newIcons: Map<String, Bitmap>
    )

    private lateinit var launcherApps: LauncherApps
    private lateinit var prefs: SharedPreferences
    private val me: UserHandle = Process.myUserHandle()

    // Bu koleksiyonlara YALNIZCA ana thread dokunur.
    private val apps = LinkedHashMap<String, LauncherActivityInfo>()   // "app:<component>"
    private val folders = LinkedHashMap<String, Folder>()              // "folder:<uuid>"
    private val shortcuts = LinkedHashMap<String, Sc>()                // "sc:<pkg>/<id>"
    private val order = mutableListOf<String>()                        // olması gereken sıra
    private val iconCache = HashMap<String, Bitmap>()                  // hazır, boyutlandırılmış simgeler
    private val labels = HashMap<String, String>()                     // uygulama adları (ana thread'de kaynak okumamak için)
    private val normLabels = HashMap<String, String>()                 // arama için sadeleştirilmiş adlar

    // Adaptörün o an gösterdiği durum (DiffUtil için)
    private var shownKeys: List<String> = emptyList()
    private var shownSigs: List<String> = emptyList()

    // ---------- Arka plan işleri ----------
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val searchExec: ExecutorService = Executors.newSingleThreadExecutor()
    // Simgeleri çok çekirdekte paralel hazırlamak için (soğuk açılışı hızlandırır); boşta kalınca thread'ler kapanır.
    private val iconPool: ExecutorService =
        (Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) as ThreadPoolExecutor).apply {
            setKeepAliveTime(5, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    private var loading = false
    private var pendingReload = false
    private var skipNextResumeReload = true
    private var loadedOnce = false                 // ilk uygulama listesi geldi mi?
    private var pendingPinIntent: Intent? = null   // liste gelmeden önce gelen kısayol isteği
    @Volatile private var searchGen = 0
    private val reloadRunnable = Runnable { startReload() }

    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("tr-TR"))
    private val trLocale: Locale = Locale.forLanguageTag("tr-TR")
    private val density by lazy { resources.displayMetrics.density }
    private val iconPx by lazy { dp(56) }

    // ---------- UI ----------
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private lateinit var grid: RecyclerView
    private lateinit var results: ListView
    private lateinit var gridAdapter: GridAdapter
    private val rows = mutableListOf<Row>()
    private var folderDialog: Dialog? = null
    private var askedContacts = false
    private var pendingCallNumber: String? = null   // CALL_PHONE izni beklenirken
    private lateinit var moveBtn: ImageView
    private lateinit var actionBtn: ImageView   // normalde ayarlar, düzenleme modunda klasör simgesi
    private var editMode = false

    private fun dp(v: Int) = (v * density).toInt()

    // ---------- Yaşam döngüsü ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = getSystemService(LauncherApps::class.java)
        prefs = getSharedPreferences("myl", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        buildUi()
        loadPrefs()
        startReload()
        launcherApps.registerCallback(callback)
        handleIntent(intent)
        if (savedInstanceState == null) promptDefaultIfNeeded(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        promptDefaultIfNeeded(intent)
    }

    /** Uygulama başka bir launcher'dan simgesiyle açıldıysa ve varsayılan değilse, seçim ekranını göster. */
    private fun promptDefaultIfNeeded(i: Intent?) {
        if (i != null && i.hasCategory(Intent.CATEGORY_LAUNCHER) && !isDefaultLauncher()) {
            openDefaultLauncherSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // ColorOS bazı paket olaylarını kaçırabildiği için emniyet kemeri.
        // Artık simge önbelleği silinmiyor; arka planda hızlıca kontrol edilir,
        // bir şey değişmediyse ekrana hiç dokunulmaz.
        if (skipNextResumeReload) skipNextResumeReload = false else startReload()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(callback)
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        searchExec.shutdown()
        iconPool.shutdown()
        folderDialog?.dismiss()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.text.isNotEmpty() || search.hasFocus()) closeSearch()
        else if (editMode) setEditMode(false)
        // Ana ekranda geri tuşu hiçbir şey yapmaz
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQ_CALL) {
            val n = pendingCallNumber
            pendingCallNumber = null
            if (n != null) {
                if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startCall(n) else dial(n)
            }
            return
        }
        updateResults(search.text.toString())
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == "android.content.pm.action.CONFIRM_PIN_SHORTCUT") {
            if (!loadedOnce) { pendingPinIntent = i; return }
            try {
                val req = launcherApps.getPinItemRequest(i) ?: return
                val info = req.shortcutInfo ?: return
                if (req.isValid && req.accept()) {
                    addShortcut(info, pin = false)
                }
            } catch (e: Exception) {
                toast("Kısayol eklenemedi")
            }
        } else if (i.hasCategory(Intent.CATEGORY_HOME)) {
            closeSearch()
            setEditMode(false)
            folderDialog?.dismiss()
            grid.scrollToPosition(0)
        }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = pkgChanged(packageName)
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = pkgChanged(packageName)
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = pkgChanged(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            packageNames?.forEach { pkgChanged(it) } ?: Unit
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            packageNames?.forEach { pkgChanged(it) } ?: Unit
    }

    /** Sadece değişen paketin simgesini geçersiz kıl; art arda gelen olayları tek yüklemede birleştir. */
    private fun pkgChanged(pkg: String?) {
        if (pkg == null) iconCache.clear()
        else iconCache.keys.removeAll { it.startsWith("app:$pkg/") || it.startsWith("sc:$pkg/") }
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 250)
    }

    // ---------- Arayüz ----------
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom
                )
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }

        // Üst çubuk: arama kutusu + küçük "düzenle" düğmesi
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        search = EditText(this).apply {
            hint = "Kişi, uygulama veya web'de ara"
            setHintTextColor(0xAAFFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(21).toFloat()
                setColor(0x66000000)
            }
        }
        bar.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))

        moveBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_move)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
            setOnClickListener { setEditMode(!editMode) }
        }
        actionBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
            setOnClickListener { if (editMode) showFolderPicker() else showSettingsMenu(this) }
        }
        bar.addView(actionBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) })
        bar.addView(moveBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) })

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Uygulama ızgarası (sağdan sola 4 sütun, aşağı doğru sonsuz)
        val frame = FrameLayout(this)
        gridAdapter = GridAdapter()
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(24))
            adapter = gridAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            recycledViewPool.setMaxRecycledViews(0, 32)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        frame.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        touchHelper.attachToRecyclerView(grid)

        // Arama sonuçları
        results = ListView(this).apply {
            divider = null
            visibility = View.GONE
            setBackgroundColor(0xDD000000.toInt())
            adapter = resultsAdapter
            setOnItemClickListener { _, _, p, _ ->
                if (p in rows.indices) {
                    val act = rows[p].action
                    closeSearch()
                    act()
                }
            }
            setOnItemLongClickListener { _, _, p, _ ->
                val lc = rows.getOrNull(p)?.onLong
                if (lc != null) { closeSearch(); lc(); true } else false
            }
        }
        frame.addView(results, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateResults(s?.toString().orEmpty())
        })
        search.setOnFocusChangeListener { _, has ->
            if (has && !askedContacts &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedContacts = true
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
            }
        }
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                val q = search.text.toString().trim()
                if (q.isNotEmpty()) openUrl("https://www.google.com/search?q=" + Uri.encode(q))
                true
            } else false
        }
    }

    private fun closeSearch() {
        search.setText("")
        search.clearFocus()
        (getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(search.windowToken, 0)
        grid.requestFocus()
    }

    private fun openDefaultLauncherSettings() {
        val tries = listOf(
            Settings.ACTION_HOME_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (a in tries) {
            try { startActivity(Intent(a)); return } catch (e: Exception) { }
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        (moveBtn.background as GradientDrawable).setColor(if (on) 0xCCEC4899.toInt() else 0x66000000)
        actionBtn.setImageResource(if (on) R.drawable.ic_folder_add else R.drawable.ic_settings)
        toast(if (on) "Düzenleme açık: simgeyi başka bir simgenin ortasına bırakırsan yer değiştirirler, kenarına (arasına) bırakırsan araya girer. Klasör için üstteki klasör düğmesini kullan" else "Düzenleme kapalı")
    }

    private fun showSettingsMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Düzeni yedekle").setOnMenuItemClickListener { backupLayout(); true }
        pm.menu.add("Düzeni geri al").setOnMenuItemClickListener { confirmRestore(); true }
        pm.menu.add("Ayarlar").setOnMenuItemClickListener { safeStart(Intent(Settings.ACTION_SETTINGS)); true }
        pm.show()
    }

    // ---------- Düzen yedekleme / geri yükleme ----------
    /** Yerleşim düzenini (sıra, klasörler, kısayollar) İndirilenler klasörüne JSON olarak kaydeder. */
    private fun backupLayout() {
        try {
            val fo = JSONObject()
            for ((k, f) in folders) fo.put(k, JSONObject().put("n", f.name).put("c", JSONArray(f.children)))
            val so = JSONObject()
            for ((k, sc) in shortcuts) so.put(k, JSONObject().put("p", sc.pkg).put("i", sc.id).put("l", sc.label))
            val json = JSONObject()
                .put("myl", 1)
                .put("order", JSONArray(order))
                .put("folders", fo)
                .put("shortcuts", so)
                .toString(2)
            val name = "myl-duzen-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) { toast("Yedek kaydedilemedi"); return }
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            toast("Yedeklendi: İndirilenler/$name")
        } catch (e: Exception) {
            toast("Yedek kaydedilemedi")
        }
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle("Düzeni geri al")
            .setMessage("Mevcut yerleşim düzeni, seçeceğin yedek dosyasındaki düzenle değiştirilecek.")
            .setPositiveButton("Dosya seç") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try { startActivityForResult(i, REQ_RESTORE) } catch (e: Exception) { toast("Dosya seçici açılamadı") }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_RESTORE || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: run { toast("Dosya okunamadı"); return }
            val o = JSONObject(text)
            if (!o.has("order") || !o.has("folders") || !o.has("shortcuts")) { toast("Geçersiz yedek dosyası"); return }
            val ord = ArrayList<String>()
            o.getJSONArray("order").let { a -> for (i in 0 until a.length()) ord.add(a.getString(i)) }
            val fol = LinkedHashMap<String, Folder>()
            o.getJSONObject("folders").let { fo ->
                for (k in fo.keys()) {
                    val f = fo.getJSONObject(k)
                    val ch = f.getJSONArray("c")
                    val l = mutableListOf<String>()
                    for (i in 0 until ch.length()) l.add(ch.getString(i))
                    fol[k] = Folder(f.getString("n"), l)
                }
            }
            val scs = LinkedHashMap<String, Sc>()
            o.getJSONObject("shortcuts").let { so ->
                for (k in so.keys()) {
                    val x = so.getJSONObject(k)
                    scs[k] = Sc(x.getString("p"), x.getString("i"), x.getString("l"))
                }
            }
            folderDialog?.dismiss()
            order.clear(); order.addAll(ord)
            folders.clear(); folders.putAll(fol)
            shortcuts.clear(); shortcuts.putAll(scs)
            persistAndRefresh()   // yüklü olmayan uygulamalar ayıklanır, yeniler sona eklenir
            startReload()         // geri yüklenen kısayolların simgeleri hazırlanır
            toast("Düzen geri yüklendi")
        } catch (e: Exception) {
            toast("Geçersiz yedek dosyası")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Tarayıcı açılamadı")
        }
        closeSearch()
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Açılamadı") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Arama ----------
    /** Türkçe karakterleri sadeleştirir: "Işık" ile "isik" birbirini bulur. */
    private fun fold(s: String): String {
        val low = s.lowercase(trLocale)
        val sb = StringBuilder(low.length)
        for (ch in low) {
            sb.append(
                when (ch) {
                    'ı' -> 'i'; 'ö' -> 'o'; 'ü' -> 'u'; 'ş' -> 's'; 'ç' -> 'c'; 'ğ' -> 'g'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun updateResults(q0: String) {
        val q = q0.trim()
        val gen = ++searchGen
        if (q.isEmpty()) {
            results.visibility = View.GONE
            return
        }
        // Uygulamalar anında gelir; kişi sorgusu arka planda yapılıp sonra eklenir.
        showRows(q, emptyList())
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            searchExec.execute {
                if (gen != searchGen) return@execute
                // Hızlı yazarken her harfte kişi veritabanını sorgulama: kısa bekle, hâlâ güncelse sorgula.
                try { Thread.sleep(120) } catch (e: InterruptedException) { return@execute }
                if (gen != searchGen) return@execute
                val found = findContacts(q)
                main.post {
                    if (gen == searchGen && !isDestroyed && found.isNotEmpty()) showRows(q, found)
                }
            }
        }
    }

    private fun showRows(q: String, contacts: List<Row>) {
        rows.clear()
        rows.addAll(contacts.take(MAX_RESULTS))
        val fq = fold(q)
        val matches = ArrayList<String>()
        for (k in apps.keys) {
            val n = normLabels[k] ?: continue
            if (n.contains(fq)) matches.add(k)
        }
        matches.sortWith(compareBy<String>({ normLabels[it]?.startsWith(fq) != true }, { normLabels[it] ?: "" }))
        for (k in matches.take((MAX_RESULTS - rows.size).coerceAtLeast(0))) {
            val label = labels[k] ?: continue
            rows.add(Row(label, "Uygulama", iconCache[k]) { launch(k) })
        }
        val enc = Uri.encode(q)
        rows.add(Row("Google'da ara: $q", null, null) { openUrl("https://www.google.com/search?q=$enc") })
        rows.add(Row("Yandex'te ara: $q", null, null) { openUrl("https://yandex.com/search/?text=$enc") })
        rows.add(Row("IMDb'de ara: $q", null, null) { openUrl("https://www.imdb.com/find/?q=$enc") })
        // Yanlış dokunmayı önlemek için her sonucun altına dokunulmaz boş satır
        val withGaps = ArrayList<Row>(rows.size * 2)
        for (r in rows) { withGaps.add(r); withGaps.add(Row("", null, null, spacer = true) {}) }
        rows.clear()
        rows.addAll(withGaps)
        resultsAdapter.notifyDataSetChanged()
        results.visibility = View.VISIBLE
    }

    /** Arka plan thread'inde çalışır. */
    private fun findContacts(q: String): List<Row> {
        val out = mutableListOf<Row>()
        try {
            val name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, name),
                "$name LIKE ? ESCAPE '\\'", arrayOf("%$esc%"),
                "$name COLLATE LOCALIZED ASC LIMIT 5"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val nm = c.getString(1) ?: continue
                    // Dokun: telefon araması. Basılı tut: WhatsApp sohbeti.
                    out.add(Row(nm, "Kişi", null, onLong = { openWhatsAppChat(id, nm) }) { callContact(id, nm) })
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Kişinin telefon numaralarını (tekrarsız) verir; varsayılan numara başta. */
    private fun phonesOf(contactId: Long): List<String> {
        val list = LinkedHashMap<String, String>()   // sadeleştirilmiş -> gösterilen
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)?.trim().orEmpty()
                    if (n.isNotEmpty()) list.putIfAbsent(n.filter { it.isDigit() || it == '+' }, n)
                }
            }
        } catch (e: Exception) { }
        return list.values.toList()
    }

    private fun callContact(contactId: Long, name: String) {
        val nums = phonesOf(contactId)
        when {
            nums.isEmpty() -> toast("$name için numara yok")
            nums.size == 1 -> placeCall(nums[0])
            else -> AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(nums.toTypedArray()) { _, i -> placeCall(nums[i]) }
                .show()
        }
    }

    private fun placeCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(number)
        } else {
            pendingCallNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
        }
    }

    private fun startCall(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        } catch (e: Exception) { dial(number) }
    }

    /** İzin verilmezse arama yerine çevirici açılır. */
    private fun dial(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        } catch (e: Exception) { toast("Aranamadı") }
    }

    /** Rehberdeki WhatsApp (veya WhatsApp Business) sohbet kaydını açar. */
    private fun openWhatsAppChat(contactId: Long, name: String) {
        val mimes = listOf(WA_CHAT to WA_PKG, WA_BIZ_CHAT to WA_BIZ_PKG)
        try {
            for ((mime, pkg) in mimes) {
                contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId.toString(), mime), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0))
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(pkg))
                        return
                    }
                }
            }
            toast("$name için WhatsApp bulunamadı")
        } catch (e: Exception) {
            toast("WhatsApp açılamadı")
        }
    }

    private companion object {
        const val REQ_CALL = 2
        const val REQ_RESTORE = 3
        const val MAX_RESULTS = 5   // kişi + uygulama sonuçları toplamı
        const val WA_PKG = "com.whatsapp"
        const val WA_BIZ_PKG = "com.whatsapp.w4b"
        const val WA_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        const val WA_BIZ_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
    }

    private class RowHolder(val icon: ImageView, val title: TextView, val sub: TextView) {
        val placeholder = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x44FFFFFF)
        }
    }

    private val resultsAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].spacer) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = !rows[p].spacer
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            if (rows[p].spacer) {
                return cv ?: View(this@MainActivity).apply {
                    layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24))
                }
            }
            val h: RowHolder
            val v: View
            if (cv == null) {
                val line = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val iv = ImageView(this@MainActivity)
                line.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val title = TextView(this@MainActivity).apply {
                    textSize = 22f; setTextColor(Color.WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }
                val sub = TextView(this@MainActivity).apply {
                    textSize = 14f; setTextColor(0xAAFFFFFF.toInt())
                }
                col.addView(title)
                col.addView(sub)
                line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
                h = RowHolder(iv, title, sub)
                line.tag = h
                v = line
            } else {
                v = cv
                h = cv.tag as RowHolder
            }
            val r = rows[p]
            if (r.icon != null) h.icon.setImageBitmap(r.icon) else h.icon.setImageDrawable(h.placeholder)
            h.title.text = r.title
            if (r.sub != null) { h.sub.text = r.sub; h.sub.visibility = View.VISIBLE } else h.sub.visibility = View.GONE
            return v
        }
    }

    // ---------- Izgara adaptörü ----------
    private fun newCell(): Triple<LinearLayout, ImageView, TextView> {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(6))
        }
        val iv = ImageView(this)
        cell.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
        val tv = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        cell.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        return Triple(cell, iv, tv)
    }

    inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        inner class VH(val cell: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val (cell, iv, tv) = newCell()
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val h = VH(cell, iv, tv)
            // Dinleyiciler bir kez kurulur; bind'da yeniden nesne üretilmez.
            cell.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0 && p < shownKeys.size) onTap(shownKeys[p])
            }
            // Uzun basıp bırakınca "tıklama" sayılmasın (menü yerine uygulama açılıyordu)
            cell.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (!editMode && p >= 0 && p < shownKeys.size) showMenu(shownKeys[p], cell, null)
                true
            }
            return h
        }

        override fun getItemCount() = shownKeys.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val k = shownKeys[position]
            h.label.text = labelFor(k)
            setIcon(h.icon, k)
        }
    }

    private fun setIcon(iv: ImageView, k: String) {
        if (k.startsWith("folder:")) {
            val f = folders[k]
            iv.setImageDrawable(FolderDrawable(f?.children?.take(4)?.map { iconCache[it] }.orEmpty(), iconPx))
        } else {
            iv.setImageBitmap(iconCache[k])
        }
    }

    /** Sırayı ekrana yansıtır; yalnızca gerçekten değişen hücreler güncellenir. */
    private fun refreshGrid() {
        val newKeys = order.toList()
        val newSigs = newKeys.map { sigFor(it) }
        val oldKeys = shownKeys
        val oldSigs = shownSigs
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldKeys.size
            override fun getNewListSize() = newKeys.size
            override fun areItemsTheSame(o: Int, n: Int) = oldKeys[o] == newKeys[n]
            override fun areContentsTheSame(o: Int, n: Int) = oldSigs[o] == newSigs[n]
        }, false)
        shownKeys = newKeys
        shownSigs = newSigs
        diff.dispatchUpdatesTo(gridAdapter)
    }

    private fun sigFor(k: String): String = when {
        k.startsWith("folder:") -> {
            val f = folders[k]
            "F" + (f?.name ?: "") + "|" + (f?.children?.take(4)?.joinToString(",") { sigFor(it) } ?: "")
        }
        else -> labelFor(k) + "|" + System.identityHashCode(iconCache[k])
    }

    private fun onTap(key: String) {
        if (key.startsWith("folder:")) openFolder(key)
        else if (!editMode) launch(key)   // düzenleme modunda uygulama açılmaz
    }

    // ---------- Sürükle-bırak: yer değiştirme ----------
    // Hedefin ORTASINA getirince mavi halka: sürüklenen simge ile hedef yer değiştirir.
    // Hedefin KENARINA (iki simge arasına) getirince mavi çubuk: simge o iki simgenin arasına girer.
    // (Klasör oluşturmak/klasöre eklemek için üstteki klasör düğmesi kullanılır.)
    private var hintView: View? = null
    private var hintInsert = false
    private var hintBefore = false
    private val hintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }
    private val hintRect = RectF()
    private val hintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3338BDF8 }
    private val hintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt(); style = Paint.Style.STROKE
    }

    /** Sürüklenen öğenin merkezinin altındaki (kendisi hariç) hücreyi bulur. */
    private fun findTarget(rv: RecyclerView, exclude: View, x: Float, y: Float): View? {
        for (i in rv.childCount - 1 downTo 0) {
            val c = rv.getChildAt(i)
            if (c === exclude) continue
            if (x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) return c
        }
        return null
    }

    private fun updateHint(rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float) {
        val iv = vh.itemView
        val pos = vh.bindingAdapterPosition
        val cx = iv.left + iv.width / 2f + dX
        val cy = iv.top + iv.height / 2f + dY
        val t = if (pos >= 0) findTarget(rv, iv, cx, cy) else null
        val tp = if (t != null) rv.getChildAdapterPosition(t) else -1
        if (t == null || tp < 0 || tp >= shownKeys.size || tp == pos) { hintView = null; return }
        hintView = t
        hintInsert = isEdgeDrop(t, cx, cy)
        hintBefore = isBeforeSide(t, cx)
    }

    /**
     * Bırakılan nokta hedefin listede ÖNCEKİ tarafında mı? Izgaranın GERÇEK çizim yönüne bakar:
     * RTL'de sağ taraf = hedefin önü, LTR'de sol taraf = hedefin önü.
     * (Çubuğun çizildiği taraf ile simgenin gideceği taraf hep aynı kalsın diye tek yerden hesaplanır.)
     */
    private fun isBeforeSide(t: View, cx: Float): Boolean {
        val rightHalf = cx > t.left + t.width / 2f
        return if (grid.layoutDirection == View.LAYOUT_DIRECTION_RTL) rightHalf else !rightHalf
    }

    /** Hedefin merkezinden yeterince uzakta (kenar/arası) bırakılıyorsa araya girme sayılır. */
    private fun isEdgeDrop(t: View, cx: Float, cy: Float): Boolean {
        val tcx = t.left + t.width / 2f
        val tcy = t.top + t.height / 2f
        return hypot(cx - tcx, cy - tcy) > t.width * 0.3f
    }

    private fun drawHint(c: Canvas) {
        val t = hintView ?: return
        if (hintInsert) {
            val rtl = grid.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val barAtRight = if (rtl) hintBefore else !hintBefore
            val x = if (barAtRight) t.right - dp(1).toFloat() else t.left + dp(1).toFloat()
            val half = dp(3).toFloat()
            hintRect.set(x - half, t.top + dp(8).toFloat(), x + half, t.bottom - dp(8).toFloat())
            c.drawRoundRect(hintRect, half, half, hintBar)
            return
        }
        val r = dp(18).toFloat()
        hintRect.set(t.left + dp(2).toFloat(), t.top + dp(2).toFloat(), t.right - dp(2).toFloat(), t.bottom - dp(2).toFloat())
        hintRing.strokeWidth = dp(2) + dp(1) / 2f
        c.drawRoundRect(hintRect, r, r, hintFill)
        c.drawRoundRect(hintRect, r, r, hintRing)
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        var dx = 0f
        var dy = 0f

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun isLongPressDragEnabled() = editMode

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                dx = 0f; dy = 0f
                vh.itemView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
            }
        }

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && active) {
                dx = dX; dy = dY
                updateHint(rv, vh, dX, dY)
                drawHint(c)   // simgelerin ALTINA çizilir
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().cancel()
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            hintView = null
            rv.invalidate()
            if (editMode) handleDrop(rv, vh, dx, dy)
            dx = 0f; dy = 0f
        }
    })

    private fun handleDrop(rv: RecyclerView, vh: RecyclerView.ViewHolder, dx: Float, dy: Float) {
        val pos = vh.bindingAdapterPosition
        if (pos < 0 || pos >= shownKeys.size) return
        val key = shownKeys[pos]
        // Düzenleme modunda kıpırdamadan bırakılırsa hiçbir şey yapma (menü yok)
        if (hypot(dx, dy) < dp(14)) return
        val cx = vh.itemView.left + vh.itemView.width / 2f + dx
        val cy = vh.itemView.top + vh.itemView.height / 2f + dy
        val target = rv.findChildViewUnder(cx, cy) ?: return
        val tp = rv.getChildAdapterPosition(target)
        if (tp < 0 || tp >= shownKeys.size || tp == pos) return
        val targetKey = shownKeys[tp]
        val insert = isEdgeDrop(target, cx, cy)
        val before = isBeforeSide(target, cx)
        // Değişiklik, RecyclerView kendi çizim/animasyon döngüsünden çıktıktan sonra uygulanır.
        rv.post { if (insert) insertNear(key, targetKey, before) else replaceAt(key, targetKey) }
    }

    /** Sürüklenen öğeyi hedefin hemen önüne/arkasına (iki öğenin arasına) yerleştirir. */
    private fun insertNear(drag: String, target: String, before: Boolean) {
        val from = order.indexOf(drag)
        if (from < 0 || !order.contains(target) || drag == target) return
        order.removeAt(from)
        val idx = order.indexOf(target)
        order.add(if (before) idx else idx + 1, drag)
        persistAndRefresh()
    }

    /** Sürüklenen öğe ile hedef birbirinin yerini alır (yer değiştirir). */
    private fun replaceAt(drag: String, target: String) {
        val from = order.indexOf(drag)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || drag == target) return
        order[from] = target
        order[to] = drag
        persistAndRefresh()
    }

    // ---------- Klasör seçici (düzenleme modundaki klasör düğmesi) ----------
    /** Seçilen uygulamaları yeni bir klasöre ya da var olan bir klasöre ekler. */
    private fun showFolderPicker() {
        val candidates = order.filter { !it.startsWith("folder:") }
        if (candidates.isEmpty()) { toast("Eklenecek uygulama yok"); return }
        val checked = HashSet<String>()
        val folderKeys = folders.keys.toList()
        var target = -1   // -1 = yeni klasör, aksi halde folderKeys indeksi

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xF0222222.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = "Klasöre ekle"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })

        // Hedef seçimi: "Yeni klasör" + var olan klasörler (yatay kaydırılır)
        val nameEdit = EditText(this).apply {
            setText("Klasör")
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            hint = "Klasör adı"
            textSize = 15f
            setSelectAllOnFocus(true)
        }
        val chips = ArrayList<TextView>()
        fun paintChips() {
            chips.forEachIndexed { i, tv ->
                (tv.background as GradientDrawable).setColor(if (i - 1 == target) 0xFFEC4899.toInt() else 0x33FFFFFF)
            }
            nameEdit.visibility = if (target == -1) View.VISIBLE else View.GONE
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipNames = listOf("＋ Yeni klasör") + folderKeys.map { folders[it]?.name ?: "" }
        chipNames.forEachIndexed { i, nm ->
            val tv = TextView(this).apply {
                text = nm
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat() }
                setOnClickListener { target = i - 1; paintChips() }
            }
            chips.add(tv)
            chipRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })
        box.addView(nameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        paintChips()

        // Uygulama listesi (çoklu seçim)
        val list = ListView(this)
        list.divider = null
        val adapter = object : BaseAdapter() {
            override fun getCount() = candidates.size
            override fun getItem(p: Int) = candidates[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val k = candidates[p]
                val line = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(this@MainActivity).apply {
                        textSize = 16f; setTextColor(Color.WHITE)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12); marginEnd = dp(12)
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                }
                (line.getChildAt(0) as ImageView).setImageBitmap(iconCache[k])
                (line.getChildAt(1) as TextView).text = labelFor(k)
                val mark = line.getChildAt(2) as TextView
                val on = k in checked
                mark.text = if (on) "✓" else ""
                mark.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (on) setColor(0xFFEC4899.toInt()) else { setColor(Color.TRANSPARENT); setStroke(dp(2), 0x88FFFFFF.toInt()) }
                }
                return line
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val k = candidates[p]
            if (!checked.remove(k)) checked.add(k)
            adapter.notifyDataSetChanged()
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
            topMargin = dp(8)
        })

        // Düğmeler
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(TextView(this).apply {
            text = "İptal"; textSize = 15f; setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { d.dismiss() }
        })
        btns.addView(TextView(this).apply {
            text = "Ekle"; textSize = 15f; setTextColor(0xFFEC4899.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                val sel = order.filter { it in checked }   // ekrandaki sıra korunur
                if (sel.isEmpty()) { toast("Uygulama seç"); return@setOnClickListener }
                if (target == -1) {
                    if (sel.size < 2) { toast("Yeni klasör için en az 2 uygulama seç"); return@setOnClickListener }
                    val nm = nameEdit.text.toString().trim().ifEmpty { "Klasör" }
                    val id = "folder:" + UUID.randomUUID().toString().take(8)
                    val idx = order.indexOf(sel[0])
                    order.removeAll(sel.toSet())
                    order.add(idx.coerceIn(0, order.size), id)
                    folders[id] = Folder(nm, sel.toMutableList())
                } else {
                    val f = folders[folderKeys[target]] ?: return@setOnClickListener
                    for (k in sel) if (!f.children.contains(k)) f.children.add(k)
                    order.removeAll(sel.toSet())
                }
                d.dismiss()
                persistAndRefresh()
            }
        })
        box.addView(btns)

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    // ---------- Menüler ----------
    private fun showMenu(key: String, anchor: View, fromFolder: String?) {
        val pm = PopupMenu(this, anchor)
        val menu = pm.menu
        when {
            key.startsWith("folder:") -> {
                menu.add("Yeniden adlandır").setOnMenuItemClickListener { rename(key); true }
                menu.add("Klasörü dağıt").setOnMenuItemClickListener { dissolve(key); true }
            }
            key.startsWith("sc:") -> {
                menu.add("Başlat").setOnMenuItemClickListener { launch(key); true }
                menu.add("Ana ekrandan kaldır").setOnMenuItemClickListener { removeShortcut(key); true }
            }
            key.startsWith("app:") -> {
                val a = apps[key] ?: return
                val pkg = a.componentName.packageName
                for (si in shortcutsOf(pkg).take(8)) {
                    val t = si.shortLabel?.toString() ?: si.id
                    menu.add(t).setOnMenuItemClickListener { shortcutActions(si); true }
                }
                menu.add("Uygulama bilgisi").setOnMenuItemClickListener {
                    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    true
                }
                menu.add("Kaldır").setOnMenuItemClickListener {
                    safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    true
                }
            }
        }
        if (fromFolder != null) {
            menu.add("Klasörden çıkar").setOnMenuItemClickListener { removeFromFolder(fromFolder, key); true }
        }
        pm.show()
    }

    private fun shortcutActions(si: ShortcutInfo) {
        AlertDialog.Builder(this)
            .setTitle(si.shortLabel?.toString() ?: si.id)
            .setItems(arrayOf("Başlat", "Ana ekrana ekle")) { _, which ->
                if (which == 0) startShortcutInfo(si.`package`, si.id) else addShortcut(si, pin = true)
            }
            .show()
    }

    private fun rename(fk: String) {
        val f = folders[fk] ?: return
        val et = EditText(this).apply { setText(f.name); setSingleLine(); setSelection(f.name.length) }
        AlertDialog.Builder(this)
            .setTitle("Klasör adı")
            .setView(et)
            .setPositiveButton("Tamam") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) f.name = n
                persistAndRefresh()
                if (folderDialog?.isShowing == true) { folderDialog?.dismiss(); openFolder(fk) }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun dissolve(fk: String) {
        val f = folders[fk] ?: return
        val idx = order.indexOf(fk)
        if (idx < 0) return
        order.removeAt(idx)
        order.addAll(idx, f.children)
        folders.remove(fk)
        persistAndRefresh()
    }

    private fun removeFromFolder(fk: String, key: String) {
        val f = folders[fk] ?: return
        f.children.remove(key)
        order.add(key)
        folderDialog?.dismiss()
        persistAndRefresh()   // içinde tek kalan klasörler de hemen dağıtılır
    }

    // ---------- Klasör penceresi ----------
    private fun openFolder(fk: String) {
        val f = folders[fk] ?: return
        folderDialog?.dismiss()
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xEE222222.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = f.name
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { rename(fk) }
        })
        val g = GridLayout(this).apply {
            columnCount = 4
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val cw = (resources.displayMetrics.widthPixels * 0.84f / 4).toInt()
        for (ck in f.children.toList()) {
            val (cell, iv, tv) = newCell()
            iv.setImageBitmap(iconCache[ck])
            tv.text = labelFor(ck)
            cell.setOnClickListener { if (!editMode) { d.dismiss(); launch(ck) } }
            cell.setOnLongClickListener { showMenu(ck, cell, fk); true }
            g.addView(cell, GridLayout.LayoutParams().apply { width = cw })
        }
        box.addView(g)
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnDismissListener { if (folderDialog === d) folderDialog = null }
        folderDialog = d
        d.show()
    }

    // ---------- Başlatma ----------
    private fun launch(key: String) {
        try {
            when {
                key.startsWith("app:") -> {
                    val a = apps[key] ?: return
                    if (a.componentName.packageName == packageName) openDefaultLauncherSettings()
                    else launcherApps.startMainActivity(a.componentName, me, null, null)
                }
                key.startsWith("sc:") -> {
                    val s = shortcuts[key] ?: return
                    startShortcutInfo(s.pkg, s.id)
                }
            }
        } catch (e: Exception) {
            toast("Açılamadı")
        }
    }

    private fun startShortcutInfo(pkg: String, id: String) {
        try {
            launcherApps.startShortcut(pkg, id, null, null, me)
        } catch (e: Exception) {
            toast("Kısayol açılamadı")
        }
    }

    // ---------- Kısayollar ----------
    private fun shortcutsOf(pkg: String, pinnedOnly: Boolean = false): List<ShortcutInfo> = try {
        var flags = ShortcutQuery.FLAG_MATCH_PINNED
        if (!pinnedOnly) {
            flags = flags or ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST
            if (Build.VERSION.SDK_INT >= 30) flags = flags or ShortcutQuery.FLAG_MATCH_CACHED
        }
        val q = ShortcutQuery().setPackage(pkg).setQueryFlags(flags)
        launcherApps.getShortcuts(q, me)?.filter { it.isEnabled } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun addShortcut(si: ShortcutInfo, pin: Boolean) {
        val pkg = si.`package`
        val key = "sc:$pkg/${si.id}"
        if (shortcuts.containsKey(key)) { toast("Zaten ekli"); return }
        shortcuts[key] = Sc(pkg, si.id, si.shortLabel?.toString() ?: si.id)
        order.add(key)
        // Tek bir simge: ana thread'de hemen hazırlanır
        try {
            val d = launcherApps.getShortcutIconDrawable(si, 0)
                ?: launcherApps.getActivityList(pkg, me).firstOrNull()?.getBadgedIcon(0)
            if (d != null) iconCache[key] = renderIcon(d, iconPx)
        } catch (e: Exception) { }
        if (pin) {
            try {
                val pinned = shortcutsOf(pkg, pinnedOnly = true).map { it.id }
                launcherApps.pinShortcuts(pkg, (pinned + si.id).distinct(), me)
            } catch (e: Exception) { }
        }
        persistAndRefresh()
        grid.post { grid.smoothScrollToPosition((order.size - 1).coerceAtLeast(0)) }
        toast("Ana ekrana eklendi")
    }

    private fun removeShortcut(key: String) {
        val s = shortcuts.remove(key) ?: return
        order.remove(key)
        folders.values.forEach { it.children.remove(key) }
        try {
            val keep = shortcutsOf(s.pkg, pinnedOnly = true).map { it.id }.filter { it != s.id }
            launcherApps.pinShortcuts(s.pkg, keep, me)
        } catch (e: Exception) { }
        iconCache.remove(key)
        persistAndRefresh()
    }

    // ---------- Kalıcı veri ----------
    private fun loadPrefs() {
        order.clear(); folders.clear(); shortcuts.clear()
        try {
            prefs.getString("order", null)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) order.add(a.getString(i))
            }
            prefs.getString("folders", null)?.let { s ->
                val o = JSONObject(s)
                for (k in o.keys()) {
                    val f = o.getJSONObject(k)
                    val ch = f.getJSONArray("c")
                    val l = mutableListOf<String>()
                    for (i in 0 until ch.length()) l.add(ch.getString(i))
                    folders[k] = Folder(f.getString("n"), l)
                }
            }
            prefs.getString("shortcuts", null)?.let { s ->
                val o = JSONObject(s)
                for (k in o.keys()) {
                    val x = o.getJSONObject(k)
                    shortcuts[k] = Sc(x.getString("p"), x.getString("i"), x.getString("l"))
                }
            }
        } catch (e: Exception) { }
    }

    private fun savePrefs() {
        val fo = JSONObject()
        for ((k, f) in folders) fo.put(k, JSONObject().put("n", f.name).put("c", JSONArray(f.children)))
        val so = JSONObject()
        for ((k, s) in shortcuts) so.put(k, JSONObject().put("p", s.pkg).put("i", s.id).put("l", s.label))
        prefs.edit()
            .putString("order", JSONArray(order).toString())
            .putString("folders", fo.toString())
            .putString("shortcuts", so.toString())
            .apply()
    }

    private fun persistAndRefresh() {
        normalize()
        savePrefs()
        refreshGrid()
    }

    private fun isValid(k: String) = when {
        k.startsWith("app:") -> apps.containsKey(k)
        k.startsWith("sc:") -> shortcuts.containsKey(k)
        else -> false
    }

    // ---------- Uygulama listesini yükleme (arka plan) ----------
    /**
     * Yüklü uygulamaları ve henüz önbellekte olmayan simgeleri ARKA PLANDA hazırlar,
     * sonucu ana thread'e tek seferde teslim eder. Ana thread hiç bloklanmaz.
     */
    private fun startReload() {
        if (loading) { pendingReload = true; return }
        loading = true
        val known = HashSet(iconCache.keys)
        val knownLabels = HashMap(labels)
        val scs = shortcuts.values.toList()
        val size = iconPx
        worker.execute {
            var snap: Snapshot? = null
            try {
                val list = launcherApps.getActivityList(null, me)
                // Sistem geçici olarak boş liste dönerse (kilit açılırken vb.) düzeni silmemek için yok say.
                if (!list.isNullOrEmpty()) {
                    val map = LinkedHashMap<String, LauncherActivityInfo>(list.size * 2)
                    val lbls = HashMap<String, String>(list.size * 2)
                    val norms = HashMap<String, String>(list.size * 2)
                    val todo = ArrayList<Pair<String, LauncherActivityInfo>>()
                    for (a in list) {
                        val k = "app:" + a.componentName.flattenToString()
                        val dup = map.containsKey(k)
                        map[k] = a
                        if (dup) continue
                        // Ad okuma ve sadeleştirme burada (arka planda) yapılır; ana thread'de kaynak okunmaz.
                        val l = try { a.label.toString() } catch (e: Exception) { "" }
                        lbls[k] = l
                        norms[k] = fold(l)
                        if (k !in known) todo.add(k to a)
                    }
                    val icons = HashMap<String, Bitmap>()
                    if (todo.size > 8) {
                        // Çok simge varsa (ilk açılış) çekirdeklere paylaştır
                        val futures = todo.map { (k, a) ->
                            iconPool.submit(Callable<Pair<String, Bitmap>?> {
                                try { k to renderIcon(a.getBadgedIcon(0), size) } catch (e: Exception) { null }
                            })
                        }
                        for (f in futures) {
                            try { f.get()?.let { icons[it.first] = it.second } } catch (e: Exception) { }
                        }
                    } else {
                        for ((k, a) in todo) {
                            try { icons[k] = renderIcon(a.getBadgedIcon(0), size) } catch (e: Exception) { }
                        }
                    }
                    for (s in scs) {
                        val k = "sc:${s.pkg}/${s.id}"
                        if (k !in known) {
                            try { scIcon(s)?.let { icons[k] = renderIcon(it, size) } } catch (e: Exception) { }
                        }
                    }
                    // Hiçbir şey değişmediyse (en sık durum: ana ekrana dönüş) ana thread'e hiç iş yükleme.
                    if (icons.isNotEmpty() || lbls != knownLabels) {
                        snap = Snapshot(map, lbls, norms, icons)
                    }
                }
            } catch (e: Exception) { }
            val result = snap
            main.post {
                loading = false
                if (!isDestroyed) {
                    if (result != null) applySnapshot(result)
                    if (pendingReload) { pendingReload = false; startReload() }
                }
            }
        }
    }

    private fun applySnapshot(s: Snapshot) {
        apps.clear()
        apps.putAll(s.apps)
        iconCache.putAll(s.newIcons)
        labels.clear()
        labels.putAll(s.labels)
        normLabels.clear()
        normLabels.putAll(s.norms)
        // Artık var olmayan öğelerin simgelerini bellekten at
        iconCache.keys.removeAll { !apps.containsKey(it) && !shortcuts.containsKey(it) }
        val changed = normalize()
        if (changed) savePrefs()
        refreshGrid()
        if (search.text.isNotEmpty()) updateResults(search.text.toString())
        loadedOnce = true
        pendingPinIntent?.let { pendingPinIntent = null; handleIntent(it) }
    }

    /**
     * Sırayı ve klasörleri gerçek duruma göre düzeltir. Yeni uygulamalar listenin SONUNA (alfabetik) eklenir.
     * Bir şey değiştiyse true döner.
     */
    private fun normalize(): Boolean {
        if (apps.isEmpty()) return false   // liste henüz yüklenmediyse sırayı bozma
        var changed = false
        for (f in folders.values) if (f.children.removeAll { !isValid(it) }) changed = true
        for (fk in folders.keys.toList()) {
            val f = folders[fk] ?: continue
            if (f.children.size <= 1) {
                val idx = order.indexOf(fk)
                if (idx >= 0) {
                    order.removeAt(idx)
                    if (f.children.size == 1) order.add(idx, f.children[0])
                }
                folders.remove(fk)
                changed = true
            }
        }
        val inFolder = HashSet<String>()
        for (f in folders.values) inFolder.addAll(f.children)
        val res = LinkedHashSet<String>()
        for (k in order) {
            when {
                k.startsWith("folder:") -> if (folders.containsKey(k)) res.add(k)
                k in inFolder -> {}
                isValid(k) -> res.add(k)
            }
        }
        val missing = apps.keys
            .filter { it !in res && it !in inFolder }
            .map { it to collator.getCollationKey(labels[it] ?: "") }
            .sortedBy { it.second }
            .map { it.first }
        res.addAll(missing)
        val newOrder = res.toList()
        if (newOrder != order) {
            order.clear()
            order.addAll(newOrder)
            changed = true
        }
        return changed
    }

    // ---------- Etiket / ikon ----------
    private fun labelFor(k: String): String = when {
        k.startsWith("app:") -> labels[k] ?: ""
        k.startsWith("sc:") -> shortcuts[k]?.label ?: ""
        k.startsWith("folder:") -> folders[k]?.name ?: ""
        else -> ""
    }

    /** Arka plan thread'inde de çağrılabilir. */
    private fun scIcon(s: Sc): Drawable? {
        try {
            var qf = ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_DYNAMIC or
                ShortcutQuery.FLAG_MATCH_MANIFEST
            if (Build.VERSION.SDK_INT >= 30) qf = qf or ShortcutQuery.FLAG_MATCH_CACHED
            val q = ShortcutQuery().setPackage(s.pkg).setShortcutIds(listOf(s.id)).setQueryFlags(qf)
            val l = launcherApps.getShortcuts(q, me)
            if (!l.isNullOrEmpty()) {
                val d = launcherApps.getShortcutIconDrawable(l[0], 0)
                if (d != null) return d
            }
        } catch (e: Exception) { }
        return launcherApps.getActivityList(s.pkg, me).firstOrNull()?.getBadgedIcon(0)
    }

    /** Simgeyi bir kez hedef boyutta bitmap'e çizer; sonrasında kaydırma sırasında hiç vektör/adaptive çizimi yapılmaz. */
    private fun renderIcon(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }
}

/** Klasör simgesi: yarı saydam yuvarlak zemin üzerinde en çok 4 mini simge. */
class FolderDrawable(icons: List<Bitmap?>, private val sizePx: Int) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val minis: List<Bitmap?> = icons.take(4)
    private val rect = RectF()
    private val dst = RectF()

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(rect, w * 0.28f, w * 0.28f, bg)
        val pad = w * 0.12f
        val cell = (w - pad * 3) / 2f
        minis.forEachIndexed { i, bmp ->
            if (bmp == null) return@forEachIndexed
            val col = i % 2
            val row = i / 2
            val l = b.left + pad + col * (cell + pad)
            val t = b.top + pad + row * (cell + pad)
            dst.set(l, t, l + cell, t + cell)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
        }
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; bmpPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { bg.colorFilter = cf; bmpPaint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
