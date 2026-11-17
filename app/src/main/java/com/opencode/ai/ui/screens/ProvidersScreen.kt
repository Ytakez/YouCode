package com.opencode.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.ai.data.ProviderItem
import com.opencode.ai.data.SampleData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    onLocalModelsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    var search by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { context.getSharedPreferences("providers", android.content.Context.MODE_PRIVATE) }
    // id -> saved key (blank = not connected)
    val savedKeys = remember {
        mutableStateMapOf<String, String>().apply {
            SampleData.providers.forEach { put(it.id, prefs.getString("key_${it.id}", "") ?: "") }
            SampleData.providers.filter { it.id in setOf("custom-cloud", "openrouter-free", "opencode-go", "opencode-zen", "claude", "opencode-server") }.forEach { provider ->
                val hasBase = prefs.getString("base_${provider.id}", "").orEmpty().isNotBlank()
                val configured = if (provider.id == "opencode-server") hasBase else
                    hasBase && prefs.getString("model_${provider.id}", "").orEmpty().isNotBlank() &&
                        prefs.getString("key_${provider.id}", "").orEmpty().isNotBlank()
                if (configured) put(provider.id, "configured")
            }
        }
    }
    var dialogItem by remember { mutableStateOf<ProviderItem?>(null) }

    val filtered = if (search.isBlank()) SampleData.providers
    else SampleData.providers.filter {
        it.name.contains(search, true) || it.description.contains(search, true)
    }

    val featured = filtered.filter { it.category == "opencode" || it.category == "local" || it.category == "opencode-server" }
    val keyProviders = filtered.filter { it.category == "key" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Провайдеры") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Built-in setup guide for the OpenRouter free-model connection.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Как подключить 12 бесплатных моделей",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Нажми «Получить API-ключ» и открой сайт OpenRouter.\n" +
                            "2. Войди или зарегистрируйся и создай ключ в разделе API Keys.\n" +
                            "3. Скопируй ключ. Не отправляй его другим людям.\n" +
                            "4. Вернись сюда, найди «OpenRouter — 12 бесплатных моделей» в списке провайдеров, открой его, вставь ключ и нажми «Сохранить».\n" +
                            "5. Открой выбор моделей, выбери модель с пометкой Free и отправь сообщение в чате.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { uriHandler.openUri("https://openrouter.ai/settings/keys") }) {
                        Text("Получить API-ключ OpenRouter")
                    }
                    Text(
                        "Важно: бесплатные модели имеют лимиты и могут временно становиться недоступными. Для работы нужен интернет и действующий ключ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "opencode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            featured.forEachIndexed { index, item ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(360, delayMillis = index * 55)) +
                        slideInVertically(tween(420, delayMillis = index * 55)) { it / 8 }
                ) {
                ProviderCard(
                    item = item,
                    connected = !savedKeys[item.id].isNullOrBlank(),
                    onClick = {
                        if (item.id == "local") {
                            onLocalModelsClick()
                        } else {
                            dialogItem = item
                        }
                    }
                )
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Все провайдеры",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(32.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                singleLine = true
            )

            Spacer(Modifier.height(14.dp))

            keyProviders.forEach { item ->
                val connected = !savedKeys[item.id].isNullOrBlank()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dialogItem = item }
                        .padding(vertical = 12.dp)
                ) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connected) "подключён" else "ключ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    dialogItem?.let { item ->
        KeyDialog(
            item = item,
            currentKey = prefs.getString("key_${item.id}", "") ?: "",
            custom = item.category == "key" || item.id in setOf("openrouter-free", "opencode-go", "opencode-zen", "claude", "opencode-server"),
            currentUrl = prefs.getString("base_${item.id}", when (item.id) {
                "opencode-go" -> "https://opencode.ai/zen/go/v1"
                "opencode-zen" -> "https://opencode.ai/zen/v1"
                "claude" -> "https://api.anthropic.com/v1"
                "opencode-server" -> ""
                "openrouter-free" -> "https://openrouter.ai/api/v1"
                else -> ""
            }) ?: "",
            currentModel = prefs.getString("model_${item.id}", when (item.id) { "opencode-server" -> "server-default"; "openrouter-free" -> "selected-model"; else -> "" }) ?: "",
            currentProtocol = prefs.getString("protocol_${item.id}", if (item.id == "claude") "anthropic" else "openai") ?: "openai",
            onSaveCustom = { url, key, model, protocol ->
                prefs.edit().putString("base_${item.id}", url)
                    .putString("key_${item.id}", key)
                    .putString("model_${item.id}", model)
                    .putString("protocol_${item.id}", protocol)
                    .putString("active_provider", item.id).apply()
                savedKeys[item.id] = if (url.isNotBlank() && (item.id == "opencode-server" || (model.isNotBlank() && key.isNotBlank()))) "configured" else ""
                dialogItem = null
            },
            onSave = { key ->
                savedKeys[item.id] = key
                prefs.edit().putString("key_${item.id}", key).apply()
                dialogItem = null
            },
            onDismiss = { dialogItem = null }
        )
    }
}

@Composable
private fun KeyDialog(
    item: ProviderItem,
    currentKey: String,
    custom: Boolean = false,
    currentUrl: String = "",
    currentModel: String = "",
    currentProtocol: String = "openai",
    onSaveCustom: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentKey) }
    var url by remember { mutableStateOf(currentUrl) }
    var model by remember { mutableStateOf(currentModel) }
    var protocol by remember { mutableStateOf(currentProtocol) }
    val isClaude = item.id == "claude"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                Text(
                    if (item.id == "opencode-server") "Укажи адрес запущенного OpenCode Server (например, http://192.168.1.10:4096). Пароль — необязательно, если сервер защищён паролем."
                    else if (item.id == "openrouter-free") "Один API-ключ OpenRouter подключает все 12 бесплатных моделей из списка. Модель выбирается в разделе выбора моделей. Бесплатные варианты ограничены по числу запросов; провайдеры могут быть временно недоступны."
                    else if (custom) "Укажи Base URL, точный ID модели и API-ключ провайдера. OpenCode Go/Zen используют API-ключ из консоли OpenCode; Claude — ключ Anthropic API."
                    else if (isClaude) "Вставь токен Claude Code (Pro или Max)."
                    else "Вставь API-ключ провайдера.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (custom) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (item.id == "opencode-server") "Адрес OpenCode Server" else "Домен / Base URL") }, placeholder = { Text(if (item.id == "opencode-server") "http://192.168.1.10:4096" else "https://api.example.com/v1") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (item.id == "opencode-server") "Модель (не используется — выбери на сервере)" else if (item.id == "openrouter-free") "Модель выбирается в списке YouCode" else "Модель") }, placeholder = { Text(if (item.id == "opencode-server") "server-default" else if (item.id == "openrouter-free") "selected-model" else "точный ID модели из каталога провайдера") }, singleLine = true, readOnly = item.id == "openrouter-free")
                    if (item.id != "opencode-server" && item.id != "openrouter-free") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { protocol = "openai" }) { Text(if (protocol == "openai") "✓ OpenAI API" else "OpenAI API") }
                            TextButton(onClick = { protocol = "anthropic" }) { Text(if (protocol == "anthropic") "✓ Claude API" else "Claude API") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (item.id == "opencode-server") "Пароль OpenCode Server" else if (isClaude) "Токен" else "API-ключ (если нужен)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (custom) onSaveCustom(url.trim(), text.trim(), model.trim(), protocol)
                    else onSave(text.trim())
                },
                enabled = if (item.id == "opencode-server") url.isNotBlank() else if (custom) url.isNotBlank() && model.isNotBlank() && text.isNotBlank() else text.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (currentKey.isNotBlank() || (custom && currentUrl.isNotBlank())) {
                    TextButton(onClick = { if (custom) onSaveCustom("", "", "", "openai") else onSave("") }) { Text("Отключить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

@Composable
private fun ProviderCard(item: ProviderItem, connected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(item.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    Text("подключён", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
