package com.swordfish.lemuroid.retrotv.pad

import android.content.Context
import android.view.InputDevice
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

/**
 * Urządzenia ukryte przez użytkownika (np. klawiatura z touchpadem rozpoznana jako pad):
 * nie pokazują się na liście graczy i nie zajmują miejsca gracza w grze.
 */
object PadIgnore {
    private const val PREFS = "retrotv"
    private const val KEY = "pad_ignored"

    @Volatile
    private var ignored: Map<String, String> = emptyMap()

    /** descriptor → nazwa (do listy „Ukryte urządzenia”). */
    fun all(): Map<String, String> = ignored

    fun isIgnored(device: InputDevice): Boolean = ignored.containsKey(device.descriptor)

    fun load(context: Context) {
        ignored =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet()).orEmpty()
                .mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, ignored.map { "${it.key}\t${it.value}" }.toSet()).apply()
    }

    fun hide(
        context: Context,
        device: InputDevice,
    ) {
        ignored = ignored + (device.descriptor to device.name)
        save(context)
        // Emulator też przestaje traktować urządzenie jako pad (osobne ustawienie silnika Lemuroid).
        SharedPreferencesHelper.getSharedPreferences(context.applicationContext).edit()
            .putBoolean(InputDeviceManager.computeEnabledGamePadPreference(device), false).apply()
    }

    fun restore(
        context: Context,
        descriptor: String,
    ) {
        ignored = ignored - descriptor
        save(context)
        val prefs = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        val prefix = "pref_key_gamepad_enabled_"
        prefs.edit().remove(prefix + descriptor).apply()
    }
}
