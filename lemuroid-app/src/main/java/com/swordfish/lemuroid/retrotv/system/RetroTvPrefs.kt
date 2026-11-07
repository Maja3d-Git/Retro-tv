package com.swordfish.lemuroid.retrotv.system

import android.content.Context

/** Ustawienia własne Retro TV (oddzielone od ustawień Lemuroid). */
object RetroTvPrefs {
    private const val FILE = "retrotv"
    private const val AUTO_UPDATE_CHECK = "auto_update_check"
    private const val SKIPPED_BUILD = "skipped_build"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun autoUpdateCheck(context: Context): Boolean = prefs(context).getBoolean(AUTO_UPDATE_CHECK, true)

    fun setAutoUpdateCheck(
        context: Context,
        enabled: Boolean,
    ) = prefs(context).edit().putBoolean(AUTO_UPDATE_CHECK, enabled).apply()

    /** Numer wydania, które użytkownik pominął przyciskiem "Później". */
    fun skippedBuild(context: Context): Int = prefs(context).getInt(SKIPPED_BUILD, 0)

    fun setSkippedBuild(
        context: Context,
        build: Int,
    ) = prefs(context).edit().putInt(SKIPPED_BUILD, build).apply()
}
