package com.opencode.ai.ui.screens

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.ai.data.SampleData
import com.opencode.ai.data.SmartMode
import com.opencode.ai.ui.theme.entrance
import com.opencode.ai.ui.theme.pressScale
import com.opencode.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: ChatViewModel? = null, onSignOut: (() -> Unit)? = null) {
    var showModePicker by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showWipeChats by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf<String?>(null) }
    var configText by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    val currentMode = viewModel?.currentMode
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        if (viewModel != null) {
            rules = withContext(Dispatchers.IO) { viewModel.loadAgentRules() }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // —— Оформление ——
            SectionTitle("Оформление")
            if (viewModel != null) {
                val themeOptions = listOf(
                    "system" to "Система",
                    "light" to "Светлая",
                    "dark" to "Тёмная"
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    themeOptions.forEachIndexed { index, (id, label) ->
                        SegmentedButton(
                            selected = viewModel.themeMode == id,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setThemeMode(id)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(label) }
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Цвета обоев", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Material You: палитра из обоев вместо фиксированной",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = viewModel.dynamicColor,
                                onCheckedChange = { viewModel.setDynamicColor(it) },
                                thumbContent = if (viewModel.dynamicColor) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // —— Режим / модель ——
            SectionTitle("Персонализация")
            Surface(
                onClick = { showModePicker = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth().pressScale(0.97f).animateContentSize()
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Режим работы", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentMode?.name ?: "Выбрать режим", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(currentMode?.description ?: "Нажми, чтобы выбрать", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(viewModel?.currentModel?.name ?: "Модель не выбрана", fontWeight = FontWeight.Medium)
                        Text(
                            viewModel?.currentModel?.provider ?: "Выбери модель в меню чата",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // —— Правила AGENTS.md ——
            SectionTitle("Правила для модели")
            Text(
                "Общий AGENTS.md opencode: что модель должна помнить в каждом чате.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (viewModel != null && rules != null) {
                OutlinedTextField(
                    value = rules!!,
                    onValueChange = { rules = it },
                    minLines = 3,
                    maxLines = 12,
                    placeholder = { Text("Например: отвечай кратко и по-русски") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    viewModel.saveAgentRules(rules.orEmpty())
                    statusMsg = "Правила сохранены"
                }) { Text("Сохранить правила") }
            }

            // —— Файлы ——
            SectionTitle("Файлы")
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Папка для новых чатов", fontWeight = FontWeight.Medium)
                    Text(
                        viewModel?.folderLabel() ?: "Папка приложения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Меняется кнопкой «Папка» в чате (как в Margy).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // —— Данные / OpenCode ——
            SectionTitle("Данные")
            if (viewModel != null) {
                OutlinedButton(
                    shape = RoundedCornerShape(28.dp),
                    onClick = {
                        configText = viewModel.loadOpenCodeConfig()
                        showConfigDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Настройки opencode (opencode.json)") }

                OutlinedButton(
                    shape = RoundedCornerShape(28.dp),
                    onClick = {
                        viewModel.restartOpenCodeEngine { err ->
                            statusMsg = err?.let { "Ошибка перезапуска: $it" } ?: "OpenCode перезапущен"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Перезапустить opencode") }

                OutlinedButton(
                    shape = RoundedCornerShape(28.dp),
                    onClick = { showWipeChats = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Удалить все чаты") }
            }

            statusMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (onSignOut != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    shape = RoundedCornerShape(28.dp),
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Выйти из аккаунта") }
            }

            Text(
                "YouCode · версия ${com.opencode.ai.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
            )
        }
    }

    if (showConfigDialog && viewModel != null) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("opencode.json") },
            text = {
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveOpenCodeConfig(configText) { err ->
                        statusMsg = err?.let { "JSON: $it" } ?: "opencode.json сохранён"
                        showConfigDialog = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showWipeChats && viewModel != null) {
        AlertDialog(
            onDismissRequest = { showWipeChats = false },
            title = { Text("Удалить все чаты?") },
            text = { Text("История на этом устройстве будет стёрта. OpenCode и правила останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllChats()
                    showWipeChats = false
                    statusMsg = "Чаты удалены"
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeChats = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showSignOutDialog && onSignOut != null) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Чаты и настройки провайдеров на этом устройстве будут удалены.") },
            confirmButton = {
                TextButton(onClick = { showSignOutDialog = false; onSignOut() }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text("Выбери режим") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SampleData.smartModes.forEach { mode ->
                        ModeOption(mode, selected = currentMode?.id == mode.id) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel?.selectMode(mode)
                            showModePicker = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModePicker = false }) { Text("Закрыть") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp)
    )
}

@Composable
private fun ModeOption(mode: SmartMode, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(mode.name, fontWeight = FontWeight.Medium)
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
