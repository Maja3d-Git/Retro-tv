package com.swordfish.lemuroid.retrotv.home

import android.content.Context

/** Statystyki gry: łączny czas i liczba uruchomień każdej gry (zapisywane w procesie głównym). */
object PlayStats {
    private const val PREFS = "retrotv_stats"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(
        context: Context,
        gameId: Int,
        durationMs: Long,
    ) {
        if (durationMs < 5_000) return
        val p = prefs(context)
        p.edit()
            .putLong("time_$gameId", p.getLong("time_$gameId", 0) + durationMs)
            .putInt("count_$gameId", p.getInt("count_$gameId", 0) + 1)
            .apply()
    }

    fun timeMs(
        context: Context,
        gameId: Int,
    ): Long = prefs(context).getLong("time_$gameId", 0)

    fun sessions(
        context: Context,
        gameId: Int,
    ): Int = prefs(context).getInt("count_$gameId", 0)

    fun totalMs(context: Context): Long = prefs(context).all.filterKeys { it.startsWith("time_") }.values.sumOf { (it as? Long) ?: 0L }

    fun topGameIds(
        context: Context,
        limit: Int,
    ): List<Int> =
        prefs(context).all
            .filterKeys { it.startsWith("time_") }
            .mapNotNull { (k, v) -> k.removePrefix("time_").toIntOrNull()?.let { it to ((v as? Long) ?: 0L) } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    fun format(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "mniej niż minuta"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
