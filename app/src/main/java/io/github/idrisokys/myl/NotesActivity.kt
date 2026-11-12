package io.github.idrisokys.myl

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var infoMenu by remember { mutableStateOf(false) }          // sağ üstteki bilgi (i) menüsü
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
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
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
        root = Store.root(ctx)
        stack = stack.takeWhile { java.io.File(it.uri.path ?: "").isDirectory }
        val q = searchQuery
        if (q != null) {
            searchJob?.cancel(); hits = null
            searchJob = scope.launch { hits = withContext(Dispatchers.IO) { Store.search(ctx, rootUri, q) { isActive } } }
        }
        tick++
        toast(L("Yenilendi", "Refreshed"))
    }
    fun deleteNow(items: List<Entry>) {
        selected = emptySet()
        run { items.forEach { Store.delete(ctx, it.uri) } }
    }
    fun saveRename() {
        val r = rn ?: return
        val n = renameText
        if (n.isBlank()) return
        selected = emptySet()
        editMode = false          // kayıt yapılınca düzenleme modu otomatik kapanır
        focus.clearFocus()
        scope.launch {
            val ok = lock.withLock { withContext(Dispatchers.IO) { Store.rename(ctx, current, r, n) } }
            tick++
            if (!ok) toast(L("Ad değiştirilemedi (aynı adda öğe olabilir)", "Could not rename (an item with that name may exist)"))
        }
    }

    Scaffold(bottomBar = {
        if (rn != null) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    renameText, { renameText = it.take(maxChars) },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
                        // Donanım klavyesi: Enter kaydeder, Shift+Enter yeni satır
                        if (ev.key == Key.Enter && !ev.isShiftPressed) { if (ev.type == KeyEventType.KeyDown) saveRename(); true } else false
                    },
                    label = { Text(if (rn.isDir) L("Klasör adı", "Folder name") else L("Ad", "Name")) },
                    singleLine = false, minLines = 1, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { saveRename() }))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${renameText.length}/$maxChars", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton({ clipboard.getText()?.text?.let { renameText = it.take(maxChars) } }) { Icon(Icons.Default.ContentPaste, L("Yapıştır", "Paste")) }
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
                OutlinedTextField(
                    linkText, { linkText = it },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
                        // Donanım klavyesi: Enter gönderir, Shift+Enter yeni satır
                        if (ev.key == Key.Enter && !ev.isShiftPressed) { if (ev.type == KeyEventType.KeyDown) submit(); true } else false
                    },
                    placeholder = { Text(L("Link veya metin yapıştır (Enter: kaydet)", "Paste a link or text (Enter: save)")) },
                    singleLine = false, minLines = 2, maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }))
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
                if (editMode && selected.isNotEmpty()) {
                    IconButton({ moving = list.filter { it.key in selected }; dialog = "move" }) { Icon(Icons.Default.DriveFileMove, L("Taşı", "Move")) }
                    IconButton({ deleteNow(list.filter { it.key in selected }) }) { Icon(Icons.Default.Delete, L("Sil", "Delete")) }
                }
                IconButton({ dialog = "folder" }) { Icon(Icons.Default.CreateNewFolder, L("Klasör", "Folder")) }
                // Düzenle tuşu: satırlarda seçim kutusunu açar/kapatır; tek öğe seçilince adı altta değiştirilir, ok tuşlarıyla sırası değiştirilir
                IconButton({ editMode = !editMode; selected = emptySet() }) {
                    Icon(if (editMode) Icons.Default.Done else Icons.Default.Edit, if (editMode) L("Düzenlemeyi bitir", "Done editing") else L("Düzenle", "Edit"))
                }
                IconButton({ refreshAll() }) { Icon(Icons.Default.Refresh, L("Yenile", "Refresh")) }
                // En sağdaki bilgi (i) ikonu: küçük menü açar (gemi resmi, e-posta, GitHub adresi)
                Box {
                    IconButton({ infoMenu = true }) { Icon(Icons.Default.Info, L("Bilgi", "Info")) }
                    DropdownMenu(infoMenu, { infoMenu = false }) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painterResource(R.drawable.releases_icon), null,
                                Modifier.size(36.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer { Text("sislivadi@gmail.com", style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "https://github.com/idrisokys/", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                modifier = Modifier.clickable {
                                    infoMenu = false
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/idrisokys/"))) }
                                    catch (e: Exception) { toast(L("Link açılamadı", "Could not open link")) }
                                })
                        }
                    }
                }
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
                Column(Modifier.animateItemPlacement()) {
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
                            if (editMode) Checkbox(sel, { selected = if (sel) selected - e.key else selected + e.key })
                            else Icon(if (e.isDir) Icons.Default.Folder else if (e.isTxt) Icons.Default.Description else Icons.Default.Link, null)
                        },
                        headlineContent = { Text(e.title, maxLines = 4, overflow = TextOverflow.Ellipsis) })
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
            else Toast.makeText(ctx, L("Kaydedilemedi", "Could not save"), Toast.LENGTH_SHORT).show()
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

    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                        TextField(
                            value, { value = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = screenH * 0.4f).focusRequester(focus),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
                        Spacer(Modifier.height(screenH * 0.5f))
                    }
                } else {
                    SelectionContainer {
                        Text(shown, Modifier.padding(pad).fillMaxSize().verticalScroll(readScroll).padding(16.dp),
                            onTextLayout = { layout = it })
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
                    modifier = Modifier.clickable { onOpen(h.entry) },
                    overlineContent = { Text(if (h.path.isEmpty()) L("Ana liste", "Main list") else h.path.joinToString(" › ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (h.isNote || h.entry.isTxt) Icons.Default.Description else Icons.Default.Link, null) },
                    headlineContent = { Text(h.entry.title, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (!h.isNote && h.detail.isNotBlank()) Text(h.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton({ onGoto(h.path) }) { Icon(Icons.Default.FolderOpen, L("Klasöre git", "Go to folder")) } })
                HorizontalDivider()
            }
        }
    }
}
