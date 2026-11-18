package github.idrisokys.myl

import github.idrisokys.myl.common.Notice

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Basit duvar kağıdı kırpıcı: seçilen görsel, cihaz ekranıyla aynı oranlı bir çerçevede
 * parmakla sürüklenip iki parmakla büyütülür; "Ayarla" ana ekran + kilit ekranına uygular.
 */
class CropWallpaperActivity : Activity() {

    private lateinit var cropView: CropView
    private lateinit var setBtn: Button
    private var targetW = 1080
    private var targetH = 1920

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }

        computeTarget()

        val bmp = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // setBitmap için gerekli
                val w = info.size.width
                val h = info.size.height
                val scale = 4096f / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        } catch (e: Exception) {
            Notice.showThenFinish(this, "Görsel açılamadı", true)
            return
        }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        cropView = CropView(this, bmp, targetW.toFloat() / targetH, dp(88))

        val hint = TextView(this).apply {
            text = "Sürükle ve yakınlaştır"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val cancel = Button(this).apply { text = "İptal"; setOnClickListener { finish() } }
        setBtn = Button(this).apply { text = "Ayarla"; setOnClickListener { apply() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(12))
            addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(setBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(cropView, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(8) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    /** Telefon: dikey, TV: yatay; çıktı boyutu = gerçek ekran boyutu. */
    private fun computeTarget() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        val a = minOf(dm.widthPixels, dm.heightPixels)
        val b = maxOf(dm.widthPixels, dm.heightPixels)
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        targetW = if (isTv) b else a
        targetH = if (isTv) a else b
    }

    private fun apply() {
        setBtn.isEnabled = false
        val app = applicationContext
        val out = cropView.render(targetW, targetH)
        thread {
            val msg = try {
                val wm = WallpaperManager.getInstance(app)
                if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
                    "Bu cihaz duvar kağıdı değiştirmeyi desteklemiyor"
                } else {
                    try {
                        wm.setBitmap(out, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                        "Ana ekran ve kilit ekranı güncellendi"
                    } catch (e: Exception) {
                        wm.setBitmap(out, null, true, WallpaperManager.FLAG_SYSTEM)
                        "Ana ekran güncellendi"
                    }
                }
            } catch (e: Exception) {
                "Duvar kağıdı ayarlanamadı"
            }
            runOnUiThread {
                Notice.showThenFinish(this, msg, true)
            }
        }
    }

    private class CropView(
        ctx: Context,
        private val src: Bitmap,
        private val ratio: Float,   // çerçeve genişlik / yükseklik
        private val bottomReserve: Int
    ) : View(ctx) {

        private val m = Matrix()
        private val frame = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dim = Paint().apply { color = 0xAA000000.toInt() }
        private val border = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private var minScale = 1f
        private val vals = FloatArray(9)

        private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 8f)
                val f = target / cur
                m.postScale(f, f, d.focusX, d.focusY)
                clamp(); invalidate()
                return true
            }
        })
        private val moveDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaleDetector.isInProgress) return false
                m.postTranslate(-dx, -dy)
                clamp(); invalidate()
                return true
            }
        })

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val margin = 24f * resources.displayMetrics.density
            val availW = w - 2 * margin
            val availH = h - bottomReserve - 2 * margin
            var fw = availW
            var fh = fw / ratio
            if (fh > availH) { fh = availH; fw = fh * ratio }
            val l = (w - fw) / 2f
            val t = margin + (availH - fh) / 2f
            frame.set(l, t, l + fw, t + fh)

            minScale = maxOf(frame.width() / src.width, frame.height() / src.height)
            m.reset()
            m.postScale(minScale, minScale)
            m.postTranslate(
                frame.centerX() - src.width * minScale / 2f,
                frame.centerY() - src.height * minScale / 2f
            )
            clamp()
        }

        private fun currentScale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

        /** Görsel her zaman çerçeveyi tamamen kaplasın. */
        private fun clamp() {
            val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            m.mapRect(r)
            var dx = 0f
            var dy = 0f
            if (r.left > frame.left) dx = frame.left - r.left
            else if (r.right < frame.right) dx = frame.right - r.right
            if (r.top > frame.top) dy = frame.top - r.top
            else if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom
            m.postTranslate(dx, dy)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(e)
            moveDetector.onTouchEvent(e)
            return true
        }

        override fun onDraw(c: Canvas) {
            c.drawBitmap(src, m, paint)
            // çerçeve dışını karart
            c.drawRect(0f, 0f, width.toFloat(), frame.top, dim)
            c.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dim)
            c.drawRect(0f, frame.top, frame.left, frame.bottom, dim)
            c.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dim)
            c.drawRect(frame, border)
        }

        /** Çerçevenin içindeki bölgeyi tam ekran boyutunda bitmap olarak üretir. */
        fun render(tw: Int, th: Int): Bitmap {
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val k = tw / frame.width()
            c.scale(k, k)
            c.translate(-frame.left, -frame.top)
            c.drawBitmap(src, m, paint)
            return out
        }
    }
}
