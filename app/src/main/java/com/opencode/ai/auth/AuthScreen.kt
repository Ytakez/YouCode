package com.opencode.ai.auth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val NO_NETWORK = "Нет соединения с сервером. Проверьте интернет."

private enum class Stage { Welcome, Telegram }

@Composable
fun AuthScreen(onAuthenticated: (AuthSession) -> Unit, auth: AuthManager) {
    var stage by rememberSaveable { mutableStateOf(Stage.Welcome) }
    var tgToken by rememberSaveable { mutableStateOf<String?>(null) }
    var tgLink by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    fun goBack() {
        message = null
        tgToken = null
        stage = Stage.Welcome
    }
    BackHandler(enabled = stage != Stage.Welcome && !busy) { goBack() }

    fun openTelegram(link: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            message = "Не удалось открыть Telegram. Откройте ссылку вручную: $link"
        }
    }

    LaunchedEffect(stage, tgToken) {
        val token = tgToken
        if (stage != Stage.Telegram || token == null) return@LaunchedEffect
        while (true) {
            delay(2000)
            try {
                val s = auth.telegramPoll(token)
                if (s != null) {
                    onAuthenticated(s)
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.io.IOException) {
                // временный сбой сети — пробуем ещё
            } catch (e: Exception) {
                message = e.message ?: "Не удалось войти через Telegram"
                tgToken = null
                stage = Stage.Welcome
                break
            }
        }
    }

    fun startTelegram() {
        message = null
        scope.launch {
            busy = true
            try {
                val r = auth.telegramStart()
                tgToken = r.token
                tgLink = r.link
                stage = Stage.Telegram
                openTelegram(r.link)
            } catch (_: java.io.IOException) {
                message = NO_NETWORK
            } catch (e: Exception) {
                message = e.message ?: "Не удалось начать вход через Telegram"
            } finally {
                busy = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.55f), cs.surface)))
    ) {
        Crossfade(stage, label = "auth", modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) { current ->
            when (current) {
                Stage.Welcome -> WelcomeContent(
                    notice = message,
                    busy = busy,
                    onTelegram = ::startTelegram
                )

                Stage.Telegram -> Column(Modifier.fillMaxSize()) {
                    BackButton(enabled = true, onClick = ::goBack)
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Подтвердите вход в Telegram",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "В боте нажмите «Запустить», затем «Да, это я». После этого вернитесь сюда — вход выполнится сам.",
                            color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        message?.let {
                            Spacer(Modifier.height(16.dp))
                            Notice(it, error = true)
                        }
                        Spacer(Modifier.height(28.dp))
                        AuthButton("Открыть Telegram", icon = Icons.AutoMirrored.Filled.Send, onClick = { openTelegram(tgLink) })
                        TextButton(onClick = ::goBack) { Text("Отмена") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent(notice: String?, busy: Boolean, onTelegram: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Code, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("Добро пожаловать в", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("YouCode", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(12.dp))
            Text(
                "Вход только через Telegram",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        notice?.let {
            Notice(it, error = true)
            Spacer(Modifier.height(16.dp))
        }
        AuthButton(
            "Войти через Telegram",
            busy = busy,
            enabled = !busy,
            icon = Icons.AutoMirrored.Filled.Send,
            onClick = onTelegram
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BackButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(8.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
    }
}

@Composable
private fun AuthButton(
    text: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val content: @Composable RowScope.() -> Unit = {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        content = content
    )
}

@Composable
private fun Notice(text: String, error: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) cs.errorContainer else cs.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) cs.onErrorContainer else cs.onSecondaryContainer
        )
    }
}
