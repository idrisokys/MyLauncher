package github.idrisokys.myl.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Okunan kelimeler ve ekran görüntüsü, TextSelectActivity'ye aynı süreç içinde bu nesneyle aktarılır. */
object OcrHolder {
    @Volatile var bmp: Bitmap? = null
    @Volatile var words: List<OcrEngine.W> = emptyList()
    fun clear() { bmp = null; words = emptyList() }
}

/** Cihaz üzerinde (internetsiz) metin tanıma. Türkçe Latin alfabesidir; ş ğ ı İ ö ü ç tanınır. */
object OcrEngine {
    /** [block]: paragraf, [line]: paragraf içindeki satır no. Kopyalarken aynı paragraf boşlukla, farklı paragraf alt satırla birleşir. */
    class W(val text: String, val box: RectF, val block: Int, val line: Int)

    fun run(bmp: Bitmap, done: (List<W>) -> Unit) {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val out = ArrayList<W>()
                t.textBlocks.forEachIndexed { bi, b ->
                    b.lines.forEachIndexed { li, l ->
                        val els = l.elements
                        if (els.isEmpty()) {
                            l.boundingBox?.let { out.add(W(l.text, RectF(it), bi, li)) }
                        } else for (e in els) {
                            e.boundingBox?.let { out.add(W(e.text, RectF(it), bi, li)) }
                        }
                    }
                }
                rec.close(); done(out)
            }
            .addOnFailureListener { rec.close(); done(emptyList()) }
    }
}
