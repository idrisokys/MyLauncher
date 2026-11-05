package io.github.idrisokys.myl

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Arayüzsüz: Paylaş menüsünden ("Duvar kağıdı yap") gelen görseli
 * kırpmadan ekrana sığdırıp ana ekran + kilit ekranı duvar kağıdı yapar.
 */
class SetWallpaperActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = extractUri(intent)
        if (uri == null) {
            Toast.makeText(this, "Görsel bulunamadı", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, "Duvar kağıdı ayarlanıyor…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        thread {
            val msg = try {
                if (applyWallpaper(app, uri)) "Ana ekran ve kilit ekranı güncellendi"
                else "Ana ekran güncellendi (bu cihazda kilit ekranı duvar kağıdı yok)"
            } catch (e: UnsupportedOperationException) {
                "Bu cihaz duvar kağıdı değiştirmeyi desteklemiyor"
            } catch (e: Exception) {
                "Duvar kağıdı ayarlanamadı"
            }
            runOnUiThread {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun extractUri(i: Intent?): Uri? {
        if (i == null) return null
        i.data?.let { return it }
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return stream ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    /** true: ana + kilit, false: sadece ana ekran (kilit ekranı desteklenmiyor, ör. Android TV) */
    private fun applyWallpaper(ctx: Context, uri: Uri): Boolean {
        val wm = WallpaperManager.getInstance(ctx)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
            throw UnsupportedOperationException()
        }
        val bmp = fitToScreen(ctx, decode(ctx, uri))
        return try {
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            true
        } catch (e: Exception) {
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
            false
        }
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // setBitmap için gerekli
            val max = 4096
            val w = info.size.width
            val h = info.size.height
            val scale = max.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
    }

    /** Görseli kırpmadan, ekrana tamamen sığacak şekilde ortalar (kalan alan siyah). */
    private fun fitToScreen(ctx: Context, src: Bitmap): Bitmap {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        val a = minOf(dm.widthPixels, dm.heightPixels)
        val b = maxOf(dm.widthPixels, dm.heightPixels)
        val isTv = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        // Telefon: dikey, TV: yatay
        val tw = if (isTv) b else a
        val th = if (isTv) a else b

        val scale = minOf(tw.toFloat() / src.width, th.toFloat() / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val left = (tw - w) / 2
        val top = (th - h) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), paint)
        return out
    }
}
