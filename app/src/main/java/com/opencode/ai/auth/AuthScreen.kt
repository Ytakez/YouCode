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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESEND_SECONDS = 60
private const val MIN_CODE_LENGTH = 6
private const val NO_NETWORK = "Нет соединения с сервером. Проверьте интернет."
private val FieldShape = RoundedCornerShape(20.dp)

private enum class Stage { Welcome, Credentials, Code, Telegram }

@Composable
fun AuthScreen(onAuthenticated: (AuthSession) -> Unit, auth: AuthManager) {
    var stage by rememberSaveable { mutableStateOf(Stage.Welcome) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var registering by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var tgToken by rememberSaveable { mutableStateOf<String?>(null) }
    var tgLink by rememberSaveable { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(cooldown) {
        if (cooldown > 0) { delay(1000); cooldown -= 1 }
    }

    fun goBack() {
        message = null; info = null; tgToken = null
        stage = if (stage == Stage.Code) Stage.Credentials else Stage.Welcome
    }
    BackHandler(enabled = stage != Stage.Welcome && !busy) { goBack() }

    fun openTelegram(link: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message = "Не удалось открыть Telegram. Откройте ссылку вручную: $link" }
    }

    // Пока открыт экран ожидания, раз в 2 секунды спрашиваем сервер: подтвердил ли пользователь вход в боте.
    LaunchedEffect(stage, tgToken) {
        val token = tgToken
        if (stage != Stage.Telegram || token == null) return@LaunchedEffect
        while (true) {
            delay(2000)
            try {
                val s = auth.telegramPoll(token)
                if (s != null) { onAuthenticated(s); break }
            } catch (e: CancellationException) { throw e }
            catch (e: java.io.IOException) { /* сеть моргнула — пробуем ещё раз */ }
            catch (e: Exception) {
                message = e.message ?: "Не удалось войти через Telegram"
                tgToken = null; stage = Stage.Welcome
                break
            }
        }
    }

    fun startTelegram() {
        message = null; info = null
        scope.launch {
            busy = true
            try {
                val r = auth.telegramStart()
                tgToken = r.token; tgLink = r.link; stage = Stage.Telegram
                openTelegram(r.link)
            } catch (e: java.io.IOException) { message = NO_NETWORK }
            catch (e: Exception) { message = e.message ?: "Не удалось начать вход через Telegram" }
            finally { busy = false }
        }
    }

    fun openCodeStage() { stage = Stage.Code; code = ""; cooldown = RESEND_SECONDS; message = null }

    fun submitCredentials() {
        message = null; info = null
        if (!email.contains("@") || password.length < 6) { message = "Введите корректную почту и пароль минимум из 6 символов"; return }
        scope.launch {
            busy = true
            try {
                val mail = email.trim()
                if (registering) {
                    val loggedIn = auth.signUp(mail, password)
                    if (loggedIn) auth.currentSession()?.let(onAuthenticated) else openCodeStage()
                } else onAuthenticated(auth.signIn(mail, password))
            } catch (e: AuthException) {
                if (e.code == "email_not_confirmed") {
                    // Аккаунт есть, но почта не подтверждена — высылаем новый код и показываем ввод
                    val sent = runCatching { auth.resendCode(email.trim()) }
                    openCodeStage()
                    if (sent.isFailure) message = sent.exceptionOrNull()?.message
                } else message = e.message ?: "Не удалось выполнить запрос"
            } catch (e: java.io.IOException) {
                message = NO_NETWORK
            } catch (e: Exception) {
                message = e.message ?: "Не удалось выполнить запрос"
            } finally { busy = false }
        }
    }

    fun verifyCode() {
        message = null; info = null
        scope.launch {
            busy = true
            try { onAuthenticated(auth.verifyEmail(email.trim(), code)) }
            catch (e: java.io.IOException) { message = NO_NETWORK }
            catch (e: Exception) { message = e.message ?: "Не удалось подтвердить код" }
            finally { busy = false }
        }
    }

    fun resend() {
        message = null; info = null
        scope.launch {
            busy = true
            try {
                auth.resendCode(email.trim())
                cooldown = RESEND_SECONDS
                info = "Код отправлен ещё раз. Проверьте также папку «Спам»."
            } catch (e: java.io.IOException) { message = NO_NETWORK }
            catch (e: Exception) { message = e.message ?: "Не удалось отправить код" }
            finally { busy = false }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.55f), cs.surface)))
    ) {
        Crossfade(stage, label = "auth", modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) { current ->
            when (current) {
                Stage.Welcome -> WelcomeContent(
                    notice = message, busy = busy,
                    onTelegram = ::startTelegram,
                    onEmail = { message = null; stage = Stage.Credentials }
                )

                Stage.Telegram -> Column(Modifier.fillMaxSize()) {
                    BackButton(enabled = true, onClick = ::goBack)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(28.dp))
                        Text("Подтвердите вход в Telegram", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "В боте нажмите «Запустить», затем «Да, это я». После этого вернитесь сюда — вход выполнится сам.",
                            color = cs.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                        message?.let { Spacer(Modifier.height(16.dp)); Notice(it, error = true) }
                        Spacer(Modifier.height(28.dp))
                        AuthButton("Открыть Telegram", icon = Icons.AutoMirrored.Filled.Send, onClick = { openTelegram(tgLink) })
                        TextButton(onClick = ::goBack) { Text("Отмена") }
                    }
                }

                Stage.Credentials -> Column(Modifier.fillMaxSize()) {
                    BackButton(enabled = !busy, onClick = ::goBack)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Text(if (registering) "Регистрация" else "Вход по почте", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (registering) "Придумайте пароль — затем пришлём код подтверждения на почту" else "Введите почту и пароль от аккаунта",
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.height(28.dp))
                        OutlinedTextField(
                            email, { email = it }, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                            label = { Text("Электронная почта") }, leadingIcon = { Icon(Icons.Outlined.Mail, null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            password, { password = it }, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                            label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "Скрыть пароль" else "Показать пароль")
                                }
                            },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true
                        )
                        Spacer(Modifier.height(16.dp))
                        info?.let { Notice(it, error = false); Spacer(Modifier.height(12.dp)) }
                        message?.let { Notice(it, error = true); Spacer(Modifier.height(12.dp)) }
                        Spacer(Modifier.height(4.dp))
                        AuthButton(if (registering) "Зарегистрироваться" else "Войти", busy = busy, enabled = !busy, onClick = ::submitCredentials)
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { registering = !registering; message = null; info = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text(if (registering) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться") }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                Stage.Code -> Column(Modifier.fillMaxSize()) {
                    BackButton(enabled = !busy, onClick = ::goBack)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Text("Введите код из письма", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Мы отправили код подтверждения на ${email.trim()}", color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(28.dp))
                        OutlinedTextField(
                            code, { code = it.filter(Char::isDigit).take(10) },
                            modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                            placeholder = { Text("······", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                        )
                        Spacer(Modifier.height(16.dp))
                        info?.let { Notice(it, error = false); Spacer(Modifier.height(12.dp)) }
                        message?.let { Notice(it, error = true); Spacer(Modifier.height(12.dp)) }
                        Spacer(Modifier.height(4.dp))
                        AuthButton("Подтвердить", busy = busy, enabled = !busy && code.length >= MIN_CODE_LENGTH, onClick = ::verifyCode)
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            enabled = !busy && cooldown == 0, onClick = ::resend,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text(if (cooldown > 0) "Отправить код ещё раз ($cooldown с)" else "Отправить код ещё раз") }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent(notice: String?, busy: Boolean, onTelegram: () -> Unit, onEmail: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        }
        notice?.let { Notice(it, error = true); Spacer(Modifier.height(16.dp)) }
        AuthButton("Войти через Telegram", busy = busy, enabled = !busy, icon = Icons.AutoMirrored.Filled.Send, onClick = onTelegram)
        Spacer(Modifier.height(12.dp))
        AuthButton("Войти по почте", enabled = !busy, icon = Icons.Outlined.Mail, tonal = true, onClick = onEmail)
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
    tonal: Boolean = false,
    onClick: () -> Unit
) {
    val content: @Composable RowScope.() -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
    val modifier = Modifier.fillMaxWidth().height(56.dp)
    if (tonal) FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = modifier, content = content)
    else Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = modifier, content = content)
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
            text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (error) cs.onErrorContainer else cs.onSecondaryContainer
        )
    }
}
