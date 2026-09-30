package com.swipegallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swipegallery.data.prefs.ThemeMode

private val LocalSwipeColors = staticCompositionLocalOf { DarkSwipeColors }
private val LocalSwipeTypography = staticCompositionLocalOf { swipeTypography(44.sp) }

object SwipeTheme {
    val colors: SwipeColors
        @Composable @ReadOnlyComposable get() = LocalSwipeColors.current
    val type: SwipeTypography
        @Composable @ReadOnlyComposable get() = LocalSwipeTypography.current
}

/** Spacing scale: 4, 8, 12, 16, 24, 32. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 20.dp
}

object Radii {
    val card = 24.dp
    val tile = 18.dp
    val small = 14.dp
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun SwipeGalleryTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkSwipeColors else LightSwipeColors
    val displaySize = responsiveDisplaySize()
    val type = remember(displaySize) { swipeTypography(displaySize) }
    val scheme = remember(colors) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colors.primaryButton,
            onPrimary = colors.onPrimaryButton,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.surface,
            surfaceContainerLow = colors.surfaceSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.remove,
        )
    }
    val materialType = remember(type) {
        Typography(
            bodyLarge = type.body,
            bodyMedium = type.bodySmall,
            labelLarge = type.button,
            titleLarge = type.title,
            headlineSmall = type.headline,
        )
    }
    CompositionLocalProvider(
        LocalSwipeColors provides colors,
        LocalSwipeTypography provides type,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType, content = content)
    }
}
