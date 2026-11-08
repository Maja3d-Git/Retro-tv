package com.swordfish.lemuroid.retrotv.pad

import android.content.Context
import android.view.InputDevice

/** Kolejność padów = numery graczy (pierwszy na liście to gracz 1). Zapamiętana po identyfikatorze pada. */
object PadOrder {
    private const val PREFS = "retrotv"
    private const val KEY = "pad_order"

    private fun saved(context: Context): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun sort(
        context: Context,
        devices: List<InputDevice>,
    ): List<InputDevice> {
        val order = saved(context)
        return devices.withIndex()
            .sortedWith(compareBy({ order.indexOf(it.value.descriptor).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }

    /** Przesuwa pad o jedno miejsce wyżej (np. z gracza 2 na gracza 1). */
    fun moveUp(
        context: Context,
        current: List<InputDevice>,
        descriptor: String,
    ) {
        val list = current.map { it.descriptor }.toMutableList()
        val i = list.indexOf(descriptor)
        if (i <= 0) return
        list[i] = list[i - 1].also { list[i - 1] = list[i] }
        val rest = saved(context).filter { it !in list }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, (list + rest).joinToString("\n")).apply()
    }
}
