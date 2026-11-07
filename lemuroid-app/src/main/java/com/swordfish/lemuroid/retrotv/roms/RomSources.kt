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
        (GameSystem.all().flatMap { it.supportedExtensions } + listOf("zip", "cue", "bin", "m3u", "chd", "iso"))
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
            target
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

/** Katalog darmowych gier homebrew. Lista leży w repozytorium, pliki pobieramy z wydań autorów na GitHubie. */
object HomebrewCatalog {
    data class Item(
        val title: String,
        val system: String,
        val repo: String,
        val extensions: List<String>,
        val author: String,
        val license: String,
        val description: String,
    )

    private const val CATALOG_URL = "https://raw.githubusercontent.com/Maja3d-Git/retro-tv/main/catalog/homebrew.json"

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "RetroTV-Android").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Serwer odpowiedział kodem ${r.code}")
            return r.body?.string() ?: throw IOException("Pusta odpowiedź")
        }
    }

    suspend fun load(): List<Item> =
        withContext(Dispatchers.IO) {
            val json = JSONObject(get(CATALOG_URL))
            val games = json.getJSONArray("games")
            (0 until games.length()).map { i ->
                val g = games.getJSONObject(i)
                val ext = g.optJSONArray("ext")
                Item(
                    title = g.getString("title"),
                    system = g.getString("system"),
                    repo = g.getString("repo"),
                    extensions = (0 until (ext?.length() ?: 0)).map { ext!!.getString(it).lowercase(Locale.US) },
                    author = g.optString("author"),
                    license = g.optString("license"),
                    description = g.optString("description"),
                )
            }
        }

    data class Asset(val name: String, val url: String, val size: Long)

    /** Znajduje plik gry w najnowszym wydaniu autora. */
    suspend fun resolve(item: Item): Asset =
        withContext(Dispatchers.IO) {
            val json = JSONObject(get("https://api.github.com/repos/${item.repo}/releases/latest"))
            val assets = json.optJSONArray("assets") ?: throw IOException("Autor nie udostępnia plików do pobrania")
            val list =
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .map { Asset(it.getString("name"), it.getString("browser_download_url"), it.optLong("size")) }
            list.firstOrNull { a -> item.extensions.any { a.name.lowercase(Locale.US).endsWith(".$it") } }
                ?: list.firstOrNull { it.name.lowercase(Locale.US).endsWith(".zip") }
                ?: throw IOException("W najnowszym wydaniu nie ma pliku gry")
        }

    fun open(asset: Asset): InputStream {
        val request = Request.Builder().url(asset.url).header("User-Agent", "RetroTV-Android").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Pobieranie nie powiodło się (kod ${response.code})")
        }
        return response.body?.byteStream() ?: throw IOException("Pusta odpowiedź")
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

/**
 * Wyszukiwanie gier w archive.org – tylko wśród pozycji oznaczonych przez autorów
 * jako domena publiczna albo Creative Commons (homebrew, darmowe gry).
 */
object ArchiveSearch {
    data class Item(val id: String, val title: String, val license: String, val description: String)

    data class FileEntry(val itemId: String, val name: String, val size: Long) {
        val url: String get() = "https://archive.org/download/$itemId/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "RetroTV-Android").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("archive.org odpowiedział kodem ${r.code}")
            return r.body?.string() ?: throw IOException("Pusta odpowiedź")
        }
    }

    fun licenseLabel(url: String): String =
        when {
            url.contains("publicdomain") -> "domena publiczna"
            url.contains("creativecommons") -> "Creative Commons"
            else -> "licencja autora"
        }

    suspend fun search(query: String): List<Item> =
        withContext(Dispatchers.IO) {
            val words = query.trim().replace(Regex("""[^\p{L}\p{N}\s]"""), " ").trim()
            val q =
                "(${words.ifBlank { "homebrew" }}) AND mediatype:software AND " +
                    "(licenseurl:*creativecommons* OR licenseurl:*publicdomain*)"
            val url =
                "https://archive.org/advancedsearch.php?q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=licenseurl&fl%5B%5D=description" +
                    "&rows=60&page=1&output=json"
            val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
            (0 until docs.length()).map { docs.getJSONObject(it) }.map { d ->
                val desc =
                    when (val raw = d.opt("description")) {
                        is org.json.JSONArray -> raw.optString(0)
                        null -> ""
                        else -> raw.toString()
                    }
                Item(
                    id = d.getString("identifier"),
                    title = d.optString("title", d.getString("identifier")),
                    license = licenseLabel(d.optString("licenseurl")),
                    description = desc.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(160),
                )
            }
        }

    /** Pliki gier w danej pozycji (pomija zrzuty ekranu, opisy itp.). */
    suspend fun files(itemId: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val files = JSONObject(get("https://archive.org/metadata/$itemId")).optJSONArray("files") ?: return@withContext emptyList()
            (0 until files.length()).map { files.getJSONObject(it) }
                .filter { RomFiles.isRom(it.optString("name")) && it.optString("source") != "metadata" }
                .map { FileEntry(itemId, it.getString("name"), it.optString("size").toLongOrNull() ?: 0L) }
        }

    fun open(file: FileEntry): InputStream {
        val request = Request.Builder().url(file.url).header("User-Agent", "RetroTV-Android").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Pobieranie nie powiodło się (kod ${response.code})")
        }
        return response.body?.byteStream() ?: throw IOException("Pusta odpowiedź")
    }
}
