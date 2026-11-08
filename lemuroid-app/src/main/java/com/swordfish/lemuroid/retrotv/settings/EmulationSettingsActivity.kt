package com.swordfish.lemuroid.retrotv.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.Panel
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.Heading
import com.swordfish.lemuroid.retrotv.ui.TvButton
import com.swordfish.lemuroid.retrotv.ui.TvChoice
import com.swordfish.lemuroid.retrotv.ui.TvToggle

/** „Emulacja i ekran” w stylu Retro TV: obraz, gra, rdzenie. Te same ustawienia, co w silniku Lemuroid. */
class EmulationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { EmulationContent(this) } }
    }
}

@Composable
private fun EmuToggle(
    prefs: SharedPreferences,
    label: String,
    key: String,
    default: Boolean,
) {
    var value by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    TvToggle(label, value) {
        value = it
        prefs.edit().putBoolean(key, it).apply()
    }
}

/** Treść „Emulacja i ekran” – osobno albo jako zakładka w Ustawieniach ([embedded]). */
@Composable
fun EmulationContent(
    activity: ComponentActivity,
    embedded: Boolean = false,
) {
    val prefs = remember { SharedPreferencesHelper.getSharedPreferences(activity.applicationContext) }

    fun key(id: Int) = activity.getString(id)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!embedded) runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.background(K.bg)).verticalScroll(rememberScrollState())
                .then(if (embedded) Modifier else Modifier.padding(horizontal = 64.dp, vertical = 36.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Emulacja i ekran", color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
        Text("Zmiany działają od następnego uruchomienia gry.", color = K.muted, fontSize = 15.sp)

        // ---------- obraz ----------
        Heading("Obraz", "Jak wyglądają piksele na telewizorze")
        var shader by remember { mutableStateOf(prefs.getString(key(R.string.pref_key_shader_filter), "auto") ?: "auto") }
        Panel {
            listOf(
                "auto" to "Automatycznie (zależnie od konsoli)",
                "sharp" to "Ostry – wyraźne piksele",
                "smooth" to "Gładki – zmiękczone krawędzie",
                "crt" to "CRT – jak stary telewizor",
                "lcd" to "LCD – jak ekran Game Boya",
            ).forEachIndexed { i, (value, label) ->
                Row(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    TvChoice(label, shader == value) {
                        shader = value
                        prefs.edit().putString(key(R.string.pref_key_shader_filter), value).apply()
                    }
                }
            }
        }
        var hd by remember { mutableStateOf(prefs.getBoolean(key(R.string.pref_key_hd_mode), false)) }
        Panel {
            TvToggle("Tryb HD (wyższa rozdzielczość grafiki 3D)", hd) {
                hd = it
                prefs.edit().putBoolean(key(R.string.pref_key_hd_mode), it).apply()
            }
            if (hd) {
                var quality by remember { mutableStateOf(prefs.getInt(key(R.string.pref_key_hd_mode_quality), 1)) }
                listOf(0 to "Jakość HD: niska (szybciej)", 1 to "Jakość HD: średnia", 2 to "Jakość HD: wysoka (wolniej)").forEach { (v, l) ->
                    TvChoice(l, quality == v) {
                        quality = v
                        prefs.edit().putInt(key(R.string.pref_key_hd_mode_quality), v).apply()
                    }
                }
            }
        }

        // ---------- gra ----------
        Heading("Gra")
        Panel {
            EmuToggle(prefs, "Zapisuj grę automatycznie przy wyjściu („Kontynuuj”)", key(R.string.pref_key_autosave), true)
            EmuToggle(prefs, "Wibracje pada", key(R.string.pref_key_enable_rumble), false)
            EmuToggle(prefs, "Dźwięk z małym opóźnieniem (gdy dźwięk się spóźnia)", key(R.string.pref_key_low_latency_audio), false)
            EmuToggle(prefs, "Wczytuj gry bezpośrednio z folderu (szybciej)", key(R.string.pref_key_allow_direct_game_load), true)
        }

        // ---------- rdzenie ----------
        val multi = remember { GameSystem.all().filter { it.systemCoreConfigs.size > 1 }.sortedBy { activity.getString(it.titleResId) } }
        if (multi.isNotEmpty()) {
            Heading("Emulatory konsol", "Gdy gra źle działa, spróbuj innego emulatora tej konsoli")
            multi.forEach { system ->
                val prefKey = CoresSelection.computeSystemPreferenceKey(system.id)
                var selected by remember {
                    mutableStateOf(prefs.getString(prefKey, null) ?: system.systemCoreConfigs.first().coreID.coreName)
                }
                Text(activity.getString(system.titleResId), color = K.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Panel {
                    system.systemCoreConfigs.forEach { cfg ->
                        TvChoice(cfg.coreID.coreDisplayName, selected == cfg.coreID.coreName) {
                            selected = cfg.coreID.coreName
                            prefs.edit().putString(prefKey, cfg.coreID.coreName).apply()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Ustawienia zaawansowane", onClick = { activity.startActivity(Intent(activity, TVSettingsActivity::class.java)) })
            TvButton("Wróć", onClick = { finish() })
        }
        Spacer(Modifier.width(1.dp))
    }
}
