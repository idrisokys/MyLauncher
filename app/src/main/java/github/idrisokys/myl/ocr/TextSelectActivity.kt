package github.idrisokys.myl.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.text.style.ScaleXSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import github.idrisokys.myl.L

/**
 * Ekran görüntüsü tam ekran gösterilir; OCR ile okunan her paragraf, görüntünün üstüne tam yerine
 * görünmez (şeffaf) ama seçilebilir bir TextView olarak yerleştirilir. Seçim, imleç damlaları ve üstte
 * çıkan Kopyala / Tümünü seç / Paylaş paneli Android'in KENDİ metin seçim özelliğidir.
 * Panele "Google" ve (sistem eklemediyse) "Paylaş" eklenir. Kopyala → panoya yazar ve kapanır.
 */
class TextSelectActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var hint: TextView
    private val tvs = ArrayList<SelTv>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Sistem "Kopyala"sı çalıştıktan sonra ekranı kapatır. */
    private inner class SelTv(ctx: Context) : TextView(ctx) {
        override fun onTextContextMenuItem(id: Int): Boolean {
            val r = super.onTextContextMenuItem(id)
            if (id == android.R.id.copy) finish()
            return r
        }
        fun selected(): String {
            val s = selectionStart; val e = selectionEnd
            if (s < 0 || e <= s) return ""
            return text.subSequence(s, e).toString()
        }
    }

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
        // Ekran görüntüsü (boş bir yere dokununca seçimi kaldırır)
        root.addView(ImageView(this).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { clearSelections() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Metin katmanı: görüntü ölçeği hesaplanınca (yerleşimden sonra) kurulur
        root.post { buildTextLayer(bmp.width, bmp.height) }

        // İpucu
        hint = TextView(this).apply {
            text = L("Seçmek için bir metne basılı tut", "Long-press text to select")
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(0xCC222222.toInt()); cornerRadius = dp(20).toFloat() }
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48); marginStart = dp(16); marginEnd = dp(16) })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start() }, 4000)

        // Kapat
        root.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA222222.toInt()) }
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = dp(36); marginEnd = dp(14) })

        setContentView(root)
    }

    private class Line(val text: String, val box: RectF)

    /** Her OCR paragrafı için, görüntüdeki metnin birebir üstüne oturan şeffaf, seçilebilir TextView kurar. */
    private fun buildTextLayer(bw: Int, bh: Int) {
        if (root.width == 0 || root.height == 0) return
        val sc = minOf(root.width.toFloat() / bw, root.height.toFloat() / bh)
        val ox = (root.width - bw * sc) / 2f; val oy = (root.height - bh * sc) / 2f

        val words = OcrHolder.words
        // paragraf → satır → kelimeler
        val blocks = LinkedHashMap<Int, LinkedHashMap<Int, ArrayList<OcrEngine.W>>>()
        for (w in words) blocks.getOrPut(w.block) { LinkedHashMap() }.getOrPut(w.line) { ArrayList() }.add(w)

        val probe = Paint()
        for ((_, lineMap) in blocks) {
            val lines = lineMap.values.map { ws ->
                val u = RectF(ws[0].box); for (w in ws) u.union(w.box)
                Line(ws.joinToString(" ") { it.text }, u)
            }.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) continue

            val blockBox = RectF(lines[0].box); for (l in lines) blockBox.union(l.box)

            // Yazı boyutu: satır kutusu yüksekliğine göre (ortanca)
            val hs = lines.map { it.box.height() * sc }.sorted()
            val lineH = hs[hs.size / 2]
            probe.textSize = 100f
            val fm = probe.fontMetrics
            val textSize = (lineH * 100f / (fm.descent - fm.ascent)).coerceAtLeast(6f)
            probe.textSize = textSize
            val fontLine = probe.fontMetrics.let { it.descent - it.ascent }

            // Satırlar arası adım (ilk satır tepesinden sonrakine)
            val pitch = if (lines.size > 1) (lines.last().box.top - lines.first().box.top) * sc / (lines.size - 1) else fontLine

            val sb = SpannableStringBuilder()
            lines.forEachIndexed { i, l ->
                if (i > 0) sb.append('\n')
                val s = sb.length
                sb.append(l.text)
                val e = sb.length
                // Satırın genişliği görüntüdekiyle aynı olsun
                val mw = probe.measureText(l.text)
                val target = l.box.width() * sc
                if (mw > 0f) sb.setSpan(ScaleXSpan(target / mw), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Satır girintisi (ortalı / girintili metinler için)
                val lead = ((l.box.left - blockBox.left) * sc).toInt()
                if (lead > 0) sb.setSpan(LeadingMarginSpan.Standard(lead), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val tv = SelTv(this).apply {
                text = sb
                setTextColor(Color.TRANSPARENT)             // görüntü zaten metni gösteriyor
                setHighlightColor(0x66EC4E6A)               // seçim rengi
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                includeFontPadding = false
                setLineSpacing(pitch - fontLine, 1f)
                setHorizontallyScrolling(true)              // satırlar asla kendiliğinden kırılmasın
                setPadding(0, 0, 0, 0)
                setTextIsSelectable(true)                   // Android'in kendi seçimi
                customSelectionActionModeCallback = actionCallback(this)
            }
            tvs.add(tv)
            root.addView(tv, FrameLayout.LayoutParams(
                (blockBox.width() * sc).toInt() + dp(6), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (blockBox.left * sc + ox).toInt()
                topMargin = (blockBox.top * sc + oy).toInt()
            })
        }
        // Kapat düğmesi ve ipucu metinlerin üstünde kalsın
        for (i in 0 until root.childCount) { val v = root.getChildAt(i); if (v !is SelTv && v !is ImageView) v.bringToFront() }
    }

    private val ID_GOOGLE = 0x6001
    private val ID_SHARE = 0x6002

    /** Android'in seçim panelinin yanına Google (ve gerekirse Paylaş) ekler. */
    private fun actionCallback(tv: SelTv) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            hint.visibility = View.GONE
            // Seçim başka paragrafta başladıysa eskisini kaldır (aynı anda tek seçim)
            for (o in tvs) if (o !== tv) clearOne(o)
            menu.add(0, ID_GOOGLE, 100, "Google").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (menu.findItem(android.R.id.shareText) == null)
                menu.add(0, ID_SHARE, 101, L("Paylaş", "Share")).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val t = tv.selected()
            when (item.itemId) {
                ID_GOOGLE -> { google(t); mode.finish(); return true }
                ID_SHARE -> { share(t); mode.finish(); return true }
            }
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    private fun clearOne(tv: SelTv) {
        (tv.text as? Spannable)?.let { Selection.removeSelection(it) }
        tv.setTextIsSelectable(false); tv.setTextIsSelectable(true)
    }

    private fun clearSelections() { for (t in tvs) clearOne(t) }

    private fun google(raw: String) {
        val t = raw.trim().replace(Regex("\\s+"), " ").take(300)
        if (t.isEmpty()) return
        val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { Toast.makeText(this, L("Tarayıcı açılamadı", "Could not open browser"), Toast.LENGTH_SHORT).show() }
    }

    private fun share(t: String) {
        if (t.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
    }

    override fun onDestroy() { if (isFinishing) OcrHolder.clear(); super.onDestroy() }
}
