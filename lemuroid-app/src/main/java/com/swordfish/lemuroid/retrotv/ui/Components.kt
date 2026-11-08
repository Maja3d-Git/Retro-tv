package com.swordfish.lemuroid.retrotv.ui

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
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

// ---------- wspólne elementy ----------

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val bg =
        when {
            !enabled -> K.card
            primary -> K.accent
            else -> K.btn
        }
    val fg =
        when {
            !enabled -> K.muted
            primary -> K.onAccent
            else -> K.text
        }
    Box(
        modifier =
            modifier
                .height(48.dp)
                .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(26.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(bg)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
fun TvToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) K.btn else Color.Transparent)
                .border(2.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(interactionSource = source, indication = null) { onChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = K.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (checked) K.accent else K.line),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (checked) Color.White else K.muted),
            )
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = K.text,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = K.muted, fontSize = 15.sp, modifier = Modifier.width(200.dp))
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(K.card)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
        if (subtitle != null) Text(subtitle, color = K.muted, fontSize = 14.sp)
    }
}


/** Ustawia domyślną czcionkę Retro TV dla całej zawartości. */
@Composable
fun RetroTvContent(content: @Composable () -> Unit) {
    ProvideTextStyle(TextStyle(fontFamily = RetroTvTheme.body), content)
}

/** Wiersz wyboru jednej z opcji (jak przycisk radiowy). */
@Composable
fun TvChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) K.btn else Color.Transparent)
                .border(2.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(2.dp, if (selected) K.accent else K.muted, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(K.accent))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = K.text, fontSize = 15.sp)
    }
}

/** Nagłówek menu bocznego: logo i nazwa, wspólny dla wszystkich ekranów. */
@Composable
fun RailHeader(subtitle: String?) {
    Row(Modifier.padding(start = 10.dp, bottom = if (subtitle == null) 18.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        com.swordfish.lemuroid.retrotv.home.PadLogo(Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text("RETRO TV", color = K.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
    }
    if (subtitle != null) {
        Text(subtitle, color = K.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 50.dp, bottom = 18.dp))
    }
}

/**
 * Obszar ekranu (menu boczne albo treść), z którego pilot nie wychodzi strzałkami góra/dół.
 * Dzięki temu „w dół” na końcu listy nie przeskakuje do innej zakładki menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvPane(): Modifier =
    this
        .focusProperties {
            exit = { direction ->
                if (direction == androidx.compose.ui.focus.FocusDirection.Up ||
                    direction == androidx.compose.ui.focus.FocusDirection.Down
                ) {
                    androidx.compose.ui.focus.FocusRequester.Cancel
                } else {
                    androidx.compose.ui.focus.FocusRequester.Default
                }
            }
        }
        .focusGroup()

/** Obszar ekranu z menu u góry: strzałki w lewo/prawo nie wyprowadzają fokusu poza obszar. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvRowPane(): Modifier =
    this
        .focusProperties {
            exit = { direction ->
                if (direction == androidx.compose.ui.focus.FocusDirection.Left ||
                    direction == androidx.compose.ui.focus.FocusDirection.Right
                ) {
                    androidx.compose.ui.focus.FocusRequester.Cancel
                } else {
                    androidx.compose.ui.focus.FocusRequester.Default
                }
            }
        }
        .focusGroup()
