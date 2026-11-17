package github.idrisokys.myl.qs

import android.content.Context
import android.os.SystemClock
import github.idrisokys.myl.L
import github.idrisokys.myl.Store
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/*
 * Link klasörü eşitleme: Hızlı Paylaş'ın TCP/mDNS altyapısını kullanır; Hızlı Paylaş penceresindeki (SendPopup) ⇄ simgesinden başlatılır
 * (Notlarım'da eşitleme düğmesi yoktur). Mac uygulaması da aynı uç noktaları sunar.
 * Ağda açık (Hızlı Ayarlar kutucuğu açık) olan her cihazla <ana dizin>/Link içindeki .url / .txt dosyaları ve
 * klasörler karşılaştırılır:
 *   - karşı cihazda olmayan dosya gönderilir, bizde olmayan dosya alınır (eksikler tamamlanır),
 *   - iki tarafta da olan aynı yoldaki dosyanın DEĞİŞTİRİLME ZAMANI yeni olanı eskisinin üzerine yazar.
 * Silinen dosyalar eşitlenmez (silinen, diğer cihazda varsa geri gelir).
 *
 * Yıldız düğmesi ([run] mirror = true): "bu cihaz esas alınır". Karşı cihazlar bu cihazın birebir kopyası olur:
 * bizde olmayan dosya/klasörler karşı cihazdan silinir (Notlarım'daki "Sil" gibi gizli .onemsiz klasörüne taşınır),
 * farklı olanların üzerine bizimki yazılır (zamana bakılmaz), eksikler gönderilir. Bu cihazda hiçbir şey değişmez.
 */
object NoteSync {
    /** Dosya sistemlerinin zaman çözünürlüğü farkı için tolerans: bundan yakın zamanlar "aynı" sayılır. */
    private const val TOL = 2000L
    private const val MAX_DEPTH = 24

    /** [rel]: karşılaştırma anahtarı (NFC); [raw]: karşı cihazın bildirdiği özgün yol (macOS adları NFD olabilir, istekte bu kullanılır). */
    class Item(val rel: String, val dir: Boolean, val size: Long, val mtime: Long, val raw: String = rel)

    class Result(
        var peers: Int = 0, var sent: Int = 0, var got: Int = 0, var deleted: Int = 0, val mode: Int = 0,
        val errors: MutableList<String> = mutableListOf(), var error: String? = null
    ) {
        fun message(): String = when {
            error == null && peers > 0 && mode == 2 -> {
                val base = L("Karşıdaki cihaza göre eşitlendi: $got alındı, $deleted silindi", "Synced to the other device: $got received, $deleted deleted")
                if (errors.isEmpty()) base else base + "\n" + errors.first()
            }
            error == null && peers > 0 && mode == 1 -> {
                val base = L("Bu cihaz esas alındı: $peers cihaz, $sent gönderildi, $deleted silindi", "This device is now the master: $peers device(s), $sent sent, $deleted deleted")
                if (errors.isEmpty()) base else base + "\n" + errors.first()
            }
            error != null -> L("Eşitleme hatası: $error", "Sync error: $error")
            peers == 0 -> L("Açık cihaz bulunamadı", "No open device found")
            else -> {
                val base = L("Eşitlendi: $peers cihaz, $sent gönderildi, $got alındı", "Synced: $peers device(s), $sent sent, $got received")
                if (errors.isEmpty()) base else base + "\n" + errors.first()
            }
        }
    }

    // ---------------------------------------------------------------- ortak yardımcılar

    private fun root(): File = Store.dataDir()

    private fun isNoteName(n: String) = !n.endsWith(".part", true) && (n.endsWith(".url", true) || n.endsWith(".txt", true))

    /** macOS dosya adları ayrışık (NFD: "s"+"̧") olabilir; Android'dekiyle eşleşsin diye birleşik (NFC) biçime çevrilir. */
    private fun nfc(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    /** Karşı taraf zamanı saniye olarak bildirirse (1e11'den küçük) milisaniyeye çevirir. */
    private fun ms(t: Long) = if (t in 1 until 100_000_000_000L) t * 1000 else t

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    /**
     * Karşı taraftan gelen göreli yolu Link klasörü içinde güvenli bir dosyaya çevirir; geçersizse null.
     * Her bölüm ayrı doğrulanır (".." ve kontrol karakterleri yok), sonuç Link dışına çıkamaz.
     * [dir] false ise yalnızca .url / .txt adları kabul edilir.
     */
    fun resolve(rel: String, dir: Boolean = false): File? {
        val segs = rel.split('/')
        if (segs.isEmpty() || segs.size > MAX_DEPTH) return null
        for (sg in segs) {
            if (sg.isEmpty() || sg == "." || sg == ".." || sg.toByteArray().size > 240) return null
            if (sg.any { it.code < 32 || it in ":*?\"<>|\\" }) return null
        }
        if (!dir && !(isNoteName(segs.last()) || segs.last() == ".order.txt")) return null
        val base = root()
        val f = File(base, segs.joinToString("/"))
        return try {
            val bc = base.canonicalPath
            if (f.canonicalPath.startsWith(bc + File.separator)) f else null
        } catch (e: Exception) { null }
    }

    /** Yerel not ağacı: göreli yol → öğe. */
    fun local(): Map<String, Item> {
        val out = LinkedHashMap<String, Item>()
        fun walk(d: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val fs = d.listFiles() ?: return
            for (f in fs) {
                val rel = prefix + f.name
                if (f.isDirectory) { out[rel] = Item(rel, true, 0, 0); walk(f, "$rel/", depth + 1) }
                else if (isNoteName(f.name)) out[rel] = Item(rel, false, f.length(), f.lastModified())
            }
        }
        walk(root(), "", 0)
        return out
    }

    // ---------------------------------------------------------------- sunucu tarafı (Receiver çağırır)

    private fun respond(s: Socket, status: String, headers: String, body: ByteArray?) {
        val out = s.outputStream
        out.write("HTTP/1.1 $status\r\nContent-Length: ${body?.size ?: 0}\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    /** GET /m : yerel not listesi. Satır: T \t boyut \t zaman \t kodlanmış-yol (T: F dosya, D klasör). */
    fun serveManifest(s: Socket) {
        val sb = StringBuilder()
        for (it in local().values) sb.append(if (it.dir) "D" else "F").append('\t').append(it.size).append('\t')
            .append(it.mtime).append('\t').append(enc(it.rel)).append('\n')
        respond(s, "200 OK", "", sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** GET /f (X-Name: yol) : tek dosyayı gönderir. */
    fun serveFile(s: Socket, rel: String) {
        val f = resolve(rel)
        if (f == null || !f.isFile) { respond(s, "404 Not Found", "", null); return }
        val len = f.length()
        val out = s.outputStream
        out.write("HTTP/1.1 200 OK\r\nContent-Length: $len\r\nX-Mtime: ${f.lastModified()}\r\nConnection: close\r\n\r\n".toByteArray())
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    /** DELETE /x (X-Name: yol, X-Dir: 1 klasör): öğeyi siler (gizli .onemsiz klasörüne taşır; geri alınabilir). */
    fun serveDelete(s: Socket, rel: String, dir: Boolean) {
        val f = resolve(rel, dir = dir)
        val ok = f != null && (!f.exists() || github.idrisokys.myl.common.moveToOnemsiz(f))
        respond(s, if (ok) "200 OK" else "500 Error", "", null)
    }

    /** POST /d (X-Name: yol) : klasör oluşturur. */
    fun serveMkdir(s: Socket, rel: String) {
        val d = resolve(rel, dir = true)
        val ok = d != null && (d.isDirectory || d.mkdirs())
        respond(s, if (ok) "200 OK" else "500 Error", "", null)
    }

    // ---------------------------------------------------------------- istemci tarafı

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private class Resp(val status: Int, val len: Long, val mtime: Long, val ins: InputStream)

    private fun connect(p: Peer): Socket {
        val s = Socket().apply { tcpNoDelay = true; soTimeout = 30000 }
        s.connect(InetSocketAddress(p.host, p.port), 8000)
        return s
    }

    private fun readResp(s: Socket): Resp {
        val ins = java.io.BufferedInputStream(s.inputStream, 64 * 1024)
        val st = readLine(ins).split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var len = 0L; var mt = 0L
        while (true) {
            val l = readLine(ins)
            if (l.isEmpty()) break
            val k = l.substringBefore(':').lowercase(); val v = l.substringAfter(':').trim()
            if (k == "content-length") len = v.toLongOrNull() ?: 0L
            else if (k == "x-mtime") mt = ms(v.toLongOrNull() ?: 0L)
        }
        return Resp(st, len, mt, ins)
    }

    private fun fetchManifest(p: Peer): Map<String, Item> {
        connect(p).use { s ->
            s.outputStream.write("GET /m HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray()); s.outputStream.flush()
            val r = readResp(s)
            require(r.status == 200) {
                L("Karşı cihaz Link eşitlemesini desteklemiyor ya da izni yok (kod ${r.status}). Karşı cihazda uygulamayı güncelleyin, Hızlı Paylaş'ı açın ve dosya erişim izni verin.",
                    "The other device does not support Link sync or has no file access (code ${r.status}). Update the app there, turn Quick Share on and grant file access.")
            }
            val bytes = ByteArray(r.len.toInt().coerceIn(0, 8 shl 20))
            var n = 0
            while (n < bytes.size) { val k = r.ins.read(bytes, n, bytes.size - n); if (k < 0) break; n += k }
            val out = LinkedHashMap<String, Item>()
            String(bytes, 0, n, Charsets.UTF_8).lineSequence().forEach { ln ->
                val a = ln.split('\t')
                if (a.size == 4) {
                    val raw = dec(a[3]).trim('/')
                    if (raw.isNotEmpty()) {
                        val rel = nfc(raw)
                        out[rel] = Item(rel, a[0].trim() == "D", a[1].trim().toLongOrNull() ?: 0, ms(a[2].trim().toLongOrNull() ?: 0), raw)
                    }
                }
            }
            return out
        }
    }

    private fun pull(p: Peer, it: Item) {
        val dest = resolve(it.rel) ?: return
        dest.parentFile?.mkdirs()
        connect(p).use { s ->
            s.outputStream.write("GET /f HTTP/1.1\r\nX-Name: ${enc(it.raw)}\r\nConnection: close\r\n\r\n".toByteArray()); s.outputStream.flush()
            val r = readResp(s)
            require(r.status == 200) { "${it.rel}: ${r.status}" }
            val tmp = File(dest.parentFile, "${dest.name}.${UUID.randomUUID().toString().take(8)}.part")
            var ok = false
            try {
                FileOutputStream(tmp).use { o ->
                    val b = ByteArray(64 * 1024); var left = r.len
                    while (left > 0) {
                        val n = r.ins.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(b, 0, n); left -= n
                    }
                    ok = left == 0L
                }
                if (ok) {
                    if (r.mtime > 0) tmp.setLastModified(r.mtime)
                    dest.delete()
                    ok = tmp.renameTo(dest)
                }
            } finally { if (!ok) tmp.delete() }
            require(ok) { "${it.rel}: " + L("alma yarım kaldı", "incomplete") }
        }
    }

    private fun push(p: Peer, it: Item) {
        val f = resolve(it.rel) ?: return
        val len = f.length(); val mt = f.lastModified()
        connect(p).use { s ->
            val out = s.outputStream
            out.write(("POST /u HTTP/1.1\r\nX-Name: ${enc(it.rel)}\r\nX-Sync: 1\r\nX-Mtime: $mt\r\n" +
                "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray())
            var sent = 0L
            f.inputStream().use { i ->
                val b = ByteArray(64 * 1024)
                while (sent < len) {
                    val n = i.read(b, 0, minOf(b.size.toLong(), len - sent).toInt())
                    if (n < 0) break
                    out.write(b, 0, n); sent += n
                }
            }
            require(sent == len) { "${it.rel}: " + L("dosya değişti", "file changed") }
            out.flush()
            val r = readResp(s)
            require(r.status == 200) { "${it.rel}: " + L("karşı cihaz kabul etmedi", "refused") }
        }
    }

    private fun mkdirRemote(p: Peer, rel: String) {
        connect(p).use { s ->
            s.outputStream.write("POST /d HTTP/1.1\r\nX-Name: ${enc(rel)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            s.outputStream.flush()
            require(readResp(s).status == 200) { "$rel: mkdir" }
        }
    }

    private fun deleteRemote(p: Peer, rel: String, dir: Boolean) {
        // Bilerek DELETE ve Content-Length yok: bu özelliği bilmeyen eski sürüm isteği "400" ile reddeder (boş dosya oluşturmaz).
        connect(p).use { s ->
            s.outputStream.write("DELETE /x HTTP/1.1\r\nX-Name: ${enc(rel)}\r\nX-Dir: ${if (dir) 1 else 0}\r\nConnection: close\r\n\r\n".toByteArray())
            s.outputStream.flush()
            require(readResp(s).status == 200) { "$rel: " + L("silinemedi / eski sürüm", "could not delete / old version") }
        }
    }

    /** Karşı cihazı bu cihazın aynısı yapar; (gönderilen, silinen) sayısını döndürür. */
    private fun mirrorPeer(p: Peer, errs: MutableList<String>): Pair<Int, Int> {
        val remote = fetchManifest(p)
        val loc = local()
        var sent = 0; var deleted = 0
        val depth = { k: String -> k.count { c -> c == '/' } }
        // 1) Karşıda olup bizde olmayan (ya da türü farklı) öğeleri sil; silinen klasörün içindekiler ayrıca sayılmaz
        val stale = remote.keys.filter { k -> val l = loc[k]; l == null || l.dir != remote[k]!!.dir }
        val gone = mutableListOf<String>()
        for (k in stale.sortedWith(compareBy(depth, { it }))) {
            if (gone.any { k.startsWith("$it/") }) continue
            val r = remote[k]!!
            try { deleteRemote(p, r.raw, r.dir); deleted++; if (r.dir) gone.add(k) }
            catch (e: Exception) { errs.add("${p.name}: ${e.message ?: k}") }
        }
        // 2) Bizdekileri karşıya yaz: klasörler önce; dosyalar eksik ya da farklıysa (boyut/zaman) zorla üzerine yazılır
        for (k in loc.keys.sortedWith(compareBy(depth, { it }))) {
            val l = loc[k]!!; val r = remote[k]?.takeIf { it.dir == l.dir && k !in gone }
            try {
                if (l.dir) { if (r == null) mkdirRemote(p, k) }
                else if (r == null || r.size != l.size || Math.abs(l.mtime - r.mtime) > TOL) { push(p, l); sent++ }
            } catch (e: Exception) { errs.add("${p.name}: ${e.message ?: k}") }
        }
        return sent to deleted
    }

    /** Bu cihazı karşı cihazın aynısı yapar; (alınan, silinen) döndürür. Karşı cihazda yeni sürüm gerekmez. */
    private fun pullMirror(p: Peer, errs: MutableList<String>): Pair<Int, Int> {
        val remote = fetchManifest(p)
        val loc = local()
        var got = 0; var deleted = 0
        val depth = { k: String -> k.count { c -> c == '/' } }
        val stale = loc.keys.filter { k -> val r = remote[k]; r == null || r.dir != loc[k]!!.dir }
        val gone = mutableListOf<String>()
        for (k in stale.sortedWith(compareBy(depth, { it }))) {
            if (gone.any { k.startsWith("$it/") }) continue
            val l = loc[k]!!
            try {
                val f = resolve(k, dir = l.dir)
                if (f != null && f.exists() && !github.idrisokys.myl.common.moveToOnemsiz(f)) error(k)
                deleted++; if (l.dir) gone.add(k)
            } catch (e: Exception) { errs.add("${p.name}: ${e.message ?: k}") }
        }
        for (k in remote.keys.sortedWith(compareBy(depth, { it }))) {
            val r = remote[k]!!; val l = loc[k]?.takeIf { it.dir == r.dir && k !in gone }
            try {
                if (r.dir) { if (l == null) resolve(k, dir = true)?.mkdirs() }
                else if (l == null || l.size != r.size || Math.abs(l.mtime - r.mtime) > TOL) { pull(p, r); got++ }
            } catch (e: Exception) { errs.add("${p.name}: ${e.message ?: k}") }
        }
        return got to deleted
    }

    /** Tek cihazla eşitler; (gönderilen, alınan) sayısını döndürür. Dosya bazlı hatalar [errs]'e eklenir, diğer dosyalar sürer. */
    private fun syncPeer(p: Peer, errs: MutableList<String>): Pair<Int, Int> {
        val remote = fetchManifest(p)
        val loc = local()
        var sent = 0; var got = 0
        val keys = (loc.keys + remote.keys).sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))   // üst klasörler önce
        for (k in keys) {
            val l = loc[k]; val r = remote[k]
            try {
                if ((l ?: r)!!.dir) {
                    if (l == null) resolve(k, dir = true)?.mkdirs()
                    else if (r == null) mkdirRemote(p, k)
                    continue
                }
                when {
                    r == null -> { push(p, l!!); sent++ }
                    l == null -> { pull(p, r); got++ }
                    l.mtime - r.mtime > TOL -> { push(p, l); sent++ }
                    r.mtime - l.mtime > TOL -> { pull(p, r); got++ }
                }
            } catch (e: Exception) { errs.add("${p.name}: ${e.message ?: k}") }
        }
        return sent to got
    }

    private fun reachable(p: Peer): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(p.host, p.port), 1500) }; true
    } catch (e: Exception) { false }

    /**
     * Ağı tek seferlik tarar ve gerçekten açık (bağlanılabilen) cihazları döndürür. Arka plan iş parçacığında çağrılmalı.
     * Hızlı Paylaş servisi açık olsa da kendi aramasını yapar (liste her seferinde sıfırdan kurulur).
     */
    fun scan(c: Context): List<Peer> {
        Identity.init(c)
        Peers.clear()
        val disc = try { Discovery(c).also { it.browse() } } catch (e: Exception) { null }
        try {
            val t0 = SystemClock.uptimeMillis(); var found = -1L
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (found < 0 && Peers.list().isNotEmpty()) found = now
                if (found >= 0 && now - found >= 1000) break
                if (found < 0 && now - t0 >= 3500) break
                Thread.sleep(150)
            }
            val cand = Peers.list()
            val ok = java.util.concurrent.ConcurrentLinkedQueue<Peer>()
            cand.map { p -> kotlin.concurrent.thread(isDaemon = true) { if (reachable(p)) ok.add(p) else Peers.del(p.svc) } }
                .forEach { it.join(2500) }
            return cand.filter { it in ok }
        } finally { try { disc?.stop() } catch (e: Exception) { } }
    }

    /**
     * Seçilen cihazlarla eşitler. Arka plan iş parçacığında çağrılmalı (ağ + disk).
     * mode: 0 = iki cihaz aynı olsun (birleştirir), 1 = bu cihaza göre, 2 = karşıdaki cihaza göre (ilk seçilen).
     */
    fun run(c: Context, mode: Int, targets: List<Peer>): Result {
        val res = Result(mode = mode)
        if (!Store.hasAccess(c)) { res.error = L("depolama izni yok", "no storage permission"); return res }
        if (targets.isEmpty()) { res.error = L("cihaz seçilmedi", "no device selected"); return res }
        Identity.init(c)
        // Eşitleme her cihazın ana dizinindeki Link klasörü üzerinden yürür; klasör yoksa (eski QuickShare/Link dahil) hazırlanır.
        if (Store.root(c) == null) { res.error = L("Link klasörü hazırlanamadı", "Could not prepare the Link folder"); return res }
        Hold.acquire(c)
        try {
            for (p0 in if (mode == 2) targets.take(1) else targets) {
                var p = p0
                try {
                    fun go(x: Peer) = when (mode) { 1 -> mirrorPeer(x, res.errors); 2 -> pullMirror(x, res.errors); else -> syncPeer(x, res.errors) }
                    val (a, b) = try { go(p) } catch (e: java.io.IOException) {
                        // Bayat adres/port olabilir: mDNS'ten yeniden çözüp bir kez daha dene.
                        val np = reresolve(c, p) ?: throw e
                        Peers.put(np.svc, np); p = np
                        go(p)
                    }
                    res.peers++
                    when (mode) { 1 -> { res.sent += a; res.deleted += b }; 2 -> { res.got += a; res.deleted += b }; else -> { res.sent += a; res.got += b } }
                } catch (e: Exception) {
                    Peers.del(p0.svc)
                    res.errors.add("${p0.name}: ${e.message ?: L("bağlantı hatası", "connection error")}")
                }
            }
        } finally { Hold.release() }
        return res
    }
}
