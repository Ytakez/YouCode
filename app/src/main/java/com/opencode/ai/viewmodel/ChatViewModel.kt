package com.opencode.ai.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.ai.data.AiModel
import com.opencode.ai.data.AttachedFile
import com.opencode.ai.data.ChatMessage
import com.opencode.ai.data.ChatSession
import com.opencode.ai.data.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel : ViewModel() {

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

    var sessions = mutableStateListOf<ChatSession>().apply { addAll(SampleData.sampleSessions) }
        private set

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

        isGenerating = true
        viewModelScope.launch {
            delay(700 + (text.length * 12L).coerceAtMost(2200))

            val reply = generateMockReply(text, currentModel, attachmentsCopy, currentMode)
            val botMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                content = reply.first,
                isUser = false,
                modelName = currentModel.name,
                attachments = reply.second
            )
            messages.add(botMsg)
            isGenerating = false
        }
    }

    fun newChat() {
        messages.clear()
        pendingAttachments.clear()
    }

    fun loadSession(session: ChatSession) {
        messages.clear()
        pendingAttachments.clear()
        messages.add(
            ChatMessage(
                id = "sys",
                content = "Загружен диалог: ${session.title}",
                isUser = false
            )
        )
    }

    private fun generateMockReply(
        userText: String,
        model: AiModel,
        attachments: List<AttachedFile>,
        mode: com.opencode.ai.data.SmartMode = SampleData.smartModes.first()
    ): Pair<String, List<AttachedFile>> {
        val lower = userText.lowercase()
        val hasFiles = attachments.isNotEmpty()
        val folderInfo = if (workingFolderName != null) {
            val list = workingFolderFiles.take(8).joinToString(", ")
            "\n\n📁 Рабочая папка: **$workingFolderName**" + (if (list.isNotBlank()) "\nФайлы: $list" else "")
        } else ""
        val fileNames = attachments.joinToString(", ") { it.name }

        // AI "saves" a file
        if (lower.contains("сохрани") || lower.contains("save") || lower.contains("запиши в файл") ||
            lower.contains("создай файл") || lower.contains("export")
        ) {
            val saved = AttachedFile(
                id = UUID.randomUUID().toString(),
                name = "opencode_result_${System.currentTimeMillis() % 10000}.txt",
                mimeType = "text/plain",
                contentPreview = "Результат сгенерирован моделью ${model.name}\n\nЗапрос: $userText\n\nЭто демо-сохранение. В реальном режиме файл будет записан через SAF.",
                isSavedByAi = true
            )
            return Pair(
                "Готово. Я подготовил файл **${saved.name}**.\n\nНажми на чип ниже, чтобы сохранить его на устройство через системный диалог.\n\nСодержимое готово к экспортту.".replace('экспортту', 'экспорту'),
                listOf(saved)
            )
        }

        if (hasFiles) {
            val preview = attachments.firstOrNull()?.contentPreview?.take(400) ?: ""
            return Pair(
                "Я получил файл(ы): **$fileNames**\n\n" +
                        (if (preview.isNotBlank()) "Фрагмент содержимого:\n```\n$preview\n```\n\n" else "") +
                        "Могу проанализировать, переписать, объяснить или сохранить результат в новый файл.\n\nСкажи, что сделать с этим файлом?",
                emptyList()
            )
        }

        return when {
            lower.contains("привет") || lower.contains("hello") || lower.contains("hi") ->
                Pair(
                    "Привет! 👋\n\nЯ **${model.name}** — бесплатная модель YouCode.\nКонтекст: ${model.contextSize}\n\nРаботаю без API-ключа. Могу читать и сохранять файлы.\n\nЧем помочь?",
                    emptyList()
                )
            lower.contains("кто ты") || lower.contains("представься") ->
                Pair(
                    "Я **${model.name}** из YouCode Console.\n\n• Контекст: ${model.contextSize}\n• Статус: бесплатно\n• Файлы: могу читать и сохранять\n\nСейчас локальный мок. Позже подключишь облако — получишь полный интеллект.",
                    emptyList()
                )
            lower.contains("файл") || lower.contains("file") || lower.contains("документ") ->
                Pair(
                    "Я умею работать с файлами:\n\n1. **Прикрепи** файл кнопкой 📎 — прочитаю содержимое\n2. Напиши «сохрани это» или «создай файл» — подготовлю результат для сохранения\n3. Могу анализировать код, тексты, логи\n\nПопробуй прикрепить что-нибудь.",
                    emptyList()
                )
            lower.contains("код") || lower.contains("code") || lower.contains("программ") ->
                Pair(
                    "Отлично, давай с кодом. 💻\n\nНапиши задачу или прикрепи файл с кодом — разберу, поправлю или сгенерирую новый.\n\nПример:\n```kotlin\nfun main() {\n    println(\"Hello from ${model.name}\")\n}\n```",
                    emptyList()
                )
            lower.contains("помощь") || lower.contains("help") || lower.contains("что умеешь") ->
                Pair(
                    "Я умею:\n• Отвечать на вопросы\n• Работать с кодом\n• **Читать прикреплённые файлы**\n• **Сохранять результаты** в файлы\n• Объяснять и переписывать текст\n\nСейчас — локальный режим без API.\nПодключи провайдера — станет намного сильнее.",
                    emptyList()
                )
            else ->
                Pair(
                    "Понял: «$userText»$folderInfo\n\n[Ответ от **${model.name}** · ${mode.emoji} ${mode.name}]\n\nЭто демо без реального API. Могу работать с файлами — прикрепи 📎 или попроси «сохрани результат».\n\nЧтобы получить мощный ИИ:\n1. Открой «Провайдеры»\n2. Подключи ключ или YouCode Go\n3. Выбери модель",
                    emptyList()
                )
        }
    }
}
