package com.swordfish.lemuroid.retrotv.controllers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.RetroKey
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.retrotv.pad.PadDetector
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.launch

/** Zmiana przycisków pada krok po kroku: „Wciśnij przycisk dla A”, potem B… */
class PadRemapActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_DESCRIPTOR = "descriptor"

        fun intent(
            context: Context,
            descriptor: String,
        ): Intent = Intent(context, PadRemapActivity::class.java).putExtra(EXTRA_DESCRIPTOR, descriptor)

        /** Przyciski konsoli w kolejności pytania: nazwa i kod przycisku w emulatorze. */
        private val STEPS =
            listOf(
                "A" to KeyEvent.KEYCODE_BUTTON_A,
                "B" to KeyEvent.KEYCODE_BUTTON_B,
                "X" to KeyEvent.KEYCODE_BUTTON_X,
                "Y" to KeyEvent.KEYCODE_BUTTON_Y,
                "L" to KeyEvent.KEYCODE_BUTTON_L1,
                "R" to KeyEvent.KEYCODE_BUTTON_R1,
                "L2" to KeyEvent.KEYCODE_BUTTON_L2,
                "R2" to KeyEvent.KEYCODE_BUTTON_R2,
                "Select" to KeyEvent.KEYCODE_BUTTON_SELECT,
                "Start" to KeyEvent.KEYCODE_BUTTON_START,
            )
    }

    private val manager by lazy {
        InputDeviceManager(applicationContext) { SharedPreferencesHelper.getSharedPreferences(applicationContext) }
    }
    private var step by mutableStateOf(0)
    private var lastInfo by mutableStateOf<String?>(null)
    private var device: InputDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        val descriptor = intent.getStringExtra(EXTRA_DESCRIPTOR)
        device = PadDetector.pads().firstOrNull { it.descriptor == descriptor }
        if (device == null) {
            finish()
            return
        }
        setContent { RetroTvContent { Screen() } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val d = device ?: return super.dispatchKeyEvent(event)
        val fromPad = event.device?.descriptor == d.descriptor && KeyEvent.isGamepadButton(event.keyCode)
        if (!fromPad || step >= STEPS.size) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val (name, target) = STEPS[step]
            val pressedKey = event.keyCode
            lifecycleScope.launch {
                manager.updateBinding(d, RetroKey(target), InputKey(pressedKey))
                lastInfo = "$name ← ${InputKey(pressedKey).displayName()}"
                step++
            }
        }
        return true
    }

    private fun resetDefaults() {
        val d = device ?: return
        SharedPreferencesHelper.getSharedPreferences(applicationContext).edit()
            .remove(InputDeviceManager.computeKeyBindingGamePadPreference(d)).apply()
        lastInfo = "Przywrócono domyślne przyciski"
        step = STEPS.size
    }

    @Composable
    private fun Screen() {
        val focus = remember { FocusRequester() }
        LaunchedEffect(step >= STEPS.size) { runCatching { focus.requestFocus() } }
        val done = step >= STEPS.size
        Column(
            Modifier.fillMaxSize().background(K.bg).padding(horizontal = 48.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Przyciski pada", color = K.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display)
            Text(device?.name ?: "", color = K.muted, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            if (!done) {
                val (name, _) = STEPS[step]
                Text("Wciśnij na padzie przycisk dla", color = K.text, fontSize = 20.sp)
                Box(Modifier.size(140.dp).clip(CircleShape).background(K.p.activeBg), contentAlignment = Alignment.Center) {
                    Text(name, color = K.p.activeFg, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                }
                Text("Krok ${step + 1} z ${STEPS.size}", color = K.muted, fontSize = 15.sp)
            } else {
                Text("Gotowe", color = K.ok, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Nowe ustawienie działa od następnego uruchomienia gry.", color = K.muted, fontSize = 15.sp)
            }
            lastInfo?.let { Text(it, color = K.muted, fontSize = 15.sp) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!done) TvButton("Pomiń ten przycisk", onClick = { step++ })
                TvButton("Przywróć domyślne", onClick = { resetDefaults() })
                TvButton(if (done) "Zakończ" else "Przerwij", primary = done, modifier = Modifier.focusRequester(focus), onClick = { finish() })
            }
            Text("Przyciski na ekranie obsługujesz pilotem.", color = K.muted, fontSize = 13.sp)
        }
    }
}
