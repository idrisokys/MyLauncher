package github.idrisokys.myl

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rust ile yazılmış yerel kütüphane köprüsü (`native/` klasörü → libmylnative.so).
 *
 * Her sarmalayıcı, kütüphane yüklenemediğinde ya da yerel kod hata verdiğinde `null` döner;
 * çağıran taraf bu durumda eski Kotlin koduyla devam eder. Böylece uygulama .so olmadan da çalışır.
 */
object Native {
    /** Kütüphane yüklendi ve çalışıyor mu? */
    val ok: Boolean by lazy {
        try { System.loadLibrary("mylnative"); nVersion() > 0 } catch (t: Throwable) { false }
    }

    @JvmStatic private external fun nVersion(): Int
    @JvmStatic private external fun nScanMedia(root: String, skip: String, exts: Array<String>): Array<String>?
    @JvmStatic private external fun nListDirMeta(dir: String): Array<String>?
    @JvmStatic private external fun nWalkNotes(root: String, dl: Boolean, maxDepth: Int): Array<String>?
    @JvmStatic private external fun nSearchNotes(paths: Array<String>, query: String): Array<String>?
    @JvmStatic private external fun nUnzip(zip: String, dest: String): Int
    @JvmStatic private external fun nZipPaths(sources: Array<String>, dest: String): Int
    @JvmStatic private external fun nHashFile(path: String, algo: String): Array<String>?
    @JvmStatic private external fun nHashFiles(paths: Array<String>, algo: String): Array<String>?
    @JvmStatic private external fun nDecodeImage(path: String, maxDim: Int, fit: Boolean): ByteArray?
    @JvmStatic private external fun nConvertImages(srcs: Array<String>, destDir: String, format: String, quality: Int, maxDim: Int): Array<String>?

    private inline fun <T> safe(block: () -> T?): T? =
        if (!ok) null else try { block() } catch (t: Throwable) { null }

    // ------------------------------------------------------------ tarama / meta veri

    /** Depolamadaki resim/video dosyaları (en yeni başta). [skip] klasörü atlanır, "." ile başlayanlar atlanır. */
    fun scanMedia(root: File, skip: File, exts: Set<String>): List<File>? = safe {
        nScanMedia(root.path, skip.path, exts.toTypedArray())?.map { File(it) }
    }

    class Meta(val name: String, val isDir: Boolean, val size: Long, val mtime: Long, val children: Int)

    /** Klasör içeriğinin boyut / tarih / alt öğe sayısı (ada göre). Okunamazsa null. */
    fun listDirMeta(dir: File): Map<String, Meta>? = safe {
        val a = nListDirMeta(dir.path) ?: return@safe null
        val out = LinkedHashMap<String, Meta>(a.size / 5 + 1)
        var i = 0
        while (i + 4 < a.size) {
            out[a[i]] = Meta(a[i], a[i + 1] == "1", a[i + 2].toLongOrNull() ?: 0L, a[i + 3].toLongOrNull() ?: 0L, a[i + 4].toIntOrNull() ?: 0)
            i += 5
        }
        out
    }

    /** NoteSync yerel ağacı: 4'lü gruplar [göreli yol, "D"/"F", boyut, tarih]. */
    fun walkNotes(root: File, dl: Boolean, maxDepth: Int): Array<String>? = safe { nWalkNotes(root.path, dl, maxDepth) }

    // ------------------------------------------------------------ arama

    class NoteHit(val idx: Int, val titleMatch: Boolean, val isNote: Boolean, val detail: String)

    /** [paths] dosyalarını paralel okuyup sorguyla eşleştirir (Türkçe/aksan duyarsız, her kelime bulunmalı). */
    fun matchNotes(paths: List<String>, query: String): List<NoteHit>? = safe {
        val a = nSearchNotes(paths.toTypedArray(), query) ?: return@safe null
        val out = ArrayList<NoteHit>(a.size / 4)
        var i = 0
        while (i + 3 < a.size) {
            out.add(NoteHit(a[i].toIntOrNull() ?: 0, a[i + 1] == "1", a[i + 2] == "1", a[i + 3]))
            i += 4
        }
        out
    }

    // ------------------------------------------------------------ arşiv

    /** ZIP'i [dest] klasörüne açar (zip-slip korumalı). Açılan giriş sayısı; başarısızsa -1; kütüphane yoksa null. */
    fun unzip(zip: File, dest: File): Int? = safe { nUnzip(zip.path, dest.path) }

    /** [sources] (dosya/klasör) öğelerini ZIP'e yazar. Eklenen dosya sayısı; başarısızsa -1; kütüphane yoksa null. */
    fun zip(sources: List<File>, dest: File): Int? = safe { nZipPaths(sources.map { it.path }.toTypedArray(), dest.path) }

    // ------------------------------------------------------------ dosya analizi

    /** Dosya özeti (hex). [algo]: "sha256", "md5" ya da "crc32". Hata/yok: null. */
    fun hashFile(f: File, algo: String = "sha256"): String? = safe { nHashFile(f.path, algo)?.firstOrNull() }

    /** Birden çok dosyanın özeti (paralel); okunamayanlar için null. */
    fun hashFiles(files: List<File>, algo: String = "sha256"): List<String?>? = safe {
        nHashFiles(files.map { it.path }.toTypedArray(), algo)?.map { it.ifEmpty { null } }
    }

    // ------------------------------------------------------------ görsel işleme

    /**
     * Resmi örnekleyerek çözer ve EXIF yönünü uygular. [fit]=true: en büyük kenar [maxDim]'i geçmez (tam ekran);
     * false: en küçük kenar >= [maxDim] (küçük resim). jpg/png/webp/gif/bmp desteklenir; diğerlerinde null döner.
     */
    fun decodeImage(path: String, maxDim: Int, fit: Boolean): Bitmap? = safe {
        val b = nDecodeImage(path, maxDim, fit) ?: return@safe null
        if (b.size < 8) return@safe null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0); val h = bb.getInt(4)
        if (w <= 0 || h <= 0 || b.size.toLong() != 8L + w.toLong() * h.toLong() * 4L) return@safe null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bb.position(8)
        bmp.copyPixelsFromBuffer(bb)
        bmp
    }

    /**
     * Toplu dönüştürme. [format]: "jpg" | "png" | "webp" (kayıpsız). [maxDim] > 0 ise en büyük kenar buna sığdırılır.
     * Her giriş için oluşan dosyanın yolu; başarısızsa null.
     */
    fun convertImages(srcs: List<File>, destDir: File, format: String, quality: Int = 90, maxDim: Int = 0): List<String?>? = safe {
        nConvertImages(srcs.map { it.path }.toTypedArray(), destDir.path, format, quality, maxDim)?.map { it.ifEmpty { null } }
    }
}
