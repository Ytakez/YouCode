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

/** Уровень «разума» (reasoning effort) модели. */
enum class ReasoningLevel(val label: String, val variant: String?, val openAiEffort: String?, val thinkingBudget: Int) {
    AUTO("Авто", null, null, 0),
    LOW("Низкий", "low", "low", 1024),
    MEDIUM("Средний", "medium", "medium", 4096),
    HIGH("Большой", "high", "high", 8192),
    VERY_HIGH("Очень большой", "xhigh", "high", 16000),
    MAX("Максимальный", "max", "high", 32000)
}

data class AttachedFile(
    val id: String,
    val name: String,
    val uri: Uri? = null,
    val mimeType: String = "text/plain",
    val sizeBytes: Long = 0,
    val contentPreview: String = "",
    val isSavedByAi: Boolean = false
)

/** Один шаг «хода работы» ИИ: размышление или вызов инструмента. */
data class TraceStep(
    val id: String,
    val kind: String, // "thinking" | "tool"
    val title: String,
    val text: String = "",
    val status: String = "running", // running | done | error
    val startedAt: Long = 0L,
    val endedAt: Long? = null
) {
    val isRunning: Boolean get() = status == "running"
}

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val modelName: String? = null,
    val attachments: List<AttachedFile> = emptyList(),
    val trace: List<TraceStep> = emptyList()
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
    val emoji: String,
    val description: String,
    val preferredModelIds: List<String>,
    val systemHint: String
)

object SampleData {
    
    val smartModes = listOf(
        SmartMode(
            "auto", "Авто", "✨",
            "Сам выбирает модель под задачу",
            listOf("deepseek-v4.1-flash-free", "hy4-flash-free", "big-pickle"),
            "Ты универсальный помощник. Подстраивайся под задачу."
        ),
        SmartMode(
            "code", "Программирование", "💻",
            "Лучшие модели для кода. При лимите — следующая",
            listOf("deepseek-v4.1-flash-free", "flint-40b-free", "glm-5.3-flash-free", "hy4-flash-free"),
            "Ты senior-разработчик. Пиши чистый код, объясняй кратко, предлагай лучшие практики."
        ),
        SmartMode(
            "study", "Учёба", "📚",
            "Объяснения, конспекты, подготовка к экзаменам",
            listOf("longcat-2.5-free", "nemotron-3.5-super-vl-free", "hy4-flash-free"),
            "Ты терпеливый преподаватель. Объясняй просто, с примерами, проверяй понимание."
        ),
        SmartMode(
            "design", "Дизайн", "🎨",
            "UI/UX, цвета, типографика, макеты",
            listOf("big-pickle", "beam-free", "iceman-free"),
            "Ты продуктовый дизайнер. Думай о иерархии, доступности, трендах Material Design 3."
        ),
        SmartMode(
            "write", "Тексты", "✍️",
            "Статьи, посты, письма, сценарии",
            listOf("longcat-2.5-free", "hy4-flash-free", "iceman-free"),
            "Ты сильный редактор и копирайтер. Пиши ясно, живо, без воды."
        ),
        SmartMode(
            "translate", "Перевод", "🌐",
            "Точный перевод и адаптация",
            listOf("hy4-flash-free", "longcat-2.5-free", "deepseek-v4.1-flash-free"),
            "Ты профессиональный переводчик. Сохраняй смысл и стиль оригинала."
        ),
        SmartMode(
            "math", "Математика", "🔢",
            "Задачи, формулы, пошаговые решения",
            listOf("deepseek-v4.1-flash-free", "flint-40b-free", "nemotron-3.5-lightning-free"),
            "Ты математик. Решай пошагово, проверяй ответ, объясняй логику."
        ),
        SmartMode(
            "business", "Бизнес", "📊",
            "Стратегия, аналитика, презентации",
            listOf("big-pickle", "longcat-2.5-free", "hy4-flash-free"),
            "Ты бизнес-консультант. Думай структурированно, давай actionable советы."
        ),
        SmartMode(
            "creative", "Креатив", "🎭",
            "Идеи, истории, брейншторм",
            listOf("beam-free", "iceman-free", "iceman-free"),
            "Ты креативный директор. Предлагай неожиданные идеи, развивай образы."
        ),
        SmartMode(
            "research", "Исследование", "🔬",
            "Поиск фактов, сравнение, анализ",
            listOf("nemotron-3.5-super-vl-free", "deepseek-v4.1-flash-free", "longcat-2.5-free"),
            "Ты исследователь. Опирайся на факты, сравнивай источники, указывай неопределённость."
        ),
        SmartMode(
            "legal", "Документы", "📄",
            "Договоры, заявления, формальные тексты",
            listOf("longcat-2.5-free", "hy4-flash-free", "glm-5.3-flash-free"),
            "Ты помогаешь с формальными документами. Пиши чётко и структурированно. Это не юрконсультация."
        ),
        SmartMode(
            "health", "Здоровье", "🩺",
            "Общая информация о ЗОЖ (не медицина)",
            listOf("nemotron-3.5-super-vl-free", "hy4-flash-free"),
            "Давай общую информацию о здоровье и ЗОЖ. Всегда напоминай обратиться к врачу."
        ),
        SmartMode(
            "travel", "Путешествия", "✈️",
            "Маршруты, советы, чек-листы",
            listOf("beam-free", "big-pickle", "hy4-flash-free"),
            "Ты travel-эксперт. Помогай с маршрутами, бюджетом и практическими советами."
        ),
        SmartMode(
            "fast", "Быстрый ответ", "⚡",
            "Коротко и по делу, минимальный расход",
            listOf("hy4-flash-free", "iceman-free", "mimo-v2.6-flash-free"),
            "Отвечай максимально кратко и по делу."
        ),
    )

    /** Запасной список на случай, когда живой каталог ещё не загружен. Основной список приходит из /api/model. */
    val freeModels = listOf(
        AiModel("hy4-flash-free", "Hy4 Flash Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("iceman-free", "Iceman Free", "262k", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("big-pickle", "Big Pickle", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("longcat-2.5-free", "LongCat 2.5 Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("flint-40b-free", "Flint 40B Free", "262k", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("nemotron-3-ultra-free", "Nemotron 3 Ultra Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash Free", "200k", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("nemotron-3.5-super-vl-free", "Nemotron 3.5 Super VL Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("beam-free", "Beam Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("mimo-v2.6-flash-free", "MiMo V2.6 Flash Free", "200k", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("glm-5.3-flash-free", "GLM 5.3 Flash Free", "200k", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
        AiModel("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free", "1M", true, "OpenCode Zen", "Бесплатная модель OpenCode", remoteProviderId = "opencode"),
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
