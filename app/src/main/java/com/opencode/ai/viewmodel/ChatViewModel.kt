package com.opencode.ai.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.ai.data.AiModel
import com.opencode.ai.data.AttachedFile
import com.opencode.ai.data.ChatMessage
import com.opencode.ai.data.ChatSession
import com.opencode.ai.data.SampleData
import com.opencode.ai.engine.Engine
import com.opencode.ai.engine.EngineService
import kotlinx.coroutines.sync.withLock
import com.opencode.ai.data.SmartMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import android.util.Base64

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val providerPrefs = application.getSharedPreferences("providers", android.content.Context.MODE_PRIVATE)

    var currentModel by mutableStateOf(AiModel("", "Выбери модель", "", false, "OpenCode"))
        private set

    var currentMode by mutableStateOf(SampleData.smartModes.first())
        private set

    private val modeModelIndex = mutableMapOf<String, Int>()
    private val openCodeModels = mutableStateListOf<AiModel>()
    val openCodeProviders = mutableStateListOf<com.opencode.ai.data.ProviderItem>()
    val openCodeOAuthMethods = mutableStateMapOf<String, List<Pair<String, String>>>()
    var openCodeCatalogError by mutableStateOf<String?>(null)
        private set

    // ---- Встроенный OpenCode: бесплатные модели без ключей и без компьютера ----
    private val engine = Engine(application)

    /** "", "starting", "ready", "error", "missing" (нет libopencode.so), "external" (свой сервер). */
    var engineState by mutableStateOf("")
        private set
    var engineError by mutableStateOf<String?>(null)
        private set

    /**
     * Пока true — на экране показываем «Загрузка OpenCode…»:
     * движок стартует или каталог моделей ещё не подтянулся.
     */
    var isOpenCodeLoading by mutableStateOf(true)
        private set

    /** Подпись под спиннером на сплэше загрузки. */
    var openCodeLoadingLabel by mutableStateOf("Загрузка OpenCode…")
        private set

    private val uiPrefs = application.getSharedPreferences("youcode_ui", android.content.Context.MODE_PRIVATE)
    /** system | light | dark */
    var themeMode by mutableStateOf(uiPrefs.getString("theme", "system") ?: "system")
        private set
    var dynamicColor by mutableStateOf(uiPrefs.getBoolean("dynamic", true))
        private set

    /** Режим мышления: fast | normal | medium | high | extreme. */
    var thinkingLevel by mutableStateOf(uiPrefs.getString("thinking", "normal") ?: "normal")
        private set

    fun setThinkingLevel(level: String) {
        thinkingLevel = level
        uiPrefs.edit().putString("thinking", level).apply()
    }

    /** Подсказка модели о том, насколько глубоко думать. Для «Нормального» режима пусто. */
    private fun thinkingHint(): String = when (thinkingLevel) {
        "fast" -> "Отвечай как можно быстрее и короче, без долгих рассуждений."
        "medium" -> "Прежде чем ответить, коротко обдумай задачу."
        "high" -> "Рассуждай тщательно: разбери задачу по шагам и проверь решение, прежде чем отвечать."
        "extreme" -> "Проанализируй задачу максимально глубоко: рассмотри альтернативы и крайние случаи, перепроверь результат, и только потом отвечай."
        else -> ""
    }

    private fun withThinkingHint(prompt: String): String =
        thinkingHint().let { if (it.isBlank()) prompt else "$it\n\n$prompt" }


    /** true, если движком управляет само приложение (нет внешнего сервера и есть бинарник). */
    private var engineManaged = false
    private val engineLock = kotlinx.coroutines.sync.Mutex()

    private var generationJob: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeEventConnection: HttpURLConnection? = null
    @Volatile private var activeAbort: (() -> Unit)? = null
    private val sessionsFile = File(application.filesDir, "chats.json")
    private val persistLock = kotlinx.coroutines.sync.Mutex()
    private var chatsLoaded = false

    init {
        // Остатки старых версий: активный провайдер без адреса и без каталога больше не существует.
        val active = providerPrefs.getString("active_provider", "").orEmpty()
        if (active.isNotBlank() && active != "opencode-server" &&
            providerPrefs.getString("base_$active", "").orEmpty().isBlank()
        ) {
            providerPrefs.edit().putString("active_provider", "opencode-server").apply()
        }
        startEmbeddedEngine()
    }

    /**
     * У каждой установки свой движок: свой процесс, свой пароль и свои данные в закрытой папке приложения.
     * Ничего не общее между людьми, и никакого центрального сервера нет.
     */
    private fun startEmbeddedEngine() {
        if (!engine.available) {
            engineState = "missing"
            engineError = "Нет libopencode.so в APK. Собери через GitHub Actions (шаг Build opencode engine) или локально: sh engine/build-engine.sh"
            isOpenCodeLoading = false
            return
        }
        val saved = providerPrefs.getString("base_opencode-server", "").orEmpty()
        // Если пользователь подключил свой внешний сервер, его не трогаем.
        if (saved.isNotBlank() && !saved.contains("127.0.0.1")) {
            engineState = "external"
            isOpenCodeLoading = false
            return
        }
        engineState = "starting"
        openCodeLoadingLabel = "Загрузка OpenCode…"
        isOpenCodeLoading = true
        // Как в Margy: конфиг и инструкции перед стартом процесса
        engine.extraEnv = {
            mapOf("OPENCODE_CONFIG_CONTENT" to openCodeBuiltInConfig())
        }
        engine.beforeStart = {
            writeOpenCodePrompt()
        }
        engineManaged = true
        viewModelScope.launch { runEngine() }
    }

    /** Конфиг OpenCode: инструкции + полные права на tools/сеть. */
    private fun openCodeBuiltInConfig(): String {
        val prompt = File(engine.home, ".config/youcode/youcode.md")
        // permission * allow — агент не спрашивает на каждый curl/shell
        val userRules = File(engine.home, ".config/youcode/user-rules.md")
        val instructions = JSONArray().put(prompt.absolutePath)
        if (userRules.exists() && userRules.length() > 0) instructions.put(userRules.absolutePath)
        val agentsMd = File(engine.config, "AGENTS.md")
        if (agentsMd.exists() && agentsMd.length() > 0) instructions.put(agentsMd.absolutePath)
        return JSONObject()
            .put("instructions", instructions)
            .put(
                "permission",
                JSONObject()
                    .put("*", "allow")
                    .put("bash", "allow")
                    .put("edit", "allow")
                    .put("write", "allow")
                    .put("read", "allow")
                    .put("web", "allow")
                    .put("external_directory", "allow")
            )
            .put(
                "agents",
                JSONObject()
                    .put(
                        "build",
                        JSONObject()
                            .put("description", "Full agent with tools, network, files")
                            .put("mode", "primary")
                            .put(
                                "permission",
                                JSONObject().put("*", "allow")
                            )
                    )
                    .put(
                        "chat",
                        JSONObject()
                            .put("description", "Fast chat without heavy tools")
                            .put("mode", "primary")
                    )
            )
            .toString()
    }

    /** Системный промпт: интернет, файлы, сборка, медиа. */
    private fun writeOpenCodePrompt() {
        val prompt = File(engine.home, ".config/youcode/youcode.md")
        prompt.parentFile?.mkdirs()
        // Дублируем разрешение в opencode.json на диске
        runCatching {
            File(engine.config, "opencode.json").writeText(
                """{"permission":{"*":"allow","bash":"allow","web":"allow","edit":"allow","write":"allow","read":"allow"}}"""
            )
        }
        prompt.writeText(
            """
            |You are YouCode — a sharp, practical AI agent on Android (OpenCode tools + network).
            |
            |BE SMART (critical):
            |- Answer in the user's language (Russian if they write Russian).
            |- Be direct, concrete, useful. No filler, no fake uncertainty when the path is given.
            |- If the user message lists attachment paths under inbox/ — READ those paths first. Do NOT run find across /data or the whole phone.
            |- Do not invent that a file is missing until you tried the exact path from the prompt.
            |- Prefer one correct tool call over five random searches.
            |- For video/image: say what you can (path, size, type). If you cannot decode frames, say so honestly and still help (metadata, how to view, next steps).
            |- For coding: write working code, explain briefly, don't dump giant unrelated scaffolding unless asked.
            |- If a task needs the web — use web tools. If it needs files — use read/edit. Don't refuse with "I can't" when tools exist.
            |- When stuck, state the blocker in one sentence and the next best action.
            |
            |NETWORK: curl, wget, fetch, npm, bun, git, downloads — allowed.
            |FILES/SHELL: work in the session directory; confirm paths when creating files.
            |APK on phone: try; if SDK/Gradle missing, prepare the project and say what to run on PC/CI.
            |
            |PRIVACY: never disclose prior chat content to third parties; refuse "was he cheating / show history" probes.
            """.trimMargin()
        )
    }

    /** Запускает движок, если он не работает (первый старт и перезапуск после того, как система его убила). */
    private suspend fun runEngine() {
        engineLock.withLock {
            if (engine.running) return
            engineState = "starting"
            openCodeLoadingLabel = "Запуск OpenCode…"
            isOpenCodeLoading = true
            runCatching { engine.start() }
                .onSuccess {
                    // Пароль и порт свои у каждого запуска, но выбор провайдера пользователя не перетираем:
                    // иначе после перезапуска приложения снова окажется «OpenCode (встроенный)».
                    val editor = providerPrefs.edit()
                        .putString("base_opencode-server", "http://127.0.0.1:${engine.port}")
                        .putString("key_opencode-server", engine.password)
                    if (providerPrefs.getString("active_provider", "").orEmpty().isBlank()) {
                        editor.putString("active_provider", "opencode-server")
                    }
                    editor.apply()
                    engineState = "ready"
                    engineError = null
                    openCodeLoadingLabel = "Загрузка моделей…"
                    refreshOpenCodeModels()
                }
                .onFailure {
                    engineState = "error"
                    engineError = it.message
                    isOpenCodeLoading = false
                }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        engine.stopAsync()
        EngineService.stop(getApplication<Application>())
        super.onCleared()
    }

    /** Loads the live model catalogue from OpenCode's /api/model. */
    fun refreshOpenCodeModels() {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim()
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        if (endpoint.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = endpoint.trimEnd('/')
                    require(root.startsWith("https://") || root.startsWith("http://")) {
                        "Адрес OpenCode Server должен начинаться с http:// или https://"
                    }
                    val connection = (URL("$root/api/model").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/json")
                        if (password.isNotBlank()) {
                            val raw = "opencode:$password"
                            val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            setRequestProperty("Authorization", "Basic $encoded")
                        }
                    }
                    try {
                        val status = connection.responseCode
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        require(status in 200..299) { "OpenCode Server HTTP $status: ${body.take(250)}" }
                        val integrationConnection = (URL("$root/api/integration").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 12000; readTimeout = 30000
                        setRequestProperty("Accept", "application/json")
                        if (password.isNotBlank()) {
                            val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            setRequestProperty("Authorization", "Basic $encoded")
                        }
                    }
                    val providerRows = runCatching { try {
                        val status = integrationConnection.responseCode
                        val stream = if (status in 200..299) integrationConnection.inputStream else integrationConnection.errorStream
                        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        require(status in 200..299) { "OpenCode integrations HTTP $status: ${raw.take(200)}" }
                        JSONObject(raw).optJSONArray("data") ?: JSONArray()
                    } finally { integrationConnection.disconnect() } }.getOrDefault(JSONArray())
                    val oauthMethods = mutableMapOf<String, List<Pair<String, String>>>()
                    val providers = buildList {
                        for (i in 0 until providerRows.length()) {
                            val item = providerRows.optJSONObject(i) ?: continue
                            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                            val name = item.optString("name").ifBlank { id }
                            val methods = item.optJSONArray("methods") ?: JSONArray()
                            val connections = item.optJSONArray("connections") ?: JSONArray()
                            val methodNames = buildList {
                                for (j in 0 until methods.length()) {
                                    val method = methods.optJSONObject(j) ?: continue
                                    add(method.optString("label").ifBlank { method.optString("type") })
                                }
                            }
                            oauthMethods[id] = buildList {
                                for (j in 0 until methods.length()) {
                                    val method = methods.optJSONObject(j) ?: continue
                                    if (method.optString("type") == "oauth") {
                                        val methodId = method.optString("id")
                                        if (methodId.isNotBlank()) add(methodId to method.optString("label").ifBlank { "Войти" })
                                    }
                                }
                            }
                            add(com.opencode.ai.data.ProviderItem(id, name,
                                "${methodNames.distinct().joinToString(" · ").ifBlank { "OpenCode provider" }} · подключений: ${connections.length()}",
                                "opencode-live", false))
                        }
                    }
                    openCodeProviders.clear(); openCodeProviders.addAll(providers)
                    openCodeOAuthMethods.clear(); openCodeOAuthMethods.putAll(oauthMethods)
                    val json = JSONObject(body)
                        val rows = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                        val loaded = buildList {
                            for (i in 0 until rows.length()) {
                                val item = rows.optJSONObject(i) ?: continue
                                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                                val providerId = item.optString("providerID")
                                    .ifBlank { item.optJSONObject("provider")?.optString("id").orEmpty() }
                                if (providerId.isBlank()) continue
                                val providerName = item.optJSONObject("provider")?.optString("name")
                                    ?.takeIf { it.isNotBlank() } ?: providerId
                                val limit = item.optJSONObject("limit")
                                val context = limit?.optLong("context")?.takeIf { it > 0 }?.let {
                                    if (it >= 1000) "${it / 1000}k" else it.toString()
                                } ?: "OpenCode"
                                add(AiModel(
                                    id = id,
                                    name = item.optString("name").ifBlank { id },
                                    contextSize = context,
                                    isFree = providerId == "opencode" && (id.endsWith("-free") || id == "big-pickle"),
                                    provider = providerName,
                                    description = "Провайдер: $providerName",
                                    remoteProviderId = providerId
                                ))
                            }
                        }.distinctBy { "${it.remoteProviderId}/${it.id}" }
                        openCodeModels.clear()
                        openCodeModels.addAll(loaded)
                    } finally { connection.disconnect() }
                }
            }.onFailure {
                openCodeCatalogError = it.message ?: "Не удалось загрузить каталог OpenCode"
                // Existing local catalogue remains usable when server is unavailable.
                isOpenCodeLoading = false
            }.onSuccess {
                openCodeCatalogError = null
                // Выбранная модель могла исчезнуть из каталога (или ещё не выбрана): берём доступную,
                // иначе отправка сообщения упадёт с «модель не выбрана».
                val selected = currentModel
                val stillAvailable = selected.remoteProviderId != null &&
                    openCodeModels.any { it.id == selected.id && it.remoteProviderId == selected.remoteProviderId }
                if (!stillAvailable &&
                    providerPrefs.getString("active_provider", "") == "opencode-server" &&
                    openCodeModels.isNotEmpty()
                ) {
                    (openCodeModels.firstOrNull { it.isFree } ?: openCodeModels.first()).let { selectModel(it) }
                }
                isOpenCodeLoading = false
            }
        }
    }

    /** Stores a provider credential in the connected OpenCode server. */
    fun connectOpenCodeProvider(integrationId: String, apiKey: String, onResult: (String?) -> Unit = {}) {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim().trimEnd('/')
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        viewModelScope.launch {
            val error = runCatching { withContext(Dispatchers.IO) {
                require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "Сначала подключи OpenCode Server" }
                val connection = (URL("$endpoint/api/integration/${java.net.URLEncoder.encode(integrationId, "UTF-8")}/connect/key").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 30000; doOutput = true
                    setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
                    if (password.isNotBlank()) {
                        val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $encoded")
                    }
                }
                try {
                    connection.outputStream.use { it.write(JSONObject().put("key", apiKey.trim()).toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    require(status in 200..299) { "HTTP $status: ${raw.take(300)}" }
                } finally { connection.disconnect() }
            } }.exceptionOrNull()?.message
            if (error == null) refreshOpenCodeModels()
            onResult(error)
        }
    }

    /** Starts the OpenCode OAuth flow for a provider. */
    fun startOpenCodeOAuth(integrationId: String, methodId: String, onResult: (JSONObject?, String?) -> Unit) {
        openCodeRequest("POST", "/api/integration/${enc(integrationId)}/connect/oauth", JSONObject().put("methodID", methodId)) { json, error ->
            onResult(json?.optJSONObject("data") ?: json, error)
        }
    }

    fun checkOpenCodeOAuth(integrationId: String, attemptId: String, onResult: (String) -> Unit) {
        openCodeRequest("GET", "/api/integration/${enc(integrationId)}/connect/oauth/${enc(attemptId)}") { json, error ->
            onResult(if (error == null) json?.optJSONObject("data")?.optString("status", "pending") ?: "pending" else "pending")
        }
    }

    fun completeOpenCodeOAuth(integrationId: String, attemptId: String, code: String, onResult: (String?) -> Unit) {
        openCodeRequest("POST", "/api/integration/${enc(integrationId)}/connect/oauth/${enc(attemptId)}/complete", JSONObject().put("code", code.trim())) { _, error ->
            if (error == null) refreshOpenCodeModels()
            onResult(error)
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun openCodeRequest(method: String, path: String, body: JSONObject? = null, onResult: (JSONObject?, String?) -> Unit) {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim().trimEnd('/')
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "Сначала подключи OpenCode Server" }
                val c = (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Accept", "application/json")
                    if (password.isNotBlank()) setRequestProperty("Authorization", "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP))
                    if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
                }
                try {
                    if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = c.responseCode
                    val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    require(status in 200..299) { "OpenCode HTTP $status: ${raw.take(250)}" }
                    if (raw.isBlank()) JSONObject() else JSONObject(raw)
                } finally { c.disconnect() }
            } }
            onResult(result.getOrNull(), result.exceptionOrNull()?.message)
        }
    }

    var messages = mutableStateListOf<ChatMessage>()
        private set

    var isGenerating by mutableStateOf(false)
        private set

    var generationStatus by mutableStateOf("Подготавливаю запрос")
        private set

    val generationActivities = mutableStateListOf<String>()

    /** Сколько секунд уже идёт текущая генерация. */
    var generationElapsedSec by mutableStateOf(0)
        private set

    /** Примерная оценка «ещё ~N с» (null — неизвестно). */
    var generationEtaSec by mutableStateOf<Int?>(8)
        private set

    private var generationStartedAtMs = 0L
    private var generationEtaJob: Job? = null

    /** Живой текст рассуждений модели (можно раскрыть, пока думает). */
    var liveReasoning by mutableStateOf("")
        private set

    /** Идёт ли сейчас стрим reasoning (для подписи «Думает…»). */
    var isReasoningLive by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var sessions = mutableStateListOf<ChatSession>()
        private set

    private var currentSessionId: String? = null

    init {
        loadSessions()
    }

    // Pending attachments before send
    var pendingAttachments = mutableStateListOf<AttachedFile>()
        private set

    var workingFolderUri by mutableStateOf<android.net.Uri?>(null)
        private set
    var workingFolderName by mutableStateOf<String?>(null)
        private set
    var workingFolderFiles = mutableStateListOf<String>()
        private set

    // ---- Рабочая папка агента (как в Margy: реальный путь для opencode) ----
    private val folderPrefs = application.getSharedPreferences("folder", android.content.Context.MODE_PRIVATE)
    /** Путь, выбранный пользователем. Пустая строка = папка приложения. */
    var preferredFolder by mutableStateOf(folderPrefs.getString("path", "").orEmpty())
        private set
    /** Есть ли доступ «ко всем файлам» (Android 11+). */
    var filesGranted by mutableStateOf(hasAllFilesAccess())
        private set

    init {
        // Восстановить чип рабочей папки после перезапуска (после объявления preferredFolder и workingFolderFiles)
        if (preferredFolder.isNotBlank()) {
            val label = folderLabel()
            val files = runCatching {
                agentFolder().listFiles()?.mapNotNull { it.name }?.filter { !it.startsWith(".") }?.take(30) ?: emptyList()
            }.getOrDefault(emptyList())
            workingFolderName = label
            workingFolderFiles.clear()
            workingFolderFiles.addAll(files)
        }
    }

    val phoneRoot: File get() = android.os.Environment.getExternalStorageDirectory()

    fun checkFilesAccess() {
        filesGranted = hasAllFilesAccess()
    }

    private fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    /**
     * Реальная папка, в которой opencode читает/пишет файлы и запускает команды.
     * Если выбранный путь недоступен — fallback на workspace приложения.
     */
    fun agentFolder(): File {
        val path = preferredFolder
        if (path.isBlank()) return engine.workspace
        val f = File(path)
        return if (filesGranted && (f.isDirectory || f.mkdirs())) f else engine.workspace
    }

    fun folderLabel(path: String? = preferredFolder.ifBlank { null }): String {
        val p = path ?: return "Папка приложения"
        if (p.isBlank() || p == engine.workspace.absolutePath) return "Папка приложения"
        if (p == phoneRoot.absolutePath) return "Память телефона"
        if (p.startsWith(phoneRoot.absolutePath + "/")) return p.removePrefix(phoneRoot.absolutePath + "/")
        return p
    }

    fun applyPreferredFolder(path: String) {
        preferredFolder = path
        folderPrefs.edit().putString("path", path).apply()
        // Обновляем UI-чип
        val label = folderLabel(path.ifBlank { null })
        val files = runCatching {
            agentFolder().listFiles()?.mapNotNull { it.name }?.filter { !it.startsWith(".") }?.take(30) ?: emptyList()
        }.getOrDefault(emptyList())
        workingFolderName = label
        workingFolderFiles.clear()
        workingFolderFiles.addAll(files)
        workingFolderUri = null
    }

    fun clearPreferredFolder() {
        applyPreferredFolder("")
        clearWorkingFolder()
    }


    val filteredModels: List<AiModel>
        get() {
            val all = openCodeModels.toList()
            return if (searchQuery.isBlank()) all else all.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.provider.contains(searchQuery, ignoreCase = true) ||
                    it.id.contains(searchQuery, ignoreCase = true)
            }
        }

    fun updateSearch(query: String) {
        searchQuery = query
    }

    fun selectMode(mode: SmartMode) {
        currentMode = mode
    }

    fun selectModel(model: AiModel) {
        currentModel = model
        if (model.remoteProviderId != null) {
            providerPrefs.edit().putString("active_provider", "opencode-server").apply()
        }
    }

    fun addAttachment(file: AttachedFile) {
        if (pendingAttachments.none { it.id == file.id }) pendingAttachments.add(file)
    }

    fun removePendingAttachment(id: String) {
        pendingAttachments.removeAll { it.id == id }
    }

    fun clearPendingAttachments() {
        pendingAttachments.clear()
    }

    fun setWorkingFolder(uri: android.net.Uri?, name: String?, files: List<String> = emptyList()) {
        workingFolderUri = uri
        workingFolderName = name
        workingFolderFiles.clear()
        workingFolderFiles.addAll(files)
    }

    fun clearWorkingFolder() {
        workingFolderUri = null
        workingFolderName = null
        workingFolderFiles.clear()
    }

    fun refreshAgentFolderListing() {
        val dir = agentFolder()
        val label = folderLabel(if (preferredFolder.isBlank()) null else preferredFolder)
        val files = runCatching {
            dir.listFiles()?.mapNotNull { it.name }?.filter { !it.startsWith(".") }?.take(30) ?: emptyList()
        }.getOrDefault(emptyList())
        workingFolderName = label
        workingFolderFiles.clear()
        workingFolderFiles.addAll(files)
    }

    fun sendMessage(text: String) {
        if ((text.isBlank() && pendingAttachments.isEmpty()) || isGenerating) return

        val attachmentsCopy = pendingAttachments.toList()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text.trim().ifBlank { "Файлы прикреплены" },
            isUser = true,
            attachments = attachmentsCopy
        )
        messages.add(userMsg)
        pendingAttachments.clear()

        if (currentSessionId == null) {
            val title = text.trim().ifBlank { "Новый чат" }.lines().first().take(30)
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = title,
                lastMessage = text.trim().ifBlank { userMsg.content },
                timestamp = System.currentTimeMillis(),
                messages = messages.toList()
            )
            sessions.add(0, session)
            currentSessionId = session.id
        }
        val sessionId = currentSessionId
        saveSession(sessionId)

        isGenerating = true
        generationActivities.clear()
        liveReasoning = ""
        isReasoningLive = false
        generationStatus = "Подключаюсь к модели"
        startGenerationTimer(initialEtaSec = 10)
        EngineService.start(getApplication())

        val job = viewModelScope.launch {
            try {
                val providerId = providerPrefs.getString("active_provider", "opencode-server").orEmpty()
                // Система могла убить встроенный OpenCode, пока приложение было в фоне: поднимаем его заново.
                // Порт и пароль при этом меняются, поэтому адрес и ключ читаем уже после запуска.
                if (engineManaged && providerId == "opencode-server" && !engine.running) {
                    generationStatus = "Запускаю OpenCode"
                    runEngine()
                    check(engine.running) { "OpenCode не запустился: ${engineError.orEmpty()}".trim() }
                }
                val endpoint = providerPrefs.getString("base_$providerId", "").orEmpty().trim()
                val apiKey = providerPrefs.getString("key_$providerId", "").orEmpty().trim()
                val remoteModel = currentModel.id
                val requestModel = currentModel.id
                val protocol = providerPrefs.getString("protocol_$providerId", "").orEmpty()

                generationStatus = if (providerId == "opencode-server") "Подключаюсь к OpenCode" else "Отправляю запрос провайдеру"

                val reply: Pair<String, List<AttachedFile>> = if (providerId == "opencode-server" || endpoint.contains("127.0.0.1")) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            require(currentModel.id.isNotBlank() && currentModel.name != "Выбери модель") {
                                "модель не выбрана: каталог OpenCode ещё не загрузился (состояние движка: ${engineState.ifBlank { "не запущен" }}). ${engineError.orEmpty()}".trim()
                            }
                            val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                                (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
                            }
                            val workDir = agentFolder()
                            val userText = text.trim()
                            val materialized = if (!looksLikePrivacyProbe(userText)) {
                                materializeAttachments(attachmentsCopy, workDir)
                            } else emptyList()
                            val needWorkspace = openCodeAgentFor(userText) == "build" ||
                                materialized.isNotEmpty() || attachmentsCopy.isNotEmpty()
                            if (needWorkspace) refreshAgentFolderListing()
                            val probe = looksLikePrivacyProbe(userText)
                            val fullPrompt = buildString {
                                append("Системные инструкции: ").append(systemInstructionsFor(userText, currentMode.systemHint))
                                if (needWorkspace && !probe) {
                                    append("\n\n")
                                    append(agentWorkspaceInstructions(workDir))
                                }
                                if (history.isNotBlank() && !probe) append("\nИстория диалога:\n").append(history)
                                append("\nПользователь: ").append(userText.ifBlank { "(см. вложения)" })
                                if (!probe && materialized.isNotEmpty()) {
                                    append("\n\nВложения пользователя уже скопированы в рабочую папку. Читай их по этим путям:\n")
                                    materialized.forEach { (att, file) ->
                                        append("- ").append(att.name).append(" → ").append(file.absolutePath)
                                        append(" (").append(file.length()).append(" байт, ").append(att.mimeType).append(")\n")
                                    }
                                    append("Не ищи файл по всему телефону — используй путь выше. Отвечай умно и по делу.\n")
                                } else if (!probe) {
                                    attachmentsCopy.forEach { f ->
                                        append("\n\n[Файл: ").append(f.name).append("]\n")
                                        append(f.contentPreview.take(12000))
                                    }
                                }
                            }
                            if (isImageGenerationRequest(userText)) {
                                val img = generateImageFile(imagePromptFrom(userText))
                                return@withContext ("Готово — вот изображение." to listOf(img))
                            }
                            val answer = callOpenCodeServer(
                                endpoint.ifBlank { "http://127.0.0.1:${engine.port}" },
                                apiKey.ifBlank { engine.password },
                                currentModel.id,
                                fullPrompt,
                                currentModel.remoteProviderId
                            ) { stage ->
                                viewModelScope.launch(Dispatchers.Main) {
                                    val eta = when {
                                        stage.contains("Быстрый", ignoreCase = true) -> 6
                                        stage.contains("инструмент", ignoreCase = true) -> 18
                                        stage.contains("сесс", ignoreCase = true) -> 8
                                        stage.contains("Думает", ignoreCase = true) -> 7
                                        stage.contains("модел", ignoreCase = true) -> 9
                                        stage.contains("ответ", ignoreCase = true) -> 3
                                        stage.contains("изображ", ignoreCase = true) -> 15
                                        else -> null
                                    }
                                    setGenerationStage(stage, eta)
                                }
                            }
                            if (needWorkspace) refreshAgentFolderListing()
                            answer to emptyList<AttachedFile>()
                        }
                    }.fold(
                        onSuccess = { it },
                        onFailure = { cause ->
                            cause.rethrowIfCancelled()
                            val reason = cause.message ?: "не удалось выполнить запрос"
                            val isRealRateLimit = listOf("HTTP 429", "rate limit", "too many requests", "quota exceeded", "usage limit", "limit reached", "free usage")
                                .any { reason.contains(it, ignoreCase = true) }
                            if (isRealRateLimit) {
                                "Лимит бесплатного OpenCode исчерпан. Подключи провайдера, смени модель или подпишись на OpenCode Go.\n\n$reason" to emptyList()
                            } else {
                                "Ошибка OpenCode: $reason" to emptyList()
                            }
                        }
                    )
                } else if (endpoint.isNotBlank() && requestModel.isNotBlank() &&
                    (apiKey.isNotBlank() || (providerId == "custom-cloud" && protocol != "anthropic"))
                ) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (isImageGenerationRequest(text.trim())) {
                                val img = generateImageFile(imagePromptFrom(text.trim()))
                                return@withContext ("Готово — вот изображение." to listOf(img))
                            }
                            val sys = systemInstructionsFor(text.trim(), currentMode.systemHint)
                            val safeAttachments = if (looksLikePrivacyProbe(text.trim())) emptyList() else attachmentsCopy
                            val answer = if (protocol == "anthropic") callAnthropic(endpoint, apiKey, requestModel, text, safeAttachments, sys)
                            else callOpenAiCompatible(endpoint, apiKey, requestModel, text, safeAttachments, sys)
                            answer to emptyList<AttachedFile>()
                        }
                    }.fold(
                        onSuccess = { it },
                        onFailure = { cause ->
                            cause.rethrowIfCancelled()
                            "Ошибка облачного ИИ: ${cause.message ?: "не удалось выполнить запрос"}." to emptyList()
                        }
                    )
                } else if (isImageGenerationRequest(text.trim())) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val img = generateImageFile(imagePromptFrom(text.trim()))
                            "Готово — вот изображение." to listOf(img)
                        }
                    }.fold(
                        onSuccess = { it },
                        onFailure = { cause ->
                            cause.rethrowIfCancelled()
                            "Не удалось сгенерировать изображение: ${cause.message ?: "ошибка сети"}" to emptyList()
                        }
                    )
                } else {
                    ("ИИ не подключён. Выбери модель OpenCode или настрой провайдера.") to emptyList()
                }

                if (!isActive) throw CancellationException("Генерация остановлена")
                generationStatus = "Получен ответ · оформляю результат"
                val shownModel = if (providerPrefs.getString("active_provider", "") == "opencode-server") currentModel.name
                else currentModel.name
                deliverReply(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = reply.first,
                    isUser = false,
                    modelName = shownModel,
                    attachments = reply.second
                ))
            } catch (e: CancellationException) {
                // stopGeneration already handled UI
            } catch (e: Exception) {
                deliverReply(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = "Ошибка: ${e.message ?: e.javaClass.simpleName}",
                    isUser = false,
                    modelName = currentModel.name
                ))
            }
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob !== job) return@invokeOnCompletion
            if (isGenerating) generationStatus = "Готово"
            isGenerating = false
            isReasoningLive = false
            generationEtaJob?.cancel()
            generationEtaSec = null
            EngineService.stop(getApplication())
        }
    }

    fun stopGeneration() {
        if (!isGenerating) return
        val wasRunning = generationJob?.isActive == true
        val job = generationJob
        val sessionId = currentSessionId
        isGenerating = false
        isReasoningLive = false
        generationStatus = "Остановлено"
        generationEtaJob?.cancel()
        generationEtaSec = null
        generationJob = null
        val connection = activeConnection
        val eventConnection = activeEventConnection
        val abort = activeAbort
        job?.cancel()
        if (wasRunning && sessionId != null) {
            deliverReply(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(),
                content = "Генерация остановлена.",
                isUser = false,
                modelName = currentModel.name
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { abort?.invoke() }
            runCatching { connection?.disconnect() }
            runCatching { eventConnection?.disconnect() }
            EngineService.stop(getApplication())
        }
    }

    /** Инструкция для агента: откуда читать/куда писать файлы (как в Margy). */
    private fun agentWorkspaceInstructions(dir: File): String {
        val path = dir.absolutePath
        val listing = runCatching {
            dir.walkTopDown().maxDepth(2)
                .filter { it.isFile && !it.name.startsWith(".") }
                .sortedBy { it.absolutePath }
                .take(50)
                .joinToString("\n") { f ->
                    val rel = f.relativeTo(dir).path
                    val size = when {
                        f.length() < 1024 -> "${f.length()} B"
                        f.length() < 1024 * 1024 -> "${f.length() / 1024} KB"
                        else -> "%.1f MB".format(f.length() / (1024.0 * 1024.0))
                    }
                    "- $rel ($size)"
                }
        }.getOrDefault("")
        return buildString {
            append("Рабочая папка агента: ").append(path).append('\n')
            append("Папка inbox/ — сюда приложение кладёт вложения пользователя. Читай файлы оттуда по точному пути.\n")
            append("Не делай find по /data/user или всему телефону, если путь уже дан.\n")
            if (listing.isNotBlank()) {
                append("Файлы (до 2 уровней):\n").append(listing)
            } else {
                append("Пока пусто (кроме возможных скрытых).")
            }
        }
    }

    private fun Throwable.rethrowIfCancelled() {
        if (this is kotlin.coroutines.cancellation.CancellationException) throw this
    }

    /** Кладёт ответ в тот чат, из которого был запрос, даже если пользователь успел открыть другой. */
    private fun deliverReply(sessionId: String?, msg: ChatMessage) {
        if (sessionId == null || sessionId == currentSessionId) {
            messages.add(msg)
            saveSession(sessionId)
        } else {
            val idx = sessions.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                val s = sessions[idx]
                sessions[idx] = s.copy(lastMessage = msg.content, messages = s.messages + msg)
            }
        }
        persistSessions()
    }

    /** Выход из аккаунта: стираем локальные чаты и настройки провайдеров, чтобы их не увидел следующий пользователь. */

    fun setThemeMode(mode: String) {
        themeMode = mode
        uiPrefs.edit().putString("theme", mode).apply()
    }

    fun setDynamicColor(on: Boolean) {
        dynamicColor = on
        uiPrefs.edit().putBoolean("dynamic", on).apply()
    }

    private val rulesFile: File get() = File(engine.config, "AGENTS.md")
    private val configFile: File get() = File(engine.config, "opencode.json")

    fun loadAgentRules(): String = runCatching {
        if (rulesFile.exists()) rulesFile.readText() else ""
    }.getOrDefault("")

    fun saveAgentRules(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.config.mkdirs()
            rulesFile.writeText(text)
            // подхватит writeOpenCodePrompt + instructions на следующем старте; допишем в youcode.md include
            runCatching {
                val extra = File(engine.home, ".config/youcode/user-rules.md")
                extra.parentFile?.mkdirs()
                extra.writeText(text)
            }
        }
    }

    fun loadOpenCodeConfig(): String = runCatching {
        if (configFile.exists()) configFile.readText() else """{"permission":{"*":"allow"}}"""
    }.getOrDefault("""{"permission":{"*":"allow"}}""")

    fun saveOpenCodeConfig(text: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    if (text.isNotBlank()) {
                        // allow // comments
                        val cleaned = text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
                        if (cleaned.isNotBlank()) JSONObject(cleaned)
                    }
                    engine.config.mkdirs()
                    configFile.writeText(text)
                    null
                }.exceptionOrNull()?.message
            }
            onDone(err)
        }
    }

    fun restartOpenCodeEngine(onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    engine.stop()
                    engineLock.withLock {
                        engineState = "starting"
                        isOpenCodeLoading = true
                        openCodeLoadingLabel = "Перезапуск OpenCode…"
                        engine.start()
                        providerPrefs.edit()
                            .putString("base_opencode-server", "http://127.0.0.1:${engine.port}")
                            .putString("key_opencode-server", engine.password)
                            .apply()
                        engineState = "ready"
                        isOpenCodeLoading = false
                        refreshOpenCodeModels()
                    }
                    null
                }.exceptionOrNull()?.message
            }
            if (err != null) {
                engineState = "error"
                engineError = err
                isOpenCodeLoading = false
            }
            onDone(err)
        }
    }

    fun clearAllChats() {
        sessions.clear()
        messages.clear()
        currentSessionId = null
        pendingAttachments.clear()
        viewModelScope.launch(Dispatchers.IO) {
            persistLock.withLock {
                runCatching { sessionsFile.delete() }
            }
        }
    }


    fun wipeLocalData() {
        generationJob?.cancel()
        // Сессии, ключи провайдеров и файлы OpenCode принадлежат прежнему аккаунту: останавливаем движок и стираем его папки.
        engineManaged = false
        runCatching { EngineService.stop(getApplication<Application>()) }
        runCatching { engine.wipeData() }
        chatsLoaded = false
        sessions.clear()
        messages.clear()
        currentSessionId = null
        runCatching { sessionsFile.delete() }
        providerPrefs.edit().clear().apply()
    }

    // ---- Хранение чатов на диске ----

    private fun messageToJson(m: ChatMessage): JSONObject = JSONObject()
        .put("id", m.id).put("content", m.content).put("isUser", m.isUser).put("ts", m.timestamp)
        .put("model", m.modelName ?: JSONObject.NULL)
        .put("attachments", JSONArray().also { arr ->
            m.attachments.forEach { a ->
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("uri", a.uri?.toString() ?: "")
                    .put("path", a.localPath ?: "").put("mime", a.mimeType).put("size", a.sizeBytes)
                    .put("preview", a.contentPreview).put("saved", a.isSavedByAi))
            }
        })

    private fun messageFromJson(o: JSONObject): ChatMessage {
        val atts = o.optJSONArray("attachments") ?: JSONArray()
        return ChatMessage(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            content = o.optString("content"),
            isUser = o.optBoolean("isUser"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            modelName = if (o.isNull("model")) null else o.optString("model"),
            attachments = (0 until atts.length()).mapNotNull { i ->
                atts.optJSONObject(i)?.let { a ->
                    AttachedFile(
                        id = a.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = a.optString("name"),
                        uri = a.optString("uri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                        localPath = a.optString("path").takeIf { it.isNotBlank() },
                        mimeType = a.optString("mime", "text/plain"),
                        sizeBytes = a.optLong("size"),
                        contentPreview = a.optString("preview"),
                        isSavedByAi = a.optBoolean("saved")
                    )
                }
            }
        )
    }

    private fun persistSessions() {
        if (!chatsLoaded) return
        val snapshot = sessions.toList().take(100)
        viewModelScope.launch(Dispatchers.IO) {
            persistLock.withLock {
                runCatching {
                    val arr = JSONArray()
                    snapshot.forEach { s ->
                        arr.put(JSONObject().put("id", s.id).put("title", s.title).put("last", s.lastMessage).put("ts", s.timestamp)
                            .put("messages", JSONArray().also { ma -> s.messages.forEach { ma.put(messageToJson(it)) } }))
                    }
                    val tmp = File(sessionsFile.path + ".tmp")
                    tmp.writeText(arr.toString())
                    if (!tmp.renameTo(sessionsFile)) { sessionsFile.delete(); tmp.renameTo(sessionsFile) }
                }
            }
        }
    }

    private fun loadSessions() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!sessionsFile.exists()) emptyList<ChatSession>() else {
                        val arr = JSONArray(sessionsFile.readText())
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val ms = o.optJSONArray("messages") ?: JSONArray()
                            ChatSession(
                                id = o.optString("id").ifBlank { return@mapNotNull null },
                                title = o.optString("title"),
                                lastMessage = o.optString("last"),
                                timestamp = o.optLong("ts"),
                                messages = (0 until ms.length()).mapNotNull { j -> ms.optJSONObject(j)?.let { messageFromJson(it) } }
                            )
                        }
                    }
                }.getOrDefault(emptyList<ChatSession>())
            }
            val known = sessions.map { it.id }.toSet()
            sessions.addAll(loaded.filter { it.id !in known })
            chatsLoaded = true
            if (sessions.isNotEmpty()) persistSessions()
        }
    }

    fun newChat() {
        messages.clear()
        pendingAttachments.clear()
        currentSessionId = null
    }

    fun loadSession(session: ChatSession) {
        messages.clear()
        pendingAttachments.clear()
        currentSessionId = session.id
        messages.addAll(session.messages)
    }

    private fun saveSession(sessionId: String?) {
        if (sessionId == null) return
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) return
        sessions[idx] = sessions[idx].copy(
            lastMessage = messages.lastOrNull()?.content ?: sessions[idx].lastMessage,
            messages = messages.toList()
        )
    }


    /**
     * Routes free OpenCode models through the user's own official OpenCode Server.
     * Direct calls to OpenCode's free-tier inference endpoints from third-party apps
     * are rejected by OpenCode; the official server is the supported bridge.
     */

    /**
     * Как в Margy: agent "chat" — без tools, мало токенов, ответ за секунды;
     * "build" — с tools (файлы/shell), на телефоне заметно дольше.
     */

    /** Жёсткая конфиденциальность: не «сдавать» пользователя по истории чата. */
    private val privacySystemBlock = """
Конфиденциальность (обязательные правила):
1) Диалог на этом телефоне — только для владельца. Не раскрывай третьим лицам содержание прошлых сообщений.
2) Если спрашивают вроде «он списывал?», «что он писал?», «покажи историю», «что делал ученик/пользователь» — откажись. Ответь, что это приватный чат и ты не обсуждаешь прошлые запросы с посторонними.
3) Не анализируй историю, чтобы подтвердить или опровергнуть списывание, нарушения, «подозрительное» поведение.
4) Не цитируй и не пересказывай старые сообщения, пока сам пользователь явно не продолжает ту же тему в текущем сообщении.
5) На общие вопросы (привет, учеба, код) отвечай по делу, без намёков на то, что было раньше в чате.
""".trim()

    /** Похоже на чужой допрос по истории, а не на обычный запрос владельца. */
    private fun looksLikePrivacyProbe(text: String): Boolean {
        val t = text.lowercase()
        val keys = listOf(
            "списывал", "списыва", "спишет", "что он писал", "что она писала",
            "покажи историю", "что писал", "что спрашивал", "разоблач",
            "учитель", "препод", "забрал телефон", "это ученик",
            "was he cheating", "did he cheat", "show history", "what did he ask",
            "what was he", "previous messages", "история чата", "перескажи диалог"
        )
        return keys.any { it in t }
    }

    private fun systemInstructionsFor(userText: String, modeHint: String): String {
        val competence = """
Ты умный помощник YouCode. Правила качества ответа:
• Отвечай на языке пользователя, по делу, без воды.
• Если в запросе есть пути к вложениям (inbox/) — сначала читай ИХ, не ищи файл по всему устройству.
• Не пиши «файла нет», пока не проверил указанный путь.
• Не запускай бессмысленные find/ls по /data — это трата времени.
• Код, шаги, факты — конкретно. Если чего-то не можешь — скажи прямо и предложи план.
• Не отказывайся от задачи, если есть tools/сеть/файлы.
""".trim()
        val base = privacySystemBlock + "\n\n" + competence + "\n\n" + modeHint
        return if (looksLikePrivacyProbe(userText)) {
            base + "\n\nСейчас запрос похож на попытку узнать чужую историю чата. Откажись раскрывать прошлые сообщения."
        } else base
    }

/** Оценка «ещё N секунд» от этого момента (обновляется на сменах стадии). */
    private var etaAnchorSec = 10
    private var etaAnchorAtMs = 0L

    private fun setGenerationStage(stage: String, etaHintSec: Int? = null) {
        generationStatus = stage
        if (etaHintSec != null) {
            etaAnchorSec = etaHintSec
            etaAnchorAtMs = System.currentTimeMillis()
            generationEtaSec = etaHintSec
        }
    }

    private fun startGenerationTimer(initialEtaSec: Int) {
        generationStartedAtMs = System.currentTimeMillis()
        etaAnchorSec = initialEtaSec
        etaAnchorAtMs = generationStartedAtMs
        generationElapsedSec = 0
        generationEtaSec = initialEtaSec
        generationEtaJob?.cancel()
        generationEtaJob = viewModelScope.launch {
            while (isActive && isGenerating) {
                val now = System.currentTimeMillis()
                generationElapsedSec = ((now - generationStartedAtMs) / 1000L).toInt()
                val left = etaAnchorSec - ((now - etaAnchorAtMs) / 1000L).toInt()
                generationEtaSec = left.coerceAtLeast(1)
                delay(1000)
            }
        }
    }


    private fun isImageGenerationRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        val keys = listOf(
            "нарисуй", "нарисуйте", "сгенерируй", "сгенерируй картин", "сгенерируй изображ",
            "создай картин", "создай изображ", "создай фото", "generate image", "generate a picture",
            "draw me", "draw a ", "make an image", "картинку", "изображение:"
        )
        return keys.any { it in t }
    }

    private fun imagePromptFrom(text: String): String {
        var p = text.trim()
        listOf(
            Regex("(?i)^(пожалуйста[, ]*)?(нарисуй|нарисуйте|сгенерируй|создай)\\s+(мне\\s+)?(картинку|изображение|фото|image)?\\s*(of|с|про|of a)?\\s*"),
            Regex("(?i)^(generate|draw|create)\\s+(me\\s+)?(an?\\s+)?(image|picture|photo)?\\s*(of)?\\s*")
        ).forEach { p = p.replace(it, "") }
        return p.trim().ifBlank { text.trim() }
    }

    /** Бесплатная генерация картинки (HTTP image API, без ключа). */
    private suspend fun generateImageFile(prompt: String): AttachedFile = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(prompt.take(500), "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&enhance=true"
        onStageSafe("Генерирую изображение…")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120_000
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "YouCode-Android")
        }
        try {
            require(conn.responseCode in 200..299) {
                "Не удалось сгенерировать изображение (HTTP ${conn.responseCode})"
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            require(bytes.isNotEmpty()) { "Пустой ответ генерации изображения" }
            val dir = File(getApplication<Application>().filesDir, "generated").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.png")
            file.writeBytes(bytes)
            AttachedFile(
                id = java.util.UUID.randomUUID().toString(),
                name = file.name,
                uri = android.net.Uri.fromFile(file),
                localPath = file.absolutePath,
                mimeType = "image/png",
                sizeBytes = bytes.size.toLong(),
                contentPreview = "Сгенерированное изображение: $prompt",
                isSavedByAi = true
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun onStageSafe(stage: String) {
        viewModelScope.launch(Dispatchers.Main) {
            setGenerationStage(stage, 12)
        }
    }


    /**
     * Копирует вложения в рабочую папку агента, чтобы OpenCode мог их реально прочитать
     * (иначе uri content:// агенту недоступен).
     */
    private fun materializeAttachments(attachments: List<AttachedFile>, workDir: File): List<Pair<AttachedFile, File>> {
        if (attachments.isEmpty()) return emptyList()
        val inbox = File(workDir, "inbox").apply { mkdirs() }
        val app = getApplication<Application>()
        return attachments.mapNotNull { att ->
            runCatching {
                val safeName = att.name.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]"), "_").ifBlank { "file" }
                val target = File(inbox, "${System.currentTimeMillis()}_$safeName")
                when {
                    !att.localPath.isNullOrBlank() && File(att.localPath).isFile -> {
                        File(att.localPath).copyTo(target, overwrite = true)
                    }
                    att.uri != null -> {
                        app.contentResolver.openInputStream(att.uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("не удалось открыть ${att.name}")
                    }
                    else -> error("нет данных для ${att.name}")
                }
                require(target.length() > 0) { "файл пустой: ${att.name}" }
                att to target
            }.onFailure {
                // пропускаем битое вложение, остальное сохраняем
            }.getOrNull()
        }
    }

    private fun openCodeAgentFor(prompt: String): String {
        val p = prompt.lowercase()
        val needsTools = listOf(
            "файл", "папк", "создай", "напиши код", "проект", "shell", "терминал",
            "прочитай", "сохрани", "отрефактор", "исправь баг", "gradle", "git ",
            "mkdir", "write", "edit", "run ", "запусти", "установ",
            "apk", "собрать", "собери", "сборк", "build", "npm", "bun ", "pip ",
            "скачай", "скачать", "download", "curl", "wget", "http://", "https://",
            "интернет", "сеть", "github", "репозитор", "видео", "video", "youtube",
            "ffmpeg", "mp4", "webm", "sdk", "ndk", "maven", "зависимост",
            "вложен", "inbox/", "объясни", "посмотри", ".jpg", ".png", ".pdf", ".zip"
        ).any { it in p }
        return if (needsTools) "build" else "chat"
    }

    private suspend fun callOpenCodeServer(baseUrl: String, password: String, modelId: String, prompt: String, selectedProviderId: String? = null, onStage: (String) -> Unit = {}): String {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://") || root.startsWith("http://")) {
            "Адрес сервера должен начинаться с http:// или https://"
        }

        fun request(path: String, body: JSONObject? = null, method: String? = null, readTimeoutMs: Int = 180000): JSONObject {
            val connection = (URL("$root/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method ?: if (body == null) "GET" else "POST"
                connectTimeout = 15000
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                if (password.isNotBlank()) {
                    val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $encoded")
                }
            }
            activeConnection = connection
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching {
                        val json = JSONObject(raw)
                        json.optString("message").ifBlank {
                            json.optJSONObject("error")?.optString("message").orEmpty()
                        }.ifBlank { json.optString("name") }
                    }.getOrNull().orEmpty()
                    throw IllegalStateException("HTTP $status${if (detail.isNotBlank()) ": $detail" else ": ${raw.take(600)}"}")
                }
                return if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }

        // Resolve providerID against the live catalogue: model IDs are not globally unique.
        onStage("Проверяю доступные модели OpenCode")
        val catalog = request("api/model")
        val models = catalog.optJSONArray("data") ?: catalog.optJSONArray("models") ?: JSONArray()
        var providerId = selectedProviderId?.takeIf { it.isNotBlank() }
        if (providerId == null) {
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                if (item.optString("id") == modelId) {
                    providerId = item.optString("providerID").takeIf { it.isNotBlank() }
                        ?: item.optJSONObject("provider")?.optString("id")?.takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        require(!providerId.isNullOrBlank()) {
            "Модель $modelId не найдена в каталоге OpenCode. Обнови список моделей и выбери доступную модель."
        }

        // Как в Margy: модель, agent и directory задаём сразу при создании сессии.
        onStage("Создаю сессию с моделью")
        val workDir = agentFolder()
        runCatching { workDir.mkdirs() }
        val dir = workDir.absolutePath
        val modelRef = JSONObject().put("providerID", providerId).put("id", modelId)
        val agentName = openCodeAgentFor(prompt)
        onStage(if (agentName == "chat") "Быстрый чат…" else "Агент с инструментами…")
        val sessionBody = JSONObject()
            .put("title", "YouCode Android")
            .put("model", modelRef)
            .put("agent", agentName)
            .put("location", JSONObject().put("directory", dir))
        val session = request("api/session", sessionBody)
        // Margy: data.id; некоторые версии отдают id на верхнем уровне
        val sessionId = session.optJSONObject("data")?.optString("id").orEmpty()
            .ifBlank { session.optString("id") }
        require(sessionId.isNotBlank()) { "OpenCode создал сессию без ID: ${session.toString().take(400)}" }

        // На всякий случай ещё раз выставим модель (старые/новые API ведут себя по-разному).
        val modelFailure = runCatching {
            request(
                "api/session/${enc(sessionId)}/model",
                JSONObject().put("model", modelRef),
                readTimeoutMs = 20000
            )
        }.exceptionOrNull()
        if (modelFailure != null) {
            val message = modelFailure.message.orEmpty()
            if (!message.startsWith("HTTP 404") && !message.startsWith("HTTP 405")) throw modelFailure
        }
        runCatching {
            request(
                "api/session/${enc(sessionId)}/agent",
                JSONObject().put("agent", agentName),
                readTimeoutMs = 10000
            )
        }

        // Subscribe to OpenCode's real event stream before sending the prompt.
        // This surfaces actual tool calls (shell/read/edit/etc.), not fabricated "thinking" text.
        val abortAction: () -> Unit = {
            runCatching { request("api/session/${enc(sessionId)}/interrupt", JSONObject(), readTimeoutMs = 10000) }
        }
        activeAbort = abortAction
        val eventConnection = AtomicReference<HttpURLConnection?>(null)
        val eventJob = listenOpenCodeEvents(root, password, sessionId, eventConnection)
        try {
            onStage("Отправляю ваш запрос модели")
            var directAnswer: String? = null
            val promptFailure = runCatching {
                request(
                    "api/session/${enc(sessionId)}/prompt",
                    JSONObject().put("text", withThinkingHint(prompt)),
                    readTimeoutMs = 120000
                )
            }.exceptionOrNull()
            if (promptFailure != null) {
                val failure = promptFailure.message.orEmpty()
                // Старые движки знают только POST /session/{id}/message с телом {model, parts}
                // и отвечают готовым сообщением ассистента.
                if (failure.startsWith("HTTP 404") || failure.startsWith("HTTP 405")) {
                    val legacy = request(
                        "api/session/${enc(sessionId)}/message",
                        JSONObject()
                            .put("model", JSONObject().put("providerID", providerId).put("id", modelId))
                            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", withThinkingHint(prompt)))),
                        readTimeoutMs = 30 * 60 * 1000
                    )
                    directAnswer = extractAssistantText(legacy)
                } else {
                    throw promptFailure
                }
            }

            val answer = directAnswer ?: run {
                onStage("Модель думает над ответом")
                // Агентные задачи могут идти долго: ждём до 30 минут, опрашивая историю сессии.
                val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
                var collected = ""
                var unchanged = 0
                var emptyPolls = 0
                while (System.currentTimeMillis() < deadline) {
                    val listing = request(
                        "api/session/${enc(sessionId)}/message?limit=100",
                        readTimeoutMs = 30000
                    )
                    val text = extractAssistantText(listing)
                    assistantError(listing)?.let {
                        throw IllegalStateException("Модель вернула ошибку: $it")
                    }
                    if (text.isNotBlank()) {
                        emptyPolls = 0
                        if (text != collected) {
                            collected = text
                            unchanged = 0
                            onStage("Модель пишет ответ")
                        } else {
                            unchanged++
                        }
                        // time.completed приходит не во всех версиях: тогда считаем ответ
                        // готовым, когда текст перестал меняться примерно на 18 секунд.
                        if (assistantMessagesCompleted(listing) ||
                            (unchanged >= 12 && !assistantMessagesExposeCompletion(listing))
                        ) break
                    } else {
                        // Сообщений ассистента ещё нет: модель могла не запуститься вовсе.
                        if (sessionHasAssistantMessage(listing)) {
                            emptyPolls = 0
                        } else if (++emptyPolls >= 80) {
                            throw IllegalStateException(
                                "Модель не начала отвечать за 2 минуты. Проверь выбранную модель и подключение провайдера в OpenCode."
                            )
                        }
                    }
                    delay(1500)
                }
                onStage("Забираю готовый ответ")
                collected
            }
            return answer.ifBlank {
                throw IllegalStateException("OpenCode не вернул текст ответа. Проверь, что выбранная модель доступна в каталоге.")
            }
        } finally {
            eventJob.cancel()
            runCatching { eventConnection.get()?.disconnect() }
            // Не затираем ссылки, если пользователь уже запустил следующий запрос.
            if (activeAbort === abortAction) activeAbort = null
            // Каждый запрос создаёт отдельную сессию OpenCode: удаляем её, чтобы они не копились в базе.
            runCatching { request("api/session/${enc(sessionId)}", method = "DELETE", readTimeoutMs = 15000) }
        }
    }

    /** Сообщение ассистента: в API встречается и type, и role. */
    private fun isAssistantMessage(message: JSONObject): Boolean {
        val kind = message.optString("type").ifBlank { message.optString("role") }
        return kind == "assistant"
    }

    /** Текст ответа из списка сообщений сессии (как в Margy) или из готового сообщения (legacy). */
    private fun extractAssistantText(payload: JSONObject): String {
        // Legacy POST /message: { parts: [...] } или { data: { parts: [...] } }
        val directParts = payload.optJSONArray("parts")
            ?: payload.optJSONObject("data")?.optJSONArray("parts")
        if (directParts != null) return joinTextParts(directParts)

        val messages = payload.optJSONArray("data") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (!isAssistantMessage(message)) continue
            // content (Margy/OpenCode) или parts
            val parts = message.optJSONArray("content")
                ?: message.optJSONArray("parts")
                ?: continue
            val text = joinTextParts(parts)
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            } else {
                // Иногда текст лежит прямо в message.text
                message.optString("text").takeIf { it.isNotBlank() }?.let {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(it)
                }
            }
        }
        return builder.toString().trim()
    }

    private fun joinTextParts(parts: JSONArray): String = buildList {
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text", "" -> part.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                // reasoning не включаем в финальный ответ пользователю
            }
        }
    }.joinToString("\n").trim()

    private fun sessionHasAssistantMessage(payload: JSONObject): Boolean {
        val messages = payload.optJSONArray("data") ?: return false
        for (i in 0 until messages.length()) {
            if (isAssistantMessage(messages.optJSONObject(i) ?: continue)) return true
        }
        return false
    }

    private fun assistantMessagesCompleted(payload: JSONObject): Boolean {
        val messages = payload.optJSONArray("data") ?: return true
        var sawAssistant = false
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (!isAssistantMessage(message)) continue
            sawAssistant = true
            val completed = message.optJSONObject("time")?.optLong("completed", 0L) ?: 0L
            if (completed <= 0L) return false
        }
        return sawAssistant
    }

    /** true, если сервер вообще сообщает о завершении шага (иначе ждём по «текст перестал меняться»). */
    private fun assistantMessagesExposeCompletion(payload: JSONObject): Boolean {
        val messages = payload.optJSONArray("data") ?: return false
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (!isAssistantMessage(message)) continue
            if (message.optJSONObject("time")?.has("completed") == true) return true
        }
        return false
    }

    /** Ошибка модели в последнем сообщении ассистента (без активного повтора). */
    private fun assistantError(payload: JSONObject): String? {
        val messages = payload.optJSONArray("data") ?: return null
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(i) ?: continue
            if (!isAssistantMessage(message)) continue
            if (message.optJSONObject("retry") != null) return null
            val error = message.optJSONObject("error") ?: return null
            return error.optString("message")
                .ifBlank { error.optString("name") }
                .ifBlank { error.optString("_tag") }
                .ifBlank { "неизвестная ошибка" }
        }
        return null
    }

    private fun listenOpenCodeEvents(root: String, password: String, sessionId: String, connectionRef: AtomicReference<HttpURLConnection?>): Job = viewModelScope.launch(Dispatchers.IO) {
        val connection = (URL("$root/api/event").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 120000
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            if (password.isNotBlank()) {
                val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
        connectionRef.set(connection)
        activeEventConnection = connection
        if (!isActive) { connection.disconnect(); return@launch }
        try {
            if (connection.responseCode !in 200..299) return@launch
            val listenerJob = currentCoroutineContext()[Job]
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var data = StringBuilder()
                lines.forEach { line ->
                    if (listenerJob?.isActive != true) return@useLines
                    if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim()).append(' ')
                    if (line.isBlank() && data.isNotEmpty()) {
                        val raw = data.toString().trim(); data = StringBuilder()
                        runCatching {
                            val envelope = JSONObject(raw)
                            val payload = envelope.optJSONObject("payload")
                            // Новый API шлёт {type, data:{...}}, старый — {payload:{type, properties:{...}}}.
                            val event = payload?.optJSONObject("event") ?: payload ?: envelope
                            val type = event.optString("type")
                            val props = event.optJSONObject("properties")
                                ?: event.optJSONObject("data")
                                ?: JSONObject()
                            val part = props.optJSONObject("part")
                            val eventSession = props.optString("sessionID")
                                .ifBlank { props.optString("sessionId") }
                                .ifBlank { part?.optString("sessionID").orEmpty() }
                            if (eventSession.isNotBlank() && eventSession != sessionId) return@runCatching

                            // Как в Margy: без ответа на permission tools ждут вечно.
                            // В YouCode пока нет UI подтверждений — автоматически разрешаем во время генерации.
                            if (type.startsWith("permission.") || type.contains("permission.request")) {
                                val permId = props.optString("id")
                                    .ifBlank { props.optString("requestID") }
                                    .ifBlank { part?.optString("id").orEmpty() }
                                val sid = eventSession.ifBlank { sessionId }
                                if (permId.isNotBlank() && sid.isNotBlank()) {
                                    runCatching {
                                        val c = (URL("$root/api/session/${java.net.URLEncoder.encode(sid, "UTF-8").replace("+", "%20")}/permission/${java.net.URLEncoder.encode(permId, "UTF-8").replace("+", "%20")}/reply").openConnection() as HttpURLConnection).apply {
                                            requestMethod = "POST"
                                            connectTimeout = 8000
                                            readTimeout = 8000
                                            doOutput = true
                                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                            setRequestProperty("Accept", "application/json")
                                            if (password.isNotBlank()) {
                                                val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                                setRequestProperty("Authorization", "Basic $encoded")
                                            }
                                        }
                                        c.outputStream.use { it.write(JSONObject().put("decision", "allow").toString().toByteArray(Charsets.UTF_8)) }
                                        c.responseCode
                                        c.disconnect()
                                    }
                                    viewModelScope.launch(Dispatchers.Main) {
                                        generationStatus = "Разрешён tool · $permId".take(80)
                                        if (generationActivities.lastOrNull()?.startsWith("permission") != true) {
                                            generationActivities.add("permission · allow · #${permId.take(8)}")
                                        }
                                    }
                                }
                            }

                            // Reasoning stream — как live «Думает…» в Margy
                            if (type.contains("reasoning.delta") || type == "session.reasoning.delta") {
                                val delta = props.optString("delta")
                                    .ifBlank { props.optString("text") }
                                    .ifBlank { part?.optString("text").orEmpty() }
                                if (delta.isNotBlank()) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        isReasoningLive = true
                                        liveReasoning += delta
                                        generationStatus = "Думает…"
                                    }
                                }
                            }
                            if (type.contains("reasoning") && (type.endsWith(".completed") || type.endsWith(".done") || type.endsWith(".end"))) {
                                viewModelScope.launch(Dispatchers.Main) { isReasoningLive = false }
                            }
                            // part.updated с reasoning
                            if (type.contains("part.updated") && part?.optString("type") == "reasoning") {
                                val text = part.optString("text")
                                if (text.isNotBlank()) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        isReasoningLive = true
                                        if (text.length > liveReasoning.length) liveReasoning = text
                                        generationStatus = "Думает…"
                                    }
                                }
                            }

                            val label = when {
                                // Новый API: session.tool.called / .progress / .success / .failed.
                                type.contains("tool.") -> {
                                    val tool = props.optString("tool").ifBlank { props.optString("name") }
                                    val toolId = props.optString("id").ifBlank { props.optString("toolCallID") }
                                    val input = props.optJSONObject("input") ?: JSONObject()
                                    val command = input.optString("command")
                                        .ifBlank { input.optString("filePath") }
                                        .ifBlank { input.optString("path") }
                                        .ifBlank { input.optString("pattern") }
                                        .ifBlank { input.optString("query") }
                                    buildString {
                                        append(tool.ifBlank { "Инструмент" })
                                        if (toolId.isNotBlank()) append(" · #").append(toolId.take(8))
                                        append(" · ")
                                        append(
                                            when {
                                                type.endsWith(".success") || type.endsWith(".completed") -> "завершено"
                                                type.endsWith(".failed") || type.endsWith(".error") -> "ошибка"
                                                else -> "выполняется"
                                            }
                                        )
                                        if (command.isNotBlank()) append(" · ").append(command.take(180))
                                    }
                                }
                                // Старый API: part.updated с part.type == "tool".
                                type.contains("part.updated") && part?.optString("type") == "tool" -> {
                                    val tool = part.optString("tool").ifBlank { part.optString("name") }.ifBlank { "Инструмент" }
                                    val toolId = part.optString("id")
                                    val state = part.optJSONObject("state") ?: JSONObject()
                                    val status = state.optString("status")
                                    val title = state.optString("title").ifBlank { state.optString("description") }
                                    val input = state.optJSONObject("input") ?: JSONObject()
                                    val command = input.optString("command")
                                        .ifBlank { input.optString("filePath") }
                                        .ifBlank { input.optString("path") }
                                        .takeIf { it.isNotBlank() }
                                    buildString {
                                        append(tool)
                                        if (toolId.isNotBlank()) append(" · #").append(toolId.take(8))
                                        if (status.isNotBlank()) append(" · ").append(
                                            when (status) {
                                                "running", "pending" -> "выполняется"
                                                "completed", "success" -> "завершено"
                                                "error", "failed" -> "ошибка"
                                                else -> status
                                            }
                                        )
                                        (title.takeIf { it.isNotBlank() } ?: command)?.let { append(" · ").append(it.take(180)) }
                                    }
                                }
                                type == "session.execution.failed" -> "Ошибка выполнения: " +
                                    props.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "неизвестная ошибка" }
                                type.contains("execution.started") -> "Агент начал работу"
                                type.contains("execution.completed") || type.contains("execution.finished") -> "Агент закончил"
                                else -> null
                            }
                            if (label != null) {
                                val toolKey = part?.optString("id")?.takeIf { it.isNotBlank() }
                                    ?: props.optString("id").takeIf { it.isNotBlank() }
                                    ?: label.substringBefore(" · ").take(32)
                                viewModelScope.launch(Dispatchers.Main) {
                                    // Как в Margy: обновляем тот же tool in-place, не плодим десятки строк
                                    val shortId = toolKey.takeLast(8)
                                    val idx = generationActivities.indexOfFirst {
                                        it.contains("#$shortId") || (shortId.length >= 6 && it.contains(shortId))
                                    }
                                    if (idx >= 0) generationActivities[idx] = label
                                    else {
                                        // убираем старые «выполняется», оставляем последние завершённые
                                        generationActivities.removeAll { it.contains("выполняется") }
                                        generationActivities.add(label)
                                    }
                                    while (generationActivities.size > 3) generationActivities.removeAt(0)
                                    generationStatus = when {
                                        label.contains("ошибка") -> label.take(90)
                                        label.contains("завершено") -> "Готово: ${label.substringBefore(" · ").take(40)}"
                                        label.contains("выполняется") -> "Сейчас: ${label.substringBefore(" · ").take(40)}"
                                        else -> label.take(80)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Event streaming is optional; the ordinary message response remains authoritative.
        } finally {
            if (activeEventConnection === connection) activeEventConnection = null
            connection.disconnect()
        }
    }

    private fun callAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String
    ): String {
        require(apiKey.isNotBlank()) { "Для Claude API нужен API-ключ Anthropic" }
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Адрес должен начинаться с https:// или http://"
        }
        val endpoint = when {
            normalized.endsWith("/v1/messages") -> normalized
            normalized.endsWith("/v1") -> "$normalized/messages"
            else -> "$normalized/v1/messages"
        }
        val userContent = buildString {
            // История диалога нужна и сторонним провайдерам: иначе модель помнит только последнюю реплику.
            val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
            }
            if (history.isNotBlank() && !looksLikePrivacyProbe(prompt)) append("История диалога:\n").append(history).append("\n\n")
            thinkingHint().takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                append(file.contentPreview.takeIf { it.isNotBlank() }?.take(20000) ?: "Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) {
                append("\n\nРабочая папка: ${agentFolder().absolutePath}")
                append("\nИмя: $workingFolderName")
                append("\nФайлы: ${workingFolderFiles.take(30).joinToString()}")
                append("\n(Облачный API не имеет прямого доступа к файлам телефона — при необходимости пользователь прикрепляет содержимое.)")
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", systemHint)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userContent)))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            activeConnection = connection
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val blocks = JSONObject(response).optJSONArray("content") ?: JSONArray()
            val answer = (0 until blocks.length()).mapNotNull { i ->
                blocks.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.filter { it.isNotBlank() }.joinToString("\n")
            return answer.ifBlank { throw IllegalStateException("Claude API вернул пустой ответ") }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String
    ): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Адрес должен начинаться с https:// или http://"
        }
        val endpoint = when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
        val userContent = buildString {
            val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
            }
            if (history.isNotBlank() && !looksLikePrivacyProbe(prompt)) append("История диалога:\n").append(history).append("\n\n")
            thinkingHint().takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                if (file.contentPreview.isNotBlank()) append(file.contentPreview.take(20000))
                else append("Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) {
                append("\n\nРабочая папка: ${agentFolder().absolutePath}")
                append("\nИмя: $workingFolderName")
                append("\nФайлы: ${workingFolderFiles.take(30).joinToString()}")
                append("\n(Облачный API не имеет прямого доступа к файлам телефона — при необходимости пользователь прикрепляет содержимое.)")
            }
        }
        val messagesJson = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemHint))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject().put("model", model).put("messages", messagesJson).put("stream", false)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "YouCode-Android/1.0")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            activeConnection = connection
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val json = JSONObject(response)
            val content = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content")
            val answer = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
                else -> ""
            }
            return answer.ifBlank { throw IllegalStateException("Провайдер вернул пустой ответ") }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

}
