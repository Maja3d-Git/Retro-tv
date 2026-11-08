package com.swordfish.lemuroid.retrotv.roms

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.swordfish.lemuroid.lib.library.GameSystem
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Plik gry znaleziony w źródle (pendrive, NAS, katalog). */
data class RomEntry(
    val name: String,
    val size: Long,
    val source: String,
    val isDirectory: Boolean = false,
)

object RomFiles {
    /** Rozszerzenia plików, które Retro TV potrafi uruchomić (plus archiwa ZIP). */
    val extensions: Set<String> by lazy {
        (GameSystem.all().flatMap { it.supportedExtensions } + listOf("zip", "cue", "bin", "m3u", "chd", "iso", "img", "rom"))
            .map { it.lowercase(Locale.US) }
            .toSet()
    }

    fun isRom(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.US) in extensions

    /** Folder w pamięci telewizora, który biblioteka skanuje automatycznie. */
    fun internalDir(context: Context): File = File(context.getExternalFilesDir(null), "roms").apply { mkdirs() }

    fun freeBytes(context: Context): Long = runCatching { StatFs(internalDir(context).path).availableBytes }.getOrDefault(0L)

    fun totalBytes(context: Context): Long = runCatching { StatFs(internalDir(context).path).totalBytes }.getOrDefault(0L)

    fun internalFiles(context: Context): List<File> =
        internalDir(context).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }.toList()

    fun formatSize(bytes: Long): String =
        when {
            bytes >= 1L shl 30 -> String.format(Locale("pl"), "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale("pl"), "%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
            else -> "$bytes B"
        }

    /** Nazwa folderu wybranego przez okno systemowe. */
    fun folderLabel(
        context: Context,
        uri: String,
    ): String {
        val parsed = Uri.parse(uri)
        val name = runCatching { DocumentFile.fromTreeUri(context, parsed)?.name }.getOrNull()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull() ?: ""
        val volume = docId.substringBefore(':')
        val where =
            when {
                volume.equals("primary", true) -> "pamięć telewizora"
                volume.isNotBlank() -> "nośnik $volume"
                else -> "folder"
            }
        return "${name ?: docId.substringAfter(':').ifBlank { uri }} ($where)"
    }

    /** Wyszukuje pliki gier w folderze wybranym np. na pendrivie. */
    suspend fun scanTree(
        context: Context,
        treeUri: Uri,
    ): List<RomEntry> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<RomEntry>()
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext result
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty() && result.size < 5000) {
                val dir = queue.removeFirst()
                dir.listFiles().forEach { f ->
                    val name = f.name ?: return@forEach
                    if (name.startsWith(".")) return@forEach
                    if (f.isDirectory) {
                        queue.add(f)
                    } else if (isRom(name)) {
                        result.add(RomEntry(name, f.length(), f.uri.toString()))
                    }
                }
            }
            result.sortedBy { it.name.lowercase() }
        }

    /** Kopiuje strumień do pamięci telewizora, raportując postęp 0..1. */
    suspend fun copyIn(
        context: Context,
        subfolder: String,
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: suspend (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(internalDir(context), subfolder).apply { mkdirs() }
            val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val target = File(dir, safeName)
            val part = File(dir, "$safeName.part")
            open().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (size > 0) {
                            val p = (done * 100 / size).toInt()
                            if (p != last) {
                                last = p
                                onProgress(done.toFloat() / size)
                            }
                        }
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Nie udało się zapisać $safeName")
            Timber.i("Zaimportowano %s (%d B) do %s", safeName, target.length(), subfolder)
            expandZipIfNeeded(target)
            target
        }

    /**
     * ZIP z kilkoma grami rozpakowujemy na osobne pliki (biblioteka rozpoznaje grę w ZIP-ie
     * tylko wtedy, gdy jest w nim jedna gra). ZIP z jedną grą zostaje bez zmian.
     */
    fun expandZipIfNeeded(file: File): Int {
        if (!file.name.lowercase(Locale.US).endsWith(".zip")) return 0
        val entries =
            runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    zip.entries().toList().filter { !it.isDirectory && isRom(it.name) && !it.name.lowercase(Locale.US).endsWith(".zip") }.map { it.name }
                }
            }.getOrElse { return 0 }
        if (entries.size <= 1) return 0
        val dir = File(file.parentFile, file.nameWithoutExtension.replace(Regex("""[\\/:*?"<>|]"""), "_")).apply { mkdirs() }
        var count = 0
        java.util.zip.ZipFile(file).use { zip ->
            entries.forEach { name ->
                val entry = zip.getEntry(name) ?: return@forEach
                val out = File(dir, name.substringAfterLast('/').replace(Regex("""[\\/:*?"<>|]"""), "_"))
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                count++
            }
        }
        file.delete()
        Timber.i("Rozpakowano %d gier z %s", count, file.name)
        return count
    }
}

/** Dostęp do udziałów sieciowych (NAS, komputer z Windows) przez SMB. */
class SmbSource(
    val host: String,
    val share: String,
    private val user: String,
    private val password: String,
) {
    private val context by lazy {
        val props =
            Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.responseTimeout", "15000")
                setProperty("jcifs.smb.client.soTimeout", "20000")
            }
        val base = BaseContext(PropertyConfiguration(props))
        if (user.isBlank()) {
            base.withAnonymousCredentials()
        } else {
            val domain = if (user.contains('\\')) user.substringBefore('\\') else ""
            val name = user.substringAfter('\\')
            base.withCredentials(NtlmPasswordAuthenticator(domain, name, password))
        }
    }

    private fun url(path: String): String {
        val clean = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return "smb://${host.trim()}/${share.trim('/')}/$clean"
    }

    suspend fun list(path: String): List<RomEntry> =
        withContext(Dispatchers.IO) {
            SmbFile(url(path), context).listFiles()
                .filter { !it.name.startsWith(".") }
                .mapNotNull { f ->
                    val dir = f.isDirectory
                    val name = f.name.trimEnd('/')
                    when {
                        dir -> RomEntry(name, 0, "${path.trim('/')}/$name".trim('/'), isDirectory = true)
                        RomFiles.isRom(name) -> RomEntry(name, f.length(), "${path.trim('/')}/$name".trim('/'))
                        else -> null
                    }
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

    fun open(path: String): InputStream {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return SmbFile(url(parent) + name, context).inputStream
    }
}

/** Pobieranie pliku gry z dowolnego linku (np. strona autora, itch.io, archive.org). */
object LinkDownload {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()
    }

    data class Target(val name: String, val size: Long, val response: okhttp3.Response)

    suspend fun open(url: String): Target =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url.trim()).header("User-Agent", "RetroTV-Android").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("Serwer odpowiedział kodem ${response.code}")
            }
            val disposition = response.header("Content-Disposition").orEmpty()
            val fromHeader = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""").find(disposition)?.groupValues?.get(1)
            val fromUrl = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }
            val name = java.net.URLDecoder.decode(fromHeader ?: fromUrl ?: "gra", "UTF-8")
            if (!RomFiles.isRom(name)) {
                response.close()
                throw IOException("„$name” nie wygląda na plik gry (obsługiwane m.in. .nes, .sfc, .gba, .zip)")
            }
            Target(name, response.body?.contentLength() ?: -1L, response)
        }
}
