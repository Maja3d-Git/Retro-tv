package com.swordfish.lemuroid.retrotv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.swordfish.lemuroid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Informacje o wydaniu z GitHub Releases. */
data class ReleaseInfo(
    val build: Int,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val apkUrl: String,
    val apkSize: Long,
) {
    val versionName: String get() = "1.0.$build"
    val isNewerThanInstalled: Boolean get() = build > BuildConfig.RETROTV_BUILD
}

/**
 * Sprawdza, pobiera i instaluje nowe wersje Retro TV z wydań w repozytorium GitHub.
 * Wydania tworzy workflow "Build APK" z tagiem "build-N".
 */
object Updater {
    private const val APK_NAME = "retro-tv.apk"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val latestUrl get() = "https://api.github.com/repos/${BuildConfig.RETROTV_REPO}/releases/latest"

    suspend fun fetchLatest(): ReleaseInfo =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(latestUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "RetroTV-Android")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Serwer aktualizacji odpowiedział kodem ${response.code}")
                val json = JSONObject(response.body?.string() ?: throw IOException("Pusta odpowiedź serwera"))
                val tag = json.optString("tag_name")
                val build = tag.removePrefix("build-").toIntOrNull() ?: throw IOException("Nieznany format wydania: $tag")
                val assets = json.optJSONArray("assets")
                var url: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == APK_NAME) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                        }
                    }
                }
                ReleaseInfo(
                    build = build,
                    title = json.optString("name", tag),
                    notes = json.optString("body", "").trim(),
                    publishedAt = json.optString("published_at", ""),
                    apkUrl = url ?: throw IOException("Wydanie $tag nie zawiera pliku $APK_NAME"),
                    apkSize = size,
                )
            }
        }

    /** Pobiera APK; [onProgress] dostaje wartość 0..1 (albo -1, gdy rozmiar nie jest znany). */
    suspend fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "retro-tv-${release.build}.apk")
            val partial = File(dir, target.name + ".part")

            Timber.i("Pobieranie aktualizacji %s z %s", release.versionName, release.apkUrl)
            val request = Request.Builder().url(release.apkUrl).header("User-Agent", "RetroTV-Android").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Pobieranie nie powiodło się (kod ${response.code})")
                val body = response.body ?: throw IOException("Pusta odpowiedź serwera")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    withContext(Dispatchers.Main) { onProgress(done.toFloat() / total) }
                                }
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Nie udało się zapisać pliku aktualizacji")
            Timber.i("Pobrano aktualizację: %d bajtów", target.length())
            target
        }

    /** Czy system pozwala tej aplikacji instalować pakiety (Android 8+ wymaga zgody użytkownika). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Otwiera ekran systemowy, na którym użytkownik zezwala na instalowanie aplikacji z Retro TV. */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                Timber.w(e, "Brak ekranu zgody na instalację, otwieram ustawienia aplikacji")
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
                    )
                }
            }
        }
    }

    fun install(
        activity: Activity,
        apk: File,
    ) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.retrotv.files", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        Timber.i("Uruchamiam instalator dla %s", apk.name)
        activity.startActivity(intent)
    }
}
