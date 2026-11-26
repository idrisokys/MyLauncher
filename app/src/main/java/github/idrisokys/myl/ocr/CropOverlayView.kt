package github.idrisokys.myl.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ekran görüntüsünün üstünde sürüklenebilir bir seçim dikdörtgeni çizer.
 * - Köşelere / kenarlara dokunup sürükle → boyutlandır
 * - Dikdörtgenin içinden sürükle → taşı
 * - Dışarıdan sürükle → yeni dikdörtgen çiz
 * [bounds]: görüntünün ekrandaki alanı; seçim bunun dışına çıkamaz. Sonuç [rect] (görünüm koordinatları).
 */
class CropOverlayView(ctx: Context, private val bounds: RectF) : View(ctx) {
    val rect = RectF()

    private val d = resources.displayMetrics.density
    private val minSize = 56 * d
    private val touch = 28 * d

    private val pDim = Paint().apply { color = 0x99000000.toInt() }
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEC4E6A.toInt() }

    private var dl = false; private var dt = false; private var dr = false; private var db = false
    private var moving = false; private var creating = false
    private var lx = 0f; private var ly = 0f      // son dokunma noktası
    private var ax = 0f; private var ay = 0f      // yeni çizimde başlangıç noktası

    init {
        val w = bounds.width(); val h = bounds.height()
        rect.set(bounds.left + w * 0.15f, bounds.top + h * 0.25f, bounds.right - w * 0.15f, bounds.bottom - h * 0.25f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Seçimin dışını karart
        c.drawRect(0f, 0f, w, rect.top, pDim)
        c.drawRect(0f, rect.bottom, w, h, pDim)
        c.drawRect(0f, rect.top, rect.left, rect.bottom, pDim)
        c.drawRect(rect.right, rect.top, w, rect.bottom, pDim)
        // Üçe bölme çizgileri
        val tx = rect.width() / 3f; val ty = rect.height() / 3f
        for (i in 1..2) {
            c.drawLine(rect.left + tx * i, rect.top, rect.left + tx * i, rect.bottom, pGrid)
            c.drawLine(rect.left, rect.top + ty * i, rect.right, rect.top + ty * i, pGrid)
        }
        c.drawRect(rect, pBorder)
        // Köşe tutamaçları
        val r = 7 * d
        c.drawCircle(rect.left, rect.top, r, pHandle)
        c.drawCircle(rect.right, rect.top, r, pHandle)
        c.drawCircle(rect.left, rect.bottom, r, pHandle)
        c.drawCircle(rect.right, rect.bottom, r, pHandle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(bounds.left, bounds.right)
        val y = e.y.coerceIn(bounds.top, bounds.bottom)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dl = false; dt = false; dr = false; db = false; moving = false; creating = false
                val inY = e.y >= rect.top - touch && e.y <= rect.bottom + touch
                val inX = e.x >= rect.left - touch && e.x <= rect.right + touch
                if (inY && abs(e.x - rect.left) <= touch) dl = true
                else if (inY && abs(e.x - rect.right) <= touch) dr = true
                if (inX && abs(e.y - rect.top) <= touch) dt = true
                else if (inX && abs(e.y - rect.bottom) <= touch) db = true
                if (!(dl || dt || dr || db)) {
                    if (rect.contains(e.x, e.y)) moving = true
                    else { creating = true; ax = x; ay = y; rect.set(x, y, x, y) }
                }
                lx = x; ly = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lx; val dy = y - ly
                when {
                    creating -> rect.set(min(ax, x), min(ay, y), max(ax, x), max(ay, y))
                    moving -> {
                        val l = (rect.left + dx).coerceIn(bounds.left, bounds.right - rect.width())
                        val t = (rect.top + dy).coerceIn(bounds.top, bounds.bottom - rect.height())
                        rect.offsetTo(l, t)
                    }
                    else -> {
                        if (dl) rect.left = (rect.left + dx).coerceIn(bounds.left, rect.right - minSize)
                        if (dr) rect.right = (rect.right + dx).coerceIn(rect.left + minSize, bounds.right)
                        if (dt) rect.top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - minSize)
                        if (db) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, bounds.bottom)
                    }
                }
                lx = x; ly = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (creating) {
                    // Çok küçük çizildiyse en az boyuta büyüt (sınırlar içinde kalarak)
                    if (rect.width() < minSize) { rect.right = min(bounds.right, rect.left + minSize); rect.left = max(bounds.left, rect.right - minSize) }
                    if (rect.height() < minSize) { rect.bottom = min(bounds.bottom, rect.top + minSize); rect.top = max(bounds.top, rect.bottom - minSize) }
                }
                creating = false; moving = false; dl = false; dt = false; dr = false; db = false
                invalidate()
            }
        }
        return true
    }
}
