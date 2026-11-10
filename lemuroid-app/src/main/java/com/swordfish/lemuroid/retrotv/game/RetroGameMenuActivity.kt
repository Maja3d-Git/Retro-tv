package com.swordfish.lemuroid.retrotv.game

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.ImmersiveActivity
import com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SaveInfo
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Menu w trakcie gry w stylu Retro TV. Zwraca wynik w tym samym formacie co dawne menu Lemuroid
 * ([GameMenuContract]), a zaawansowane opcje emulatora otwiera w dawnym menu.
 */
class RetroGameMenuActivity : ImmersiveActivity() {
    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    private val legacyRequest = 4242

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        val game = intent.getSerializableExtra(GameMenuContract.EXTRA_GAME) as Game
        val core = intent.getSerializableExtra(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG) as SystemCoreConfig
        setContent { RetroTvContent { MenuScreen(game, core) } }
    }

    fun finishWith(build: Intent.() -> Unit) {
        setResult(Activity.RESULT_OK, Intent().apply(build))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    fun openLegacyMenu() {
        val legacy = Intent(this, TVGameMenuActivity::class.java).putExtras(intent)
        startActivityForResult(legacy, legacyRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == legacyRequest) {
            // Przekazujemy wybór z dawnego menu dalej do gry.
            setResult(resultCode, data)
            finish()
        }
    }

    private suspend fun loadSlots(
        game: Game,
        core: SystemCoreConfig,
    ): List<Pair<SaveInfo, Bitmap?>> {
        val infos = statesManager.getSavedSlotsInfo(game, core.coreID)
        return infos.mapIndexed { index, info ->
            val preview = if (info.exists) runCatching { statesPreviewManager.getPreviewForSlot(game, core.coreID, index, 320) }.getOrNull() else null
            info to preview
        }
    }

    private enum class Page { MAIN, SAVE, LOAD, DISK, NETPLAY }

    @Composable
    private fun MenuScreen(
        game: Game,
        core: SystemCoreConfig,
    ) {
        var page by remember { mutableStateOf(Page.MAIN) }
        var slots by remember { mutableStateOf<List<Pair<SaveInfo, Bitmap?>>>(emptyList()) }
        val netplay by com.swordfish.lemuroid.retrotv.netplay.NetplayHostService.state.collectAsState()
        LaunchedEffect(Unit) { slots = loadSlots(game, core) }
        BackHandler { if (page == Page.MAIN) finishWith {} else page = Page.MAIN }

        val audio = intent.getBooleanExtra(GameMenuContract.EXTRA_AUDIO_ENABLED, true)
        val ffSupported = intent.getBooleanExtra(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, false)
        val ff = intent.getBooleanExtra(GameMenuContract.EXTRA_FAST_FORWARD, false)
        val disks = intent.getIntExtra(GameMenuContract.EXTRA_DISKS, 0)
        val currentDisk = intent.getIntExtra(GameMenuContract.EXTRA_CURRENT_DISK, 0)
        val states = core.statesSupported

        Row(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x99000000)))),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PAUZA", color = Color(0xFFFFB089), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(
                    game.displayName(),
                    color = Color.White,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RetroTvTheme.display,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                val saved = slots.withIndex().filter { it.value.first.exists }
                if (states && saved.isNotEmpty() && page == Page.MAIN) {
                    Text("OSTATNIE ZAPISY", color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        saved.sortedByDescending { it.value.first.date }.take(3).forEach { (i, pair) -> SlotCard(i, pair.first, pair.second) }
                    }
                }
            }
            Column(Modifier.width(400.dp).fillMaxHeight().background(K.nav).padding(horizontal = 20.dp, vertical = 28.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val first = remember(page) { FocusRequester() }
                LaunchedEffect(page) { runCatching { first.requestFocus() } }
                when (page) {
                    Page.MAIN -> {
                        MenuItem("Wznów grę", modifier = Modifier.focusRequester(first)) { finishWith {} }
                        MenuLabel("GRA")
                        if (states) {
                            MenuItem("Zapisz stan", "wybierz slot") { page = Page.SAVE }
                            MenuItem("Wczytaj stan", if (slots.any { it.first.exists }) "wybierz slot" else "brak zapisów", enabled = slots.any { it.first.exists }) { page = Page.LOAD }
                        }
                        if (states) {
                            MenuItem("Cofnij grę", "o 10 sekund") {
                                finishWith { putExtra(com.swordfish.lemuroid.app.shared.game.BaseGameActivity.RETROTV_REWIND, 10) }
                            }
                            MenuItem("Cofnij grę", "o 30 sekund") {
                                finishWith { putExtra(com.swordfish.lemuroid.app.shared.game.BaseGameActivity.RETROTV_REWIND, 30) }
                            }
                        }
                        MenuItem("Zacznij od nowa") { finishWith { putExtra(GameMenuContract.RESULT_RESET, true) } }
                        MenuLabel("OPCJE")
                        // Obraz tylko dla tej gry (od następnego uruchomienia).
                        val gamePrefs = remember { getSharedPreferences("retrotv", MODE_PRIVATE) }
                        val shaderKey = "shader_game_${game.id}"
                        var shader by remember { mutableStateOf(gamePrefs.getString(shaderKey, null)) }
                        val shaderNames = listOf(null to "jak w ustawieniach", "sharp" to "ostry", "smooth" to "gładki", "crt" to "CRT", "lcd" to "LCD")
                        MenuItem("Obraz tej gry", shaderNames.first { it.first == shader }.second) {
                            val next = shaderNames[(shaderNames.indexOfFirst { it.first == shader } + 1) % shaderNames.size].first
                            shader = next
                            gamePrefs.edit().apply { if (next == null) remove(shaderKey) else putString(shaderKey, next) }.apply()
                            android.widget.Toast.makeText(this@RetroGameMenuActivity, "Zmiana obrazu po ponownym uruchomieniu gry", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (ffSupported) {
                            MenuItem("Przyspieszenie", if (ff) "włączone" else "wyłączone") {
                                finishWith { putExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD, !ff) }
                            }
                        }
                        MenuItem("Dźwięk", if (audio) "włączony" else "wyłączony") {
                            finishWith { putExtra(GameMenuContract.RESULT_ENABLE_AUDIO, !audio) }
                        }
                        if (disks > 1) MenuItem("Zmień płytę", "płyta ${currentDisk + 1} z $disks") { page = Page.DISK }
                        MenuLabel("ONLINE")
                        if (netplay.active) {
                            MenuItem("Pokój ${netplay.code}", "goście: ${netplay.guests.count { it.connected }}") { page = Page.NETPLAY }
                        } else {
                            MenuItem("Graj przez internet", "nowy pokój") {
                                finishWith { putExtra(com.swordfish.lemuroid.app.shared.game.BaseGameActivity.RETROTV_NETPLAY, "start") }
                            }
                        }
                    }
                    Page.SAVE, Page.LOAD -> {
                        val saving = page == Page.SAVE
                        Text(if (saving) "Zapisz stan" else "Wczytaj stan", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                        Text(if (saving) "Zapis nadpisze wybrany slot." else "Wybierz zapis do wczytania.", color = K.muted, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        slots.forEachIndexed { i, (info, bmp) ->
                            val enabled = saving || info.exists
                            SlotThumb(i, info, bmp, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), enabled = enabled) {
                                finishWith { putExtra(if (saving) GameMenuContract.RESULT_SAVE else GameMenuContract.RESULT_LOAD, i) }
                            }
                        }
                        MenuItem("Wróć") { page = Page.MAIN }
                    }
                    Page.NETPLAY -> {
                        Text("Gra przez internet", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                        Text("KOD POKOJU", color = K.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(netplay.code, color = K.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display, letterSpacing = 4.sp)
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp)) {
                                com.swordfish.lemuroid.retrotv.system.QrImage("${com.swordfish.lemuroid.retrotv.netplay.Netplay.WEB_URL}?kod=${netplay.code}", Modifier.width(96.dp).aspectRatio(1f))
                            }
                        }
                        Text(
                            if (netplay.online) "Pokój jest otwarty." else "Łączenie z serwerem…",
                            color = if (netplay.online) K.ok else K.muted,
                            fontSize = 14.sp,
                        )
                        Text(
                            "Goście wpisują kod w aplikacji Retro TV (Dołącz do gry) albo na stronie:\n${com.swordfish.lemuroid.retrotv.netplay.Netplay.WEB_URL}",
                            color = K.muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        if (netplay.guests.isEmpty()) {
                            Text("Czekam na gości…", color = K.muted, fontSize = 14.sp)
                        } else {
                            netplay.guests.forEach { g ->
                                Text("${g.name}: ${if (g.connected) "połączony" else "łączy się…"}", color = if (g.connected) K.ok else K.muted, fontSize = 14.sp)
                            }
                        }
                        Text("Dźwięk gry słyszą tylko osoby przy tym telewizorze.", color = K.muted, fontSize = 12.sp)
                        MenuItem("Wróć do gry", modifier = Modifier.focusRequester(first)) { finishWith {} }
                        MenuItem("Zamknij pokój", danger = true) {
                            finishWith { putExtra(com.swordfish.lemuroid.app.shared.game.BaseGameActivity.RETROTV_NETPLAY, "stop") }
                        }
                    }
                    Page.DISK -> {
                        Text("Zmień płytę", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                        (0 until disks).forEach { d ->
                            MenuItem("Płyta ${d + 1}", if (d == currentDisk) "bieżąca" else null, modifier = if (d == 0) Modifier.focusRequester(first) else Modifier) {
                                finishWith { putExtra(GameMenuContract.RESULT_CHANGE_DISK, d) }
                            }
                        }
                        MenuItem("Wróć") { page = Page.MAIN }
                    }
                }
            }
            if (page == Page.MAIN) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
                Spacer(Modifier.height(8.dp))
                MenuItem("Wyjdź do biblioteki", danger = true) { finishWith { putExtra(GameMenuContract.RESULT_QUIT, true) } }
            }
            }
        }
    }

    @Composable
    private fun MenuLabel(text: String) {
        Text(
            text,
            color = K.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
        )
    }

    /** Karta zapisu na ekranie pauzy: miniatura nad podpisem (bez łamania tekstu). */
    @Composable
    private fun SlotCard(
        index: Int,
        info: SaveInfo,
        preview: Bitmap?,
    ) {
        val date = SimpleDateFormat("d MMM, HH:mm", Locale("pl")).format(Date(info.date))
        Column(
            Modifier.width(170.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Text("Slot ${index + 1} · $date", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun MenuItem(
        label: String,
        value: String? = null,
        enabled: Boolean = true,
        danger: Boolean = false,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        Row(
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) K.p.activeBg else Color.Transparent)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color =
                    when {
                        focused -> K.p.activeFg
                        !enabled -> K.line
                        danger -> K.coral
                        else -> K.text
                    },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    color = if (focused) Color(0xFF444444) else K.muted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
    }

    @Composable
    private fun SlotThumb(
        index: Int,
        info: SaveInfo,
        preview: Bitmap?,
        modifier: Modifier,
        enabled: Boolean = true,
        focusable: Boolean = true,
        onClick: () -> Unit,
    ) {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        val date = if (info.exists) SimpleDateFormat("d MMM, HH:mm", Locale("pl")).format(Date(info.date)) else "pusty"
        Row(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(K.card)
                .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
                .then(if (focusable) Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick) else Modifier)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(96.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(K.bg), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("—", color = K.muted, fontSize = 18.sp)
                }
            }
            Column {
                Text("Slot ${index + 1}", color = if (enabled) K.text else K.line, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(date, color = K.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
