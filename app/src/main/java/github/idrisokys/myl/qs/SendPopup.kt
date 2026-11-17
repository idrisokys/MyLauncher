package github.idrisokys.myl.qs

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import github.idrisokys.myl.L
import github.idrisokys.myl.R

/**
 * Hızlı Paylaş düğmesine basılı tutunca düğmenin hemen altında açılan küçük pencere.
 *
 * 1) Her basılı tutuşta liste sıfırdan yenilenir: ağ bir kez taranır, bulunan her cihazın gerçekten açık olduğu
 *    (TCP bağlantısı) doğrulanır ve liste bir kez gösterilir. Pencere açıkken sürekli/anlık kontrol YAPILMAZ;
 *    yeniden taramak için düğmeye tekrar basılı tutmak gerekir.
 * 2) Cihaza dokununca sistemin dosya seçicisi açılır; birden çok dosya seçilebilir.
 * 3) Seçim dönünce aynı pencere yeniden açılır ve altındaki satırlarda
 *    "Gönderiliyor…" ve bitince "Gönderilme tamamlandı" (ya da hata) yazar.
 *
 * Sağ üstteki eşitleme simgesi (⇄): aynı pencerede cihazlar işaretlenir ve Link klasörü eşitleme yöntemi seçilir
 * (Bu cihaza göre / Karşıdaki cihaza göre / İki cihazda aynı olsun); eşitleme [NoteSync] ile <ana dizin>/Link üzerinden yürür.
 *
 * Kullanım: ana ekran [show] çağırır, dosya seçici sonucunu [onPicked]'a iletir, kapanırken [dispose] çağırır.
 */
class SendPopup(
    private val act: Activity,
    private val anchor: () -> View,
    private val reqPick: Int,
) {
    private val h = Handler(Looper.getMainLooper())
    private val density = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var pw: PopupWindow? = null
    private var disc: Discovery? = null          // yalnızca Hızlı Paylaş kapalıyken, pencere açıkken çalışan geçici arama
    private var target: Peer? = null             // seçilen cihaz
    private var awaitingPick = false             // dosya seçici açık: pencere kapanınca temizlik yapma
    private var statusMode = false
    private var list: LinearLayout? = null
    private var shownKey = ""
    private var line1: TextView? = null          // "Gönderiliyor…"
    private var line2: TextView? = null          // "Gönderilme tamamlandı" / hata
    private var hdrTitle: TextView? = null       // eşitleme görünümünde başlık
    private var syncView = false                 // true: cihaz listesi yerine eşitleme seçenekleri
    private var syncRunning = false
    private val picked = HashSet<String>()       // eşitlenmek üzere işaretlenen cihazlar

    private var live: List<Peer>? = null         // null = tarama sürüyor; liste = doğrulanmış açık cihazlar
    private var scanGen = 0                      // eski taramanın sonucu yenisini ezmesin

    // ---------- Dışarıya açık ----------
    fun show() {
        closeNow()
        statusMode = false
        live = null
        Peers.clear()                         // önceki taramadan kalan (kapanmış olabilecek) cihazlar atılır
        startDiscovery()
        open()
        scan()
    }

    /** Dosya seçici sonucu (iptal edilirse data null). */
    fun onPicked(data: Intent?) {
        val p = target
        val uris = collect(data)
        if (!awaitingPick || p == null || uris.isEmpty()) { closeNow(); return }
        showStatus(p, uris)
    }

    fun dispose() = closeNow()

    // ---------- Cihaz arama ----------
    private fun startDiscovery() {
        stopDiscovery()
        // Alıcı servisi açık olsa bile kendi aramamız başlatılır: servisin eski bulguları Peers'tan silindi,
        // yeni arama hepsini baştan raporlar.
        try {
            Identity.init(act)
            Peers.clear()
            disc = Discovery(act).also { it.browse() }
        } catch (e: Exception) { }
    }

    /** Tek seferlik tarama: cihazlar bulunur, açık olduklarından emin olunur, liste bir kez çizilir. */
    private fun scan() {
        val gen = ++scanGen
        kotlin.concurrent.thread(isDaemon = true, name = "qs-scan") {
            val t0 = android.os.SystemClock.uptimeMillis(); var found = -1L
            while (gen == scanGen) {
                val now = android.os.SystemClock.uptimeMillis()
                if (found < 0 && Peers.list().isNotEmpty()) found = now
                if (found >= 0 && now - found >= 1000) break      // ilk cihazdan sonra diğerlerine kısa süre tanı
                if (found < 0 && now - t0 >= 3500) break          // hiç bulunamazsa vazgeç
                try { Thread.sleep(150) } catch (e: InterruptedException) { return@thread }
            }
            if (gen != scanGen) return@thread
            // Bayat kayıtları ele: her cihaza kısa bir bağlantı denemesi (paralel)
            val cand = Peers.list()
            val ok = java.util.concurrent.ConcurrentLinkedQueue<Peer>()
            cand.map { p -> kotlin.concurrent.thread(isDaemon = true) { if (reachable(p)) ok.add(p) else Peers.del(p.svc) } }
                .forEach { try { it.join(2500) } catch (e: InterruptedException) { } }
            val alive = cand.filter { it in ok }
            h.post {
                if (gen != scanGen || statusMode || pw?.isShowing != true) return@post
                stopDiscovery()                    // tarama bitti; arka planda dinleme/kontrol sürmez
                live = alive
                refreshList()
            }
        }
    }

    private fun reachable(p: Peer): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(p.host, p.port), 1500) }; true
    } catch (e: Exception) { false }

    private fun stopDiscovery() {
        try { disc?.stop() } catch (e: Exception) { }
        disc = null
    }

    // ---------- Pencere ----------
    private fun open() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(14).toFloat() }
        }
        if (!statusMode) {
            val hdr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(act).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), 0, 0, 0)
                text = if (syncView) L("Nasıl eşitlensin?", "How to sync?") else ""
            }
            hdrTitle = title
            hdr.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ic = ImageView(act).apply {
                setImageResource(R.drawable.ic_sync); setColorFilter(Color.WHITE)
                contentDescription = L("Cihazlarla eşitle", "Sync with devices")
                setPadding(dp(8), dp(8), dp(8), dp(8)); selectable(this)
                setOnClickListener { toggleSync() }
            }
            hdr.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(hdr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else hdrTitle = null
        val lst = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        list = lst
        shownKey = ""
        box.addView(lst, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (statusMode) {
            box.addView(View(act).apply { setBackgroundColor(0x33FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(4); bottomMargin = dp(4); marginStart = dp(8); marginEnd = dp(8)
                })
            // Alt satırlar: durum mesajları (yükseklik sabit ayrılır, pencere zıplamaz)
            line1 = statusLine(Color.WHITE).also { box.addView(it, statusParams()) }
            line2 = statusLine(Color.WHITE).also { box.addView(it, statusParams()) }
        } else { line1 = null; line2 = null }

        val p = PopupWindow(box, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        p.isOutsideTouchable = true
        p.elevation = dp(8).toFloat()
        p.setOnDismissListener { if (pw === p && !awaitingPick) { pw = null; cleanup() } }
        pw = p
        refreshList()                       // pencere görünmeden önce doldur
        p.showAsDropDown(anchor(), 0, dp(6))
    }

    private fun statusLine(color: Int) = TextView(act).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(26)
    }

    private fun statusParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun selectable(v: View) {
        val t = TypedValue()
        if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, t, true) && t.resourceId != 0)
            v.setBackgroundResource(t.resourceId)
    }

    /** Pencere içeriği değişince (cihaz bulundu, uzun hata mesajı) boyutu yeniler. */
    private fun resize() {
        val p = pw ?: return
        if (p.isShowing) p.update(anchor(), 0, dp(6), dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun toggleSync() {
        if (syncRunning || statusMode) return
        syncView = !syncView
        picked.clear()
        hdrTitle?.text = if (syncView) L("Nasıl eşitlensin?", "How to sync?") else ""
        shownKey = ""
        refreshList()
    }

    /** Eşitleme görünümü: cihaz kutucukları + üç yöntem düğmesi (Notlarım'daki eski eşitleme penceresiyle aynı). */
    private fun renderSync(lst: LinearLayout) {
        lst.removeAllViews()
        val peers = live
        if (peers == null || peers.isEmpty()) {
            lst.addView(TextView(act).apply {
                text = if (peers == null) L("Cihazlar aranıyor…", "Searching for devices…") else L("Açık cihaz bulunamadı", "No open device found")
                setTextColor(0xAAFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            resize(); return
        }
        val buttons = ArrayList<TextView>()
        fun upd() { buttons.forEach { it.isEnabled = picked.isNotEmpty(); it.alpha = if (picked.isNotEmpty()) 1f else 0.4f } }
        peers.forEach { p ->
            lst.addView(CheckBox(act).apply {
                text = p.name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                isChecked = p.id in picked
                setOnCheckedChangeListener { _, on -> if (on) picked.add(p.id) else picked.remove(p.id); upd() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        lst.addView(View(act).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4); bottomMargin = dp(4); marginStart = dp(8); marginEnd = dp(8)
            })
        listOf(1 to L("Bu cihaza göre", "Based on this device"),
            2 to L("Karşıdaki cihaza göre", "Based on the other device"),
            0 to L("İki cihazda aynı olsun", "Make both the same")).forEach { (m, label) ->
            val b = TextView(act).apply {
                text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                selectable(this)
                setOnClickListener { startSync(m, peers.filter { it.id in picked }) }
            }
            buttons.add(b)
            lst.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        upd()
        resize()
    }

    private fun startSync(mode: Int, targets: List<Peer>) {
        if (syncRunning || targets.isEmpty()) return
        syncRunning = true
        val lst = list
        lst?.removeAllViews()
        lst?.addView(TextView(act).apply {
            text = L("Eşitleniyor…", "Syncing…"); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        resize()
        kotlin.concurrent.thread(isDaemon = true, name = "qs-sync") {
            val r = try { NoteSync.run(act, mode, targets) } catch (e: Exception) { NoteSync.Result(mode = mode, error = e.message ?: "?") }
            val ok = r.error == null && r.errors.isEmpty() && r.peers > 0
            h.post {
                syncRunning = false
                val l = list
                if (pw?.isShowing == true && l != null && syncView) {
                    l.removeAllViews()
                    l.addView(TextView(act).apply {
                        text = r.message(); setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(12), dp(8), dp(12), dp(8))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    resize()
                    h.postDelayed({ closeNow() }, if (ok) 3000 else 5000)
                } else Toast.makeText(act, r.message(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshList() {
        val lst = list ?: return
        if (syncView && !statusMode) { renderSync(lst); return }
        val peers = if (statusMode) listOfNotNull(target) else (live ?: emptyList())
        val key = (if (live == null && !statusMode) "scan|" else "done|") + peers.joinToString("|") { it.id + ":" + it.name }
        if (key == shownKey && lst.childCount > 0) return
        shownKey = key
        lst.removeAllViews()
        if (peers.isEmpty()) {
            lst.addView(TextView(act).apply {
                text = if (live == null && !statusMode) L("Cihazlar aranıyor…", "Searching for devices…")
                       else L("Açık cihaz bulunamadı (yenilemek için tekrar basılı tut)", "No open device found (long-press again to rescan)")
                setTextColor(0xAAFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        } else {
            peers.forEach { lst.addView(deviceRow(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))) }
        }
        resize()
    }

    private fun deviceRow(p: Peer): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            if (statusMode) background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(10).toFloat() }
            else { selectable(this); setOnClickListener { pick(p) } }
        }
        row.addView(View(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF66BB6A.toInt()) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
        row.addView(TextView(act).apply {
            text = p.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    // ---------- Dosya seçimi ve gönderim ----------
    private fun pick(p: Peer) {
        target = p
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        awaitingPick = true
        scanGen++                           // süren tarama iptal
        try {
            act.startActivityForResult(i, reqPick)
            pw?.dismiss()
        } catch (e: Exception) {
            Toast.makeText(act, L("Dosya seçici açılamadı", "Could not open file picker"), Toast.LENGTH_SHORT).show()
            closeNow()
        }
    }

    @Suppress("DEPRECATION")
    private fun collect(data: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        val cd = data?.clipData
        if (cd != null) {
            for (n in 0 until cd.itemCount) cd.getItemAt(n).uri?.let { out.add(it) }
        } else data?.data?.let { out.add(it) }
        return out
    }

    private fun showStatus(p: Peer, uris: List<Uri>) {
        awaitingPick = false
        statusMode = true
        stopDiscovery()                      // cihaz kaydı Peers'ta kalır; gönderim için yeterli
        pw = null
        open()
        line1?.text = L("Gönderiliyor…", "Sending…")
        ReceiverService.sendListener = { st, msg -> h.post { onStatus(st, msg) } }
        try {
            Sender.submit(act, uris, p.id)
        } catch (e: Exception) {
            onStatus(2, L("Gönderilemedi", "Could not send"))
        }
    }

    private fun onStatus(st: Int, msg: String) {
        if (!statusMode || pw?.isShowing != true) return
        when (st) {
            0 -> line1?.text = msg
            1 -> {
                line1?.text = L("Gönderiliyor…", "Sending…")      // "3/5" ilerleme bilgisi kalmasın
                line2?.setTextColor(0xFF66BB6A.toInt())
                line2?.text = "✓ $msg"
                resize()
                h.postDelayed({ closeNow() }, 2500)
            }
            else -> {
                line2?.setTextColor(0xFFEF5350.toInt())
                line2?.text = msg
                resize()
                h.postDelayed({ closeNow() }, 4000)
            }
        }
    }

    // ---------- Kapatma ----------
    private fun closeNow() {
        val p = pw
        pw = null
        cleanup()
        try { p?.dismiss() } catch (e: Exception) { }
    }

    private fun cleanup() {
        scanGen++
        h.removeCallbacksAndMessages(null)
        stopDiscovery()
        ReceiverService.sendListener = null
        awaitingPick = false
        statusMode = false
        target = null
        syncView = false; picked.clear(); hdrTitle = null
        list = null; line1 = null; line2 = null; live = null
    }
}
