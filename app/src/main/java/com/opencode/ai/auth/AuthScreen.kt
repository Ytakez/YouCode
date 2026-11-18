package com.opencode.ai.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onAuthenticated: (AuthSession) -> Unit, auth: AuthManager) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("Y", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(22.dp))
        Text("YouCode", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(if (registering) "Создайте аккаунт" else "Войдите в аккаунт", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Электронная почта") }, leadingIcon = { Icon(Icons.Outlined.Mail, null) }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Spacer(Modifier.height(16.dp))
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(8.dp)) }
        Button(onClick = {
            message = null
            if (!email.contains("@") || password.length < 6) { message = "Введите корректную почту и пароль минимум из 6 символов"; return@Button }
            scope.launch {
                busy = true
                try {
                    if (registering) {
                        val loggedIn = auth.signUp(email.trim(), password)
                        if (loggedIn) onAuthenticated(auth.currentSession()!!) else { message = "Аккаунт создан. Подтвердите почту по ссылке из письма, затем войдите."; registering = false }
                    } else onAuthenticated(auth.signIn(email.trim(), password))
                } catch (e: Exception) { message = e.message ?: "Не удалось выполнить запрос" }
                finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(if (registering) "Зарегистрироваться" else "Войти")
        }
        TextButton(onClick = { registering = !registering; message = null }) { Text(if (registering) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться") }
    }
}
