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
import com.opencode.ai.data.ReasoningLevel
import com.opencode.ai.data.TraceStep
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
import java.util.UUID
import android.util.Base64

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val providerPrefs = application.getSharedPreferences("providers", android.content.Context.MODE_PRIVATE)

    var currentModel by mutableStateOf(SampleData.freeModels.first())
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

    /** true, если движком управляет само приложение (нет внешнего сервера и есть бинарник). */
    private var engineManaged = false
    private val engineLock = kotlinx.coroutines.sync.Mutex()

    init {
        startEmbeddedEngine()
    }

    /**
     * У каждой установки свой движок: свой процесс, свой пароль и свои данные в закрытой папке приложения.
     * Ничего не общее между людьми, и никакого центрального сервера нет.
     */
    private fun startEmbeddedEngine() {
        if (!engine.available) { engineState = "missing"; return }
        val saved = providerPrefs.getString("base_opencode-server", "").orEmpty()
        // Если пользователь подключил свой внешний сервер, его не трогаем.
        if (saved.isNotBlank() && !saved.contains("127.0.0.1")) { engineState = "external"; return }
        engineManaged = true
        viewModelScope.launch { runEngine() }
    }

    /** Запускает движок, если он не работает (первый старт и перезапуск после того, как система его убила). */
    private suspend fun runEngine() {
        engineLock.withLock {
            if (engine.running) return
            engineState = "starting"
            runCatching { engine.start() }
                .onSuccess {
                    providerPrefs.edit()
                        .putString("base_opencode-server", "http://127.0.0.1:${engine.port}")
                        .putString("key_opencode-server", engine.password)
                        .putString("active_provider", "opencode-server")
                        .apply()
                    engineState = "ready"
                    engineError = null
                    refreshOpenCodeModels()
                }
                .onFailure {
                    engineState = "error"
                    engineError = it.message
                }
        }
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }

    /** Loads the live model catalogue from OpenCode's /api/model. */
    fun refreshOpenCodeModels(attempt: Int = 0) {
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
                                if (item.has("enabled") && !item.optBoolean("enabled", true)) continue
                                val providerId = item.optString("providerID")
                                    .ifBlank { item.optJSONObject("provider")?.optString("id").orEmpty() }
                                if (providerId.isBlank()) continue
                                val providerName = item.optJSONObject("provider")?.optString("name")
                                    ?.takeIf { it.isNotBlank() } ?: if (providerId == "opencode") "OpenCode Zen" else providerId
                                val costs = item.optJSONArray("cost")
                                val paid = costs != null && (0 until costs.length()).any {
                                    (costs.optJSONObject(it)?.optDouble("input", 0.0) ?: 0.0) > 0.0
                                }
                                val limit = item.optJSONObject("limit")
                                val context = limit?.optLong("context")?.takeIf { it > 0 }?.let {
                                    if (it >= 1000) "${it / 1000}k" else it.toString()
                                } ?: "OpenCode"
                                add(AiModel(
                                    id = id,
                                    name = item.optString("name").ifBlank { id },
                                    contextSize = context,
                                    isFree = providerId == "opencode" && !paid,
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
            }.onSuccess {
                openCodeCatalogError = null
                if (openCodeModels.isEmpty() && attempt < 6) {
                    // Сразу после старта движок ещё подтягивает каталог моделей: пробуем снова.
                    delay(2500)
                    refreshOpenCodeModels(attempt + 1)
                    return@onSuccess
                }
                val inCatalog = openCodeModels.any { it.id == currentModel.id && it.remoteProviderId == currentModel.remoteProviderId }
                if ((currentModel.remoteProviderId == null || !inCatalog) &&
                    providerPrefs.getString("active_provider", "") == "opencode-server"
                ) {
                    (openCodeModels.firstOrNull { it.isFree } ?: openCodeModels.firstOrNull())?.let { selectModel(it) }
                }
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

    /** Реальные события инструментов OpenCode (не скрытые рассуждения модели). */
    val generationActivities = mutableStateListOf<String>()

    var searchQuery by mutableStateOf("")
        private set

    var sessions = mutableStateListOf<ChatSession>()
        private set

    private var currentSessionId: String? = null

    // Pending attachments before send
    var pendingAttachments = mutableStateListOf<AttachedFile>()
        private set

    /** Ход работы текущего ответа: размышления и инструменты в порядке появления. */
    var liveTrace by mutableStateOf<List<TraceStep>>(emptyList())
        private set
    private val traceBuffer = LinkedHashMap<String, TraceStep>()
    private val traceLock = Any()

    private fun resetTrace() {
        synchronized(traceLock) { traceBuffer.clear() }
        liveTrace = emptyList()
    }

    private fun upsertTrace(step: TraceStep) {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(traceLock) {
            val old = traceBuffer[step.id]
            val finished = step.status != "running"
            traceBuffer[step.id] = step.copy(
                startedAt = step.startedAt.takeIf { it > 0 } ?: old?.startedAt ?: now,
                endedAt = step.endedAt ?: if (finished) (old?.endedAt ?: now) else null,
                text = step.text.ifBlank { old?.text.orEmpty() }
            )
            traceBuffer.values.toList()
        }
        viewModelScope.launch(Dispatchers.Main) { liveTrace = snapshot }
    }

    /** Разбирает part OpenCode (reasoning / tool) в шаг хода работы. */
    private fun tracePartToStep(part: JSONObject): TraceStep? {
        val type = part.optString("type")
        val id = part.optString("id").ifBlank { part.optString("callID") }
        if (id.isBlank()) return null
        val time = part.optJSONObject("time")
        val start = time?.optLong("start", 0L) ?: 0L
        val end = (time?.optLong("end", 0L) ?: 0L).takeIf { it > 0 }
            ?: (time?.optLong("completed", 0L) ?: 0L).takeIf { it > 0 }
        return when (type) {
            "reasoning" -> TraceStep(
                id = id, kind = "thinking", title = "Думал",
                text = part.optString("text"),
                status = if (end != null) "done" else "running",
                startedAt = start, endedAt = end
            )
            "tool" -> {
                val state = part.optJSONObject("state") ?: JSONObject()
                val input = state.optJSONObject("input")
                val tool = part.optString("tool").ifBlank { "Инструмент" }
                val detail = state.optString("title").ifBlank { state.optString("description") }
                    .ifBlank { input?.optString("command").orEmpty() }
                    .ifBlank { input?.optString("filePath").orEmpty() }
                    .ifBlank { input?.optString("pattern").orEmpty() }
                val stateTime = state.optJSONObject("time")
                val toolStart = stateTime?.optLong("start", 0L) ?: 0L
                val toolEnd = (stateTime?.optLong("end", 0L) ?: 0L).takeIf { it > 0 }
                val status = when (state.optString("status")) {
                    "completed" -> "done"
                    "error" -> "error"
                    else -> "running"
                }
                val body = state.optString("output").ifBlank { state.optString("error") }
                    .ifBlank { input?.toString(2).orEmpty() }
                TraceStep(
                    id = id, kind = "tool",
                    title = if (detail.isNotBlank()) "$tool: ${detail.take(200)}" else tool,
                    text = body.take(6000),
                    status = status, startedAt = toolStart, endedAt = toolEnd
                )
            }
            else -> null
        }
    }

    var reasoningLevel by mutableStateOf(
        runCatching { ReasoningLevel.valueOf(providerPrefs.getString("reasoning_level", "AUTO").orEmpty()) }
            .getOrDefault(ReasoningLevel.AUTO)
    )

    fun selectReasoning(level: ReasoningLevel) {
        reasoningLevel = level
        providerPrefs.edit().putString("reasoning_level", level.name).apply()
    }

    var workingFolderUri by mutableStateOf<android.net.Uri?>(null)
        private set
    var workingFolderName by mutableStateOf<String?>(null)
        private set
    var workingFolderFiles = mutableStateListOf<String>()
        private set

    val filteredModels: List<AiModel>
        get() {
            val all = if (openCodeModels.isNotEmpty()) openCodeModels.sortedByDescending { it.isFree } else SampleData.freeModels
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
        when {
            model.remoteProviderId != null -> providerPrefs.edit().putString("active_provider", "opencode-server").apply()
        }
    }

    fun addAttachment(file: AttachedFile) {
        if (pendingAttachments.none { it.id == file.id }) {
            pendingAttachments.add(file)
        }
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

    fun sendMessage(text: String) {
        if ((text.isBlank() && pendingAttachments.isEmpty()) || isGenerating) return

        val attachmentsCopy = pendingAttachments.toList()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text.trim().ifBlank { "📎 Файл(ы) прикреплены" },
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
                lastMessage = text.trim(),
                timestamp = System.currentTimeMillis()
            )
            sessions.add(0, session)
            currentSessionId = session.id
        }
        val sessionId = currentSessionId

        isGenerating = true
        generationActivities.clear()
        resetTrace()
        generationStatus = "Подключаюсь к модели"
        EngineService.start(getApplication<Application>())
        viewModelScope.launch {
            // Если система убила встроенный движок в фоне, поднимаем его заново.
            if (engineManaged && !engine.running) runEngine()
            val providerId = providerPrefs.getString("active_provider", "").orEmpty()
            val endpoint = providerPrefs.getString("base_${providerId}", "").orEmpty().trim()
            val apiKey = providerPrefs.getString("key_${providerId}", "").orEmpty().trim()
            val remoteModel = providerPrefs.getString("model_${providerId}", "").orEmpty().trim()
            val requestModel = remoteModel
            val protocol = providerPrefs.getString("protocol_${providerId}", "openai").orEmpty()
            generationStatus = if (providerId == "opencode-server") "Подключаюсь к OpenCode" else "Отправляю запрос провайдеру"
            val reply = if (providerId == "opencode-server" && endpoint.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                            (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
                        }
                        val fullPrompt = buildString {
                            append("Системные инструкции: ").append(currentMode.systemHint)
                            if (history.isNotBlank()) append("\nИстория диалога:\n").append(history)
                            append("\nПользователь: ").append(text)
                            attachmentsCopy.forEach { f -> append("\n\n[Файл: ").append(f.name).append("]\n").append(f.contentPreview.take(12000)) }
                        }
                        callOpenCodeServer(endpoint, apiKey, currentModel.id, fullPrompt, currentModel.remoteProviderId, reasoningLevel) { stage ->
                            viewModelScope.launch(Dispatchers.Main) { generationStatus = stage }
                        }
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = {
                        val reason = it.message ?: "не удалось выполнить запрос"
                        val isRealRateLimit = reason.contains("HTTP 429", ignoreCase = true) ||
                            reason.contains("rate limit", ignoreCase = true) ||
                            reason.contains("too many requests", ignoreCase = true) ||
                            reason.contains("quota exceeded", ignoreCase = true)
                        if (isRealRateLimit) {
                            "Провайдер действительно отклонил запрос из-за ограничения частоты или квоты. Подробности: $reason" to emptyList()
                        } else {
                            "Ошибка OpenCode (это не обязательно лимит): $reason" to emptyList()
                        }
                    }
                )
            } else if (endpoint.isNotBlank() && requestModel.isNotBlank() && apiKey.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (protocol == "anthropic") callAnthropic(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint, reasoningLevel)
                        else callOpenAiCompatible(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint, reasoningLevel)
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { "Ошибка облачного ИИ: ${it.message ?: "не удалось выполнить запрос"}. Проверь Base URL, API-ключ и доступность модели." to emptyList() }
                )
            } else {
                ("ИИ не подключён. Открой «Провайдеры» и подключи OpenCode Server или укажи Base URL, API-ключ и ID модели для другого провайдера. Демонстрационные ответы отключены.") to emptyList()
            }
            generationStatus = "Получен ответ · оформляю результат"
            val shownModel = if (providerId == "opencode-server") currentModel.name else if (remoteModel.isNotBlank()) remoteModel else currentModel.name
            val finishedTrace = synchronized(traceLock) {
                val now = System.currentTimeMillis()
                traceBuffer.values.map {
                    if (it.isRunning) it.copy(status = "done", endedAt = it.endedAt ?: now) else it
                }
            }
            messages.add(ChatMessage(
                id = UUID.randomUUID().toString(), content = reply.first, isUser = false,
                modelName = shownModel, attachments = reply.second, trace = finishedTrace
            ))
            liveTrace = emptyList()
            isGenerating = false
            generationStatus = "Готово"
            EngineService.stop(getApplication<Application>())
            saveSession(sessionId)
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
    private fun callOpenCodeServer(baseUrl: String, password: String, modelId: String, prompt: String, selectedProviderId: String? = null, reasoning: ReasoningLevel = ReasoningLevel.AUTO, onStage: (String) -> Unit = {}): String {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://") || root.startsWith("http://")) {
            "Адрес сервера должен начинаться с http:// или https://"
        }

        fun request(path: String, body: JSONObject? = null): JSONObject {
            val connection = (URL("$root/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = 15000
                readTimeout = 180000
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
                connection.disconnect()
            }
        }

        // Resolve providerID against the live catalogue: model IDs are not globally unique.
        onStage("Проверяю каталог моделей")
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

        // OpenCode 2.x: модель задаётся при создании сессии, сообщение отправляется на /prompt
        // (он только ставит запрос в очередь), а ответ берём из списка сообщений сессии.
        onStage("Создаю сессию")
        val modelRef = JSONObject().put("providerID", providerId).put("id", modelId)
        val session = request("api/session", JSONObject().put("title", "YouCode Android").put("model", modelRef))
        val sessionId = session.optJSONObject("data")?.optString("id").orEmpty()
            .ifBlank { session.optString("id") }
        require(sessionId.isNotBlank()) { "OpenCode создал сессию без ID: ${session.toString().take(400)}" }

        val eventJob = listenOpenCodeEvents(root, password, sessionId)
        try {
            onStage("Модель обрабатывает запрос")
            request("api/session/${enc(sessionId)}/prompt", JSONObject().put("text", prompt).apply {
                reasoning.variant?.let { put("variant", it) }
            })

            onStage("Получаю результат модели")
            val deadline = System.currentTimeMillis() + 170_000L
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
                val list = request("api/session/${enc(sessionId)}/message?order=desc&limit=10")
                val items = list.optJSONArray("data") ?: JSONArray()
                // Ход работы: идём от старых сообщений к новым, чтобы порядок шагов сохранялся.
                for (i in items.length() - 1 downTo 0) {
                    val msg = items.optJSONObject(i) ?: continue
                    if (msg.optString("type") != "assistant") continue
                    val parts = msg.optJSONArray("content") ?: continue
                    for (j in 0 until parts.length()) {
                        parts.optJSONObject(j)?.let { tracePartToStep(it) }?.let(::upsertTrace)
                    }
                }
                for (i in 0 until items.length()) {
                    val msg = items.optJSONObject(i) ?: continue
                    if (msg.optString("type") != "assistant") continue
                    val err = msg.optJSONObject("error")
                    if (err != null) {
                        val errorText: String = err.optString("message").ifBlank { err.toString().take(400) }
                        throw IllegalStateException(errorText)
                    }
                    val finish = msg.optString("finish")
                    val done = msg.optJSONObject("time")?.has("completed") == true
                    if (!done || finish == "tool-calls") break
                    val content = msg.optJSONArray("content") ?: JSONArray()
                    val answer = buildList {
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "text") {
                                part.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }.joinToString("\n").trim()
                    if (answer.isNotBlank()) return answer
                    break
                }
            }
            throw IllegalStateException("OpenCode не вернул ответ за 3 минуты. Проверь, что модель доступна и ключ провайдера задан.")
        } finally {
            eventJob.cancel()
        }
    }

    private fun listenOpenCodeEvents(root: String, password: String, sessionId: String): Job = viewModelScope.launch(Dispatchers.IO) {
        val connection = (URL("$root/api/event").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 5000
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            if (password.isNotBlank()) {
                val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
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
                            val event = envelope.optJSONObject("payload") ?: envelope
                            val type = event.optString("type")
                            val props = event.optJSONObject("properties") ?: JSONObject()
                            val part = props.optJSONObject("part")
                            val eventSession = props.optString("sessionID")
                                .ifBlank { props.optString("sessionId") }
                                .ifBlank { part?.optString("sessionID").orEmpty() }
                            if (eventSession.isNotBlank() && eventSession != sessionId) return@runCatching
                            if (type.contains("part.updated") && part != null) {
                                tracePartToStep(part)?.let(::upsertTrace)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Event streaming is optional; the ordinary message response remains authoritative.
        } finally { connection.disconnect() }
    }

    private fun callAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String,
        reasoning: ReasoningLevel = ReasoningLevel.AUTO
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
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                append(file.contentPreview.takeIf { it.isNotBlank() }?.take(20000) ?: "Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) append("\n\nРабочая папка: $workingFolderName. Файлы: ${workingFolderFiles.take(30).joinToString()}")
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", if (reasoning.thinkingBudget > 0) reasoning.thinkingBudget + 4096 else 4096)
            .put("system", systemHint)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userContent)))
        if (reasoning.thinkingBudget > 0) {
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", reasoning.thinkingBudget))
        }
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
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val blocks = JSONObject(response).optJSONArray("content") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") != "thinking") continue
                val thought = block.optString("thinking").trim()
                if (thought.isNotBlank()) {
                    upsertTrace(TraceStep(id = "think-$i", kind = "thinking", title = "Думал", text = thought, status = "done"))
                }
            }
            val answer = (0 until blocks.length()).mapNotNull { i ->
                blocks.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.filter { it.isNotBlank() }.joinToString("\n")
            return answer.ifBlank { throw IllegalStateException("Claude API вернул пустой ответ") }
        } finally {
            connection.disconnect()
        }
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String,
        reasoning: ReasoningLevel = ReasoningLevel.AUTO
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
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                if (file.contentPreview.isNotBlank()) append(file.contentPreview.take(20000))
                else append("Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) {
                append("\n\nРабочая папка: $workingFolderName. Файлы: ${workingFolderFiles.take(30).joinToString()}")
            }
        }
        val messagesJson = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemHint))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject().put("model", model).put("messages", messagesJson).put("stream", false)
        reasoning.openAiEffort?.let { body.put("reasoning_effort", it) }
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
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val json = JSONObject(response)
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val thought = message?.optString("reasoning_content").orEmpty()
                .ifBlank { message?.optString("reasoning").orEmpty() }.trim()
            if (thought.isNotBlank()) {
                upsertTrace(TraceStep(id = "think-0", kind = "thinking", title = "Думал", text = thought, status = "done"))
            }
            val content = message?.opt("content")
            val answer = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
                else -> ""
            }
            return answer.ifBlank { throw IllegalStateException("Провайдер вернул пустой ответ") }
        } finally {
            connection.disconnect()
        }
    }

}
