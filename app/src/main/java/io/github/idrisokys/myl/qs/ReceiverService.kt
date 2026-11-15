package io.github.idrisokys.myl.qs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.idrisokys.myl.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka planda dinler (yalnızca ana ekrandaki Hızlı Paylaş düğmesi açıkken). Gönderimler de burada (tek iş parçacığında,
 * sırayla) yapılır; paylaş ekranı hemen kapanabilir.
 *
 * Bildirim özelliği yoktur: ilerleme, "Durdur" düğmesi, "Dosya geldi" bildirimi kaldırıldı. Android arka plan servisi için
 * zorunlu kıldığı tek bildirim en düşük öncelikte, yazısız ve sessiz tutulur (bildirim izni de istenmez/verilmezse hiç görünmez).
 * Durum mesajları ana ekranda şeffaf yazı olarak gösterilir ([eventListener]).
 */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    companion object {
        @Volatile var running = false
        /** Alıcı açılıp kapanınca (ana ekrandaki düğmenin rengi için) çağrılır; ana thread'e dönmek dinleyenin işi. */
        @Volatile var stateListener: (() -> Unit)? = null
        /** "Dosya alındı", "Dosya gönderildi" ve hata mesajları; ana thread'e dönmek dinleyenin işi. */
        @Volatile var eventListener: ((String) -> Unit)? = null
        const val ACTION_SEND = "io.github.idrisokys.myl.qs.SEND"

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("qs_cfg", 0)
        /** Hızlı Paylaş açık mı? Varsayılan KAPALI: kullanıcı düğmeye dokunmadan hiçbir şey dinlenmez. */
        fun enabled(c: Context) = prefs(c).getBoolean("on", false)

        /** Dosya yazma izni verilmiş mi? */
        fun ready(c: Context): Boolean = SaveDir.direct(c)

        fun start(c: Context) {
            prefs(c).edit().putBoolean("on", true).apply()
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) {
            prefs(c).edit().putBoolean("on", false).apply()
            c.stopService(Intent(c, ReceiverService::class.java))
        }
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        stateListener?.invoke()
        Identity.init(this)
        // Zorunlu minimum: sessiz, en düşük öncelikli, yazısız bildirim
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("qs_run", "Hızlı Paylaş", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        val n = Notification.Builder(this, "qs_run").setSmallIcon(R.drawable.ic_qs_tile).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val it = i ?: return START_STICKY   // sistem yeniden başlatınca intent null gelir
        if (it.action == ACTION_SEND) {
            val cd = it.clipData
            val uris = (0 until (cd?.itemCount ?: 0)).mapNotNull { n -> cd?.getItemAt(n)?.uri }
            val only = it.getStringExtra("only")
            if (uris.isNotEmpty()) {
                pending.incrementAndGet()
                io.execute {
                    try { Sender.run(this, only, uris) }
                    finally {
                        // Hızlı Paylaş kapalıysa (yalnızca gönderim için açılmış servis), gönderim bitince servis de kapanır.
                        if (pending.decrementAndGet() == 0 && !enabled(this)) stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stateListener?.invoke()
        io.shutdown()
        disc?.stop(); rx?.stop()
        super.onDestroy()
    }
}
