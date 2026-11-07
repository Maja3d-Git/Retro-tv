package com.swordfish.lemuroid.retrotv.update

import android.app.Activity
import android.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.swordfish.lemuroid.retrotv.system.RetroTvPrefs
import com.swordfish.lemuroid.retrotv.system.TVSystemActivity
import kotlinx.coroutines.launch
import timber.log.Timber

/** Sprawdza przy starcie aplikacji, czy jest nowa wersja, i proponuje aktualizację. */
object UpdatePrompt {
    private var checkedThisProcess = false

    fun checkOnStart(
        activity: Activity,
        scope: LifecycleCoroutineScope,
    ) {
        if (checkedThisProcess || !RetroTvPrefs.autoUpdateCheck(activity)) return
        checkedThisProcess = true
        scope.launch {
            val release =
                try {
                    Updater.fetchLatest()
                } catch (e: Exception) {
                    Timber.w(e, "Automatyczne sprawdzenie aktualizacji nie powiodło się")
                    return@launch
                }
            if (!release.isNewerThanInstalled || release.build <= RetroTvPrefs.skippedBuild(activity)) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            Timber.i("Dostępna aktualizacja %s", release.versionName)

            val notes = release.notes.ifBlank { "Poprawki i ulepszenia." }.take(600)
            AlertDialog.Builder(activity)
                .setTitle("Dostępna nowa wersja ${release.versionName}")
                .setMessage("Co nowego:\n\n$notes")
                .setPositiveButton("Aktualizuj") { _, _ -> TVSystemActivity.launchUpdate(activity) }
                .setNegativeButton("Później") { _, _ -> RetroTvPrefs.setSkippedBuild(activity, release.build) }
                .show()
        }
    }
}
