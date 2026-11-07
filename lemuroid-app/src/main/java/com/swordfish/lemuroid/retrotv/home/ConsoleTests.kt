package com.swordfish.lemuroid.retrotv.home

import android.content.Context

/** Wyniki ostatniego uruchomienia gry dla każdej konsoli (do ekranu Test konsol). */
object ConsoleTests {
    private const val PREFS = "retrotv_console_tests"

    data class Result(val ok: Boolean, val detail: String, val time: Long)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(
        context: Context,
        systemId: String,
        ok: Boolean,
        detail: String,
    ) {
        prefs(context).edit()
            .putBoolean("ok_$systemId", ok)
            .putString("detail_$systemId", detail.take(200))
            .putLong("time_$systemId", System.currentTimeMillis())
            .apply()
    }

    fun get(
        context: Context,
        systemId: String,
    ): Result? {
        val p = prefs(context)
        if (!p.contains("ok_$systemId")) return null
        return Result(p.getBoolean("ok_$systemId", false), p.getString("detail_$systemId", "") ?: "", p.getLong("time_$systemId", 0))
    }
}
