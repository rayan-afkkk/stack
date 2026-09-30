package com.swipegallery.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Design tokens. Pastel surfaces carry their own dark foreground ([onPastel]) instead of the
 * default text color, so contrast holds on every card.
 */
@Immutable
data class SwipeColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val primaryButton: Color,
    val onPrimaryButton: Color,
    val accent: Color,
    val onAccent: Color,
    val keep: Color,
    val remove: Color,
    val blueCard: Color,
    val onBlueCard: Color,
    val onBlueCardSecondary: Color,
    val lavenderCard: Color,
    val mintCard: Color,
    val onPastel: Color,
    val onPastelSecondary: Color,
    val scrim: Color,
)

val DarkSwipeColors = SwipeColors(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF211F1B),
    surfaceSecondary = Color(0xFF191815),
    textPrimary = Color(0xFFF3EEE5),
    textSecondary = Color(0xFFAAA69D),
    border = Color(0xFF34312B),
    primaryButton = Color(0xFFF3EEE5),
    onPrimaryButton = Color(0xFF14130F),
    accent = Color(0xFFFF6B3D),
    onAccent = Color(0xFF14130F),
    keep = Color(0xFFB8DEC0),
    remove = Color(0xFFF3A6A4),
    blueCard = Color(0xFF233F50),
    onBlueCard = Color(0xFFF3EEE5),
    onBlueCardSecondary = Color(0xFFC3CDD2),
    lavenderCard = Color(0xFFD7C0F0),
    mintCard = Color(0xFFC4E5CE),
    onPastel = Color(0xFF17161A),
    onPastelSecondary = Color(0xFF3F3B45),
    scrim = Color(0xCC000000),
)

val LightSwipeColors = SwipeColors(
    isDark = false,
    background = Color(0xFFF6F1E7),
    surface = Color(0xFFECE5D8),
    surfaceSecondary = Color(0xFFF1EBE0),
    textPrimary = Color(0xFF1B1A17),
    textSecondary = Color(0xFF625D54),
    border = Color(0xFFD8CFBF),
    primaryButton = Color(0xFF1B1A17),
    onPrimaryButton = Color(0xFFF6F1E7),
    accent = Color(0xFFC9471D),
    onAccent = Color(0xFFFFFFFF),
    keep = Color(0xFFB8DEC0),
    remove = Color(0xFFF3A6A4),
    blueCard = Color(0xFFCADCE6),
    onBlueCard = Color(0xFF14232C),
    onBlueCardSecondary = Color(0xFF3A4C56),
    lavenderCard = Color(0xFFDDC9F2),
    mintCard = Color(0xFFCBE8D3),
    onPastel = Color(0xFF17161A),
    onPastelSecondary = Color(0xFF3F3B45),
    scrim = Color(0x99000000),
)
