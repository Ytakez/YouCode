package com.opencode.ai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF52634A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8C8),
    onPrimaryContainer = Color(0xFF101F0D),
    secondary = Color(0xFF58624F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7D0),
    onSecondaryContainer = Color(0xFF151E10),
    tertiary = Color(0xFF38666A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBCEBED),
    onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFF8FAF3),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF8FAF3),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFE1E4D9),
    onSurfaceVariant = Color(0xFF44483F),
    outline = Color(0xFF74796D),
    outlineVariant = Color(0xFFC4C8BC)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB9D0AA),
    onPrimary = Color(0xFF25351F),
    primaryContainer = Color(0xFF3B4B34),
    onPrimaryContainer = Color(0xFFD5E8C8),
    secondary = Color(0xFFC0CCB4),
    onSecondary = Color(0xFF2A3324),
    secondaryContainer = Color(0xFF414B3A),
    onSecondaryContainer = Color(0xFFDCE7D0),
    tertiary = Color(0xFFA0CFD1),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF1E4D50),
    onTertiaryContainer = Color(0xFFBCEBED),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE2E5DA),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE2E5DA),
    surfaceVariant = Color(0xFF42483D),
    onSurfaceVariant = Color(0xFFC2C8B9),
    outline = Color(0xFF8C9283),
    outlineVariant = Color(0xFF42483D)
)

private val YouCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun YouCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var paletteRevision by remember { mutableIntStateOf(0) }

    // Re-read Monet colors whenever the app returns to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) paletteRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Read the revision so Compose recalculates the platform wallpaper palette.
    val colorScheme = when {
        paletteRevision >= 0 && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Smoothly interpolate the wallpaper palette when the system palette changes.
    val animatedScheme = colorScheme.copy(
        primary = animateColorAsState(colorScheme.primary, tween(420)).value,
        onPrimary = animateColorAsState(colorScheme.onPrimary, tween(420)).value,
        primaryContainer = animateColorAsState(colorScheme.primaryContainer, tween(420)).value,
        onPrimaryContainer = animateColorAsState(colorScheme.onPrimaryContainer, tween(420)).value,
        secondary = animateColorAsState(colorScheme.secondary, tween(420)).value,
        secondaryContainer = animateColorAsState(colorScheme.secondaryContainer, tween(420)).value,
        tertiary = animateColorAsState(colorScheme.tertiary, tween(420)).value,
        tertiaryContainer = animateColorAsState(colorScheme.tertiaryContainer, tween(420)).value,
        background = animateColorAsState(colorScheme.background, tween(420)).value,
        surface = animateColorAsState(colorScheme.surface, tween(420)).value,
        onSurface = animateColorAsState(colorScheme.onSurface, tween(420)).value,
        onBackground = animateColorAsState(colorScheme.onBackground, tween(420)).value,
        onSurfaceVariant = animateColorAsState(colorScheme.onSurfaceVariant, tween(420)).value,
        surfaceVariant = animateColorAsState(colorScheme.surfaceVariant, tween(420)).value,
        surfaceContainer = animateColorAsState(colorScheme.surfaceContainer, tween(420)).value,
        surfaceContainerLow = animateColorAsState(colorScheme.surfaceContainerLow, tween(420)).value,
        surfaceContainerHigh = animateColorAsState(colorScheme.surfaceContainerHigh, tween(420)).value,
        outline = animateColorAsState(colorScheme.outline, tween(420)).value,
        outlineVariant = animateColorAsState(colorScheme.outlineVariant, tween(420)).value
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = animatedScheme,
        typography = Typography,
        shapes = YouCodeShapes,
        content = content
    )
}
