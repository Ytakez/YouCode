package com.opencode.ai.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val avatarCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Круглая аватарка: картинка по ссылке, а пока её нет — первая буква имени. */
@Composable
fun AvatarImage(url: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(if (url.isBlank()) null else avatarCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isNotBlank() && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                    }
                    try {
                        c.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        c.disconnect()
                    }
                }.getOrNull()
            }
            if (loaded != null) {
                avatarCache.put(url, loaded)
                bitmap = loaded
            }
        }
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString()
        when {
            bmp != null -> Image(
                bmp.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            initial != null -> Text(
                initial,
                fontSize = (size.value / 2.3f).sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            else -> Icon(
                Icons.Filled.Person, contentDescription = null,
                modifier = Modifier.size(size / 2),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(store: ProfileStore, onBack: () -> Unit) {
    val profile = store.profile
    val scope = rememberCoroutineScope()
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var callName by remember(profile.callName) { mutableStateOf(profile.callName.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                uploading = true
                message = null
                runCatching { store.uploadAvatar(uri) }
                    .onSuccess { isError = false; message = "Фото обновлено" }
                    .onFailure { isError = true; message = it.message ?: "Не удалось загрузить фото" }
                uploading = false
            }
        }
    }

    val nameChanged = name.trim() != profile.displayName
    val callText = callName.trim()
    val callChanged = callText != profile.callName.orEmpty()
    val canSave = name.isNotBlank() && (nameChanged || callChanged) && !saving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.clickable(enabled = !uploading) { picker.launch("image/*") }) {
                AvatarImage(profile.avatarUrl, profile.displayName, 112.dp)
                if (uploading) {
                    Box(
                        Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp) }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PhotoCamera, contentDescription = "Сменить фото",
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Нажмите на фото, чтобы выбрать другое",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Имя") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = callName,
                onValueChange = { callName = it.take(40) },
                label = { Text("Как ИИ обращается ко мне") },
                placeholder = { Text(name.ifBlank { "Как в имени" }) },
                supportingText = {
                    Text(
                        if (profile.callName == "") "Сейчас ИИ не называет вас по имени. Впишите обращение, чтобы изменить."
                        else "Оставьте пустым, чтобы ИИ звал вас по имени."
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            val tgName = profile.username.takeIf { it.isNotBlank() && !Regex("^tg\\d+$").matches(it) }
            if (tgName != null) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Telegram", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("@$tgName", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        message = null
                        runCatching {
                            store.update(
                                displayName = if (nameChanged) name else null,
                                callName = if (callChanged && callText.isNotEmpty()) callText else null,
                                clearCallName = callChanged && callText.isEmpty()
                            )
                        }.onSuccess { isError = false; message = "Сохранено" }
                            .onFailure { isError = true; message = it.message ?: "Не удалось сохранить" }
                        saving = false
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Сохранить")
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
