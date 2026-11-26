package github.idrisokys.myl.ocr

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import github.idrisokys.myl.L
import github.idrisokys.myl.common.KFileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    private lateinit var closeBtn: View
    private lateinit var cropBtn: View
    private var crop: CropOverlayView? = null
    private var cropBar: View? = null

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
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(104); marginStart = dp(16); marginEnd = dp(16) })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start() }, 4000)

        // Kapat (alan seçme modundayken önce o moddan çıkar)
        closeBtn = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA222222.toInt()) }
            setOnClickListener { if (crop != null) exitCrop() else finish() }
        }
        root.addView(closeBtn, FrameLayout.LayoutParams(dp(52), dp(52), android.view.Gravity.BOTTOM or android.view.Gravity.START).apply { bottomMargin = dp(36); marginStart = dp(20) })

        // Alan seç → resim olarak paylaş (✕'in soluna)
        cropBtn = ImageView(this).apply {
            setImageResource(github.idrisokys.myl.R.drawable.ic_crop)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA222222.toInt()) }
            contentDescription = L("Alanı seç ve resim olarak paylaş", "Select an area and share as image")
            setOnClickListener { enterCrop() }
        }
        root.addView(cropBtn, FrameLayout.LayoutParams(dp(52), dp(52), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { bottomMargin = dp(36); marginEnd = dp(20) })

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
        for (i in 0 until root.childCount) { val v = root.getChildAt(i); if (v !is SelTv && (v !is ImageView || v === cropBtn)) v.bringToFront() }
    }

    // ---------- Alan seç → resim olarak paylaş ----------

    /** Görüntünün ekrandaki ölçeği ve kayması: [sc, ox, oy]. */
    private fun fit(bmp: Bitmap): FloatArray {
        val sc = minOf(root.width.toFloat() / bmp.width, root.height.toFloat() / bmp.height)
        return floatArrayOf(sc, (root.width - bmp.width * sc) / 2f, (root.height - bmp.height * sc) / 2f)
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = GradientDrawable().apply { setColor(if (filled) 0xFFEC4E6A.toInt() else 0xCC222222.toInt()); cornerRadius = dp(24).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun enterCrop() {
        if (crop != null) return
        val bmp = OcrHolder.bmp ?: return
        if (root.width == 0 || root.height == 0) return
        clearSelections()
        hint.visibility = View.GONE
        val (sc, ox, oy) = fit(bmp).let { Triple(it[0], it[1], it[2]) }
        val v = CropOverlayView(this, RectF(ox, oy, ox + bmp.width * sc, oy + bmp.height * sc))
        crop = v
        root.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(pill(L("İptal", "Cancel"), false) { exitCrop() })
            addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
            addView(pill(L("Paylaş", "Share"), true) { shareCrop() })
        }
        cropBar = bar
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })

        cropBtn.visibility = View.GONE
        closeBtn.bringToFront()
    }

    private fun exitCrop() {
        crop?.let { root.removeView(it) }; crop = null
        cropBar?.let { root.removeView(it) }; cropBar = null
        cropBtn.visibility = View.VISIBLE
    }

    /** Seçili alanı ekran görüntüsünden keser, PNG olarak kaydeder ve paylaşma menüsünü açar. */
    private fun shareCrop() {
        val v = crop ?: return
        val bmp = OcrHolder.bmp ?: return
        val (sc, ox, oy) = fit(bmp).let { Triple(it[0], it[1], it[2]) }
        val r = v.rect
        val l = ((r.left - ox) / sc).roundToInt().coerceIn(0, bmp.width - 1)
        val t = ((r.top - oy) / sc).roundToInt().coerceIn(0, bmp.height - 1)
        val w = ((r.right - ox) / sc).roundToInt().coerceIn(l + 1, bmp.width) - l
        val h = ((r.bottom - oy) / sc).roundToInt().coerceIn(t + 1, bmp.height) - t
        if (w < 8 || h < 8) { Toast.makeText(this, L("Seçilen alan çok küçük", "Selected area is too small"), Toast.LENGTH_SHORT).show(); return }

        val cache = cacheDir
        Thread {
            val f: File? = try {
                val dir = File(cache, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }   // eski paylaşımları temizle
                val out = File(dir, "ekran_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png")
                val piece = Bitmap.createBitmap(bmp, l, t, w, h)
                FileOutputStream(out).use { piece.compress(Bitmap.CompressFormat.PNG, 100, it) }
                piece.recycle()
                out
            } catch (e: Throwable) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (f == null) { Toast.makeText(this, L("Resim hazırlanamadı", "Could not prepare the image"), Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val uri = KFileProvider.uriFor(this, f)
                val send = Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("", uri)
                try { startActivity(Intent.createChooser(send, null)); exitCrop() }
                catch (e: Exception) { Toast.makeText(this, L("Paylaşılamadı", "Could not share"), Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (crop != null) exitCrop() else super.onBackPressed() }

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
