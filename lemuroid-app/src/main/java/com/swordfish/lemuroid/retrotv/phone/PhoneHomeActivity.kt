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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody

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
            // Kod pokoju gry online (QR z ekranu pokoju na telewizorze).
            uri.getQueryParameter("kod")?.takeIf { it.isNotBlank() }?.let { room ->
                startActivity(Intent(this, NetplayGuestActivity::class.java).putExtra("code", room.uppercase()))
                return@registerForActivityResult
            }
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

    /** Wysyła plik aplikacji na telewizor (PUT /upload), który sam uruchamia instalator. */
    private fun uploadApk(
        hostPort: String,
        tvCode: String,
        apk: java.io.File,
    ): String {
        val client =
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        val body = apk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
        val request =
            okhttp3.Request.Builder()
                .url("http://$hostPort/upload?k=${android.net.Uri.encode(tvCode)}&name=retro-tv.apk")
                .put(body)
                .build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException(text.ifBlank { "telewizor odpowiedział kodem ${r.code}" })
            return text.ifBlank { "Wysłano – zatwierdź instalację na telewizorze" }
        }
    }

    @Composable
    private fun Screen() {
        var code by remember { mutableStateOf(prefs.getString("code", "") ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        var tvStatus by remember { mutableStateOf<String?>(null) }
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
            // Nagłówek jak na telewizorze.
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.height(36.dp).width(36.dp).clip(RoundedCornerShape(10.dp)).background(K.card),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text("+", color = K.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("RETRO TV", color = K.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = RetroTvTheme.display, letterSpacing = 1.sp)
            }
            release?.let { r ->
                TvButton("Zainstaluj aktualizację ${r.versionName}", primary = true, modifier = Modifier.fillMaxWidth(), onClick = { installUpdate(r) })
            }
            updateStatus?.let { Text(it, color = K.muted, fontSize = 14.sp) }

            Card("Pad do telewizora") {
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
                Text("Na telewizorze: zębatka → Kontrolery. Kod pokoju gry online też tu zeskanujesz.", color = K.muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isLetterOrDigit() }.take(12) },
                    singleLine = true,
                    label = { Text("Albo wpisz kod telewizora") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = K.text,
                            unfocusedTextColor = K.text,
                            focusedBorderColor = K.coral,
                            unfocusedBorderColor = K.line,
                            focusedLabelColor = K.coral,
                            unfocusedLabelColor = K.muted,
                            cursorColor = K.coral,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = K.coral, fontSize = 14.sp) }
                TvButton("Połącz kodem", modifier = Modifier.fillMaxWidth(), onClick = {
                    validCode()?.let { PhonePadActivity.start(this@PhoneHomeActivity, it) }
                })
            }

            Card("Telewizor") {
                TvButton("Zaktualizuj Retro TV na telewizorze", modifier = Modifier.fillMaxWidth(), onClick = {
                    val host = prefs.getString("last_host", null)
                    val c = code.trim().lowercase()
                    if (host.isNullOrBlank() || c.isEmpty()) {
                        tvStatus = "Najpierw połącz telefon z telewizorem (zeskanuj kod QR z Kontrolerów)."
                        return@TvButton
                    }
                    scope.launch {
                        try {
                            tvStatus = "Sprawdzam najnowszą wersję…"
                            val r = com.swordfish.lemuroid.retrotv.update.Updater.fetchLatest()
                            val apk =
                                com.swordfish.lemuroid.retrotv.update.Updater.download(this@PhoneHomeActivity, r) { p ->
                                    tvStatus = "Pobieram ${r.versionName}… ${(p * 100).toInt()}%"
                                }
                            tvStatus = "Wysyłam na telewizor…"
                            tvStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uploadApk(host, c, apk) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            tvStatus = "Nie udało się: ${e.message}. Retro TV musi być otwarte na telewizorze, w tej samej sieci Wi-Fi."
                        }
                    }
                })
                Text("Telefon pobierze nową wersję i wyśle ją na telewizor – przydaje się, gdy telewizor nie może pobrać jej sam.", color = K.muted, fontSize = 13.sp)
                tvStatus?.let { Text(it, color = K.text, fontSize = 14.sp) }
            }

            Card("Gry") {
                TvButton("Wyślij gry na telewizor", modifier = Modifier.fillMaxWidth(), onClick = {
                    if (validCode() != null) filePicker.launch(arrayOf("*/*"))
                })
                Text("Pliki trafią do pamięci telewizora (działa w tej samej sieci Wi-Fi).", color = K.muted, fontSize = 13.sp)
            }

            Card("Gra przez internet") {
                TvButton("Dołącz do gry", modifier = Modifier.fillMaxWidth(), onClick = {
                    startActivity(Intent(this@PhoneHomeActivity, NetplayGuestActivity::class.java))
                })
                Text("Kod pokoju widzi gospodarz na swoim telewizorze. Przyciski pojawią się na ekranie.", color = K.muted, fontSize = 13.sp)
            }

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

@Composable
private fun Card(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), color = K.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        content()
    }
}
