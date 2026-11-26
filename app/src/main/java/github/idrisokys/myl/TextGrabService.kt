package github.idrisokys.myl

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Ekran metni seçici.
 *
 * Sol kenarda (geri hareketi bölgesinin hemen içinde) saydam, ince bir çizgi durur. Çizgi sağa çekilince o an ekranda
 * görünen tüm uygulama pencerelerinin metni okunur (erişilebilirlik API'si) ve tam ekran bir panelde TEK bir seçilebilir
 * metin olarak gösterilir: cümleleri parmakla seçip sistemin "Kopyala" düğmesiyle kopyalayabilir ya da "Tümünü kopyala"ya
 * basabilirsiniz. Şifre alanları okunmaz. Hizmet MyL ile birlikte gelir; Ayarlar > Erişilebilirlik'ten bir kez açılır
 * (MyL ⚙ menüsündeki "Ekran metni" simgesi oraya götürür).
 */
class TextGrabService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var handle: HandleView? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addHandle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removePanel(); removeHandle()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removePanel(); removeHandle()
        super.onDestroy()
    }

    // ---------- Kenar çizgisi ----------
    private fun addHandle() {
        if (handle != null) return
        val v = HandleView(this)
        val lp = WindowManager.LayoutParams(
            dp(IDLE_W), dp(HANDLE_H),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            // Sistemin kenar geri hareketi ilk ~24-40 dp'yi alır; çizgi onun içinde durur
            x = dp(GESTURE_INSET)
            title = "MyL ekran metni"
        }
        try {
            wm.addView(v, lp)
            handle = v; handleLp = lp
        } catch (e: Exception) { }
    }

    private fun removeHandle() {
        handle?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        handle = null; handleLp = null
    }

    /** Sürükleme sırasında çizginin esneyebilmesi için pencere geçici olarak genişler. */
    private fun setHandleWide(wide: Boolean) {
        val v = handle ?: return
        val lp = handleLp ?: return
        lp.width = dp(if (wide) WIDE_W else IDLE_W)
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private inner class HandleView(c: Context) : View(c) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(3).toFloat()
        }
        private var downX = 0f
        private var pull = 0f           // 0..THRESHOLD*1.4 (px)
        private var active = false
        private var fired = false
        private val threshold = dp(PULL_DP).toFloat()

        init {
            // Sistem geri hareketiyle çakışmasın
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; pull = 0f; active = true; fired = false
                    setHandleWide(true); invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (active && !fired) {
                    pull = (e.rawX - downX).coerceIn(0f, threshold * 1.4f)
                    invalidate()
                    if (pull >= threshold) {
                        fired = true
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        post { onPulled() }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = false; pull = 0f
                    setHandleWide(false); invalidate()
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val x = dp(IDLE_W) / 2f
            val h = height.toFloat()
            val pad = dp(26).toFloat()
            val t = (pull / threshold).coerceIn(0f, 1f)
            line.color = if (fired) 0xFF7CF29A.toInt() else Color.argb((70 + 150 * t).toInt(), 255, 255, 255)
            if (pull <= 0f) {
                c.drawLine(x, pad, x, h - pad, line)
            } else {
                // Çekilen yöne doğru bombeleşen yay
                val p = Path()
                p.moveTo(x, pad)
                p.quadTo(x + pull * 1.9f, h / 2f, x, h - pad)
                c.drawPath(p, line)
            }
        }
    }

    private fun onPulled() {
        val text = collectText()
        if (text.isBlank()) {
            Toast.makeText(this, L("Ekranda seçilebilir metin bulunamadı", "No selectable text found on screen"), Toast.LENGTH_SHORT).show()
            return
        }
        showPanel(text)
    }

    // ---------- Ekran metnini toplama ----------
    private class Item(val text: String, val r: Rect)

    private fun collectText(): String {
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        val screenH = resources.displayMetrics.heightPixels
        try {
            for (w in windows) {
                val r = Rect(); w.getBoundsInScreen(r)
                val ok = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> true
                    // Bildirim paneli gibi büyük sistem pencereleri (durum çubuğu saati vb. değil)
                    AccessibilityWindowInfo.TYPE_SYSTEM -> r.height() > screenH / 3
                    else -> false // klavye, kendi katmanlarımız (erişilebilirlik katmanı) vb.
                }
                if (ok) w.root?.let { roots.add(it) }
            }
        } catch (e: Exception) { }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        var budget = MAX_NODES
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0 || depth > 60) return
            if (!n.isVisibleToUser) return
            if (!n.isPassword) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (!r.isEmpty && seen.add("$t@${r.left},${r.top}")) items.add(Item(t, r))
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                walk(c, depth + 1)
                @Suppress("DEPRECATION") c.recycle()
            }
        }
        for (r in roots) walk(r, 0)

        // Okuma sırası: yukarıdan aşağıya satırlar, satır içinde soldan sağa
        items.sortWith(compareBy({ it.r.top }, { it.r.left }))
        val lines = ArrayList<String>()
        var row = ArrayList<Item>()
        var rowTop = 0; var rowBottom = 0
        fun flush() {
            if (row.isEmpty()) return
            lines.add(row.sortedBy { it.r.left }.joinToString("  ") { it.text })
            row = ArrayList()
        }
        for (item in items) {
            val cy = (item.r.top + item.r.bottom) / 2
            if (row.isNotEmpty() && cy in rowTop..rowBottom) {
                row.add(item); rowBottom = maxOf(rowBottom, item.r.bottom)
            } else {
                flush(); row.add(item); rowTop = item.r.top; rowBottom = item.r.bottom
            }
        }
        flush()
        return lines.joinToString("\n")
    }

    // ---------- Seçilebilir metin paneli ----------
    private fun showPanel(text: String) {
        removePanel()
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (e.action == KeyEvent.ACTION_UP) removePanel()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            setBackgroundColor(0xF2121214.toInt())
            isFocusable = true; isFocusableInTouchMode = true
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = label
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(18).toFloat() }
            setOnClickListener { onClick() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(TextView(this).apply {
            this.text = L("Ekrandaki metin", "Text on screen")
            setTextColor(0xCCFFFFFF.toInt()); textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(L("Tümünü kopyala", "Copy all")) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MyL", text))
            Toast.makeText(this, L("Kopyalandı", "Copied"), Toast.LENGTH_SHORT).show()
        })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        bar.addView(pill("✕") { removePanel() })
        col.addView(bar)

        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 17f
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            highlightColor = 0x665B9CFF
            setPadding(dp(18), dp(6), dp(18), dp(28))
        }
        col.addView(ScrollView(this).apply { addView(tv) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { title = "MyL ekran metni paneli" }
        try {
            wm.addView(root, lp)
            panel = root
            root.requestFocus()
            handle?.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, L("Panel açılamadı", "Could not open panel"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
        handle?.visibility = View.VISIBLE
    }

    companion object {
        private const val IDLE_W = 22        // dp: çizginin boşta pencere genişliği
        private const val WIDE_W = 190       // dp: sürüklerken
        private const val HANDLE_H = 260     // dp
        private const val GESTURE_INSET = 28 // dp: kenar geri hareketinin dışında kalması için
        private const val PULL_DP = 90       // dp: bu kadar sağa çekilince tetiklenir
        private const val MAX_NODES = 6000

        /** MyL ekran metni hizmeti Erişilebilirlik ayarlarında açık mı? */
        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, TextGrabService::class.java)
            return s.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
