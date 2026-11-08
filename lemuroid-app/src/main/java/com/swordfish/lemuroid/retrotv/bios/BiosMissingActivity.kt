package com.swordfish.lemuroid.retrotv.bios

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.retrotv.roms.RomManagerActivity
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Okno „Brakuje pliku BIOS” przed uruchomieniem gry – co wgrać i jak najprościej to zrobić. */
class BiosMissingActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_GAME = "game"
        private const val EXTRA_SYSTEM = "system"
        private const val EXTRA_FILES = "files"
        const val RESULT_READY = "ready"

        fun intent(
            context: Context,
            game: Game,
            systemName: String,
            files: List<String>,
        ): Intent =
            Intent(context, BiosMissingActivity::class.java)
                .putExtra(EXTRA_GAME, game)
                .putExtra(EXTRA_SYSTEM, systemName)
                .putExtra(EXTRA_FILES, files.toTypedArray())
    }

    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { Screen() } }
    }

    @Suppress("DEPRECATION")
    private fun game(): Game? = intent.getSerializableExtra(EXTRA_GAME) as? Game

    /** Skanuje bibliotekę (BIOS trafia wtedy na miejsce) i sprawdza ponownie przez kilkanaście sekund. */
    private fun recheck() {
        val g = game() ?: return
        status = "Szukam pliku BIOS…"
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
        lifecycleScope.launch {
            repeat(15) {
                delay(1_000)
                if (BiosCheck.missingFor(this@BiosMissingActivity, g).isEmpty()) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_READY, true))
                    status = "BIOS znaleziony – uruchamiam grę"
                    delay(600)
                    finish()
                    return@launch
                }
            }
            status = "Nadal brakuje pliku. Sprawdź nazwę pliku i spróbuj ponownie."
        }
    }

    @Composable
    private fun Screen() {
        val system = intent.getStringExtra(EXTRA_SYSTEM) ?: ""
        val files = intent.getStringArrayExtra(EXTRA_FILES)?.toList().orEmpty()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(760.dp).clip(RoundedCornerShape(22.dp)).background(K.card).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("POTRZEBNY PLIK BIOS", color = K.coral, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(
                    "Gry z konsoli $system uruchamiają się z oryginalnym BIOS-em tej konsoli",
                    color = K.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RetroTvTheme.display,
                )
                Text(
                    "Brakuje: " + files.joinToString(" albo "),
                    color = K.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Wystarczy wgrać go raz – Retro TV sam rozpozna plik i umieści go we właściwym miejscu. " +
                        "BIOS-ów nie dołączamy do aplikacji, bo są chronione prawem autorskim (pochodzą z Twojej konsoli).",
                    color = K.muted,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("Najprościej:", color = K.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("• z telefonu: aplikacja Retro TV → „Wyślij gry na telewizor” → wybierz plik BIOS", color = K.muted, fontSize = 15.sp)
                Text("• z pendrive’a albo folderu: Gry i ROM-y → wskaż folder, w którym jest plik", color = K.muted, fontSize = 15.sp)
                status?.let { Text(it, color = K.ok, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Wgrałem – sprawdź", primary = true, modifier = Modifier.focusRequester(focus), onClick = { recheck() })
                    TvButton("Gry i ROM-y", onClick = { startActivity(Intent(this@BiosMissingActivity, RomManagerActivity::class.java)) })
                    TvButton("Zamknij", onClick = { finish() })
                }
            }
        }
    }
}
