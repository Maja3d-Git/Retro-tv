package com.swordfish.lemuroid.app.shared.startup

import android.content.Context
import androidx.startup.Initializer
import com.swordfish.lemuroid.app.shared.game.GameProcessLock
import timber.log.Timber

class GameProcessInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Timber.i("Requested initialization of game process tasks")
        // Retro TV: blokadę zakłada dopiero ekran gry (BaseGameActivity). W procesie gry działa też
        // poczekalnia pokoju – sama blokada procesu blokowała wtedy uruchomienie gry.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(DebugInitializer::class.java)
    }
}
