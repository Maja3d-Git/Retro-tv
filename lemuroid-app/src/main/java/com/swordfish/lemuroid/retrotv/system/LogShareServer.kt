package com.swordfish.lemuroid.retrotv.system

import android.content.Context
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.swordfish.lemuroid.retrotv.RetroTvLog
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Mały serwer HTTP w sieci lokalnej, który udostępnia dziennik pod jednorazowym adresem.
 * Telefon skanuje kod QR i otwiera dziennik w przeglądarce. Serwer działa, dopóki ekran
 * System jest otwarty (i najwyżej [MAX_LIFETIME_MS]).
 */
class LogShareServer(private val context: Context) {
    companion object {
        const val PORT = 8765
        private const val MAX_LIFETIME_MS = 10 * 60 * 1000L
    }

    private var socket: ServerSocket? = null
    val token: String = randomToken()

    fun start() {
        if (socket != null) return
        val server =
            try {
                ServerSocket(PORT)
            } catch (e: Exception) {
                Timber.w(e, "Nie udało się uruchomić serwera logów na porcie %d", PORT)
                ServerSocket(0)
            }
        socket = server
        val startedAt = System.currentTimeMillis()
        thread(name = "retrotv-log-server", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    if (System.currentTimeMillis() - startedAt > MAX_LIFETIME_MS) {
                        client.close()
                        stop()
                        break
                    }
                    client.use { c ->
                        c.soTimeout = 5000
                        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        val path = requestLine.split(" ").getOrNull(1) ?: "/"
                        val ok = path.startsWith("/logs") && path.contains("t=$token")
                        val body =
                            if (ok) {
                                RetroTvLog.exportText(context).toByteArray()
                            } else {
                                "Nieprawidlowy adres. Zeskanuj kod QR ponownie na telewizorze.".toByteArray()
                            }
                        val header =
                            buildString {
                                append(if (ok) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
                                append("Content-Type: text/plain; charset=utf-8\r\n")
                                if (ok) append("Content-Disposition: inline; filename=\"retro-tv-log.txt\"\r\n")
                                append("Content-Length: ${body.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                        c.getOutputStream().apply {
                            write(header.toByteArray())
                            write(body)
                            flush()
                        }
                        if (ok) Timber.i("Dziennik pobrany przez %s", c.inetAddress.hostAddress)
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Timber.w(e, "Błąd serwera logów")
                }
            }
        }
    }

    val port: Int get() = socket?.localPort ?: PORT

    fun url(ip: String): String = "http://$ip:$port/logs?t=$token"

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun randomToken(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}

object QrCode {
    fun encode(text: String): BitMatrix {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
}
