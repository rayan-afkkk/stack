package com.swipegallery.ui.review

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.data.media.contentUri
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.review.QueueEstimate
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.trash.TrashSummary
import com.swipegallery.ui.components.CenteredLoading
import com.swipegallery.ui.components.Divider
import com.swipegallery.ui.components.EmptyState
import com.swipegallery.ui.components.Notice
import com.swipegallery.ui.components.PhotoImage
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.QuietButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.SecondaryButton
import com.swipegallery.ui.components.SwipeCard
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.rememberPhotoAccessActions
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.Format

@Composable
fun ReviewScreen(container: AppContainer, onStartCleanup: () -> Unit) {
    val vm = containerViewModel { c, _ -> ReviewViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by container.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    val actions = rememberPhotoAccessActions(container, state.access, prefs?.photoPermissionRequested == true)
    var previewKey by rememberSaveable { mutableStateOf<String?>(null) }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onSystemResult(confirmed = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(vm) {
        vm.launches.collect { request ->
            try {
                trashLauncher.launch(request)
            } catch (e: RuntimeException) {
                vm.onLaunchFailed()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reconcile() }

    Screen {
        Column(Modifier.fillMaxSize().readableWidth(900.dp)) {
            val thumbPx = with(LocalDensity.current) { 160.dp.roundToPx() }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.topSafeArea().padding(top = Space.xl, bottom = Space.s)) {
                        ScreenHeading(stringResource(R.string.review_title))
                        if (state.tiles.isNotEmpty()) {
                            Spacer(Modifier.height(Space.s))
                            Text(
                                pluralStringResource(R.plurals.review_subtitle, state.tiles.size, Format.count(state.tiles.size)),
                                style = SwipeTheme.type.body,
                                color = SwipeTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                state.result?.let { summary ->
                    item(span = { GridItemSpan(maxLineSpan) }) { ResultCard(summary, state.prepareFailed, vm::dismissResult) }
                }
                if (state.result == null && state.prepareFailed) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Notice(Icons.Outlined.Info, stringResource(R.string.trash_prepare_failed), actionLabel = stringResource(R.string.action_dismiss), onAction = vm::dismissResult)
                    }
                }
                state.notice?.let { notice ->
                    item(span = { GridItemSpan(maxLineSpan) }) { ReconcileNotice(notice, vm::dismissResult) }
                }
                when {
                    !state.loaded -> item(span = { GridItemSpan(maxLineSpan) }) { CenteredLoading() }
                    state.tiles.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Outlined.DeleteOutline,
                            title = stringResource(R.string.review_empty_title),
                            body = stringResource(R.string.review_empty_body),
                            actionLabel = stringResource(R.string.review_empty_cta),
                            onAction = onStartCleanup,
                        )
                    }

                    state.access == PhotoAccess.NONE -> item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Outlined.PhotoLibrary,
                            title = stringResource(R.string.permission_title),
                            body = pluralStringResource(R.plurals.review_no_access_body, state.tiles.size, Format.count(state.tiles.size)),
                            actionLabel = stringResource(
                                if (actions.mustUseSettings) R.string.permission_open_settings else R.string.permission_allow,
                            ),
                            onAction = actions.requestOrManage,
                        )
                    }

                    else -> {
                        if (state.access == PhotoAccess.SELECTED && state.tiles.any { !it.accessible }) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Notice(
                                    icon = Icons.Outlined.Lock,
                                    text = stringResource(R.string.review_inaccessible_notice),
                                    actionLabel = stringResource(R.string.access_manage),
                                    onAction = actions.requestOrManage,
                                )
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SelectionBar(
                                selected = state.selectedItems.size,
                                total = state.accessibleCount,
                                onAll = vm::selectAll,
                                onNone = vm::selectNone,
                                enabled = !state.busy,
                            )
                        }
                        items(state.tiles, key = { it.item.key }, contentType = { "thumb" }) { tile ->
                            QueueThumb(
                                tile = tile,
                                sizePx = thumbPx,
                                enabled = !state.busy,
                                onToggle = { vm.toggle(tile.item.key) },
                                onOpen = { previewKey = tile.item.key },
                            )
                        }
                    }
                }
            }
            if (state.access != PhotoAccess.NONE && state.tiles.isNotEmpty()) {
                TrashPanel(
                    selectedCount = state.selectedItems.size,
                    estimate = state.selectedEstimate,
                    busy = state.busy,
                    onMove = vm::moveSelectedToTrash,
                )
            }
        }
    }

    val preview = previewKey?.let { key -> state.tiles.firstOrNull { it.item.key == key } }
    if (preview != null) {
        PreviewDialog(
            item = preview.item,
            selected = preview.selected,
            accessible = preview.accessible,
            onToggle = { vm.toggle(preview.item.key) },
            onKeepInstead = {
                vm.keepInstead(preview.item.key)
                previewKey = null
            },
            onClose = { previewKey = null },
        )
    } else if (previewKey != null) {
        // The previewed photo left the queue (kept, trashed or removed elsewhere).
        LaunchedEffect(Unit) { previewKey = null }
    }
}

@Composable
private fun SelectionBar(selected: Int, total: Int, onAll: () -> Unit, onNone: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.review_selected_of, Format.count(selected), Format.count(total)),
            style = SwipeTheme.type.label,
            color = SwipeTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            if (selected < total) QuietButton(stringResource(R.string.review_select_all), onAll)
            else QuietButton(stringResource(R.string.review_select_none), onNone)
        }
    }
}

@Composable
private fun QueueThumb(tile: QueueTile, sizePx: Int, enabled: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = SwipeTheme.colors
    val shape = RoundedCornerShape(Radii.small)
    val context = LocalContext.current
    val description = queuedDescription(context, tile.item)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, if (tile.selected) c.textPrimary.copy(alpha = 0.6f) else c.border, shape),
    ) {
        PhotoImage(
            uri = tile.item.contentUri(),
            contentDescription = null,
            widthPx = sizePx,
            heightPx = sizePx,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (tile.selected) 1f else 0.45f)
                .clickable(onClickLabel = stringResource(R.string.review_preview), role = Role.Image, onClick = onOpen)
                .semantics { contentDescription = description },
        )
        if (tile.accessible) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .toggleable(value = tile.selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.scrim), contentAlignment = Alignment.Center) {
                    Icon(
                        if (tile.selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (tile.selected) c.keep else c.textPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.review_tile_no_access),
                tint = c.textPrimary,
                modifier = Modifier.align(Alignment.Center).size(22.dp),
            )
        }
    }
}

@Composable
private fun TrashPanel(selectedCount: Int, estimate: QueueEstimate, busy: Boolean, onMove: () -> Unit) {
    val c = SwipeTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(c.background)) {
        Divider()
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.m)) {
            if (selectedCount > 0) {
                val size = if (estimate.knownBytes > 0) {
                    stringResource(R.string.review_estimate, Format.bytes(context, estimate.knownBytes))
                } else {
                    stringResource(R.string.review_estimate_unknown)
                }
                Text(size, style = SwipeTheme.type.bodySmall, color = c.textPrimary)
                if (estimate.unknownSizeCount > 0 && estimate.knownBytes > 0) {
                    Text(
                        pluralStringResource(R.plurals.review_estimate_unknown_count, estimate.unknownSizeCount, estimate.unknownSizeCount),
                        style = SwipeTheme.type.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(Space.s))
            }
            PrimaryButton(
                text = if (selectedCount > 0) {
                    pluralStringResource(R.plurals.review_move_cta, selectedCount, Format.count(selectedCount))
                } else {
                    stringResource(R.string.review_move_none)
                },
                onClick = onMove,
                enabled = selectedCount > 0 && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.review_consequence), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun ResultCard(summary: TrashSummary, prepareFailed: Boolean, onDismiss: () -> Unit) {
    val c = SwipeTheme.colors
    SwipeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = Space.xl, end = Space.s, top = Space.l, bottom = Space.l)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = Space.s)) {
                val headline = when {
                    summary.moved > 0 -> pluralStringResource(R.plurals.trash_moved, summary.moved, Format.count(summary.moved))
                    summary.cancelled -> stringResource(R.string.trash_cancelled)
                    prepareFailed -> stringResource(R.string.trash_prepare_failed)
                    else -> stringResource(R.string.trash_nothing_moved)
                }
                Text(headline, style = SwipeTheme.type.title, color = c.textPrimary)
                val lines = buildList {
                    if (summary.moved > 0 && summary.notMoved > 0) {
                        add(pluralStringResource(R.plurals.trash_not_moved, summary.notMoved, Format.count(summary.notMoved)))
                    }
                    if (summary.missing > 0) add(pluralStringResource(R.plurals.trash_missing, summary.missing, Format.count(summary.missing)))
                    if (summary.changed > 0) add(pluralStringResource(R.plurals.trash_changed, summary.changed, Format.count(summary.changed)))
                    if (summary.inaccessible > 0) {
                        add(pluralStringResource(R.plurals.trash_inaccessible, summary.inaccessible, Format.count(summary.inaccessible)))
                    }
                    if (summary.moved > 0) add(stringResource(R.string.trash_storage_note))
                    if (summary.cancelled && summary.moved == 0) add(stringResource(R.string.trash_retry_hint))
                }
                lines.forEach {
                    Spacer(Modifier.height(Space.xs))
                    Text(it, style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_dismiss), tint = c.textSecondary)
            }
        }
    }
}

@Composable
private fun ReconcileNotice(notice: QueueNotice, onDismiss: () -> Unit) {
    val parts = buildList {
        if (notice.missing > 0) add(pluralStringResource(R.plurals.trash_missing, notice.missing, Format.count(notice.missing)))
        if (notice.changed > 0) add(pluralStringResource(R.plurals.trash_changed, notice.changed, Format.count(notice.changed)))
    }
    Notice(Icons.Outlined.Info, parts.joinToString(" "), actionLabel = stringResource(R.string.action_dismiss), onAction = onDismiss)
}

@Composable
private fun PreviewDialog(
    item: QueueItem,
    selected: Boolean,
    accessible: Boolean,
    onToggle: () -> Unit,
    onKeepInstead: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val c = SwipeTheme.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.background).topSafeArea().bottomSafeArea()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = c.textPrimary)
                }
                Text(
                    item.photo.dateMillis?.let { Format.date(it) } ?: stringResource(R.string.meta_no_date),
                    style = SwipeTheme.type.title,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            val density = LocalDensity.current
            val px = with(density) { 1200.dp.roundToPx() }
            PhotoImage(
                uri = item.contentUri(),
                contentDescription = queuedDescription(context, item),
                widthPx = px,
                heightPx = px,
                contentScale = ContentScale.Fit,
                showErrorText = true,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Space.l),
            )
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    item.photo.sizeBytes?.let { Format.bytes(context, it) } ?: stringResource(R.string.meta_size_unknown),
                    style = SwipeTheme.type.bodySmall,
                    color = c.textSecondary,
                )
                if (accessible) {
                    SecondaryButton(
                        stringResource(if (selected) R.string.review_exclude else R.string.review_include),
                        onToggle,
                        Modifier.fillMaxWidth(),
                    )
                }
                PrimaryButton(stringResource(R.string.review_keep_instead), onKeepInstead, Modifier.fillMaxWidth())
                Text(stringResource(R.string.review_keep_instead_note), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
            }
        }
    }
}

private fun queuedDescription(context: android.content.Context, item: QueueItem): String {
    val parts = mutableListOf(context.getString(R.string.a11y_queued_photo))
    item.photo.dateMillis?.let { parts += Format.longDate(it) }
    item.photo.sizeBytes?.let { parts += Format.bytes(context, it) }
    return parts.joinToString(", ")
}
