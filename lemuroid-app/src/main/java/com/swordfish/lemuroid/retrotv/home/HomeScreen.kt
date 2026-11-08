package com.swordfish.lemuroid.retrotv.home

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import com.swordfish.lemuroid.retrotv.pad.PhonePadText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import com.swordfish.lemuroid.retrotv.ui.tvRowPane
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.swordfish.lemuroid.lib.library.metaSystemID
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.retrotv.pad.PadDetector
import com.swordfish.lemuroid.retrotv.pad.PhonePadServer
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.tvPane
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Akcje ekranu głównego, realizowane przez MainTVActivity. */
interface HomeActions {
    fun play(game: Game)

    /** Pokój przed startem gry – goście dołączają, a gra rusza po ich wejściu. */
    fun playTeam(game: Game)

    fun restart(game: Game)

    fun toggleFavorite(game: Game)

    fun pickFolder()

    fun openRomManager()

    fun rescan()

    fun stopScan()

    fun openEmulationSettings()

    fun openControllers()

    fun openSystem()

    fun joinOnline()

    /** Usuwa plik gry; zwraca komunikat dla użytkownika. */
    fun deleteGame(game: Game): String
}

private sealed class Screen {
    object Start : Screen()

    object Search : Screen()

    data class Library(val meta: MetaSystemID) : Screen()

    data class Details(val gameId: Int, val from: Screen) : Screen()
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    busy: Boolean,
    actions: HomeActions,
) {
    val state by viewModel.home.collectAsState()
    val libraryGames by viewModel.libraryGames.collectAsState()
    val topGames by viewModel.topGames.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Start) }

    fun open(s: Screen) {
        screen = s
        val base = (s as? Screen.Details)?.from ?: s
        (base as? Screen.Library)?.let { viewModel.selectedSystem.value = it.meta }
    }
    val showDetails: (Game) -> Unit = { g -> open(Screen.Details(g.id, (screen as? Screen.Details)?.from ?: screen)) }

    var settingsOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = screen != Screen.Start && !settingsOpen) {
        val s = screen
        open(if (s is Screen.Details) s.from else Screen.Start)
    }

    Box(Modifier.fillMaxSize().background(K.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopNav(actions, onStart = { open(Screen.Start) }, onSearch = { open(Screen.Search) }, onSettings = { settingsOpen = true })
            Box(Modifier.weight(1f).fillMaxWidth().tvRowPane()) {
                when (val s = screen) {
                    Screen.Start -> StartContent(state, topGames, actions, showDetails, onOpenSystem = { open(Screen.Library(it)) }, onSearch = { open(Screen.Search) })
                    Screen.Search -> SearchContent(viewModel, state, actions, showDetails)
                    is Screen.Library -> LibraryContent(s.meta, state, libraryGames, actions, showDetails)
                    is Screen.Details -> {
                        val all = state.recent + state.favorites + topGames + (libraryGames ?: emptyList()) + viewModel.searchResults.collectAsState().value
                        val game = all.firstOrNull { it.id == s.gameId }
                        if (game == null) {
                            LaunchedEffect(s) { open(s.from) }
                        } else {
                            DetailsContent(game, state, actions) { open(s.from) }
                        }
                    }
                }
            }
        }
        if (settingsOpen) SettingsPanel(actions) { settingsOpen = false }
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Text("Wczytywanie…", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------- menu boczne ----------

/** Menu u góry: logo, sekcje, szukanie, stan padów, godzina i zębatka z ustawieniami. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun TopNav(
    actions: HomeActions,
    onStart: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    var now by remember { mutableStateOf(Date()) }
    var pads by remember { mutableStateOf(countPads()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = Date()
            pads = countPads()
        }
    }
    val phones by PhonePadServer.phones.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            // Strzałka w górę z treści zawsze trafia na „Start”.
            .focusProperties { enter = { startFocus } }
            .tvRowPane()
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Logo()
        Spacer(Modifier.width(20.dp))
        NavPill("Start", selected = true, modifier = Modifier.focusRequester(startFocus), onClick = onStart)
        NavPill("Gry i ROM-y", selected = false, onClick = actions::openRomManager)
        NavPill("Dołącz do gry", selected = false, onClick = actions::joinOnline)
        Spacer(Modifier.weight(1f))
        IconPill(androidx.compose.material.icons.Icons.Outlined.Search, "Szukaj gry", onSearch)
        Chip(if (pads == 0) "Brak pada" else if (pads == 1) "Pad podłączony" else "$pads pady", if (pads > 0) K.ok else K.muted)
        if (phones.isNotEmpty()) Chip(if (phones.size == 1) "1 telefon" else "${phones.size} telefony", K.ok)
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            color = K.muted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconPill(androidx.compose.material.icons.Icons.Outlined.Settings, "Ustawienia", onSettings)
    }
}

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(K.card),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("+", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFF7A59)))
            Spacer(Modifier.width(2.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF9B8CFF)))
        }
        Text("RETRO TV", color = K.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display, letterSpacing = 1.sp)
    }
}

@Composable
private fun NavPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .height(44.dp)
            .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) K.p.activeBg else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) K.p.activeFg else K.muted,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun IconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .size(48.dp)
            .border(3.dp, if (focused) K.coral else Color.Transparent, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(if (focused) K.p.activeBg else K.card)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = description, tint = if (focused) K.p.activeFg else K.text, modifier = Modifier.size(22.dp))
    }
}

/** Panel ustawień pod zębatką: Kontrolery, Emulacja i ekran, System. */
@Composable
private fun SettingsPanel(
    actions: HomeActions,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier
                .padding(top = 80.dp, end = 40.dp)
                .width(390.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF121212))
                .focusGroup()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "USTAWIENIA",
                color = K.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 8.dp),
            )
            SettingsItem(androidx.compose.material.icons.Icons.Outlined.SportsEsports, "Kontrolery", "Pady, telefon jako pad, kod QR", Modifier.focusRequester(first)) {
                onClose()
                actions.openControllers()
            }
            SettingsItem(androidx.compose.material.icons.Icons.Outlined.Tv, "Emulacja i ekran", "Filtry, proporcje, rdzenie") {
                onClose()
                actions.openEmulationSettings()
            }
            SettingsItem(androidx.compose.material.icons.Icons.Outlined.Tune, "System", "Aktualizacje, wygląd, kopie zapisów, dziennik") {
                onClose()
                actions.openSystem()
            }
            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(K.line))
            Text(
                "Retro TV ${com.swordfish.lemuroid.BuildConfig.VERSION_NAME}",
                color = K.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fg = if (focused) K.p.activeFg else K.text
    Row(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) K.p.activeBg else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        Column {
            Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = if (focused) Color(0xFF555555) else K.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RailLabel(text: String) {
    Text(
        text,
        color = K.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    count: Int? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) K.p.activeBg else Color.Transparent)
            .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color =
                when {
                    selected -> K.p.activeFg
                    !enabled -> K.line
                    else -> K.text
                },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text("$count", color = if (selected) K.p.activeFg else K.muted, fontSize = 13.sp)
        }
    }
}

/** Logo z ikony aplikacji (ciemny pad). */
@Composable
fun PadLogo(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF24242A)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+", color = Color(0xFFECEAE5), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFF7A70)))
            Spacer(Modifier.width(2.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8E80F0)))
        }
    }
}

// ---------- pasek górny ----------

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    state: HomeViewModel.HomeState,
    @Suppress("UNUSED_PARAMETER") onSearch: (() -> Unit)? = null,
) {
    // Pady, telefony i godzina są w menu u góry – tu tylko tytuł sekcji.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title != "Start") Text(title, color = K.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
            if (subtitle != null) Text(subtitle, color = K.muted, fontSize = 14.sp)
        }
        if (state.indexing) Chip("Skanowanie biblioteki…", K.coral)
    }
}

@Composable
private fun Chip(
    text: String,
    dot: Color?,
) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(K.card)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = K.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun countPads(): Int = PadDetector.pads().size

// ---------- Start ----------

@Composable
private fun StartContent(
    state: HomeViewModel.HomeState,
    topGames: List<Game>,
    actions: HomeActions,
    onDetails: (Game) -> Unit,
    onOpenSystem: (MetaSystemID) -> Unit,
    onSearch: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val continueGame = state.continueGame
    val empty = state.loaded && state.systems.isEmpty()
    LaunchedEffect(continueGame?.id, empty) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val totalMs = PlayStats.totalMs(LocalContext.current)
        TopBar("Start", if (totalMs > 60_000) "Łączny czas gry: ${PlayStats.format(totalMs)}" else null, state, onSearch = onSearch)

        if (empty) {
            EmptyLibrary(state, actions, firstFocus)
        }

        if (!empty && continueGame != null) {
            ContinueCard(continueGame, actions, firstFocus, onDetails)
        }

        val recent = state.recent.filter { it.id != continueGame?.id }
        if (!empty && recent.isNotEmpty()) GameRow("Ostatnio grane", recent, actions, onDetails)
        if (!empty && state.favorites.isNotEmpty()) GameRow("Ulubione", state.favorites, actions, onDetails)
        if (!empty && topGames.size >= 2) GameRow("Najczęściej grane", topGames, actions, onDetails)

        if (!empty && state.systems.isNotEmpty()) {
            Text("Twoje konsole", color = K.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val context = LocalContext.current
            state.systems.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rowItems.forEachIndexed { i, info ->
                        val focusMod =
                            if (continueGame == null && rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        ConsoleCard(info, context, Modifier.weight(1f).then(focusMod)) { onOpenSystem(info.metaSystem) }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    state: HomeViewModel.HomeState,
    actions: HomeActions,
    focus: FocusRequester,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(K.card)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PadLogo(Modifier.size(56.dp))
        Text(
            if (state.indexing) "Szukam gier w wybranym folderze…" else "Dodaj swoje gry",
            color = K.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RetroTvTheme.display,
        )
        Text(
            "Wskaż folder z plikami gier (ROM): w pamięci telewizora, na pendrivie albo na dysku sieciowym. " +
                "Retro TV sam rozpozna konsole i pobierze okładki. Gry z podfolderów też zostaną znalezione.",
            color = K.muted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Wybierz folder", primary = true, onClick = actions::pickFolder, modifier = Modifier.focusRequester(focus))
            TvButton("Pobierz darmowe gry", onClick = actions::openRomManager)
            TvButton("Podłącz pada", onClick = actions::openControllers)
        }
    }
}

@Composable
private fun ContinueCard(
    game: Game,
    actions: HomeActions,
    focus: FocusRequester,
    onDetails: (Game) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(K.card),
    ) {
        Cover(game, Modifier.width(380.dp).fillMaxHeight(), titleSize = 24)
        Column(
            Modifier.weight(1f).padding(horizontal = 26.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("KONTYNUUJ", color = K.coral, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                game.displayName(),
                color = K.text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RetroTvTheme.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${systemName(context, game)} · ${lastPlayed(game)}", color = K.muted, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Graj", primary = true, onClick = { actions.play(game) }, modifier = Modifier.focusRequester(focus))
                TvButton("Z drużyną", onClick = { actions.playTeam(game) })
                TvButton("Od nowa", onClick = { actions.restart(game) })
                TvButton("Szczegóły", onClick = { onDetails(game) })
            }
        }
    }
}

@Composable
private fun GameRow(
    title: String,
    games: List<Game>,
    actions: HomeActions,
    onDetails: (Game) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = K.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            items(games, key = { it.id }) { game -> GameTile(game, actions, onDetails, Modifier.width(150.dp)) }
        }
    }
}

@Composable
private fun ConsoleCard(
    info: MetaSystemInfo,
    context: Context,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .height(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(K.card)
            .border(3.dp, if (focused) K.coral else K.line.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(info.metaSystem.imageResId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(K.text),
            modifier = Modifier.size(54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(info.getName(context), color = K.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(gamesCount(info.count), color = K.muted, fontSize = 13.sp)
        }
    }
}

// ---------- Biblioteka ----------

@Composable
private fun LibraryContent(
    meta: MetaSystemID,
    state: HomeViewModel.HomeState,
    games: List<Game>?,
    actions: HomeActions,
    onDetails: (Game) -> Unit,
) {
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(meta, games?.isNotEmpty()) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 12.dp)) {
        TopBar(context.getString(meta.titleResId), games?.let { gamesCount(it.size) }, state)
        Spacer(Modifier.height(16.dp))
        if (games == null) {
            Text("Wczytywanie…", color = K.muted, fontSize = 16.sp)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                items(games, key = { it.id }) { game ->
                    val mod = if (game.id == games.first().id) Modifier.focusRequester(firstFocus) else Modifier
                    GameTile(game, actions, onDetails, mod)
                }
            }
        }
        HintBar()
    }
}

@Composable
private fun HintBar() {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hint("OK / A", "Graj")
        Hint("Y / Menu", "Szczegóły")
        Hint("Wstecz / B", "Start")
    }
}

@Composable
private fun Hint(
    key: String,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(K.btn).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(key, color = K.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = K.muted, fontSize = 13.sp)
    }
}

// ---------- kafelek gry ----------

@Composable
private fun GameTile(
    game: Game,
    actions: HomeActions,
    onDetails: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(
        modifier
            .scale(if (focused) 1.05f else 1f)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if ((code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_MENU) &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_UP
                ) {
                    onDetails(game)
                    true
                } else {
                    code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_MENU
                }
            }
            .clickable(interactionSource = source, indication = null) { actions.play(game) },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            Cover(game, Modifier.fillMaxSize(), titleSize = 15)
            if (game.isFavorite) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC24242A))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("★", color = Color(0xFFFFD43B), fontSize = 13.sp) }
            }
        }
        Text(
            game.displayName(),
            color = K.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
    }
}

/** Okładka gry albo kolorowy zastępnik z tytułem, gdy okładki brak. */
@Composable
private fun Cover(
    game: Game,
    modifier: Modifier,
    titleSize: Int,
) {
    val tile = K.p.tiles[abs(game.title.hashCode()) % K.p.tiles.size]
    val systemIcon = remember(game.systemId) { runCatching { GameSystem.findById(game.systemId).metaSystemID().imageResId }.getOrNull() }
    Box(modifier.background(tile), contentAlignment = Alignment.BottomStart) {
        if (systemIcon != null) {
            Image(
                painter = painterResource(systemIcon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.28f)),
                modifier = Modifier.align(Alignment.Center).fillMaxSize(0.8f),
            )
        }
        Text(
            game.displayName(),
            color = Color.White,
            fontSize = titleSize.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RetroTvTheme.display,
            lineHeight = (titleSize + 3).sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
        val url = game.displayCoverUrl()
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------- Szczegóły gry ----------

@Composable
private fun DetailsContent(
    game: Game,
    state: HomeViewModel.HomeState,
    actions: HomeActions,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(game.id) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopBar("Szczegóły gry", null, state)
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.width(220.dp).aspectRatio(0.75f).clip(RoundedCornerShape(14.dp))) {
                Cover(game, Modifier.fillMaxSize(), titleSize = 20)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(game.displayName(), color = K.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                Text(systemName(context, game) + (game.developer?.let { " · $it" } ?: ""), color = K.muted, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                DetailRow("Ostatnio", lastPlayed(game))
                DetailRow("Czas gry", PlayStats.format(PlayStats.timeMs(context, game.id)).let { if (PlayStats.sessions(context, game.id) == 0) "—" else it })
                DetailRow("Uruchomienia", PlayStats.sessions(context, game.id).toString())
                DetailRow("Plik", game.fileName)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Graj", primary = true, onClick = { actions.play(game) }, modifier = Modifier.focusRequester(focus))
                    TvButton("Z drużyną", onClick = { actions.playTeam(game) })
                    TvButton("Od nowa", onClick = { actions.restart(game) })
                    TvButton(if (game.isFavorite) "Usuń z ulubionych" else "Do ulubionych", onClick = { actions.toggleFavorite(game) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (confirmDelete) {
                        TvButton("Tak, usuń plik gry", primary = true, onClick = {
                            info = actions.deleteGame(game)
                            confirmDelete = false
                        })
                        TvButton("Anuluj", onClick = { confirmDelete = false })
                    } else {
                        TvButton("Usuń grę", onClick = { confirmDelete = true })
                        TvButton("Wróć", onClick = onClose)
                    }
                }
                info?.let { Text(it, color = K.muted, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row {
        Text(label, color = K.muted, fontSize = 15.sp, modifier = Modifier.width(130.dp))
        Text(value, color = K.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Wyszukiwanie ----------

@Composable
private fun SearchContent(
    viewModel: HomeViewModel,
    state: HomeViewModel.HomeState,
    actions: HomeActions,
    onDetails: (Game) -> Unit,
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        PhonePadText.listener = { text -> viewModel.searchQuery.value = text }
        onDispose { PhonePadText.listener = null }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TopBar("Szukaj", "Wpisz pilotem albo na telefonie (przycisk Klawiatura na padzie w telefonie)", state)
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            singleLine = true,
            label = { Text("Nazwa gry") },
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
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (query.isNotBlank() && results.isEmpty()) {
            Text("Nie znaleziono gier pasujących do „$query”.", color = K.muted, fontSize = 15.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(results, key = { it.id }) { game -> GameTile(game, actions, onDetails) }
        }
    }
}

// ---------- pomocnicze ----------

private fun systemName(
    context: Context,
    game: Game,
): String = runCatching { context.getString(GameSystem.findById(game.systemId).shortTitleResId) }.getOrDefault(game.systemId)

private fun gamesCount(n: Int): String {
    val word =
        when {
            n == 1 -> "gra"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "gry"
            else -> "gier"
        }
    return "$n $word"
}

private fun lastPlayed(game: Game): String {
    val t = game.lastPlayedAt ?: return "jeszcze nie grano"
    val then = Calendar.getInstance().apply { timeInMillis = t }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    val days =
        ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis -
            then.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis) /
            86_400_000L).toInt()
    return when {
        days <= 0 -> "dziś, $time"
        days == 1 -> "wczoraj, $time"
        days < 7 -> "$days dni temu"
        else -> SimpleDateFormat("d MMM yyyy", Locale("pl")).format(Date(t))
    }
}
