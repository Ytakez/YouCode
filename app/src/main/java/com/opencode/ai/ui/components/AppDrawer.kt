package com.opencode.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.ai.data.ChatSession

@Composable
fun AppDrawer(
    sessions: List<ChatSession>,
    onNewChat: () -> Unit,
    onSessionClick: (ChatSession) -> Unit,
    onProvidersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onModelsClick: () -> Unit,
    onSkillsClick: () -> Unit = {},
    onLocalModelsClick: () -> Unit = {}
) {
    val startOfToday = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = sessions.filter { it.timestamp >= startOfToday }
    val earlier = sessions.filter { it.timestamp < startOfToday }

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header / Logo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "YouCode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // New chat
            DrawerItem(
                icon = Icons.Default.Add,
                label = "Новый чат",
                onClick = onNewChat,
                highlighted = true
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Chat history: scrollable area that takes all free space,
            // so the bottom buttons are always visible
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (today.isNotEmpty()) {
                    Text(
                        "Сегодня",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    today.forEach { session ->
                        SessionItem(session = session, onClick = { onSessionClick(session) })
                    }
                }
                if (earlier.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Раньше",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    earlier.forEach { session ->
                        SessionItem(session = session, onClick = { onSessionClick(session) })
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))

            // Bottom actions (always on screen)
            DrawerItem(
                icon = Icons.Outlined.CloudUpload,
                label = "Загрузить диалог",
                onClick = { }
            )
            DrawerItem(
                icon = Icons.Outlined.Speed,
                label = "Лимиты",
                onClick = { }
            )
            DrawerItem(
                icon = Icons.Outlined.Extension,
                label = "Скиллы и MCP",
                onClick = onSkillsClick
            )
            DrawerItem(
                icon = Icons.Outlined.Storage,
                label = "Провайдеры и модели",
                onClick = onProvidersClick
            )
            DrawerItem(
                icon = Icons.Outlined.Settings,
                label = "Настройки",
                onClick = onSettingsClick
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onClick: () -> Unit
) {
    Text(
        text = session.title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
