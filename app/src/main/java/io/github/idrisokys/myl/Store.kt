package io.github.idrisokys.myl

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class Entry(val name: String, val uri: Uri, val isDir: Boolean) {
    /** Liste anahtarı; her seferinde yeniden hesaplanmasın diye bir kez üretilir. */
    val key: String = uri.toString()
    /** Düz metin dosyası (.txt) mı? */
    val isTxt: Boolean = !isDir && name.endsWith(".txt", true)
    /** Listede gösterilen ad (uzantısız). */
    val title: String = if (!isDir && (name.endsWith(".url", true) || isTxt)) name.dropLast(4) else name
    val ext: String = if (isTxt) ".txt" else ".url"
}

/** Arama sonucu: bulunan öğe, bulunduğu klasör zinciri ve gösterilecek özet. */
data class Hit(val entry: Entry, val path: List<Entry>, val detail: String, val isNote: Boolean, val titleMatch: Boolean)

object Store {
    private const val ORDER = ".order.txt"

    /** Tüm veriler dahili depolamanın ana dizininde: <ana dizin>/Link */
    private const val DATA_DIR = "Link"

    /** Eski sürümlerin kullandığı konum (<ana dizin>/QuickShare/Link); varsa yeni konuma taşınır. */
    private const val LEGACY_PARENT_DIR = "QuickShare"

    /** Tüm yazma/okuma işlemleri (uygulama + paylaşım ekranı) bu kilitle sıralanır. */
    private val mutex = Any()

    // Aramalar arasında thread havuzunu yeniden kullan; her aramada yeni 6 thread açma.
    // Dört işçi, depolama ağırlıklı aramalarda bellek/IO baskısını sınırlarken arayüzü akıcı tutar.
    private val searchExecutor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "MyL-Search").apply { isDaemon = true }
    }

    private fun file(u: Uri): File = File(u.path ?: "")
    private fun uriOf(f: File): Uri = Uri.fromFile(f)

    /** Depolama erişim izni verilmiş mi? (Android 11+: "Tüm dosyalara erişim", daha eskilerde: depolama izni) */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Ekleme alanı sınırı: bundan uzun metin (tek parça link hariç) .txt dosyası olur. */
    const val ADD_MAX = 220

    /** Veri klasörü (<ana dizin>/Link). Diskte link / Link / LINK gibi farklı yazılmış olabilir; var olan yazım kullanılır. */
    fun dataDir(): File {
        val base = Environment.getExternalStorageDirectory()
        val real = try { base.list()?.firstOrNull { it.equals(DATA_DIR, ignoreCase = true) } } catch (e: Exception) { null }
        return File(base, real ?: DATA_DIR)
    }

    /** Veri klasörünün Uri'si; klasör yoksa oluşturulur. İzin yoksa null. */
    fun root(ctx: Context): Uri? {
        if (!hasAccess(ctx)) return null
        val base = Environment.getExternalStorageDirectory()
        var d = dataDir()
        synchronized(mutex) {
            if (!d.exists()) { migrateLegacy(base, d); d = dataDir() }
            if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) return null
        }
        return uriOf(d)
    }

    /** "Tüm dosyalara erişim" (Android 11+) ayar ekranını açar. Daha eski sürümlerde false döner (normal izin istenmeli). */
    fun openAccessSettings(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val i1 = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
        val i2 = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (i in listOf(i1, i2)) {
            try { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (e: Exception) { }
        }
        return true
    }

    /** Eski QuickShare/Link klasörü varsa ve yeni Link klasörü henüz yoksa, içeriğiyle birlikte yeni konuma taşır. */
    private fun migrateLegacy(base: File, target: File) {
        val parent = File(base, LEGACY_PARENT_DIR)
        val old = File(parent, DATA_DIR)
        if (!old.isDirectory) return
        try {
            if (!old.renameTo(target)) {
                // Aynı bölümde değilse (nadir) kopyala, başarılıysa eskisini sil.
                if (old.copyRecursively(target, overwrite = false)) old.deleteRecursively()
                else target.deleteRecursively()   // yarım kalan kopyayı bırakma; veri eski yerinde kalır
            }
            // QuickShare klasörü boş kaldıysa kaldır (başka veri varsa dokunma).
            if (parent.isDirectory && parent.list()?.isEmpty() == true) parent.delete()
        } catch (e: Exception) { }
    }

    /** Dosya yöneticisinde açmak için: Uri'yi harici depolama belge Uri'sine çevirir. */
    fun documentUri(dir: Uri): Uri? {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val p = dir.path ?: return null
        if (!p.startsWith(base)) return null
        val rel = p.removePrefix(base).trim('/')
        return android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    }

    private val WS = Regex("\\s+")
    private val CTRL = Regex("[\\r\\n\\t]+")
    private val SEPS = Regex("\\s*[:|/\\\\]\\s*")
    private val BAD = Regex("[*?\"<>]")
    private val URL_RE = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /** Dosya adında sorun çıkaran karakterleri düzgünce temizler. */
    private fun clean(s: String, max: Int = 80): String =
        s.replace(CTRL, " ")
            .replace(SEPS, " - ")
            .replace(BAD, "")
            .replace(WS, " ")
            .trim().trimStart('.', ' ', '-').take(max).trimEnd { it.isHighSurrogate() }
            .let { fitBytes(it) }.trim().ifBlank { "link" }

    /** Dosya sistemi adı ~255 bayt ile sınırlı; ".url" ve " (2)" payı bırakarak bayt cinsinden kısaltır. */
    private fun fitBytes(s: String, limit: Int = 240): String {
        var bytes = 0; var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > limit) break
            bytes += n; i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    /** Tek sorguyla klasörün tüm içeriği; okunamazsa null (boş klasörden ayırt edilir). */
    private fun childrenOrNull(dir: Uri): List<Entry>? {
        val fs = file(dir).listFiles() ?: return null
        return fs.map { Entry(it.name, uriOf(it), it.isDirectory) }
    }

    private fun children(dir: Uri): List<Entry> = childrenOrNull(dir) ?: emptyList()

    private fun ordered(all: List<Entry>): List<Entry> {
        val files = all.filter { !it.name.startsWith(".") && (it.isDir || it.name.endsWith(".url", true) || it.name.endsWith(".txt", true)) }
        val byName = files.associateBy { it.name }
        val ord = readOrder(all).distinct().mapNotNull { byName[it] }
        val seen = ord.mapTo(HashSet()) { it.name }
        val rest = files.filter { it.name !in seen }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return ord + rest
    }

    fun list(ctx: Context, dir: Uri): List<Entry> = synchronized(mutex) { ordered(children(dir)) }

    private fun readOrder(all: List<Entry>): List<String> {
        val f = all.firstOrNull { it.name == ORDER } ?: return emptyList()
        return try { file(f.uri).readLines().filter { it.isNotBlank() } } catch (e: Exception) { emptyList() }
    }

    private fun saveOrderIn(dir: Uri, names: List<String>) {
        try { File(file(dir), ORDER).writeText(names.joinToString("\n")) } catch (e: Exception) { }
    }

    fun saveOrder(ctx: Context, dir: Uri, names: List<String>) = synchronized(mutex) {
        // Klasör okunamadıysa sıralama dosyası yeniden yazılmaz.
        if (childrenOrNull(dir) != null) saveOrderIn(dir, names)
    }

    private fun write(dir: Uri, base: String, bytes: ByteArray, max: Int = 220, ext: String = ".url"): Boolean = synchronized(mutex) {
        try {
            val all = childrenOrNull(dir) ?: return false
            val taken = all.mapTo(HashSet()) { it.name.lowercase() }
            val b = clean(base, max)
            var name = "$b$ext"; var i = 2
            while (name.lowercase() in taken) { name = "$b ($i)$ext"; i++ }
            File(file(dir), name).writeBytes(bytes)
            // Yeni öğe listenin sonuna eklenir; mevcut sıra korunur.
            saveOrderIn(dir, ordered(all).map { it.name } + name)
            true
        } catch (e: Exception) { false }
    }

    fun addLink(ctx: Context, dir: Uri, title: String, url: String): Boolean =
        write(dir, title, "[InternetShortcut]\r\nURL=$url\r\n".toByteArray())

    /** Düz metni de kaydeder (URL olmayan kayıt). */
    fun addNote(ctx: Context, dir: Uri, text: String): Boolean {
        val title = text.replace(WS, " ").trim().ifBlank { "not" }
        return write(dir, title, noteBytes(text))
    }

    private fun noteBytes(text: String): ByteArray {
        val enc = text.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")
        return "[InternetShortcut]\r\nTEXT=$enc\r\n".toByteArray()
    }

    /** Metni, uzunluk sınırı olmadan düz .txt dosyası olarak kaydeder; ad metnin ilk 100 karakteridir. */
    fun addTextFile(ctx: Context, dir: Uri, text: String): Boolean {
        val title = text.replace(WS, " ").trim().ifBlank { "not" }
        return write(dir, title, text.toByteArray(Charsets.UTF_8), 100, ".txt")
    }

    /** .txt dosyasının tamamını okur. */
    fun readPlain(ctx: Context, uri: Uri): String? = try {
        file(uri).readText(Charsets.UTF_8).removePrefix("\uFEFF")
    } catch (e: Exception) { null }

    /** Mevcut .txt dosyasının içeriğini baştan yazar (dosya adı değişmez). */
    fun writePlain(ctx: Context, uri: Uri, text: String): Boolean = synchronized(mutex) {
        try { file(uri).writeText(text, Charsets.UTF_8); true } catch (e: Exception) { false }
    }

    /** Metindeki ilk linki bulur; sondaki noktalama işaretlerini atar. */
    fun extractUrl(text: String): String? {
        var u = URL_RE.find(text)?.value ?: return null
        while (u.isNotEmpty()) {
            val last = u.last()
            val strip = last in ".,;:!?\"'>]}" || (last == ')' && '(' !in u)
            if (strip) u = u.dropLast(1) else break
        }
        return if (u.length > 8) u else null
    }

    /** Link veya metni uygun şekilde kaydeder. Ağ erişimi yaptığı için arka planda çağrılmalı. */
    fun addFromText(ctx: Context, dir: Uri, text: String, subject: String? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val url = extractUrl(t) ?: return addNote(ctx, dir, t)
        // Ağ erişimi yok: başlık, paylaşılan konudan / metinden ya da sitenin adresinden alınır.
        val title = subject?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: t.replace(url, "").lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: "link"
        return addLink(ctx, dir, title, url)
    }

    /**
     * Tek "gönder" kuralı (uygulama içi ekleme, launcher arama kutusu ve Paylaş menüsü hep bunu kullanır):
     * link ise .url, kısa metin not (.url içinde TEXT=), [ADD_MAX]'ı aşan metin .txt. Tek parça uzun link yine link olur.
     * Ağ erişimi yaptığı için arka planda çağrılmalı.
     */
    fun submit(ctx: Context, dir: Uri, text: String, subject: String? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val singleUrl = extractUrl(t) != null && t.none { it.isWhitespace() }
        return if (t.length > ADD_MAX && !singleUrl) addTextFile(ctx, dir, t) else addFromText(ctx, dir, t, subject)
    }

    fun moveLink(ctx: Context, e: Entry, target: Uri): Boolean = synchronized(mutex) {
        try {
            val bytes = file(e.uri).readBytes()
            if (!write(target, e.title, bytes, 220, e.ext)) return false
            file(e.uri).delete()
        } catch (ex: Exception) { false }
    }

    /** Link ya da klasörü başka klasöre taşır. [from] öğenin şu an bulunduğu klasördür. */
    fun moveEntry(ctx: Context, e: Entry, from: Uri, target: Uri): Boolean = synchronized(mutex) {
        if (!e.isDir) return moveLink(ctx, e, target)
        try {
            val all = children(target)
            val taken = all.mapTo(HashSet()) { it.name.lowercase() }
            var name = e.name
            var i = 2
            while (name.lowercase() in taken) { name = "${e.name} ($i)"; i++ }
            val src = file(e.uri)
            val dst = File(file(target), name)
            // Hedef kaynağın içindeyse taşıma yapılmaz.
            if (dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) return false
            if (!src.renameTo(dst)) {
                if (!src.copyRecursively(dst, overwrite = false)) return false
                src.deleteRecursively()
            }
            saveOrderIn(target, ordered(all).map { it.name } + name)
            true
        } catch (ex: Exception) { false }
    }

    fun addFolder(ctx: Context, dir: Uri, name: String) = synchronized(mutex) {
        try {
            val all = children(dir)
            val base = clean(name)
            var n = base; var i = 2
            while (all.any { it.name.equals(n, true) }) { n = "$base ($i)"; i++ }
            File(file(dir), n).mkdir()
            Unit
        } catch (e: Exception) { }
    }

    /** Link (.url) veya klasör adını değiştirir; sıralamayı korur. */
    fun rename(ctx: Context, dir: Uri, e: Entry, title: String): Boolean = synchronized(mutex) {
        try {
            val base = clean(title, 220)
            val newName = if (e.isDir) base else base + e.ext
            val all = children(dir)
            // Kısa metin (not) ise: kopyalanan içerik de yeni ada uysun diye dosya içeriği güncellenir
            val isNote = !e.isDir && !e.isTxt && textOf(lines(e.uri)) != null
            if (newName == e.name) {
                if (isNote) file(e.uri).writeBytes(noteBytes(title.trim()))
                return true
            }
            if (all.any { it.name.equals(newName, true) && it.uri != e.uri }) return false
            val order = ordered(all).map { it.name }
            val dst = File(file(dir), newName)
            if (!file(e.uri).renameTo(dst)) return false
            if (isNote) dst.writeBytes(noteBytes(title.trim()))
            saveOrderIn(dir, order.map { if (it == e.name) newName else it })
            true
        } catch (ex: Exception) { false }
    }

    fun delete(ctx: Context, uri: Uri) = synchronized(mutex) {
        try { file(uri).deleteRecursively(); Unit } catch (e: Exception) { }
    }

    private fun lines(uri: Uri): List<String> = try {
        file(uri).readLines().map { it.trim().removePrefix("\uFEFF") }
    } catch (e: Exception) { emptyList() }

    private fun urlOf(ls: List<String>): String? =
        ls.firstOrNull { it.startsWith("URL=", true) }?.substring(4)?.trim()?.ifBlank { null }

    private fun textOf(ls: List<String>): String? =
        ls.firstOrNull { it.startsWith("TEXT=", true) }?.let { unescape(it.substring(5)) }

    /** Dosyayı bir kez okuyup (link, metin) çiftini döndürür. */
    fun readLink(ctx: Context, uri: Uri): Pair<String?, String?> {
        val ls = lines(uri)
        return urlOf(ls) to textOf(ls)
    }

    private fun unescape(dec: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < dec.length) {
            val c = dec[i]
            if (c == '\\' && i + 1 < dec.length) { sb.append(if (dec[i + 1] == 'n') '\n' else dec[i + 1]); i += 2 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Büyük/küçük harf, Türkçe i/ı/İ/I ve aksan (ş→s, ğ→g...) farkını yok sayar; uzunluğu korur. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when {
            c.code < 128 -> c.lowercaseChar()
            c == 'İ' || c == 'ı' -> 'i'
            c == 'ş' || c == 'Ş' -> 's'
            c == 'ğ' || c == 'Ğ' -> 'g'
            c == 'ü' || c == 'Ü' -> 'u'
            c == 'ö' || c == 'Ö' -> 'o'
            c == 'ç' || c == 'Ç' -> 'c'
            else -> java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).first().lowercaseChar()
        })
        return sb.toString()
    }

    /** Tüm klasörlerde; başlık, link adresi ve not metninde arar. Yazılan her kelime bulunmalıdır. Dosyalar paralel okunur. */
    fun search(ctx: Context, root: Uri, query: String, alive: () -> Boolean = { true }): List<Hit> {
        val terms = fold(query).split(WS).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val files = ArrayList<Pair<Entry, List<Entry>>>()
        fun walk(dir: Uri, path: List<Entry>) {
            for (e in list(ctx, dir)) {
                if (!alive()) return
                if (e.isDir) walk(e.uri, path + e) else files.add(e to path)
            }
        }
        walk(root, emptyList())

        fun match(e: Entry, path: List<Entry>): Hit? {
            val ls = if (e.isTxt) emptyList() else lines(e.uri)
            val url = urlOf(ls)
            val text = if (e.isTxt) readPlain(ctx, e.uri) ?: "" else textOf(ls) ?: ""
            val ft = fold(e.title)
            val body = url ?: text
            val fb = fold(body)
            val all = ft + "\n" + fb
            if (!terms.all { all.contains(it) }) return null
            val detail = if (url != null) url else {
                val i = terms.map { fb.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                val st = maxOf(0, i - 30)
                (if (st > 0) "…" else "") + text.substring(st, minOf(text.length, st + 120)).replace(WS, " ")
            }
            return Hit(e, path, detail, url == null, terms.all { ft.contains(it) })
        }

        val completion = ExecutorCompletionService<Hit?>(searchExecutor)
        val pending = HashSet<Future<Hit?>>()
        val out = ArrayList<Hit>(minOf(files.size, 500))
        var nextFile = 0
        try {
            // Kuyruğu sınırsız büyütme: en fazla 8 dosya aynı anda işlenmek üzere sıraya girsin.
            while (alive() && out.size < 500 && (nextFile < files.size || pending.isNotEmpty())) {
                while (alive() && nextFile < files.size && pending.size < 8) {
                    val (entry, path) = files[nextFile++]
                    pending.add(completion.submit(Callable {
                        if (!Thread.currentThread().isInterrupted) match(entry, path) else null
                    }))
                }

                // Sonuçları tamamlanma sırasıyla al: yavaş bir dosya diğer sonuçları bloke etmesin.
                val completed = completion.poll(100, TimeUnit.MILLISECONDS) ?: continue
                pending.remove(completed)
                runCatching { completed.get() }.getOrNull()?.let(out::add)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // Kullanıcı aramayı değiştirdiyse bekleyen işleri mümkün olduğunca erken durdur.
            pending.forEach { it.cancel(true) }
        }
        return out.sortedBy { !it.titleMatch }   // başlıkta geçenler önce
    }
}
