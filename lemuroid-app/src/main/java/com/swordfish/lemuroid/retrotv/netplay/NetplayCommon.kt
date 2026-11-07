package com.swordfish.lemuroid.retrotv.netplay

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.security.SecureRandom

/**
 * Wspólne elementy gry przez internet.
 *
 * Pokój ma kod (np. K7M2QX). Telewizor-host i goście wymieniają opisy połączeń WebRTC
 * przez publiczny serwer MQTT (tematy retrotv/v1/KOD/...), a potem obraz i przyciski
 * płyną bezpośrednio między urządzeniami.
 */
object Netplay {
    const val BROKER = "wss://broker.hivemq.com:8884/mqtt"
    const val WEB_URL = "https://maja3d-git.github.io/Retro-tv/graj/"
    const val MAX_GUESTS = 3

    val iceServers: List<PeerConnection.IceServer> =
        listOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
        )

    fun hostTopic(code: String) = "retrotv/v1/${code.uppercase()}/host"

    fun guestTopic(
        code: String,
        guestId: String,
    ) = "retrotv/v1/${code.uppercase()}/guest/$guestId"

    fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun newId(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    @Volatile
    private var initialized = false

    fun factory(
        context: Context,
        egl: EglBase,
    ): PeerConnectionFactory {
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
}

/** Kanał wymiany wiadomości przez publiczny serwer MQTT. */
class Signaling(
    private val clientId: String,
    private val subscribeTopic: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private var client: MqttAsyncClient? = null

    fun connect() {
        val c = MqttAsyncClient(Netplay.BROKER, "retrotv-$clientId", MemoryPersistence())
        c.setCallback(
            object : MqttCallbackExtended {
                override fun connectComplete(
                    reconnect: Boolean,
                    serverURI: String?,
                ) {
                    runCatching { c.subscribe(subscribeTopic, 1) }
                        .onFailure { Timber.w(it, "Netplay: subskrypcja nie powiodła się") }
                    onState(true)
                }

                override fun connectionLost(cause: Throwable?) {
                    Timber.w(cause, "Netplay: utracono połączenie z serwerem")
                    onState(false)
                }

                override fun messageArrived(
                    topic: String?,
                    message: MqttMessage,
                ) {
                    val json = runCatching { JSONObject(String(message.payload)) }.getOrNull() ?: return
                    onMessage(json)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            },
        )
        val options =
            MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 20
                keepAliveInterval = 30
            }
        client = c
        runCatching { c.connect(options) }.onFailure {
            Timber.e(it, "Netplay: nie udało się połączyć z serwerem")
            onState(false)
        }
    }

    fun send(
        topic: String,
        json: JSONObject,
    ) {
        val c = client ?: return
        runCatching { c.publish(topic, MqttMessage(json.toString().toByteArray()).apply { qos = 1 }) }
            .onFailure { Timber.w(it, "Netplay: wysyłanie nie powiodło się") }
    }

    fun close() {
        val c = client ?: return
        client = null
        Thread {
            runCatching { c.disconnect(1000).waitForCompletion(1500) }
            runCatching { c.close() }
        }.start()
    }
}

/** Obserwator SDP z wygodnymi lambdami. */
open class SimpleSdpObserver(
    private val onCreated: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onCreated(sdp)

    override fun onSetSuccess() = onSet()

    override fun onCreateFailure(error: String?) {
        Timber.w("Netplay: błąd tworzenia SDP: %s", error)
    }

    override fun onSetFailure(error: String?) {
        Timber.w("Netplay: błąd ustawiania SDP: %s", error)
    }
}

/** Obserwator połączenia z pustymi metodami – nadpisujemy tylko potrzebne. */
open class SimplePcObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

    override fun onIceCandidate(candidate: IceCandidate?) {}

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

    override fun onAddStream(stream: MediaStream?) {}

    override fun onRemoveStream(stream: MediaStream?) {}

    override fun onDataChannel(channel: DataChannel?) {}

    override fun onRenegotiationNeeded() {}

    override fun onAddTrack(
        receiver: RtpReceiver?,
        streams: Array<out MediaStream>?,
    ) {}
}
