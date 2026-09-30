package com.swipegallery.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.QuietButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGES = 3

/**
 * Three short pages. Finishing only marks onboarding as seen; replaying it from Settings never
 * touches usage, preferences, purchases or review history.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onFinished: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    var finishing by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finishing) {
            finishing = true
            scope.launch {
                container.preferences.setOnboardingComplete()
                onFinished()
            }
        }
    }
    val page = pager.currentPage
    val c = SwipeTheme.colors

    Screen {
        Column(
            Modifier
                .fillMaxSize()
                .topSafeArea()
                .bottomSafeArea()
                .readableWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.s).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (page < PAGES - 1) QuietButton(stringResource(R.string.onboarding_skip), finish)
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                OnboardingPage(index = index, isCurrent = index == page, reducedMotion = reducedMotion)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.l)
                    .semantics {
                        contentDescription = "${page + 1} / $PAGES"
                    },
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGES) { i ->
                    val active = i == page
                    val width by animateFloatAsState(if (active) 20f else 6f, tween(if (reducedMotion) 0 else 180), label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(width.dp)
                            .clip(CircleShape)
                            .background(if (active) c.textPrimary else c.border),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                if (page > 0) {
                    QuietButton(
                        stringResource(R.string.onboarding_back),
                        onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    )
                }
                val last = page == PAGES - 1
                PrimaryButton(
                    text = stringResource(if (last) R.string.onboarding_get_started else R.string.onboarding_next),
                    onClick = { if (last) finish() else scope.launch { pager.animateScrollToPage(page + 1) } },
                    modifier = Modifier.weight(1f),
                    loading = finishing,
                )
            }
            Spacer(Modifier.height(Space.l))
        }
    }
}

@Composable
private fun OnboardingPage(index: Int, isCurrent: Boolean, reducedMotion: Boolean) {
    val (title, body) = when (index) {
        0 -> R.string.onboarding_1_title to R.string.onboarding_1_body
        1 -> R.string.onboarding_2_title to R.string.onboarding_2_body
        else -> R.string.onboarding_3_title to R.string.onboarding_3_body
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .aspectRatio(1.1f, matchHeightConstraintsFirst = false),
            contentAlignment = Alignment.Center,
        ) {
            when (index) {
                0 -> LighterGalleryVisual(isCurrent, reducedMotion)
                1 -> SwipeDemoVisual(isCurrent, reducedMotion)
                else -> PrivacyVisual()
            }
        }
        Spacer(Modifier.height(Space.xl))
        Text(
            stringResource(title),
            style = SwipeTheme.type.display,
            color = SwipeTheme.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.l))
        Text(stringResource(body), style = SwipeTheme.type.bodyLarge, color = SwipeTheme.colors.textSecondary)
        if (index == 2) {
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.onboarding_3_note), style = SwipeTheme.type.bodySmall, color = SwipeTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(Space.l))
    }
}

/** A grid of muted tiles; a few fade back once, suggesting a lighter gallery. */
@Composable
private fun LighterGalleryVisual(isCurrent: Boolean, reducedMotion: Boolean) {
    val c = SwipeTheme.colors
    val tiles = listOf(c.blueCard, c.surface, c.mintCard, c.surface, c.lavenderCard, c.surface, c.mintCard, c.blueCard, c.surface)
    val fading = setOf(1, 5, 6)
    val fade = remember { Animatable(1f) }
    LaunchedEffect(isCurrent) {
        if (isCurrent && fade.value == 1f) {
            if (reducedMotion) fade.snapTo(0.18f) else {
                delay(350)
                fade.animateTo(0.18f, tween(700, easing = FastOutSlowInEasing))
            }
        }
    }
    Column(
        Modifier.fillMaxWidth(0.72f).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .graphicsLayer { alpha = if (i in fading) fade.value else 1f }
                            .clip(RoundedCornerShape(Radii.small))
                            .background(tiles[i])
                            .border(1.dp, c.border, RoundedCornerShape(Radii.small)),
                    )
                }
            }
        }
    }
}

/** One card that nudges left then right, once, with clearly labelled directions. */
@Composable
private fun SwipeDemoVisual(isCurrent: Boolean, reducedMotion: Boolean) {
    val c = SwipeTheme.colors
    val offset = remember { Animatable(0f) }
    var played by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val travel = with(LocalDensity.current) { (maxWidth * 0.14f).toPx() }
        LaunchedEffect(isCurrent) {
            if (isCurrent && !played && !reducedMotion) {
                played = true
                delay(400)
                val spec = tween<Float>(420, easing = FastOutSlowInEasing)
                offset.animateTo(-travel, spec)
                offset.animateTo(0f, spec)
                delay(150)
                offset.animateTo(travel, spec)
                offset.animateTo(0f, spec)
            }
        }
        val progress = (offset.value / travel).coerceIn(-1f, 1f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionLabel(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    secondary = Icons.Outlined.Delete,
                    text = stringResource(R.string.onboarding_demo_left),
                    color = c.remove,
                    emphasis = (-progress).coerceAtLeast(0f),
                    leading = true,
                )
                DirectionLabel(
                    icon = Icons.AutoMirrored.Outlined.ArrowForward,
                    secondary = Icons.Outlined.Check,
                    text = stringResource(R.string.onboarding_demo_right),
                    color = c.keep,
                    emphasis = progress.coerceAtLeast(0f),
                    leading = false,
                )
            }
            Spacer(Modifier.height(Space.l))
            Box(
                Modifier
                    .fillMaxWidth(0.52f)
                    .aspectRatio(0.75f)
                    .graphicsLayer {
                        translationX = offset.value
                        rotationZ = (offset.value / travel).coerceIn(-1f, 1f) * 5f
                    }
                    .clip(RoundedCornerShape(Radii.card))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(Radii.card)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(Radii.small))
                        .background(c.blueCard),
                )
            }
        }
    }
}

@Composable
private fun DirectionLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    secondary: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
    emphasis: Float,
    leading: Boolean,
) {
    val c = SwipeTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f + 0.82f * emphasis))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (emphasis > 0.5f) c.onPastel else c.textPrimary
        if (leading) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Icon(secondary, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = SwipeTheme.type.label, color = fg)
        if (!leading) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PrivacyVisual() {
    val c = SwipeTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(Space.l))
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.mintCard)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = c.onPastel, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.settings_on_this_device), style = SwipeTheme.type.label, color = c.onPastel)
        }
    }
}
