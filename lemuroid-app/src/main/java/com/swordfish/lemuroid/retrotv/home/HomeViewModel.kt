package com.swordfish.lemuroid.retrotv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    private val db: RetrogradeDatabase,
    appContext: Context,
) : ViewModel() {
    class Factory(
        private val db: RetrogradeDatabase,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(db, appContext) as T
    }

    data class HomeState(
        val loaded: Boolean = false,
        val recent: List<Game> = emptyList(),
        val favorites: List<Game> = emptyList(),
        val systems: List<MetaSystemInfo> = emptyList(),
        val indexing: Boolean = false,
        val scanning: Boolean = false,
    ) {
        /** Gra do karty "Kontynuuj": ostatnio uruchomiona, ulubiona lub nie. */
        val continueGame: Game?
            get() = (recent + favorites).filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt ?: 0L }
    }

    private val monitor = PendingOperationsMonitor(appContext)

    val home: StateFlow<HomeState> =
        combine(
            db.gameDao().selectFirstRecents(20),
            db.gameDao().selectFirstFavorites(20),
            systems(appContext),
            monitor.anyLibraryOperationInProgress(),
            monitor.isDirectoryScanInProgress(),
        ) { recent, favorites, systems, indexing, scanning ->
            HomeState(true, recent, favorites, systems, indexing, scanning)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    val selectedSystem = MutableStateFlow<MetaSystemID?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val libraryGames: StateFlow<List<Game>?> =
        selectedSystem
            .flatMapLatest { meta ->
                if (meta == null) {
                    flowOf(null)
                } else {
                    db.gameDao().observeBySystems(meta.systemIDs.map { it.dbname })
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun systems(appContext: Context): Flow<List<MetaSystemInfo>> =
        db.gameDao().selectSystemsWithCount().map { counts ->
            counts.asSequence()
                .filter { it.count > 0 }
                .map { GameSystem.findById(it.systemId).metaSystemID() to it.count }
                .groupBy({ it.first }, { it.second })
                .map { (meta, list) -> MetaSystemInfo(meta, list.sum()) }
                .sortedBy { it.getName(appContext) }
                .toList()
        }
}
