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
import com.opencode.ai.data.MsgPart
import com.opencode.ai.data.OcAsk
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
                    refreshOpenCodeModels()
                }
                .onFailure {
                    engineState = "error"
                    engineError = it.message
                }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        engine.stop()
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

    private class DeltaPiece(val message: String, val reasoning: Boolean, val ordinal: Int, val text: String)

    /** Что хранит opencode о текущем ходе (инструменты, готовые части) — обновляется опросом. */
    private var liveTurn by mutableStateOf<List<AssistantMsg>>(emptyList())

    /** Что модель печатает и о чём думает прямо сейчас — приходит потоком событий. */
    private var deltaPieces by mutableStateOf<List<DeltaPiece>>(emptyList())

    /** Инструменты, ждущие разрешения пользователя. */
    var asks by mutableStateOf<List<OcAsk>>(emptyList())
        private set

    var searchQuery by mutableStateOf("")
        private set

    var sessions = mutableStateListOf<ChatSession>()
        private set

    private var currentSessionId: String? = null

    init { loadSessions() }

    // Pending attachments before send
    var pendingAttachments = mutableStateListOf<AttachedFile>()
        private set

    var workingFolderUri by mutableStateOf<android.net.Uri?>(null)
        private set
    var workingFolderName by mutableStateOf<String?>(null)
        private set
    var workingFolderFiles = mutableStateListOf<String>()
        private set

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
                timestamp = System.currentTimeMillis(),
                messages = messages.toList()
            )
            sessions.add(0, session)
            currentSessionId = session.id
        }
        val sessionId = currentSessionId
        saveSession(sessionId)
        persistSessions()

        isGenerating = true
        generationActivities.clear()
        clearLive()
        generationStatus = "Подключаюсь к модели"
        EngineService.start(getApplication<Application>())
        val job = viewModelScope.launch {
            // Если система убила встроенный движок в фоне, поднимаем его заново.
            if (engineManaged && !engine.running) runEngine()
            val providerId = providerPrefs.getString("active_provider", "").orEmpty()
            val endpoint = providerPrefs.getString("base_${providerId}", "").orEmpty().trim()
            val apiKey = providerPrefs.getString("key_${providerId}", "").orEmpty().trim()
            val remoteModel = providerPrefs.getString("model_${providerId}", "").orEmpty().trim()
            val requestModel = remoteModel
            val protocol = providerPrefs.getString("protocol_${providerId}", "openai").orEmpty()
            generationStatus = if (providerId == "opencode-server") "Подключаюсь к OpenCode" else "Отправляю запрос провайдеру"
            var replyTurn: OcTurn? = null
            val reply = if (providerId == "opencode-server" && endpoint.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        require(currentModel.remoteProviderId != null) {
                            "модель не выбрана: каталог OpenCode ещё не загрузился (состояние движка: ${engineState.ifBlank { "не запущен" }}). ${engineError.orEmpty()}".trim()
                        }
                        val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                            (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
                        }
                        val existingOcSession = sessions.firstOrNull { it.id == sessionId }?.ocSessionId
                        val result = callOpenCodeServer(
                            endpoint, apiKey, currentModel.id, existingOcSession,
                            buildPrompt = { isNewSession ->
                                buildString {
                                    // В живой сессии контекст уже у opencode: повторяем инструкции и историю только в новой.
                                    if (isNewSession) {
                                        append("Системные инструкции: ").append(currentMode.systemHint)
                                        if (history.isNotBlank()) append("\nИстория диалога:\n").append(history)
                                        append("\nПользователь: ")
                                    }
                                    append(text.trim())
                                    attachmentsCopy.forEach { f -> append("\n\n[Файл: ").append(f.name).append("]\n").append(f.contentPreview.take(12000)) }
                                }
                            },
                            selectedProviderId = currentModel.remoteProviderId,
                            directory = if (engineManaged) engine.workspacePath else null,
                            onSession = { id ->
                                viewModelScope.launch(Dispatchers.Main) {
                                    val i = sessions.indexOfFirst { it.id == sessionId }
                                    if (i >= 0 && sessions[i].ocSessionId != id) sessions[i] = sessions[i].copy(ocSessionId = id)
                                    persistSessions()
                                }
                            },
                            onStage = { stage -> viewModelScope.launch(Dispatchers.Main) { generationStatus = stage } }
                        )
                        replyTurn = result.turn
                        result.text
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { cause ->
                        // Отмена по кнопке «стоп» — не ошибка: не подменяем её текстом про лимиты.
                        cause.rethrowIfCancelled()
                        val reason = cause.message ?: "не удалось выполнить запрос"
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
            } else if (endpoint.isNotBlank() && requestModel.isNotBlank() &&
                (apiKey.isNotBlank() || (providerId == "custom-cloud" && protocol != "anthropic"))
            ) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (protocol == "anthropic") callAnthropic(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint)
                        else callOpenAiCompatible(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint)
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { cause ->
                        cause.rethrowIfCancelled()
                        "Ошибка облачного ИИ: ${cause.message ?: "не удалось выполнить запрос"}. Проверь Base URL, API-ключ и доступность модели." to emptyList()
                    }
                )
            } else {
                ("ИИ не подключён. Открой «Провайдеры» и подключи OpenCode Server или укажи Base URL, API-ключ и ID модели для другого провайдера. Демонстрационные ответы отключены.") to emptyList()
            }
            val shownModel = if (providerId == "opencode-server") currentModel.name else if (remoteModel.isNotBlank()) remoteModel else currentModel.name
            // Отмена во время запроса обрывает корутину раньше, чем мы сюда дойдём.
            // Здесь страховка от отмены в неудачный момент: не показываем оборванный ответ.
            if (!isActive) throw CancellationException("Генерация остановлена")
            generationStatus = "Получен ответ · оформляю результат"
            val turn = replyTurn
            clearLive()
            deliverReply(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(), content = reply.first, isUser = false,
                modelName = shownModel, attachments = reply.second,
                parts = turn?.parts.orEmpty(), error = turn?.error,
                tokensIn = turn?.tokensIn ?: 0, tokensOut = turn?.tokensOut ?: 0
            ))
        }
        generationJob = job
        job.invokeOnCompletion {
            // Задача могла быть остановлена кнопкой и заменена новой: чужой финал не трогаем,
            // иначе он погасил бы индикатор и сервис уже следующего запроса.
            if (generationJob !== job) return@invokeOnCompletion
            // Выполняется при любом исходе (успех, ошибка, отмена). «Остановлено» не перетираем.
            if (isGenerating) generationStatus = "Готово"
            isGenerating = false
            EngineService.stop(getApplication<Application>())
        }
    }

    /** Останавливает текущую генерацию: рвёт соединение и просит OpenCode прервать сессию. */
    fun stopGeneration() {
        if (!isGenerating) return
        // Состояние меняем сразу: кнопка «стоп» должна срабатывать мгновенно,
        // даже если заблокированное чтение сокета закроется чуть позже.
        val wasRunning = generationJob?.isActive == true
        val job = generationJob
        val sessionId = currentSessionId
        // То, что модель успела сделать, остаётся в чате.
        val partial = liveShown()
        isGenerating = false
        generationStatus = "Остановлено"
        generationJob = null
        val connection = activeConnection
        val eventConnection = activeEventConnection
        val abort = activeAbort
        job?.cancel()
        if (wasRunning && sessionId != null) {
            val said = partial.filterIsInstance<MsgPart.Text>().joinToString("\n\n") { it.text.trim() }.trim()
            deliverReply(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(),
                content = said.ifBlank { if (partial.isEmpty()) "Генерация остановлена." else "" },
                isUser = false,
                modelName = currentModel.name,
                parts = partial,
                error = if (partial.isNotEmpty()) "Генерация остановлена." else null
            ))
        }
        clearLive()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { abort?.invoke() }
            runCatching { connection?.disconnect() }
            runCatching { eventConnection?.disconnect() }
            EngineService.stop(getApplication<Application>())
        }
    }

    /** Отмена корутины — не ошибка провайдера: её нужно пробрасывать, а не показывать как сбой. */
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
    fun wipeLocalData() {
        generationJob?.cancel()
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
        .put("error", m.error ?: JSONObject.NULL)
        .put("tin", m.tokensIn).put("tout", m.tokensOut)
        .put("parts", JSONArray().also { arr -> m.parts.forEach { arr.put(partToJson(it)) } })
        .put("attachments", JSONArray().also { arr ->
            m.attachments.forEach { a ->
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("uri", a.uri?.toString() ?: "")
                    .put("mime", a.mimeType).put("size", a.sizeBytes).put("preview", a.contentPreview).put("saved", a.isSavedByAi))
            }
        })

    private fun partToJson(p: MsgPart): JSONObject = when (p) {
        is MsgPart.Text -> JSONObject().put("t", "text").put("text", p.text)
        is MsgPart.Reasoning -> JSONObject().put("t", "reasoning").put("text", p.text).put("ms", p.ms)
        is MsgPart.Tool -> JSONObject().put("t", "tool").put("name", p.name).put("status", p.status)
            .put("arg", p.arg ?: JSONObject.NULL).put("input", p.input).put("output", p.output.take(6000))
            .put("error", p.error ?: JSONObject.NULL)
    }

    private fun partFromJson(o: JSONObject): MsgPart? = when (o.optString("t")) {
        "text" -> MsgPart.Text(o.optString("text"))
        "reasoning" -> MsgPart.Reasoning(o.optString("text"), o.optLong("ms"))
        "tool" -> MsgPart.Tool(
            o.optString("name"), o.optString("status", "completed"), o.str("arg"),
            o.optString("input"), o.optString("output"), o.str("error")
        )
        else -> null
    }

    private fun messageFromJson(o: JSONObject): ChatMessage {
        val atts = o.optJSONArray("attachments") ?: JSONArray()
        return ChatMessage(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            content = o.optString("content"),
            isUser = o.optBoolean("isUser"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            modelName = if (o.isNull("model")) null else o.optString("model"),
            parts = o.optJSONArray("parts").objects().mapNotNull { partFromJson(it) },
            error = o.str("error"),
            tokensIn = o.optLong("tin"),
            tokensOut = o.optLong("tout"),
            attachments = (0 until atts.length()).mapNotNull { i ->
                atts.optJSONObject(i)?.let { a ->
                    AttachedFile(
                        id = a.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = a.optString("name"),
                        uri = a.optString("uri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
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
                        arr.put(JSONObject().put("id", s.id).put("title", s.title).put("last", s.lastMessage).put("ts", s.timestamp).put("oc", s.ocSessionId ?: JSONObject.NULL)
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
                                ocSessionId = o.str("oc"),
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
        if (!isGenerating) clearLive()
        messages.clear()
        pendingAttachments.clear()
        currentSessionId = null
    }

    fun loadSession(session: ChatSession) {
        if (!isGenerating) clearLive()
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


    /** Результат хода: сессия opencode (чат продолжается в ней) и всё, что модель сделала. */
    private class OcResult(val sessionId: String, val turn: OcTurn?, val text: String)

    @Volatile private var ocRoot: String = ""
    @Volatile private var ocPassword: String = ""
    @Volatile private var activeOcSession: String? = null

    /** Простой запрос к OpenCode без привязки к текущей генерации (разрешения, ответы на них). */
    private fun ocCall(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("$ocRoot/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (ocPassword.isNotBlank()) {
                val encoded = Base64.encodeToString("opencode:$ocPassword".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(status in 200..299) { "HTTP $status: ${raw.take(300)}" }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    /** Запросы разрешений для текущей сессии: показываем диалог, как в OpenCode. */
    private fun refreshAsks() {
        val session = activeOcSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching {
                ocCall("GET", "api/permission/request").optJSONArray("data").objects().map { parseAsk(it) }
            }.getOrNull() ?: return@launch
            val mine = list.filter { it.session == session }
            withContext(Dispatchers.Main) { asks = mine }
        }
    }

    /** decision: "once", "always" или "reject". */
    fun replyAsk(ask: OcAsk, decision: String) {
        asks = asks.filter { it.id != ask.id }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ocCall(
                    "POST", "api/session/${enc(ask.session)}/permission/${enc(ask.id)}/reply",
                    JSONObject().put("decision", decision)
                )
            }
            refreshAsks()
        }
    }

    /**
     * Ход в сессии opencode встроенного (или своего) сервера. Как у Margy: сессия живёт между сообщениями,
     * модель видит инструменты, а мы читаем из неё рассуждения, вызовы инструментов и текст.
     */
    private suspend fun callOpenCodeServer(
        baseUrl: String,
        password: String,
        modelId: String,
        existingSessionId: String?,
        buildPrompt: (isNewSession: Boolean) -> String,
        selectedProviderId: String? = null,
        directory: String? = null,
        onSession: (String) -> Unit = {},
        onStage: (String) -> Unit = {}
    ): OcResult {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://") || root.startsWith("http://")) {
            "Адрес сервера должен начинаться с http:// или https://"
        }
        ocRoot = root
        ocPassword = password

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

        // Чат продолжается в той же сессии; если её нет (чистая установка, другой сервер) — создаём новую.
        var sessionId = existingSessionId?.takeIf { it.isNotBlank() }
        if (sessionId != null) {
            onStage("Продолжаю сессию")
            val alive = runCatching { request("api/session/${enc(sessionId!!)}", readTimeoutMs = 15000) }.isSuccess
            if (!alive) sessionId = null
        }
        val created = sessionId == null
        if (sessionId == null) {
            onStage("Создаю сессию")
            // Как у Margy: агент build (с инструментами) и папка проекта. Для чужого сервера не трогаем.
            val sessionBody = JSONObject().put("title", "YouCode Android")
            if (!directory.isNullOrBlank()) {
                sessionBody.put("agent", "build")
                sessionBody.put("location", JSONObject().put("directory", directory))
            }
            val session = request("api/session", sessionBody)
            sessionId = session.optString("id").ifBlank {
                session.optJSONObject("data")?.optString("id").orEmpty()
            }
            require(sessionId.isNotBlank()) { "OpenCode создал сессию без ID: ${session.toString().take(400)}" }
        }
        val sid: String = sessionId ?: error("OpenCode не вернул сессию")
        activeOcSession = sid
        onSession(sid)

        // Модель задаётся на уровне сессии: в теле prompt её нет.
        val modelFailure = runCatching {
            request(
                "api/session/${enc(sid)}/model",
                JSONObject().put("model", JSONObject().put("providerID", providerId).put("id", modelId)),
                readTimeoutMs = 20000
            )
        }.exceptionOrNull()
        if (modelFailure != null) {
            val message = modelFailure.message.orEmpty()
            // Совсем старые движки не знают переключения модели, но принимают model в теле /message.
            if (!message.startsWith("HTTP 404") && !message.startsWith("HTTP 405")) throw modelFailure
        }

        val abortAction: () -> Unit = {
            runCatching { request("api/session/${enc(sid)}/interrupt", JSONObject(), readTimeoutMs = 10000) }
        }
        activeAbort = abortAction
        val eventConnection = AtomicReference<HttpURLConnection?>(null)
        val executionEnded = java.util.concurrent.atomic.AtomicBoolean(false)
        val eventJob = listenOpenCodeEvents(root, password, sid, eventConnection, executionEnded)
        try {
            // Запоминаем последнее сообщение пользователя ДО запроса: так отличаем новый ход от прошлого.
            val previousUser = runCatching {
                lastUserId(request("api/session/${enc(sid)}/message?limit=200", readTimeoutMs = 30000))
            }.getOrNull()

            onStage("Отправляю запрос модели")
            val fullPrompt = buildPrompt(created)
            var directAnswer: String? = null
            val promptFailure = runCatching {
                request("api/session/${enc(sid)}/prompt", JSONObject().put("text", fullPrompt), readTimeoutMs = 120000)
            }.exceptionOrNull()
            if (promptFailure != null) {
                val failure = promptFailure.message.orEmpty()
                // Старые движки знают только POST /session/{id}/message с телом {model, parts}
                // и отвечают готовым сообщением ассистента.
                if (failure.startsWith("HTTP 404") || failure.startsWith("HTTP 405")) {
                    val legacy = request(
                        "api/session/${enc(sid)}/message",
                        JSONObject()
                            .put("model", JSONObject().put("providerID", providerId).put("id", modelId))
                            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", fullPrompt))),
                        readTimeoutMs = 30 * 60 * 1000
                    )
                    directAnswer = legacyText(legacy)
                } else {
                    throw promptFailure
                }
            }
            if (directAnswer != null) {
                require(directAnswer.isNotBlank()) { "OpenCode не вернул текст ответа. Проверь, что выбранная модель доступна в каталоге." }
                return OcResult(sid, null, directAnswer)
            }

            onStage("Модель обрабатывает запрос")
            // Агентные задачи могут идти долго: ждём до 30 минут, опрашивая историю сессии.
            val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
            var turn: OcTurn? = null
            var emptyPolls = 0
            var stable = 0
            var lastSignature = ""
            var tick = 0
            while (System.currentTimeMillis() < deadline) {
                // Флаг читаем ДО запроса: тогда в списке гарантированно лежит финальное состояние.
                val endedBefore = executionEnded.get()
                val t = parseTurn(request("api/session/${enc(sid)}/message?limit=200", readTimeoutMs = 30000), previousUser)
                turn = t
                viewModelScope.launch(Dispatchers.Main) { liveTurn = t.messages }
                if (tick++ % 4 == 0) refreshAsks()

                // Ошибка модели окончательна (повторы OpenCode сюда не попадают), OpenCode сам объявляет конец хода.
                if (t.error != null || endedBefore || (t.started && t.idle)) break

                if (!t.started || t.messages.isEmpty()) {
                    if (++emptyPolls >= 170) {
                        throw IllegalStateException(
                            "Модель не начала отвечать за 2 минуты. Проверь выбранную модель и подключение провайдера в OpenCode."
                        )
                    }
                } else {
                    emptyPolls = 0
                }
                if (t.signature != lastSignature) {
                    lastSignature = t.signature
                    stable = 0
                    onStage(
                        when {
                            t.toolRunning -> "Выполняю инструменты"
                            t.hasText -> "Модель печатает ответ"
                            else -> "Модель думает"
                        }
                    )
                } else {
                    stable++
                }
                // Страховка для движков без сигнала конца: ответ готов, если ~30 секунд ничего не менялось.
                if (stable >= 45 && !t.toolRunning && t.hasText && asks.isEmpty()) break
                delay(700)
            }
            onStage("Получаю результат модели")
            val finalTurn = turn
            require(finalTurn != null && (finalTurn.parts.isNotEmpty() || finalTurn.error != null)) {
                "OpenCode не вернул ответ. Проверь, что выбранная модель доступна в каталоге."
            }
            return OcResult(sid, finalTurn, finalTurn.text)
        } finally {
            eventJob.cancel()
            runCatching { eventConnection.get()?.disconnect() }
            // Не затираем ссылки, если пользователь уже запустил следующий запрос.
            if (activeAbort === abortAction) activeAbort = null
            // Сессию не удаляем: в ней живёт контекст чата.
        }
    }

    private fun clearLive() {
        liveTurn = emptyList()
        deltaPieces = emptyList()
        asks = emptyList()
    }

    /** То, что модель делает прямо сейчас: сохранённые части хода с наложенным потоком текста и рассуждений. */
    fun liveShown(): List<MsgPart> {
        val byMessage = deltaPieces.groupBy { it.message }
        fun piece(l: DeltaPiece): MsgPart = if (l.reasoning) MsgPart.Reasoning(l.text) else MsgPart.Text(l.text)
        val out = mutableListOf<MsgPart>()
        for (m in liveTurn) {
            val parts = m.parts.toMutableList()
            for (l in byMessage[m.id].orEmpty()) {
                if (l.text.isBlank()) continue
                // ordinal-я сохранённая часть того же вида, если opencode её уже записал
                val same = parts.withIndex().filter { (_, p) -> if (l.reasoning) p is MsgPart.Reasoning else p is MsgPart.Text }
                val stored = same.getOrNull(l.ordinal)
                if (stored == null) {
                    parts += piece(l)
                } else {
                    val old = when (val p = stored.value) { is MsgPart.Text -> p.text; is MsgPart.Reasoning -> p.text; else -> "" }
                    if (old.length < l.text.length) {
                        val keepMs = (stored.value as? MsgPart.Reasoning)?.ms ?: 0L
                        parts[stored.index] = if (l.reasoning) MsgPart.Reasoning(l.text, keepMs) else MsgPart.Text(l.text)
                    }
                }
            }
            out += parts
        }
        val known = liveTurn.map { it.id }.toSet()
        for ((id, pieces) in byMessage) if (id !in known) pieces.filter { it.text.isNotBlank() }.forEach { out += piece(it) }
        return out
    }

    /**
     * Поток событий OpenCode: рассуждения и печатаемый ответ дельтами, запросы разрешений, конец хода.
     * Соединение без таймаута чтения (долгая «тишина» при размышлении — это нормально)
     * и с переподключением, как у Margy.
     */
    private fun listenOpenCodeEvents(
        root: String,
        password: String,
        sessionId: String,
        connectionRef: AtomicReference<HttpURLConnection?>,
        executionEnded: java.util.concurrent.atomic.AtomicBoolean
    ): Job = viewModelScope.launch(Dispatchers.IO) {
        // Текст и рассуждения нумеруются отдельно у каждого сообщения ассистента: кусок известен по трём полям.
        val pieces = LinkedHashMap<Triple<String, Boolean, Int>, StringBuilder>()
        var lastPublish = 0L

        fun publish(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < 80) return
            lastPublish = now
            val snapshot = pieces.map { (k, v) -> DeltaPiece(k.first, k.second, k.third, v.toString()) }
            viewModelScope.launch(Dispatchers.Main) { deltaPieces = snapshot }
        }

        fun handle(raw: String) {
            val envelope = JSONObject(raw)
            val payload = envelope.optJSONObject("payload")
            // Новый API шлёт {type, data:{...}}, старый — {payload:{type, properties:{...}}}.
            val event = payload?.optJSONObject("event") ?: payload ?: envelope
            val type = event.optString("type")
            val props = event.optJSONObject("properties")
                ?: event.optJSONObject("data")
                ?: JSONObject()
            val eventSession = props.optString("sessionID").ifBlank { props.optString("sessionId") }
            if (eventSession.isNotBlank() && eventSession != sessionId) return

            when {
                (type == "session.reasoning.delta" || type == "session.text.delta") && props.has("delta") -> {
                    val message = props.optString("assistantMessageID")
                    if (message.isNotBlank()) {
                        val key = Triple(message, type == "session.reasoning.delta", props.optInt("ordinal"))
                        pieces.getOrPut(key) { StringBuilder() }.append(props.optString("delta"))
                        publish()
                    }
                }
                // Любое session.execution.* кроме started — ход завершён (успех, ошибка или прерывание).
                type.startsWith("session.execution.") && type != "session.execution.started" -> {
                    executionEnded.set(true)
                    publish(force = true)
                }
                type.startsWith("permission.") -> refreshAsks()
            }
        }

        var failures = 0
        while (isActive && failures < 20) {
            val connection = (URL("$root/api/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 0 // поток живёт, пока идёт запрос
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                if (password.isNotBlank()) {
                    val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $encoded")
                }
            }
            connectionRef.set(connection)
            activeEventConnection = connection
            if (!isActive) { connection.disconnect(); break }
            try {
                if (connection.responseCode !in 200..299) {
                    failures++
                } else {
                    failures = 0
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val data = StringBuilder()
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        val raw = data.toString(); data.clear()
                                        runCatching { handle(raw) }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Поток событий необязателен: ход всё равно читается из списка сообщений.
                failures++
            } finally {
                if (activeEventConnection === connection) activeEventConnection = null
                connection.disconnect()
            }
            if (isActive) delay(1000)
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
            if (history.isNotBlank()) append("История диалога:\n").append(history).append("\n\n")
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                append(file.contentPreview.takeIf { it.isNotBlank() }?.take(20000) ?: "Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) append("\n\nРабочая папка: $workingFolderName. Файлы: ${workingFolderFiles.take(30).joinToString()}")
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
            if (history.isNotBlank()) append("История диалога:\n").append(history).append("\n\n")
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
