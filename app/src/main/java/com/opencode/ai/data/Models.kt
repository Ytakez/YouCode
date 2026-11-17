package com.opencode.ai.data

import android.net.Uri

data class AiModel(
    val id: String,
    val name: String,
    val contextSize: String,
    val isFree: Boolean = true,
    val provider: String = "YouCode",
    val description: String = "",
    val isFavorite: Boolean = false
)

data class AttachedFile(
    val id: String,
    val name: String,
    val uri: Uri? = null,
    val mimeType: String = "text/plain",
    val sizeBytes: Long = 0,
    val contentPreview: String = "",
    val isSavedByAi: Boolean = false
)

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

data class LocalModel(
    val id: String,
    val name: String,
    val vendor: String,
    val description: String,
    val sizeGb: Float,
    val minRamGb: Int,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f
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
            listOf("cohere/north-mini-code:free", "nvidia/nemotron-3.5-lightning:free", "dots-studio/dots-3-note-preview:free"),
            "Ты универсальный помощник. Подстраивайся под задачу."
        ),
        SmartMode(
            "code", "Программирование", "💻",
            "Лучшие модели для кода. При лимите — следующая",
            listOf("cohere/north-mini-code:free", "recursive/flint-v1.0:free", "particle-ai/particle-1:free", "nvidia/nemotron-3.5-lightning:free"),
            "Ты senior-разработчик. Пиши чистый код, объясняй кратко, предлагай лучшие практики."
        ),
        SmartMode(
            "study", "Учёба", "📚",
            "Объяснения, конспекты, подготовка к экзаменам",
            listOf("inclusionai/ling-3.2-flash:free", "nvidia/nemotron-3.5-super-vl:free", "nvidia/nemotron-3.5-lightning:free"),
            "Ты терпеливый преподаватель. Объясняй просто, с примерами, проверяй понимание."
        ),
        SmartMode(
            "design", "Дизайн", "🎨",
            "UI/UX, цвета, типографика, макеты",
            listOf("dots-studio/dots-3-note-preview:free", "thinkingmachines/inkling-2-small:free", "liquid/lfm-3-1.7b:free"),
            "Ты продуктовый дизайнер. Думай о иерархии, доступности, трендах Material Design 3."
        ),
        SmartMode(
            "write", "Тексты", "✍️",
            "Статьи, посты, письма, сценарии",
            listOf("inclusionai/ling-3.2-flash:free", "nvidia/nemotron-3.5-lightning:free", "liquid/lfm-3-1.7b:free"),
            "Ты сильный редактор и копирайтер. Пиши ясно, живо, без воды."
        ),
        SmartMode(
            "translate", "Перевод", "🌐",
            "Точный перевод и адаптация",
            listOf("nvidia/nemotron-3.5-lightning:free", "inclusionai/ling-3.2-flash:free", "cohere/north-mini-code:free"),
            "Ты профессиональный переводчик. Сохраняй смысл и стиль оригинала."
        ),
        SmartMode(
            "math", "Математика", "🔢",
            "Задачи, формулы, пошаговые решения",
            listOf("cohere/north-mini-code:free", "recursive/flint-v1.0:free", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"),
            "Ты математик. Решай пошагово, проверяй ответ, объясняй логику."
        ),
        SmartMode(
            "business", "Бизнес", "📊",
            "Стратегия, аналитика, презентации",
            listOf("dots-studio/dots-3-note-preview:free", "inclusionai/ling-3.2-flash:free", "nvidia/nemotron-3.5-lightning:free"),
            "Ты бизнес-консультант. Думай структурированно, давай actionable советы."
        ),
        SmartMode(
            "creative", "Креатив", "🎭",
            "Идеи, истории, брейншторм",
            listOf("thinkingmachines/inkling-2-small:free", "liquid/lfm-3-1.7b:free", "liquid/lfm-3-1.7b:free"),
            "Ты креативный директор. Предлагай неожиданные идеи, развивай образы."
        ),
        SmartMode(
            "research", "Исследование", "🔬",
            "Поиск фактов, сравнение, анализ",
            listOf("nvidia/nemotron-3.5-super-vl:free", "cohere/north-mini-code:free", "inclusionai/ling-3.2-flash:free"),
            "Ты исследователь. Опирайся на факты, сравнивай источники, указывай неопределённость."
        ),
        SmartMode(
            "legal", "Документы", "📄",
            "Договоры, заявления, формальные тексты",
            listOf("inclusionai/ling-3.2-flash:free", "nvidia/nemotron-3.5-lightning:free", "particle-ai/particle-1:free"),
            "Ты помогаешь с формальными документами. Пиши чётко и структурированно. Это не юрконсультация."
        ),
        SmartMode(
            "health", "Здоровье", "🩺",
            "Общая информация о ЗОЖ (не медицина)",
            listOf("nvidia/nemotron-3.5-super-vl:free", "nvidia/nemotron-3.5-lightning:free"),
            "Давай общую информацию о здоровье и ЗОЖ. Всегда напоминай обратиться к врачу."
        ),
        SmartMode(
            "travel", "Путешествия", "✈️",
            "Маршруты, советы, чек-листы",
            listOf("thinkingmachines/inkling-2-small:free", "dots-studio/dots-3-note-preview:free", "nvidia/nemotron-3.5-lightning:free"),
            "Ты travel-эксперт. Помогай с маршрутами, бюджетом и практическими советами."
        ),
        SmartMode(
            "fast", "Быстрый ответ", "⚡",
            "Коротко и по делу, минимальный расход",
            listOf("nvidia/nemotron-3.5-lightning:free", "liquid/lfm-3-1.7b:free", "baidu/ernie-5.5-flash-preview:free"),
            "Отвечай максимально кратко и по делу."
        ),
    )

    // Twelve currently listed free chat models from OpenRouter. Model availability and free limits can change upstream.
    val freeModels = listOf(
        AiModel("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra", "1M", true, "OpenRouter"),
        AiModel("nvidia/nemotron-3.5-super-vl:free", "Nemotron 3.5 Super VL", "1M", true, "OpenRouter"),
        AiModel("baidu/ernie-5.5-flash-preview:free", "ERNIE 5.5 Flash Preview", "1M", true, "OpenRouter"),
        AiModel("thinkingmachines/inkling-2-small:free", "Inkling 2 Small", "1M", true, "OpenRouter"),
        AiModel("nvidia/nemotron-3.5-lightning:free", "Nemotron 3.5 Lightning", "1M", true, "OpenRouter"),
        AiModel("dots-studio/dots-3-note-preview:free", "Dots3 Note Preview", "512K", true, "OpenRouter"),
        AiModel("particle-ai/particle-1:free", "Particle 1", "1M", true, "OpenRouter"),
        AiModel("inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash", "1M", true, "OpenRouter"),
        AiModel("cohere/north-mini-code:free", "North Mini Code", "256K", true, "OpenRouter"),
        AiModel("recursive/flint-v1.0:free", "Flint", "262K", true, "OpenRouter"),
        AiModel("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "Nemotron 3 Nano Omni", "256K", true, "OpenRouter"),
        AiModel("liquid/lfm-3-1.7b:free", "LFM3 1.7B", "262K", true, "OpenRouter"),
    )

    val localModels = listOf(
        LocalModel("gemma4-e2b", "Gemma 4 E2B", "Google", "точнее мобильной", 3.1f, 8),
        LocalModel("gemma4-e4b-mobile", "Gemma 4 E4B (мобильная)", "Google", "умнее E2B, для телефонов с 8 ГБ памяти", 3.2f, 8),
        LocalModel("gemma4-e4b", "Gemma 4 E4B", "Google", "лучшая из маленьких, для 12 ГБ памяти и больше", 5.0f, 12),
        LocalModel("qwen35-2b", "Qwen 3.5 2B", "Alibaba", "самая лёгкая", 1.3f, 4),
        LocalModel("qwen35-4b", "Qwen 3.5 4B", "Alibaba", "хорошо работает с инструментами", 2.7f, 6),
    )

    val providers = listOf(
        ProviderItem("opencode-server", "OpenCode Server (бесплатные модели)", "Подключение к твоему официальному OpenCode Server. Бесплатные модели запускаются внутри OpenCode, не через прямой API.", "opencode-server"),
        ProviderItem("opencode-go", "OpenCode Go", "Подписка opencode: Kimi, GLM, DeepSeek, Qwen, MiniMax и другие открытые модели за фиксированную цену.", "opencode"),
        ProviderItem("opencode-zen", "OpenCode Zen", "Аккаунт opencode: бесплатные модели, а с балансом все модели Zen, включая Claude, GPT и Gemini.", "opencode"),
        ProviderItem("claude", "Claude", "Подписка Claude Pro или Max через официальный Claude Code.", "opencode"),
        ProviderItem("local", "Модели на телефоне", "Gemma 4, Qwen и другие, без интернета и бесплатно", "local"),
        ProviderItem("openrouter-free", "OpenRouter — 12 бесплатных моделей", "Один API-ключ для 12 бесплатных моделей. Выбирай модель в списке YouCode; лимиты зависят от OpenRouter.", "key", true),
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
