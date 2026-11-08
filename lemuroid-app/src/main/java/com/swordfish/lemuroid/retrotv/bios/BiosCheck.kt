package com.swordfish.lemuroid.retrotv.bios

import android.content.Context
import com.swordfish.lemuroid.lib.library.metaSystemID
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import java.io.File

/**
 * Sprawdzanie plików BIOS przed uruchomieniem gry. BIOS-ów nie dołączamy (prawa autorskie) –
 * użytkownik wgrywa własny plik raz, a biblioteka przenosi go na miejsce przy skanowaniu.
 */
object BiosCheck {
    private fun manager(context: Context) = BiosManager(DirectoriesManager(context.applicationContext))

    /** Brakujące pliki dla gry; pusta lista, gdy którykolwiek rdzeń ma komplet. */
    fun missingFor(
        context: Context,
        game: Game,
    ): List<String> {
        val system = runCatching { GameSystem.findById(game.systemId) }.getOrNull() ?: return emptyList()
        val bm = manager(context)
        val lists = system.systemCoreConfigs.map { bm.getMissingBiosFiles(it, game) }
        if (lists.isEmpty() || lists.any { it.isEmpty() }) return emptyList()
        return lists.minByOrNull { it.size } ?: emptyList()
    }

    /** Czy konsola w ogóle nie ruszy bez BIOS-u (np. Sega CD, Atari Lynx). */
    fun systemRequiresBios(system: GameSystem): Boolean =
        system.systemCoreConfigs.isNotEmpty() &&
            system.systemCoreConfigs.all { it.requiredBIOSFiles.isNotEmpty() || it.regionalBIOSFiles.isNotEmpty() }

    /** Konsola wymaga BIOS-u i nie ma żadnego kompletu (dla wymaganych regionalnych wystarczy jeden region). */
    fun systemBlocked(
        context: Context,
        system: GameSystem,
    ): Boolean {
        if (!systemRequiresBios(system)) return false
        val dir = DirectoriesManager(context.applicationContext).getSystemDirectory()
        return system.systemCoreConfigs.none { cfg ->
            cfg.requiredBIOSFiles.all { File(dir, it).exists() } &&
                (cfg.regionalBIOSFiles.isEmpty() || cfg.regionalBIOSFiles.values.any { File(dir, it).exists() })
        }
    }

    fun metaBlocked(
        context: Context,
        meta: MetaSystemID,
    ): Boolean {
        val systems = GameSystem.all().filter { runCatching { it.metaSystemID() }.getOrNull() == meta }
        return systems.isNotEmpty() && systems.all { systemBlocked(context, it) }
    }
}
