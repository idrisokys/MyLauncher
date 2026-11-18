package github.idrisokys.myl.qs

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import github.idrisokys.myl.L
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Hızlı Paylaş gönderim oturumu (arayüzden bağımsız). Notlarım (Compose) ve Dosyalarım (View) aynı kodu kullanır:
 * ağ bir kez taranır, açık olduğu doğrulanan cihazlar [onPeers] ile bildirilir; [send] ile seçilen cihaza gönderilir.
 * Tüm geri çağrılar ana iş parçacığında gelir. Pencere kapanırken [stop] çağrılmalıdır.
 *
 * [onStatus]: state 0 = sürüyor, 1 = başarılı, 2 = hata.
 */
class QuickShareSession(
    private val ctx: Context,
    private val files: List<File>,
    private val onPeers: (List<Peer>) -> Unit,
    private val onStatus: (state: Int, text: String) -> Unit,
) {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var disc: Discovery? = null
    var sending = false
        private set

    fun start() {
        Thread {
            try {
                Identity.init(ctx)
                Peers.clear()
                disc = Discovery(ctx).also { it.browse() }
            } catch (e: Exception) { }
            val t0 = SystemClock.uptimeMillis(); var found = -1L
            while (!closed) {
                val now = SystemClock.uptimeMillis()
                if (found < 0 && Peers.list().isNotEmpty()) found = now
                if (found >= 0 && now - found >= 1000) break
                if (found < 0 && now - t0 >= 3500) break
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
            // Bayat kayıtları ele: her cihaza kısa bir bağlantı denemesi
            val alive = Peers.list().filter { p ->
                try { Socket().use { it.connect(InetSocketAddress(p.host, p.port), 1500) }; true } catch (e: Exception) { false }
            }
            try { disc?.stop() } catch (e: Exception) { }
            if (!closed) h.post { if (!closed) onPeers(alive) }
        }.start()
    }

    fun send(p: Peer) {
        if (sending) return
        sending = true
        onStatus(0, L("Gönderiliyor…", "Sending…"))
        ReceiverService.sendListener = { st, msg ->
            h.post {
                if (!closed) when (st) {
                    0 -> onStatus(0, msg)
                    1 -> { onStatus(1, "✓ $msg") }
                    else -> { sending = false; onStatus(2, msg) }
                }
            }
        }
        try { Sender.submit(ctx, files.map { Uri.fromFile(it) }, p.id) }
        catch (e: Exception) { sending = false; onStatus(2, L("Gönderilemedi", "Could not send")) }
    }

    /** Başarıda da çağrılabilir: gönderim bitti, tekrar gönderilebilir. */
    fun finishSending() { sending = false }

    fun stop() {
        closed = true
        try { disc?.stop() } catch (e: Exception) { }
        ReceiverService.sendListener = null
    }
}
