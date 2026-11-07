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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    private enum class Page { MAIN, SAVE, LOAD, DISK }

    @Composable
    private fun MenuScreen(
        game: Game,
        core: SystemCoreConfig,
    ) {
        var page by remember { mutableStateOf(Page.MAIN) }
        var slots by remember { mutableStateOf<List<Pair<SaveInfo, Bitmap?>>>(emptyList()) }
        LaunchedEffect(Unit) { slots = loadSlots(game, core) }
        BackHandler { if (page == Page.MAIN) finishWith {} else page = Page.MAIN }

        val audio = intent.getBooleanExtra(GameMenuContract.EXTRA_AUDIO_ENABLED, true)
        val ffSupported = intent.getBooleanExtra(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, false)
        val ff = intent.getBooleanExtra(GameMenuContract.EXTRA_FAST_FORWARD, false)
        val disks = intent.getIntExtra(GameMenuContract.EXTRA_DISKS, 0)
        val currentDisk = intent.getIntExtra(GameMenuContract.EXTRA_CURRENT_DISK, 0)
        val states = core.statesSupported

        Row(Modifier.fillMaxSize().background(Color(0x99000000))) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PAUZA", color = Color(0xFFFFB089), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(game.displayName(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                Spacer(Modifier.weight(1f))
                if (states && slots.isNotEmpty() && page == Page.MAIN) {
                    Text("ZAPISY STANU", color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        slots.take(4).forEachIndexed { i, (info, bmp) -> SlotThumb(i, info, bmp, Modifier.width(150.dp), focusable = false) {} }
                    }
                }
            }
            Column(
                Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .background(K.nav)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val first = remember(page) { FocusRequester() }
                LaunchedEffect(page) { runCatching { first.requestFocus() } }
                when (page) {
                    Page.MAIN -> {
                        MenuItem("Wznów grę", modifier = Modifier.focusRequester(first)) { finishWith {} }
                        if (states) {
                            MenuItem("Zapisz stan", "wybierz slot") { page = Page.SAVE }
                            MenuItem("Wczytaj stan", if (slots.any { it.first.exists }) "wybierz slot" else "brak zapisów", enabled = slots.any { it.first.exists }) { page = Page.LOAD }
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
                        MenuItem("Zacznij od nowa") { finishWith { putExtra(GameMenuContract.RESULT_RESET, true) } }
                        MenuItem("Więcej opcji emulatora", "filtry, sterowanie, rdzeń") { openLegacyMenu() }
                        Spacer(Modifier.height(8.dp))
                        MenuItem("Wyjdź do biblioteki", danger = true) { finishWith { putExtra(GameMenuContract.RESULT_QUIT, true) } }
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
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) K.accent else Color.Transparent)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color =
                    when {
                        focused -> K.onAccent
                        !enabled -> K.line
                        danger -> K.coral
                        else -> K.text
                    },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (value != null) Text(value, color = if (focused) K.onAccent else K.muted, fontSize = 14.sp)
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
