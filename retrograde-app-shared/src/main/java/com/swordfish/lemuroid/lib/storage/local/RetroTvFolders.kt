package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

/** Retro TV: lista folderów z grami (główny folder Lemuroid + dodatkowe). */
object RetroTvFolders {
    private const val KEY_EXTRA = "retrotv_extra_folders"

    private fun prefs(context: Context) = SharedPreferencesHelper.getLegacySharedPreferences(context)

    fun mainFolder(context: Context): String? = prefs(context).getString(context.getString(R.string.pref_key_extenral_folder), null)

    fun clearMainFolder(context: Context) {
        prefs(context).edit().remove(context.getString(R.string.pref_key_extenral_folder)).apply()
    }

    fun extraFolders(context: Context): List<String> =
        prefs(context).getString(KEY_EXTRA, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun addExtraFolder(
        context: Context,
        uri: String,
    ) {
        val list = (extraFolders(context) + uri).distinct()
        prefs(context).edit().putString(KEY_EXTRA, list.joinToString("\n")).apply()
    }

    fun removeExtraFolder(
        context: Context,
        uri: String,
    ) {
        val list = extraFolders(context).filter { it != uri }
        prefs(context).edit().putString(KEY_EXTRA, list.joinToString("\n")).apply()
    }

    fun allFolders(context: Context): List<String> = (listOfNotNull(mainFolder(context)) + extraFolders(context)).distinct()
}
