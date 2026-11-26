package github.idrisokys.myl.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Ekran görüntüsünü çizer; üstüne OCR kelimeleriyle sistemdeki gibi seçim yapılır:
 * kelimeye basılı tut → seç, parmağı sürükle → genişlet, iki ucundaki damlalarla ayarla, çift dokun → paragrafı seç.
 */
class TextSelectView(ctx: Context) : View(ctx) {
    /** (seçim var mı, seçimin ekrandaki dikdörtgeni, sürükleniyor mu) */
    var onSelection: ((Boolean, RectF?, Boolean) -> Unit)? = null

    private var bmp: Bitmap? = null
    private var words: List<OcrEngine.W> = emptyList()
    private var vb: Array<RectF> = emptyArray()      // kelimelerin görünüm koordinatları
    private var sc = 1f; private var ox = 0f; private var oy = 0f
    private var a = -1; private var b = -1           // seçim aralığı (dahil)
    private var drag = 0                              // 0 yok, 1 baş damla, 2 son damla, 3 uzatma
    private var anchor = -1
    private val d = resources.displayMetrics.density
    private val hs = 22 * d                           // damla boyutu
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66EC4E6A }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEC4E6A.toInt() }

    val hasSelection get() = a >= 0

    fun set(bitmap: Bitmap, w: List<OcrEngine.W>) { bmp = bitmap; words = w; relayout(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    private fun relayout() {
        val bm = bmp ?: return
        if (width == 0 || height == 0) return
        sc = minOf(width.toFloat() / bm.width, height.toFloat() / bm.height)
        ox = (width - bm.width * sc) / 2f; oy = (height - bm.height * sc) / 2f
        vb = Array(words.size) { i -> val r = words[i].box; RectF(r.left * sc + ox, r.top * sc + oy, r.right * sc + ox, r.bottom * sc + oy) }
    }

    fun clear() { a = -1; b = -1; invalidate(); notifySel(false) }
    fun selectAll() { if (words.isNotEmpty()) { a = 0; b = words.size - 1; invalidate(); notifySel(false) } }

    fun selectedText(): String {
        if (a < 0) return ""
        val sb = StringBuilder()
        for (i in a..b) {
            if (i > a) sb.append(if (words[i].block != words[i - 1].block) "\n" else " ")
            sb.append(words[i].text)
        }
        return sb.toString()
    }

    // ---------- çizim ----------
    override fun onDraw(c: Canvas) {
        val bm = bmp ?: return
        c.save(); c.translate(ox, oy); c.scale(sc, sc); c.drawBitmap(bm, 0f, 0f, pImg); c.restore()
        if (a < 0) return
        val rs = lineRects()
        for (r in rs) { val q = RectF(r); q.inset(-2 * d, -2 * d); c.drawRoundRect(q, 3 * d, 3 * d, pHi) }
        val f = rs.first(); val l = rs.last()
        handle(c, f.left, f.bottom + 2 * d, true)
        handle(c, l.right, l.bottom + 2 * d, false)
    }

    private fun handle(c: Canvas, x: Float, y: Float, start: Boolean) {
        val r = hs / 2f
        val p = Path()
        if (start) p.addRoundRect(RectF(x - hs, y, x, y + hs), floatArrayOf(r, r, 0f, 0f, r, r, r, r), Path.Direction.CW)
        else p.addRoundRect(RectF(x, y, x + hs, y + hs), floatArrayOf(0f, 0f, r, r, r, r, r, r), Path.Direction.CW)
        c.drawPath(p, pHandle)
    }

    /** Seçili kelimeleri satır satır birleştirir (aynı paragraf + satır = tek dikdörtgen). */
    private fun lineRects(): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null; var key = -1L
        for (i in a..b) {
            val w = words[i]; val k = w.block * 100000L + w.line
            if (cur == null || k != key) { cur = RectF(vb[i]); key = k; out.add(cur) } else cur.union(vb[i])
        }
        return out
    }

    private fun notifySel(dragging: Boolean) {
        if (a < 0) { onSelection?.invoke(false, null, false); return }
        val rs = lineRects(); val u = RectF(rs[0]); for (r in rs) u.union(r)
        onSelection?.invoke(true, u, dragging)
    }

    // ---------- dokunma ----------
    private fun nearest(x: Float, y: Float, maxDist: Float = Float.MAX_VALUE): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for (i in vb.indices) {
            val r = vb[i]
            val dx = maxOf(r.left - x, 0f, x - r.right); val dy = maxOf(r.top - y, 0f, y - r.bottom)
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return if (best >= 0 && bd <= maxDist * maxDist) best else -1
    }

    private fun near(px: Float, py: Float, x: Float, y: Float) = (px - x) * (px - x) + (py - y) * (py - y) <= (32 * d) * (32 * d)

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            val i = nearest(e.x, e.y, 22 * d)
            if (i < 0) return
            a = i; b = i; anchor = i; drag = 3
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate(); notifySel(true)
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val i = nearest(e.x, e.y, 22 * d)
            if (i < 0) return false
            val blk = words[i].block
            var s = i; var t = i
            while (s > 0 && words[s - 1].block == blk) s--
            while (t < words.size - 1 && words[t + 1].block == blk) t++
            a = s; b = t; drag = 0
            invalidate(); notifySel(false)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (a >= 0) clear(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = 0
                if (a >= 0) {
                    val rs = lineRects(); val f = rs.first(); val l = rs.last()
                    if (near(e.x, e.y, f.left - hs / 2, f.bottom + hs / 2 + 2 * d)) drag = 1
                    else if (near(e.x, e.y, l.right + hs / 2, l.bottom + hs / 2 + 2 * d)) drag = 2
                    if (drag != 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        notifySel(true); return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag != 0) {
                if (drag == 3) {
                    val i = nearest(e.x, e.y)
                    if (i >= 0) { a = minOf(anchor, i); b = maxOf(anchor, i) }
                } else {
                    // Damla satırın altında durur; parmağın üstündeki kelimeyi hedefle
                    val i = nearest(e.x, e.y - 26 * d)
                    if (i >= 0) {
                        if (drag == 1) { a = i; if (a > b) { val t = a; a = b; b = t; drag = 2 } }
                        else { b = i; if (b < a) { val t = a; a = b; b = t; drag = 1 } }
                    }
                }
                invalidate(); notifySel(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag != 0) {
                val was = drag; drag = 0
                notifySel(false)
                if (was != 3) return true
            }
        }
        return gd.onTouchEvent(e)
    }
}
