package com.swordfish.lemuroid.retrotv.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.app.tv.folderpicker.TVFolderPickerLauncher
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.retrotv.home.PadLogo
import com.swordfish.lemuroid.retrotv.pad.PhonePadServer
import com.swordfish.lemuroid.retrotv.roms.RomManagerActivity
import com.swordfish.lemuroid.retrotv.system.QrImage
import com.swordfish.lemuroid.retrotv.system.SystemStatus
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.ThemeMode
import com.swordfish.lemuroid.retrotv.ui.TvButton
import com.swordfish.lemuroid.retrotv.ui.TvChoice

/** Kreator przy pierwszym uruchomieniu: motyw, gry, pady, kopie zapisów. */
class OnboardingActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "retrotv"
        private const val KEY_DONE = "onboarding_done"
        private const val EXTRA_STEP = "step"

        fun shouldShow(context: Context): Boolean =
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

        fun markDone(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        val start = intent.getIntExtra(EXTRA_STEP, 0)
        setContent { RetroTvContent { Wizard(start) } }
    }

    private fun restartAt(step: Int) {
        intent.putExtra(EXTRA_STEP, step)
        recreate()
    }

    @Composable
    private fun Wizard(start: Int) {
        var step by remember { mutableIntStateOf(start) }
        val total = 4
        BackHandler(enabled = step > 0) { step-- }
        Box(Modifier.fillMaxSize().background(K.bg).padding(48.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(720.dp).clip(RoundedCornerShape(20.dp)).background(K.card).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PadLogo(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Retro TV", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                    Spacer(Modifier.weight(1f))
                    Text("Krok ${step + 1} z $total", color = K.muted, fontSize = 14.sp)
                }
                val focus = remember(step) { FocusRequester() }
                LaunchedEffect(step) { runCatching { focus.requestFocus() } }
                when (step) {
                    0 -> ThemeStep(focus) { step = 1 }
                    1 -> GamesStep(focus) { step = 2 }
                    2 -> PadsStep(focus) { step = 3 }
                    else -> DoneStep(focus)
                }
            }
        }
    }

    @Composable
    private fun Title(
        title: String,
        text: String,
    ) {
        Text(title, color = K.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
        Text(text, color = K.muted, fontSize = 16.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun ThemeStep(
        focus: FocusRequester,
        next: () -> Unit,
    ) {
        Title("Witaj!", "Retro TV to emulator starych konsol na telewizor. Zacznijmy od wyglądu.")
        val current = RetroTvTheme.mode(this)
        ThemeMode.values().forEach { mode ->
            TvChoice(mode.label, mode == current) {
                if (mode != current) {
                    RetroTvTheme.setMode(this, mode)
                    restartAt(0)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Dalej", primary = true, onClick = next, modifier = Modifier.focusRequester(focus))
        }
    }

    @Composable
    private fun GamesStep(
        focus: FocusRequester,
        next: () -> Unit,
    ) {
        Title(
            "Skąd wziąć gry?",
            "Wskaż folder z plikami gier (pamięć telewizora, pendrive) albo pobierz darmowe gry homebrew. " +
                "Więcej możliwości, w tym dysk sieciowy, znajdziesz później w „Gry i ROM-y”.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Wybierz folder", primary = true, modifier = Modifier.focusRequester(focus), onClick = {
                if (TVHelper.isSAFSupported(this@OnboardingActivity)) {
                    StorageFrameworkPickerLauncher.pickFolder(this@OnboardingActivity)
                } else {
                    TVFolderPickerLauncher.pickFolder(this@OnboardingActivity)
                }
                next()
            })
            TvButton("Darmowe gry", onClick = {
                startActivity(Intent(this@OnboardingActivity, RomManagerActivity::class.java))
                next()
            })
            TvButton("Później", onClick = next)
        }
    }

    @Composable
    private fun PadsStep(
        focus: FocusRequester,
        next: () -> Unit,
    ) {
        Title(
            "Czym będziesz grać?",
            "Pad Bluetooth sparujesz w Ustawieniach Android TV → Piloty i akcesoria. " +
                "Możesz też użyć telefonu: zeskanuj kod aparatem (ta sama sieć Wi-Fi).",
        )
        val ip = remember { SystemStatus.network(this).ipAddress }
        val phones by PhonePadServer.phones.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ip != null) QrImage(PhonePadServer.pairingUrl(this@OnboardingActivity, ip), Modifier.size(160.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (phones.isEmpty()) "Czekam na telefon…" else "Połączono: ${phones.joinToString { it.name }}",
                    color = if (phones.isEmpty()) K.muted else K.ok,
                    fontSize = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Dalej", primary = true, onClick = next, modifier = Modifier.focusRequester(focus))
                    TvButton("Pomiń, ustawię później", onClick = next)
                }
                Text("Telefon połączysz w każdej chwili w System → Kontrolery.", color = K.muted, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun DoneStep(focus: FocusRequester) {
        Title(
            "Gotowe!",
            "Kopie zapisów w Google Drive włączysz w System → Kopie zapisów, gdy będą dostępne. " +
                "Ustawienia, aktualizacje i dziennik znajdziesz w menu System.",
        )
        TvButton("Zaczynamy", primary = true, modifier = Modifier.focusRequester(focus).fillMaxWidth(0.4f), onClick = {
            markDone(this@OnboardingActivity)
            finish()
        })
    }
}
