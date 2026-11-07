package com.swordfish.lemuroid.retrotv.roms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.TvButton
import com.swordfish.lemuroid.retrotv.ui.tvPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Dostęp do plików bez systemowego okna wyboru folderów (wiele telewizorów go nie ma):
 * zwykłe ścieżki, np. /storage/1234-ABCD dla pendrive'a.
 */
object FileAccess {
    data class Root(val label: String, val dir: File)

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Otwiera ekran zgody „Dostęp do wszystkich plików”. Zwraca false, gdy telewizor go nie ma. */
    fun openAllFilesSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val pkg = Uri.parse("package:${activity.packageName}")
        val intents =
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        for (intent in intents) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Timber.w(e, "Brak ekranu zgody na pliki: %s", intent.action)
            }
        }
        return false
    }

    fun adbCommand(context: Context) = "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"

    /** Pamięć wewnętrzna i podłączone pendrive'y / karty. */
    fun roots(context: Context): List<Root> {
        val result = mutableListOf<Root>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = runCatching { sm?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
        for (v in volumes) {
            val dir =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.directory
                } else {
                    runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as? File }.getOrNull()
                } ?: continue
            if (!dir.canRead() && !dir.isDirectory) continue
            val desc = runCatching { v.getDescription(context) }.getOrNull() ?: dir.name
            val label = if (v.isRemovable) "Pendrive / karta: $desc" else "Pamięć telewizora"
            result += Root(label, dir)
        }
        // Zapasowo: katalogi w /storage (niektóre telewizory nie zgłaszają pendrive'a jako woluminu).
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "self" && it.name != "emulated" && result.none { r -> r.dir.path == it.path } }
            ?.forEach { result += Root("Pendrive / karta: ${it.name}", it) }
        if (result.none { !it.label.startsWith("Pendrive") }) {
            result += Root("Pamięć telewizora", Environment.getExternalStorageDirectory())
        }
        return result
    }

    fun subfolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun romCount(dir: File): Int = dir.listFiles()?.count { it.isFile && RomFiles.isRom(it.name) } ?: 0

    /** Pliki gier w folderze i podfolderach (do importu). */
    suspend fun scanRoms(dir: File): List<RomEntry> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<RomEntry>()
            val queue = ArrayDeque(listOf(dir to 0))
            while (queue.isNotEmpty() && out.size < 5000) {
                val (d, depth) = queue.removeFirst()
                d.listFiles()?.forEach { f ->
                    when {
                        f.name.startsWith(".") -> {}
                        f.isDirectory && depth < 6 -> queue.addLast(f to depth + 1)
                        f.isFile && RomFiles.isRom(f.name) -> out += RomEntry(f.name, f.length(), Uri.fromFile(f).toString())
                    }
                }
            }
            out.sortedBy { it.name.lowercase() }
        }
}

/** Pełnoekranowa przeglądarka folderów sterowana pilotem. */
@Composable
fun FolderBrowser(
    title: String,
    confirmLabel: String,
    onPick: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val roots = remember { FileAccess.roots(context) }
    var current by remember { mutableStateOf<File?>(null) }
    val folders = remember(current) { current?.let { FileAccess.subfolders(it) } }
    val roms = remember(current) { current?.let { FileAccess.romCount(it) } ?: 0 }
    val firstFocus = remember { FocusRequester() }

    fun up() {
        val c = current ?: return onClose()
        current = if (roots.any { it.dir.path == c.path }) null else c.parentFile
    }
    BackHandler { up() }
    LaunchedEffect(current) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(K.bg).tvPane().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = K.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                current?.path ?: "Wybierz pamięć",
                color = K.muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    TvButton(confirmLabel, primary = true, modifier = Modifier.focusRequester(firstFocus), onClick = { onPick(current!!) })
                    TvButton("W górę", onClick = { up() })
                }
                TvButton("Anuluj", onClick = onClose, modifier = if (current == null) Modifier.focusRequester(firstFocus) else Modifier)
                if (current != null) {
                    Text(
                        if (roms > 0) "W tym folderze: $roms plików gier" else "Brak gier bezpośrednio w tym folderze",
                        color = if (roms > 0) K.ok else K.muted,
                        fontSize = 14.sp,
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current == null) {
                    if (roots.isEmpty()) item { Text("Nie znaleziono pamięci. Podłącz pendrive.", color = K.muted, fontSize = 15.sp) }
                    items(roots) { r -> FolderRow(r.label, r.dir.path) { current = r.dir } }
                } else {
                    val list = folders.orEmpty()
                    if (list.isEmpty()) item { Text("Brak podfolderów.", color = K.muted, fontSize = 15.sp) }
                    items(list) { f -> FolderRow("📁 ${f.name}", null) { current = f } }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) K.p.activeBg else K.card)
            .border(3.dp, if (focused) K.coral else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, color = if (focused) K.p.activeFg else K.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, color = K.muted, fontSize = 13.sp, maxLines = 1)
    }
}
