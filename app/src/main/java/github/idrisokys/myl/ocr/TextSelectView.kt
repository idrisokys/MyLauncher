package github.idrisokys.myl.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import github.idrisokys.myl.L
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ekran görüntüsünü çizer ve TÜM sayfada, paragraf sınırı olmadan, karakter karakter serbest seçim sağlar.
 *  - Bir kelimeye basılı tut → kelime seçilir; parmağı sürüklemeye devam edersen seçim kelime kelime genişler.
 *  - İki ucundaki damlaları sürükle → karakter hassasiyetinde, sayfanın herhangi bir yerine kadar genişlet.
 *  - Çift dokun → paragrafı seç; boş yere dokun → seçimi kaldır.
 *  - Üstte, Android'in kendi yüzen paneli çıkar: Kopyala / Paylaş / Google / Tümünü seç.
 * OCR yalnızca kelime kutuları verdiği için, kelime içindeki karakter konumları kutunun genişliği
 * karakter genişliklerine orantılanarak hesaplanır.
 */
class TextSelectView(ctx: Context) : View(ctx) {
    companion object {
        const val ACT_COPY = 1
        const val ACT_SHARE = 2
        const val ACT_GOOGLE = 3
        private const val ACT_ALL = 4
    }

    /** Panelden Kopyala / Paylaş / Google seçilince (işlem kimliği, seçili metin). */
    var onAction: ((Int, String) -> Unit)? = null
    /** Seçim oluştu / kalktı. */
    var onSelState: ((Boolean) -> Unit)? = null

    /** Bir OCR satırı: metin, görüntüdeki kutusu ve her imlecin (0..uzunluk) görüntü x konumu. */
    private class Ln(val text: String, val box: RectF, val cx: FloatArray, val block: Int)

    private var bmp: Bitmap? = null
    private var lines: List<Ln> = emptyList()
    private var sc = 1f; private var ox = 0f; private var oy = 0f

    // Konum = (satır no shl 20) or imleç no. Seçim [ss, se) ; ss < se. -1 = seçim yok.
    private var ss = -1L; private var se = -1L
    private var drag = 0                 // 0 yok, 1 baş damla, 2 son damla, 3 basılı tutup genişletme
    private var aS = 0L; private var aE = 0L   // genişletmede başlangıç kelimesi
    private var offX = 0f; private var offY = 0f
    private var mode: ActionMode? = null
    private var mag: Magnifier? = null
    private var lastHas = false

    private val d = resources.displayMetrics.density
    private val hs = 22 * d
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66EC4E6A }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8CE01F.toInt() }

    val hasSelection get() = ss >= 0

    private fun pos(line: Int, ch: Int) = (line.toLong() shl 20) or ch.toLong()
    private fun pl(p: Long) = (p shr 20).toInt()
    private fun pc(p: Long) = (p and 0xFFFFF).toInt()

    // ---------- veri ----------
    fun set(bitmap: Bitmap, words: List<OcrEngine.W>) {
        bmp = bitmap
        val probe = Paint().apply { textSize = 100f }
        val groups = LinkedHashMap<Long, ArrayList<OcrEngine.W>>()
        for (w in words) if (w.text.isNotBlank()) groups.getOrPut(w.block * 100000L + w.line) { ArrayList() }.add(w)
        lines = readingOrder(groups.values.map { buildLine(it, probe) })
        relayout(); invalidate()
    }

    private fun buildLine(ws0: List<OcrEngine.W>, probe: Paint): Ln {
        val ws = ws0.sortedBy { it.box.left }
        val sb = StringBuilder(); val offs = IntArray(ws.size)
        val box = RectF(ws[0].box)
        ws.forEachIndexed { i, w ->
            if (i > 0) sb.append(' ')
            offs[i] = sb.length; sb.append(w.text.trim()); box.union(w.box)
        }
        val cx = FloatArray(sb.length + 1)
        ws.forEachIndexed { i, w ->
            val t = w.text.trim(); val m = t.length; val tot = probe.measureText(t)
            val l = w.box.left; val r = w.box.right
            for (k in 0..m) {
                val f = if (tot > 0f) probe.measureText(t, 0, k) / tot else k.toFloat() / m
                cx[offs[i] + k] = l + (r - l) * f
            }
        }
        for (i in 1 until cx.size) if (cx[i] < cx[i - 1]) cx[i] = cx[i - 1]
        return Ln(sb.toString(), box, cx, ws[0].block)
    }

    /**
     * ML Kit blokları görsel sırada vermez (tablo hücreleri, yan paneller, başlıklar karışır); seçim satır numarasına göre
     * çalıştığı için seçim "zıplar". Satırları XY-kesim ile okuma sırasına dizeriz: önce geniş boşluklardan
     * sütunlara / satırlara böl, sonra yukarıdan aşağı, soldan sağa.
     */
    private fun readingOrder(src: List<Ln>): List<Ln> {
        if (src.size < 2) return src
        val mh = src.map { it.box.height() }.sorted()[src.size / 2].coerceAtLeast(1f)
        val out = ArrayList<Ln>(src.size)

        // (en büyük boşluk, bölme indeksi, eksende sıralı liste)
        fun gap(g: List<Ln>, vertical: Boolean): Triple<Float, Int, List<Ln>> {
            val s = if (vertical) g.sortedBy { it.box.left } else g.sortedBy { it.box.top }
            var maxEnd = if (vertical) s[0].box.right else s[0].box.bottom
            var best = 0f; var at = -1
            for (i in 1 until s.size) {
                val st = if (vertical) s[i].box.left else s[i].box.top
                if (st - maxEnd > best) { best = st - maxEnd; at = i }
                val en = if (vertical) s[i].box.right else s[i].box.bottom
                if (en > maxEnd) maxEnd = en
            }
            return Triple(best, at, s)
        }

        fun rec(g: List<Ln>) {
            if (g.size <= 1) { out.addAll(g); return }
            val h = gap(g, false); val v = gap(g, true)
            val useV = (v.first >= mh * 0.5f && v.first >= h.first && v.second > 0) || (h.second <= 0 && v.second > 0)
            when {
                useV -> { rec(v.third.subList(0, v.second)); rec(v.third.subList(v.second, v.third.size)) }
                h.second > 0 -> { rec(h.third.subList(0, h.second)); rec(h.third.subList(h.second, h.third.size)) }
                else -> out.addAll(mergeRows(g, mh))
            }
        }
        rec(src)
        return out
    }

    /** Aynı görsel satırdaki parçaları (satır içi kod kutusu, ayrı okunan kelime grupları) tek satırda birleştirir. */
    private fun mergeRows(g: List<Ln>, mh: Float): List<Ln> {
        val rows = ArrayList<ArrayList<Ln>>(); val rb = ArrayList<RectF>()
        for (l in g.sortedBy { it.box.centerY() }) {
            val last = rb.lastOrNull()
            val ov = if (last == null) -1f else minOf(l.box.bottom, last.bottom) - maxOf(l.box.top, last.top)
            if (last != null && ov >= 0.5f * minOf(l.box.height(), last.height())) {
                rows.last().add(l); last.union(l.box)
            } else { rows.add(arrayListOf(l)); rb.add(RectF(l.box)) }
        }
        val res = ArrayList<Ln>()
        for (r in rows) {
            val s = r.sortedBy { it.box.left }
            var cur = s[0]
            for (i in 1 until s.size) {
                val n = s[i]
                if (n.box.left - cur.box.right <= 2f * mh) cur = joinLn(cur, n)
                else { res.add(cur); cur = n }
            }
            res.add(cur)
        }
        return res
    }

    private fun joinLn(a: Ln, b: Ln): Ln {
        val box = RectF(a.box); box.union(b.box)
        val cx = FloatArray(a.cx.size + b.cx.size)
        System.arraycopy(a.cx, 0, cx, 0, a.cx.size)
        System.arraycopy(b.cx, 0, cx, a.cx.size, b.cx.size)
        for (i in 1 until cx.size) if (cx[i] < cx[i - 1]) cx[i] = cx[i - 1]
        return Ln(a.text + " " + b.text, box, cx, if (b.text.length > a.text.length) b.block else a.block)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    private fun relayout() {
        val bm = bmp ?: return
        if (width == 0 || height == 0) return
        sc = minOf(width.toFloat() / bm.width, height.toFloat() / bm.height)
        ox = (width - bm.width * sc) / 2f; oy = (height - bm.height * sc) / 2f
    }

    // görüntü → ekran
    private fun vx(l: Ln, c: Int) = ox + sc * l.cx[c]
    private fun vTop(l: Ln) = oy + sc * l.box.top
    private fun vBot(l: Ln) = oy + sc * l.box.bottom

    // ---------- seçim işlemleri ----------
    fun clear() {
        ss = -1L; se = -1L; drag = 0
        mode?.finish(); mode = null
        magHide()
        invalidate(); lastHas = false; onSelState?.invoke(false)
    }

    fun selectAll() {
        if (lines.isEmpty()) return
        ss = pos(0, 0); se = pos(lines.size - 1, lines.last().text.length)
        changed()
    }

    fun selectedText(): String {
        if (ss < 0) return ""
        val sb = StringBuilder(); var prevBlock = -1
        for (i in pl(ss)..pl(se)) {
            val l = lines[i]
            val a = if (i == pl(ss)) pc(ss) else 0
            val b = if (i == pl(se)) pc(se) else l.text.length
            if (a >= b) continue
            if (sb.isNotEmpty()) sb.append(if (l.block != prevBlock) '\n' else ' ')
            sb.append(l.text, a, b); prevBlock = l.block
        }
        return sb.toString().trim()
    }

    private fun changed() {
        invalidate()
        val has = ss >= 0
        if (has != lastHas) { lastHas = has; onSelState?.invoke(has) }
        if (drag != 0) mode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
        else if (ss >= 0) { ensureMode(); mode?.invalidateContentRect(); mode?.hide(0L) }
    }

    // ---------- konum bulma ----------
    /** (en yakın satır, satıra gerçek uzaklık px). Dikey yakınlık öncelikli: sütunlar arasında karışmasın. */
    private fun nearestLine(x: Float, y: Float): Pair<Int, Float> {
        var best = -1; var bd = Float.MAX_VALUE; var real = 0f
        for (i in lines.indices) {
            val l = lines[i]
            val dy = maxOf(vTop(l) - y, 0f, y - vBot(l))
            val dx = maxOf(vx(l, 0) - x, 0f, x - vx(l, l.text.length))
            val dd = dy * dy * 4f + dx * dx
            if (dd < bd) { bd = dd; best = i; real = sqrt(dy * dy + dx * dx) }
        }
        return Pair(best, real)
    }

    /** Ekrandaki (x, y) noktasına en yakın imleç konumu; sayfanın her yerinden çalışır. */
    private fun posAt(x: Float, y: Float): Long {
        val li = nearestLine(x, y).first
        if (li < 0) return -1L
        val l = lines[li]
        var bc = 0; var bd = Float.MAX_VALUE
        for (i in l.cx.indices) { val dd = abs(vx(l, i) - x); if (dd < bd) { bd = dd; bc = i } }
        return pos(li, bc)
    }

    /** x'in altındaki karakterin indeksi. */
    private fun charAt(li: Int, x: Float): Int {
        val l = lines[li]; var ci = 0
        for (i in 0 until l.text.length) if (vx(l, i) <= x) ci = i
        return ci
    }

    /** Karakterin içinde bulunduğu kelimenin [başlangıç, bitiş) konumları. */
    private fun wordAt(li: Int, ci0: Int): LongArray? {
        val t = lines[li].text
        var c = ci0.coerceIn(0, t.length - 1)
        if (t[c] == ' ') {
            c = if (c > 0 && t[c - 1] != ' ') c - 1 else if (c + 1 < t.length && t[c + 1] != ' ') c + 1 else return null
        }
        var a = c; var b = c + 1
        while (a > 0 && t[a - 1] != ' ') a--
        while (b < t.length && t[b] != ' ') b++
        return longArrayOf(pos(li, a), pos(li, b))
    }

    private fun selUnion(): RectF {
        val u = RectF(); var first = true
        for (i in pl(ss)..pl(se)) {
            val l = lines[i]
            val a = if (i == pl(ss)) pc(ss) else 0
            val b = if (i == pl(se)) pc(se) else l.text.length
            if (a >= b) continue
            val r = RectF(vx(l, a), vTop(l), vx(l, b), vBot(l))
            if (first) { u.set(r); first = false } else u.union(r)
        }
        return u
    }

    // ---------- Android'in yüzen paneli ----------
    private val cb = object : ActionMode.Callback2() {
        override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean {
            menu.add(0, ACT_COPY, 0, context.getString(android.R.string.copy)).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ACT_SHARE, 1, L("Paylaş", "Share")).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ACT_GOOGLE, 2, "Google").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ACT_ALL, 3, context.getString(android.R.string.selectAll)).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }
        override fun onPrepareActionMode(m: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ACT_COPY, ACT_SHARE, ACT_GOOGLE -> { val t = selectedText(); m.finish(); onAction?.invoke(item.itemId, t); return true }
                ACT_ALL -> { selectAll(); return true }
            }
            return false
        }
        override fun onDestroyActionMode(m: ActionMode) { if (mode === m) mode = null }
        override fun onGetContentRect(m: ActionMode, v: View, out: Rect) {
            if (ss < 0) { out.set(0, 0, width, 1); return }
            val u = selUnion()
            // Seçim ekranın büyük kısmını kaplıyorsa panel en üste atlamasın: seçimin başladığı satırın üstünde dursun.
            if (u.height() > height * 0.35f) {
                val ls = lines[pl(ss)]
                u.set(vx(ls, pc(ss)), vTop(ls), maxOf(vx(ls, ls.text.length), vx(ls, pc(ss)) + 1f), vBot(ls))
            }
            out.set(u.left.toInt(), u.top.toInt(), u.right.toInt(), u.bottom.toInt())
            if (!out.intersect(0, 0, width, height)) out.set(0, 0, width, 1)
        }
    }

    private fun ensureMode() {
        if (mode != null || ss < 0) return
        mode = startActionMode(cb, ActionMode.TYPE_FLOATING)
    }

    override fun onDetachedFromWindow() { mode?.finish(); mode = null; magHide(); super.onDetachedFromWindow() }

    // ---------- büyüteç ve damla konumu ----------
    private fun magAt(p: Long) {
        if (p < 0 || pl(p) >= lines.size) return
        val l = lines[pl(p)]
        try {
            val m = mag ?: Magnifier(this).also { mag = it }
            m.show(vx(l, pc(p)).coerceIn(0f, width.toFloat()), ((vTop(l) + vBot(l)) / 2f).coerceIn(0f, height.toFloat()))
        } catch (e: Throwable) { }
    }

    private fun magHide() { try { mag?.dismiss() } catch (e: Throwable) { } }

    /** Damla, satırın hemen altında; satır ekranın dibindeyse damla ekranın içinde kalır. */
    private fun handleY(l: Ln) = minOf(vBot(l) + 2 * d, height - hs - 2 * d).coerceAtLeast(0f)

    // ---------- çizim ----------
    override fun onDraw(c: Canvas) {
        val bm = bmp ?: return
        c.save(); c.translate(ox, oy); c.scale(sc, sc); c.drawBitmap(bm, 0f, 0f, pImg); c.restore()
        if (ss < 0) return
        for (i in pl(ss)..pl(se)) {
            val l = lines[i]
            val a = if (i == pl(ss)) pc(ss) else 0
            val b = if (i == pl(se)) pc(se) else l.text.length
            if (a >= b) continue
            val r = RectF(vx(l, a), vTop(l), vx(l, b), vBot(l)); r.inset(-2 * d, -2 * d)
            c.drawRoundRect(r, 3 * d, 3 * d, pHi)
        }
        val ls = lines[pl(ss)]; val le = lines[pl(se)]
        handle(c, vx(ls, pc(ss)), handleY(ls), true)
        handle(c, vx(le, pc(se)), handleY(le), false)
    }

    private fun handle(c: Canvas, x: Float, y: Float, start: Boolean) {
        val r = hs / 2f
        val p = Path()
        if (start) p.addRoundRect(RectF(x - hs, y, x, y + hs), floatArrayOf(r, r, 0f, 0f, r, r, r, r), Path.Direction.CW)
        else p.addRoundRect(RectF(x, y, x + hs, y + hs), floatArrayOf(0f, 0f, r, r, r, r, r, r), Path.Direction.CW)
        c.drawPath(p, pHandle)
    }

    // ---------- dokunma ----------
    private fun near(px: Float, py: Float, x: Float, y: Float) = (px - x) * (px - x) + (py - y) * (py - y) <= (32 * d) * (32 * d)

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(ev: MotionEvent) = true

        override fun onLongPress(ev: MotionEvent) {
            val (li, dist) = nearestLine(ev.x, ev.y)
            if (li < 0 || dist > 24 * d) return
            val w = wordAt(li, charAt(li, ev.x)) ?: return
            aS = w[0]; aE = w[1]; ss = aS; se = aE; drag = 3
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            parent?.requestDisallowInterceptTouchEvent(true)
            changed()
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            val (li, dist) = nearestLine(ev.x, ev.y)
            if (li < 0 || dist > 24 * d) return false
            val blk = lines[li].block
            var a = li; var b = li
            while (a > 0 && lines[a - 1].block == blk) a--
            while (b < lines.size - 1 && lines[b + 1].block == blk) b++
            ss = pos(a, 0); se = pos(b, lines[b].text.length); drag = 0
            changed()
            return true
        }

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean { if (ss >= 0) clear(); return true }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = 0
                if (ss >= 0) {
                    val ls = lines[pl(ss)]; val le = lines[pl(se)]
                    val sx = vx(ls, pc(ss)); val sy = handleY(ls)
                    val ex = vx(le, pc(se)); val ey = handleY(le)
                    val ds = (ev.x - (sx - hs / 2)).let { it * it } + (ev.y - (sy + hs / 2)).let { it * it }
                    val de = (ev.x - (ex + hs / 2)).let { it * it } + (ev.y - (ey + hs / 2)).let { it * it }
                    val tryStart = near(ev.x, ev.y, sx - hs / 2, sy + hs / 2)
                    val tryEnd = near(ev.x, ev.y, ex + hs / 2, ey + hs / 2)
                    if (tryStart && (!tryEnd || ds <= de)) { drag = 1; offX = ev.x - sx; offY = ev.y - (vTop(ls) + vBot(ls)) / 2 }
                    else if (tryEnd) { drag = 2; offX = ev.x - ex; offY = ev.y - (vTop(le) + vBot(le)) / 2 }
                    if (drag != 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        mode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag != 0) {
                val os = ss; val oe = se
                if (drag == 3) {
                    // Basılı tutup sürükle: başlangıç kelimesi ile parmağın altındaki kelime arası (kelime kelime)
                    val li = nearestLine(ev.x, ev.y).first
                    if (li >= 0) {
                        val w = wordAt(li, charAt(li, ev.x))
                        val ws = w?.get(0) ?: posAt(ev.x, ev.y)
                        val we = w?.get(1) ?: ws
                        ss = minOf(aS, ws); se = maxOf(aE, we)
                    }
                } else {
                    // Damlayı karakter hassasiyetinde, sayfanın herhangi bir yerine sürükle
                    val p = posAt(ev.x - offX, ev.y - offY)
                    if (p >= 0) {
                        if (drag == 1) {
                            if (p < se) ss = p else if (p > se) { ss = se; se = p; drag = 2 }
                        } else {
                            if (p > ss) se = p else if (p < ss) { se = ss; ss = p; drag = 1 }
                        }
                    }
                }
                if (ss != os || se != oe) {
                    performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                    changed()
                }
                magAt(if (drag == 1) ss else if (drag == 2) se else posAt(ev.x, ev.y))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag != 0) {
                val was = drag; drag = 0
                magHide()
                changed()
                if (was != 3) return true
            }
        }
        return gd.onTouchEvent(ev)
    }
}
