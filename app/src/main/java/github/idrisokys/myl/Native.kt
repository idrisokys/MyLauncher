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
    @JvmStatic private external fun nWalkForSearch(root: String, query: String): Array<String>?
    @JvmStatic private external fun nSearchNotes(paths: Array<String>, query: String): Array<String>?
    @JvmStatic private external fun nUnzip(zip: String, dest: String): Int
    @JvmStatic private external fun nZipPaths(sources: Array<String>, dest: String): Int
    @JvmStatic private external fun nHashFile(path: String, algo: String): Array<String>?
    @JvmStatic private external fun nHashFiles(paths: Array<String>, algo: String): Array<String>?
    @JvmStatic private external fun nDecodeImage(path: String, maxDim: Int, fit: Boolean): ByteArray?
    @JvmStatic private external fun nThumbGet(path: String, cacheDir: String, tag: Int): ByteArray?
    @JvmStatic private external fun nThumbPut(path: String, cacheDir: String, tag: Int, w: Int, h: Int, rgba: ByteArray): Boolean
    @JvmStatic private external fun nThumbTrim(cacheDir: String, maxBytes: Long): Long
    @JvmStatic private external fun nConvertImages(srcs: Array<String>, destDir: String, format: String, quality: Int, maxDim: Int): Array<String>?
    @JvmStatic private external fun nSetLang(english: Boolean)
    @JvmStatic private external fun nServerStart(recvDir: String, linkRoot: String?, dlRoot: String?, trash: String?, port: Int): Long
    @JvmStatic private external fun nServerPoll(id: Long): Array<String>?
    @JvmStatic private external fun nServerStop(id: Long)
    @JvmStatic private external fun nSend(addr: String, name: String, fd: Int, len: Long): Int
    @JvmStatic private external fun nMdnsBrowse(token: Long, service: String, local: String?, skip: String?, timeoutMs: Int): Array<String>?
    @JvmStatic private external fun nMdnsCancel(token: Long)
    @JvmStatic private external fun nSync(mode: Int, root: String, download: Boolean, trash: String?, peers: Array<String>): Array<String>?

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

    /** Arama yürüyüşünde bir öğe: [rel] kök klasöre göre "/" ile ayrılmış yol. */
    class Walked(val rel: String, val isDir: Boolean, val pinned: Boolean, val nameHit: Boolean)

    /**
     * Notlar ağacını Rust'ta gezer (Kotlin `Store.list` sırası ve süzgeci ile aynı): klasör içeriğinden ÖNCE gelir.
     * [query] kelimeleri klasör adında geçiyorsa `nameHit` işaretlenir. Kütüphane yoksa/hata olursa null (Kotlin yürüyüşü).
     */
    fun walkForSearch(root: File, query: String): List<Walked>? = safe {
        val a = nWalkForSearch(root.path, query) ?: return@safe null
        val out = ArrayList<Walked>(a.size / 4)
        var i = 0
        while (i + 3 < a.size) {
            out.add(Walked(a[i], a[i + 1] == "D", a[i + 2] == "1", a[i + 3] == "1"))
            i += 4
        }
        out
    }

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

    // ------------------------------------------------------------ küçük resim disk önbelleği (galeri)

    /**
     * Diskteki küçük resim (JPEG) varsa Bitmap olarak döner; yoksa, dosya değiştiyse ya da kütüphane yoksa null.
     * [tag]: küçük resim türü (aynı dosyanın farklı boyut/türleri karışmasın).
     */
    fun thumbGet(f: File, cacheDir: File, tag: Int): Bitmap? = safe {
        val b = nThumbGet(f.path, cacheDir.path, tag) ?: return@safe null
        if (b.size < 8) return@safe null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0); val h = bb.getInt(4)
        if (w <= 0 || h <= 0 || b.size.toLong() != 8L + w.toLong() * h.toLong() * 4L) return@safe null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bb.position(8)
        bmp.copyPixelsFromBuffer(bb)
        bmp
    }

    /** Küçük resmi diske yazar (yalnızca opak ARGB_8888; saydam olanlar yazılmaz). Başarılıysa true. */
    fun thumbPut(f: File, cacheDir: File, tag: Int, bmp: Bitmap): Boolean = safe {
        if (bmp.config != Bitmap.Config.ARGB_8888 || bmp.isRecycled || bmp.hasAlpha() && !bmp.isPremultiplied) return@safe false
        val buf = ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)
        nThumbPut(f.path, cacheDir.path, tag, bmp.width, bmp.height, buf.array())
    } ?: false

    /** Önbellek [maxBytes]'ı aşarsa en eski girdileri siler. */
    fun thumbTrim(cacheDir: File, maxBytes: Long) { safe { nThumbTrim(cacheDir.path, maxBytes); true } }

    // ------------------------------------------------------------ mDNS cihaz tarama (Rust, qshare_core::mdns)

    class MdnsHit(val instance: String, val ip: String, val port: Int, val id: String, val name: String)

    /**
     * Tek seferlik mDNS taraması: bulunan tüm cihazlar tek soketle PARALEL çözülür (NsdManager sırayla çözer).
     * Engelleyicidir (arka plan iş parçacığı); en çok [timeoutMs] sürer, cihaz bulunup sessizlik olunca daha erken biter.
     * [token] ile [mdnsCancel] taramayı erken bitirir. [local]: çıkış arayüzünün IPv4'ü. Kütüphane yoksa/hata olursa null.
     */
    fun mdnsBrowse(token: Long, local: String?, skip: String?, timeoutMs: Int): List<MdnsHit>? = safe {
        val a = nMdnsBrowse(token, "_qshare._tcp.local", local, skip, timeoutMs) ?: return@safe null
        val out = ArrayList<MdnsHit>(a.size / 5)
        var i = 0
        while (i + 4 < a.size) {
            out.add(MdnsHit(a[i], a[i + 1], a[i + 2].toIntOrNull() ?: 0, a[i + 3], a[i + 4]))
            i += 5
        }
        out
    }

    fun mdnsCancel(token: Long) { safe { nMdnsCancel(token); true } }

    // ------------------------------------------------------------ Hızlı Paylaş / Link eşitlemesi (qs/)

    /** Rust alma sunucusu: [id] tutamaç, [port] dinlenen port. */
    class Server(val id: Long, val port: Int)

    /** Rust'tan dönen hata/özet metinlerinin dili (cihaz dili Türkçe değilse İngilizce). */
    fun setLang() { safe { nSetLang(java.util.Locale.getDefault().language != "tr"); true } }

    /**
     * Alma sunucusunu başlatır (port 0: boş port). [linkRoot] / [dlRoot] null ise Link / Download eşitlemesi kapalıdır.
     * [trash]: eşitlemede silinenlerin taşındığı klasör (.onemsiz). Başarısızsa ya da kütüphane yoksa null.
     */
    fun serverStart(recvDir: File, linkRoot: File?, dlRoot: File?, trash: File?): Server? = safe {
        val v = nServerStart(recvDir.path, linkRoot?.path, dlRoot?.path, trash?.path, 0)
        if (v < 0) null else Server(v ushr 16, (v and 0xFFFF).toInt())
    }

    /**
     * Sunucu olayını bekler (en çok ~1,5 sn): [tür, ad, klasör]; zaman aşımında boş dizi; sunucu kapandıysa null.
     * Türler: 1 alınıyor, 2 alındı (ad, klasör), 3 alma yarım kaldı, 4 eşitleme dosyaları değiştirdi, 5 bağlantı başladı, 6 bitti.
     */
    fun serverPoll(s: Server): Array<String>? = safe { nServerPoll(s.id) }

    fun serverStop(s: Server) { safe { nServerStop(s.id); true } }

    const val SEND_OK = 0
    const val SEND_CONNECT = 1
    const val SEND_SHORT = 2
    const val SEND_REFUSED = 3

    /**
     * Açık bir dosya tanıtıcısından [len] bayt gönderir; tanıtıcıyı KAPATMAZ. Dönüş: SEND_* kodu
     * (4 = G/Ç hatası); kütüphane yoksa null.
     */
    fun send(addr: String, name: String, fd: Int, len: Long): Int? = safe { nSend(addr, name, fd, len) }

    class SyncOut(val peers: Int, val sent: Int, val got: Int, val deleted: Int, val errors: List<String>)

    /**
     * Link ya da Download klasörünü [peers] (ad → "ip:port") ile eşitler. [mode]: 0 birleştir, 1 bu cihaza göre,
     * 2 karşıdakine göre. Engelleyicidir (arka plan iş parçacığı). Kütüphane yoksa null.
     */
    fun sync(mode: Int, root: File, download: Boolean, trash: File?, peers: List<Pair<String, String>>): SyncOut? = safe {
        val flat = ArrayList<String>(peers.size * 2)
        for ((name, addr) in peers) { flat.add(name); flat.add(addr) }
        val a = nSync(mode, root.path, download, trash?.path, flat.toTypedArray()) ?: return@safe null
        if (a.size < 5) return@safe null
        SyncOut(a[1].toIntOrNull() ?: 0, a[2].toIntOrNull() ?: 0, a[3].toIntOrNull() ?: 0, a[4].toIntOrNull() ?: 0, a.drop(5))
    }
}
