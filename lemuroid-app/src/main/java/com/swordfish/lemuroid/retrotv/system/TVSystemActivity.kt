package com.swordfish.lemuroid.retrotv.system

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.retrotv.RetroTvLog
import com.swordfish.lemuroid.ext.feature.savesync.SaveSyncManagerImpl
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.retrotv.pad.PhonePadServer
import androidx.compose.runtime.collectAsState
import com.swordfish.lemuroid.retrotv.update.ReleaseInfo
import com.swordfish.lemuroid.retrotv.update.Updater
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

import com.swordfish.lemuroid.retrotv.ui.InfoRow
import com.swordfish.lemuroid.retrotv.ui.RailHeader
import com.swordfish.lemuroid.retrotv.ui.tvPane
import androidx.compose.ui.focus.focusProperties
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.Panel
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.SectionTitle
import com.swordfish.lemuroid.retrotv.ui.TvButton
import com.swordfish.lemuroid.retrotv.ui.TvToggle
import com.swordfish.lemuroid.retrotv.ui.TvChoice
import com.swordfish.lemuroid.retrotv.ui.ThemeMode
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent

private enum class Section(val title: String) {
    UPDATES("Aktualizacje"),
    APPEARANCE("Wygląd"),
    BACKUP("Kopie zapisów"),
    NETWORK("Połączenie"),
    CONTROLLERS("Kontrolery"),
    LOGS("Dziennik"),
    ABOUT("O aplikacji"),
}

class TVSystemActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_UPDATE = "retrotv.start_update"
        const val EXTRA_SECTION = "retrotv.section"

        fun launchSection(
            context: Context,
            section: String,
        ) {
            context.startActivity(Intent(context, TVSystemActivity::class.java).putExtra(EXTRA_SECTION, section))
        }

        fun launchUpdate(context: Context) {
            context.startActivity(
                Intent(context, TVSystemActivity::class.java).putExtra(EXTRA_START_UPDATE, true),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        val startUpdate = savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_UPDATE, false)
        val initial = Section.values().firstOrNull { it.name == intent.getStringExtra(EXTRA_SECTION) } ?: Section.UPDATES
        setContent { RetroTvContent { SystemScreen(this, startUpdate, initial) } }
    }
}

// ---------- ekran ----------

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SystemScreen(
    activity: ComponentActivity,
    startUpdate: Boolean,
    initial: Section,
) {
    var section by remember { mutableStateOf(initial) }
    val tabs = remember { Section.values().associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { tabs.getValue(initial).requestFocus() } }

    Row(Modifier.fillMaxSize().background(K.bg)) {
        Column(
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(K.nav)
                // Wejście do menu (strzałka w lewo) zawsze na bieżącą zakładkę.
                .focusProperties { enter = { tabs.getValue(section) } }
                .tvPane()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RailHeader("System")
            Section.values().forEachIndexed { index, s ->
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                val selected = s == section
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .focusRequester(tabs.getValue(s))
                        .onFocusChanged { if (it.isFocused) section = s }
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) K.p.activeBg else Color.Transparent)
                        .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(interactionSource = source, indication = null) { section = s }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        s.title,
                        color = if (selected) K.p.activeFg else K.text,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Wersja ${BuildConfig.VERSION_NAME}",
                color = K.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight().tvPane().padding(horizontal = 32.dp, vertical = 28.dp)) {
            when (section) {
                Section.UPDATES -> UpdatesSection(activity, startUpdate)
                Section.APPEARANCE -> AppearanceSection(activity)
                Section.BACKUP -> BackupSection(activity)
                Section.NETWORK -> NetworkSection()
                Section.CONTROLLERS -> ControllersSection()
                Section.LOGS -> LogsSection()
                Section.ABOUT -> AboutSection()
            }
        }
    }
}

// ---------- Aktualizacje ----------

private sealed class UpdateState {
    object Checking : UpdateState()

    data class Ready(val release: ReleaseInfo) : UpdateState()

    data class Failed(val message: String) : UpdateState()

    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState()

    data class Downloaded(val release: ReleaseInfo, val file: File) : UpdateState()
}

@Composable
private fun UpdatesSection(
    activity: ComponentActivity,
    startUpdate: Boolean,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var autoCheck by remember { mutableStateOf(RetroTvPrefs.autoUpdateCheck(activity)) }
    var needsPermission by remember { mutableStateOf(false) }
    var autoStarted by remember { mutableStateOf(false) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state =
                try {
                    UpdateState.Ready(Updater.fetchLatest())
                } catch (e: Exception) {
                    Timber.w(e, "Sprawdzanie aktualizacji nie powiodło się")
                    UpdateState.Failed(e.message ?: "Nieznany błąd")
                }
        }
    }

    fun download(release: ReleaseInfo) {
        if (!Updater.canInstall(activity)) {
            needsPermission = true
            return
        }
        needsPermission = false
        state = UpdateState.Downloading(release, 0f)
        scope.launch {
            state =
                try {
                    val file = Updater.download(activity, release) { p -> state = UpdateState.Downloading(release, p) }
                    Updater.install(activity, file)
                    UpdateState.Downloaded(release, file)
                } catch (e: Exception) {
                    Timber.e(e, "Aktualizacja nie powiodła się")
                    UpdateState.Failed("Pobieranie nie powiodło się: ${e.message}")
                }
        }
    }

    LaunchedEffect(Unit) { check() }
    LaunchedEffect(state) {
        val s = state
        if (startUpdate && !autoStarted && s is UpdateState.Ready && s.release.isNewerThanInstalled) {
            autoStarted = true
            download(s.release)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Aktualizacje", "Nowe wersje Retro TV pobierane prosto z GitHuba")
        Panel {
            InfoRow("Zainstalowana wersja", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.RETROTV_BUILD})")
            InfoRow("Zbudowana", BuildConfig.RETROTV_BUILD_TIME)
            when (val s = state) {
                UpdateState.Checking -> InfoRow("Najnowsza wersja", "Sprawdzanie…", K.muted)
                is UpdateState.Failed -> InfoRow("Najnowsza wersja", s.message, K.coral)
                is UpdateState.Ready ->
                    if (s.release.isNewerThanInstalled) {
                        InfoRow("Najnowsza wersja", "${s.release.versionName}: dostępna aktualizacja", K.ok)
                    } else {
                        InfoRow("Najnowsza wersja", "Masz najnowszą wersję", K.ok)
                    }
                is UpdateState.Downloading -> InfoRow("Pobieranie", "${(s.progress * 100).toInt()}%", K.accent)
                is UpdateState.Downloaded -> InfoRow("Pobrano", "Potwierdź instalację w oknie systemu", K.ok)
            }
            val s = state
            if (s is UpdateState.Downloading) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(K.line),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(s.progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(K.accent),
                    )
                }
            }
        }

        val s = state
        val release =
            when (s) {
                is UpdateState.Ready -> s.release
                is UpdateState.Downloaded -> s.release
                else -> null
            }
        if (release != null && release.isNewerThanInstalled && release.notes.isNotBlank()) {
            Panel {
                Text("Co nowego w ${release.versionName}", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(release.notes, color = K.muted, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }

        if (needsPermission) {
            Panel {
                Text("Potrzebna zgoda na instalację", color = K.warn, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Android wymaga jednorazowej zgody, żeby Retro TV mogło zainstalować swoją aktualizację. " +
                        "Włącz „Zezwalaj z tego źródła”, wróć tutaj i wybierz Aktualizuj jeszcze raz.",
                    color = K.muted,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(8.dp))
                TvButton("Otwórz ustawienia zgody", onClick = { Updater.openInstallPermissionSettings(activity) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s) {
                is UpdateState.Ready ->
                    if (s.release.isNewerThanInstalled) {
                        TvButton("Aktualizuj do ${s.release.versionName}", primary = true, onClick = { download(s.release) })
                    }
                is UpdateState.Downloaded ->
                    TvButton("Zainstaluj ponownie", primary = true, onClick = { Updater.install(activity, s.file) })
                else -> {}
            }
            TvButton(
                "Sprawdź teraz",
                enabled = s !is UpdateState.Checking && s !is UpdateState.Downloading,
                onClick = { check() },
            )
        }

        Panel {
            TvToggle("Sprawdzaj aktualizacje przy uruchomieniu", autoCheck) {
                autoCheck = it
                RetroTvPrefs.setAutoUpdateCheck(activity, it)
            }
        }
    }
}

// ---------- Wygląd ----------

@Composable
private fun AppearanceSection(activity: ComponentActivity) {
    val current = remember { RetroTvTheme.mode(activity) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Wygląd", "Motyw Kartridż: jasny plastik albo grafitowa obudowa")
        Panel {
            ThemeMode.values().forEach { mode ->
                TvChoice(mode.label, mode == current) {
                    if (mode != current) {
                        RetroTvTheme.setMode(activity, mode)
                        activity.intent.putExtra(TVSystemActivity.EXTRA_SECTION, Section.APPEARANCE.name)
                        activity.recreate()
                    }
                }
            }
        }
        Text(
            "Tryb automatyczny włącza ciemny motyw między 19:00 a 7:00.",
            color = K.muted,
            fontSize = 14.sp,
        )
    }
}

// ---------- Kopie zapisów ----------

@Composable
private fun BackupSection(activity: ComponentActivity) {
    val manager = remember { SaveSyncManagerImpl(activity.applicationContext, DirectoriesManager(activity.applicationContext)) }
    var refresh by remember { mutableStateOf(0) }
    val configured = remember(refresh) { manager.isConfigured() }
    val config = remember(refresh) { runCatching { manager.getConfigInfo() }.getOrDefault("") }
    val last = remember(refresh) { runCatching { manager.getLastSyncInfo() }.getOrDefault("") }
    DisposableEffect(Unit) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
            }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Kopie zapisów", "Zapisy gier w ukrytym folderze aplikacji na Twoim Dysku Google")
        Panel {
            InfoRow("Konto Google", if (configured) config.ifBlank { "połączone" } else "niepołączone", if (configured) K.ok else K.muted)
            InfoRow("Ostatnia synchronizacja", last.ifBlank { "—" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val settings = manager.getSettingsActivity()
            TvButton(if (configured) "Zmień / odłącz konto" else "Połącz z Google", primary = !configured, onClick = {
                if (settings != null) activity.startActivity(Intent(activity, settings))
            })
            if (configured) {
                TvButton("Synchronizuj teraz", primary = true, onClick = {
                    SaveSyncWork.enqueueManualWork(activity.applicationContext)
                })
            }
        }
        Text(
            "Synchronizacja działa automatycznie po każdej grze. Logowanie Google wymaga jednorazowej konfiguracji " +
                "projektu w Google Cloud dla tej aplikacji – jeśli logowanie się nie udaje, ta konfiguracja jeszcze nie została zrobiona.",
            color = K.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

// ---------- Połączenie ----------

@Composable
private fun NetworkSection() {
    val context = LocalContext.current
    var network by remember { mutableStateOf(SystemStatus.network(context)) }
    var services by remember { mutableStateOf<List<ServiceCheck>?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        network = SystemStatus.network(context)
        services = null
        scope.launch { services = SystemStatus.checkServices() }
    }
    LaunchedEffect(Unit) { refresh() }
    val locationPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { refresh() }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Połączenie", "Stan sieci i serwerów, z których korzysta aplikacja")
        Panel {
            InfoRow(
                "Sieć",
                if (network.connected) network.type else "Brak połączenia",
                if (network.connected) K.text else K.coral,
            )
            InfoRow(
                "Internet",
                if (network.internetValidated) "Działa" else "Brak dostępu do internetu",
                if (network.internetValidated) K.ok else K.coral,
            )
            if (network.type == "Wi-Fi") {
                InfoRow(
                    "Nazwa sieci Wi-Fi",
                    network.ssid ?: if (SystemStatus.hasLocationPermission(context)) "nieznana" else "wymaga zgody na lokalizację",
                    if (network.ssid != null) K.text else K.muted,
                )
            }
            InfoRow("Adres IP telewizora", network.ipAddress ?: "—")
        }
        Panel {
            Text("Serwery", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val list = services
            if (list == null) {
                Text("Sprawdzanie…", color = K.muted, fontSize = 15.sp)
            } else {
                list.forEach {
                    InfoRow(it.name, if (it.ok) "OK · ${it.detail}" else "Niedostępny · ${it.detail}", if (it.ok) K.ok else K.coral)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Sprawdź ponownie", onClick = { refresh() })
            if (network.type == "Wi-Fi" && !SystemStatus.hasLocationPermission(context)) {
                TvButton("Pokaż nazwę sieci", onClick = { locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) })
            }
        }
        Text(
            "Android udostępnia nazwę sieci Wi-Fi tylko po zgodzie na lokalizację. Retro TV nie zbiera ani nie wysyła Twojej lokalizacji.",
            color = K.muted,
            fontSize = 13.sp,
        )
    }
}

// ---------- Kontrolery ----------

@Composable
private fun ControllersSection() {
    val context = LocalContext.current
    var list by remember { mutableStateOf(SystemStatus.controllers()) }
    val phones by PhonePadServer.phones.collectAsState()
    val ip = remember { SystemStatus.network(context).ipAddress }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("Kontrolery", "Pady Bluetooth i USB oraz telefony jako pady")
            Panel {
                Text("Pady", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (list.isEmpty()) {
                    Text(
                        "Nie wykryto żadnego pada. Sparuj pad w Ustawieniach Android TV → Piloty i akcesoria.",
                        color = K.muted,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                } else {
                    list.forEachIndexed { i, c -> InfoRow("Gracz ${i + 1}: ${c.name}", c.detail, K.ok) }
                }
            }
            Panel {
                Text("Telefony", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (phones.isEmpty()) {
                    Text("Brak połączonych telefonów.", color = K.muted, fontSize = 15.sp)
                } else {
                    phones.forEach { p ->
                        InfoRow("Telefon ${p.player}: ${p.name}", "gracz ${list.size + p.player} w grze", K.ok)
                    }
                }
                Text(
                    "W menu telefon działa jak pilot, a w grze jak pad z przyciskami danej konsoli. " +
                        "Do $MAX_PHONES_TEXT telefonów naraz.",
                    color = K.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
            Row { TvButton("Odśwież listę", onClick = { list = SystemStatus.controllers() }) }
        }
        Column(
            Modifier
                .width(230.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(K.card)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Telefon jako pad", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (ip != null) {
                val url = PhonePadServer.pairingUrl(context, ip)
                QrImage(url, Modifier.size(180.dp))
                val ssid = remember { SystemStatus.network(context).ssid }
                if (ssid != null) Text("Sieć: $ssid", color = K.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Zeskanuj aparatem telefonu w tej samej sieci Wi-Fi i obróć telefon poziomo.",
                    color = K.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Text(url, color = K.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("Brak połączenia z siecią lokalną.", color = K.coral, fontSize = 14.sp)
            }
        }
    }
}

private const val MAX_PHONES_TEXT = "4"

// ---------- Dziennik ----------

@Composable
private fun LogsSection() {
    val context = LocalContext.current
    var onlyProblems by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    val lines =
        remember(version, onlyProblems) {
            RetroTvLog.readLines(context, limit = 3000)
                .let { all -> if (onlyProblems) all.filter { it.isError || it.isWarning } else all }
                .takeLast(300)
                .reversed()
        }
    val server = remember { LogShareServer(context.applicationContext) }
    val ip = remember { SystemStatus.network(context).ipAddress }
    DisposableEffect(Unit) {
        server.start()
        onDispose { server.stop() }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("Dziennik", "Najnowsze wpisy na górze · ${lines.size} pozycji")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                TvButton(if (onlyProblems) "Wszystko" else "Tylko błędy", onClick = { onlyProblems = !onlyProblems })
                TvButton("Odśwież", onClick = { version++ })
                TvButton("Wyczyść", onClick = {
                    RetroTvLog.clear(context)
                    version++
                })
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(K.nav)
                    .padding(8.dp),
            ) {
                items(lines) { line -> LogLineRow(line) }
            }
        }
        Column(
            Modifier
                .width(230.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(K.card)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Wyślij na telefon", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (ip != null) {
                val url = server.url(ip)
                QrImage(url, Modifier.size(180.dp))
                Text(
                    "Zeskanuj aparatem telefonu w tej samej sieci Wi-Fi. Dziennik otworzy się w przeglądarce.",
                    color = K.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Text(url, color = K.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("Brak połączenia z siecią lokalną.", color = K.coral, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LogLineRow(line: RetroTvLog.Line) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var expanded by remember { mutableStateOf(false) }
    val color =
        when {
            line.isError -> K.coral
            line.isWarning -> K.warn
            else -> K.text
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) K.btn else Color.Transparent)
            .clickable(interactionSource = source, indication = null) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row {
            Text(line.time.substring(5), color = K.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                " ${line.level} ${line.tag}",
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            line.message,
            color = color,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun QrImage(
    text: String,
    modifier: Modifier,
) {
    val matrix = remember(text) { QrCode.encode(text) }
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
        val cells = matrix.width
        val cell = size.width / cells
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix.get(x, y)) {
                    drawRect(Color(0xFF24242A), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

// ---------- O aplikacji ----------

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .focusRequester(focus)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("O aplikacji")
        Panel {
            InfoRow("Aplikacja", "Retro TV ${BuildConfig.VERSION_NAME}")
            InfoRow("Pakiet", context.packageName)
            InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("Urządzenie", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("Kod źródłowy", "github.com/${BuildConfig.RETROTV_REPO}")
        }
        Panel {
            Text("Licencje", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Retro TV jest oprogramowaniem wolnym na licencji GNU GPL v3. " +
                    "Bazuje na aplikacji Lemuroid (Filippo Scognamiglio, GPLv3) i korzysta z rdzeni emulatorów libretro, " +
                    "z których każdy ma własną licencję. Czcionki Chakra Petch i Rubik: SIL Open Font License. " +
                    "Aplikacja nie zawiera żadnych gier.",
                color = K.muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
    }
}
