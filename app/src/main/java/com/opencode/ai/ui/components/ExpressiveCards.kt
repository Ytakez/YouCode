package com.opencode.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencode.ai.ui.theme.pressScale

/**
 * Material 3 Expressive: карточки — плоские тональные контейнеры без обводки и тени,
 * с крупными скруглениями. Списки собираются в «сегментированные группы»:
 * у первой и последней карточки крупные внешние углы, между соседями — маленькие
 * углы и узкий зазор.
 */
object Expressive {
    /** Крупные «hero»-карточки (28dp, как extraLarge в M3). */
    val Card = 28.dp
    /** Карточки поменьше: чипы, инструменты, вложенные блоки (20dp — large increased). */
    val CardSmall = 20.dp
    /** Мелкие вложенные элементы (16dp). */
    val Nested = 16.dp
    /** Угол между соседями внутри группы. */
    val Joint = 6.dp
    /** Зазор между карточками в группе. */
    val Gap = 3.dp
}

/** Форма элемента [index] из [count] в сегментированной группе. */
fun groupShape(
    index: Int,
    count: Int,
    outer: Dp = Expressive.Card,
    inner: Dp = Expressive.Joint
): Shape {
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner,
        bottomEnd = if (last) outer else inner
    )
}

/** Плоская тональная карточка M3 Expressive. С [onClick] — нажимаемая. */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Expressive.Card),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.pressScale(0.98f),
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content
        )
    }
}

/** Иконка в цветном «значке» — тональный контейнер M3 Expressive. */
@Composable
fun ExpressiveIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(14.dp),
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Box(
        modifier = modifier.size(size).background(container, shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Маленькая «таблетка» с текстом: статус, размер контекста, провайдер. */
@Composable
fun ExpressivePill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
