package io.github.idrisokys.myl

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LinkCepte'nin "gönder" davranışının aynısı: metin link ise başlığı çekilip .url olarak,
 * kısa metin not (.url içinde TEXT=) olarak, 220 karakteri aşan metin .txt olarak Link klasörüne kaydedilir.
 */
object LinkSave {
    const val ADD_MAX = 220
    private const val ORDER = ".order.txt"
    private val mutex = Any()

    private val WS = Regex("\\s+")
    private val CTRL = Regex("[\\r\\n\\t]+")
    private val SEPS = Regex("\\s*[:|/\\\\]\\s*")
    private val BAD = Regex("[*?\"<>]")
    private val URL_RE = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val badTitles = listOf("just a moment", "access denied", "attention required", "403 forbidden",
        "robot check", "are you a robot", "verifying you are human", "bir dakika", "erişim engellendi")

    /** Gönder: link ise link, uzun metin .txt, kısa metin not. Arka planda çağrılmalı. */
    fun submit(dir: File, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val isUrl = extractUrl(t) != null
        val tooLong = t.length > ADD_MAX
        val singleUrl = isUrl && t.none { it.isWhitespace() }
        return if (tooLong && !singleUrl) addTextFile(dir, t) else addFromText(dir, t)
    }

    private fun clean(s: String, max: Int = 80): String =
        s.replace(CTRL, " ")
            .replace(SEPS, " - ")
            .replace(BAD, "")
            .replace(WS, " ")
            .trim().trimStart('.', ' ', '-').take(max).trimEnd { it.isHighSurrogate() }
            .let { fitBytes(it) }.trim().ifBlank { "link" }

    private fun fitBytes(s: String, limit: Int = 240): String {
        var bytes = 0; var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > limit) break
            bytes += n; i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    private fun ordered(all: List<File>, dir: File): List<String> {
        val files = all.filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".url", true) || it.name.endsWith(".txt", true)) }
        val byName = files.associateBy { it.name }
        val saved = try { File(dir, ORDER).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } } catch (e: Exception) { null } ?: emptyList()
        val ord = saved.distinct().mapNotNull { byName[it]?.name }
        val seen = ord.toHashSet()
        val rest = files.filter { it.name !in seen }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map { it.name }
        return ord + rest
    }

    private fun write(dir: File, base: String, bytes: ByteArray, max: Int = 220, ext: String = ".url"): Boolean = synchronized(mutex) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val all = dir.listFiles()?.toList() ?: return false
            val taken = all.mapTo(HashSet()) { it.name.lowercase() }
            val b = clean(base, max)
            var name = "$b$ext"; var i = 2
            while (name.lowercase() in taken) { name = "$b ($i)$ext"; i++ }
            File(dir, name).writeBytes(bytes)
            // Yeni öğe listenin sonuna eklenir; mevcut sıra korunur.
            File(dir, ORDER).writeText((ordered(all, dir) + name).joinToString("\n"))
            true
        } catch (e: Exception) { false }
    }

    private fun addLink(dir: File, title: String, url: String) =
        write(dir, title, "[InternetShortcut]\r\nURL=$url\r\n".toByteArray())

    private fun addNote(dir: File, text: String): Boolean {
        val title = text.replace(WS, " ").trim().ifBlank { "not" }
        val enc = text.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")
        return write(dir, title, "[InternetShortcut]\r\nTEXT=$enc\r\n".toByteArray())
    }

    private fun addTextFile(dir: File, text: String): Boolean {
        val title = text.replace(WS, " ").trim().ifBlank { "not" }
        return write(dir, title, text.toByteArray(Charsets.UTF_8), 100, ".txt")
    }

    private fun extractUrl(text: String): String? {
        var u = URL_RE.find(text)?.value ?: return null
        while (u.isNotEmpty()) {
            val last = u.last()
            val strip = last in ".,;:!?\"'>]}" || (last == ')' && '(' !in u)
            if (strip) u = u.dropLast(1) else break
        }
        return if (u.length > 8) u else null
    }

    private fun addFromText(dir: File, t: String): Boolean {
        val url = extractUrl(t) ?: return addNote(dir, t)
        val title = fetchTitle(url)
            ?: t.replace(url, "").lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: "link"
        return addLink(dir, title, url)
    }

    private fun fetchTitle(url: String): String? {
        var target = if (url.startsWith("http://", true)) "https://" + url.substring(7) else url
        val deadline = System.currentTimeMillis() + 7000
        repeat(5) {
            if (System.currentTimeMillis() > deadline) return null
            var conn: HttpURLConnection? = null
            try {
                val c = URL(target).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 3000; c.readTimeout = 3000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                c.setRequestProperty("Accept-Language", "tr,en;q=0.8")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    target = URL(URL(target), loc).toString()
                    if (target.startsWith("http://", true)) target = "https://" + target.substring(7)
                    return@repeat
                }
                if (code !in 200..299) return null
                return readTitle(c)
            } catch (e: Exception) {
                return null
            } finally { conn?.disconnect() }
        }
        return null
    }

    private fun readTitle(c: HttpURLConnection): String? {
        val buf = ByteArray(160_000)
        var n = 0
        c.inputStream.use { ins ->
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
                if (String(buf, maxOf(0, n - r - 8), minOf(n, r + 8), Charsets.ISO_8859_1).contains("</title>", true)) break
            }
        }
        val head = String(buf, 0, n, Charsets.ISO_8859_1)
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        val charset = try { java.nio.charset.Charset.forName(cs ?: "UTF-8") } catch (e: Exception) { Charsets.UTF_8 }
        val html = String(buf, 0, n, charset)
        val raw = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return null
        val t = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim()
        if (t.isBlank() || badTitles.any { t.startsWith(it, true) }) return null
        return t
    }
}
