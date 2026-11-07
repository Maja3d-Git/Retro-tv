package com.swordfish.lemuroid.retrotv

import android.content.Context
import android.os.Build
import android.util.Log
import com.swordfish.lemuroid.BuildConfig
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dziennik aplikacji Retro TV.
 *
 * Każdy proces (główny i proces gry) pisze do własnego pliku w filesDir/logs.
 * Gdy plik przekroczy [MAX_FILE_BYTES], poprzednia zawartość trafia do pliku ".1".
 */
object RetroTvLog {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val DIR = "logs"

    data class Line(val time: String, val level: Char, val tag: String, val message: String, val raw: String) {
        val isError: Boolean get() = level == 'E' || level == 'A'
        val isWarning: Boolean get() = level == 'W'
    }

    @Volatile
    private var file: File? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(
        context: Context,
        processName: String,
    ) {
        if (file != null) return
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        file = File(dir, "retrotv-$processName.log")

        Timber.plant(FileTree())
        installCrashHandler()

        Timber.i(
            "Start procesu %s · Retro TV %s (build %d) · Android %s (API %d) · %s %s",
            processName,
            BuildConfig.VERSION_NAME,
            BuildConfig.RETROTV_BUILD,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
        )
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            write(Log.ASSERT, "CRASH", "Awaria w wątku ${thread.name}: ${Log.getStackTraceString(throwable)}")
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun levelChar(priority: Int): Char =
        when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        }

    fun write(
        priority: Int,
        tag: String?,
        message: String,
    ) {
        val target = file ?: return
        val line = "${timeFormat.format(Date())} ${levelChar(priority)}/${tag ?: "RetroTV"}: $message\n"
        synchronized(lock) {
            runCatching {
                if (target.length() > MAX_FILE_BYTES) {
                    val old = File(target.path + ".1")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(line)
            }
        }
    }

    /** Wszystkie pliki logów (bieżące i poprzednie) ze wszystkich procesów. */
    fun logFiles(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles()?.filter { it.isFile && it.name.startsWith("retrotv-") }?.sortedBy { it.name } ?: emptyList()
    }

    /** Wczytuje linie ze wszystkich plików i sortuje je chronologicznie (najnowsze na końcu). */
    fun readLines(
        context: Context,
        limit: Int = 2000,
    ): List<Line> {
        val pattern = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEA])/([^:]*): (.*)$""")
        val result = mutableListOf<Line>()
        synchronized(lock) {
            logFiles(context).forEach { f ->
                var current: Line? = null
                runCatching {
                    f.forEachLine { raw ->
                        val m = pattern.find(raw)
                        if (m != null) {
                            current?.let { result.add(it) }
                            val (time, level, tag, msg) = m.destructured
                            current = Line(time, level[0], tag, msg, raw)
                        } else if (current != null) {
                            // Kontynuacja wielolinijkowego wpisu (np. stos wywołań).
                            val c = current!!
                            current = c.copy(message = c.message + "\n" + raw, raw = c.raw + "\n" + raw)
                        }
                    }
                }
                current?.let { result.add(it) }
            }
        }
        return result.sortedBy { it.time }.takeLast(limit)
    }

    /** Cały dziennik jako tekst, gotowy do wysłania. */
    fun exportText(context: Context): String {
        val header =
            buildString {
                appendLine("Retro TV ${BuildConfig.VERSION_NAME} (build ${BuildConfig.RETROTV_BUILD}, ${BuildConfig.RETROTV_BUILD_TIME})")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Wygenerowano: ${timeFormat.format(Date())}")
                appendLine("----")
            }
        return header + readLines(context, limit = 10_000).joinToString("\n") { it.raw }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            logFiles(context).forEach { it.delete() }
        }
        Timber.i("Dziennik wyczyszczony")
    }

    private class FileTree : Timber.Tree() {
        override fun isLoggable(
            tag: String?,
            priority: Int,
        ): Boolean = BuildConfig.DEBUG || priority >= Log.INFO

        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            // Timber dokleja już stos wywołań wyjątku do treści wiadomości.
            write(priority, tag, message)
        }
    }
}
