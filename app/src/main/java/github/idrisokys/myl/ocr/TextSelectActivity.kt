package github.idrisokys.myl.ocr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import github.idrisokys.myl.L

/**
 * Ekran görüntüsü, ekranın birebir üstünde tam ekran gösterilir; metin yerinde seçilir.
 * Kopyala → panoya yazar ve kapanır (önceki uygulamaya dönülür). Geri tuşu / ✕ ile kapanır.
 */
class TextSelectActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var tv: TextSelectView
    private lateinit var bar: LinearLayout
    private lateinit var hint: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val bmp = OcrHolder.bmp
        if (bmp == null) { finish(); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextSelectView(this).apply { set(bmp, OcrHolder.words) }
        root.addView(tv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Seçim araç çubuğu
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0xF2222222.toInt()); cornerRadius = dp(24).toFloat() }
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        fun item(t: String, f: () -> Unit) {
            if (bar.childCount > 0) bar.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL })
            bar.addView(TextView(this).apply {
                text = t; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(11), dp(16), dp(11)); setOnClickListener { f() }
            })
        }
        item(L("Kopyala", "Copy")) { copy() }
        item(L("Tümünü seç", "Select all")) { tv.selectAll() }
        item(L("Paylaş", "Share")) { share() }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // İpucu
        hint = TextView(this).apply {
            text = L("Seçmek için bir kelimeye basılı tut · paragraf için çift dokun", "Long-press a word to select · double-tap for a paragraph")
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(0xCC222222.toInt()); cornerRadius = dp(20).toFloat() }
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48); marginStart = dp(16); marginEnd = dp(16) })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start() }, 4000)

        // Kapat
        root.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA222222.toInt()) }
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(36); marginEnd = dp(14) })

        tv.onSelection = { has, r, dragging ->
            if (has) hint.visibility = View.GONE
            if (!has || dragging || r == null) bar.visibility = View.GONE
            else { bar.visibility = View.VISIBLE; place(r) }
        }
        setContentView(root)
    }

    /** Araç çubuğunu seçimin üstüne (yer yoksa altına) koyar. */
    private fun place(r: RectF) {
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = bar.measuredWidth; val h = bar.measuredHeight
        val x = (r.centerX() - w / 2f).coerceIn(dp(8).toFloat(), (root.width - w - dp(8)).coerceAtLeast(dp(8)).toFloat())
        var y = r.top - h - dp(12)
        if (y < dp(48)) y = r.bottom + dp(48)
        bar.x = x; bar.y = y
    }

    private fun copy() {
        val t = tv.selectedText()
        if (t.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MyL", t))
        Toast.makeText(this, L("Kopyalandı", "Copied"), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun share() {
        val t = tv.selectedText()
        if (t.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
    }

    override fun onDestroy() { if (isFinishing) OcrHolder.clear(); super.onDestroy() }
}
