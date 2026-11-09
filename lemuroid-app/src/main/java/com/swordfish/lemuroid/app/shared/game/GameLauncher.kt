package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** Zamyka proces gry (osobny proces „:game” tej samej aplikacji). */
private fun killGameProcess(activity: Activity) {
    val am = activity.getSystemService(android.app.ActivityManager::class.java) ?: return
    am.runningAppProcesses
        ?.filter { it.processName == activity.packageName + ":game" }
        ?.forEach { android.os.Process.killProcess(it.pid) }
}

class GameLauncher(
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
) {
    @OptIn(DelicateCoroutinesApi::class)
    fun launchGameAsync(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ): Boolean {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            // Retro TV: poprzednia gra została w tle (np. wyjście przyciskiem Home) – pytamy, czy ją zamknąć.
            runCatching {
                android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Poprzednia gra działa w tle")
                    .setMessage("Zamknąć ją i uruchomić „${game.title}”? Niezapisany postęp poprzedniej gry przepadnie.")
                    .setPositiveButton("Zamknij i graj") { _, _ ->
                        killGameProcess(activity)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            launchGameAsync(activity, game, loadSave, leanback)
                        }, 800)
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
            }.onFailure { activity.displayToast(R.string.game_process_another_game_running) }
            return false
        }

        GlobalScope.launch {
            val system = GameSystem.findById(game.systemId)
            val coreConfig = coresSelection.getCoreConfigForSystem(system)
            gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
            gameLaunchTaskHandler.markGameStarted(game)
            BaseGameActivity.launchGame(activity, coreConfig, game, loadSave, leanback)
        }

        return true
    }
}
