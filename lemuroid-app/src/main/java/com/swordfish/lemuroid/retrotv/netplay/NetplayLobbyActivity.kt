package com.swordfish.lemuroid.retrotv.netplay

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton

/**
 * Pokój przed uruchomieniem gry („Graj z drużyną”): kod dla gości, lista graczy
 * i start dopiero wtedy, gdy ktoś dołączy. Działa w procesie gry, razem z serwisem pokoju.
 */
class NetplayLobbyActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_TITLE = "title"
        const val RESULT_MODE = "mode"

        fun intent(
            context: Context,
            title: String,
        ): Intent = Intent(context, NetplayLobbyActivity::class.java).putExtra(EXTRA_TITLE, title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Retro TV"
        if (savedInstanceState == null) NetplayHostService.openLobby(this, title)
        setContent { RetroTvContent { Screen(title) } }
    }

    private fun done(mode: String?) {
        if (mode == null) {
            NetplayHostService.stop(this)
            setResult(Activity.RESULT_CANCELED)
        } else {
            if (mode == "solo") NetplayHostService.stop(this)
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_MODE, mode))
        }
        finish()
    }

    @Composable
    private fun Screen(title: String) {
        val state by NetplayHostService.state.collectAsState()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BackHandler { done(null) }
        val guests = state.guests

        Row(Modifier.fillMaxSize().background(K.bg).padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("GRA Z DRUŻYNĄ", color = K.coral, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(
                    title,
                    color = K.text,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RetroTvTheme.display,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text("KOD POKOJU", color = K.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(
                    state.code.ifEmpty { "…" },
                    color = K.text,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RetroTvTheme.display,
                    letterSpacing = 6.sp,
                )
                Text(
                    if (state.online) "Pokój jest otwarty" else "Łączenie z serwerem…",
                    color = if (state.online) K.ok else K.muted,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                if (state.code.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White).padding(8.dp)) {
                            com.swordfish.lemuroid.retrotv.system.QrImage("${Netplay.WEB_URL}?kod=${state.code}", Modifier.size(120.dp))
                        }
                        Text("Zeskanuj aplikacją Retro TV na telefonie albo aparatem (otworzy się strona gry).", color = K.muted, fontSize = 14.sp, modifier = Modifier.width(260.dp))
                    }
                }
                Text("Jak dołączyć:", color = K.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("• Retro TV na innym telewizorze → „Dołącz do gry” → wpisz kod", color = K.muted, fontSize = 15.sp)
                Text("• Retro TV na telefonie → „Dołącz do gry online”", color = K.muted, fontSize = 15.sp)
                Text("• Przeglądarka: ${Netplay.WEB_URL}", color = K.muted, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Po starcie telewizor poprosi o zgodę na przesyłanie obrazu – wybierz „Rozpocznij”.",
                    color = K.muted,
                    fontSize = 13.sp,
                )
            }
            Column(
                Modifier.width(380.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(K.card).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Gracze", color = K.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                PlayerRow("Ty", "gracz 1", true)
                if (guests.isEmpty()) {
                    Text("Czekam, aż ktoś dołączy…", color = K.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    guests.forEach { g -> PlayerRow(g.name, "gracz ${g.player}", true) }
                }
                Spacer(Modifier.weight(1f))
                TvButton(
                    if (guests.isEmpty()) "Rozpocznij (czekam na graczy)" else "Rozpocznij grę",
                    primary = guests.isNotEmpty(),
                    enabled = guests.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { done("team") },
                )
                TvButton("Graj sam", modifier = Modifier.fillMaxWidth().focusRequester(focus), onClick = { done("solo") })
                TvButton("Anuluj", modifier = Modifier.fillMaxWidth(), onClick = { done(null) })
            }
        }
    }

    @Composable
    private fun PlayerRow(
        name: String,
        role: String,
        ready: Boolean,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.bg).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) K.ok else K.line))
            Spacer(Modifier.width(12.dp))
            Text(name, color = K.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(role, color = K.muted, fontSize = 14.sp)
        }
    }
}
