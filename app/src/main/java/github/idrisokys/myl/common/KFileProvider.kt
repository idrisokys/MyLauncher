package github.idrisokys.myl.common

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** XML (file_paths.xml) gerektirmeyen, tamamen Kotlin dosya sağlayıcı. */
class KFileProvider : ContentProvider() {
    companion object {
        fun uriFor(c: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority("${c.packageName}.fp")
                .appendPath(f.name).appendQueryParameter("p", f.absolutePath).build()
    }

    private fun file(u: Uri) = File(u.getQueryParameter("p") ?: throw FileNotFoundException())

    override fun onCreate() = true
    override fun getType(u: Uri): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file(u).extension.lowercase()) ?: "application/octet-stream"

    override fun query(u: Uri, proj: Array<out String>?, sel: String?, args: Array<out String>?, order: String?): Cursor {
        val f = file(u); val cols = proj ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray()
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
