package github.idrisokys.myl.qs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import github.idrisokys.myl.L
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/*
 * Hızlı Paylaş (eski QuickShare uygulaması) — MyL'ye gömülü.
 * Arayüzü yoktur: ana ekrandaki Hızlı Paylaş düğmesi (dokun = aç, basılı tut = kapat) ve Paylaş menüsü girişi (QsShareActivity) vardır.
 * Aynı Wi-Fi'deki cihazlar mDNS ile bulunur, dosyalar düz TCP ile gönderilir/alınır.
 */

private const val TYPE = "_qshare._tcp."
private const val BUF = 1 shl 20
/**
 * TCP soket tamponu (SO_RCVBUF/SO_SNDBUF). 5 GHz Wi-Fi (802.11ac/ax) gecikme x bant genişliği çarpımını karşılayacak
 * kadar büyük pencere için 4 MB istenir; sistem kendi üst sınırına kırpar (büyük istek, küçük istekten kötü olamaz).
 */
private const val SOCK_BUF = 4 shl 20
/** Alıcıda ağ okuma ile disk yazmayı ayıran tampon havuzu: en çok SLOTS × CH = 8 MB (gerektikçe ayrılır). */
private const val CH = 512 * 1024
private const val SLOTS = 16
private class Chunk(val b: ByteArray, val n: Int)   // n < 0: bitti işareti

class Peer(val id: String, val name: String, val host: InetAddress, val port: Int, val svc: String = "")

/** Kısa durum mesajlarını (Dosya alındı / Dosya gönderildi / hata) ana ekrana iletir. Bildirim ve ilerleme çubuğu yoktur. */
object Bus {
    fun set(s: String) { ReceiverService.eventListener?.invoke(s) }
}

/** Aktarım sürerken Wi-Fi ve CPU'yu uyanık tutar; aktarım bitince bırakır (boşta pil harcamaz). */
object Hold {
    private var n = 0
    private var wl: WifiManager.WifiLock? = null
    private var wl2: WifiManager.WifiLock? = null
    private var pl: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Synchronized fun acquire(c: Context) {
        if (n++ > 0) return
        val app = c.applicationContext
        wl = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "qs").apply { setReferenceCounted(false); acquire() }
        // Android 14 öncesinde HIGH_PERF de tutulur: düşük gecikme kilidi yalnızca ön plan + ekran açıkken etkindir.
        if (Build.VERSION.SDK_INT in 29..33) {
            wl2 = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qs2").apply { setReferenceCounted(false); acquire() }
        }
        pl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qs:xfer").apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
    }

    @Synchronized fun release() {
        if (n == 0 || --n > 0) return
        try { wl?.release() } catch (e: Exception) { }
        try { wl2?.release() } catch (e: Exception) { }
        try { pl?.release() } catch (e: Exception) { }
        wl = null; wl2 = null; pl = null
    }
}

/** Ağda bulunan cihazlar. */
object Peers {
    private val m = LinkedHashMap<String, Peer>()
    @Synchronized fun put(svc: String, p: Peer) { m[svc] = p }
    @Synchronized fun del(svc: String) { m.remove(svc) }
    @Synchronized fun find(svc: String): Peer? = m[svc]
    @Synchronized fun list(): List<Peer> = m.values.distinctBy { it.id }
    @Synchronized fun clear() { m.clear() }
}

/** Cihaz kimliği: ilk kullanımda üretilen rastgele kimlik (şifreleme / sertifika yok). */
object Identity {
    lateinit var id: String
    lateinit var name: String
    private var ok = false

    @Synchronized
    fun init(c: Context) {
        if (ok) return
        val app = c.applicationContext
        name = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
        val sp = app.getSharedPreferences("qs_identity", 0)
        id = sp.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { sp.edit().putString("id", it).apply() }
        ok = true
    }
}

/** Alınan (paylaşılan) dosyaların konumu: Download klasörü. Notlarım eşitlemesi ise <ana dizin>/Link içine yazar. */
object SaveDir {
    fun dir(): File = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    /** Android 11+: "Tüm dosyalara erişim", Android 10: depolama izni verilmişse ana dizine doğrudan yazılabilir. */
    fun direct(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** mDNS (NsdManager): kendini duyurur ve çevredeki cihazları bulur. */
class Discovery(c: Context) {
    private val app = c.applicationContext
    private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var rl: NsdManager.RegistrationListener? = null
    private var dl: NsdManager.DiscoveryListener? = null
    private val q = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private val tries = HashMap<String, Int>()
    private val myName get() = "QS" + Identity.id.take(10)

    fun register(port: Int) {
        val i = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = TYPE
            this.port = port
            setAttribute("n", Identity.name)
            setAttribute("i", Identity.id)
        }
        rl = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        nsd.registerService(i, NsdManager.PROTOCOL_DNS_SD, rl)
    }

    fun browse() {
        dl = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != myName) enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) { Peers.del(s.serviceName) }
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    fun stop() {
        try { rl?.let { nsd.unregisterService(it) } } catch (e: Exception) { }
        try { dl?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
    }

    @Synchronized private fun enqueue(s: NsdServiceInfo) { q.addLast(s); pump() }

    @Synchronized private fun pump() {
        if (busy) return
        val s = q.removeFirstOrNull() ?: return
        busy = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {
                // NsdManager çözümlemesi sık sık geçici hata verir; cihaz listeden düşmesin diye 2 kez daha dene.
                synchronized(this@Discovery) {
                    val n = tries[s.serviceName] ?: 0
                    if (n < 2) { tries[s.serviceName] = n + 1; q.addLast(s) }
                }
                done()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val f = i.attributes["i"]?.toString(Charsets.UTF_8)
                val n = i.attributes["n"]?.toString(Charsets.UTF_8)
                val addr = pickAddress(i)
                if (f != null && f != Identity.id && addr != null)
                    { Peers.put(i.serviceName, Peer(f, n ?: "?", addr, i.port, i.serviceName)) }
                synchronized(this@Discovery) { tries.remove(s.serviceName) }
                done()
            }
        })
    }

    /**
     * Çözümlenen adresler arasında IPv4'ü tercih eder. NsdManager bazen IPv6 (özellikle kapsamsız fe80:: link-local)
     * döndürür; bu adrese bağlanılamaz ve cihaz listede görünür ama aktarım hiç başlamaz (Sony TV vb.).
     */
    @Suppress("DEPRECATION")
    private fun pickAddress(i: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val all = i.hostAddresses
            return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { !it.isLinkLocalAddress } ?: all.firstOrNull()
        }
        return i.host
    }

    @Synchronized private fun done() { busy = false; pump() }
}

/** TCP sunucu: ağdaki cihazlardan gelen dosyaları kabul eder. */
class Receiver(private val ctx: Context) {
    private var ss: ServerSocket? = null
    val port get() = ss!!.localPort

    fun start() {
        // Tampon, bind'dan ÖNCE ayarlanmalı; yoksa büyük TCP penceresi (hız) devreye girmez.
        val s = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = SOCK_BUF
            bind(InetSocketAddress(0))
        }
        ss = s
        thread(isDaemon = true, name = "qs-accept") {
            while (true) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "qs-rx") { handle(c) }
            }
        }
    }

    fun stop() { try { ss?.close() } catch (e: Exception) { } }

    /** Başlık satırı okur (en fazla 8 KB; sonsuz satıra karşı korumalı). */
    private fun line(i: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun reply(s: Socket, st: String) {
        s.outputStream.write("HTTP/1.1 $st\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.outputStream.flush()
    }

    private fun handle(s: Socket) {
        Hold.acquire(ctx)
        val recvError = L("Alma hatası", "Receive error")
        try {
            s.use {
                s.soTimeout = 30000
                s.tcpNoDelay = true
                // Başlıklar bayt bayt okunduğu için tamponlu akış (sistem çağrısı sayısını düşürür)
                val ins = BufferedInputStream(s.inputStream, 64 * 1024)
                val req = line(ins)
                val method = req.substringBefore(' ')
                val target = req.substringAfter(' ').substringBefore(' ')
                var len = -1L
                var name = "file"
                var sync = false          // Notlarım eşitlemesi: X-Name göreli yoldur (alt klasörlü)
                var mtime = -1L
                var isDir = false
                while (true) {
                    val l = line(ins)
                    if (l.isEmpty()) break
                    val k = l.substringBefore(':').lowercase()
                    val v = l.substringAfter(':').trim()
                    if (k == "content-length") len = v.toLongOrNull() ?: -1L
                    else if (k == "x-name") name = URLDecoder.decode(v, "UTF-8")
                    else if (k == "x-sync") sync = v == "1"
                    else if (k == "x-mtime") mtime = v.toLongOrNull() ?: -1L
                    else if (k == "x-dir") isDir = v == "1"
                }

                // Notlarım eşitleme uç noktaları (düz dosya gönderiminden bağımsız)
                if (method == "GET" && target == "/m") {
                    if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); return }
                    NoteSync.serveManifest(s); return
                }
                if (method == "GET" && target == "/f") {
                    if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); return }
                    NoteSync.serveFile(s, name); return
                }
                if (method == "DELETE" && target == "/x") {
                    if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); return }
                    NoteSync.serveDelete(s, name, isDir); return
                }
                if (method == "POST" && target == "/d") {
                    if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); return }
                    NoteSync.serveMkdir(s, name); return
                }

                if (len < 0) { reply(s, "400 Bad Request"); return }
                val syncDest: File? = if (sync) NoteSync.resolve(name) else null
                if (sync && syncDest == null) { reply(s, "400 Bad Request"); return }
                val clean = syncDest?.name ?: name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trimStart('.').take(120).ifBlank { "file" }

                // Paylaşılan dosyalar Download klasörüne, not eşitlemesi <ana dizin>/Link'e yazılır; izin yoksa ya da yazılamazsa alma reddedilir.
                if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); Bus.set(recvError); return }
                val d = (syncDest?.parentFile ?: SaveDir.dir()).also { it.mkdirs() }
                if (!d.isDirectory || d.usableSpace < len) { reply(s, "507 Insufficient Storage"); Bus.set(recvError); return }

                // Önce .part dosyasına yazılır, başarılı olunca asıl ada çevrilir (aynı adlı dosyanın üzerine yazar).
                // Aynı adlı iki aktarım birbirinin yarım dosyasını bozmasın diye .part adı benzersizdir.
                val file = File(d, "$clean.${UUID.randomUUID().toString().take(8)}.part")
                var ok = false
                try {
                    FileOutputStream(file).use { out ->
                        // Okuyucu (bu iş parçacığı) ağdan okur, yazıcı iş parçacığı diske yazar; yavaş depolama
                        // ağı bekletmez. Havuz boşalınca okuyucu doğal olarak yavaşlar (taşma yok).
                        val free = ArrayBlockingQueue<ByteArray>(SLOTS)
                        var made = 0   // tamponlar gerektikçe ayrılır: küçük dosyada 8 MB boşuna ayrılmaz
                        val full = ArrayBlockingQueue<Chunk>(SLOTS + 1)
                        val werr = AtomicBoolean(false)
                        val wr = thread(isDaemon = true, name = "qs-write") {
                            while (true) {
                                val c = try { full.take() } catch (e: InterruptedException) { break }
                                if (c.n < 0) break
                                // Hata olsa da kuyruk boşaltılmaya devam eder; okuyucu asılı kalmaz.
                                if (!werr.get()) try { out.write(c.b, 0, c.n) } catch (e: Exception) { werr.set(true) }
                                free.add(c.b)
                            }
                        }
                        var left = len
                        var last = -1
                        try {
                            while (left > 0 && !werr.get()) {
                                val b = free.poll() ?: if (made < SLOTS) { made++; ByteArray(CH) } else (free.poll(30, TimeUnit.SECONDS) ?: break)
                                val want = minOf(CH.toLong(), left).toInt()
                                var fill = 0
                                while (fill < want) {
                                    val n = ins.read(b, fill, want - fill)
                                    if (n < 0) break
                                    fill += n
                                }
                                if (fill > 0) full.put(Chunk(b, fill)) else free.add(b)
                                left -= fill
                                val p = ((len - left) * 100 / len).toInt()
                                if (p != last) last = p
                                if (fill < want) break
                            }
                        } finally {
                            full.put(Chunk(ByteArray(0), -1))
                            wr.join()   // bütün yazmalar bitmeden dosya kapanmaz / yeniden adlandırılmaz
                        }
                        ok = left == 0L && !werr.get()
                    }
                } finally {
                    if (ok) {
                        // Download'da aynı adlı dosya varsa üzerine yazılmaz: ad_1, ad_2… olarak kaydedilir
                        val dest = syncDest ?: github.idrisokys.myl.common.uniqueFile(d, clean)
                        if (sync && mtime > 0) file.setLastModified(mtime)   // zaman korunur: eşitlemede "yeni olan kazanır" bunu kullanır
                        if (sync) dest.delete()
                        if (file.renameTo(dest)) { if (!sync) MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null) }
                        else { file.delete(); ok = false }
                    } else file.delete()
                }
                reply(s, if (ok) "200 OK" else "500 Error")
                if (!ok) Bus.set(L("Alma yarım kaldı", "Transfer incomplete"))
                else if (!sync) Bus.set(L("Dosya alındı", "File received"))
            }
        } catch (e: Exception) {
            Bus.set(recvError)
        } finally {
            Hold.release()
        }
    }
}

/** Bağlantı kurma aşamasında oluşan hata (veri henüz gönderilmedi; yeniden denemek güvenli). */
private class ConnectFail(cause: Exception) : Exception(cause.message, cause)

/** Çözümlenen adresler arasında IPv4'ü tercih eder (Discovery.pickAddress ile aynı mantık). */
@Suppress("DEPRECATION")
private fun pickAddr(i: NsdServiceInfo): InetAddress? {
    if (Build.VERSION.SDK_INT >= 34) {
        val all = i.hostAddresses
        return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { !it.isLinkLocalAddress } ?: all.firstOrNull()
    }
    return i.host
}

/**
 * Cihazın adresini/portunu mDNS'ten yeniden çözer. Karşı cihaz servisi yeniden başlatınca port değişir;
 * önbellekteki eski port "connection refused" verir. En çok ~5 sn bekler, bulamazsa null döner.
 */
@Suppress("DEPRECATION")
internal fun reresolve(c: Context, old: Peer): Peer? {
    if (old.svc.isEmpty()) return null
    val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    repeat(3) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var res: Peer? = null
        val info = NsdServiceInfo().apply { serviceName = old.svc; serviceType = TYPE }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { latch.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val addr = pickAddr(i)
                    if (addr != null && i.port > 0) res = Peer(old.id, old.name, addr, i.port, old.svc)
                    latch.countDown()
                }
            })
        } catch (e: Exception) { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        res?.let { return it }
        Thread.sleep(300)   // FAILURE_ALREADY_ACTIVE vb. geçici hatalar için kısa bekleme
    }
    return null
}

object Sender {
    /**
     * Gönderimi arka plan servisine devreder; böylece paylaş ekranı hemen kapanır, aktarım
     * bildirimde ilerleme çubuğuyla sürer. Dosya okuma izni servise de verilir (ClipData + grant).
     * only verilirse sadece o cihaza, verilmezse bulunan tüm cihazlara sırayla gönderir.
     */
    fun submit(c: Context, uris: List<Uri>, only: String?) {
        if (uris.isEmpty()) return
        val clip = ClipData.newRawUri(null, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        // Not: Intent.setClipData() Intent döndürmez (void), bu yüzden zincirlenemez.
        val i = Intent(c, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_SEND
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("only", only)
        }
        c.startForegroundService(i)
    }

    /** Servisin arka plan iş parçacığında çalışır. */
    fun run(c: Context, only: String?, uris: List<Uri>) {
        val ps = Peers.list().filter { only == null || it.id == only }
        if (ps.isEmpty()) {
            report(2, L("Cihaz bulunamadı", "No device found"))
            return
        }
        Hold.acquire(c)
        try {
            // Çok dosyada her dosya için bağlantı kurma/yanıt bekleme süresi toplamı yavaşlatır: en fazla 3 paralel bağlantı.
            val tasks = ps.flatMap { p -> uris.map { u -> p to u } }
            val errs = ConcurrentLinkedQueue<String>()
            val pool = Executors.newFixedThreadPool(minOf(3, tasks.size))
            val finished = AtomicInteger(0)
            try {
                tasks.map { (p, u) ->
                    pool.submit {
                        try { one(c, p, u) }
                        catch (e: Exception) {
                            val why = e.message ?: L("bağlantı hatası", "connection error")
                            errs.add(L("Hata: ${p.name}: $why", "Error: ${p.name}: $why"))
                        } finally {
                            // Çok dosyada paylaşım penceresine "Gönderiliyor… 2/5" gibi ilerleme bildirilir.
                            val k = finished.incrementAndGet()
                            if (tasks.size > 1 && k < tasks.size)
                                ReceiverService.sendListener?.invoke(0, L("Gönderiliyor… $k/${tasks.size}", "Sending… $k/${tasks.size}"))
                        }
                    }
                }.forEach { it.get() }
            } finally { pool.shutdown() }
            val err: String? = errs.peek()
            if (err != null) report(2, err) else report(1, L("Gönderilme tamamlandı", "Transfer complete"))
        } finally { Hold.release() }
    }

    /**
     * Gönderim sonucunu bildirir. Paylaş penceresi açıksa ona (0 = sürüyor, 1 = tamamlandı, 2 = hata),
     * değilse ana ekrandaki kısa mesaj olarak gösterilir.
     */
    private fun report(state: Int, msg: String) {
        val l = ReceiverService.sendListener
        if (l != null) l(state, msg)
        else Bus.set(if (state == 1) L("Dosya gönderildi", "File sent") else msg)
    }

    private fun one(c: Context, p: Peer, u: Uri) {
        val errOpen = L("Dosya açılamadı", "Could not open file")
        val cr = c.contentResolver
        var name = "file"
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) it.getString(i)?.let { n -> name = n }
            }
        }
        val afd = cr.openAssetFileDescriptor(u, "r") ?: error(errOpen)
        var len = afd.length
        if (len < 0) {
            // Bazı sağlayıcılar uzunluğu AFD'de vermez; SIZE sütununa bak.
            cr.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) len = it.getLong(0)
            }
        }
        var spool: File? = null
        val src: InputStream
        if (len < 0) {
            // Boyut hiç bilinmiyorsa önce geçici dosyaya al, sonra boyutuyla gönder.
            afd.close()
            val t = File.createTempFile("tx", null, c.cacheDir).also { spool = it }
            try {
                (cr.openInputStream(u) ?: error(errOpen)).use { i -> t.outputStream().use { o -> i.copyTo(o, BUF) } }
            } catch (e: Exception) { t.delete(); throw e }
            len = t.length()
            src = t.inputStream()
        } else src = afd.createInputStream()
        try {
            try {
                transfer(p, name, len, src, errOpen)
            } catch (e: ConnectFail) {
                // Bağlanılamadı: büyük olasılıkla bayat adres/port. Yeniden çözüp bir kez daha dene.
                val np = reresolve(c, p)
                if (np != null && (np.port != p.port || np.host != p.host)) {
                    Peers.put(np.svc, np)
                    try { transfer(np, name, len, src, errOpen) }
                    catch (e2: ConnectFail) {
                        Peers.del(np.svc)
                        throw Exception("${np.host.hostAddress}:${np.port} → ${e2.message}")
                    }
                } else {
                    Peers.del(p.svc)   // gerçekten ulaşılamıyor: listede bayat kayıt kalmasın
                    throw Exception("${p.host.hostAddress}:${p.port} → ${e.message}")
                }
            }
        } finally {
            try { src.close() } catch (e: Exception) { }
            try { afd.close() } catch (e: Exception) { }
            spool?.delete()
        }
    }

    private fun transfer(p: Peer, name: String, len: Long, ins: InputStream, errOpen: String) {
        // Tampon, connect'ten ÖNCE ayarlanmalı (TCP pencere ölçekleme)
        val s = Socket().apply { sendBufferSize = SOCK_BUF; tcpNoDelay = true; soTimeout = 30000 }
        s.use {
            try { s.connect(InetSocketAddress(p.host, p.port), 10000) } catch (e: java.io.IOException) { throw ConnectFail(e) }
            val out = s.outputStream
            out.write(
                ("POST /u HTTP/1.1\r\nX-Name: ${URLEncoder.encode(name, "UTF-8")}\r\n" +
                    "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray()
            )
            val b = ByteArray(BUF)
            var sent = 0L
            var last = -1
            while (sent < len) {
                // Dosya gönderim sırasında büyürse protokolü bozmamak için bildirilen uzunluğu aşma.
                val n = ins.read(b, 0, minOf(b.size.toLong(), len - sent).toInt())
                if (n < 0) break
                out.write(b, 0, n)
                sent += n
                val pc = if (len > 0) (sent * 100 / len).toInt() else 0
                if (pc != last) last = pc
            }
            // Dosya kısaldıysa alıcıyı 30 sn bekletmek yerine hemen hata ver.
            require(sent == len) { errOpen }
            out.flush()
            val l = BufferedReader(InputStreamReader(s.inputStream)).readLine()
            require(l?.contains(" 200") == true) { L("Karşı cihaz aktarımı kabul etmedi", "The other device refused the transfer") }
        }
    }
}
