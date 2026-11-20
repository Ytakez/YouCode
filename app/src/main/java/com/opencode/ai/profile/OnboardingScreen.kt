package com.opencode.ai.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opencode.ai.ui.components.QField
import com.opencode.ai.ui.components.QOption
import com.opencode.ai.ui.components.QuestionCard
import com.opencode.ai.ui.components.QuestionForm
import kotlinx.coroutines.launch

// Служебные значения вариантов: обычный ввод человека («да», «нет» и т.д.) с ними не спутать
private const val CHOICE_YES = "\u0001yes"
private const val CHOICE_NO = "\u0001no"

/**
 * Знакомство после первого входа через Telegram:
 * 1) человек вводит имя или ник; 2) YouCode здоровается и спрашивает, можно ли звать его так
 * (Да / Нет / Свой вариант). Когда ответ сохранён, профиль помечается пройденным и открывается приложение.
 */
@Composable
fun OnboardingScreen(store: ProfileStore) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (store.profile.displayName.isBlank()) 0 else 1) }
    var name by remember { mutableStateOf(store.profile.displayName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (step == 0) {
                Text(
                    "Как вас зовут?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Введите своё имя или любой ник — так к вам будут обращаться.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))

                fun submitName() {
                    if (name.isBlank() || busy) return
                    scope.launch {
                        busy = true
                        error = null
                        runCatching { store.update(displayName = name) }
                            .onSuccess { step = 1 }
                            .onFailure { error = it.message ?: "Не удалось сохранить имя" }
                        busy = false
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Имя") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitName() }),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { submitName() },
                    enabled = name.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Продолжить")
                }
            } else {
                val shown = name.trim().ifBlank { store.profile.displayName }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Code, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            "Привет, $shown! Я YouCode — ваш ИИ-помощник.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                val form = remember(shown) {
                    QuestionForm(
                        id = "onboarding", sessionId = "", title = "Вопрос от YouCode",
                        fields = listOf(
                            QField(
                                key = "call", kind = "string",
                                title = "Как к вам обращаться?",
                                description = "Можно называть вас «$shown»?",
                                options = listOf(
                                    QOption(CHOICE_YES, "Да", "Буду звать вас «$shown»"),
                                    QOption(CHOICE_NO, "Нет", "Не буду называть по имени")
                                ),
                                multiple = false, custom = true
                            )
                        )
                    )
                }

                // «Да» — по имени (call_name не задаём), «Нет» — без имени, иначе — введённое обращение
                fun finish(choice: String?) {
                    if (busy) return
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            when (choice) {
                                null, CHOICE_YES -> store.update(onboarded = true)
                                CHOICE_NO -> store.update(callName = "", onboarded = true)
                                else -> store.update(callName = choice, onboarded = true)
                            }
                        }.onFailure { error = it.message ?: "Не удалось сохранить" }
                        busy = false
                    }
                }

                QuestionCard(
                    form = form,
                    submitting = busy,
                    error = error,
                    onSubmit = { answers -> finish(answers["call"]?.firstOrNull()) },
                    onSkip = { finish(null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
