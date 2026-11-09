package com.swordfish.lemuroid.retrotv.roms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.storage.local.RetroTvFolders
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.Panel
import com.swordfish.lemuroid.retrotv.ui.RailHeader
import com.swordfish.lemuroid.retrotv.ui.tvPane
import androidx.compose.ui.focus.focusProperties
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.SectionTitle
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

private enum class RomSection(val title: String) {
    FOLDERS("Foldery z grami"),
    PHONE("Z telefonu"),
    USB("Import z pendrive’a"),
    NAS("Dysk sieciowy"),
    LINK("Z linku"),
    STORAGE("Pamięć telewizora"),
}

/** Stan długiej operacji (kopiowanie, pobieranie). */
private data class Job(val label: String, val progress: Float)

class RomManagerActivity : ComponentActivity() {
    private var onTreePicked: ((Uri) -> Unit)? = null

    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onTreePicked?.invoke(uri)
            }
            onTreePicked = null
        }

    /** Otwiera systemowe okno wyboru folderu; zwraca false, gdy telewizor go nie ma. */
    fun pickTree(onPicked: (Uri) -> Unit): Boolean {
        return try {
            onTreePicked = onPicked
            treePicker.launch(null)
            true
        } catch (e: Exception) {
            Timber.w(e, "Brak systemowego okna wyboru folderu")
            onTreePicked = null
            false
        }
    }

    private var onReadGranted: (() -> Unit)? = null

    private val readPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onReadGranted?.invoke()
            onReadGranted = null
        }

    /** Uruchamia [onReady], gdy aplikacja ma dostęp do plików; inaczej prosi o zgodę i zwraca komunikat. */
    fun withFileAccess(
        toast: (String) -> Unit,
        onReady: () -> Unit,
    ) {
        when {
            FileAccess.hasAccess(this) -> onReady()
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R -> {
                onReadGranted = onReady
                readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            FileAccess.openAllFilesSettings(this) ->
                toast("Włącz „Dostęp do wszystkich plików” dla Retro TV, wróć i wybierz ponownie.")
            else -> toast("Telewizor nie ma ekranu zgody na pliki. Z komputera: ${FileAccess.adbCommand(this)}")
        }
    }

    fun rescan() {
        // Najpierw rozpakowujemy ZIP-y z grami (wysłane z telefonu, skopiowane z pendrive’a), potem skanujemy.
        kotlin.concurrent.thread(name = "retrotv-unzip", isDaemon = true) {
            runCatching { RomFiles.expandExistingZips(applicationContext) }
            LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { RomManagerScreen(this) } }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RomManagerScreen(activity: RomManagerActivity) {
    var section by remember { mutableStateOf(RomSection.FOLDERS) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var browse by remember { mutableStateOf<BrowseRequest?>(null) }
    val openBrowser: (BrowseRequest) -> Unit = { req -> activity.withFileAccess({ message = it }) { browse = req } }
    val tabs = remember { RomSection.values().associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { tabs.getValue(RomSection.FOLDERS).requestFocus() } }

    Box(Modifier.fillMaxSize().background(K.bg)) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(220.dp).fillMaxHeight().background(K.nav)
                    .focusProperties { enter = { tabs.getValue(section) } }
                    .tvPane().padding(horizontal = 12.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RailHeader("Gry i ROM-y")
                RomSection.values().forEachIndexed { i, s ->
                    TabItem(s.title, s == section, Modifier.focusRequester(tabs.getValue(s))) { section = s }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().tvPane().padding(horizontal = 32.dp, vertical = 28.dp)) {
                when (section) {
                    RomSection.FOLDERS -> FoldersSection(activity, openBrowser) { message = it }
                    RomSection.PHONE -> PhoneSection(activity)
                    RomSection.USB -> UsbSection(activity, openBrowser, { job = it }) { message = it }
                    RomSection.NAS -> NasSection(activity, { job = it }) { message = it }
                    RomSection.LINK -> LinkSection(activity, { job = it }) { message = it }
                    RomSection.STORAGE -> StorageSection(activity) { message = it }
                }
            }
        }
        browse?.let { req ->
            FolderBrowser(req.title, req.confirm, onPick = { browse = null; req.onPick(it) }, onClose = { browse = null })
        }
        val j = job
        if (j != null) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(K.card).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(j.label, color = K.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ProgressBar(j.progress)
                    Text("${(j.progress * 100).toInt()}%", color = K.muted, fontSize = 14.sp)
                }
            }
        }
        val m = message
        if (m != null && j == null) {
            LaunchedEffect(m) {
                kotlinx.coroutines.delay(4000)
                message = null
            }
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    m,
                    color = K.p.activeFg,
                    fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(K.p.activeBg).padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private class BrowseRequest(val title: String, val confirm: String, val onPick: (File) -> Unit)

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .onFocusChanged { if (it.isFocused) onSelect() }
            .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) K.p.activeBg else if (selected) K.btn else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = if (focused) K.p.activeFg else if (selected) K.text else K.muted,
            fontSize = 15.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(K.line)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(K.accent))
    }
}

/** Wiersz listy z tekstem i przyciskami po prawej. */
@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.card).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = K.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = K.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

private val noPickerMessage =
    "Ten telewizor nie ma systemowego okna wyboru folderów. Użyj wbudowanej przeglądarki (pierwszy przycisk)."

// ---------- Foldery ----------

@Composable
private fun FoldersSection(
    activity: RomManagerActivity,
    openBrowser: (BrowseRequest) -> Unit,
    toast: (String) -> Unit,
) {
    var folders by remember { mutableStateOf(RetroTvFolders.allFolders(activity)) }
    var paths by remember { mutableStateOf(RetroTvFolders.pathFolders(activity)) }
    val main = RetroTvFolders.mainFolder(activity)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Foldery z grami", "Retro TV skanuje wszystkie te foldery i pamięć telewizora")
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ListRow("Pamięć Retro TV", "Gry zaimportowane z pendrive’a, dysku sieciowego i katalogu") {}
            }
            items(paths) { path ->
                ListRow(File(path).name.ifEmpty { path }, path) {
                    TvButton("Usuń", onClick = {
                        RetroTvFolders.removePathFolder(activity, path)
                        paths = RetroTvFolders.pathFolders(activity)
                        activity.rescan()
                        toast("Usunięto folder. Gry z niego znikną z biblioteki.")
                    })
                }
            }
            items(folders) { uri ->
                ListRow(RomFiles.folderLabel(activity, uri), if (uri == main) "folder główny" else "folder dodatkowy") {
                    TvButton("Usuń", onClick = {
                        if (uri == main) RetroTvFolders.clearMainFolder(activity) else RetroTvFolders.removeExtraFolder(activity, uri)
                        runCatching {
                            activity.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        folders = RetroTvFolders.allFolders(activity)
                        activity.rescan()
                        toast("Usunięto folder. Gry z niego znikną z biblioteki.")
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Dodaj folder", primary = true, onClick = {
                openBrowser(
                    BrowseRequest("Dodaj folder z grami", "Dodaj ten folder") { dir ->
                        RetroTvFolders.addPathFolder(activity, dir.path)
                        paths = RetroTvFolders.pathFolders(activity)
                        activity.rescan()
                        toast("Dodano folder. Skanuję gry…")
                    },
                )
            })
            TvButton("Okno systemowe", onClick = {
                val ok =
                    activity.pickTree { uri ->
                        RetroTvFolders.addExtraFolder(activity, uri.toString())
                        folders = RetroTvFolders.allFolders(activity)
                        activity.rescan()
                        toast("Dodano folder. Skanuję gry…")
                    }
                if (!ok) toast(noPickerMessage)
            })
            TvButton("Skanuj ponownie", onClick = {
                activity.rescan()
                toast("Skanuję bibliotekę…")
            })
        }
        Text(
            "Folder na pendrivie działa tylko wtedy, gdy pendrive jest podłączony. Żeby gry zostały na stałe, użyj „Import z pendrive’a”.",
            color = K.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

// ---------- Z telefonu ----------

@Composable
private fun PhoneSection(activity: RomManagerActivity) {
    val net = remember { com.swordfish.lemuroid.retrotv.system.SystemStatus.network(activity) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Z telefonu", "Wyślij pliki gier z telefonu prosto do pamięci telewizora")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val ip = net.ipAddress
            if (ip != null) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(K.card).padding(16.dp)) {
                    val serverPort by com.swordfish.lemuroid.retrotv.pad.PhonePadServer.port.collectAsState()
                    com.swordfish.lemuroid.retrotv.system.QrImage(
                        remember(serverPort) { com.swordfish.lemuroid.retrotv.pad.PhonePadServer.uploadUrl(activity, ip) },
                        Modifier.width(200.dp).height(200.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Zeskanuj kod aparatem telefonu.", color = K.text, fontSize = 15.sp)
                Text("2. Stuknij „Wybierz pliki z telefonu” i zaznacz gry.", color = K.text, fontSize = 15.sp)
                Text("3. Po wysłaniu gry pojawią się w bibliotece.", color = K.text, fontSize = 15.sp)
                if (ip != null) {
                    val livePort by com.swordfish.lemuroid.retrotv.pad.PhonePadServer.port.collectAsState()
                    val shownPort = if (livePort > 0) livePort else com.swordfish.lemuroid.retrotv.pad.PhonePadServer.httpPort(activity)
                    Text("Albo wpisz w przeglądarce telefonu: $ip:$shownPort/upload?k=${com.swordfish.lemuroid.retrotv.pad.PhonePadServer.token(activity)}", color = K.muted, fontSize = 14.sp)
                    if (livePort == 0) Text("Serwer jeszcze się uruchamia…", color = K.coral, fontSize = 14.sp)
                }
                net.ssid?.let { Text("Telefon musi być w sieci: $it", color = K.muted, fontSize = 14.sp) }
                if (ip == null) Text("Telewizor nie jest połączony z siecią lokalną.", color = K.coral, fontSize = 14.sp)
                Text("Z telefonu podłączonego jako pad: przycisk „Wyślij gry”.", color = K.muted, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("Retro TV na telefonie z Androidem: Udostępnij → Retro TV", color = K.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Kod do wpisania w telefonie: ${com.swordfish.lemuroid.retrotv.pad.PhonePadServer.token(activity)}", color = K.text, fontSize = 15.sp)
                Text("ZIP z kilkoma grami zostanie rozpakowany automatycznie.", color = K.muted, fontSize = 14.sp)
            }
        }
    }
}

// ---------- Pendrive ----------

@Composable
private fun UsbSection(
    activity: RomManagerActivity,
    openBrowser: (BrowseRequest) -> Unit,
    setJob: (Job?) -> Unit,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<RomEntry>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    fun import(entries: List<RomEntry>) {
        scope.launch {
            try {
                entries.forEachIndexed { i, e ->
                    setJob(Job("Kopiuję ${i + 1}/${entries.size}: ${e.name}", 0f))
                    RomFiles.copyIn(activity, "pendrive", e.name, e.size, { activity.contentResolver.openInputStream(Uri.parse(e.source))!! }) { p ->
                        setJob(Job("Kopiuję ${i + 1}/${entries.size}: ${e.name}", p))
                    }
                }
                activity.rescan()
                toast("Skopiowano ${entries.size} plików. Gry pojawią się w bibliotece.")
                selected = emptySet()
            } catch (e: Exception) {
                Timber.e(e, "Import z pendrive'a nie powiódł się")
                toast("Kopiowanie przerwane: ${e.message}")
            } finally {
                setJob(null)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Import z pendrive’a", "Skopiuj gry do pamięci telewizora, żeby działały bez pendrive’a")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Wybierz folder na pendrivie", primary = found == null, onClick = {
                openBrowser(
                    BrowseRequest("Folder na pendrivie", "Szukaj gier tutaj") { dir ->
                        scanning = true
                        scope.launch {
                            found = runCatching { FileAccess.scanRoms(dir) }.getOrElse { emptyList() }
                            selected = found!!.map { it.source }.toSet()
                            scanning = false
                        }
                    },
                )
            })
            TvButton("Okno systemowe", onClick = {
                val ok =
                    activity.pickTree { uri ->
                        scanning = true
                        scope.launch {
                            found = runCatching { RomFiles.scanTree(activity, uri) }.getOrElse { emptyList() }
                            selected = found!!.map { it.source }.toSet()
                            scanning = false
                        }
                    }
                if (!ok) toast(noPickerMessage)
            })
            val list = found
            if (!list.isNullOrEmpty()) {
                val chosen = list.filter { it.source in selected }
                TvButton(
                    "Kopiuj zaznaczone (${chosen.size} · ${RomFiles.formatSize(chosen.sumOf { it.size })})",
                    primary = true,
                    enabled = chosen.isNotEmpty(),
                    onClick = { import(chosen) },
                )
            }
        }
        Text("Wolne miejsce w telewizorze: ${RomFiles.formatSize(RomFiles.freeBytes(activity))}", color = K.muted, fontSize = 14.sp)
        when {
            scanning -> Text("Szukam gier…", color = K.muted, fontSize = 15.sp)
            found?.isEmpty() == true -> Text("W tym folderze nie znaleziono plików gier.", color = K.muted, fontSize = 15.sp)
            found != null ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(found!!) { e ->
                        val on = e.source in selected
                        ListRow(e.name, RomFiles.formatSize(e.size)) {
                            TvButton(if (on) "✓ Zaznaczona" else "Zaznacz", onClick = {
                                selected = if (on) selected - e.source else selected + e.source
                            })
                        }
                    }
                }
        }
    }
}

// ---------- Dysk sieciowy ----------

private object NasPrefs {
    private fun p(c: Context) = c.getSharedPreferences("retrotv", Context.MODE_PRIVATE)

    fun get(
        c: Context,
        key: String,
    ) = p(c).getString("nas_$key", "") ?: ""

    fun save(
        c: Context,
        host: String,
        share: String,
        user: String,
        pass: String,
    ) = p(c).edit().putString("nas_host", host).putString("nas_share", share).putString("nas_user", user).putString("nas_pass", pass).apply()
}

@Composable
private fun NasSection(
    activity: RomManagerActivity,
    setJob: (Job?) -> Unit,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(NasPrefs.get(activity, "host")) }
    var share by remember { mutableStateOf(NasPrefs.get(activity, "share")) }
    var user by remember { mutableStateOf(NasPrefs.get(activity, "user")) }
    var pass by remember { mutableStateOf(NasPrefs.get(activity, "pass")) }
    var source by remember { mutableStateOf<SmbSource?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RomEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun open(p: String) {
        val s = source ?: return
        loading = true
        error = null
        scope.launch {
            try {
                entries = s.list(p)
                path = p
            } catch (e: Exception) {
                Timber.w(e, "SMB: nie udało się otworzyć %s", p)
                error = "Nie udało się połączyć: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    val colors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = K.text,
            unfocusedTextColor = K.text,
            focusedBorderColor = K.coral,
            unfocusedBorderColor = K.line,
            focusedLabelColor = K.coral,
            unfocusedLabelColor = K.muted,
            cursorColor = K.accent,
        )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Dysk sieciowy", "Kopiuj gry z NAS-a albo folderu udostępnionego na komputerze (SMB)")
        if (source == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Adres (np. 192.168.1.10)") }, singleLine = true, colors = colors, modifier = Modifier.weight(1f))
                OutlinedTextField(share, { share = it }, label = { Text("Udział (np. gry)") }, singleLine = true, colors = colors, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("Użytkownik (puste = gość)") }, singleLine = true, colors = colors, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    pass,
                    { pass = it },
                    label = { Text("Hasło") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = colors,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Połącz", primary = true, enabled = host.isNotBlank() && share.isNotBlank(), onClick = {
                    NasPrefs.save(activity, host.trim(), share.trim(), user.trim(), pass)
                    source = SmbSource(host.trim(), share.trim(), user.trim(), pass)
                    open("")
                })
            }
            Text(
                "Tekst możesz wpisać pilotem albo na telefonie podłączonym jako pad (przycisk Klawiatura).",
                color = K.muted,
                fontSize = 14.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Rozłącz", onClick = {
                    source = null
                    entries = emptyList()
                    path = ""
                })
                if (path.isNotEmpty()) TvButton("W górę", onClick = { open(path.substringBeforeLast('/', "")) })
                Text("\\\\${source!!.host}\\${source!!.share}\\${path.replace('/', '\\')}", color = K.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            error?.let { Text(it, color = K.coral, fontSize = 15.sp) }
            if (loading) Text("Wczytywanie…", color = K.muted, fontSize = 15.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { e ->
                    if (e.isDirectory) {
                        ListRow(e.name, "folder") { TvButton("Otwórz", onClick = { open(e.source) }) }
                    } else {
                        ListRow(e.name, RomFiles.formatSize(e.size)) {
                            TvButton("Kopiuj", primary = true, onClick = {
                                val s = source ?: return@TvButton
                                scope.launch {
                                    try {
                                        setJob(Job("Kopiuję ${e.name}", 0f))
                                        RomFiles.copyIn(activity, "nas", e.name, e.size, { s.open(e.source) }) { p -> setJob(Job("Kopiuję ${e.name}", p)) }
                                        activity.rescan()
                                        toast("Skopiowano ${e.name}")
                                    } catch (ex: Exception) {
                                        Timber.e(ex, "SMB: kopiowanie nie powiodło się")
                                        toast("Kopiowanie nie powiodło się: ${ex.message}")
                                    } finally {
                                        setJob(null)
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

// ---------- Z linku ----------

@Composable
private fun LinkSection(
    activity: RomManagerActivity,
    setJob: (Job?) -> Unit,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.swordfish.lemuroid.retrotv.pad.PhonePadText.listener = { url = it }
        onDispose { com.swordfish.lemuroid.retrotv.pad.PhonePadText.listener = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Z linku", "Pobierz plik gry z bezpośredniego linku, np. ze strony autora, itch.io albo archive.org")
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("Adres pliku (https://…)") },
            singleLine = true,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = K.text,
                    unfocusedTextColor = K.text,
                    focusedBorderColor = K.coral,
                    unfocusedBorderColor = K.line,
                    focusedLabelColor = K.coral,
                    unfocusedLabelColor = K.muted,
                    cursorColor = K.accent,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Pobierz", primary = true, enabled = url.startsWith("http"), onClick = {
                scope.launch {
                    try {
                        setJob(Job("Łączenie…", 0f))
                        val target = LinkDownload.open(url)
                        target.response.use { r ->
                            RomFiles.copyIn(activity, "linki", target.name, target.size, { r.body!!.byteStream() }) { p ->
                                setJob(Job("Pobieram ${target.name}", p))
                            }
                        }
                        activity.rescan()
                        toast("Pobrano ${target.name}. Gra pojawi się w bibliotece.")
                        url = ""
                    } catch (e: Exception) {
                        Timber.w(e, "Pobieranie z linku nie powiodło się")
                        toast("Nie udało się pobrać: ${e.message}")
                    } finally {
                        setJob(null)
                    }
                }
            })
        }
        Text(
            "Najwygodniej wkleić link na telefonie: połącz telefon jako pad i użyj przycisku Klawiatura. " +
                "Pobieraj tylko gry, do których masz prawo – darmowe gry homebrew albo kopie własnych kartridży.",
            color = K.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

// ---------- Pamięć ----------

@Composable
private fun StorageSection(
    activity: RomManagerActivity,
    toast: (String) -> Unit,
) {
    var files by remember { mutableStateOf(RomFiles.internalFiles(activity)) }
    var confirm by remember { mutableStateOf<File?>(null) }
    val root = RomFiles.internalDir(activity)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            "Pamięć telewizora",
            "Gry w pamięci Retro TV: ${files.size} · ${RomFiles.formatSize(files.sumOf { it.length() })} · " +
                "wolne ${RomFiles.formatSize(RomFiles.freeBytes(activity))} z ${RomFiles.formatSize(RomFiles.totalBytes(activity))}",
        )
        if (files.isEmpty()) {
            Panel { Text("Nie ma jeszcze gier w pamięci. Zaimportuj je z pendrive’a, dysku sieciowego albo pobierz darmowe gry.", color = K.muted, fontSize = 15.sp, lineHeight = 22.sp) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { f ->
                val folder = f.parentFile?.relativeTo(root)?.path?.ifBlank { null }
                ListRow(f.name, listOfNotNull(RomFiles.formatSize(f.length()), folder).joinToString(" · ")) {
                    if (confirm == f) {
                        TvButton("Tak, usuń", primary = true, onClick = {
                            f.delete()
                            files = RomFiles.internalFiles(activity)
                            confirm = null
                            activity.rescan()
                            toast("Usunięto ${f.name}")
                        })
                        TvButton("Anuluj", onClick = { confirm = null })
                    } else {
                        TvButton("Usuń", onClick = { confirm = f })
                    }
                }
            }
        }
    }
}
