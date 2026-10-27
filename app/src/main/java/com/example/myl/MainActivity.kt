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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot

class MainActivity : Activity() {

    // ---------- Veri modelleri ----------
    class Folder(var name: String, val children: MutableList<String>)
    class Sc(val pkg: String, val id: String, var label: String)
    class Row(val title: String, val sub: String?, val icon: Drawable?, val action: () -> Unit)

    private lateinit var launcherApps: LauncherApps
    private lateinit var prefs: SharedPreferences
    private val me: UserHandle = Process.myUserHandle()

    private val apps = LinkedHashMap<String, LauncherActivityInfo>()   // "app:<component>"
    private val folders = LinkedHashMap<String, Folder>()              // "folder:<uuid>"
    private val shortcuts = LinkedHashMap<String, Sc>()                // "sc:<pkg>/<id>"
    private val order = mutableListOf<String>()                        // ekranda görünen sıra
    private val iconCache = HashMap<String, Drawable>()

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        reload()
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
        // ColorOS bazı paket olaylarını kaçırabildiği için emniyet kemeri
        iconCache.clear()
        reload()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(callback)
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
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refreshAll()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refreshAll()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refreshAll()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refreshAll()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refreshAll()
    }

    private fun refreshAll() {
        iconCache.clear()
        reload()
    }

    // ---------- Arayüz ----------
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }

        // Üst çubuk: arama kutusu + küçük "Varsayılan" düğmesi
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
        toast(if (on) "Taşıma modu açık: simgeyi basılı tutup sürükle" else "Taşıma modu kapalı")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Tarayıcı açılamadı")
        }
        closeSearch()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Arama ----------
    private fun updateResults(q0: String) {
        val q = q0.trim()
        if (q.isEmpty()) {
            results.visibility = View.GONE
            return
        }
        rows.clear()
        rows.addAll(findContacts(q))
        var n = 0
        for ((k, a) in apps) {
            if (n >= 6) break
            val label = a.label.toString()
            if (label.contains(q, ignoreCase = true)) {
                rows.add(Row(label, "Uygulama", iconFor(k)) { launch(k) })
                n++
            }
        }
        val enc = Uri.encode(q)
        rows.add(Row("Google'da ara: $q", null, null) { openUrl("https://www.google.com/search?q=$enc") })
        rows.add(Row("Yandex'te ara: $q", null, null) { openUrl("https://yandex.com/search/?text=$enc") })
        rows.add(Row("IMDb'de ara: $q", null, null) { openUrl("https://www.imdb.com/find/?q=$enc") })
        resultsAdapter.notifyDataSetChanged()
        results.visibility = View.VISIBLE
    }

    private fun findContacts(q: String): List<Row> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<Row>()
        try {
            val name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, name),
                "$name LIKE ?", arrayOf("%$q%"),
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

    private val resultsAdapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val r = rows[p]
            val line = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val iv = ImageView(this@MainActivity)
            iv.setImageDrawable(r.icon ?: GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x44FFFFFF)
            })
            line.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this@MainActivity).apply {
                text = r.title; textSize = 16f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (r.sub != null) col.addView(TextView(this@MainActivity).apply {
                text = r.sub; textSize = 12f; setTextColor(0xAAFFFFFF.toInt())
            })
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            return line
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
            return VH(cell, iv, tv)
        }

        override fun getItemCount() = order.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val k = order[position]
            h.label.text = labelFor(k)
            h.icon.setImageDrawable(iconFor(k))
            h.cell.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) onTap(order[p])
            }
        }
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

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
            if (!editMode) return false
            val from = vh.bindingAdapterPosition
            val to = t.bindingAdapterPosition
            if (from < 0 || to < 0 || from >= order.size || to >= order.size) return false
            val item = order.removeAt(from)
            order.add(to, item)
            gridAdapter.notifyItemMoved(from, to)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun isLongPressDragEnabled() = true

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
            if (editMode) savePrefs() else handleDrop(rv, vh, dx, dy)
            dx = 0f; dy = 0f
        }
    })

    private fun handleDrop(rv: RecyclerView, vh: RecyclerView.ViewHolder, dx: Float, dy: Float) {
        val pos = vh.bindingAdapterPosition
        if (pos < 0 || pos >= order.size) return
        val key = order[pos]
        // Neredeyse hiç kıpırdamadan bırakıldıysa: uzun basma menüsü
        if (hypot(dx, dy) < dp(14)) {
            showMenu(key, vh.itemView, null)
            return
        }
        val cx = vh.itemView.left + vh.itemView.width / 2f + dx
        val cy = vh.itemView.top + vh.itemView.height / 2f + dy
        val target = rv.findChildViewUnder(cx, cy) ?: return
        val tp = rv.getChildAdapterPosition(target)
        if (tp < 0 || tp >= order.size || tp == pos) return
        val tcx = target.left + target.width / 2f
        val tcy = target.top + target.height / 2f
        if (hypot(cx - tcx, cy - tcy) > target.width * 0.4f) return
        mergeInto(key, order[tp])
    }

    private fun mergeInto(drag: String, target: String) {
        if (drag.startsWith("folder:")) return
        if (target.startsWith("folder:")) {
            folders[target]?.children?.add(drag)
            order.remove(drag)
        } else {
            val id = "folder:" + UUID.randomUUID().toString().take(8)
            order.remove(drag)
            val idx = order.indexOf(target)
            if (idx < 0) return
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
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    true
                }
                menu.add("Kaldır").setOnMenuItemClickListener {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
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
        persistAndRefresh()
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
            iv.setImageDrawable(iconFor(ck))
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
        if (pin) {
            try {
                val pinned = shortcutsOf(pkg, pinnedOnly = true).map { it.id }
                launcherApps.pinShortcuts(pkg, (pinned + si.id).distinct(), me)
            } catch (e: Exception) { }
        }
        persistAndRefresh()
        grid.post { grid.smoothScrollToPosition(order.size - 1) }
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
        savePrefs()
        gridAdapter.notifyDataSetChanged()
    }

    private fun isValid(k: String) = when {
        k.startsWith("app:") -> apps.containsKey(k)
        k.startsWith("sc:") -> shortcuts.containsKey(k)
        else -> false
    }

    /** Yüklü uygulamaları okur; sırayı, klasörleri düzeltir. Yeni uygulamalar listenin SONUNA eklenir. */
    private fun reload() {
        apps.clear()
        for (a in launcherApps.getActivityList(null, me)) {
            apps["app:" + a.componentName.flattenToString()] = a
        }
        // Klasörleri temizle
        for (f in folders.values) f.children.removeAll { !isValid(it) }
        for (fk in folders.keys.toList()) {
            val f = folders[fk]!!
            if (f.children.size <= 1) {
                val idx = order.indexOf(fk)
                if (idx >= 0) {
                    order.removeAt(idx)
                    if (f.children.size == 1) order.add(idx, f.children[0])
                }
                folders.remove(fk)
            }
        }
        val inFolder = folders.values.flatMap { it.children }.toHashSet()
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
            .sortedBy { apps[it]!!.label.toString().lowercase() }
        res.addAll(missing)
        order.clear()
        order.addAll(res)
        savePrefs()
        gridAdapter.notifyDataSetChanged()
        if (search.text.isNotEmpty()) updateResults(search.text.toString())
    }

    // ---------- Etiket / ikon ----------
    private fun labelFor(k: String): String = when {
        k.startsWith("app:") -> apps[k]?.label?.toString() ?: ""
        k.startsWith("sc:") -> shortcuts[k]?.label ?: ""
        k.startsWith("folder:") -> folders[k]?.name ?: ""
        else -> ""
    }

    private fun iconFor(k: String): Drawable? {
        if (k.startsWith("folder:")) {
            val f = folders[k]
            return FolderDrawable(f?.children?.take(4)?.map { iconFor(it) }.orEmpty(), dp(56))
        }
        var d = iconCache[k]
        if (d == null) {
            d = when {
                k.startsWith("app:") -> apps[k]?.getBadgedIcon(0)
                k.startsWith("sc:") -> scIcon(k)
                else -> null
            }
            if (d != null) iconCache[k] = d
        }
        // Her görünüm kendi kopyasını alır (sınırlar çakışmasın)
        return d?.constantState?.newDrawable()?.mutate() ?: d
    }

    private fun scIcon(k: String): Drawable? {
        val s = shortcuts[k] ?: return null
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

}

/** Klasör simgesi: yarı saydam yuvarlak zemin üzerinde en çok 4 mini simge. */
class FolderDrawable(icons: List<Drawable?>, private val sizePx: Int) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val minis: List<Drawable?> = icons.take(4).map { it?.constantState?.newDrawable()?.mutate() ?: it }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        canvas.drawRoundRect(RectF(b), w * 0.28f, w * 0.28f, bg)
        val pad = w * 0.12f
        val cell = (w - pad * 3) / 2f
        minis.forEachIndexed { i, d ->
            if (d == null) return@forEachIndexed
            val col = i % 2
            val row = i / 2
            val l = b.left + pad + col * (cell + pad)
            val t = b.top + pad + row * (cell + pad)
            d.setBounds(l.toInt(), t.toInt(), (l + cell).toInt(), (t + cell).toInt())
            d.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { bg.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
