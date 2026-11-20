package github.idrisokys.myl.common

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import github.idrisokys.myl.R

/** Uygulamanın tek yazı tipi: Figtree (res/font/figtree.xml: Medium + Bold). */
object Fonts {
    private var normal: Typeface? = null
    private var bold: Typeface? = null

    fun normal(c: Context): Typeface = normal ?: (ResourcesCompat.getFont(c, R.font.figtree) ?: Typeface.DEFAULT).also { normal = it }

    /** Aile içindeki gerçek Bold dosyasını seçer. */
    fun bold(c: Context): Typeface = bold ?: Typeface.create(normal(c), Typeface.BOLD).also { bold = it }
}
