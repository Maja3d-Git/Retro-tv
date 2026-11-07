package com.swordfish.lemuroid.retrotv.netplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.retrotv.pad.PadSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.nio.charset.Charset

data class NetplayGuest(val id: String, val name: String, val player: Int, val connected: Boolean)

data class NetplayState(
    val active: Boolean = false,
    val code: String = "",
    val online: Boolean = false,
    val guests: List<NetplayGuest> = emptyList(),
    val error: String? = null,
    /** Poczekalnia przed startem gry: goście dołączają, obraz jeszcze nie jest wysyłany. */
    val lobby: Boolean = false,
)

/**
 * Host gry przez internet (działa w procesie gry). Przechwytuje ekran za zgodą użytkownika,
 * tworzy pokój z kodem i dla każdego gościa zestawia połączenie WebRTC z obrazem
 * i kanałem danych na przyciski.
 */
class NetplayHostService : Service() {
    companion object {
        private const val EXTRA_PROJECTION = "projection"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LOBBY = "lobby"
        private const val CHANNEL = "retrotv_netplay"
        private const val NOTIFICATION_ID = 7341

        private val stateFlow = MutableStateFlow(NetplayState())
        val state: StateFlow<NetplayState> = stateFlow

        /** Odbiorca przycisków gości – ustawia go ekran gry. */
        @Volatile
        var inputSink: PadSink? = null

        /** Numer pierwszego gracza dla gości (po padach i telefonach w domu). */
        @Volatile
        var firstGuestPlayer: Int = 2

        fun start(
            context: Context,
            projectionResult: Intent,
            title: String,
        ) {
            val intent =
                Intent(context, NetplayHostService::class.java)
                    .putExtra(EXTRA_PROJECTION, projectionResult)
                    .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Otwiera pokój przed uruchomieniem gry (bez przechwytywania obrazu). */
        fun openLobby(
            context: Context,
            title: String,
        ) {
            context.startService(Intent(context, NetplayHostService::class.java).putExtra(EXTRA_LOBBY, true).putExtra(EXTRA_TITLE, title))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NetplayHostService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var signaling: Signaling? = null
    private val peers = mutableMapOf<String, Peer>()
    private val waiting = linkedMapOf<String, NetplayGuest>()
    private var code = ""

    private inner class Peer(val guest: NetplayGuest, val pc: PeerConnection) {
        var channel: DataChannel? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.getBooleanExtra(EXTRA_LOBBY, false) == true) {
            if (signaling == null) {
                code = Netplay.newCode()
                stateFlow.value = NetplayState(active = true, lobby = true, code = code)
                startSignaling()
                Timber.i("Netplay: poczekalnia %s otwarta", code)
            }
            return START_NOT_STICKY
        }
        if (factory != null) return START_NOT_STICKY
        val projection: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_PROJECTION)
            }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (code.isEmpty()) code = Netplay.newCode()
        goForeground(intent?.getStringExtra(EXTRA_TITLE) ?: "Retro TV")
        try {
            startCapture(projection)
        } catch (e: Exception) {
            Timber.e(e, "Netplay: nie udało się przechwycić obrazu")
            stateFlow.value = NetplayState(error = "Nie udało się przechwycić obrazu: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        stateFlow.value = stateFlow.value.copy(active = true, lobby = false, code = code, error = null)
        if (signaling == null) startSignaling()
        // Goście z poczekalni dostają teraz obraz gry.
        val ready = waiting.values.toList()
        waiting.clear()
        ready.forEach { onJoin(it.id, it.name) }
        Timber.i("Netplay: pokój %s gra (gości z poczekalni: %d)", code, ready.size)
        return START_NOT_STICKY
    }

    private fun startSignaling() {
        signaling =
            Signaling(
                clientId = "host-$code",
                subscribeTopic = Netplay.hostTopic(code),
                onMessage = { json -> main.post { onSignal(json) } },
                onState = { online -> main.post { stateFlow.value = stateFlow.value.copy(online = online) } },
            ).also { it.connect() }
    }

    private fun goForeground(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gra przez internet", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_retrotv_system_64dp)
                .setContentTitle("Pokój $code · $title")
                .setContentText("Gra przez internet jest włączona")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(projection: Intent) {
        val eglBase = EglBase.create().also { egl = it }
        val f = Netplay.factory(this, eglBase).also { factory = it }
        val cap =
            ScreenCapturerAndroid(
                projection,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Timber.i("Netplay: przechwytywanie zatrzymane przez system")
                        main.post { stopSelf() }
                    }
                },
            )
        val h = SurfaceTextureHelper.create("retrotv-capture", eglBase.eglBaseContext)
        val src = f.createVideoSource(true)
        cap.initialize(h, this, src.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap
        helper = h
        source = src
        track = f.createVideoTrack("retrotv-game", src)
    }

    private fun onSignal(json: JSONObject) {
        val from = json.optString("from").take(40)
        when (json.optString("type")) {
            "join" -> onJoin(from, json.optString("name", "Gość").take(30))
            "answer" -> peers[from]?.pc?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, json.optString("sdp")))
            "bye" -> {
                if (waiting.remove(from) != null) publishGuests()
                removePeer(from)
            }
        }
    }

    private fun onJoin(
        guestId: String,
        name: String,
    ) {
        if (guestId.isBlank()) return
        if (factory == null) {
            // Poczekalnia: zapisujemy gościa i prosimy, żeby czekał na start gry.
            if (!waiting.containsKey(guestId) && waiting.size >= Netplay.MAX_GUESTS) {
                signaling?.send(Netplay.guestTopic(code, guestId), JSONObject().put("type", "full"))
                return
            }
            val taken = waiting.filterKeys { it != guestId }.values.map { it.player }.toSet()
            val player = (firstGuestPlayer until firstGuestPlayer + 8).first { it !in taken }
            waiting[guestId] = NetplayGuest(guestId, name, player, false)
            signaling?.send(Netplay.guestTopic(code, guestId), JSONObject().put("type", "wait").put("player", player))
            publishGuests()
            Timber.i("Netplay: %s czeka w poczekalni", name)
            return
        }
        removePeer(guestId)
        if (peers.size >= Netplay.MAX_GUESTS) {
            signaling?.send(Netplay.guestTopic(code, guestId), JSONObject().put("type", "full"))
            return
        }
        val taken = peers.values.map { it.guest.player }.toSet()
        val player = (firstGuestPlayer until firstGuestPlayer + 8).first { it !in taken }
        val f = factory ?: return
        lateinit var peer: Peer
        val pc =
            f.createPeerConnection(
                Netplay.rtcConfig(),
                object : SimplePcObserver() {
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) {
                            main.post { sendOffer(peer) }
                        }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        main.post {
                            val connected = state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED
                            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                                removePeer(guestId)
                            } else {
                                updateGuest(guestId, connected)
                            }
                        }
                    }
                },
            ) ?: return
        peer = Peer(NetplayGuest(guestId, name, player, false), pc)
        peers[guestId] = peer
        track?.let { pc.addTrack(it, listOf("retrotv")) }
        val channel = pc.createDataChannel("input", DataChannel.Init().apply { ordered = true })
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}

                override fun onStateChange() {}

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val msg = runCatching { JSONObject(String(bytes, Charset.forName("UTF-8"))) }.getOrNull() ?: return
                    main.post { onGuestInput(peer.guest.player, msg) }
                }
            },
        )
        peer.channel = channel
        pc.createOffer(
            SimpleSdpObserver(onCreated = { sdp -> pc.setLocalDescription(SimpleSdpObserver(), sdp) }),
            MediaConstraints(),
        )
        publishGuests()
        Timber.i("Netplay: gość %s (%s) dołącza jako gracz %d", name, guestId, player)
    }

    private fun sendOffer(peer: Peer) {
        val sdp = peer.pc.localDescription?.description ?: return
        signaling?.send(
            Netplay.guestTopic(code, peer.guest.id),
            JSONObject().put("type", "offer").put("sdp", sdp).put("player", peer.guest.player),
        )
    }

    private fun onGuestInput(
        player: Int,
        msg: JSONObject,
    ) {
        val sink = inputSink ?: return
        when (msg.optString("t")) {
            "k" -> sink.onKey(player, msg.optInt("c"), msg.optInt("d") == 1)
            "m" ->
                sink.onMotion(
                    player,
                    msg.optString("s"),
                    msg.optDouble("x", 0.0).toFloat().coerceIn(-1f, 1f),
                    msg.optDouble("y", 0.0).toFloat().coerceIn(-1f, 1f),
                )
        }
    }

    private fun updateGuest(
        id: String,
        connected: Boolean,
    ) {
        val p = peers[id] ?: return
        if (p.guest.connected == connected) return
        peers[id] = Peer(p.guest.copy(connected = connected), p.pc).also { it.channel = p.channel }
        publishGuests()
    }

    private fun removePeer(id: String) {
        val p = peers.remove(id) ?: return
        runCatching { p.channel?.close() }
        runCatching { p.pc.close() }
        publishGuests()
        Timber.i("Netplay: gość %s opuścił pokój", p.guest.name)
    }

    private fun publishGuests() {
        stateFlow.value = stateFlow.value.copy(guests = (peers.values.map { it.guest } + waiting.values).sortedBy { it.player })
    }

    override fun onDestroy() {
        waiting.keys.forEach { id -> signaling?.send(Netplay.guestTopic(code, id), JSONObject().put("type", "bye")) }
        waiting.clear()
        peers.keys.toList().forEach { id ->
            signaling?.send(Netplay.guestTopic(code, id), JSONObject().put("type", "bye"))
            removePeer(id)
        }
        signaling?.close()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { egl?.release() }
        factory = null
        stateFlow.value = NetplayState()
        Timber.i("Netplay: pokój %s zamknięty", code)
        super.onDestroy()
    }
}
