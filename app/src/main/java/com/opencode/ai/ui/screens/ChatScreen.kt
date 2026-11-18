package com.opencode.ai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.opencode.ai.ui.theme.MorphingOrb
import com.opencode.ai.ui.theme.aurora
import com.opencode.ai.ui.theme.entrance
import com.opencode.ai.ui.theme.pressScale
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
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
import com.opencode.ai.data.ReasoningLevel
import com.opencode.ai.data.TraceStep
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Psychology
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
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // id сообщений, которые уже проигрывали анимацию появления (чтобы не повторять при прокрутке)
    val animatedIds = remember { mutableSetOf<String>() }
    val topBarElevation by animateDpAsState(
        targetValue = if (listState.canScrollBackward) 4.dp else 0.dp,
        animationSpec = spring(stiffness = 400f),
        label = "topbar_elevation"
    )
    val canSend = (inputText.isNotBlank() || viewModel.pendingAttachments.isNotEmpty()) &&
        !viewModel.isGenerating

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

    LaunchedEffect(viewModel.liveTrace.size, viewModel.isGenerating) {
        if (viewModel.isGenerating && viewModel.messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Material You expressive top app bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = topBarElevation
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Меню",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        "YouCode",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (viewModel.engineState) {
                            "starting" -> "Запускаю встроенный OpenCode…"
                            "error" -> "OpenCode не запустился"
                            "missing" -> "Встроенный OpenCode недоступен на этом устройстве"
                            else -> "Твой AI-помощник"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (viewModel.engineState == "error") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot_pulse")
                val dotScale by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = if (viewModel.isGenerating) 1.9f else 1f,
                    animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "dot_scale"
                )
                Surface(
                    onClick = onModelClick,
                    modifier = Modifier.pressScale(0.94f),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .graphicsLayer {
                                    scaleX = dotScale
                                    scaleY = dotScale
                                }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(7.dp))
                        AnimatedContent(
                            targetState = "${viewModel.currentMode.emoji} ${viewModel.currentModel.name}",
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(spring(stiffness = 500f)) { it / 2 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically(spring(stiffness = 500f)) { -it / 2 })
                            },
                            label = "model_chip_text"
                        ) { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "Выбрать модель",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // Messages
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (viewModel.messages.isEmpty()) {
                EmptyChatState(onSuggestion = { viewModel.sendMessage(it) })
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(viewModel.messages, key = { it.id }) { msg ->
                        val isNew = remember(msg.id) { animatedIds.add(msg.id) }
                        MessageBubble(
                            message = msg,
                            modifier = Modifier
                                .animateItem(fadeInSpec = null)
                                .entrance(
                                    enabled = isNew,
                                    fromX = if (msg.isUser) 40.dp else (-40).dp,
                                    fromY = 18.dp
                                ),
                            onSaveFileClick = { file -> createDocumentLauncher.launch(file.name) }
                        )
                    }
                    if (viewModel.isGenerating) {
                        item(key = "live_trace") {
                            LiveTrace(
                                steps = viewModel.liveTrace,
                                status = viewModel.generationStatus,
                                modifier = Modifier.padding(end = 20.dp)
                            )
                        }
                    }
                }

                // Кнопка «вниз» появляется, если лента не у конца
                androidx.compose.animation.AnimatedVisibility(
                    visible = listState.canScrollForward,
                    enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 400f)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Вниз")
                    }
                }
            }
        }

        // Pending attachments

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

        // Input: сверху текст, снизу ряд: вложение · разум · папка · отправка
        var showReasoningMenu by remember { mutableStateOf(false) }
        var showFolderMenu by remember { mutableStateOf(false) }
        val sendContainer by animateColorAsState(
            targetValue = if (canSend) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            animationSpec = spring(stiffness = 400f),
            label = "send_container"
        )
        val sendContent by animateColorAsState(
            targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            animationSpec = spring(stiffness = 400f),
            label = "send_content"
        )
        val chipColor = MaterialTheme.colorScheme.onSurfaceVariant
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 1.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputText.isEmpty()) {
                                Text(
                                    "Сообщение",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    // Вложение
                    IconButton(
                        onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.pressScale(0.88f).size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AddPhotoAlternate,
                            contentDescription = "Прикрепить файл",
                            tint = chipColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Разум
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showReasoningMenu = true }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Psychology,
                                contentDescription = "Разум",
                                tint = if (viewModel.reasoningLevel == ReasoningLevel.AUTO) chipColor
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                viewModel.reasoningLevel.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (viewModel.reasoningLevel == ReasoningLevel.AUTO)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = showReasoningMenu,
                            onDismissRequest = { showReasoningMenu = false },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            ReasoningLevel.values().forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.label) },
                                    trailingIcon = {
                                        if (level == viewModel.reasoningLevel) {
                                            Icon(Icons.Default.Check, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectReasoning(level)
                                        showReasoningMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Папка (показывает имя выбранной папки)
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (viewModel.workingFolderName != null) showFolderMenu = true
                                    else openTreeLauncher.launch(null)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = "Рабочая папка",
                                tint = if (viewModel.workingFolderName != null) MaterialTheme.colorScheme.primary
                                else chipColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                viewModel.workingFolderName ?: "Папка",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = showFolderMenu,
                            onDismissRequest = { showFolderMenu = false },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Сменить папку") },
                                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                onClick = {
                                    showFolderMenu = false
                                    openTreeLauncher.launch(null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Отвязать папку") },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                onClick = {
                                    showFolderMenu = false
                                    viewModel.clearWorkingFolder()
                                }
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    val sendScale by animateFloatAsState(
                        targetValue = if (canSend) 1f else 0.92f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
                        label = "send_scale"
                    )
                    FilledIconButton(
                        onClick = {
                            if (canSend) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sendContainer,
                            contentColor = sendContent,
                            disabledContainerColor = sendContainer,
                            disabledContentColor = sendContent
                        ),
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = sendScale
                                scaleY = sendScale
                            }
                            .pressScale(0.86f)
                            .size(44.dp),
                        shape = CircleShape
                    ) {
                        AnimatedContent(
                            targetState = viewModel.isGenerating,
                            transitionSpec = {
                                (scaleIn(spring(dampingRatio = 0.5f, stiffness = 400f)) + fadeIn()) togetherWith
                                    (scaleOut() + fadeOut())
                            },
                            label = "send_icon"
                        ) { generating ->
                            if (generating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "💡 Объясни код" to "Объясни, как работает этот код",
        "🐞 Найди баг" to "Помоги найти баг в моём коде",
        "📝 Напиши скрипт" to "Напиши небольшой скрипт",
        "📁 Разбери файл" to "Разбери прикреплённый файл"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MorphingOrb(modifier = Modifier.entrance(index = 0, fromY = 40.dp), size = 104.dp) {
            Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }

        Spacer(Modifier.height(36.dp))

        Text("Чем помочь?", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.entrance(index = 1))

        Spacer(Modifier.height(12.dp))

        Text(
            "Бесплатные модели работают сразу.\nМогу читать и сохранять файлы.\nДля мощных моделей подключи провайдера.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.entrance(index = 2)
        )

        Spacer(Modifier.height(28.dp))

        suggestions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEachIndexed { colIndex, (label, prompt) ->
                    Surface(
                        onClick = { onSuggestion(prompt) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .entrance(index = 3 + rowIndex * 2 + colIndex)
                            .pressScale(0.93f)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onSaveFileClick: (AttachedFile) -> Unit
) {
    val isUser = message.isUser
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 26.dp, topEnd = 26.dp,
                bottomStart = if (isUser) 26.dp else 8.dp,
                bottomEnd = if (isUser) 8.dp else 26.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            else Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 360.dp).animateContentSize()
        ) {
            Column(modifier = Modifier.padding(if (isUser) 14.dp else 2.dp)) {
                if (!isUser && message.modelName != null) {
                    Text(message.modelName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                }

                if (!isUser && message.trace.isNotEmpty()) {
                    TraceSummary(message.trace)
                    Spacer(Modifier.height(8.dp))
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
        modifier = Modifier.fillMaxWidth().pressScale(0.97f)
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

private fun thinkingLabel(step: TraceStep, now: Long): String {
    val end = step.endedAt ?: now
    val sec = ((end - step.startedAt) / 1000L).coerceAtLeast(0L)
    return when {
        step.isRunning -> if (sec >= 1) "Думаю $sec с" else "Думаю…"
        sec >= 1 -> "Думал $sec с"
        else -> "Думал"
    }
}

private fun toolsWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "инструментов"
        m10 == 1 -> "инструмент"
        m10 in 2..4 -> "инструмента"
        else -> "инструментов"
    }
}

/** Ход работы, пока ответ ещё генерируется: это обычный элемент ленты, он ничего не перекрывает. */
@Composable
private fun LiveTrace(steps: List<TraceStep>, status: String, modifier: Modifier = Modifier) {
    val shown = steps.takeLast(6)
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (steps.size > shown.size) {
            Text(
                "Ещё шагов выше: ${steps.size - shown.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        shown.forEach { step ->
            key(step.id) { TraceStepRow(step = step, live = true) }
        }
        if (shown.none { it.isRunning }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Свёрнутая строка над готовым ответом: «Думал 12 с · 3 инструмента». */
@Composable
private fun TraceSummary(steps: List<TraceStep>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val thinking = steps.filter { it.kind == "thinking" }
    val thinkSec = thinking.sumOf { ((it.endedAt ?: it.startedAt) - it.startedAt).coerceAtLeast(0L) } / 1000L
    val tools = steps.count { it.kind == "tool" }
    val label = buildList {
        if (thinking.isNotEmpty()) add(if (thinkSec >= 1) "Думал $thinkSec с" else "Думал")
        if (tools > 0) add("$tools ${toolsWord(tools)}")
    }.joinToString(" · ").ifBlank { "Ход работы" }
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { open = !open }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Outlined.Psychology, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { step ->
                    key(step.id) { TraceStepRow(step = step, live = false) }
                }
            }
        }
    }
}

@Composable
private fun TraceStepRow(step: TraceStep, live: Boolean, modifier: Modifier = Modifier) {
    val isThinking = step.kind == "thinking"
    val running = live && step.isRunning
    // null = «авто»: пока модель думает, текст раскрыт; после окончания сворачивается
    var userToggle by remember(step.id) { mutableStateOf<Boolean?>(null) }
    val expanded = userToggle ?: (running && isThinking)
    val hasText = step.text.isNotBlank()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    if (isThinking) {
        Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = hasText) { userToggle = !expanded }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Outlined.Psychology, contentDescription = null,
                    tint = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    thinkingLabel(step, now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                if (hasText) {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (expanded && hasText) {
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                val scroll = rememberScrollState()
                LaunchedEffect(step.text.length, running) {
                    if (running) scroll.scrollTo(scroll.maxValue)
                }
                Box(
                    modifier = Modifier
                        .padding(start = 14.dp, top = 2.dp, bottom = 4.dp)
                        .heightIn(max = 180.dp)
                        .drawBehind {
                            drawLine(
                                color = lineColor,
                                start = Offset(1.dp.toPx(), 0f),
                                end = Offset(1.dp.toPx(), size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                ) {
                    Text(
                        step.text.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .verticalScroll(scroll)
                            .padding(start = 14.dp)
                    )
                }
            }
        }
    } else {
        val isError = step.status == "error"
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier.fillMaxWidth().animateContentSize()
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hasText) { userToggle = !expanded }
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                ) {
                    when {
                        running -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        isError -> Icon(
                            Icons.Outlined.ErrorOutline, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                        )
                        else -> Icon(
                            Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        step.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasText) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (expanded && hasText) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    ) {
                        Text(
                            step.text.trim(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            softWrap = false
                        )
                    }
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
