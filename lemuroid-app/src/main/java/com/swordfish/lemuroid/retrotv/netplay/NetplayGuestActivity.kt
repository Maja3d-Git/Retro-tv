package com.swordfish.lemuroid.retrotv.netplay

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.swordfish.lemuroid.retrotv.pad.PhonePadText
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import timber.log.Timber
import java.nio.ByteBuffer

/** Gość gry przez internet: wpisuje kod pokoju, ogląda grę i steruje swoim padem lub pilotem. */
class NetplayGuestActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val guestId = Netplay.newId()
    private var code = ""
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var signaling: Signaling? = null
    private var renderer: SurfaceViewRenderer? = null
    private var pendingTrack: VideoTrack? = null
    private var rendererReady = false

    private var status by mutableStateOf<String?>(null)
    private var streaming by mutableStateOf(false)
    private var codeInput by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        intent.getStringExtra("code")?.let { codeInput = it }
        setContent { RetroTvContent { Screen() } }
    }

    @Composable
    private fun Screen() {
        DisposableEffect(Unit) {
            PhonePadText.listener = { codeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }
            onDispose { PhonePadText.listener = null }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        renderer = r
                        egl?.let { initRenderer(r, it) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!streaming) {
                Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.width(560.dp).clip(RoundedCornerShape(20.dp)).background(K.card).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Dołącz do gry", color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
                        Text("Wpisz kod pokoju, który widzi gospodarz w menu gry.", color = K.muted, fontSize = 15.sp)
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                            label = { Text("Kod pokoju") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = K.text,
                                    unfocusedTextColor = K.text,
                                    focusedBorderColor = K.coral,
                                    unfocusedBorderColor = K.line,
                                    focusedLabelColor = K.coral,
                                    unfocusedLabelColor = K.muted,
                                    cursorColor = K.accent,
                                ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvButton("Dołącz", primary = true, enabled = codeInput.length == 6 && status == null, onClick = { join(codeInput) })
                            TvButton("Anuluj", onClick = { finish() })
                        }
                        status?.let { Text(it, color = K.muted, fontSize = 15.sp) }
                        Text(
                            "W grze sterujesz padem albo pilotem (OK = przycisk A). Wstecz kończy grę.",
                            color = K.muted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    private fun initRenderer(
        r: SurfaceViewRenderer,
        eglBase: EglBase,
    ) {
        if (rendererReady) return
        rendererReady = true
        runCatching {
            r.init(eglBase.eglBaseContext, null)
            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            r.setEnableHardwareScaler(true)
        }
        pendingTrack?.addSink(r)
    }

    private fun join(roomCode: String) {
        code = roomCode.uppercase()
        status = "Łączenie z pokojem $code…"
        val eglBase = EglBase.create().also { egl = it }
        renderer?.let { initRenderer(it, eglBase) }
        factory = Netplay.factory(this, eglBase)
        signaling =
            Signaling(
                clientId = "guest-$guestId",
                subscribeTopic = Netplay.guestTopic(code, guestId),
                onMessage = { json -> main.post { onSignal(json) } },
                onState = { online ->
                    main.post {
                        if (online && !streaming) {
                            status = "Czekam na odpowiedź gospodarza…"
                            signaling?.send(
                                Netplay.hostTopic(code),
                                JSONObject().put("type", "join").put("from", guestId).put("name", Build.MODEL ?: "Retro TV"),
                            )
                            main.postDelayed({ if (!streaming && pc == null) status = "Brak odpowiedzi. Sprawdź kod i czy gospodarz ma otwarty pokój." }, 15_000)
                        }
                    }
                },
            ).also { it.connect() }
    }

    private fun onSignal(json: JSONObject) {
        when (json.optString("type")) {
            "offer" -> onOffer(json.optString("sdp"))
            "full" -> status = "Pokój jest pełny."
            "bye" -> {
                status = "Gospodarz zamknął pokój."
                streaming = false
                closePeer()
            }
        }
    }

    private fun onOffer(sdp: String) {
        val f = factory ?: return
        closePeer()
        status = "Łączenie z grą…"
        val peer =
            f.createPeerConnection(
                Netplay.rtcConfig(),
                object : SimplePcObserver() {
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) main.post { sendAnswer() }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        main.post {
                            when (state) {
                                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> streaming = true
                                PeerConnection.IceConnectionState.FAILED -> {
                                    streaming = false
                                    status = "Nie udało się połączyć (sieć może blokować połączenia bezpośrednie)."
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED -> status = "Połączenie przerwane…"
                                else -> {}
                            }
                        }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                        main.post {
                            pendingTrack = track
                            renderer?.let { track.addSink(it) }
                        }
                    }

                    override fun onDataChannel(dc: DataChannel?) {
                        channel = dc
                    }
                },
            ) ?: return
        pc = peer
        peer.setRemoteDescription(
            SimpleSdpObserver(onSet = {
                peer.createAnswer(
                    SimpleSdpObserver(onCreated = { answer -> peer.setLocalDescription(SimpleSdpObserver(), answer) }),
                    MediaConstraints(),
                )
            }),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun sendAnswer() {
        val sdp = pc?.localDescription?.description ?: return
        signaling?.send(Netplay.hostTopic(code), JSONObject().put("type", "answer").put("from", guestId).put("sdp", sdp))
    }

    private fun send(json: JSONObject) {
        val dc = channel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(json.toString().toByteArray()), false))
    }

    // ---------- sterowanie ----------

    private val dpadDirs = mutableSetOf<Int>()

    private fun sendDpad() {
        val x = if (KeyEvent.KEYCODE_DPAD_LEFT in dpadDirs) -1 else if (KeyEvent.KEYCODE_DPAD_RIGHT in dpadDirs) 1 else 0
        val y = if (KeyEvent.KEYCODE_DPAD_UP in dpadDirs) -1 else if (KeyEvent.KEYCODE_DPAD_DOWN in dpadDirs) 1 else 0
        send(JSONObject().put("t", "m").put("s", "dpad").put("x", x).put("y", y))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!streaming) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action == KeyEvent.ACTION_MULTIPLE) return true
        when (val code = event.keyCode) {
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.repeatCount > 0) return true
                if (down) dpadDirs.add(code) else dpadDirs.remove(code)
                sendDpad()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) send(JSONObject().put("t", "k").put("c", KeyEvent.KEYCODE_BUTTON_A).put("d", if (down) 1 else 0))
            }
            else -> {
                if (!KeyEvent.isGamepadButton(code)) return super.dispatchKeyEvent(event)
                if (event.repeatCount == 0) send(JSONObject().put("t", "k").put("c", code).put("d", if (down) 1 else 0))
            }
        }
        return true
    }

    private var lastStick = 0L

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!streaming || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.dispatchGenericMotionEvent(event)
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        send(JSONObject().put("t", "m").put("s", "dpad").put("x", hatX.toDouble()).put("y", hatY.toDouble()))
        val now = System.currentTimeMillis()
        if (now - lastStick > 20) {
            lastStick = now
            send(
                JSONObject().put("t", "m").put("s", "left")
                    .put("x", event.getAxisValue(MotionEvent.AXIS_X).toDouble())
                    .put("y", event.getAxisValue(MotionEvent.AXIS_Y).toDouble()),
            )
            send(
                JSONObject().put("t", "m").put("s", "right")
                    .put("x", event.getAxisValue(MotionEvent.AXIS_Z).toDouble())
                    .put("y", event.getAxisValue(MotionEvent.AXIS_RZ).toDouble()),
            )
        }
        return true
    }

    private fun closePeer() {
        runCatching { channel?.close() }
        channel = null
        runCatching { pc?.close() }
        pc = null
    }

    override fun onDestroy() {
        if (code.isNotEmpty()) signaling?.send(Netplay.hostTopic(code), JSONObject().put("type", "bye").put("from", guestId))
        signaling?.close()
        closePeer()
        runCatching { renderer?.release() }
        runCatching { factory?.dispose() }
        runCatching { egl?.release() }
        Timber.i("Netplay: gość opuścił pokój %s", code)
        super.onDestroy()
    }
}
