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
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Image as OutlinedImageIcon
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    var showFolderDialog by remember { mutableStateOf(false) }
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
        !viewModel.isGenerating && !viewModel.isOpenCodeLoading

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


    
    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            scope.launch {
                val name = queryDisplayName(context, uri) ?: "photo.jpg"
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val (size, preview) = withContext(Dispatchers.IO) {
                    // Для фото preview короткий; байты не читаем целиком в строку
                    val len = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { inp ->
                            var n = 0L
                            val buf = ByteArray(8192)
                            while (true) {
                                val r = inp.read(buf)
                                if (r <= 0) break
                                n += r
                                if (n > 12_000_000) break
                            }
                            n
                        } ?: 0L
                    }.getOrDefault(0L)
                    len to "[Изображение]"
                }
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
    LaunchedEffect(viewModel.messages.size, viewModel.isGenerating, viewModel.generationStatus, viewModel.generationActivities.size, viewModel.liveReasoning.length) {
        if (viewModel.messages.isEmpty() && !viewModel.isGenerating) return@LaunchedEffect
        val target = (viewModel.messages.size - 1 + if (viewModel.isGenerating) 1 else 0).coerceAtLeast(0)
        // Если человек читает историю выше, ленту не дёргаем: иначе каждый статус возвращал вниз.
        val info = listState.layoutInfo
        val nearBottom = info.totalItemsCount == 0 ||
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        if (nearBottom) listState.animateScrollToItem(target)
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    shape = RoundedCornerShape(26.dp),
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
                            targetState = if (viewModel.isOpenCodeLoading) viewModel.openCodeLoadingLabel.ifBlank { "OpenCode…" } else viewModel.currentModel.name,
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
                                activities = viewModel.generationActivities,
                                reasoning = viewModel.liveReasoning,
                                reasoningLive = viewModel.isReasoningLive,
                                elapsedSec = viewModel.generationElapsedSec,
                                etaSec = viewModel.generationEtaSec,
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(32.dp),
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Column(Modifier.padding(6.dp)) {
                // Поле ввода
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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

                // Нижний ряд: + · папка · отправить
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box {
                        IconButton(
                            onClick = { showPlusMenu = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.pressScale(0.88f).size(44.dp).clip(CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Добавить",
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { rotationZ = plusRotation }
                            )
                        }
                        DropdownMenu(
                            expanded = showPlusMenu,
                            onDismissRequest = { showPlusMenu = false },
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Фото") },
                                leadingIcon = { Icon(Icons.Outlined.OutlinedImageIcon, contentDescription = null) },
                                onClick = {
                                    showPlusMenu = false
                                    pickPhotoLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Прикрепить файл") },
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                onClick = {
                                    showPlusMenu = false
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сгенерировать изображение") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    showPlusMenu = false
                                    if (inputText.isBlank()) inputText = "Нарисуй "
                                    else if (!inputText.lowercase().contains("нарисуй") &&
                                        !inputText.lowercase().contains("сгенерируй")
                                    ) {
                                        inputText = "Нарисуй: $inputText"
                                    }
                                }
                            )
                        }
                    }

                    // Режим мышления (как «Fast» в Claude): как глубоко думает ИИ
                    var showThinkingMenu by remember { mutableStateOf(false) }
                    val level = thinkingLevels.firstOrNull { it.id == viewModel.thinkingLevel } ?: thinkingLevels[1]
                    Box(Modifier.padding(start = 6.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .pressScale(0.94f)
                                .clip(RoundedCornerShape(50))
                                .clickable { showThinkingMenu = true }
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(level.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(
                                    level.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = "Режим мышления",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showThinkingMenu,
                            onDismissRequest = { showThinkingMenu = false },
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            thinkingLevels.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(item.title, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                item.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                                    trailingIcon = {
                                        if (item.id == viewModel.thinkingLevel) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.applyThinkingLevel(item.id)
                                        showThinkingMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Чип папки
                    Surface(
                        color = if (viewModel.workingFolderName != null)
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { showFolderDialog = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            viewModel.workingFolderName?.let { folderName ->
                                Text(
                                    folderName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .widthIn(max = 90.dp)
                                )
                            }
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
                            .size(48.dp),
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
    } // Column chat content

        // Диалог выбора рабочей папки агента (реальный путь для OpenCode)
    if (showFolderDialog) {
        FolderPickerDialog(
            viewModel = viewModel,
            onDismiss = { showFolderDialog = false }
        )
    }

    // При возврате из настроек разрешения — обновить статус
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.checkFilesAccess()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    } // Box root
}

@Composable
private fun FolderPickerDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val phone = viewModel.phoneRoot.absolutePath
    var choice by remember {
        mutableStateOf(
            when (viewModel.preferredFolder) {
                "" -> "app"
                phone -> "phone"
                else -> "own"
            }
        )
    }
    var path by remember {
        mutableStateOf(
            viewModel.preferredFolder.takeIf { it.isNotEmpty() && it != phone }
                ?: "$phone/YouCode"
        )
    }

    fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = { Text("Где работает агент") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "В этой папке модель читает, создаёт и меняет файлы и запускает команды. Для новых запросов к OpenCode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                listOf(
                    "app" to "Папка приложения",
                    "phone" to "Память телефона",
                    "own" to "Своя папка"
                ).forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { choice = value }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice == value, onClick = { choice = value })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (choice == "own") {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Путь") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if ((choice == "phone" || choice == "own") && !viewModel.filesGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Нужен доступ ко всем файлам. Без него будет использоваться папка приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { openAllFilesSettings() }) {
                        Text("Открыть настройки")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val selected = when (choice) {
                    "app" -> ""
                    "phone" -> phone
                    else -> path.trim()
                }
                viewModel.applyPreferredFolder(selected)
                onDismiss()
            }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
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
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(88.dp)
                .entrance(index = 0, fromY = 24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
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

        suggestions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEachIndexed { colIndex, (label, prompt) ->
                    Surface(
                        onClick = { onSuggestion(prompt) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .entrance(index = 3 + rowIndex * 2 + colIndex)
                            .pressScale(0.96f)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
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
            color = if (isUser) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (isUser) 0.dp else 2.dp,
            shadowElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = if (isUser) 0.dp else 2.dp)
                .animateContentSize()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (!isUser && !message.modelName.isNullOrBlank()) {
                    Text(
                        text = message.modelName.trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 1.dp, bottom = 2.dp)
                    )
                    Spacer(Modifier.height(4.dp))
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
                        if (file.isImage) {
                            MessageImage(file)
                            Spacer(Modifier.height(6.dp))
                        }
                        FileChip(file = file, onClick = { if (file.isSavedByAi) onSaveFileClick(file) })
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun MessageImage(file: AttachedFile) {
    val context = LocalContext.current
    val bitmap = remember(file.id, file.localPath, file.uri) {
        runCatching {
            val path = file.localPath
            if (!path.isNullOrBlank()) {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
            } else {
                val uri = file.uri ?: return@runCatching null
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(20.dp))
        )
    }
}

@Composable
private fun FileChip(file: AttachedFile, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
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
        shape = RoundedCornerShape(22.dp),
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
private fun GenerationActivity(
    status: String,
    activities: List<String>,
    reasoning: String = "",
    reasoningLive: Boolean = false,
    elapsedSec: Int = 0,
    etaSec: Int? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "activity")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "activity_rotation"
    )
    var reasoningOpen by remember { mutableStateOf(false) }

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
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) {
                CircularProgressIndicator(
                    progress = 0.72f,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    when {
                        reasoningLive || reasoning.isNotBlank() -> "Думает…"
                        activities.any { it.contains("выполняется") } -> "Выполняет инструменты…"
                        else -> "Работаю над запросом"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                val timeLine = buildString {
                    append("прошло ")
                    append(elapsedSec)
                    append(" с")
                    if (etaSec != null && etaSec > 0) {
                        append(" · ещё ~")
                        append(etaSec)
                        append(" с")
                    }
                }
                Text(
                    timeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Блок рассуждений: можно раскрыть и читать
        if (reasoning.isNotBlank()) {
            Column {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { reasoningOpen = !reasoningOpen }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (reasoningLive) "Думает…" else "Размышления",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (reasoningOpen || reasoningLive) 180f else 0f },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(visible = reasoningOpen || reasoningLive) {
                    Row(
                        modifier = Modifier
                            .height(IntrinsicSize.Min)
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Box(
                            Modifier
                                .padding(end = 12.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Text(
                            if (reasoningLive && !reasoningOpen) reasoning.takeLast(600) else reasoning.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (reasoningLive && !reasoningOpen) 8 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Текущий статус
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Сейчас",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Text(
                        status.ifBlank { "Подключаюсь…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Инструменты — по строке, с id если есть
        activities.takeLast(3).forEach { activity ->
            val isError = activity.contains("ошибка", ignoreCase = true)
            val isDone = activity.contains("завершено", ignoreCase = true)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        isError -> Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                        isDone -> Icon(
                            Icons.Outlined.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        else -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        activity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (activities.isNotEmpty() || reasoning.isNotBlank()) {
            Text(
                "Текущие действия",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }
    }
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


private data class ThinkingLevel(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val thinkingLevels = listOf(
    ThinkingLevel("fast", "Быстрый", "Короткие ответы без раздумий", Icons.Outlined.Bolt),
    ThinkingLevel("normal", "Нормальный", "Обычный режим", Icons.Outlined.Speed),
    ThinkingLevel("medium", "Средний", "Немного подумает перед ответом", Icons.Outlined.Lightbulb),
    ThinkingLevel("high", "Высокий", "Тщательный разбор по шагам", Icons.Outlined.Psychology),
    ThinkingLevel("extreme", "Экстремальный", "Максимально глубокий анализ", Icons.Outlined.AutoAwesome)
)
