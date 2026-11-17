package com.opencode.ai.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.ai.data.AttachedFile
import com.opencode.ai.data.ChatMessage
import com.opencode.ai.viewmodel.ChatViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    onModelClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }

        val name = queryDisplayName(context, uri) ?: "file"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        var preview = ""
        var size = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                size = bytes.size.toLong()
                if (mime.startsWith("text") ||
                    name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".py") ||
                    name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".json") ||
                    name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".xml") ||
                    name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".gradle") ||
                    name.endsWith(".kts")
                ) {
                    preview = String(bytes).take(2500)
                }
            }
        } catch (_: Exception) { }

        viewModel.addAttachment(
            AttachedFile(
                id = UUID.randomUUID().toString(),
                name = name,
                uri = uri,
                mimeType = mime,
                sizeBytes = size,
                contentPreview = preview
            )
        )
    }


    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }

        val tree = DocumentFile.fromTreeUri(context, uri)
        val name = tree?.name ?: "Папка"
        val files = tree?.listFiles()?.mapNotNull { it.name }?.filter { !it.startsWith(".") }?.take(30) ?: emptyList()
        viewModel.setWorkingFolder(uri, name, files)
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val lastSaved = viewModel.messages
            .asReversed()
            .flatMap { it.attachments }
            .firstOrNull { it.isSavedByAi }

        if (lastSaved != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(lastSaved.contentPreview.toByteArray())
                }
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top bar
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Меню",
                        tint = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    onClick = onModelClick,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${viewModel.currentMode.emoji} ${viewModel.currentModel.name}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ExpandMore, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }
        }

        // Messages
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (viewModel.messages.isEmpty()) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(viewModel.messages, key = { it.id }) { msg ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 12 }
                        ) {
                            MessageBubble(
                                message = msg,
                                onSaveFileClick = { file -> createDocumentLauncher.launch(file.name) }
                            )
                        }
                    }
                    if (viewModel.isGenerating) {
                        item { TypingIndicator() }
                    }
                }
            }
        }

        // Pending attachments

        // Working folder chip
        if (viewModel.workingFolderName != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📁 ${viewModel.workingFolderName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (viewModel.workingFolderFiles.isNotEmpty()) {
                            Text(
                                viewModel.workingFolderFiles.take(4).joinToString(", ") +
                                    if (viewModel.workingFolderFiles.size > 4) "…" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.clearWorkingFolder() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Отвязать папку",
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = viewModel.pendingAttachments.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                viewModel.pendingAttachments.forEach { file ->
                    PendingFileChip(file = file, onRemove = { viewModel.removePendingAttachment(file.id) })
                }
            }
        }

        // Input: floating pill
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(32.dp),
            tonalElevation = 2.dp,
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
            ) {
                IconButton(
                    onClick = { openTreeLauncher.launch(null) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (viewModel.workingFolderName != null)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = "Рабочая папка",
                        modifier = Modifier.size(22.dp))
                }

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить файл",
                        modifier = Modifier.size(22.dp))
                }

                Spacer(Modifier.width(4.dp))

                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Сообщение или команда…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    maxLines = 6
                )

                Spacer(Modifier.width(4.dp))

                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank() || viewModel.pendingAttachments.isNotEmpty()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = (inputText.isNotBlank() || viewModel.pendingAttachments.isNotEmpty()) &&
                            !viewModel.isGenerating,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить",
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }

        Spacer(Modifier.height(32.dp))

        Text("Чем помочь?", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(12.dp))

        Text(
            "Бесплатные модели работают сразу.\nМогу читать и сохранять файлы.\nДля мощных моделей подключи провайдера.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSaveFileClick: (AttachedFile) -> Unit) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 340.dp).animateContentSize()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (!isUser && message.modelName != null) {
                    Text(message.modelName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )

                if (message.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    message.attachments.forEach { file ->
                        FileChip(file = file, onClick = { if (file.isSavedByAi) onSaveFileClick(file) })
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileChip(file: AttachedFile, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (file.isSavedByAi) Icons.Outlined.Download else Icons.Outlined.InsertDriveFile,
                contentDescription = null, modifier = Modifier.size(18.dp),
                tint = if (file.isSavedByAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (file.isSavedByAi) {
                    Text("Нажми, чтобы сохранить", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (file.sizeBytes > 0) {
                    Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PendingFileChip(file: AttachedFile, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(file.name, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Убрать", modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infinite = rememberInfiniteTransition(label = "typing")
    val alpha1 by infinite.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "a1")
    val alpha2 by infinite.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, 150), RepeatMode.Reverse), "a2")
    val alpha3 by infinite.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, 300), RepeatMode.Reverse), "a3")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(alpha1, alpha2, alpha3).forEach { a ->
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).alpha(a)
                        .background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
