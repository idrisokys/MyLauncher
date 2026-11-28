package github.idrisokys.myl.files

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import github.idrisokys.myl.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Sistemin dosya seçicisinde / "Kaydet" ekranında (örn. Claude'dan dosya indirirken) sol listede
 * "Dosyalarım" olarak görünür: ana belleği gösterir, klasör açma, dosya/klasör oluşturma (kaydetme),
 * yeniden adlandırma ve silme destekler. "Tüm dosyalara erişim" izni gerekir (MyL zaten ister).
 */
class MylDocumentsProvider : DocumentsProvider() {
    private val base: File get() = Environment.getExternalStorageDirectory()
    private val rootPath: String get() = base.canonicalPath

    private val rootCols = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)
    private val docCols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun onCreate() = true

    /** Belge kimliği = dosyanın tam yolu; ana bellek dışına çıkılamaz. */
    private fun fileOf(id: String): File {
        val f = File(id)
        val p = try { f.canonicalPath } catch (e: Exception) { throw FileNotFoundException(id) }
        if (p != rootPath && !p.startsWith(rootPath + File.separator)) throw FileNotFoundException(id)
        if (!f.exists()) throw FileNotFoundException(id)
        return f
    }

    private fun idOf(f: File) = f.absolutePath

    private fun mimeOf(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    }

    private fun addRow(c: MatrixCursor, f: File) {
        var flags = 0
        if (f.isDirectory) { if (f.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE }
        else if (f.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (f.canWrite() && f.absolutePath != base.absolutePath) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            add(Document.COLUMN_DISPLAY_NAME, if (f.absolutePath == base.absolutePath) "Dosyalarım" else f.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: rootCols)
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "myl_files")
            add(Root.COLUMN_DOCUMENT_ID, idOf(base))
            add(Root.COLUMN_TITLE, "Dosyalarım")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: docCols)
        addRow(c, fileOf(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: docCols)
        fileOf(parentDocumentId).listFiles()?.forEach { addRow(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val p = File(parentDocumentId).canonicalPath; val d = File(documentId).canonicalPath
        d == p || d.startsWith(p + File.separator)
    } catch (e: Exception) { false }

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        var name = displayName.replace('/', '_').replace('\\', '_').trim().ifEmpty { "dosya" }
        val isDir = mimeType == Document.MIME_TYPE_DIR
        if (!isDir && !name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { name += ".$it" }
        }
        // Aynı ad varsa " (1)", " (2)" ... ekle
        var f = File(dir, name); var n = 1
        val stem = if (isDir || !name.contains('.')) name else name.substringBeforeLast('.')
        val ext = if (isDir || !name.contains('.')) "" else "." + name.substringAfterLast('.')
        while (f.exists()) { f = File(dir, "$stem ($n)$ext"); n++ }
        val ok = try { if (isDir) f.mkdir() else f.createNewFile() } catch (e: Exception) { false }
        if (!ok) throw FileNotFoundException("Oluşturulamadı: $name")
        context?.contentResolver?.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(AUTH(), parentDocumentId), null)
        return idOf(f)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val f = fileOf(documentId)
        val t = File(f.parentFile, displayName.replace('/', '_').trim())
        if (t.exists() || !f.renameTo(t)) throw FileNotFoundException("Yeniden adlandırılamadı")
        return idOf(t)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileOf(documentId)
        if (f.absolutePath == base.absolutePath) throw FileNotFoundException("Ana bellek silinemez")
        if (!f.deleteRecursively()) throw FileNotFoundException("Silinemedi")
    }

    private fun AUTH() = "${context!!.packageName}.documents"
}
