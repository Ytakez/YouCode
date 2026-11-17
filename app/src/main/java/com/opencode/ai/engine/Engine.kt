package com.opencode.ai.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * Настоящий opencode, запущенный внутри приложения как `opencode serve` на localhost.
 * Идея и сборка взяты из MargyCode (MIT, см. engine/LICENSE.MargyCode).
 *
 * Бинарник собирается скриптом engine/build-engine.sh и кладётся в
 * lib/arm64-v8a/libopencode.so: из папки библиотек Android разрешает запускать файлы.
 * Запросы идут от официального opencode, поэтому бесплатные модели не блокируются.
 */
class Engine(private val context: Context) {
    private var process: Process? = null
    var port = 0
        private set
    val password: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    val binary get() = File(context.applicationInfo.nativeLibraryDir, "libopencode.so")
    val available get() = binary.exists()

    private val home get() = File(context.filesDir, "home")
    private val workspace get() = File(context.filesDir, "workspace")
    private val config get() = File(home, ".config/opencode")
    private val log get() = File(context.filesDir, "opencode.log")
    private val tmp get() = File(context.cacheDir, "tmp")

    val running get() = process?.isAlive == true

    private fun baseEnv(): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "XDG_CONFIG_HOME" to File(home, ".config").absolutePath,
        "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
        "XDG_STATE_HOME" to File(home, ".local/state").absolutePath,
        "XDG_CACHE_HOME" to File(home, ".cache").absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "SHELL" to "/system/bin/sh",
        "PATH" to (System.getenv("PATH") ?: "/system/bin"),
    )

    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (running) return@withContext
        if (!available) error("opencode не найден в приложении: $binary")
        port = ServerSocket(0).use { it.localPort }
        for (d in listOf(home, workspace, tmp, config)) d.mkdirs()
        val pb = ProcessBuilder(binary.absolutePath, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
            .directory(workspace)
            .redirectErrorStream(true)
            .redirectOutput(log)
        pb.environment().putAll(baseEnv() + ("OPENCODE_PASSWORD" to password))
        process = pb.start()
        // Первый запуск создаёт базу и занимает несколько секунд.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!running) error("opencode остановился: " + log.readText().takeLast(1500))
            if (ping()) return@withContext
            delay(250)
        }
        error("opencode не запустился за минуту: " + log.readText().takeLast(1500))
    }

    private fun ping(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 2000
        c.setRequestProperty("Authorization", auth())
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun auth() = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())

    fun stop() {
        process?.destroy()
        process = null
    }
}
