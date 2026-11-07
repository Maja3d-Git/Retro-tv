package com.swordfish.lemuroid.retrotv.pad

import android.os.Handler
import android.os.Looper
import com.swordfish.lemuroid.retrotv.netplay.Signaling
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pad przez internet: gdy telefon nie może połączyć się z telewizorem w sieci domowej
 * (blokada w routerze lub w telefonie), aplikacja na telefonie wysyła przyciski przez
 * serwer MQTT – ten sam, którego używa gra online.
 */
object PadRelay {
    fun inTopic(token: String) = "retrotv/v1/pad/${token.lowercase()}/in"

    fun outTopic(
        token: String,
        cid: String,
    ) = "retrotv/v1/pad/${token.lowercase()}/out/$cid"

    private const val IDLE_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())
    private var signaling: Signaling? = null
    private var token: String = ""
    private val conns = ConcurrentHashMap<String, RelayConn>()

    private class RelayConn(val cid: String) : PhonePadServer.PadConn {
        @Volatile
        var lastSeen = System.currentTimeMillis()

        override fun send(text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            signaling?.send(outTopic(token, cid), json)
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            signaling?.send(outTopic(token, cid), JSONObject().put("t", "close").put("code", code).put("reason", reason))
            drop(cid)
        }
    }

    private val sweeper =
        object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                conns.values.filter { now - it.lastSeen > IDLE_MS }.forEach { drop(it.cid) }
                main.postDelayed(this, 5_000)
            }
        }

    @Synchronized
    fun start(token: String) {
        if (signaling != null) return
        this.token = token
        val sig =
            Signaling(
                clientId = "tvpad-" + UUID.randomUUID().toString().take(8),
                subscribeTopic = inTopic(token),
                onMessage = ::onMessage,
                onState = { ok -> Timber.i("Pad przez internet: %s", if (ok) "gotowy" else "brak połączenia z serwerem") },
            )
        signaling = sig
        sig.connect()
        main.removeCallbacks(sweeper)
        main.postDelayed(sweeper, 5_000)
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(sweeper)
        conns.keys.toList().forEach { drop(it) }
        signaling?.close()
        signaling = null
    }

    private fun onMessage(json: JSONObject) {
        val cid = json.optString("cid").take(32)
        if (cid.isEmpty()) return
        if (json.optString("t") == "bye") {
            drop(cid)
            return
        }
        val isNew = !conns.containsKey(cid)
        val conn = conns.getOrPut(cid) { RelayConn(cid) }
        conn.lastSeen = System.currentTimeMillis()
        if (isNew) {
            Timber.i("Pad w telefonie: połączenie przez internet (%s)", cid)
            PhonePadServer.noteVisitor("przez internet")
        }
        json.remove("cid")
        PhonePadServer.handleMessage(conn, json.toString(), "relay-$cid")
    }

    private fun drop(cid: String) {
        conns.remove(cid)?.let { PhonePadServer.dropConn(it) }
    }
}
