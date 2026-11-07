package com.swordfish.lemuroid.retrotv.pad

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Odbiorca poleceń z telefonów. W procesie głównym steruje menu aplikacji,
 * w procesie gry przekazuje przyciski do emulatora.
 */
interface PadSink {
    /** "menu" albo "game" – telefon pokazuje wtedy inny układ przycisków. */
    val mode: String

    /** Nazwa układu przycisków (patrz [PadLayouts]). */
    val layout: String

    /** Tytuł wyświetlany na telefonie (np. nazwa gry). */
    val title: String

    fun onKey(
        player: Int,
        keyCode: Int,
        pressed: Boolean,
    )

    fun onMotion(
        player: Int,
        source: String,
        x: Float,
        y: Float,
    ) {}

    fun onMenu(player: Int) {}

    fun onText(text: String) {}
}

data class PhoneInfo(val id: String, val name: String, val player: Int)

/**
 * Serwer pada w telefonie: strona HTTP z padem (port [HTTP_PORT]) i WebSocket z przyciskami ([WS_PORT]).
 * W danym momencie działa w jednym procesie: w głównym, gdy otwarte jest menu, albo w procesie gry.
 */
object PhonePadServer {
    const val HTTP_PORT = 8080
    const val WS_PORT = 8081
    const val MAX_PHONES = 4
    private const val PREFS = "retrotv_pad"

    private val main = Handler(Looper.getMainLooper())
    private var httpSocket: ServerSocket? = null
    private var wsServer: PadWebSocketServer? = null
    private var sink: PadSink? = null
    private lateinit var appContext: Context

    private val clients = ConcurrentHashMap<WebSocket, PhoneInfo>()
    private val phonesFlow = MutableStateFlow<List<PhoneInfo>>(emptyList())
    val phones: StateFlow<List<PhoneInfo>> = phonesFlow

    val isRunning: Boolean get() = wsServer != null

    /** Jednorazowy klucz parowania, wspólny dla obu procesów (zapisany w ustawieniach). */
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        val token = (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("token", token).commit()
        return token
    }

    fun pairingUrl(
        context: Context,
        ip: String,
    ): String = "http://$ip:$HTTP_PORT/?k=${token(context)}"

    /** Uruchamia serwer (albo tylko podmienia odbiorcę, jeśli już działa). */
    @Synchronized
    fun start(
        context: Context,
        newSink: PadSink,
    ) {
        appContext = context.applicationContext
        sink = newSink
        if (httpSocket == null) startHttp()
        if (wsServer != null) {
            broadcastWelcome()
            return
        }
        startWebSocket(attempt = 0)
    }

    @Synchronized
    fun stop() {
        unregisterNsd()
        runCatching { httpSocket?.close() }
        httpSocket = null
        val ws = wsServer
        wsServer = null
        // Zamykanie połączeń może chwilę potrwać – nie blokujemy wątku interfejsu.
        if (ws != null) thread(name = "retrotv-pad-stop", isDaemon = true) { runCatching { ws.stop(300) } }
        clients.clear()
        phonesFlow.value = emptyList()
        sink = null
    }

    /** Wysyła do wszystkich telefonów aktualny tryb i układ (np. po zmianie ekranu). */
    fun broadcastWelcome() {
        clients.forEach { (conn, info) -> sendWelcome(conn, info) }
    }

    private fun startWebSocket(attempt: Int) {
        try {
            val server = PadWebSocketServer(WS_PORT)
            server.isReuseAddr = true
            server.connectionLostTimeout = 10
            server.start()
            wsServer = server
            Timber.i("Pad w telefonie: serwer uruchomiony (proces %s)", sink?.mode)
        } catch (e: Exception) {
            if (attempt < 12) {
                main.postDelayed({ startWebSocket(attempt + 1) }, 400)
            } else {
                Timber.e(e, "Nie udało się uruchomić serwera pada")
            }
        }
    }

    const val NSD_TYPE = "_retrotv._tcp."
    private var nsdListener: android.net.nsd.NsdManager.RegistrationListener? = null

    /** Ogłasza telewizor w sieci domowej, żeby aplikacja na telefonie mogła go znaleźć. */
    private fun registerNsd() {
        if (nsdListener != null) return
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
        val info =
            android.net.nsd.NsdServiceInfo().apply {
                serviceName = "Retro TV (${android.os.Build.MODEL})"
                serviceType = NSD_TYPE
                port = HTTP_PORT
            }
        val listener =
            object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {
                    Timber.i("Retro TV ogłoszone w sieci jako %s", info.serviceName)
                }

                override fun onRegistrationFailed(
                    info: android.net.nsd.NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.w("Nie udało się ogłosić w sieci (kod %d)", errorCode)
                }

                override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) {}

                override fun onUnregistrationFailed(
                    info: android.net.nsd.NsdServiceInfo,
                    errorCode: Int,
                ) {}
            }
        nsdListener = listener
        runCatching { nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { nsdListener = null }
    }

    private fun unregisterNsd() {
        val listener = nsdListener ?: return
        nsdListener = null
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return
        runCatching { nsd.unregisterService(listener) }
    }

    private fun startHttp(attempt: Int = 0) {
        val server =
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(HTTP_PORT))
                }
            } catch (e: Exception) {
                // Port bywa jeszcze zajęty przez drugi proces (menu ↔ gra) – próbujemy ponownie.
                if (attempt < 12) {
                    main.postDelayed({ if (wsServer != null || sink != null) startHttp(attempt + 1) }, 400)
                } else {
                    Timber.w(e, "Port %d zajęty – strona pada niedostępna", HTTP_PORT)
                }
                return
            }
        httpSocket = server
        registerNsd()
        val page =
            runCatching { appContext.assets.open("retrotv_pad/index.html").readBytes() }
                .getOrElse { "Brak strony pada".toByteArray() }
        thread(name = "retrotv-pad-http", isDaemon = true) {
            while (!server.isClosed) {
                val client =
                    try {
                        server.accept()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        continue
                    }
                // Każde połączenie osobno: przeglądarki otwierają zapasowe połączenia, które nic nie wysyłają.
                thread(isDaemon = true) { serveHttp(client, page) }
            }
        }
    }

    /** Czyta jedną linię nagłówka bez buforowania (treść zapytania zostaje w strumieniu). */
    private fun readHeaderLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun serveHttp(
        client: java.net.Socket,
        page: ByteArray,
    ) {
        try {
            client.use { c ->
                c.soTimeout = 30_000
                val input = c.getInputStream()
                val request = readHeaderLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                // Wczytujemy całe zapytanie – zamknięcie gniazda z nieprzeczytanymi danymi
                // powoduje zerwanie połączenia (ERR_TOO_MANY_RETRIES w Chrome).
                while (true) {
                    val line = readHeaderLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = request.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                val (status, type, body) =
                    when {
                        path.contains("favicon") -> Triple("404 Not Found", "text/plain", ByteArray(0))
                        method == "PUT" && path.startsWith("/upload") -> handleUpload(path, headers, input)
                        path.startsWith("/upload") -> Triple("200 OK", "text/html; charset=utf-8", asset("retrotv_pad/upload.html"))
                        else -> Triple("200 OK", "text/html; charset=utf-8", page)
                    }
                val header =
                    "HTTP/1.1 $status\r\n" +
                        "Content-Type: $type\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                val out = c.getOutputStream()
                out.write(header.toByteArray())
                out.write(body)
                out.flush()
                c.shutdownOutput()
                // Dajemy przeglądarce chwilę na odebranie danych przed zamknięciem.
                runCatching { while (input.read() >= 0) { } }
            }
        } catch (e: Exception) {
            Timber.d(e, "Strona pada: połączenie przerwane")
        }
    }

    private fun asset(name: String): ByteArray =
        runCatching { appContext.assets.open(name).readBytes() }.getOrElse { "Brak strony".toByteArray() }

    /** Odbiera plik gry wysłany z telefonu (PUT /upload?k=…&name=…, treść = zawartość pliku). */
    private fun handleUpload(
        path: String,
        headers: Map<String, String>,
        input: java.io.InputStream,
    ): Triple<String, String, ByteArray> {
        val query =
            path.substringAfter('?', "").split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
        fun fail(
            code: String,
            msg: String,
        ) = Triple(code, "text/plain; charset=utf-8", msg.toByteArray())
        if (query["k"] != token(appContext)) return fail("403 Forbidden", "Nieprawidłowy kod – zeskanuj QR ponownie")
        val name = (query["name"] ?: "").substringAfterLast('/').substringAfterLast('\\')
        if (!com.swordfish.lemuroid.retrotv.roms.RomFiles.isRom(name)) return fail("415 Unsupported Media Type", "To nie jest plik gry: $name")
        val length = headers["content-length"]?.toLongOrNull() ?: return fail("411 Length Required", "Brak rozmiaru pliku")
        if (length > com.swordfish.lemuroid.retrotv.roms.RomFiles.freeBytes(appContext) - 50L * 1024 * 1024) {
            return fail("507 Insufficient Storage", "Za mało miejsca w telewizorze")
        }
        val dir = java.io.File(com.swordfish.lemuroid.retrotv.roms.RomFiles.internalDir(appContext), "telefon").apply { mkdirs() }
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val part = java.io.File(dir, "$safe.part")
        part.outputStream().use { out ->
            val buffer = ByteArray(128 * 1024)
            var left = length
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                left -= read
            }
            if (left > 0) {
                part.delete()
                return fail("400 Bad Request", "Przesyłanie przerwane")
            }
        }
        val target = java.io.File(dir, safe)
        target.delete()
        part.renameTo(target)
        val unpacked = com.swordfish.lemuroid.retrotv.roms.RomFiles.expandZipIfNeeded(target)
        Timber.i("Odebrano z telefonu: %s (%d B)", safe, length)
        main.removeCallbacks(rescanRunnable)
        main.postDelayed(rescanRunnable, 1500)
        return Triple("200 OK", "text/plain; charset=utf-8", (if (unpacked > 0) "OK: $unpacked gier" else "OK").toByteArray())
    }

    private val rescanRunnable =
        Runnable { com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler.scheduleLibrarySync(appContext) }

    fun uploadUrl(
        context: Context,
        ip: String,
    ): String = "http://$ip:$HTTP_PORT/upload?k=${token(context)}"

    private fun assignPlayer(id: String): Int {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taken = clients.values.map { it.player }.toSet()
        val remembered = prefs.getInt("slot_$id", 0)
        val slot =
            if (remembered in 1..MAX_PHONES && remembered !in taken) {
                remembered
            } else {
                (1..MAX_PHONES).firstOrNull { it !in taken } ?: return 0
            }
        prefs.edit().putInt("slot_$id", slot).apply()
        return slot
    }

    private fun sendWelcome(
        conn: WebSocket,
        info: PhoneInfo,
    ) {
        val s = sink ?: return
        val json =
            JSONObject()
                .put("t", "welcome")
                .put("player", info.player)
                .put("mode", s.mode)
                .put("layout", s.layout)
                .put("title", s.title)
        runCatching { conn.send(json.toString()) }
    }

    private fun publish() {
        phonesFlow.value = clients.values.sortedBy { it.player }
    }

    private class PadWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(
            conn: WebSocket,
            handshake: ClientHandshake,
        ) {
            val expected = token(appContext)
            if (!handshake.resourceDescriptor.contains("k=$expected")) {
                conn.close(4001, "Nieprawidłowy kod – zeskanuj QR ponownie")
            }
        }

        override fun onClose(
            conn: WebSocket,
            code: Int,
            reason: String?,
            remote: Boolean,
        ) {
            clients.remove(conn)?.let { Timber.i("Telefon %s rozłączony", it.name) }
            publish()
        }

        override fun onMessage(
            conn: WebSocket,
            message: String,
        ) {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return
            when (json.optString("t")) {
                "hello" -> {
                    val id = json.optString("id").take(64).ifEmpty { conn.remoteSocketAddress.toString() }
                    val name = json.optString("name", "Telefon").take(40)
                    val player = assignPlayer(id)
                    if (player == 0) {
                        conn.close(4002, "Podłączono już $MAX_PHONES telefony")
                        return
                    }
                    val info = PhoneInfo(id, name, player)
                    clients[conn] = info
                    Timber.i("Telefon %s połączony jako gracz %d", name, player)
                    publish()
                    sendWelcome(conn, info)
                }
                "k" -> {
                    val info = clients[conn] ?: return
                    val code = json.optInt("c")
                    val down = json.optInt("d") == 1
                    main.post { sink?.onKey(info.player, code, down) }
                }
                "m" -> {
                    val info = clients[conn] ?: return
                    val src = json.optString("s")
                    val x = json.optDouble("x", 0.0).toFloat().coerceIn(-1f, 1f)
                    val y = json.optDouble("y", 0.0).toFloat().coerceIn(-1f, 1f)
                    main.post { sink?.onMotion(info.player, src, x, y) }
                }
                "menu" -> {
                    val info = clients[conn] ?: return
                    main.post { sink?.onMenu(info.player) }
                }
                "text" -> {
                    if (clients[conn] == null) return
                    val text = json.optString("v").take(100)
                    main.post { sink?.onText(text) }
                }
                "ping" -> runCatching { conn.send("{\"t\":\"pong\"}") }
            }
        }

        override fun onError(
            conn: WebSocket?,
            ex: Exception,
        ) {
            Timber.w(ex, "Błąd połączenia z telefonem")
            if (conn == null && wsServer === this) {
                // Serwer nie wystartował (np. port zajęty przez drugi proces) – ponawiamy.
                wsServer = null
                main.postDelayed({ if (sink != null && wsServer == null) startWebSocket(1) }, 500)
            }
        }

        override fun onStart() {}
    }
}

/** Układy przycisków na telefonie dla poszczególnych konsol. */
object PadLayouts {
    fun forSystem(systemId: String): String =
        when (systemId) {
            "snes" -> "snes"
            "gba" -> "gba"
            "md", "scd" -> "genesis"
            "atari2600" -> "one"
            "psx", "psp", "n64", "nds", "3ds", "fbneo", "mame2003plus", "dos" -> "full"
            else -> "two"
        }
}
