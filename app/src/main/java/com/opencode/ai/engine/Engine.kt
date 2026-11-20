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
 * Встроенный OpenCode: приложение запускает `opencode serve` на localhost
 * и общается с ним по обычному HTTP API (как десктопный клиент opencode).
 *
 * Бинарник (engine/build-engine.sh) лежит в jniLibs под именем libopencode.so:
 * только из папки библиотек Android позволяет запускать файлы.
 *
 * Пароль случайный на каждый запуск, сервер слушает только 127.0.0.1 —
 * снаружи приложения недоступен.
 */
class Engine(private val context: Context) {

    private var process: Process? = null

    /** Порт выбирается свободный при каждом запуске. */
    var port: Int = 0
        private set

    /** Случайный пароль на сессию. */
    val password: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    val home get() = File(context.filesDir, "home")
    /** Папка, которую opencode считает проектом: инструменты читают/пишут сюда. */
    val workspace get() = File(context.filesDir, "workspace")
    val config get() = File(home, ".config/opencode")
    val log get() = File(context.filesDir, "opencode.log")
    private val tmp get() = File(context.cacheDir, "tmp")

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libopencode.so")

    val available: Boolean get() = binary.exists()
    val running: Boolean get() = process?.isAlive == true

    /** Дополнительные переменные окружения (Claude Code, Linux и т.п.). */
    var extraEnv: () -> Map<String, String> = { emptyMap() }
    /** Вызывается перед стартом процесса (запись конфигов, resolv и т.д.). */
    var beforeStart: () -> Unit = {}

    /** Linux-подобное окружение для opencode и всего, что он запускает. */
    fun baseEnv(): Map<String, String> {
        val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val path = listOf(
            systemPath,
            "/data/local/tmp",
            File(home, ".local/bin").absolutePath,
            File(workspace, "bin").absolutePath,
            "/system/bin",
            "/system/xbin",
            "/vendor/bin"
        ).joinToString(":")
        return mapOf(
            "HOME" to home.absolutePath,
            "XDG_CONFIG_HOME" to File(home, ".config").absolutePath,
            "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
            "XDG_STATE_HOME" to File(home, ".local/state").absolutePath,
            "XDG_CACHE_HOME" to File(home, ".cache").absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "SHELL" to "/system/bin/sh",
            "PATH" to path,
            // Сеть без прокси-ограничений из окружения системы
            "NO_PROXY" to "*",
            "no_proxy" to "*",
            "CURL_CA_BUNDLE" to "",
            "SSL_CERT_FILE" to "",
            // Разрешаем node/bun/npm кэши в home приложения
            "npm_config_cache" to File(home, ".npm").absolutePath,
            "BUN_INSTALL" to File(home, ".bun").absolutePath,
        )
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        check(available) { "opencode не найден в приложении: $binary" }

        for (d in listOf(home, workspace, tmp, config)) d.mkdirs()
        port = ServerSocket(0).use { it.localPort }
        beforeStart()

        val builder = ProcessBuilder(
            binary.absolutePath, "serve",
            "--hostname", "127.0.0.1",
            "--port", port.toString()
        )
            .directory(workspace)
            .redirectErrorStream(true)
            .redirectOutput(log)

        // Важно: OPENCODE_PASSWORD (не OPENCODE_SERVER_PASSWORD) — так ожидает opencode v2.x
        builder.environment().putAll(baseEnv() + extraEnv() + ("OPENCODE_PASSWORD" to password))
        process = builder.start()

        try {
            awaitReady()
        } catch (t: Throwable) {
            // Не оставляем «полузапущенный» процесс
            stop()
            throw t
        }
    }

    /** Первый запуск создаёт базу данных, поэтому ждём до минуты. */
    private suspend fun awaitReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            check(running) { "opencode остановился: ${logTail()}" }
            if (ping()) return
            delay(250)
        }
        error("opencode не запустился за минуту: ${logTail()}")
    }

    private fun ping(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 2000
        c.setRequestProperty("Authorization", auth())
        try {
            c.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    fun auth(): String =
        "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    fun stop() {
        val p = process ?: return
        process = null
        terminate(p)
    }

    /** То же, что stop(), но ожидание в отдельном потоке: безопасно вызывать с главного потока. */
    fun stopAsync() {
        val p = process ?: return
        process = null
        Thread({ terminate(p) }, "opencode-stop").start()
    }

    /**
     * Выход из аккаунта: процесс убивается сразу (SIGKILL), а папки пользователя уходят в корзину
     * переименованием (мгновенно) и удаляются в фоне. Так следующий вход не гонится с удалением.
     */
    fun wipeData() {
        process?.let { p -> process = null; runCatching { p.destroyForcibly(); p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } }
        val trash = File(context.filesDir, ".trash-${System.currentTimeMillis()}").apply { mkdirs() }
        for (d in listOf(home, workspace, tmp)) {
            if (d.exists() && !d.renameTo(File(trash, d.name))) d.deleteRecursively()
        }
        runCatching { log.delete() }
        Thread({
            context.filesDir.listFiles { f -> f.name.startsWith(".trash-") }?.forEach { it.deleteRecursively() }
        }, "opencode-wipe").start()
    }

    private fun terminate(p: Process) {
        p.destroy()
        // Если за 1.5 с не умер — жёстко (как надёжный cleanup в Margy-подходе)
        try {
            if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: Exception) {
            runCatching { p.destroyForcibly() }
        }
    }

    fun logTail(): String = runCatching { log.readText().takeLast(1500) }.getOrDefault("")

    private companion object {
        const val READY_TIMEOUT_MS = 60_000L
    }
}
