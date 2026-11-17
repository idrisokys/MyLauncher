package io.github.idrisokys.myl.files

import io.github.idrisokys.myl.common.*

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Tam ekran metin düzenleyici. file == null ise boş bir "Yeni metin" açar;
 * ilk kaydetmede ad sorar ve defaultDir içine yazar.
 */
class TextEditor(
    private val act: Activity,
    private var file: File?,
    private val defaultDir: File,
    private val onSaved: () -> Unit
) : Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val bg = 0xFF121212.toInt()
    private val bar = 0xFF212121.toInt()
    private val prefs = act.getSharedPreferences("p", 0)

    private var edit: EditText? = null
    private var titleV: TextView? = null
    private var statusV: TextView? = null
    private var undoB: ImageView? = null
    private var redoB: ImageView? = null

    private var modified = false
    private var applying = false
    private var wrap = true
    private var size = prefs.getFloat("tsize", 16f)

    private var saved = ""
    private var last = ""
    private var lastPush = 0L
    private val undo = ArrayList<String>()
    private val redo = ArrayList<String>()

    private fun dp(x: Int) = (x * act.resources.displayMetrics.density).toInt()

    private inner class Ed : EditText(act) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            updateStatus()
        }
    }

    private fun btn(ic: Drawable, desc: String, click: () -> Unit) = ImageView(act).apply {
        setImageDrawable(ic); setColorFilter(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = desc
        setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(bg))
            statusBarColor = Color.BLACK
            navigationBarColor = bg
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val text = try { file?.readText() ?: "" } catch (e: Exception) { "" }
        saved = text; last = text

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // ---- üst çubuk
        val top = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(4), 0, dp(4), 0) }
        top.addView(btn(Icons.back(), "Geri") { close() }, dp(48), dp(48))
        val tv = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0)
        }
        titleV = tv
        top.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn(Icons.save(), "Kaydet") { save(null) }, dp(48), dp(48))
        top.addView(btn(Icons.more(), "Diğer") { menu() }, dp(48), dp(48))
        col.addView(top, -1, dp(54))

        // ---- yazı alanı
        val ed = Ed().apply {
            setText(text)
            setTextColor(0xFFEEEEEE.toInt()); setHintTextColor(0xFF666666.toInt())
            hint = "Yazmaya başlayın…"
            typeface = Typeface.MONOSPACE
            textSize = size
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
        }
        edit = ed
        col.addView(ed, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- alt çubuk: geri al / yinele / durum
        col.addView(android.view.View(act).apply { setBackgroundColor(0xFF333333.toInt()) }, -1, dp(1))
        val bottom = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(4), 0, dp(12), 0) }
        undoB = btn(Icons.undo(), "Geri al") { doUndo() }
        redoB = btn(Icons.redo(), "Yinele") { doRedo() }
        bottom.addView(undoB, dp(44), dp(44))
        bottom.addView(redoB, dp(44), dp(44))
        val st = TextView(act).apply { setTextColor(0xFF999999.toInt()); textSize = 13f; gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxLines = 1 }
        statusV = st
        bottom.addView(st, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(bottom, -1, dp(44))

        setContentView(col)

        ed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (applying) return
                val now = e?.toString() ?: ""
                val t = System.currentTimeMillis()
                if (undo.isEmpty() || t - lastPush > 800) {
                    undo.add(last)
                    if (undo.size > 100) undo.removeAt(0)
                }
                lastPush = t; last = now; redo.clear()
                setModified(now != saved)
                updateUndo(); updateStatus()
            }
        })

        setModified(false); updateUndo(); updateStatus()
        if (file == null) {
            ed.requestFocus()
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    // ------------------------------------------------------------------ durum

    private fun setModified(m: Boolean) {
        modified = m
        titleV?.text = (file?.name ?: "Yeni metin") + if (m) " •" else ""
    }

    private fun updateUndo() {
        undoB?.alpha = if (undo.isEmpty()) 0.35f else 1f
        redoB?.alpha = if (redo.isEmpty()) 0.35f else 1f
    }

    private fun updateStatus() {
        val e = edit ?: return; val sv = statusV ?: return
        val t = e.text ?: return
        val p = e.selectionStart.coerceIn(0, t.length)
        var line = 1; var lastNl = -1
        for (i in 0 until p) if (t[i] == '\n') { line++; lastNl = i }
        sv.text = "Satır $line, Sütun ${p - lastNl} • ${t.length} karakter"
    }

    private fun applyText(t: String) {
        val e = edit ?: return
        applying = true
        e.setText(t)
        e.setSelection(t.length)
        applying = false
        last = t; lastPush = 0L
        setModified(t != saved); updateUndo(); updateStatus()
    }

    private fun doUndo() {
        if (undo.isEmpty()) return
        val prev = undo.removeAt(undo.size - 1)
        redo.add(last)
        applyText(prev)
    }

    private fun doRedo() {
        if (redo.isEmpty()) return
        val next = redo.removeAt(redo.size - 1)
        undo.add(last)
        applyText(next)
    }

    // ----------------------------------------------------------------- kaydet

    private fun save(after: (() -> Unit)?) {
        val f = file
        if (f == null) saveAs(after) else write(f, after)
    }

    private fun write(f: File, after: (() -> Unit)?) {
        try {
            f.parentFile?.mkdirs()
            val t = edit?.text?.toString() ?: ""
            f.writeText(t)
            file = f; saved = t
            setModified(false)
            onSaved()
            Notice.show(act, "Kaydedildi: ${f.name}", false)
            after?.invoke()
        } catch (e: Exception) {
            Notice.show(act, "Kaydedilemedi: ${e.message}", true)
        }
    }

    private fun saveAs(after: (() -> Unit)?) {
        val input = EditText(context).apply { setText(file?.name ?: "Yeni metin.txt"); setSingleLine(); selectAll() }
        AlertDialog.Builder(context).setTitle("Dosya adı").setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                var n = input.text.toString().trim().replace("/", "_")
                if (n.isNotEmpty()) {
                    if (!n.contains('.')) n += ".txt"
                    val dir = file?.parentFile ?: defaultDir
                    var t = File(dir, n)
                    if (t.path != file?.path) {
                        val base = t.nameWithoutExtension; val ext = if (t.extension.isEmpty()) "" else "." + t.extension
                        var i = 1
                        while (t.exists()) { t = File(dir, "${base}_$i$ext"); i++ }
                    }
                    write(t, after)
                }
            }
            .setNegativeButton("İptal", null).show()
    }

    // ------------------------------------------------------------------ menü

    private fun menu() {
        val items = arrayOf(
            "Farklı kaydet",
            "Yazı boyutunu büyüt",
            "Yazı boyutunu küçült",
            "Satır kaydırma: " + if (wrap) "açık" else "kapalı",
            "Metni paylaş"
        )
        AlertDialog.Builder(context).setItems(items) { _, w ->
            when (w) {
                0 -> saveAs(null)
                1 -> setSize(size + 2f)
                2 -> setSize(size - 2f)
                3 -> { wrap = !wrap; edit?.setHorizontallyScrolling(!wrap) }
                4 -> {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, edit?.text?.toString() ?: "")
                    act.startActivity(Intent.createChooser(i, "Paylaş"))
                }
            }
        }.show()
    }

    private fun setSize(s: Float) {
        size = s.coerceIn(10f, 32f)
        edit?.textSize = size
        prefs.edit().putFloat("tsize", size).apply()
    }

    // ------------------------------------------------------------------ çıkış

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { close() }

    private fun close() {
        if (!modified) { dismiss(); return }
        AlertDialog.Builder(context).setMessage("Değişiklikler kaydedilsin mi?")
            .setPositiveButton("Kaydet") { _, _ -> save { dismiss() } }
            .setNegativeButton("Kaydetme") { _, _ -> dismiss() }
            .setNeutralButton("İptal", null).show()
    }
}
