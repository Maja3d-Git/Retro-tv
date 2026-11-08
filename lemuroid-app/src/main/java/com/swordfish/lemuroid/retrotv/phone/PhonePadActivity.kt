package com.swordfish.lemuroid.retrotv.phone

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.lemuroid.retrotv.netplay.Signaling
import com.swordfish.lemuroid.retrotv.pad.PadRelay
import com.swordfish.lemuroid.retrotv.share.ShareToTvActivity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import timber.log.Timber
import java.net.URI
import java.util.UUID

/**
 * Pad na telefonie w aplikacji Retro TV. Ten sam wygląd co strona z kodu QR, ale połączenie
 * zestawia aplikacja: najpierw w sieci domowej, a gdy się nie da – przez internet.
 */
class PhonePadActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CODE = "code"

        fun start(
            context: Context,
            code: String,
        ) {
            context.startActivity(Intent(context, PhonePadActivity::class.java).putExtra(EXTRA_CODE, code))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var code: String
    private val cid = UUID.randomUUID().toString().replace("-", "").take(12)

    @Volatile
    private var lan: WebSocketClient? = null

    @Volatile
    private var relay: Signaling? = null

    @Volatile
    private var relayReady = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var connecting = false

    @Volatile
    private var welcomed = false

    private var wasStopped = false

    private val prefs by lazy { getSharedPreferences("retrotv_share", Context.MODE_PRIVATE) }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val list = ArrayList<Uri>(uris)
            val intent =
                Intent(this, ShareToTvActivity::class.java).apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
                    clipData = ClipData.newRawUri("", list.first()).apply { list.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(intent)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = (intent.getStringExtra(EXTRA_CODE) ?: "").trim().lowercase()
        // Pionowo podczas łączenia; poziomo dopiero, gdy telewizor przyjmie pad.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        web =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.BLACK)
                addJavascriptInterface(Bridge(), "RetroTVNative")
                loadUrl("file:///android_asset/retrotv_pad/index.html?k=${Uri.encode(code)}")
            }
        setContentView(web)
    }

    /** Zamyka oba połączenia (np. gdy aplikacja schodzi z ekranu). */
    private fun disconnectAll() {
        relay?.let { r ->
            r.send(PadRelay.inTopic(code), JSONObject().put("t", "bye").put("cid", cid))
            r.close()
        }
        relay = null
        relayReady = false
        val l = lan
        lan = null
        runCatching { l?.close() }
        connecting = false
    }

    /** Rozłączenie dopiero po dłuższym pobycie w tle – krótkie wyjście (zrzut ekranu, aparat) nie zrywa pada. */
    private val backgroundDisconnect =
        Runnable {
            wasStopped = true
            disconnectAll()
        }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(backgroundDisconnect)
        main.postDelayed(backgroundDisconnect, 90_000)
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(backgroundDisconnect)
        val alive = lan?.isOpen == true || relayReady
        if (wasStopped || !alive) {
            wasStopped = false
            if (welcomed || lan != null || relay != null) closed(1006, "")
        }
    }

    override fun onDestroy() {
        destroyed = true
        disconnectAll()
        web.destroy()
        super.onDestroy()
    }

    private fun js(script: String) {
        if (destroyed) return
        main.post { if (!destroyed) web.evaluateJavascript(script, null) }
    }

    private fun status(text: String) = js("window.rtvNative&&rtvNative.status(${JSONObject.quote(text)})")

    private fun opened() = js("window.rtvNative&&rtvNative.open()")

    private fun deliver(text: String) {
        if (text.contains("\"welcome\"")) {
            welcomed = true
            main.post { if (!destroyed) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        }
        js("window.rtvNative&&rtvNative.msg(${JSONObject.quote(text)})")
    }

    private fun closed(
        code: Int,
        reason: String,
    ) = js("window.rtvNative&&rtvNative.close($code,${JSONObject.quote(reason)})")

    private inner class Bridge {
        @JavascriptInterface
        fun connect() = main.post { startConnection() }

        @JavascriptInterface
        fun send(text: String) {
            lan?.takeIf { it.isOpen }?.let {
                runCatching { it.send(text) }
                return
            }
            val r = relay ?: return
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            r.send(PadRelay.inTopic(code), json.put("cid", cid))
        }

        @JavascriptInterface
        fun buzz() {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(12, 120)) else @Suppress("DEPRECATION") v.vibrate(12)
            }
        }

        @JavascriptInterface
        fun deviceName(): String = (Build.MODEL ?: "Telefon").take(30)

        @JavascriptInterface
        fun pickFiles() = main.post { runCatching { filePicker.launch(arrayOf("*/*")) } }
    }

    // ---------- połączenie ----------

    private fun startConnection() {
        if (destroyed || wasStopped || connecting) return
        if (relayReady) {
            // Ponowne połączenie strony przez internet – serwer jest już gotowy.
            opened()
            return
        }
        if (lan?.isOpen == true) {
            opened()
            return
        }
        connecting = true
        // Wybrany na telefonie wygląd pada (auto = wg konsoli na telewizorze).
        val layout = prefs.getString("pad_layout", "auto") ?: "auto"
        js("window.rtvLayout=" + if (layout == "auto") "null" else JSONObject.quote(layout))
        // Zapamiętany telewizor: łączymy od razu, bez szukania w sieci.
        val last = prefs.getString("last_host", null)
        val lastHost = last?.substringBefore(':')?.takeIf { it.isNotBlank() }
        val lastPort = last?.substringAfter(':', "")?.toIntOrNull() ?: com.swordfish.lemuroid.retrotv.pad.PhonePadServer.HTTP_PORT
        if (lastHost != null) {
            status("Łączę z telewizorem…")
            tryLan(lastHost, lastPort) { searchAndConnect() }
        } else {
            searchAndConnect()
        }
    }

    private fun searchAndConnect() {
        status("Szukam telewizora w sieci domowej…")
        findTv { host, port ->
            if (destroyed || wasStopped) return@findTv
            if (host == null) startRelay() else tryLan(host, port) { startRelay() }
        }
    }

    private fun tryLan(
        host: String,
        httpPort: Int,
        onFail: () -> Unit,
    ) {
        var failed = false

        fun fail() {
            if (failed) return
            failed = true
            main.post { if (!destroyed && !wasStopped) onFail() }
        }
        var wasOpen = false
        val uri = URI("ws://$host:${httpPort + 1}/pad?k=${Uri.encode(code)}")
        val client =
            object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    wasOpen = true
                    connecting = false
                    prefs.edit().putString("last_host", "$host:$httpPort").apply()
                    Timber.i("Pad: połączono w sieci domowej z %s", host)
                    status("Połączono przez sieć domową")
                    js("window.rtvLink='sieć domowa'")
                    opened()
                }

                override fun onMessage(message: String) = deliver(message)

                override fun onClose(
                    code: Int,
                    reason: String?,
                    remote: Boolean,
                ) {
                    if (lan === this) lan = null
                    if (wasOpen) {
                        closed(code, reason ?: "")
                    } else {
                        Timber.w("Pad: sieć domowa niedostępna (%d %s)", code, reason)
                        fail()
                    }
                }

                override fun onError(ex: Exception?) {
                    Timber.w(ex, "Pad: błąd połączenia w sieci domowej")
                }
            }
        client.connectionLostTimeout = 10
        lan = client
        runCatching { client.connect() }.onFailure { fail() }
        // Brak odpowiedzi w sieci domowej – nie czekamy dłużej niż kilka sekund.
        main.postDelayed({
            if (!wasOpen && lan === client) {
                lan = null
                runCatching { client.close() }
                fail()
            }
        }, 4_000)
    }

    private fun startRelay() {
        if (destroyed || relay != null) return
        status("Łączę przez internet…")
        val sig =
            Signaling(
                clientId = "phpad-$cid",
                subscribeTopic = PadRelay.outTopic(code, cid),
                onMessage = { json ->
                    if (json.optString("t") == "close") {
                        closed(json.optInt("code", 4000), json.optString("reason"))
                    } else {
                        deliver(json.toString())
                    }
                },
                onState = { ok ->
                    if (ok) {
                        // Także po ponownym połączeniu z serwerem – strona wysyła wtedy „hello” jeszcze raz.
                        relayReady = true
                        connecting = false
                        status("Połączono przez internet – czekam na telewizor…")
                        js("window.rtvLink='internet (możliwe opóźnienie)'")
                        opened()
                        main.postDelayed({
                            if (!welcomed && !destroyed) {
                                status("Telewizor nie odpowiada. Sprawdź, czy Retro TV jest otwarte na telewizorze (ekran główny albo gra).")
                            }
                        }, 10_000)
                    } else {
                        status("Brak internetu. Sprawdź połączenie telefonu.")
                    }
                },
            )
        relay = sig
        sig.connect()
    }

    /** Szuka telewizora: najpierw ostatni znany adres, potem ogłoszenie w sieci (NSD). */
    private fun findTv(onResult: (String?, Int) -> Unit) {
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        var done = false

        fun finish(
            host: String?,
            port: Int,
        ) {
            if (done) return
            done = true
            main.post { onResult(host, port) }
        }
        if (manager == null) {
            fallbackHost(::finish)
            return
        }
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}

                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) = fallbackHost(::finish)

                override fun onStopDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {}

                override fun onServiceLost(info: NsdServiceInfo?) {}

                @Suppress("DEPRECATION")
                override fun onServiceFound(info: NsdServiceInfo) {
                    runCatching {
                        manager.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo?,
                                    errorCode: Int,
                                ) {}

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host?.hostAddress ?: return
                                    finish(host, info.port)
                                }
                            },
                        )
                    }
                }
            }
        runCatching { manager.discoverServices("_retrotv._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { fallbackHost(::finish) }
        main.postDelayed({
            runCatching { manager.stopServiceDiscovery(listener) }
            fallbackHost(::finish)
        }, 3_000)
    }

    private fun fallbackHost(finish: (String?, Int) -> Unit) {
        val last = prefs.getString("last_host", null)
        val host = last?.substringBefore(':')
        val port = last?.substringAfter(':', "")?.toIntOrNull() ?: com.swordfish.lemuroid.retrotv.pad.PhonePadServer.HTTP_PORT
        finish(host?.takeIf { it.isNotBlank() }, port)
    }
}
