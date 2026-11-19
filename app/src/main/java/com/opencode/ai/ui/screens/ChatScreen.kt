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
import com.opencode.ai.ui.theme.entrance
import com.opencode.ai.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import com.opencode.ai.data.MsgPart
import com.opencode.ai.data.OcAsk
import com.opencode.ai.ui.components.Markdown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Terminal
import com.opencode.ai.ui.components.Expressive
import com.opencode.ai.ui.components.ExpressiveCard
import com.opencode.ai.ui.components.ExpressiveIconBadge
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
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Asks(viewModel)
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

        // Файл читаем не на главном потоке и с ограничением размера: большой файл не вешает приложение.
        scope.launch {
            val (size, preview) = withContext(Dispatchers.IO) { readAttachment(context, uri, name, mime) }
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

    // Keep the thinking block and newest messages in view; avoids the indicator covering a bubble.
    LaunchedEffect(viewModel.messages.size, viewModel.isGenerating, viewModel.generationStatus, viewModel.liveShown().sumOf { when (it) { is MsgPart.Text -> it.text.length; is MsgPart.Reasoning -> it.text.length; else -> 1 } } / 200) {
        if (viewModel.messages.isEmpty() && !viewModel.isGenerating) return@LaunchedEffect
        val target = (viewModel.messages.size - 1 + if (viewModel.isGenerating) 1 else 0).coerceAtLeast(0)
        // Если человек читает историю выше, ленту не дёргаем: иначе каждый статус возвращал вниз.
        val info = listState.layoutInfo
        val nearBottom = info.totalItemsCount == 0 ||
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        if (nearBottom) listState.animateScrollToItem(target)
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
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
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
                            targetState = viewModel.currentModel.name,
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
                        item(key = "typing_indicator") {
                            GenerationActivity(
                                status = viewModel.generationStatus,
                                parts = viewModel.liveShown(),
                                modifier = Modifier
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .padding(top = 6.dp, bottom = 8.dp)
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

        // Working folder chip
        if (viewModel.workingFolderName != null) {
            Surface(
                shape = RoundedCornerShape(Expressive.CardSmall),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
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

        // Input: одна «таблетка» как в Claude: «+» слева, поле, круглая кнопка справа
        var showPlusMenu by remember { mutableStateOf(false) }
        val plusRotation by animateFloatAsState(
            targetValue = if (showPlusMenu) 45f else 0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
            label = "plus_rotation"
        )
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 1.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
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
                Box {
                    IconButton(
                        onClick = { showPlusMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (viewModel.workingFolderName != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.pressScale(0.88f).size(44.dp).clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Добавить",
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { rotationZ = plusRotation }
                        )
                    }
                    DropdownMenu(
                        expanded = showPlusMenu,
                        onDismissRequest = { showPlusMenu = false },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Прикрепить файл") },
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                            onClick = {
                                showPlusMenu = false
                                openDocumentLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(viewModel.workingFolderName?.let { "Папка: $it" } ?: "Рабочая папка") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = {
                                showPlusMenu = false
                                openTreeLauncher.launch(null)
                            }
                        )
                    }
                }

                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputText.isEmpty()) {
                                Text(
                                    "Напиши сообщение…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                val sendScale by animateFloatAsState(
                    targetValue = if (canSend) 1f else 0.92f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
                    label = "send_scale"
                )
                FilledIconButton(
                    onClick = {
                        if (viewModel.isGenerating) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.stopGeneration()
                        } else if (canSend) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = canSend || viewModel.isGenerating,
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
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Остановить",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Отправить",
                                modifier = Modifier.size(24.dp)
                            )
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
        "Объясни код" to "Объясни, как работает этот код",
        "Найди баг" to "Помоги найти баг в моём коде",
        "Напиши скрипт" to "Напиши небольшой скрипт",
        "Разбери файл" to "Разбери прикреплённый файл"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(88.dp)
                .entrance(index = 0, fromY = 24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Чем помочь?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.entrance(index = 1)
        )

        Spacer(Modifier.height(10.dp))

        Text(
            "Бесплатные модели работают сразу.\nМогу читать и сохранять файлы.\nДля мощных моделей подключи провайдера.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.entrance(index = 2)
        )

        Spacer(Modifier.height(28.dp))

        val suggestionIcons = listOf(
            Icons.Outlined.Lightbulb, Icons.Outlined.BugReport,
            Icons.Outlined.Terminal, Icons.Outlined.Description
        )
        suggestions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEachIndexed { colIndex, (label, prompt) ->
                    ExpressiveCard(
                        onClick = { onSuggestion(prompt) },
                        shape = RoundedCornerShape(Expressive.CardSmall),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .entrance(index = 3 + rowIndex * 2 + colIndex)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            ExpressiveIconBadge(
                                icon = suggestionIcons[(rowIndex * 2 + colIndex) % suggestionIcons.size],
                                size = 40.dp,
                                shape = RoundedCornerShape(14.dp),
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                topStart = Expressive.Card, topEnd = Expressive.Card,
                bottomStart = if (isUser) Expressive.Card else Expressive.Joint,
                bottomEnd = if (isUser) Expressive.Joint else Expressive.Card
            ),
            color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
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

                if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 22.sp
                    )
                } else if (message.parts.isNotEmpty()) {
                    AssistantParts(message.parts, live = false)
                } else if (message.content.isNotBlank()) {
                    SelectionContainer { Markdown(message.content.trim()) }
                }
                if (!isUser && message.error != null) {
                    Spacer(Modifier.height(6.dp))
                    ErrorCard(message.error)
                }
                if (!isUser && message.content.isNotBlank()) {
                    val clipboard = LocalClipboardManager.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(message.content.trim())) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy, "Копировать", Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (message.tokensOut > 0) {
                            Text(
                                "${message.tokensIn} → ${message.tokensOut}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }

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
    ExpressiveCard(
        onClick = onClick,
        shape = RoundedCornerShape(Expressive.CardSmall),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExpressiveIconBadge(
                icon = if (file.isSavedByAi) Icons.Outlined.Download else Icons.Outlined.InsertDriveFile,
                size = 36.dp,
                shape = RoundedCornerShape(12.dp),
                container = if (file.isSavedByAi) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                tint = if (file.isSavedByAi) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(12.dp))
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
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
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

/** Индикатор работы и то, что модель делает сейчас: рассуждения, инструменты, текст — как у Margy. */
@Composable
private fun GenerationActivity(status: String, parts: List<MsgPart>, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "activity")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "activity_rotation"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                progress = { 0.72f },
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                status.ifBlank { "Подключаюсь…" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AssistantParts(parts, live = true)
    }
}

/** Части ответа: рассуждения, карточки инструментов и текст. Одно и то же для живого и готового ответа. */
@Composable
private fun AssistantParts(parts: List<MsgPart>, live: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, p ->
            when (p) {
                is MsgPart.Reasoning -> Thoughts(p, live && i == parts.lastIndex)
                is MsgPart.Text -> {
                    // Некоторые модели пишут рассуждения прямо в ответ между тегами <think>.
                    val t = p.text.trimStart()
                    if (t.startsWith("<think>")) {
                        val end = t.indexOf("</think>")
                        val thought = if (end < 0) t.removePrefix("<think>") else t.substring(7, end)
                        Thoughts(MsgPart.Reasoning(thought), live && end < 0)
                        val rest = if (end < 0) "" else t.substring(end + 8).trim()
                        if (rest.isNotEmpty()) SelectionContainer { Markdown(rest) }
                    } else if (p.text.isNotBlank()) {
                        SelectionContainer { Markdown(p.text.trim()) }
                    }
                }
                is MsgPart.Tool -> ToolCard(p)
            }
        }
    }
}

@Composable
private fun Thoughts(p: MsgPart.Reasoning, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    if (p.text.isBlank()) return
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (live) "Думает…"
                else if (p.ms > 0) "Думал ${"%.1f".format(p.ms / 1000.0)} с"
                else "Размышления",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.animation.AnimatedVisibility(open || live) {
            Row(Modifier.height(IntrinsicSize.Min).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                Box(Modifier.padding(end = 12.dp).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Text(
                    if (live && !open) p.text.takeLast(600) else p.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (live && !open) 6 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Что сделал инструмент, одной строкой; детали раскрываются по нажатию. */
@Composable
private fun ToolCard(t: MsgPart.Tool) {
    var open by remember { mutableStateOf(false) }
    ExpressiveCard(
        onClick = { open = !open },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(Expressive.CardSmall),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (t.status) {
                    "completed" -> Icon(Icons.Outlined.TaskAlt, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    "error" -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (t.arg != null) "${t.name}: ${t.arg}" else t.name,
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f)
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.animation.AnimatedVisibility(open) {
                val body = listOfNotNull(t.input.ifBlank { null }, t.error, t.output.ifBlank { null }).joinToString("\n\n")
                Text(
                    body.take(6000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(e: String) {
    ExpressiveCard(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(Expressive.CardSmall),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/** OpenCode спрашивает разрешения, прежде чем инструмент что-то изменит. */
@Composable
private fun Asks(viewModel: ChatViewModel) {
    val ask: OcAsk = viewModel.asks.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.Build, null) },
        title = { Text("Разрешить: ${ask.action}?") },
        text = {
            Column {
                ask.message?.let { Text(it) }
                for (r in ask.resources.take(6)) Text(
                    r, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            Row {
                TextButton({ viewModel.replyAsk(ask, "always") }) { Text("Всегда") }
                TextButton({ viewModel.replyAsk(ask, "once") }) { Text("Разрешить") }
            }
        },
        dismissButton = { TextButton({ viewModel.replyAsk(ask, "reject") }) { Text("Запретить") } }
    )
}

private const val MAX_ATTACH_READ_BYTES = 256 * 1024
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "html", "css",
    "gradle", "yml", "yaml", "toml", "sh", "c", "cpp", "h", "rs", "go", "csv", "sql", "properties"
)

/** Размер файла и текстовое превью (до 12000 символов). Читает не больше 256 КБ. */
private fun readAttachment(context: android.content.Context, uri: Uri, name: String, mime: String): Pair<Long, String> {
    var size = 0L
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) size = c.getLong(i)
        }
    } catch (_: Exception) { }
    val isText = mime.startsWith("text") || name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
    if (!isText) return size to ""
    return try {
        val out = java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val chunk = ByteArray(8192)
            var total = 0
            while (total < MAX_ATTACH_READ_BYTES) {
                val n = stream.read(chunk, 0, minOf(chunk.size, MAX_ATTACH_READ_BYTES - total))
                if (n <= 0) break
                out.write(chunk, 0, n)
                total += n
            }
        }
        val bytes = out.toByteArray()
        (if (size > 0) size else bytes.size.toLong()) to String(bytes, Charsets.UTF_8).take(12000)
    } catch (_: Exception) { size to "" }
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
