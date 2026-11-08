package com.swordfish.lemuroid.app.tv.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.tv.channel.ChannelUpdateWork
import com.swordfish.lemuroid.app.tv.favorites.TVFavoritesFragment
import com.swordfish.lemuroid.app.tv.games.TVGamesFragment
import com.swordfish.lemuroid.app.tv.home.TVHomeFragment
import com.swordfish.lemuroid.app.tv.search.TVSearchFragment
import com.swordfish.lemuroid.app.tv.shared.BaseTVActivity
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.lib.injection.PerActivity
import com.swordfish.lemuroid.lib.injection.PerFragment
import com.swordfish.lemuroid.lib.library.covers.CustomCoverManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.retrotv.update.UpdatePrompt
import androidx.activity.compose.setContent
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.app.tv.folderpicker.TVFolderPickerLauncher
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.retrotv.home.HomeActions
import com.swordfish.lemuroid.retrotv.home.HomeScreen
import com.swordfish.lemuroid.retrotv.home.HomeViewModel
import com.swordfish.lemuroid.retrotv.home.PlayStats
import com.swordfish.lemuroid.retrotv.onboarding.OnboardingActivity
import com.swordfish.lemuroid.retrotv.roms.RomFiles
import com.swordfish.lemuroid.retrotv.roms.RomManagerActivity
import android.net.Uri
import java.io.File
import com.swordfish.lemuroid.retrotv.system.TVSystemActivity
import com.swordfish.lemuroid.retrotv.ui.Palette
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import dagger.Provides
import dagger.android.ContributesAndroidInjector
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import javax.inject.Inject

@OptIn(DelicateCoroutinesApi::class)
class MainTVActivity : BaseTVActivity(), BusyActivity {
    private var pendingTeamGame: Game? = null
    private val REQUEST_LOBBY = 4801
    private var pendingBiosGame: Game? = null
    private val REQUEST_BIOS = 4802

    /** Retro TV: przed startem gry sprawdzamy BIOS i zamiast błędu pokazujemy, co wgrać. */
    private fun biosReady(game: Game): Boolean {
        val missing = com.swordfish.lemuroid.retrotv.bios.BiosCheck.missingFor(this, game)
        if (missing.isEmpty()) return true
        pendingBiosGame = game
        val system =
            runCatching { getString(com.swordfish.lemuroid.lib.library.GameSystem.findById(game.systemId).titleResId) }
                .getOrDefault(game.systemId)
        startActivityForResult(com.swordfish.lemuroid.retrotv.bios.BiosMissingActivity.intent(this, game, system, missing), REQUEST_BIOS)
        return false
    }

    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    var mainViewModel: MainTVViewModel? = null

    override fun activity(): Activity = this

    override fun isBusy(): Boolean = mainViewModel?.inProgress?.value ?: false

    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    private var appliedPalette: Palette? = null
    private var homeViewModel: HomeViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        appliedPalette = RetroTvTheme.palette

        val factory = MainTVViewModel.Factory(applicationContext)
        mainViewModel = ViewModelProvider(this, factory).get(MainTVViewModel::class.java)

        // Retro TV: nowy ekran główny w Compose zamiast interfejsu Leanback.
        homeViewModel =
            ViewModelProvider(this, HomeViewModel.Factory(retrogradeDb, applicationContext))
                .get(HomeViewModel::class.java)
        val homeViewModel = homeViewModel!!
        setContent {
            RetroTvContent {
                HomeScreen(homeViewModel, busy = false, actions = homeActions)
            }
        }

        ensureLegacyStoragePermissionsIfNeeded()

        if (savedInstanceState == null && OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else {
            UpdatePrompt.checkOnStart(this, lifecycleScope)
        }
    }

    override fun onResume() {
        super.onResume()
        // Motyw mógł zostać zmieniony w ekranie System albo minęła godzina trybu automatycznego.
        RetroTvTheme.apply(this)
        if (appliedPalette != null && appliedPalette != RetroTvTheme.palette) {
            recreate()
            return
        }
        homeViewModel?.refreshStats(applicationContext)
    }

    private val homeActions =
        object : HomeActions {
            override fun play(game: Game) {
                if (biosReady(game)) gameInteractor.onGamePlay(game)
            }

            override fun playTeam(game: Game) {
                if (!biosReady(game)) return
                pendingTeamGame = game
                startActivityForResult(
                    com.swordfish.lemuroid.retrotv.netplay.NetplayLobbyActivity.intent(this@MainTVActivity, game.title),
                    REQUEST_LOBBY,
                )
            }

            override fun restart(game: Game) {
                if (biosReady(game)) gameInteractor.onGameRestart(game)
            }

            override fun toggleFavorite(game: Game) = gameInteractor.onFavoriteToggle(game, !game.isFavorite)

            override fun pickFolder() {
                // Wbudowana przeglądarka folderów – wiele telewizorów nie ma systemowego okna wyboru.
                startActivity(android.content.Intent(this@MainTVActivity, com.swordfish.lemuroid.retrotv.roms.RomManagerActivity::class.java))
            }

            override fun deleteGame(game: Game): String {
                val uri = Uri.parse(game.fileUri)
                val internal = RomFiles.internalDir(this@MainTVActivity).canonicalPath
                return if (uri.scheme == "file" && uri.path != null && File(uri.path!!).canonicalPath.startsWith(internal)) {
                    File(uri.path!!).delete()
                    LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
                    "Usunięto plik. Gra zniknie z biblioteki po odświeżeniu."
                } else {
                    "Ta gra leży w Twoim folderze (np. na pendrivie lub w pamięci TV). Usuń plik ręcznie albo usuń cały folder w „Gry i ROM-y”."
                }
            }

            override fun joinOnline() {
                startActivity(Intent(this@MainTVActivity, com.swordfish.lemuroid.retrotv.netplay.NetplayGuestActivity::class.java))
            }

            override fun openRomManager() {
                startActivity(Intent(this@MainTVActivity, RomManagerActivity::class.java))
            }

            override fun rescan() = LibraryIndexScheduler.scheduleLibrarySync(applicationContext)

            override fun stopScan() = LibraryIndexScheduler.cancelLibrarySync(applicationContext)

            override fun openEmulationSettings() {
                startActivity(Intent(this@MainTVActivity, com.swordfish.lemuroid.retrotv.settings.EmulationSettingsActivity::class.java))
            }

            override fun openControllers() {
                startActivity(Intent(this@MainTVActivity, com.swordfish.lemuroid.retrotv.controllers.ControllersActivity::class.java))
            }

            override fun openSystem() {
                startActivity(Intent(this@MainTVActivity, TVSystemActivity::class.java))
            }
        }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_BIOS -> {
                val game = pendingBiosGame
                pendingBiosGame = null
                if (resultCode == Activity.RESULT_OK && game != null) gameInteractor.onGamePlay(game)
            }
            REQUEST_LOBBY -> {
                // „Rozpocznij grę” albo „Graj sam” w pokoju – uruchamiamy grę (pokój czeka w procesie gry).
                val game = pendingTeamGame
                pendingTeamGame = null
                if (resultCode == Activity.RESULT_OK && game != null) gameInteractor.onGamePlay(game)
            }
            BaseGameActivity.REQUEST_PLAY_GAME -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val played = data.getSerializableExtra(BaseGameActivity.PLAY_GAME_RESULT_GAME) as? Game
                    val duration = data.getLongExtra(BaseGameActivity.PLAY_GAME_RESULT_SESSION_DURATION, 0L)
                    if (played != null) PlayStats.record(applicationContext, played.id, duration)
                }
                GlobalScope.safeLaunch {
                    gameLaunchTaskHandler.handleGameFinish(false, this@MainTVActivity, resultCode, data)
                    ChannelUpdateWork.enqueue(applicationContext)
                }
            }
        }
    }

    private fun ensureLegacyStoragePermissionsIfNeeded() {
        if (TVHelper.isSAFSupported(this) || hasLegacyPermissions()) {
            return
        }

        val requestPermission = ActivityResultContracts.RequestPermission()
        val requestPermissionLauncher =
            registerForActivityResult(requestPermission) { isGranted ->
                if (!isGranted) {
                    finish()
                }
            }
        requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasLegacyPermissions(): Boolean {
        val result = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    @dagger.Module
    abstract class Module {
        @PerFragment
        @ContributesAndroidInjector(modules = [TVHomeFragment.Module::class])
        abstract fun tvHomeFragment(): TVHomeFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVGamesFragment.Module::class])
        abstract fun tvGamesFragment(): TVGamesFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVSearchFragment.Module::class])
        abstract fun tvSearchFragment(): TVSearchFragment

        @PerFragment
        @ContributesAndroidInjector(modules = [TVFavoritesFragment.Module::class])
        abstract fun tvFavoritesFragment(): TVFavoritesFragment

        @dagger.Module
        companion object {
            @Provides
            @PerActivity
            @JvmStatic
            fun gameInteractor(
                activity: MainTVActivity,
                retrogradeDb: RetrogradeDatabase,
                shortcutsGenerator: ShortcutsGenerator,
                gameLauncher: GameLauncher,
                customCoverManager: CustomCoverManager,
            ) = GameInteractor(activity, retrogradeDb, true, shortcutsGenerator, gameLauncher, customCoverManager)
        }
    }
}
