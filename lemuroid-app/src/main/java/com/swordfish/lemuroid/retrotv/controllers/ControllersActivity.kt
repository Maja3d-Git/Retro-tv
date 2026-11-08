package com.swordfish.lemuroid.retrotv.controllers

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.retrotv.pad.PadDetector
import com.swordfish.lemuroid.retrotv.pad.PadOrder
import com.swordfish.lemuroid.retrotv.pad.PhonePadServer
import com.swordfish.lemuroid.retrotv.system.QrImage
import com.swordfish.lemuroid.retrotv.system.SystemStatus
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.delay

/**
 * Kontrolery: kto jest którym graczem (pady i telefony), test i zmiana przycisków,
 * parowanie pada Bluetooth i łączenie telefonu w trzech krokach.
 */
class ControllersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { Screen() } }
    }

    private fun openBluetooth() {
        val intents = listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in intents) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
    }

    @Composable
    private fun Screen() {
        var pads by remember { mutableStateOf(PadDetector.orderedPads(this)) }
        var showPairing by remember { mutableStateOf(false) }
        val phones by PhonePadServer.phones.collectAsState()
        val serverPort by PhonePadServer.port.collectAsState()
        val visitor by PhonePadServer.lastVisitor.collectAsState()
        val net = remember { SystemStatus.network(this) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { first.requestFocus() }
            while (true) {
                delay(2_000)
                pads = PadDetector.orderedPads(this@ControllersActivity)
            }
        }
        DisposableEffect(Unit) { onDispose { } }
        BackHandler(enabled = showPairing) { showPairing = false }

        Row(Modifier.fillMaxSize().background(K.bg).padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // ---------- gracze ----------
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kontrolery", color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                Text("Gracze w kolejności. Pady są pierwsze, telefony po nich.", color = K.muted, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                var player = 1
                pads.forEachIndexed { index, pad ->
                    val p = player++
                    PlayerRow(
                        p,
                        pad.name,
                        padDetail(pad),
                        K.ok,
                    ) {
                        TvButton("Test", modifier = if (index == 0) Modifier.focusRequester(first) else Modifier, onClick = {
                            startActivity(Intent(this@ControllersActivity, PadTestActivity::class.java))
                        })
                        TvButton("Przyciski", onClick = { startActivity(PadRemapActivity.intent(this@ControllersActivity, pad.descriptor)) })
                        if (index > 0) {
                            TvButton("Wyżej", onClick = {
                                PadOrder.moveUp(this@ControllersActivity, pads, pad.descriptor)
                                pads = PadDetector.orderedPads(this@ControllersActivity)
                            })
                        }
                    }
                }
                phones.forEach { ph ->
                    PlayerRow(player++, ph.name, "telefon · ${ph.link.ifEmpty { "połączony" }}", K.ok) {}
                }
                while (player <= 4) {
                    PlayerRow(player++, "Wolne miejsce", "podłącz pad albo telefon", K.line) {}
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        "Sparuj pad Bluetooth",
                        primary = pads.isEmpty(),
                        modifier = if (pads.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        onClick = { showPairing = true },
                    )
                    if (pads.isEmpty()) {
                        TvButton("Test pada", onClick = { startActivity(Intent(this@ControllersActivity, PadTestActivity::class.java)) })
                    }
                }
                Text("Menu gry z pada: Select + Start (albo L3 + R3).", color = K.muted, fontSize = 14.sp)
            }

            // ---------- telefon ----------
            Column(
                Modifier.width(400.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(K.card).padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Telefon jako pad", color = K.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val ip = net.ipAddress
                if (ip == null) {
                    Text("Telewizor nie jest połączony z siecią.", color = K.coral, fontSize = 15.sp)
                } else {
                    Step(1, "Zainstaluj Retro TV na telefonie", "Zeskanuj aparatem i pobierz plik APK.") {
                        QrBox(APK_URL, 110)
                    }
                    Step(2, "Otwórz aplikację i zeskanuj ten kod", "Przycisk „Zeskanuj kod QR z telewizora”.") {
                        val url = remember(serverPort) { PhonePadServer.pairingUrl(this@ControllersActivity, ip) }
                        QrBox(url, 150)
                    }
                    Text(
                        "Kod do wpisania ręcznie: ${PhonePadServer.token(this@ControllersActivity)}",
                        color = K.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Step(3, if (phones.isEmpty()) "Czekam na telefon…" else "Gotowe – telefon jest padem", null) {}
                    Text(
                        visitor?.let { "Ostatnie połączenie: $it" } ?: (net.ssid?.let { "Telefon w sieci „$it” albo z internetem." } ?: ""),
                        color = K.muted,
                        fontSize = 13.sp,
                    )
                    Text(
                        if (serverPort > 0) "Nie działa? Telefon połączy się przez internet, gdy sieć domowa blokuje połączenie." else "Serwer uruchamia się…",
                        color = K.muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (showPairing) PairingDialog(onOpenSettings = { openBluetooth() }, onClose = { showPairing = false })
    }

    private fun padDetail(pad: InputDevice): String {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val b = pad.batteryState
            if (b.isPresent && !b.capacity.isNaN()) parts += "bateria ${(b.capacity * 100).toInt()}%"
        }
        parts += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !pad.isExternal) "wbudowany" else "pad"
        return parts.joinToString(" · ")
    }

    @Composable
    private fun PlayerRow(
        player: Int,
        name: String,
        detail: String,
        dot: Color,
        actions: @Composable () -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.card).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(K.bg), contentAlignment = Alignment.Center) {
                Text("$player", color = K.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = K.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(detail, color = K.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }

    @Composable
    private fun Step(
        n: Int,
        title: String,
        detail: String?,
        content: @Composable () -> Unit,
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(K.p.activeBg), contentAlignment = Alignment.Center) {
                Text("$n", color = K.p.activeFg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = K.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, color = K.muted, fontSize = 13.sp)
                content()
            }
        }
    }

    @Composable
    private fun QrBox(
        text: String,
        size: Int,
    ) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
            QrImage(text, Modifier.size(size.dp))
        }
    }

    @Composable
    private fun PairingDialog(
        onOpenSettings: () -> Unit,
        onClose: () -> Unit,
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(620.dp).clip(RoundedCornerShape(20.dp)).background(K.card).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Parowanie pada Bluetooth", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("1. Włącz w padzie tryb parowania:", color = K.text, fontSize = 15.sp)
                PairHint("Xbox", "przytrzymaj mały przycisk parowania na górze, aż logo zacznie szybko migać")
                PairHint("PlayStation 4/5", "przytrzymaj Share (Create) i PS, aż pasek świetlny zacznie migać")
                PairHint("Switch Pro", "przytrzymaj mały przycisk Sync na górze pada")
                PairHint("8BitDo i inne", "przytrzymaj przycisk Pair albo Start + Y (zobacz instrukcję pada)")
                Text("2. W ustawieniach telewizora wybierz „Dodaj akcesorium” i wskaż pad.", color = K.text, fontSize = 15.sp)
                Text("3. Wróć tutaj – pad pojawi się na liście graczy.", color = K.text, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Otwórz ustawienia Bluetooth", primary = true, modifier = Modifier.focusRequester(focus), onClick = {
                        onClose()
                        onOpenSettings()
                    })
                    TvButton("Zamknij", onClick = onClose)
                }
            }
        }
    }

    @Composable
    private fun PairHint(
        pad: String,
        how: String,
    ) {
        Text("• $pad: $how", color = K.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }

    companion object {
        // Kopia na GitHub Pages – działa także tam, gdzie github.com bywa niedostępny.
        val APK_URL = "https://${BuildConfig.RETROTV_REPO.substringBefore('/').lowercase()}.github.io/${BuildConfig.RETROTV_REPO.substringAfter('/')}/update/retro-tv.apk"
    }
}
