package com.swipegallery.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme

/** Top inset (status bar / cutout) for screens that draw edge to edge. */
fun Modifier.topSafeArea(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/** Bottom inset (gesture/nav bar) for full screens without the bottom navigation. */
fun Modifier.bottomSafeArea(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))

/** Keeps long lines readable on tablets and in landscape. */
fun Modifier.readableWidth(max: Dp = 640.dp): Modifier = widthIn(max = max)

@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(SwipeTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

@Composable
fun ScreenHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SwipeTheme.type.display,
        color = SwipeTheme.colors.textPrimary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SwipeTheme.type.label,
        color = SwipeTheme.colors.textSecondary,
        modifier = modifier.semantics { heading() },
    )
}

/** Rounded charcoal card with a quiet border instead of a shadow. */
@Composable
fun SwipeCard(
    modifier: Modifier = Modifier,
    color: Color = SwipeTheme.colors.surface,
    shape: Shape = RoundedCornerShape(Radii.card),
    bordered: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(shape)
        .background(color)
        .then(if (bordered) Modifier.border(BorderStroke(1.dp, SwipeTheme.colors.border), shape) else Modifier)
    Column(
        (if (onClick != null) base.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else base)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = SwipeTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (enabled) c.primaryButton else c.primaryButton.copy(alpha = 0.35f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.onPrimaryButton, strokeWidth = 2.dp)
            Spacer(Modifier.width(Space.s))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onPrimaryButton, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = SwipeTheme.type.button, color = c.onPrimaryButton, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = SwipeTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, c.border), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) c.textPrimary else c.textSecondary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = SwipeTheme.type.button, color = tint, textAlign = TextAlign.Center)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = SwipeTheme.colors.textSecondary) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SwipeTheme.type.button.copy(fontWeight = SwipeTheme.type.label.fontWeight), color = color)
    }
}

@Composable
fun SlimProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = SwipeTheme.colors.textPrimary,
    track: Color = SwipeTheme.colors.border,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = SwipeTheme.colors.surfaceSecondary, content: Color = SwipeTheme.colors.textPrimary, bordered: Boolean = true) {
    Box(
        modifier
            .clip(CircleShape)
            .background(container)
            .then(if (bordered) Modifier.border(1.dp, SwipeTheme.colors.border, CircleShape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = SwipeTheme.type.label, color = content)
    }
}

/** Pill-shaped single-choice selector, e.g. System | Light | Dark. */
@Composable
fun <T> SegmentedPill(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SwipeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.surfaceSecondary)
            .border(1.dp, c.border, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) c.primaryButton else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = Space.s, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = SwipeTheme.type.label.copy(fontSize = SwipeTheme.type.bodySmall.fontSize),
                    color = if (isSelected) c.onPrimaryButton else c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Quiet list row: outline icon, title, optional subtitle, chevron or custom trailing content. */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = SwipeTheme.colors.textPrimary,
    trailing: (@Composable () -> Unit)? = {
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = SwipeTheme.colors.textSecondary,
        )
    },
) {
    val c = SwipeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Icon(icon, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SwipeTheme.type.body, color = titleColor)
            if (subtitle != null) Text(subtitle, style = SwipeTheme.type.bodySmall, color = c.textSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SwipeTheme.colors.border),
    )
}

/** Generous, quiet empty state: outline icon, serif headline, one explanation, one CTA. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = SwipeTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .border(1.dp, c.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(Space.xl))
        Text(
            title,
            style = SwipeTheme.type.headline,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.m))
        Text(body, style = SwipeTheme.type.body, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.readableWidth(420.dp))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(actionLabel, onAction, Modifier.readableWidth(420.dp).fillMaxWidth())
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(Space.s))
            QuietButton(secondaryLabel, onSecondary)
        }
    }
}

/** Inline notice (e.g. "Selected photos only") with an optional action. */
@Composable
fun Notice(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = SwipeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.small))
            .background(c.surfaceSecondary)
            .border(1.dp, c.border, RoundedCornerShape(Radii.small))
            .padding(start = Space.l, end = Space.s, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Text(text, style = SwipeTheme.type.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f).padding(vertical = Space.s))
        if (actionLabel != null && onAction != null) {
            QuietButton(actionLabel, onAction, color = c.textPrimary)
        }
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SwipeTheme.colors.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}
