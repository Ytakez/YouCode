package com.opencode.ai.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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

    var messages = mutableStateListOf<ChatMessage>()
        private set

    var isGenerating by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var sessions = mutableStateListOf<ChatSession>()
        private set

    private var currentSessionId: String? = null

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
        get() = if (searchQuery.isBlank()) {
            SampleData.freeModels
        } else {
            SampleData.freeModels.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.provider.contains(searchQuery, ignoreCase = true)
            }
        }

    fun updateSearch(query: String) {
        searchQuery = query
    }

    fun selectModel(model: AiModel) {
        currentModel = model
    }

    fun selectMode(mode: com.opencode.ai.data.SmartMode) {
        currentMode = mode
        modeModelIndex[mode.id] = 0
        val firstId = mode.preferredModelIds.firstOrNull()
        if (firstId != null) {
            SampleData.freeModels.firstOrNull { it.id == firstId }?.let { currentModel = it }
        }
    }

    fun rotateModelOnLimit() {
        val mode = currentMode
        val ids = mode.preferredModelIds
        if (ids.isEmpty()) return
        val next = ((modeModelIndex[mode.id] ?: 0) + 1) % ids.size
        modeModelIndex[mode.id] = next
        SampleData.freeModels.firstOrNull { it.id == ids[next] }?.let { currentModel = it }
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
        viewModelScope.launch {
            val providerId = providerPrefs.getString("active_provider", "").orEmpty()
            val endpoint = providerPrefs.getString("base_${providerId}", "").orEmpty().trim()
            val apiKey = providerPrefs.getString("key_${providerId}", "").orEmpty().trim()
            val configuredModel = providerPrefs.getString("model_${providerId}", "").orEmpty().trim()
            // OpenRouter uses the model selected in YouCode, so one key serves all twelve entries.
            val remoteModel = if (providerId == "openrouter-free") currentModel.id else configuredModel
            val protocol = providerPrefs.getString("protocol_${providerId}", "openai").orEmpty()
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
                        callOpenCodeServer(endpoint, apiKey, currentModel.id, fullPrompt)
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { "Ошибка OpenCode Server: ${it.message ?: "не удалось выполнить запрос"}. Проверь адрес сервера, пароль и наличие выбранной бесплатной модели в OpenCode." to emptyList() }
                )
            } else if (endpoint.isNotBlank() && remoteModel.isNotBlank() && apiKey.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (protocol == "anthropic") callAnthropic(endpoint, apiKey, remoteModel, text, attachmentsCopy, currentMode.systemHint)
                        else callOpenAiCompatible(endpoint, apiKey, remoteModel, text, attachmentsCopy, currentMode.systemHint)
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { "Ошибка облачного ИИ: ${it.message ?: "не удалось выполнить запрос"}. Проверь Base URL, API-ключ, ID модели и доступ к сети." to emptyList() }
                )
            } else {
                ("ИИ не подключён. Открой «Провайдеры» и подключи OpenCode Server или укажи Base URL, API-ключ и ID модели для другого провайдера. Демонстрационные ответы отключены.") to emptyList()
            }
            val shownModel = if (providerId == "opencode-server" || providerId == "openrouter-free") currentModel.name else if (remoteModel.isNotBlank()) remoteModel else currentModel.name
            messages.add(ChatMessage(
                id = UUID.randomUUID().toString(), content = reply.first, isUser = false,
                modelName = shownModel, attachments = reply.second
            ))
            isGenerating = false
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
    private fun callOpenCodeServer(baseUrl: String, password: String, modelId: String, prompt: String): String {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://") || root.startsWith("http://")) {
            "Адрес сервера должен начинаться с http:// или https://"
        }

        fun request(path: String, body: JSONObject? = null): JSONObject {
            val connection = (URL("$root/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = 12000
                readTimeout = 120000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (password.isNotBlank()) {
                    val credentials = "opencode:$password"
                    val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $encoded")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching { JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() } }.getOrNull().orEmpty()
                    throw IllegalStateException("HTTP $status${if (detail.isNotBlank()) ": $detail" else ": ${raw.take(300)}"}")
                }
                return if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } finally { connection.disconnect() }
        }

        // Resolve the providerID from the live server catalog instead of guessing it.
        val catalog = request("api/model")
        val models = catalog.optJSONArray("data") ?: catalog.optJSONArray("models") ?: JSONArray()
        var providerId: String? = null
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            if (item.optString("id") == modelId) {
                providerId = item.optString("providerID").takeIf { it.isNotBlank() }
                    ?: item.optJSONObject("provider")?.optString("id")?.takeIf { it.isNotBlank() }
                break
            }
        }
        require(providerId != null) {
            "Модель $modelId не найдена в каталоге сервера. Обнови OpenCode и проверь, что эта бесплатная модель доступна в твоём регионе."
        }

        val response = request("api/experimental/generate/text", JSONObject()
            .put("prompt", prompt)
            .put("model", JSONObject().put("id", modelId).put("providerID", providerId)))
        val answer = response.optJSONObject("data")?.optString("text").orEmpty()
        return answer.ifBlank { throw IllegalStateException("OpenCode Server вернул пустой ответ") }
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
            connection.disconnect()
        }
    }

}
