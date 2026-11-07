package com.swordfish.lemuroid.retrotv.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.mobile.feature.main.MainActivity
import com.swordfish.lemuroid.retrotv.netplay.NetplayGuestActivity
import com.swordfish.lemuroid.retrotv.share.ShareToTvActivity
import com.swordfish.lemuroid.retrotv.ui.K
import com.swordfish.lemuroid.retrotv.ui.RetroTvContent
import com.swordfish.lemuroid.retrotv.ui.RetroTvTheme
import com.swordfish.lemuroid.retrotv.ui.TvButton
import kotlinx.coroutines.launch

/** Ekran startowy aplikacji na telefonie: pad, wysyłanie gier, gra online. */
class PhoneHomeActivity : ComponentActivity() {
    companion object {
        /** Otwiera zwykły emulator Lemuroid na telefonie zamiast ekranu Retro TV. */
        const val EXTRA_PHONE_EMULATOR = "retrotv.phone_emulator"
    }

    private val prefs by lazy { getSharedPreferences("retrotv_share", Context.MODE_PRIVATE) }

    private val filePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val list = ArrayList<android.net.Uri>(uris)
            startActivity(
                Intent(this, ShareToTvActivity::class.java).apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
                    clipData = android.content.ClipData.newRawUri("", list.first()).apply { list.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }

    private var onScanError: ((String) -> Unit)? = null

    private val qrScanner =
        registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
            val raw = result.contents ?: return@registerForActivityResult
            val uri = android.net.Uri.parse(raw)
            val k = uri.getQueryParameter("k")
            if (k.isNullOrBlank() || uri.host.isNullOrBlank()) {
                onScanError?.invoke("To nie jest kod z Retro TV.")
                return@registerForActivityResult
            }
            val port = if (uri.port > 0) uri.port else com.swordfish.lemuroid.retrotv.pad.PhonePadServer.HTTP_PORT
            prefs.edit().putString("code", k.lowercase()).putString("last_host", "${uri.host}:$port").apply()
            PhonePadActivity.start(this, k.lowercase())
        }

    /** Skanuje kod QR z ekranu telewizora i od razu otwiera pad. */
    private fun scanQr(onError: (String) -> Unit) {
        onScanError = onError
        val options =
            com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("Zeskanuj kod QR z ekranu Kontrolery na telewizorze")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
        runCatching { qrScanner.launch(options) }.onFailure { onError("Nie udało się uruchomić aparatu – wpisz kod ręcznie.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetroTvTheme.apply(this)
        setContent { RetroTvContent { Screen() } }
    }

    @Composable
    private fun Screen() {
        var code by remember { mutableStateOf(prefs.getString("code", "") ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        var release by remember { mutableStateOf<com.swordfish.lemuroid.retrotv.update.ReleaseInfo?>(null) }
        var updateStatus by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            release =
                runCatching { com.swordfish.lemuroid.retrotv.update.Updater.fetchLatest() }
                    .getOrNull()?.takeIf { it.isNewerThanInstalled }
        }

        fun installUpdate(r: com.swordfish.lemuroid.retrotv.update.ReleaseInfo) {
            val u = com.swordfish.lemuroid.retrotv.update.Updater
            if (!u.canInstall(this@PhoneHomeActivity)) {
                updateStatus = "Zezwól Retro TV na instalowanie aplikacji, wróć i naciśnij ponownie."
                u.openInstallPermissionSettings(this@PhoneHomeActivity)
                return
            }
            updateStatus = "Pobieram…"
            scope.launch {
                try {
                    val apk = u.download(this@PhoneHomeActivity, r) { p -> updateStatus = "Pobieram… ${(p * 100).toInt()}%" }
                    updateStatus = null
                    u.install(this@PhoneHomeActivity, apk)
                } catch (e: Exception) {
                    updateStatus = "Pobieranie nie powiodło się: ${e.message}"
                }
            }
        }

        fun validCode(): String? {
            val c = code.trim().lowercase()
            if (c.length < 4) {
                error = "Wpisz kod z ekranu telewizora (Kontrolery)."
                return null
            }
            prefs.edit().putString("code", c).apply()
            error = null
            return c
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(K.bg)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Retro TV", color = K.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            release?.let { r ->
                TvButton("Zainstaluj aktualizację ${r.versionName}", primary = true, modifier = Modifier.fillMaxWidth(), onClick = { installUpdate(r) })
            }
            updateStatus?.let { Text(it, color = K.muted, fontSize = 14.sp) }
            Text("Telefon jako pad i pilot do Retro TV na telewizorze.", color = K.muted, fontSize = 16.sp)
            val remembered = remember { prefs.getString("last_host", null)?.takeIf { code.isNotBlank() } }
            if (remembered != null) {
                TvButton("Połącz z telewizorem", primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    PhonePadActivity.start(this@PhoneHomeActivity, code.trim().lowercase())
                })
                Text("Zapamiętany telewizor: ${remembered.substringBefore(':')}", color = K.muted, fontSize = 13.sp)
            }
            TvButton("Zeskanuj kod QR z telewizora", primary = remembered == null, modifier = Modifier.fillMaxWidth(), onClick = {
                scanQr { error = it }
            })
            Text("Na telewizorze: Kontrolery. Po zeskanowaniu pad otworzy się sam.", color = K.muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Albo wpisz kod telewizora", color = K.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { ch -> ch.isLetterOrDigit() }.take(12) },
                singleLine = true,
                placeholder = { Text("np. abcdef") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                shape = RoundedCornerShape(12.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = K.text,
                        unfocusedTextColor = K.text,
                        focusedBorderColor = K.accent,
                        unfocusedBorderColor = K.line,
                        cursorColor = K.accent,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Kod znajdziesz na telewizorze: Kontrolery → pod kodem QR.",
                color = K.muted,
                fontSize = 14.sp,
            )
            error?.let { Text(it, color = K.coral, fontSize = 14.sp) }
            TvButton("Pad – graj telefonem", modifier = Modifier.fillMaxWidth(), onClick = {
                validCode()?.let { PhonePadActivity.start(this@PhoneHomeActivity, it) }
            })
            Text(
                "Telefon łączy się z telewizorem przez sieć domową, a gdy to niemożliwe – przez internet.",
                color = K.muted,
                fontSize = 13.sp,
            )
            TvButton("Wyślij gry na telewizor", modifier = Modifier.fillMaxWidth(), onClick = {
                if (validCode() != null) filePicker.launch(arrayOf("*/*"))
            })
            TvButton("Dołącz do gry online", modifier = Modifier.fillMaxWidth(), onClick = {
                startActivity(Intent(this@PhoneHomeActivity, NetplayGuestActivity::class.java))
            })
            Spacer(Modifier.height(8.dp))
            TvButton("Emulator na tym telefonie", modifier = Modifier.fillMaxWidth(), onClick = {
                startActivity(Intent(this@PhoneHomeActivity, MainActivity::class.java).putExtra(EXTRA_PHONE_EMULATOR, true))
            })
            Text(
                "Wersja ${com.swordfish.lemuroid.BuildConfig.VERSION_NAME}" + if (release == null) " · aktualna" else "",
                color = K.muted,
                fontSize = 13.sp,
            )
        }
    }
}
