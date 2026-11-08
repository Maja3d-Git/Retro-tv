package com.swordfish.lemuroid.retrotv.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.retrotv.roms.RomFiles
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

/**
 * Telefon → telewizor: „Udostępnij → Retro TV”. Znajduje telewizor w sieci domowej
 * i wysyła pliki gier tak samo jak strona „Wyślij gry”.
 */
class ShareToTvActivity : ComponentActivity() {
    private data class Item(val uri: Uri, val name: String, val size: Long, val status: String = "czeka", val progress: Float = 0f, val done: Boolean = false, val failed: Boolean = false)

    private data class Tv(val name: String, val host: String, val port: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val items = mutableStateListOf<Item>()
    private val tvs = mutableStateListOf<Tv>()
    private var selectedTv by mutableStateOf<Tv?>(null)
    private var code by mutableStateOf("")
    private var manualIp by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)
    private var sending by mutableStateOf(false)
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private val prefs by lazy { getSharedPreferences("retrotv_share", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        code = prefs.getString("code", "") ?: ""
        manualIp = prefs.getString("ip", "") ?: ""
        readItems(intent)
        startDiscovery()
        setContent { RetroTvContent { Screen() } }
    }

    private fun readItems(intent: Intent) {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.streamUri()?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> uris.addAll(intent.streamUris())
        }
        uris.forEach { uri ->
            var name = uri.lastPathSegment ?: "plik"
            var size = -1L
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val ok = RomFiles.isRom(name)
            items.add(Item(uri, name, size, status = if (ok) "czeka" else "to nie jest plik gry", failed = !ok))
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            ?: emptyList()

    // ---------- wyszukiwanie telewizora ----------

    private fun startDiscovery() {
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}

                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {}

                override fun onStopDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {}

                override fun onServiceLost(info: NsdServiceInfo?) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(
                        info,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                info: NsdServiceInfo?,
                                errorCode: Int,
                            ) {}

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                runOnUiThread {
                                    val tv = Tv(info.serviceName, host, info.port)
                                    if (tvs.none { it.host == host }) tvs.add(tv)
                                    if (selectedTv == null) selectedTv = tv
                                }
                            }
                        },
                    )
                }
            }
        discovery = listener
        runCatching { manager.discoverServices("_retrotv._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // ---------- wysyłanie ----------

    private fun target(): Pair<String, Int>? {
        selectedTv?.let { return it.host to it.port }
        val ip = manualIp.trim()
        if (ip.isEmpty()) return null
        val host = ip.substringBefore(':')
        val port = ip.substringAfter(':', "${com.swordfish.lemuroid.retrotv.pad.PhonePadServer.HTTP_PORT}").toIntOrNull() ?: com.swordfish.lemuroid.retrotv.pad.PhonePadServer.HTTP_PORT
        return host to port
    }

    private fun sendAll() {
        val (host, port) = target() ?: run {
            message = "Nie znaleziono telewizora. Wpisz jego adres IP (zębatka → Połączenie w Retro TV)."
            return
        }
        if (code.length != 6) {
            message = "Wpisz 6-znakowy kod z ekranu Gry i ROM-y → Z telefonu."
            return
        }
        prefs.edit().putString("code", code).putString("ip", manualIp).apply()
        sending = true
        message = null
        scope.launch {
            for (i in items.indices) {
                val item = items[i]
                if (item.done || (item.failed && item.status == "to nie jest plik gry")) continue
                items[i] = item.copy(status = "wysyłanie…", failed = false)
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { upload(host, port, item) { p -> runOnUiThread { items[i] = items[i].copy(progress = p, status = "${(p * 100).toInt()}%") } } }
                    }
                items[i] =
                    result.fold(
                        onSuccess = { reply ->
                            val ok = reply.startsWith("OK")
                            val extra = reply.removePrefix("OK").removePrefix(":").trim()
                            items[i].copy(
                                status = if (ok) "wysłano ✓" + (if (extra.isNotEmpty()) " · $extra" else "") else reply,
                                done = ok,
                                failed = !ok,
                                progress = 1f,
                            )
                        },
                        onFailure = { e ->
                            Timber.w(e, "Wysyłanie do TV nie powiodło się")
                            items[i].copy(status = "błąd: ${e.message}", failed = true)
                        },
                    )
                if (items[i].status.contains("Nieprawidłowy kod")) {
                    message = "Kod się nie zgadza. Sprawdź go na telewizorze (Gry i ROM-y → Z telefonu)."
                    break
                }
            }
            sending = false
            if (items.all { it.done || it.failed }) {
                if (items.any { it.done }) message = "Gotowe. Gry pojawią się w bibliotece telewizora za chwilę."
            }
        }
    }

    /** Wysyła plik zwykłym zapytaniem PUT /upload do serwera Retro TV na telewizorze. */
    private fun upload(
        host: String,
        port: Int,
        item: Item,
        onProgress: (Float) -> Unit,
    ): String {
        var size = item.size
        var source = item.uri
        if (size < 0) {
            // Nieznany rozmiar – najpierw kopia w pamięci podręcznej.
            val tmp = java.io.File(cacheDir, "share-${System.currentTimeMillis()}")
            contentResolver.openInputStream(item.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            size = tmp.length()
            source = Uri.fromFile(tmp)
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 60_000
            val path = "/upload?k=" + URLEncoder.encode(code, "UTF-8") + "&name=" + URLEncoder.encode(item.name, "UTF-8")
            val out = socket.getOutputStream()
            out.write("PUT $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
            contentResolver.openInputStream(source)!!.use { input ->
                val buffer = ByteArray(128 * 1024)
                var sent = 0L
                var last = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    sent += read
                    val p = if (size > 0) (sent * 100 / size).toInt() else 0
                    if (p != last) {
                        last = p
                        onProgress(sent.toFloat() / size.coerceAtLeast(1))
                    }
                }
            }
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val status = reader.readLine() ?: throw java.io.IOException("Brak odpowiedzi telewizora")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val body = reader.readText().trim()
            if (source.scheme == "file") source.path?.let { java.io.File(it).delete() }
            return if (status.contains(" 200 ")) body.ifBlank { "OK" } else body.ifBlank { status }
        }
    }

    // ---------- ekran ----------

    @Composable
    private fun Screen() {
        val fieldColors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = K.text,
                unfocusedTextColor = K.text,
                focusedBorderColor = K.coral,
                unfocusedBorderColor = K.line,
                focusedLabelColor = K.coral,
                unfocusedLabelColor = K.muted,
                cursorColor = K.accent,
            )
        Column(
            Modifier.fillMaxSize().background(K.bg).padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Wyślij do Retro TV", color = K.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
            val tv = selectedTv
            Text(
                when {
                    tv != null -> "Telewizor: ${tv.name} (${tv.host})"
                    else -> "Szukam telewizora w sieci… Retro TV musi być włączone na telewizorze."
                },
                color = if (tv != null) K.ok else K.muted,
                fontSize = 14.sp,
            )
            if (tvs.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tvs.forEach { t -> TvButton(t.name.removePrefix("Retro TV "), primary = t == selectedTv, onClick = { selectedTv = t }) }
                }
            }
            if (tv == null) {
                OutlinedTextField(manualIp, { manualIp = it }, label = { Text("Albo wpisz adres IP telewizora") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                code,
                { code = it.lowercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                label = { Text("Kod z telewizora (Gry i ROM-y → Z telefonu)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.card).padding(12.dp)) {
                        Row {
                            Text(item.name, color = K.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                item.status,
                                color = if (item.done) K.ok else if (item.failed) K.coral else K.muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                        if (!item.done && item.progress > 0f) {
                            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(K.line)) {
                                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.accent))
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, color = K.text, fontSize = 14.sp, lineHeight = 20.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    if (sending) "Wysyłanie…" else "Wyślij",
                    primary = true,
                    enabled = !sending && items.any { !it.done && !(it.failed && it.status == "to nie jest plik gry") },
                    onClick = { sendAll() },
                )
                TvButton("Zamknij", onClick = { finish() })
            }
        }
    }

    override fun onDestroy() {
        discovery?.let { d -> runCatching { nsd?.stopServiceDiscovery(d) } }
        scope.cancel()
        super.onDestroy()
    }
}
