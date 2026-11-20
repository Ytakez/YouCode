package com.opencode.ai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Вариант ответа: [value] уходит в движок, [label] и [description] видит человек. */
data class QOption(val value: String, val label: String, val description: String = "")

/**
 * Одно поле формы. [kind]: string | multiselect | boolean | number | integer.
 * Нет вариантов (options пусто) — значит, поле для свободного ввода.
 */
data class QField(
    val key: String,
    val kind: String,
    val title: String,
    val description: String,
    val options: List<QOption>,
    val multiple: Boolean,
    val custom: Boolean
)

data class QuestionForm(
    val id: String,
    val sessionId: String,
    val title: String,
    val fields: List<QField>
)

private const val CUSTOM = "\u0000custom"

/**
 * Карточка вопроса с вариантами: радиокнопки (или флажки), «Свой вариант» с полем ввода,
 * кнопки «Пропустить» и «Ответить». Ответ — карта key -> список выбранных значений.
 */
@Composable
fun QuestionCard(
    form: QuestionForm,
    submitting: Boolean,
    error: String?,
    onSubmit: (Map<String, List<String>>) -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = form.title,
    scroll: Boolean = false,
    submitLabel: String = "Ответить",
    skipLabel: String = "Пропустить",
    customLabel: String = "Свой вариант"
) {
    val single = remember(form.id) { mutableStateMapOf<String, String>() }
    val multi = remember(form.id) { mutableStateMapOf<String, Set<String>>() }
    val typed = remember(form.id) { mutableStateMapOf<String, String>() }

    fun answerOf(f: QField): List<String>? {
        val text = typed[f.key]?.trim().orEmpty()
        if (f.options.isEmpty()) return if (text.isEmpty()) null else listOf(text)
        if (f.multiple) {
            val selected = multi[f.key].orEmpty()
            val result = f.options.map { it.value }.filter { it in selected }.toMutableList()
            if (CUSTOM in selected && text.isNotEmpty()) result += text
            return result.ifEmpty { null }
        }
        val s = single[f.key] ?: return null
        return if (s == CUSTOM) (if (text.isEmpty()) null else listOf(text)) else listOf(s)
    }

    fun valid(f: QField, a: List<String>?): Boolean = when {
        a == null -> false
        f.kind == "number" -> a.first().replace(',', '.').toDoubleOrNull() != null
        f.kind == "integer" -> a.first().toLongOrNull() != null
        else -> true
    }

    // Раньше нужно было ответить на ВСЕ поля, и одно пустое текстовое поле блокировало кнопку,
    // даже когда человек уже выбрал «Да». Теперь: хотя бы один ответ, и каждый данный ответ корректен.
    val canSubmit = run {
        val given = form.fields.map { it to answerOf(it) }.filter { (_, a) -> a != null }
        given.isNotEmpty() && given.all { (f, a) -> valid(f, a) }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))
            Column(
                (if (scroll) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 24.dp)
            ) {
                form.fields.forEachIndexed { index, f ->
                    if (index > 0) Spacer(Modifier.height(20.dp))
                    FieldBlock(
                        field = f,
                        enabled = !submitting,
                        customLabel = customLabel,
                        selectedSingle = single[f.key],
                        selectedMulti = multi[f.key].orEmpty(),
                        typedText = typed[f.key].orEmpty(),
                        onSingle = { single[f.key] = it },
                        onMulti = { value, on ->
                            val now = multi[f.key].orEmpty()
                            multi[f.key] = if (on) now + value else now - value
                        },
                        onTyped = { typed[f.key] = it }
                    )
                }
            }
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSkip != null) {
                    TextButton(onClick = onSkip, enabled = !submitting) { Text(skipLabel) }
                }
                TextButton(
                    onClick = {
                        onSubmit(form.fields.mapNotNull { f -> answerOf(f)?.let { f.key to it } }.toMap())
                    },
                    enabled = canSubmit && !submitting
                ) {
                    if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(submitLabel)
                }
            }
        }
    }
}

@Composable
private fun FieldBlock(
    field: QField,
    enabled: Boolean,
    customLabel: String,
    selectedSingle: String?,
    selectedMulti: Set<String>,
    typedText: String,
    onSingle: (String) -> Unit,
    onMulti: (String, Boolean) -> Unit,
    onTyped: (String) -> Unit
) {
    if (field.title.isNotBlank()) {
        Text(field.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    if (field.description.isNotBlank()) {
        Text(
            field.description,
            style = if (field.title.isBlank()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (field.title.isBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = if (field.title.isBlank()) 0.dp else 6.dp)
        )
    }
    Spacer(Modifier.height(8.dp))

    if (field.options.isEmpty()) {
        TypedInput(field, typedText, enabled, onTyped, requestFocus = false, placeholder = "Введите ответ")
        return
    }

    field.options.forEach { option ->
        val selected = if (field.multiple) option.value in selectedMulti else selectedSingle == option.value
        OptionRow(
            selected = selected, multiple = field.multiple, label = option.label,
            description = option.description, enabled = enabled
        ) {
            if (field.multiple) onMulti(option.value, !selected) else onSingle(option.value)
        }
    }
    if (field.custom) {
        val selected = if (field.multiple) CUSTOM in selectedMulti else selectedSingle == CUSTOM
        OptionRow(selected = selected, multiple = field.multiple, label = customLabel, description = "", enabled = enabled) {
            if (field.multiple) onMulti(CUSTOM, !selected) else onSingle(CUSTOM)
        }
        if (selected) {
            TypedInput(field, typedText, enabled, onTyped, requestFocus = true, placeholder = "Напишите свой вариант")
        }
    }
}

@Composable
private fun TypedInput(
    field: QField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    requestFocus: Boolean,
    placeholder: String
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text(placeholder) },
        singleLine = field.kind != "string" || !requestFocus,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (field.kind) {
                "number" -> KeyboardType.Decimal
                "integer" -> KeyboardType.Number
                else -> KeyboardType.Text
            }
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).focusRequester(focus)
    )
}

@Composable
private fun OptionRow(
    selected: Boolean,
    multiple: Boolean,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (multiple) Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        else RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Та же карточка поверх чата: закрыть можно только ответом или «Пропустить». */
@Composable
fun QuestionDialog(
    form: QuestionForm,
    submitting: Boolean,
    error: String?,
    onSubmit: (Map<String, List<String>>) -> Unit,
    onSkip: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        QuestionCard(
            form = form,
            submitting = submitting,
            error = error,
            onSubmit = onSubmit,
            onSkip = onSkip,
            scroll = true,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .imePadding()
        )
    }
}
