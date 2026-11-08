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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
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
    private var inLobby = false
    private var codeInput by mutableStateOf("")

    private val roomScanner =
        registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
            val raw = result.contents ?: return@registerForActivityResult
            val room = runCatching { android.net.Uri.parse(raw).getQueryParameter("kod") }.getOrNull()
                ?: raw.trim().takeIf { it.length == 6 && it.all { c -> c.isLetterOrDigit() } }
            if (room.isNullOrBlank()) {
                status = "To nie jest kod pokoju Retro TV."
                return@registerForActivityResult
            }
            codeInput = room.uppercase()
            join(codeInput)
        }

    private val isPhone by lazy { !com.swordfish.lemuroid.app.tv.shared.TVHelper.isTV(this) }

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
            // Telefon: przyciski na ekranie, bo zwykle nie ma pada.
            if (streaming && isPhone) TouchControls()
            if (!streaming) {
                Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(K.card).padding(28.dp),
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
                            if (isPhone) {
                                TvButton("Zeskanuj kod", onClick = {
                                    runCatching {
                                        roomScanner.launch(
                                            com.journeyapps.barcodescanner.ScanOptions()
                                                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                                .setPrompt("Zeskanuj kod QR pokoju z telewizora gospodarza")
                                                .setBeepEnabled(false)
                                                .setOrientationLocked(true)
                                                .setCaptureActivity(com.swordfish.lemuroid.retrotv.phone.PortraitCaptureActivity::class.java),
                                        )
                                    }
                                })
                            }
                            TvButton("Anuluj", onClick = { finish() })
                        }
                        status?.let { Text(it, color = K.muted, fontSize = 15.sp) }
                        Text(
                            if (isPhone) "Po połączeniu obróć telefon poziomo – przyciski pojawią się na ekranie." else "W grze sterujesz padem albo pilotem (OK = przycisk A). Wstecz kończy grę.",
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
        if (isPhone) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                            main.postDelayed({ if (!streaming && pc == null && !inLobby) status = "Brak odpowiedzi. Sprawdź kod i czy gospodarz ma otwarty pokój." }, 15_000)
                        }
                    }
                },
            ).also { it.connect() }
    }

    private fun onSignal(json: JSONObject) {
        when (json.optString("type")) {
            "offer" -> onOffer(json.optString("sdp"))
            "full" -> status = "Pokój jest pełny."
            "wait" -> {
                inLobby = true
                status = "Jesteś w pokoju jako gracz ${json.optInt("player", 2)}. Gra zacznie się, gdy gospodarz ją uruchomi."
            }
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

    private fun key(
        code: Int,
        down: Boolean,
    ) = send(JSONObject().put("t", "k").put("c", code).put("d", if (down) 1 else 0))

    private fun dpad(
        code: Int,
        down: Boolean,
    ) {
        if (down) dpadDirs.add(code) else dpadDirs.remove(code)
        sendDpad()
    }

    @Composable
    private fun TouchControls() {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TouchButton("L", 96, 44, { key(KeyEvent.KEYCODE_BUTTON_L1, it) })
            }
            Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TouchButton("R", 96, 44, { key(KeyEvent.KEYCODE_BUTTON_R1, it) })
            }
            Column(Modifier.align(Alignment.CenterStart), horizontalAlignment = Alignment.CenterHorizontally) {
                TouchButton("▲", 60, 60, { dpad(KeyEvent.KEYCODE_DPAD_UP, it) })
                Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                    TouchButton("◀", 60, 60, { dpad(KeyEvent.KEYCODE_DPAD_LEFT, it) })
                    TouchButton("▶", 60, 60, { dpad(KeyEvent.KEYCODE_DPAD_RIGHT, it) })
                }
                TouchButton("▼", 60, 60, { dpad(KeyEvent.KEYCODE_DPAD_DOWN, it) })
            }
            Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.CenterHorizontally) {
                TouchButton("X", 58, 58, { key(KeyEvent.KEYCODE_BUTTON_X, it) }, round = true)
                Row(horizontalArrangement = Arrangement.spacedBy(58.dp)) {
                    TouchButton("Y", 58, 58, { key(KeyEvent.KEYCODE_BUTTON_Y, it) }, round = true)
                    TouchButton("A", 58, 58, { key(KeyEvent.KEYCODE_BUTTON_A, it) }, round = true)
                }
                TouchButton("B", 58, 58, { key(KeyEvent.KEYCODE_BUTTON_B, it) }, round = true)
            }
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TouchButton("SELECT", 92, 36, { key(KeyEvent.KEYCODE_BUTTON_SELECT, it) })
                TouchButton("START", 92, 36, { key(KeyEvent.KEYCODE_BUTTON_START, it) })
            }
        }
    }

    @Composable
    private fun TouchButton(
        label: String,
        w: Int,
        h: Int,
        onPress: (Boolean) -> Unit,
        round: Boolean = false,
    ) {
        var pressed by remember { mutableStateOf(false) }
        val vibrator = remember { getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator }
        Box(
            Modifier
                .width(w.dp)
                .height(h.dp)
                .clip(if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(14.dp))
                .background(if (pressed) K.coral.copy(alpha = 0.85f) else Color(0x66FFFFFF))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        onPress(true)
                        runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= 26) {
                                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(12, 120))
                            }
                        }
                        waitForUpOrCancellation()
                        pressed = false
                        onPress(false)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = if (label.length > 2) 12.sp else 20.sp, fontWeight = FontWeight.Bold)
        }
    }

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
