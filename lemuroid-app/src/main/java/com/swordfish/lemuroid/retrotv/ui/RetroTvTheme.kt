package com.swordfish.lemuroid.retrotv.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.swordfish.lemuroid.R
import java.util.Calendar

/** Paleta kolorów motywu "Kartridż". */
data class Palette(
    val isDark: Boolean,
    val bg: Color,
    val nav: Color,
    val card: Color,
    val line: Color,
    val btn: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    /** Kolor ramki zaznaczenia (fokus pilota) i ważnych etykiet. */
    val coral: Color,
    val ok: Color,
    val warn: Color,
    val activeBg: Color,
    val activeFg: Color,
    val tiles: List<Color>,
)

enum class ThemeMode(val label: String) {
    LIGHT("Jasny"),
    DARK("Czarny"),
    AUTO("Automatycznie (ciemny wieczorem)"),
}

object RetroTvTheme {
    val Light =
        Palette(
            isDark = false,
            bg = Color(0xFFD8D5CE),
            nav = Color(0xFFCBC7BE),
            card = Color(0xFFECEAE5),
            line = Color(0xFFB7B2A8),
            btn = Color(0xFFDFDCD5),
            text = Color(0xFF24221E),
            muted = Color(0xFF55514A),
            accent = Color(0xFF5847C2),
            onAccent = Color(0xFFFFFFFF),
            coral = Color(0xFFA3221B),
            ok = Color(0xFF2F7D4F),
            warn = Color(0xFF8A5A00),
            activeBg = Color(0xFF24221E),
            activeFg = Color(0xFFECEAE5),
            tiles = listOf(Color(0xFF5847C2), Color(0xFFB3261E), Color(0xFF3F7D5C), Color(0xFFB97A12)),
        )

    /** Czarny motyw (domyślny): czarne tło, białe przyciski, pomarańczowe zaznaczenie. */
    val Dark =
        Palette(
            isDark = true,
            bg = Color(0xFF000000),
            nav = Color(0xFF0B0B0B),
            card = Color(0xFF151515),
            line = Color(0xFF2C2C2C),
            btn = Color(0xFF1F1F1F),
            text = Color(0xFFF2F2F2),
            muted = Color(0xFFA6A6A6),
            accent = Color(0xFFFFFFFF),
            onAccent = Color(0xFF000000),
            coral = Color(0xFFFF7A59),
            ok = Color(0xFF5BD69A),
            warn = Color(0xFFE8B04A),
            activeBg = Color(0xFFFFFFFF),
            activeFg = Color(0xFF000000),
            tiles = listOf(Color(0xFF6A5BD6), Color(0xFFB83A33), Color(0xFF3F8A62), Color(0xFFB98322)),
        )

    /** Bieżąca paleta; ustawiana przez [apply] przy tworzeniu ekranu. */
    @Volatile
    var palette: Palette = Dark
        private set

    private const val PREFS = "retrotv"
    private const val KEY_THEME = "theme_mode"

    fun mode(context: Context): ThemeMode {
        val name = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        return ThemeMode.values().firstOrNull { it.name == name } ?: ThemeMode.DARK
    }

    fun setMode(
        context: Context,
        mode: ThemeMode,
    ) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
        apply(context)
    }

    fun apply(context: Context) {
        palette =
            when (mode(context)) {
                ThemeMode.LIGHT -> Light
                ThemeMode.DARK -> Dark
                ThemeMode.AUTO -> {
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    if (hour >= 19 || hour < 7) Dark else Light
                }
            }
    }

    val display =
        FontFamily(
            Font(R.font.retrotv_chakra_semibold, FontWeight.SemiBold),
            Font(R.font.retrotv_chakra_bold, FontWeight.Bold),
        )

    val body =
        FontFamily(
            Font(R.font.retrotv_rubik_regular, FontWeight.Normal),
            Font(R.font.retrotv_rubik_medium, FontWeight.Medium),
            Font(R.font.retrotv_rubik_semibold, FontWeight.SemiBold),
            Font(R.font.retrotv_rubik_bold, FontWeight.Bold),
        )
}

/** Skrót do kolorów bieżącej palety. */
object K {
    val p get() = RetroTvTheme.palette
    val bg get() = p.bg
    val nav get() = p.nav
    val card get() = p.card
    val line get() = p.line
    val btn get() = p.btn
    val text get() = p.text
    val muted get() = p.muted
    val accent get() = p.accent
    val onAccent get() = p.onAccent
    val coral get() = p.coral
    val ok get() = p.ok
    val warn get() = p.warn
}
