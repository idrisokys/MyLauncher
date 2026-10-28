package com.example.myl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : Activity() {

    // ---------- Veri modelleri ----------
    class Folder(var name: String, val children: MutableList<String>)
    class Sc(val pkg: String, val id: String, var label: String)
    class Row(val title: String, val sub: String?, val icon: Bitmap?, val action: () -> Unit)

    /** Arka planda hazırlanan, ana thread'e hazır halde teslim edilen uygulama listesi. */
    private class Snapshot(
        val apps: LinkedHashMap<String, LauncherActivityInfo>,
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
    private val normLabels = HashMap<String, String>()                 // arama için sadeleştirilmiş adlar

    // Adaptörün o an gösterdiği durum (DiffUtil için)
    private var shownKeys: List<String> = emptyList()
    private var shownSigs: List<String> = emptyList()

    // ---------- Arka plan işleri ----------
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val searchExec: ExecutorService = Executors.newSingleThreadExecutor()
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
    private lateinit var moveBtn: ImageView
    private var editMode = false

    private fun dp(v: Int) = (v * density).toInt()

    // ---------- Yaşam döngüsü ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = getSystemService(LauncherApps::class.java)
        prefs = getSharedPreferences("myl", MODE_PRIVATE)
        window.setDecorFitsSystemWindows(false)
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
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
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

        // Listenin en üstündeyken aşağı çekince arama açılır
        grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            var startY = 0f
            var triggered = false
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.y; triggered = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (!triggered && !rv.canScrollVertically(-1) && e.y - startY > dp(90)) {
                            triggered = true
                            openSearch()
                        }
                    }
                }
                return false
            }
        })

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

    private fun openSearch() {
        search.requestFocus()
        (getSystemService(InputMethodManager::class.java)).showSoftInput(search, 0)
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
        toast(if (on) "Düzenleme açık: simgeyi sürükle, başka simgenin üstüne bırakırsan klasör olur" else "Düzenleme kapalı")
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
                val found = findContacts(q)
                main.post {
                    if (gen == searchGen && !isDestroyed && found.isNotEmpty()) showRows(q, found)
                }
            }
        }
    }

    private fun showRows(q: String, contacts: List<Row>) {
        rows.clear()
        rows.addAll(contacts)
        val fq = fold(q)
        val matches = ArrayList<String>()
        for (k in apps.keys) {
            val n = normLabels[k] ?: continue
            if (n.contains(fq)) matches.add(k)
        }
        matches.sortWith(compareBy<String>({ normLabels[it]?.startsWith(fq) != true }, { normLabels[it] ?: "" }))
        for (k in matches.take(6)) {
            val label = apps[k]?.label?.toString() ?: continue
            rows.add(Row(label, "Uygulama", iconCache[k]) { launch(k) })
        }
        val enc = Uri.encode(q)
        rows.add(Row("Google'da ara: $q", null, null) { openUrl("https://www.google.com/search?q=$enc") })
        rows.add(Row("Yandex'te ara: $q", null, null) { openUrl("https://yandex.com/search/?text=$enc") })
        rows.add(Row("IMDb'de ara: $q", null, null) { openUrl("https://www.imdb.com/find/?q=$enc") })
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
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, name),
                "$name LIKE ? ESCAPE '\\'", arrayOf("%$esc%"),
                "$name COLLATE LOCALIZED ASC LIMIT 5"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val lookup = c.getString(1)
                    val nm = c.getString(2) ?: continue
                    out.add(Row(nm, "Kişi", null) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, lookup)))
                        } catch (e: Exception) { toast("Kişi açılamadı") }
                    })
                }
            }
        } catch (e: Exception) { }
        return out
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
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val h: RowHolder
            val v: View
            if (cv == null) {
                val line = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val iv = ImageView(this@MainActivity)
                line.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val title = TextView(this@MainActivity).apply {
                    textSize = 16f; setTextColor(Color.WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }
                val sub = TextView(this@MainActivity).apply {
                    textSize = 12f; setTextColor(0xAAFFFFFF.toInt())
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
        if (key.startsWith("folder:")) openFolder(key) else launch(key)
    }

    // ---------- Sürükle-bırak: klasör oluşturma / uzun basma menüsü ----------
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
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && active) { dx = dX; dy = dY }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().cancel()
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
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
        val tcx = target.left + target.width / 2f
        val tcy = target.top + target.height / 2f
        val move = hypot(cx - tcx, cy - tcy) > target.width * 0.4f
        // Değişiklik, RecyclerView kendi çizim/animasyon döngüsünden çıktıktan sonra uygulanır.
        rv.post { if (move) moveKey(key, targetKey) else mergeInto(key, targetKey) }
    }

    private fun moveKey(key: String, targetKey: String) {
        val from = order.indexOf(key)
        val to = order.indexOf(targetKey)
        if (from < 0 || to < 0 || from == to) return
        val item = order.removeAt(from)
        order.add(to, item)
        persistAndRefresh()
    }

    private fun mergeInto(drag: String, target: String) {
        if (drag.startsWith("folder:")) return
        if (target.startsWith("folder:")) {
            val f = folders[target] ?: return
            if (!f.children.contains(drag)) f.children.add(drag)
            order.remove(drag)
        } else {
            val idx0 = order.indexOf(target)
            if (idx0 < 0 || !order.contains(drag)) return
            val id = "folder:" + UUID.randomUUID().toString().take(8)
            order.remove(drag)
            val idx = order.indexOf(target)
            order[idx] = id
            folders[id] = Folder("Klasör", mutableListOf(target, drag))
        }
        persistAndRefresh()
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
            cell.setOnClickListener { d.dismiss(); launch(ck) }
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
            flags = flags or ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_CACHED
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
        val scs = shortcuts.values.toList()
        val size = iconPx
        worker.execute {
            var snap: Snapshot? = null
            try {
                val list = launcherApps.getActivityList(null, me)
                // Sistem geçici olarak boş liste dönerse (kilit açılırken vb.) düzeni silmemek için yok say.
                if (!list.isNullOrEmpty()) {
                    val map = LinkedHashMap<String, LauncherActivityInfo>(list.size * 2)
                    val icons = HashMap<String, Bitmap>()
                    for (a in list) {
                        val k = "app:" + a.componentName.flattenToString()
                        map[k] = a
                        if (k !in known && k !in icons) {
                            try { icons[k] = renderIcon(a.getBadgedIcon(0), size) } catch (e: Exception) { }
                        }
                    }
                    for (s in scs) {
                        val k = "sc:${s.pkg}/${s.id}"
                        if (k !in known) {
                            try { scIcon(s)?.let { icons[k] = renderIcon(it, size) } } catch (e: Exception) { }
                        }
                    }
                    snap = Snapshot(map, icons)
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
        normLabels.clear()
        for ((k, a) in apps) normLabels[k] = fold(a.label.toString())
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
            .sortedWith { a, b -> collator.compare(apps[a]!!.label.toString(), apps[b]!!.label.toString()) }
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
        k.startsWith("app:") -> apps[k]?.label?.toString() ?: ""
        k.startsWith("sc:") -> shortcuts[k]?.label ?: ""
        k.startsWith("folder:") -> folders[k]?.name ?: ""
        else -> ""
    }

    /** Arka plan thread'inde de çağrılabilir. */
    private fun scIcon(s: Sc): Drawable? {
        try {
            val q = ShortcutQuery().setPackage(s.pkg).setShortcutIds(listOf(s.id)).setQueryFlags(
                ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_CACHED
            )
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
