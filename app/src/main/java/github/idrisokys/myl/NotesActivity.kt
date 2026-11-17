package github.idrisokys.myl

import github.idrisokys.myl.common.Notice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val DarkScheme = darkColorScheme()
private val blockedSchemes = setOf("file", "content", "intent", "javascript", "android-app")

/** Launcher arama kutusundan "Notlarımda ara" ile gelen sorgu (Intent extra'sı). */
const val EXTRA_QUERY = "query"

/** Eski adıyla LinkCepte: linkleri ve notları <ana dizin>/Link klasöründe .url / .txt olarak tutar. */
class NotesActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)
    // Launcher'dan gelen arama sorgusu; NotesApp tüketince null yapılır
    private val externalQuery = mutableStateOf<String?>(null)

    private fun readQuery(i: Intent?) {
        i?.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { externalQuery.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readQuery(intent)
        setContent {
            MaterialTheme(colorScheme = DarkScheme) {
                Surface(Modifier.fillMaxSize()) {
                    NotesApp(resumes.intValue, externalQuery.value) { externalQuery.value = null }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readQuery(intent)
    }
    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesApp(resume: Int = 0, extQuery: String? = null, onExtQueryConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    var root by remember { mutableStateOf(Store.root(ctx)) }
    LaunchedEffect(resume) { root = Store.root(ctx) }   // izin verildi/kaldırıldıysa durum güncellenir
    var stack by remember { mutableStateOf(listOf<Entry>()) }
    var tick by remember { mutableIntStateOf(0) }
    var editMode by remember { mutableStateOf(false) }          // üstteki düzenle tuşu
    var selected by remember { mutableStateOf(setOf<String>()) } // düzenleme modunda işaretlenen öğeler
    var moving by remember { mutableStateOf<List<Entry>>(emptyList()) }   // taşınacak öğeler
    var dialog by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var txtDoc by remember { mutableStateOf<Pair<Entry, String>?>(null) }   // tam ekranda açık .txt (öğe, içerik)
    var linkText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf<String?>(null) }   // null = normal liste
    var hits by remember { mutableStateOf<List<Hit>?>(null) }       // null = aranıyor
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    val maxChars = 220      // ad değiştirme sınırı
    val addMax = Store.ADD_MAX   // alttaki ekleme alanı sınırı
    fun toast(s: String) = Notice.show(ctx, s, false)
    fun run(reload: Boolean = true, block: () -> Unit) {
        scope.launch { lock.withLock { withContext(Dispatchers.IO) { block() } }; if (reload) tick++ }
    }

    // Veriler sabit olarak <ana dizin>/Link içinde tutulur; klasör seçimi yoktur.
    // Tek gereken depolama izni: Android 11+ "Tüm dosyalara erişim", daha eskilerde depolama izni.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        root = Store.root(ctx); tick++
    }
    fun requestAccess() {
        if (!Store.openAccessSettings(ctx)) permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    // İzin yoksa açılışta bir kez otomatik istenir
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(root) { if (root == null && !asked) { asked = true; requestAccess() } }

    val rootUri = root
    if (rootUri == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(L("Notlarım", "My Notes"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(L("Linkler ve notlar ana dizindeki Link klasörüne kaydedilir. Bunun için depolama erişim izni gerekir.",
                "Links and notes are saved to the Link folder in the main directory. Storage access permission is required."))
            Spacer(Modifier.height(16.dp))
            Button(onClick = { requestAccess() }) { Text(L("İzin ver", "Grant permission")) }
        }
        return
    }

    val current = stack.lastOrNull()?.uri ?: rootUri
    var loaded by remember(current) { mutableStateOf<List<Entry>?>(null) }
    LaunchedEffect(current, tick, resume) { loaded = withContext(Dispatchers.IO) { Store.list(ctx, current) } }
    val list = loaded ?: emptyList()
    // Düzenleme modunda tam bir öğe seçiliyse adı alttaki geniş alanda değiştirilir
    val rn: Entry? = if (editMode && selected.size == 1) list.firstOrNull { it.key in selected } else null
    // .url kayıtlarında (link/not) ad alanı sınırsızdır; 220'yi aşarsa kayıt otomatik .txt olur. Klasör ve .txt adları 220 ile sınırlı kalır.
    val editCap = if (rn != null && !rn.isDir && !rn.isTxt) 20_000 else 220
    LaunchedEffect(rn?.key) { renameText = rn?.title ?: "" }
    LaunchedEffect(current) { selected = emptySet() }   // klasör değişince işaretler temizlenir
    BackHandler(searchQuery != null || editMode || stack.isNotEmpty()) {
        when {
            searchQuery != null -> { searchJob?.cancel(); searchQuery = null; hits = null }
            editMode -> { editMode = false; selected = emptySet() }
            else -> stack = stack.dropLast(1)
        }
    }

    // ---- Sıralama: düzenleme modunda seçili satır ok tuşlarıyla yukarı/aşağı taşınır ----
    val listState = rememberLazyListState()
    val currentDir by rememberUpdatedState(current)

    // mode: -1 bir yukarı, 1 bir aşağı, -2 başa al, 2 sona al (seçili tüm öğeler için)
    fun moveSelected(mode: Int) {
        val l = loaded ?: return
        val sel = selected
        if (sel.isEmpty()) return
        val m = l.toMutableList()
        when (mode) {
            -1 -> for (i in 1 until m.size) if (m[i].key in sel && m[i - 1].key !in sel) { val t = m[i]; m[i] = m[i - 1]; m[i - 1] = t }
            1 -> for (i in m.size - 2 downTo 0) if (m[i].key in sel && m[i + 1].key !in sel) { val t = m[i]; m[i] = m[i + 1]; m[i + 1] = t }
            -2 -> { val a = m.filter { it.key in sel }; val b = m.filter { it.key !in sel }; m.clear(); m.addAll(a + b) }
            2 -> { val a = m.filter { it.key in sel }; val b = m.filter { it.key !in sel }; m.clear(); m.addAll(b + a) }
        }
        if (m.map { it.key } == l.map { it.key }) return
        loaded = m
        val names = m.map { it.name }
        val dir = currentDir
        run(reload = false) { Store.saveOrder(ctx, dir, names) }
        // Taşınan öğe ekran dışına çıktıysa görünür kıl
        val idx = if (mode < 0) m.indexOfFirst { it.key in sel } else m.indexOfLast { it.key in sel }
        val key = m[idx].key
        scope.launch {
            delay(50)
            val info = listState.layoutInfo
            val it = info.visibleItemsInfo.firstOrNull { x -> x.key == key }
            if (it == null || it.offset < info.viewportStartOffset || it.offset + it.size > info.viewportEndOffset)
                listState.animateScrollToItem((idx - 1).coerceAtLeast(0))
        }
    }
    // Seçililer zaten en üstte/en altta toplanmışsa ilgili düğme pasif olur
    val selIdx = list.indices.filter { list[it].key in selected }
    val canUp = selIdx.withIndex().any { (n, i) -> i != n }
    val canDown = selIdx.withIndex().any { (n, i) -> i != list.size - selIdx.size + n }
    fun open(e: Entry) {
        scope.launch {
            if (e.isTxt) {
                val t = withContext(Dispatchers.IO) { Store.readPlain(ctx, e.uri) }
                if (t != null) txtDoc = e to t else toast(L("Dosya okunamadı", "Could not read file"))
                return@launch
            }
            val (u, t) = withContext(Dispatchers.IO) { Store.readLink(ctx, e.uri) }
            if (u == null) {
                // Kısa metin (not): önizleme açılmaz, tek tıklayınca metin doğrudan panoya kopyalanır
                if (t != null) { clipboard.setText(AnnotatedString(t)); toast(L("Kopyalandı", "Copied")) }
                else toast(L("Link okunamadı", "Could not read link"))
            }
            else {
                val uri = Uri.parse(u)
                // Yerel dosya / uygulama içi şemalar açılmaz (elle yazılmış veya geri yüklenmiş .url dosyalarına karşı)
                if (uri.scheme == null || uri.scheme!!.lowercase() in blockedSchemes) toast(L("Link açılamadı", "Could not open link"))
                else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (ex: Exception) { toast(L("Link açılamadı", "Could not open link")) }
            }
        }
    }
    // Satıra uzun basınca metni doğrudan panoya kopyalar (.txt: içerik, link: adres, not: metin, klasör: ad)
    fun copyEntry(e: Entry) {
        scope.launch {
            val t: String? = withContext(Dispatchers.IO) {
                when {
                    e.isDir -> e.title
                    e.isTxt -> Store.readPlain(ctx, e.uri)
                    else -> { val (u, n) = Store.readLink(ctx, e.uri); u ?: n ?: e.title }
                }
            }
            if (t != null) { clipboard.setText(AnnotatedString(t)); toast(L("Kopyalandı", "Copied")) }
            else toast(L("Kopyalanamadı", "Could not copy"))
        }
    }
    fun addTxt() {
        val text = linkText.trim()
        if (text.isEmpty()) return
        linkText = ""
        focus.clearFocus()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Store.addTextFile(ctx, current, text) }
            tick++
            if (ok) {
                val l = withContext(Dispatchers.IO) { Store.list(ctx, current) }
                loaded = l
                if (l.isNotEmpty()) listState.animateScrollToItem(0)
            }
            toast(if (ok) L("Metin dosyası eklendi", "Text file added") else L("Eklenemedi", "Could not add"))
        }
    }
    // Gönder / Enter: Store.submit ile aynı kural (link → .url, kısa metin → not, uzun metin → .txt)
    fun submit() {
        val text = linkText.trim()
        if (text.isEmpty()) return
        linkText = ""
        focus.clearFocus()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Store.submit(ctx, current, text) }
            tick++
            // Yeni kayıt en üstte eklenir: liste yenilenir ve en üste kaydırılır
            if (ok) {
                val l = withContext(Dispatchers.IO) { Store.list(ctx, current) }
                loaded = l
                if (l.isNotEmpty()) listState.animateScrollToItem(0)
            }
            toast(if (ok) L("Eklendi", "Added") else L("Eklenemedi", "Could not add"))
        }
    }
    fun startSearch(query: String = linkText) {
        val q = query.trim()
        if (q.isEmpty()) return
        linkText = q
        focus.clearFocus()
        editMode = false; selected = emptySet()
        searchJob?.cancel()
        searchQuery = q; hits = null
        searchJob = scope.launch {
            val r = withContext(Dispatchers.IO) { Store.search(ctx, rootUri, q) { isActive } }
            hits = r
        }
    }
    // Dışarıdan gelen sorgu: klasör yığınını sıfırla ve aramayı doğrudan göster
    LaunchedEffect(extQuery, rootUri) {
        val q = extQuery ?: return@LaunchedEffect
        stack = emptyList(); dialog = null; txtDoc = null
        startSearch(q)
        onExtQueryConsumed()
    }
    fun exitSearch(clearText: Boolean) {
        searchJob?.cancel()
        if (clearText && linkText.trim() == searchQuery) linkText = ""
        searchQuery = null; hits = null
    }
    // Yenile tuşu: Link klasörünü tekrar kontrol eder (silinmişse yeniden oluşturur), artık var olmayan klasörlerden çıkar, listeyi (arama açıksa aramayı) yeniden yükler
    fun refreshAll() {
        selected = emptySet()
        val refreshedRoot = Store.root(ctx)
        root = refreshedRoot
        stack = stack.takeWhile { java.io.File(it.uri.path ?: "").isDirectory }
        val q = searchQuery
        if (q != null) {
            searchJob?.cancel(); hits = null
            val searchRoot = refreshedRoot
            if (searchRoot != null) {
                searchJob = scope.launch {
                    hits = withContext(Dispatchers.IO) { Store.search(ctx, searchRoot, q) { isActive } }
                }
            }
        }
        tick++
        toast(L("Yenilendi", "Refreshed"))
    }
    // "Sabitle" / "Sabitlemeyi kaldır": hepsi sabitliyse kaldırır, değilse hepsini sabitler
    fun togglePin(items: List<Entry>) {
        if (items.isEmpty()) return
        val pin = !items.all { it.pinned }
        val names = items.map { it.name }
        val dir = current
        selected = emptySet()
        scope.launch {
            lock.withLock { withContext(Dispatchers.IO) { Store.setPinned(ctx, dir, names, pin) } }
            tick++
        }
    }
    // "Sil": öğeler silinmez, ana dizindeki gizli .onemsiz klasörüne taşınır
    fun deleteNow(items: List<Entry>) {
        selected = emptySet()
        scope.launch {
            val n = lock.withLock { withContext(Dispatchers.IO) { items.count { Store.trash(ctx, it.uri) } } }
            tick++
            toast(if (n == items.size) L(".onemsiz klasörüne taşındı ($n)", "Moved to .onemsiz ($n)") else L("$n/${items.size} öğe .onemsiz'e taşınabildi", "$n/${items.size} items moved to .onemsiz"))
        }
    }
    // "Paylaş": notlar metin olarak paylaşılır (.txt: içerik, link: adres, not: metin); klasörler paylaşılamaz
    fun shareEntries(items: List<Entry>) {
        scope.launch {
            val texts = withContext(Dispatchers.IO) {
                items.filter { !it.isDir }.mapNotNull { e ->
                    if (e.isTxt) Store.readPlain(ctx, e.uri)
                    else { val (u, t) = Store.readLink(ctx, e.uri); u ?: t ?: e.title }
                }
            }
            if (texts.isEmpty()) { toast(L("Klasörler paylaşılamaz", "Folders cannot be shared")); return@launch }
            selected = emptySet(); editMode = false
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n\n"))
            try { ctx.startActivity(Intent.createChooser(send, L("Paylaş", "Share"))) } catch (ex: Exception) { toast(L("Paylaşılamadı", "Could not share")) }
        }
    }
    fun saveRename() {
        val r = rn ?: return
        val n = renameText
        if (n.isBlank()) return
        selected = emptySet()
        editMode = false          // kayıt yapılınca düzenleme modu otomatik kapanır
        focus.clearFocus()
        scope.launch {
            val toTxt = !r.isDir && !r.isTxt && n.trim().length > maxChars
            val ok = lock.withLock {
                withContext(Dispatchers.IO) {
                    if (toTxt) {
                        // Link ise adres kaybolmasın diye metnin altına eklenir
                        val (u, _) = Store.readLink(ctx, r.uri)
                        Store.convertToTxt(ctx, current, r, if (u != null) n.trim() + "\n" + u else n.trim())
                    } else Store.rename(ctx, current, r, n)
                }
            }
            tick++
            if (!ok) toast(L("Ad değiştirilemedi (aynı adda öğe olabilir)", "Could not rename (an item with that name may exist)"))
            else if (toTxt) toast(L("Uzun olduğu için .txt olarak kaydedildi", "Too long, saved as .txt"))
        }
    }

    Scaffold(bottomBar = {
        if (rn != null) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                NativeEdit(
                    renameText, { renameText = it.take(editCap) },
                    modifier = Modifier.fillMaxWidth(),
                    hint = if (rn.isDir) L("Klasör adı", "Folder name") else L("Ad", "Name"),
                    minLines = 1, maxLines = 4, send = true, onSend = { saveRename() })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val over = renameText.length > maxChars
                    Text(if (over) "${renameText.length}/$maxChars → .txt" else "${renameText.length}/$maxChars", style = MaterialTheme.typography.labelSmall,
                        color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton({ clipboard.getText()?.text?.let { renameText = it.take(editCap) } }) { Icon(Icons.Default.ContentPaste, L("Yapıştır", "Paste")) }
                    IconButton({ saveRename() }, enabled = renameText.isNotBlank()) {
                        Icon(Icons.Default.Save, L("Kaydet", "Save"),
                            tint = if (renameText.isNotBlank()) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.38f))
                    }
                    MoveButtons(canUp, canDown) { moveSelected(it) }
                }
            }
        } else if (editMode && selected.size > 1) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("${selected.size} seçili", "${selected.size} selected"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                MoveButtons(canUp, canDown) { moveSelected(it) }
            }
        } else Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                NativeEdit(
                    linkText, { linkText = it },
                    modifier = Modifier.fillMaxWidth(),
                    hint = L("Link veya metin yapıştır (Enter: kaydet)", "Paste a link or text (Enter: save)"),
                    minLines = 2, maxLines = 8, send = true, onSend = { submit() })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val canAdd = linkText.isNotBlank()
                    Text("${linkText.length}/$addMax", style = MaterialTheme.typography.labelSmall, color = if (linkText.length > addMax) MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .combinedClickable(enabled = canAdd, onClick = { submit() }, onLongClick = { addTxt() }),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, L("Ekle (uzun bas: .txt)", "Add (long-press: .txt)"), tint = LocalContentColor.current.copy(alpha = if (canAdd) 1f else 0.38f)) }
                    IconButton({ startSearch() }, enabled = linkText.isNotBlank()) { Icon(Icons.Default.Search, L("Ara", "Search")) }
                    IconButton({ linkText = "" }, enabled = linkText.isNotEmpty()) { Icon(Icons.Default.Clear, L("Temizle", "Clear")) }
                    IconButton({ clipboard.getText()?.text?.let { linkText = it } }) { Icon(Icons.Default.ContentPaste, L("Yapıştır", "Paste")) }
                }
            }
        }
    }, topBar = {
        if (searchQuery != null) TopAppBar(
            title = {
                Text("“$searchQuery”" + (hits?.let { " · ${it.size} " + L("sonuç", "results") } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton({ exitSearch(true) }) { Icon(Icons.Default.ArrowBack, L("Aramadan çık", "Exit search")) } })
        else TopAppBar(
            title = {
                Text(
                    if (editMode && selected.isNotEmpty()) L("${selected.size} seçili", "${selected.size} selected") else if (stack.isEmpty()) L("Notlarım", "My Notes") else stack.last().name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { if (stack.isNotEmpty()) IconButton({ stack = stack.dropLast(1) }) { Icon(Icons.Default.ArrowBack, L("Geri", "Back")) } },
            actions = {
                IconButton({ dialog = "folder" }) { Icon(Icons.Default.CreateNewFolder, L("Klasör", "Folder")) }
                IconButton({ refreshAll() }) { Icon(Icons.Default.Refresh, L("Yenile", "Refresh")) }
            })
    }) { pad ->
        if (searchQuery != null) SearchResults(pad, hits, { open(it) }) { path ->
            exitSearch(true); stack = path
        } else {
        if (loaded != null && list.isEmpty()) Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(L("Burada henüz bir şey yok. Alttaki alana link veya metin yapıştırın ya da tarayıcıda Paylaş → Notlarım'ı seçin.", "Nothing here yet. Paste a link or text in the field below, or choose Share → My Notes in your browser."))
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState) {
            items(list, key = { it.key }) { e ->
                val sel = e.key in selected
                Column(Modifier.animateItem()) {
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                when {
                                    editMode -> selected = if (sel) selected - e.key else selected + e.key
                                    e.isDir -> stack = stack + e
                                    else -> open(e)
                                }
                            },
                            onLongClick = { copyEntry(e) }),
                        colors = ListItemDefaults.colors(containerColor = when {
                            sel -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }),
                        leadingContent = {
                            if (editMode) Spacer(Modifier.size(24.dp))   // düzenleme modunda kutucuk yok: yalnızca boşluk (seçim satıra dokunarak yapılır)
                            else Icon(if (e.isDir) Icons.Default.Folder else if (e.isTxt) Icons.Default.Description else Icons.Default.Link, null)
                        },
                        headlineContent = { Text(e.title, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            // Üç nokta: tek dokunuş menü (Taşı / Paylaş / Sil), basılı tutunca düzenleme modu açılır/kapanır
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).combinedClickable(
                                        onClick = { menu = true },
                                        onLongClick = { editMode = !editMode; selected = emptySet() }),
                                    Alignment.Center
                                ) { Icon(Icons.Default.MoreVert, L("Menü", "Menu")) }
                                DropdownMenu(menu, { menu = false }) {
                                    // Öğe birden çok seçilenin içindeyse işlem tüm seçilenlere uygulanır
                                    val targets = if (sel && selected.size > 1) list.filter { it.key in selected } else listOf(e)
                                    DropdownMenuItem(
                                        text = { Text(if (targets.all { it.pinned }) L("Sabitlemeyi kaldır", "Unpin") else L("Sabitle", "Pin")) },
                                        onClick = { menu = false; togglePin(targets) })
                                    DropdownMenuItem(text = { Text(L("Taşı", "Move")) }, onClick = { menu = false; moving = targets; dialog = "move" })
                                    DropdownMenuItem(text = { Text(L("Paylaş", "Share")) }, onClick = { menu = false; shareEntries(targets) })
                                    DropdownMenuItem(text = { Text(L("Sil", "Delete")) }, onClick = { menu = false; deleteNow(targets) })
                                }
                            }
                            // Sabitlenmiş öğe: üç noktanın sağında minik yuvarlak
                            if (e.pinned) Box(Modifier.padding(start = 2.dp, end = 2.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            }
                        })
                    HorizontalDivider()
                }
            }
        }
        }
    }

    txtDoc?.let { (e, text) ->
        key(e.key) {
            TxtScreen(
                title = e.title, initial = text,
                onSave = { t -> lock.withLock { withContext(Dispatchers.IO) { Store.writePlain(ctx, e.uri, t) } } },
                onCopy = { t -> clipboard.setText(AnnotatedString(t)); toast(L("Kopyalandı", "Copied")) },
                onClose = { txtDoc = null })
        }
    }

    if (dialog == "folder") {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(L("Yeni klasör", "New folder")) },
            text = { OutlinedTextField(title, { title = it }, label = { Text(L("Klasör adı", "Folder name")) }, singleLine = true) },
            confirmButton = { TextButton({ val n = title; dialog = null; run { Store.addFolder(ctx, current, n) } }, enabled = title.isNotBlank()) { Text(L("Oluştur", "Create")) } },
            dismissButton = { TextButton({ dialog = null }) { Text(L("İptal", "Cancel")) } })
    }

    if (dialog == "move") {
        val files = moving
        val exclude = files.filter { it.isDir }.map { it.uri }.toSet()   // klasör kendi içine taşınamaz
        MoveDialog(ctx, rootUri, current, exclude, { dialog = null }) { target ->
            val from = current
            dialog = null; moving = emptyList(); selected = emptySet()
            scope.launch {
                val ok = lock.withLock { withContext(Dispatchers.IO) { files.count { Store.moveEntry(ctx, it, from, target) } } }
                tick++
                toast(if (ok == files.size) L("$ok öğe taşındı", "$ok items moved") else L("$ok/${files.size} öğe taşınabildi", "$ok/${files.size} items could be moved"))
            }
        }
    }
}

/** Düzenleme modunda seçili öğeler için: başa al, yukarı, aşağı, sona al. */
@Composable
fun MoveButtons(canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit) {
    IconButton({ onMove(-2) }, enabled = canUp) { Icon(Icons.Default.VerticalAlignTop, L("Başa al", "Move to top")) }
    IconButton({ onMove(-1) }, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, L("Yukarı taşı", "Move up")) }
    IconButton({ onMove(1) }, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, L("Aşağı taşı", "Move down")) }
    IconButton({ onMove(2) }, enabled = canDown) { Icon(Icons.Default.VerticalAlignBottom, L("Sona al", "Move to bottom")) }
}

/** .txt dosyasını tam ekranda gösterir; sağ üstteki kalemle düzenlenir, kaydet ikonuyla kaydedilip okuma görünümüne dönülür. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtScreen(
    title: String, initial: String,
    onSave: suspend (String) -> Boolean,
    onCopy: (String) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(initial) }                 // dosyadaki son hâl
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(initial)) }
    var saving by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val dirty = editing && value.text != saved

    // ---- Metin içinde arama (okuma görünümü) ----
    val readScroll = rememberScrollState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cur by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val searchFocus = remember { FocusRequester() }
    // Türkçe harfleri sadeleştirir (ı/İ→i, ş→s ...); uzunluğu korur, böylece indeksler metinle aynı kalır
    fun fk(t: String) = String(CharArray(t.length) { i ->
        when (val c = t[i]) { 'ı', 'İ', 'I' -> 'i'; 'ö', 'Ö' -> 'o'; 'ü', 'Ü' -> 'u'; 'ş', 'Ş' -> 's'; 'ç', 'Ç' -> 'c'; 'ğ', 'Ğ' -> 'g'; else -> c.lowercaseChar() }
    })
    val matches = remember(saved, query, searching) {
        val q = fk(query.trim())
        if (!searching || q.isEmpty()) emptyList() else {
            val hay = fk(saved); val out = ArrayList<Int>(); var i = hay.indexOf(q)
            while (i >= 0 && out.size < 5000) { out.add(i); i = hay.indexOf(q, i + q.length) }
            out
        }
    }
    LaunchedEffect(matches) { cur = 0 }
    val qLen = query.trim().length
    val hiAll = Color(0x55FFC107); val hiCur = Color(0xCCFF9800)
    val shown = remember(saved, matches, cur, qLen) {
        if (matches.isEmpty()) AnnotatedString(saved) else buildAnnotatedString {
            append(saved)
            matches.forEachIndexed { k, st ->
                addStyle(androidx.compose.ui.text.SpanStyle(background = if (k == cur) hiCur else hiAll), st, minOf(saved.length, st + qLen))
            }
        }
    }
    fun go(d: Int) { if (matches.isNotEmpty()) cur = (cur + d + matches.size) % matches.size }
    // Geçerli eşleşmeyi ekranın üst kısmına kaydır
    LaunchedEffect(cur, matches, layout) {
        val l = layout ?: return@LaunchedEffect
        val st = matches.getOrNull(cur) ?: return@LaunchedEffect
        val top = l.getLineTop(l.getLineForOffset(st.coerceAtMost(saved.length)))
        readScroll.animateScrollTo((top - 160f).toInt().coerceAtLeast(0))
    }
    fun closeSearch() { searching = false; query = "" }
    LaunchedEffect(searching) { if (searching) { delay(100); runCatching { searchFocus.requestFocus() } } }

    fun startEdit() { searching = false; query = ""; value = TextFieldValue(saved, TextRange(saved.length)); editing = true }
    fun save() {
        if (saving) return
        saving = true
        val t = value.text
        scope.launch {
            val ok = onSave(t)
            saving = false
            if (ok) { saved = t; editing = false; askDiscard = false }
            else Notice.show(ctx, L("Kaydedilemedi", "Could not save"), false)
        }
    }
    fun back() {
        when {
            searching -> closeSearch()
            dirty -> askDiscard = true
            editing -> editing = false
            else -> onClose()
        }
    }
    LaunchedEffect(editing) { if (editing) { delay(100); runCatching { focus.requestFocus() } } }

    // Ayrı bir Dialog penceresi yerine etkinliğin kendi penceresinde tam ekran katman: Dialog penceresinde metin seçim
    // tutamaçları/araç çubuğu üstte ve kaymış çıkıyordu; kendi pencerede doğru yerde (seçili metnin altında) çıkar.
    BackHandler { back() }
    run {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = {
                        if (searching && !editing) TextField(
                            query, { query = it }, singleLine = true,
                            placeholder = { Text(L("Metinde ara", "Search in text")) },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { go(1) }))
                        else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = { IconButton({ back() }) { Icon(Icons.Default.ArrowBack, L("Geri", "Back")) } },
                    actions = {
                        if (searching && !editing) {
                            if (query.isNotBlank()) Text(
                                if (matches.isEmpty()) "0" else "${cur + 1}/${matches.size}",
                                style = MaterialTheme.typography.labelLarge)
                            IconButton({ go(-1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, L("Önceki", "Previous")) }
                            IconButton({ go(1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, L("Sonraki", "Next")) }
                            IconButton({ closeSearch() }) { Icon(Icons.Default.Close, L("Aramayı kapat", "Close search")) }
                        } else if (editing) {
                            IconButton({ save() }, enabled = !saving) {
                                Icon(Icons.Default.Save, L("Kaydet", "Save"), tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            IconButton({ onCopy(saved) }) { Icon(Icons.Default.ContentCopy, L("Kopyala", "Copy")) }
                            IconButton({ searching = true }) { Icon(Icons.Default.Search, L("Ara", "Search")) }
                            IconButton({ startEdit() }) { Icon(Icons.Default.Edit, L("Düzenle", "Edit")) }
                        }
                    })
            }) { pad ->
                if (editing) {
                    // Metin kaydırılabilir bir sütunda durur; altına boşluk eklenir, böylece imleç aşağı indikçe
                    // metin yukarı çıkar ve klavyenin üstünde rahatça düzenlenir.
                    val screenH = LocalConfiguration.current.screenHeightDp.dp
                    Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
                        NativeEdit(
                            value.text, { value = TextFieldValue(it, TextRange(it.length)) },
                            modifier = Modifier.fillMaxWidth(),
                            minHeightDp = (screenH.value * 0.4f).toInt(), plain = true, requestFocus = true)
                        Spacer(Modifier.height(screenH * 0.5f))
                    }
                } else {
                    // SelectionContainer kaydırılan sütunun İÇİNDE: seçim tutamaçları kayan metinle birlikte doğru konumda kalır
                    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(readScroll).padding(16.dp)) {
                        SelectionContainer {
                            Text(shown, Modifier.fillMaxWidth(), onTextLayout = { layout = it })
                        }
                    }
                }
            }
        }
    }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text(L("Kaydedilmemiş değişiklikler var", "Unsaved changes")) },
        text = { Text(L("Değişiklikleri kaydetmek ister misiniz?", "Do you want to save your changes?")) },
        confirmButton = { TextButton({ save() }) { Text(L("Kaydet", "Save")) } },
        dismissButton = {
            Row {
                TextButton({ askDiscard = false }) { Text(L("İptal", "Cancel")) }
                TextButton({ askDiscard = false; editing = false }) { Text(L("Kaydetme", "Discard")) }
            }
        })
}

@Composable
fun MoveDialog(ctx: android.content.Context, root: Uri, current: Uri, exclude: Set<Uri>, onDismiss: () -> Unit, onPick: (Uri) -> Unit) {
    var stack by remember { mutableStateOf(listOf<Entry>()) }
    val dir = stack.lastOrNull()?.uri ?: root
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var folders by remember(dir) { mutableStateOf(emptyList<Entry>()) }
    LaunchedEffect(dir) { folders = withContext(Dispatchers.IO) { Store.list(ctx, dir).filter { it.isDir && it.uri !in exclude } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Taşı", "Move") + ": " + (stack.lastOrNull()?.name ?: L("Ana liste", "Main list"))) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (stack.isNotEmpty()) item {
                    ListItem(modifier = Modifier.clickable { stack = stack.dropLast(1) },
                        leadingContent = { Icon(Icons.Default.ArrowBack, null) }, headlineContent = { Text(L("Üst klasör", "Parent folder")) })
                }
                item {
                    if (!creating) ListItem(modifier = Modifier.clickable { creating = true },
                        leadingContent = { Icon(Icons.Default.CreateNewFolder, null) }, headlineContent = { Text(L("Yeni klasör oluştur", "Create new folder")) })
                    else Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newName, { newName = it }, label = { Text(L("Klasör adı", "Folder name")) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = newName.isNotBlank(), onClick = {
                            val n = newName
                            val before = folders.map { it.uri }.toSet()
                            creating = false; newName = ""
                            scope.launch {
                                val all = withContext(Dispatchers.IO) { Store.addFolder(ctx, dir, n); Store.list(ctx, dir).filter { it.isDir && it.uri !in exclude } }
                                folders = all
                                all.firstOrNull { it.uri !in before }?.let { stack = stack + it }
                            }
                        }) { Text(L("Oluştur", "Create")) }
                    }
                }
                items(folders, key = { it.key }) { f ->
                    ListItem(modifier = Modifier.clickable { stack = stack + f },
                        leadingContent = { Icon(Icons.Default.Folder, null) }, headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        },
        confirmButton = { TextButton({ onPick(dir) }, enabled = dir != current) { Text(L("Buraya taşı", "Move here")) } },
        dismissButton = { TextButton(onDismiss) { Text(L("İptal", "Cancel")) } })
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResults(pad: PaddingValues, hits: List<Hit>?, onOpen: (Entry) -> Unit, onGoto: (List<Entry>) -> Unit) {
    when {
        hits == null -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        hits.isEmpty() -> Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), Alignment.Center) { Text(L("Sonuç bulunamadı.", "No results found.")) }
        else -> LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(hits, key = { it.entry.key }) { h ->
                ListItem(
                    modifier = Modifier.clickable { if (h.entry.isDir) onGoto(h.path + h.entry) else onOpen(h.entry) },
                    overlineContent = { Text(if (h.path.isEmpty()) L("Ana liste", "Main list") else h.path.joinToString(" › ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (h.entry.isDir) Icons.Default.Folder else if (h.isNote || h.entry.isTxt) Icons.Default.Description else Icons.Default.Link, null) },
                    headlineContent = { Text(h.entry.title, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (!h.isNote && h.detail.isNotBlank()) Text(h.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton({ onGoto(h.path) }) { Icon(Icons.Default.FolderOpen, L("Klasöre git", "Go to folder")) } })
                HorizontalDivider()
            }
        }
    }
}


/**
 * Sistemin kendi EditText'i. Compose TextField'ında metin seçim tutamaçları/araç çubuğu bu cihazda bir satır yukarıda çıkıyordu;
 * EditText'in seçim arayüzünü Android'in kendisi çizdiği için doğru yerde (seçili metnin altında) görünür.
 * [send]: Enter / klavyedeki Gönder → [onSend]; Shift+Enter yeni satır. [plain]: çerçevesiz (tam ekran düzenleyici).
 */
@Composable
fun NativeEdit(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "",
    minLines: Int = 1, maxLines: Int = Int.MAX_VALUE, send: Boolean = false, onSend: () -> Unit = {},
    minHeightDp: Int = 0, plain: Boolean = false, requestFocus: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val textColor = cs.onSurface.toArgb(); val hintColor = cs.onSurfaceVariant.toArgb(); val outline = cs.outline.toArgb()
    val sendNow = rememberUpdatedState(onSend)
    val changed = rememberUpdatedState(onChange)
    androidx.compose.ui.viewinterop.AndroidView(modifier = modifier, factory = { c ->
        val d = c.resources.displayMetrics.density
        android.widget.EditText(c).apply {
            background = if (plain) null else android.graphics.drawable.GradientDrawable().apply {
                setStroke((1 * d).toInt().coerceAtLeast(1), outline); cornerRadius = 4 * d
            }
            val h = (if (plain) 16 else 12) * d
            setPadding((16 * d).toInt(), h.toInt(), (16 * d).toInt(), h.toInt())
            setTextColor(textColor); setHintTextColor(hintColor); this.hint = hint
            textSize = 16f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setMinLines(minLines); setMaxLines(maxLines)
            if (minHeightDp > 0) minHeight = (minHeightDp * d).toInt()
            if (send) {
                // Çok satırlı görünür ama klavyede Enter yerine Gönder tuşu çıkar
                setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                setOnEditorActionListener { _, a, _ ->
                    if (a == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendNow.value(); true } else false
                }
                setOnKeyListener { _, code, ev ->
                    if (code == android.view.KeyEvent.KEYCODE_ENTER && !ev.isShiftPressed) {
                        if (ev.action == android.view.KeyEvent.ACTION_DOWN) sendNow.value(); true
                    } else false
                }
            } else {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            setText(value); setSelection(value.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { changed.value(s?.toString() ?: "") }
            })
            if (requestFocus) post {
                requestFocus()
                (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(this, 0)
            }
        }
    }, update = { et ->
        if (et.text.toString() != value) { et.setText(value); et.setSelection(value.length) }
        if (et.hint?.toString() != hint) et.hint = hint
    })
}
