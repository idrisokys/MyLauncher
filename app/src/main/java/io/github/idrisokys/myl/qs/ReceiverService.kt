package io.github.idrisokys.myl.qs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import io.github.idrisokys.myl.L
import io.github.idrisokys.myl.R
import io.github.idrisokys.myl.files.FilesActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka planda dinler (yalnızca Hızlı Ayarlar kutucuğu açıkken). Kalıcı bildirim: bekleme / "Gönderiliyor" / "Alınıyor" + ilerleme.
 * Gönderimler de burada (tek iş parçacığında, sırayla) yapılır; paylaş ekranı hemen kapanabilir.
 */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val back = Runnable { show(L("Gönderi bekleniyor", "Waiting for files"), -1) }

    // PendingIntent ve Builder bir kez kurulur; ilerleme güncellemelerinde yeniden üretilmez.
    private val stopPi by lazy {
        PendingIntent.getService(this, 0, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    }
    private val openPi by lazy { folderPi(this, 1) }
    private val nb by lazy {
        Notification.Builder(this, "qs_run")
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(L("Hızlı Paylaş", "QuickShare"))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_qs_tile), L("Durdur", "Stop"), stopPi).build())
    }

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "io.github.idrisokys.myl.qs.STOP"
        const val ACTION_SEND = "io.github.idrisokys.myl.qs.SEND"

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("qs_cfg", 0)
        /** Kutucuk açık mı? Varsayılan KAPALI: kullanıcı kutucuğa dokunmadan hiçbir şey dinlenmez. */
        fun enabled(c: Context) = prefs(c).getBoolean("on", false)

        /** Dosya yazma izni verilmiş mi? (Bildirim izni isteğe bağlıdır; yoksa servis yine çalışır, bildirim görünmez.) */
        fun ready(c: Context): Boolean = SaveDir.direct(c)

        fun start(c: Context) {
            prefs(c).edit().putBoolean("on", true).apply()
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) {
            prefs(c).edit().putBoolean("on", false).apply()
            c.stopService(Intent(c, ReceiverService::class.java))
        }

        /** Bildirime dokununca Dosyalarım'da Link klasörünü açar. */
        private fun folderPi(c: Context, code: Int): PendingIntent {
            val i = Intent(c, FilesActivity::class.java)
                .putExtra(FilesActivity.EXTRA_PATH, SaveDir.dir().absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("qs_run", L("Bekleme", "Waiting"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel("qs_recv", L("Gelen dosyalar", "Received files"), NotificationManager.IMPORTANCE_HIGH))
        }

        /** Dosya geldiğinde ayrı, açılır bildirim. Dokununca Link klasörü açılır. */
        fun notifyReceived(c: Context, name: String) {
            val n = Notification.Builder(c, "qs_recv")
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContentTitle(L("Dosya geldi", "File received"))
                .setContentText(name)
                .setContentIntent(folderPi(c, name.hashCode()))
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java)
                .notify(1000 + (System.nanoTime() % 100000).toInt().let { if (it < 0) -it else it }, n)
        }
    }

    override fun onBind(i: Intent?) = null

    private fun show(text: String, pct: Int) {
        try {
            val n = nb.setContentText(text).setProgress(if (pct >= 0) 100 else 0, pct.coerceAtLeast(0), false).build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        } catch (e: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Identity.init(this)
        channels(this)
        startForeground(1, nb.setContentText(L("Gönderi bekleniyor", "Waiting for files")).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // Gönderme/alma ilerlemesini bildirime yansıt
        Bus.sink = { s, p ->
            h.post {
                h.removeCallbacks(back)
                if (s.isNotEmpty()) show(s, p)
                if (p < 0) h.postDelayed(back, 4000)
            }
        }

        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val it = i ?: return START_STICKY   // sistem yeniden başlatınca intent null gelir
        when (it.action) {
            ACTION_STOP -> {
                prefs(this).edit().putBoolean("on", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                val cd = it.clipData
                val uris = (0 until (cd?.itemCount ?: 0)).mapNotNull { n -> cd?.getItemAt(n)?.uri }
                val only = it.getStringExtra("only")
                if (uris.isNotEmpty()) {
                    pending.incrementAndGet()
                    io.execute {
                        try { Sender.run(this, only, uris) }
                        finally {
                            // Kutucuk kapalıysa (yalnızca gönderim için açılmış servis), gönderim bitince servis de kapanır.
                            if (pending.decrementAndGet() == 0 && !enabled(this)) stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bus.sink = null
        h.removeCallbacksAndMessages(null)
        io.shutdown()
        disc?.stop(); rx?.stop()
        super.onDestroy()
    }
}
