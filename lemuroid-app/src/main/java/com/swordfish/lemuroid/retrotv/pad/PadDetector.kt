package com.swordfish.lemuroid.retrotv.pad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Odróżnia prawdziwe pady od pilotów i urządzeń wirtualnych, które też zgłaszają się jako „gamepad”. */
object PadDetector {
    private val notPadNames = listOf("virtual", "consumer control", "remote", "keyboard", "mouse", "touch")

    fun isRealGamepad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        // Klawiatury (także małe z touchpadem) nie są padami – inaczej zajmowały miejsce gracza 1.
        if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        val name = device.name.lowercase()
        if (notPadNames.any { name.contains(it) } || Regex("""(^|[_\s])rc\d""").containsMatchIn(name)) return false
        val gamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!gamepad && !joystick) return false
        val faceButtons =
            device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
            ).count { it }
        val stick = joystick && device.getMotionRange(MotionEvent.AXIS_X) != null && device.getMotionRange(MotionEvent.AXIS_Y) != null
        return faceButtons >= 3 || stick
    }

    /** Pady w kolejności graczy (ustawianej w Kontrolerach). */
    fun orderedPads(context: android.content.Context): List<InputDevice> = PadOrder.sort(context, pads())

    fun pads(): List<InputDevice> =
        InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { isRealGamepad(it) && !PadIgnore.isIgnored(it) }
            .distinctBy { it.descriptor }
}
