package com.opencode.ai.data

import android.net.Uri

data class AiModel(
    val id: String,
    val name: String,
    val contextSize: String,
    val isFree: Boolean = true,
    val provider: String = "YouCode",
    val description: String = "",
    val isFavorite: Boolean = false,
    val remoteProviderId: String? = null
)

data class AttachedFile(
    val id: String,
    val name: String,
    val uri: Uri? = null,
    /** Абсолютный путь на диске (сгенерированные картинки, копии фото). */
    val localPath: String? = null,
    val mimeType: String = "text/plain",
    val sizeBytes: Long = 0,
    val contentPreview: String = "",
    val isSavedByAi: Boolean = false
) {
    val isImage: Boolean get() = mimeType.startsWith("image/") ||
        name.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }
}

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val modelName: String? = null,
    val attachments: List<AttachedFile> = emptyList()
)

data class ChatSession(
    val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: Long,
    val messages: List<ChatMessage> = emptyList()
)

data class ProviderItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String, // "opencode" | "local" | "key"
    val needsKey: Boolean = false
)

data class SkillItem(
    val id: String,
    val name: String,
    val description: String
)

data class McpServer(
    val id: String,
    val name: String,
    val url: String = ""
)


data class SmartMode(
    val id: String,
    val name: String,
    val emoji: String, // kept for compatibility, intentionally empty for a serious UI
    val description: String,
    val preferredModelIds: List<String>,
    val systemHint: String
)

object SampleData {
    
    val smartModes = listOf(
        SmartMode(
            "auto", "Авто", "",
            "Сам выбирает модель под задачу",
            emptyList(),
            "Ты сильный универсальный помощник. Думай ясно, отвечай точно, предлагай следующий шаг. Без воды."
        ),
        SmartMode(
            "code", "Программирование", "",
            "Лучшие модели для кода. При лимите — следующая",
            emptyList(),
            "Ты senior-разработчик. Пиши рабочий код сразу, объясняй коротко, лови баги, не раздувай проект без нужды."
        ),
        SmartMode(
            "study", "Учёба", "",
            "Объяснения, конспекты, подготовка к экзаменам",
            emptyList(),
            "Ты сильный репетитор. Объясняй просто и по шагам, с примерами. Не обсуждай с посторонними историю чата владельца."
        ),
        SmartMode(
            "design", "Дизайн", "",
            "UI/UX, цвета, типографика, макеты",
            emptyList(),
            "Ты продуктовый дизайнер. Думай о иерархии, доступности, трендах Material Design 3."
        ),
        SmartMode(
            "write", "Тексты", "",
            "Статьи, посты, письма, сценарии",
            emptyList(),
            "Ты сильный редактор и копирайтер. Пиши ясно, живо, без воды."
        ),
        SmartMode(
            "translate", "Перевод", "",
            "Точный перевод и адаптация",
            emptyList(),
            "Ты профессиональный переводчик. Сохраняй смысл и стиль оригинала."
        ),
        SmartMode(
            "math", "Математика", "",
            "Задачи, формулы, пошаговые решения",
            emptyList(),
            "Ты математик. Решай пошагово, проверяй ответ, объясняй логику."
        ),
        SmartMode(
            "business", "Бизнес", "",
            "Стратегия, аналитика, презентации",
            emptyList(),
            "Ты бизнес-консультант. Думай структурированно, давай actionable советы."
        ),
        SmartMode(
            "creative", "Креатив", "",
            "Идеи, истории, брейншторм",
            emptyList(),
            "Ты креативный директор. Предлагай неожиданные идеи, развивай образы."
        ),
        SmartMode(
            "research", "Исследование", "",
            "Поиск фактов, сравнение, анализ",
            emptyList(),
            "Ты исследователь. Опирайся на факты, сравнивай источники, указывай неопределённость."
        ),
        SmartMode(
            "legal", "Документы", "",
            "Договоры, заявления, формальные тексты",
            emptyList(),
            "Ты помогаешь с формальными документами. Пиши чётко и структурированно. Это не юрконсультация."
        ),
        SmartMode(
            "health", "Здоровье", "",
            "Общая информация о ЗОЖ (не медицина)",
            emptyList(),
            "Давай общую информацию о здоровье и ЗОЖ. Всегда напоминай обратиться к врачу."
        ),
        SmartMode(
            "travel", "Путешествия", "",
            "Маршруты, советы, чек-листы",
            emptyList(),
            "Ты travel-эксперт. Помогай с маршрутами, бюджетом и практическими советами."
        ),
        SmartMode(
            "fast", "Быстрый ответ", "",
            "Коротко и по делу, минимальный расход",
            emptyList(),
            "Отвечай максимально кратко и по делу."
        ),
    )

    val providers = listOf(
        ProviderItem("opencode-server", "OpenCode (встроенный или свой сервер)", "Встроенный OpenCode запускается в приложении сам и даёт бесплатные модели без ключей. Можно указать адрес внешнего сервера.", "opencode-server"),
        ProviderItem("opencode-go", "OpenCode Go", "Подписка opencode: Kimi, GLM, DeepSeek, Qwen, MiniMax и другие открытые модели за фиксированную цену.", "opencode"),
        ProviderItem("opencode-zen", "OpenCode Zen", "Аккаунт opencode: бесплатные модели, а с балансом все модели Zen, включая Claude, GPT и Gemini.", "opencode"),
        ProviderItem("claude", "Claude", "Подписка Claude Pro или Max через официальный Claude Code.", "opencode"),
        ProviderItem("custom-cloud", "Свой Cloud / API", "Любой OpenAI-совместимый API: вставь домен, ключ и модель", "key", true),
        ProviderItem("302ai", "302.AI", "API-ключ", "key", true),
        ProviderItem("50x", "50X", "API-ключ", "key", true),
        ProviderItem("abacus", "Abacus", "API-ключ", "key", true),
        ProviderItem("abliterated", "AbliteratedLLM", "API-ключ", "key", true),
    )

    val skills = listOf(
        SkillItem("opencode", "OpenCode", "Use this skill for any question about OpenCode itself, including how OpenCode works, using or configuring it, migrating from V1 to V2, trouble..."),
        SkillItem("report", "Report", "Use when the user wants to report an opencode issue or bug. Collect standard diagnostics, add user-specific reproduction context, and publi..."),
    )

    val sampleSessions = listOf(
        ChatSession("1", "Greeting", "Привет! Как дела?", System.currentTimeMillis() - 3600000),
        ChatSession("2", "Знакомство", "Расскажи о себе", System.currentTimeMillis() - 7200000),
        ChatSession("3", "Запуск сервера", "Как запустить Node.js сервер?", System.currentTimeMillis() - 86400000),
        ChatSession("4", "Greeting", "Hello again", System.currentTimeMillis() - 172800000),
    )
}
