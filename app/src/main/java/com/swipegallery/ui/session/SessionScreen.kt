package com.swipegallery.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.Disposable
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.data.media.contentUri
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.Decision
import com.swipegallery.ui.components.CenteredLoading
import com.swipegallery.ui.components.EmptyState
import com.swipegallery.ui.components.Notice
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.QuietButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.SecondaryButton
import com.swipegallery.ui.components.SlimProgress
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.prefetchPhotos
import com.swipegallery.ui.components.rememberPhotoAccessActions
import com.swipegallery.ui.components.scopeTitle
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.PaywallSource
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.Format
import com.swipegallery.util.rememberReducedMotion
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPaywall: (PaywallSource) -> Unit,
    onHome: () -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val vm = containerViewModel { c, handle -> SessionViewModel(c, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by container.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    val deck = remember { SwipeDeckState() }
    val reducedMotion = rememberReducedMotion()
    val c = SwipeTheme.colors

    // Keep the final card's exit animation visible before switching to a limit/completed state.
    val deckPhase = state.phase == SessionPhase.REVIEWING ||
        (!deck.isSettled && (state.phase == SessionPhase.LIMIT_REACHED || state.phase == SessionPhase.COMPLETED))

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3_500)
            vm.messageShown()
        }
    }

    Screen {
        Column(Modifier.fillMaxSize().topSafeArea().bottomSafeArea()) {
            SessionTopBar(state, onBack)
            state.message?.let { message ->
                Notice(
                    icon = Icons.Outlined.Info,
                    text = stringResource(
                        when (message) {
                            SessionMessage.PhotoRemovedElsewhere -> R.string.session_msg_removed_elsewhere
                            SessionMessage.UndoAlreadyTrashed -> R.string.session_msg_undo_trashed
                            SessionMessage.UndoPhotoGone -> R.string.session_msg_undo_gone
                            SessionMessage.SaveFailed -> R.string.session_msg_save_failed
                        },
                    ),
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.phase == SessionPhase.LOADING -> CenteredLoading()
                    deckPhase -> ReviewingContent(
                        state = state,
                        deck = deck,
                        reducedMotion = reducedMotion,
                        hapticsEnabled = prefs?.hapticsEnabled ?: true,
                        vm = vm,
                        onOpenQueue = onOpenQueue,
                    )

                    else -> PhaseContent(
                        state = state,
                        container = container,
                        permissionRequestedBefore = prefs?.photoPermissionRequested == true,
                        onOpenQueue = onOpenQueue,
                        onOpenPaywall = onOpenPaywall,
                        onHome = onHome,
                        onRevisit = { vm.startRevisit(onOpenSession) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionTopBar(state: SessionUiState, onBack: () -> Unit) {
    val c = SwipeTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = c.textPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
                Text(
                    state.spec?.let { scopeTitle(it.scope) } ?: "",
                    style = SwipeTheme.type.title,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.total > 0) {
                    Text(
                        stringResource(R.string.session_progress, Format.count(state.decidedInSession), Format.count(state.total)),
                        style = SwipeTheme.type.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }
            val allowance = state.allowance
            if (allowance != null) {
                val text = if (allowance.isPremium) {
                    stringResource(R.string.allowance_unlimited_short)
                } else {
                    pluralStringResource(R.plurals.allowance_left_short, allowance.remaining, allowance.remaining)
                }
                Box(
                    Modifier
                        .padding(end = Space.s)
                        .clip(CircleShape)
                        .border(1.dp, c.border, CircleShape)
                        .padding(horizontal = Space.m, vertical = 6.dp),
                ) {
                    Text(text, style = SwipeTheme.type.label, color = c.textPrimary)
                }
            }
        }
        SlimProgress(
            progress = state.progress,
            modifier = Modifier.padding(horizontal = Space.l).semantics {
                contentDescription = "${state.decidedInSession} / ${state.total}"
            },
        )
    }
}

@Composable
private fun ReviewingContent(
    state: SessionUiState,
    deck: SwipeDeckState,
    reducedMotion: Boolean,
    hapticsEnabled: Boolean,
    vm: SessionViewModel,
    onOpenQueue: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxHeight < 560.dp
        val enabled = state.phase == SessionPhase.REVIEWING && !state.committing
        val deckWidthPx = with(density) { (if (landscape) maxWidth * 0.55f else maxWidth).roundToPx() }
        val deckHeightPx = with(density) { (maxHeight * 0.7f).roundToPx() }

        // Prefetch the next few photos at card size; cancelled when the session screen leaves.
        val prefetched = remember { HashMap<String, Disposable>() }
        LaunchedEffect(state.upcoming.map { it.key }, deckWidthPx, deckHeightPx) {
            state.upcoming.drop(1).take(3).filter { it.key !in prefetched }.forEach { photo ->
                prefetched[photo.key] = prefetchPhotos(context, listOf(photo.contentUri()), deckWidthPx, deckHeightPx).first()
            }
        }
        DisposableEffect(Unit) {
            onDispose { prefetched.values.forEach { it.dispose() } }
        }

        val describe: (PhotoRef) -> String = { photo -> describePhoto(context, photo) }
        val deckView: @Composable (Modifier) -> Unit = { modifier ->
            SwipeDeck(
                state = deck,
                top = state.current,
                behind = state.next,
                afterBehind = state.afterNext,
                enabled = enabled,
                undoReturn = state.undoReturn,
                topUnavailable = state.currentUnavailable,
                reducedMotion = reducedMotion,
                hapticsEnabled = hapticsEnabled,
                describe = describe,
                onDecide = vm::decide,
                onImageError = vm::onImageFailed,
                onSkipUnavailable = vm::skipUnavailable,
                modifier = modifier,
            )
        }
        val controls: @Composable ColumnScope.() -> Unit = {
            state.current?.let { PhotoDetails(it) }
            Spacer(Modifier.height(Space.l))
            DecisionButtons(
                canUndo = state.canUndo && !state.committing && deck.isSettled,
                enabled = enabled && !state.currentUnavailable,
                onUndo = vm::undo,
                onRemove = { deck.swipe(Decision.REMOVE) },
                onKeep = { deck.swipe(Decision.KEEP) },
            )
            if (state.queueCount > 0) {
                Spacer(Modifier.height(Space.xs))
                QuietButton(
                    stringResource(R.string.session_review_queue, state.queueCount),
                    onOpenQueue,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = SwipeTheme.colors.textPrimary,
                )
            } else {
                Spacer(Modifier.height(Space.m))
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                deckView(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.widthIn(max = 360.dp).weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    content = controls,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(Space.l))
                deckView(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(Space.l))
                controls(this)
            }
        }
    }
}

@Composable
private fun PhotoDetails(photo: PhotoRef) {
    val context = LocalContext.current
    val c = SwipeTheme.colors
    val date = photo.effectiveDateMillis?.let {
        stringResource(if (photo.hasCaptureDate) R.string.meta_captured else R.string.meta_added, Format.date(it))
    } ?: stringResource(R.string.meta_no_date)
    val size = photo.sizeBytes?.let { Format.bytes(context, it) } ?: stringResource(R.string.meta_size_unknown)
    Text(
        "$date · $size",
        style = SwipeTheme.type.bodySmall,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DecisionButtons(
    canUndo: Boolean,
    enabled: Boolean,
    onUndo: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    val c = SwipeTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val undoLabel = stringResource(R.string.session_undo)
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, c.border, CircleShape)
                .clickable(enabled = canUndo, role = Role.Button, onClickLabel = undoLabel, onClick = onUndo)
                .semantics { contentDescription = undoLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Undo,
                contentDescription = null,
                tint = if (canUndo) c.textPrimary else c.textSecondary.copy(alpha = 0.5f),
            )
        }
        DecisionButton(
            label = stringResource(R.string.session_remove),
            icon = Icons.Outlined.Delete,
            container = c.remove,
            enabled = enabled,
            onClick = onRemove,
            modifier = Modifier.weight(1f),
        )
        DecisionButton(
            label = stringResource(R.string.session_keep),
            icon = Icons.Outlined.Check,
            container = c.keep,
            enabled = enabled,
            onClick = onKeep,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DecisionButton(
    label: String,
    icon: ImageVector,
    container: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = SwipeTheme.colors.onPastel
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
        Text(label, style = SwipeTheme.type.button, color = fg)
    }
}

@Composable
private fun PhaseContent(
    state: SessionUiState,
    container: AppContainer,
    permissionRequestedBefore: Boolean,
    onOpenQueue: () -> Unit,
    onOpenPaywall: (PaywallSource) -> Unit,
    onHome: () -> Unit,
    onRevisit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        when (state.phase) {
            SessionPhase.LIMIT_REACHED -> LimitReachedPanel(
                queueCount = state.queueCount,
                resetsAtMillis = state.allowance?.resetsAtMillis,
                onOpenQueue = onOpenQueue,
                onUnlock = { onOpenPaywall(PaywallSource.LIMIT) },
                onHome = onHome,
            )

            SessionPhase.COMPLETED -> {
                EmptyState(
                    icon = Icons.Outlined.DoneAll,
                    title = stringResource(R.string.completed_title),
                    body = stringResource(
                        R.string.completed_body,
                        pluralStringResource(R.plurals.completed_kept, state.keptInSession, state.keptInSession),
                        pluralStringResource(R.plurals.completed_queued, state.queuedInSession, state.queuedInSession),
                    ),
                    actionLabel = if (state.queueCount > 0) {
                        stringResource(R.string.session_review_queue, state.queueCount)
                    } else {
                        stringResource(R.string.action_back_home)
                    },
                    onAction = if (state.queueCount > 0) onOpenQueue else onHome,
                    secondaryLabel = if (state.queueCount > 0) stringResource(R.string.action_back_home) else null,
                    onSecondary = if (state.queueCount > 0) onHome else null,
                )
            }

            SessionPhase.ALL_REVIEWED -> EmptyState(
                icon = Icons.Outlined.DoneAll,
                title = stringResource(R.string.all_reviewed_title),
                body = stringResource(R.string.all_reviewed_body),
                actionLabel = stringResource(R.string.all_reviewed_revisit),
                onAction = onRevisit,
                secondaryLabel = stringResource(R.string.action_back_home),
                onSecondary = onHome,
            )

            SessionPhase.NO_PHOTOS -> EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.no_photos_title),
                body = stringResource(R.string.no_photos_body),
                actionLabel = stringResource(R.string.action_back_home),
                onAction = onHome,
            )

            SessionPhase.PERMISSION_NEEDED -> {
                val actions = rememberPhotoAccessActions(container, PhotoAccess.NONE, permissionRequestedBefore)
                EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = stringResource(R.string.session_permission_title),
                    body = stringResource(R.string.session_permission_body),
                    actionLabel = stringResource(
                        if (actions.mustUseSettings) R.string.permission_open_settings else R.string.permission_allow,
                    ),
                    onAction = actions.requestOrManage,
                    secondaryLabel = stringResource(R.string.action_back_home),
                    onSecondary = onHome,
                )
            }

            SessionPhase.PREMIUM_REQUIRED -> EmptyState(
                icon = Icons.Outlined.WorkspacePremium,
                title = stringResource(R.string.session_premium_title),
                body = stringResource(R.string.session_premium_body),
                actionLabel = stringResource(R.string.action_unlock_premium),
                onAction = { onOpenPaywall(PaywallSource.PRO_FEATURE) },
                secondaryLabel = stringResource(R.string.action_back_home),
                onSecondary = onHome,
            )

            SessionPhase.NOT_FOUND -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.session_not_found_title),
                body = stringResource(R.string.session_not_found_body),
                actionLabel = stringResource(R.string.action_back_home),
                onAction = onHome,
            )

            SessionPhase.LOADING, SessionPhase.REVIEWING -> Unit
        }
    }
}

/**
 * Shown after the 50th free review. Queued photos stay fully reachable (and free to remove);
 * Premium is offered but never required to leave this state.
 */
@Composable
internal fun LimitReachedPanel(
    queueCount: Int,
    resetsAtMillis: Long?,
    onOpenQueue: () -> Unit,
    onUnlock: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Outlined.HourglassEmpty,
            title = stringResource(R.string.limit_title),
            body = stringResource(R.string.limit_body, resetsAtMillis?.let { Format.time(it) } ?: "00:00"),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (queueCount > 0) {
                PrimaryButton(
                    pluralStringResource(R.plurals.limit_review_queue, queueCount, queueCount),
                    onOpenQueue,
                    Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    stringResource(R.string.action_unlock_unlimited),
                    onUnlock,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.WorkspacePremium,
                )
            } else {
                PrimaryButton(stringResource(R.string.action_unlock_unlimited), onUnlock, Modifier.fillMaxWidth())
            }
            QuietButton(stringResource(R.string.action_back_home), onHome, Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(Space.l))
    }
}

/** Factual description for TalkBack. Never guesses what the photo shows. */
private fun describePhoto(context: android.content.Context, photo: PhotoRef): String {
    val parts = mutableListOf(context.getString(R.string.a11y_photo))
    photo.effectiveDateMillis?.let {
        parts += context.getString(if (photo.hasCaptureDate) R.string.meta_captured else R.string.meta_added, Format.longDate(it))
    }
    photo.sizeBytes?.let { parts += Format.bytes(context, it) }
    if (photo.width != null && photo.height != null) {
        parts += context.getString(R.string.a11y_dimensions, photo.width, photo.height)
    }
    parts += context.getString(R.string.a11y_swipe_hint)
    return parts.joinToString(", ")
}
