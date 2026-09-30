package com.swipegallery.ui.session

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swipegallery.R
import com.swipegallery.data.media.contentUri
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.swipe.SwipeConfig
import com.swipegallery.domain.swipe.SwipeDecider
import com.swipegallery.domain.swipe.SwipeOutcome
import com.swipegallery.ui.components.PhotoImage
import com.swipegallery.ui.components.SecondaryButton
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.confirmHaptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

data class ExitingCard(val id: Long, val photo: PhotoRef, val fromOffset: Float, val decision: Decision)

/**
 * Shared between the deck and the Keep/Remove buttons so both paths run the exact same
 * commit and animation. Also exposes whether any card is still animating out, so the screen
 * can let the final animation finish before showing a limit or completion state.
 */
@Stable
class SwipeDeckState {
    val exiting = mutableStateListOf<ExitingCard>()
    internal var trigger: ((Decision) -> Unit)? = null
    private var lastButtonCommit = 0L
    private var nextId = 0L

    val isSettled: Boolean get() = exiting.isEmpty()

    internal fun newId(): Long = nextId++

    /** Button path. Rapid double taps within [BUTTON_DEBOUNCE_MS] count once. */
    fun swipe(decision: Decision) {
        val now = SystemClock.uptimeMillis()
        if (now - lastButtonCommit < BUTTON_DEBOUNCE_MS) return
        lastButtonCommit = now
        trigger?.invoke(decision)
    }

    private companion object {
        const val BUTTON_DEBOUNCE_MS = 280L
    }
}

private val CardShape = RoundedCornerShape(Radii.card)

@Composable
fun SwipeDeck(
    state: SwipeDeckState,
    top: PhotoRef?,
    behind: PhotoRef?,
    afterBehind: PhotoRef?,
    enabled: Boolean,
    undoReturn: UndoReturn?,
    topUnavailable: Boolean,
    reducedMotion: Boolean,
    hapticsEnabled: Boolean,
    describe: (PhotoRef) -> String,
    onDecide: (PhotoRef, Decision) -> Boolean,
    onImageError: (PhotoRef) -> Unit,
    onSkipUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val config = remember(density) {
            SwipeConfig(flingVelocityPxPerSec = with(density) { 1100.dp.toPx() })
        }
        val dragProgress = remember { mutableFloatStateOf(0f) }
        val exitingKeys = state.exiting.map { it.photo.key }.toSet()
        val visible = listOfNotNull(top, behind, afterBehind).filter { it.key !in exitingKeys }
        val visibleTop = visible.getOrNull(0)
        val visibleBehind = visible.getOrNull(1)
        val peek = with(density) { 14.dp.toPx() }

        visibleBehind?.let { photo ->
            key("behind-" + photo.key) {
                CardFace(
                    photo = photo,
                    widthPx = widthPx.toInt(),
                    heightPx = heightPx.toInt(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = if (reducedMotion) 0f else dragProgress.floatValue
                            val scale = 0.95f + 0.03f * p
                            scaleX = scale
                            scaleY = scale
                            translationY = peek * (1f - p)
                            alpha = 0.45f + 0.4f * p
                        },
                    onImageError = {},
                )
            }
        }

        visibleTop?.let { photo ->
            key(photo.key) {
                TopCard(
                    state = state,
                    photo = photo,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    config = config,
                    enabled = enabled && !topUnavailable,
                    enterFrom = undoReturn?.takeIf { it.key == photo.key }?.let {
                        if (it.decision == Decision.REMOVE) -widthPx * 1.2f else widthPx * 1.2f
                    },
                    reducedMotion = reducedMotion,
                    hapticsEnabled = hapticsEnabled,
                    description = describe(photo),
                    onProgress = { dragProgress.floatValue = it },
                    onDecide = onDecide,
                    onImageError = onImageError,
                )
                if (topUnavailable) UnavailableOverlay(onSkipUnavailable)
            }
        }

        state.exiting.forEach { card ->
            key("exit-" + card.id) {
                ExitingCardView(
                    card = card,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    config = config,
                    reducedMotion = reducedMotion,
                    onDone = { state.exiting.remove(card) },
                )
            }
        }
    }
}

private class DragHolder {
    var x = 0f
    var committed = false
}

@Composable
private fun TopCard(
    state: SwipeDeckState,
    photo: PhotoRef,
    widthPx: Float,
    heightPx: Float,
    config: SwipeConfig,
    enabled: Boolean,
    enterFrom: Float?,
    reducedMotion: Boolean,
    hapticsEnabled: Boolean,
    description: String,
    onProgress: (Float) -> Unit,
    onDecide: (PhotoRef, Decision) -> Boolean,
    onImageError: (PhotoRef) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val offset = remember { Animatable(if (reducedMotion) 0f else enterFrom ?: 0f) }
    // Settle-in: 0.98 → 1.0 scale with a short upward translation.
    val settle = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val drag = remember { DragHolder() }
    val tracker = remember { VelocityTracker() }
    val settleSpec = tween<Float>(if (reducedMotion) 0 else 220, easing = FastOutSlowInEasing)
    val currentEnabled by rememberUpdatedState(enabled)
    val lift = with(LocalDensity.current) { 8.dp.toPx() }

    LaunchedEffect(Unit) {
        onProgress(0f)
        launch { settle.animateTo(1f, tween(if (reducedMotion) 0 else 180, easing = FastOutSlowInEasing)) }
        if (offset.value != 0f) offset.animateTo(0f, tween(if (reducedMotion) 0 else 240, easing = FastOutSlowInEasing))
    }

    fun commit(decision: Decision) {
        if (drag.committed || !currentEnabled) return
        if (onDecide(photo, decision)) {
            drag.committed = true
            onProgress(0f)
            view.confirmHaptic(hapticsEnabled)
            state.exiting.add(ExitingCard(state.newId(), photo, offset.value, decision))
        } else {
            drag.x = 0f
            scope.launch { offset.animateTo(0f, settleSpec) }
        }
    }

    SideEffect { state.trigger = { commit(it) } }

    val keepLabel = stringResource(R.string.session_keep)
    val removeLabel = stringResource(R.string.session_remove)

    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = description
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(keepLabel) { commit(Decision.KEEP); true },
                        CustomAccessibilityAction(removeLabel) { commit(Decision.REMOVE); true },
                    )
                }
            }
            .pointerInput(photo.key, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalSwipe(
                    onStart = {
                        tracker.resetTracking()
                        drag.x = offset.value
                        scope.launch { offset.stop() }
                    },
                    onDrag = { dx, change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        drag.x += dx
                        val x = drag.x
                        onProgress(SwipeDecider.progress(x, widthPx, config))
                        scope.launch { offset.snapTo(x) }
                    },
                    onEnd = {
                        val velocity = tracker.calculateVelocity().x
                        when (SwipeDecider.decide(drag.x, velocity, widthPx, config)) {
                            SwipeOutcome.KEEP -> commit(Decision.KEEP)
                            SwipeOutcome.REMOVE -> commit(Decision.REMOVE)
                            SwipeOutcome.CANCEL -> {
                                drag.x = 0f
                                onProgress(0f)
                                scope.launch { offset.animateTo(0f, settleSpec) }
                            }
                        }
                    },
                    onCancel = {
                        drag.x = 0f
                        onProgress(0f)
                        scope.launch { offset.animateTo(0f, settleSpec) }
                    },
                )
            },
    ) {
        CardFace(
            photo = photo,
            widthPx = widthPx.toInt(),
            heightPx = heightPx.toInt(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    translationY = lift * (1f - settle.value)
                    val s = 0.98f + 0.02f * settle.value
                    scaleX = s
                    scaleY = s
                    rotationZ = if (reducedMotion) 0f else SwipeDecider.rotationDegrees(offset.value, widthPx, config)
                },
            onImageError = { onImageError(photo) },
            labelProgress = { SwipeDecider.progress(offset.value, widthPx, config) * sign(offset.value) },
        )
    }
}

@Composable
private fun ExitingCardView(
    card: ExitingCard,
    widthPx: Float,
    heightPx: Float,
    config: SwipeConfig,
    reducedMotion: Boolean,
    onDone: () -> Unit,
) {
    val direction = if (card.decision == Decision.REMOVE) -1f else 1f
    val offset = remember { Animatable(card.fromOffset) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reducedMotion) {
            fade.animateTo(0f, tween(90))
        } else {
            offset.animateTo(direction * widthPx * 1.35f, tween(210, easing = FastOutLinearInEasing))
        }
        onDone()
    }
    CardFace(
        photo = card.photo,
        widthPx = widthPx.toInt(),
        heightPx = heightPx.toInt(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offset.value
                rotationZ = if (reducedMotion) 0f else SwipeDecider.rotationDegrees(offset.value, widthPx, config)
                alpha = fade.value
            },
        onImageError = {},
        labelProgress = { direction },
    )
}

/**
 * The photo is shown whole (ContentScale.Fit) on a quiet surface: nothing is cropped away.
 * [labelProgress] is read at draw time (-1 = full REMOVE, +1 = full KEEP) to avoid recomposing.
 */
@Composable
private fun CardFace(
    photo: PhotoRef,
    widthPx: Int,
    heightPx: Int,
    contentDescription: String?,
    modifier: Modifier,
    onImageError: () -> Unit,
    labelProgress: (() -> Float)? = null,
) {
    val c = SwipeTheme.colors
    Box(
        modifier
            .clip(CardShape)
            .background(c.surfaceSecondary)
            .border(1.dp, c.border, CardShape),
    ) {
        PhotoImage(
            uri = photo.contentUri(),
            contentDescription = contentDescription,
            widthPx = widthPx,
            heightPx = heightPx,
            contentScale = ContentScale.Fit,
            showErrorText = true,
            onError = onImageError,
            modifier = Modifier.fillMaxSize(),
        )
        if (labelProgress != null) {
            // Directional wash + stamps; text and icons, never color alone.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (-labelProgress() * 0.22f).coerceIn(0f, 0.22f) }
                    .background(c.remove),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (labelProgress() * 0.22f).coerceIn(0f, 0.22f) }
                    .background(c.keep),
            )
            Stamp(
                text = stringResource(R.string.session_stamp_remove),
                container = c.remove,
                icon = Icons.Outlined.Delete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Space.l)
                    .graphicsLayer { alpha = (-labelProgress()).coerceIn(0f, 1f) },
            )
            Stamp(
                text = stringResource(R.string.session_stamp_keep),
                container = c.keep,
                icon = Icons.Outlined.Check,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.l)
                    .graphicsLayer { alpha = labelProgress().coerceIn(0f, 1f) },
            )
        }
    }
}

@Composable
private fun Stamp(text: String, container: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    val c = SwipeTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.onPastel, modifier = Modifier.size(18.dp))
        Text(text, style = SwipeTheme.type.stamp, color = c.onPastel)
    }
}

@Composable
private fun UnavailableOverlay(onSkip: () -> Unit) {
    val c = SwipeTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .clip(CardShape)
            .background(c.surfaceSecondary)
            .border(1.dp, c.border, CardShape)
            .padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(Space.m))
        Text(stringResource(R.string.session_unavailable_title), style = SwipeTheme.type.title, color = c.textPrimary)
        Spacer(Modifier.height(Space.xs))
        Text(stringResource(R.string.session_unavailable_body), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Space.l))
        SecondaryButton(stringResource(R.string.session_skip), onSkip)
    }
}

/**
 * Horizontal-only swipe detection. Taps never start a drag; a gesture whose first decisive
 * movement is vertical is abandoned (not a swipe); only the first pointer is tracked, so a
 * second finger cannot trigger a second commit.
 */
private suspend fun PointerInputScope.detectHorizontalSwipe(
    onStart: () -> Unit,
    onDrag: (dx: Float, change: PointerInputChange) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var totalX = 0f
        var totalY = 0f
        var dragging = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    if (dragging) onEnd()
                    return@awaitEachGesture
                }
                val delta = change.positionChange()
                if (!dragging) {
                    totalX += delta.x
                    totalY += delta.y
                    if (abs(totalY) > slop && abs(totalY) >= abs(totalX)) return@awaitEachGesture
                    if (abs(totalX) > slop && abs(totalX) > abs(totalY) * 1.2f) {
                        dragging = true
                        onStart()
                        onDrag(totalX - sign(totalX) * slop, change)
                        change.consume()
                    }
                } else {
                    if (change.isConsumed) {
                        onCancel()
                        return@awaitEachGesture
                    }
                    onDrag(delta.x, change)
                    change.consume()
                }
            }
        } catch (e: CancellationException) {
            if (dragging) onCancel()
            throw e
        }
    }
}
