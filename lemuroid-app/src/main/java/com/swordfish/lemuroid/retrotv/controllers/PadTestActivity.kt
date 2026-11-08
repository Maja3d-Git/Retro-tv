package com.swordfish.lemuroid.retrotv.controllers

import android.os.Bundle
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme

/** Test pada: każdy wciśnięty przycisk podświetla się na ekranie. Wyjście: Wstecz na pilocie albo przytrzymany Start. */
class PadTestActivity : ComponentActivity() {
    private var pressed by mutableStateOf(setOf<Int>())
    private var device by mutableStateOf<String?>(null)
    private var left by mutableStateOf(0f to 0f)
    private var right by mutableStateOf(0f to 0f)
    private var startDownAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { Screen() } }
    }

    private fun isPad(event: KeyEvent): Boolean {
        val src = event.source
        return src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(event.keyCode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isPad(event)) return super.dispatchKeyEvent(event)
        device = event.device?.name
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressed = pressed + event.keyCode
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START && event.repeatCount == 0) startDownAt = event.eventTime
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START && event.eventTime - startDownAt > 1500) finish()
            }
            KeyEvent.ACTION_UP -> pressed = pressed - event.keyCode
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(event)
        device = event.device?.name
        left = event.getAxisValue(MotionEvent.AXIS_X) to event.getAxisValue(MotionEvent.AXIS_Y)
        right = event.getAxisValue(MotionEvent.AXIS_Z) to event.getAxisValue(MotionEvent.AXIS_RZ)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var p = pressed - setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (hx < -0.5f) p = p + KeyEvent.KEYCODE_DPAD_LEFT
        if (hx > 0.5f) p = p + KeyEvent.KEYCODE_DPAD_RIGHT
        if (hy < -0.5f) p = p + KeyEvent.KEYCODE_DPAD_UP
        if (hy > 0.5f) p = p + KeyEvent.KEYCODE_DPAD_DOWN
        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f) p = p + KeyEvent.KEYCODE_BUTTON_L2
        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f) p = p + KeyEvent.KEYCODE_BUTTON_R2
        pressed = p
        return true
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().background(K.bg).padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Test pada", color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
            Text(device?.let { "Pad: $it" } ?: "Wciśnij dowolny przycisk na padzie", color = K.muted, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(120.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("L2", KeyEvent.KEYCODE_BUTTON_L2)
                    Key("L1", KeyEvent.KEYCODE_BUTTON_L1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Key("R1", KeyEvent.KEYCODE_BUTTON_R1)
                    Key("R2", KeyEvent.KEYCODE_BUTTON_R2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("▲", KeyEvent.KEYCODE_DPAD_UP)
                    Row(horizontalArrangement = Arrangement.spacedBy(46.dp)) {
                        Key("◀", KeyEvent.KEYCODE_DPAD_LEFT)
                        Key("▶", KeyEvent.KEYCODE_DPAD_RIGHT)
                    }
                    Key("▼", KeyEvent.KEYCODE_DPAD_DOWN)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Key("Select", KeyEvent.KEYCODE_BUTTON_SELECT)
                        Key("Start", KeyEvent.KEYCODE_BUTTON_START)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Key("L3", KeyEvent.KEYCODE_BUTTON_THUMBL)
                        Key("R3", KeyEvent.KEYCODE_BUTTON_THUMBR)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("Y", KeyEvent.KEYCODE_BUTTON_Y)
                    Row(horizontalArrangement = Arrangement.spacedBy(46.dp)) {
                        Key("X", KeyEvent.KEYCODE_BUTTON_X)
                        Key("B", KeyEvent.KEYCODE_BUTTON_B)
                    }
                    Key("A", KeyEvent.KEYCODE_BUTTON_A)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                Stick("Lewa gałka", left)
                Stick("Prawa gałka", right)
            }
            Spacer(Modifier.height(6.dp))
            Text("Wyjście: Wstecz na pilocie albo przytrzymaj Start.", color = K.muted, fontSize = 14.sp)
        }
    }

    @Composable
    private fun Key(
        label: String,
        code: Int,
    ) {
        val on = code in pressed
        Box(
            Modifier.height(48.dp).width(if (label.length > 2) 88.dp else 48.dp).clip(RoundedCornerShape(24.dp))
                .background(if (on) K.coral else K.card),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (on) K.p.activeFg else K.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Stick(
        label: String,
        pos: Pair<Float, Float>,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(90.dp).clip(CircleShape).background(K.card), contentAlignment = Alignment.Center) {
                Box(Modifier.offset((pos.first * 30).dp, (pos.second * 30).dp).size(26.dp).clip(CircleShape).background(K.coral))
            }
            Text(label, color = K.muted, fontSize = 13.sp)
        }
    }
}
