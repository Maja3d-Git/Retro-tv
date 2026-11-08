package com.swordfish.lemuroid.retrotv.pad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import java.lang.ref.WeakReference

/** Ekran, który sam obsługuje przyciski z telefonu (np. ekran gry). */
interface PadSinkProvider {
    fun padSink(): PadSink
}

/** Odbiorca tekstu wpisanego na telefonie (np. pole wyszukiwania). */
object PhonePadText {
    @Volatile
    var listener: ((String) -> Unit)? = null
}

/**
 * Uruchamia serwer pada, gdy któryś ekran Retro TV w danym procesie jest na pierwszym planie,
 * i zatrzymuje go, gdy aplikacja schodzi z ekranu (np. przy przejściu do procesu gry).
 */
object PhonePadLifecycle : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed: WeakReference<Activity>? = null
    private val stopRunnable = Runnable { PhonePadServer.stop() }

    fun install(application: Application) {
        if (!TVHelper.isTV(application)) return
        application.registerActivityLifecycleCallbacks(this)
        watchPads(application)
    }

    private val knownPads = mutableMapOf<Int, String>()

    /** Powiadomienie „Podłączono pad … – gracz N” i „Odłączono pad …”, gdy aplikacja jest na ekranie. */
    private fun watchPads(application: Application) {
        val im = application.getSystemService(android.content.Context.INPUT_SERVICE) as? android.hardware.input.InputManager ?: return
        PadDetector.pads().forEach { knownPads[it.id] = it.name }
        im.registerInputDeviceListener(
            object : android.hardware.input.InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    val d = InputDevice.getDevice(deviceId) ?: return
                    if (!PadDetector.isRealGamepad(d) || knownPads.containsKey(deviceId)) return
                    knownPads[deviceId] = d.name
                    val player = PadDetector.orderedPads(application).indexOfFirst { it.id == deviceId } + 1
                    toast(application, "Podłączono pad: ${d.name}" + if (player > 0) " – gracz $player" else "")
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    val name = knownPads.remove(deviceId) ?: return
                    toast(application, "Odłączono pad: $name")
                }

                override fun onInputDeviceChanged(deviceId: Int) {}
            },
            handler,
        )
    }

    private fun toast(
        application: Application,
        text: String,
    ) {
        if (resumed?.get() == null) return
        runCatching { android.widget.Toast.makeText(application, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResumed(activity: Activity) {
        handler.removeCallbacks(stopRunnable)
        resumed = WeakReference(activity)
        val sink = (activity as? PadSinkProvider)?.padSink() ?: MenuSink(activity)
        PhonePadServer.start(activity, sink)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, 250)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}

    /** Telefon jako pilot: przyciski trafiają do bieżącego ekranu jak naciśnięcia pilota. */
    private class MenuSink(activity: Activity) : PadSink {
        private val target = WeakReference(activity)
        override val mode = "menu"
        override val layout = "menu"
        override val title = "Menu"

        override fun onKey(
            player: Int,
            keyCode: Int,
            pressed: Boolean,
        ) {
            val activity = target.get() ?: return
            val now = SystemClock.uptimeMillis()
            val event =
                KeyEvent(
                    now,
                    now,
                    if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    keyCode,
                    0,
                    0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    0,
                    InputDevice.SOURCE_DPAD,
                )
            activity.dispatchKeyEvent(event)
        }

        override fun onText(text: String) {
            PhonePadText.listener?.invoke(text)
        }
    }
}
