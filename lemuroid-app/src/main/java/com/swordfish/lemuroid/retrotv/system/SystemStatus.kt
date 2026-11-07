package com.swordfish.lemuroid.retrotv.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.InputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

data class NetworkInfo(
    val connected: Boolean,
    val type: String,
    val internetValidated: Boolean,
    val ipAddress: String?,
    /** Nazwa sieci Wi-Fi; null gdy brak zgody na lokalizację albo to nie Wi-Fi. */
    val ssid: String? = null,
)

data class ServiceCheck(val name: String, val ok: Boolean, val detail: String)

data class Controller(val name: String, val detail: String)

object SystemStatus {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun network(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkInfo(false, "Brak połączenia", false, null)
        val caps = cm.getNetworkCapabilities(network)
        val type =
            when {
                caps == null -> "Nieznane"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Kabel (Ethernet)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Sieć komórkowa"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Inne"
            }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val ip =
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        val ssid = if (type == "Wi-Fi") wifiName(context) else null
        return NetworkInfo(true, type, validated, ip, ssid)
    }

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun wifiName(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
        val name = raw.removeSurrounding("\"")
        return name.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /** Sprawdza, czy odpowiadają serwery, z których korzysta aplikacja. */
    suspend fun checkServices(): List<ServiceCheck> =
        withContext(Dispatchers.IO) {
            listOf(
                "Serwer aktualizacji" to "https://api.github.com/",
                "Rdzenie emulatorów" to "https://github.com/Swordfish90/LemuroidCores",
                "Okładki gier" to "https://thumbnails.libretro.com/",
            ).map { (name, url) -> check(name, url) }
        }

    private fun check(
        name: String,
        url: String,
    ): ServiceCheck {
        val start = System.nanoTime()
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", "RetroTV-Android").build()
            client.newCall(request).execute().use { response ->
                val ms = (System.nanoTime() - start) / 1_000_000
                if (response.code < 500) {
                    ServiceCheck(name, true, "$ms ms")
                } else {
                    ServiceCheck(name, false, "błąd serwera ${response.code}")
                }
            }
        } catch (e: Exception) {
            ServiceCheck(name, false, e.message ?: "brak odpowiedzi")
        }
    }

    fun controllers(): List<Controller> {
        return com.swordfish.lemuroid.retrotv.pad.PadDetector.pads()
            .map { device ->
                val details = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val battery = device.batteryState
                    if (battery.isPresent && !battery.capacity.isNaN()) {
                        details.add("bateria ${(battery.capacity * 100).toInt()}%")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && device.isExternal) {
                    details.add("zewnętrzny")
                }
                Controller(device.name, details.joinToString(" · ").ifEmpty { "podłączony" })
            }
    }
}
