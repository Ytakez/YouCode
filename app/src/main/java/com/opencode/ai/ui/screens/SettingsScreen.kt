package com.opencode.ai.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.opencode.ai.ui.theme.aurora
import com.opencode.ai.ui.theme.entrance
import com.opencode.ai.ui.theme.pressScale
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.ai.data.SampleData
import com.opencode.ai.data.SmartMode
import com.opencode.ai.viewmodel.ChatViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: ChatViewModel? = null) {
    var showModePicker by remember { mutableStateOf(false) }
    val currentMode = viewModel?.currentMode
    val haptic = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().aurora(0.8f).padding(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Персонализация", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.entrance(0))
            Text("Выбери, как YouCode будет подбирать модель для ответов.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Surface(
                onClick = { showModePicker = true },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
                modifier = Modifier.fillMaxWidth().entrance(1).pressScale(0.97f).animateContentSize()
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        AnimatedContent(
                            targetState = currentMode?.emoji ?: "✨",
                            transitionSpec = {
                                (scaleIn(spring(dampingRatio = 0.45f, stiffness = 300f), initialScale = 0.4f) + fadeIn()) togetherWith
                                    (scaleOut(targetScale = 1.4f) + fadeOut())
                            },
                            label = "mode_emoji"
                        ) { emoji ->
                            Text(emoji, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Режим работы", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentMode?.name ?: "Выбрать режим", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(currentMode?.description ?: "Нажми, чтобы выбрать", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = "Выбрать режим", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Text("Текущая модель", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp).entrance(2))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().entrance(3)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(viewModel?.currentModel?.name ?: "Модель не выбрана", fontWeight = FontWeight.Medium)
                        Text(viewModel?.currentModel?.provider ?: "Выбери модель в меню чата",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Оформление", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.entrance(4))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().entrance(5)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Material You", fontWeight = FontWeight.Medium)
                    Text("Динамические цвета системы и скруглённые поверхности.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("YouCode · версия ${com.opencode.ai.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text("Выбери режим") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SampleData.smartModes.forEach { mode ->
                        ModeOption(mode, selected = currentMode?.id == mode.id) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel?.selectMode(mode)
                            showModePicker = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModePicker = false }) { Text("Закрыть") } },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun ModeOption(mode: SmartMode, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = spring(stiffness = 400f),
        label = "mode_option_color"
    )
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().pressScale(0.96f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mode.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
