package com.swipegallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.domain.allowance.AllowanceSnapshot
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.ui.components.Badge
import com.swipegallery.ui.components.CenteredLoading
import com.swipegallery.ui.components.EmptyState
import com.swipegallery.ui.components.Notice
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.QuietButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.SlimProgress
import com.swipegallery.ui.components.SwipeCard
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.rememberPhotoAccessActions
import com.swipegallery.ui.components.scopeTitle
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.PaywallSource
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.Format

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenSetup: (SessionScope) -> Unit,
    onResume: (Long) -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenPaywall: (PaywallSource) -> Unit,
) {
    val vm = containerViewModel { c, _ -> HomeViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val access = (state.library as? LibraryState.Ready)?.access
        ?: if (state.library is LibraryState.NoAccess) PhotoAccess.NONE else null
    val actions = rememberPhotoAccessActions(
        container = container,
        access = access ?: PhotoAccess.NONE,
        permissionRequestedBefore = state.prefs?.photoPermissionRequested == true,
    )

    Screen {
        LazyColumn(
            modifier = Modifier.readableWidth(720.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            item {
                Column(Modifier.topSafeArea().padding(top = Space.xl, bottom = Space.s)) {
                    ScreenHeading(stringResource(R.string.home_title))
                }
            }
            item { AllowanceCard(state.allowance, onUnlock = { onOpenPaywall(PaywallSource.LIMIT) }) }

            when (val lib = state.library) {
                LibraryState.Loading -> item { CenteredLoading() }
                LibraryState.NoAccess -> item {
                    PermissionCard(
                        mustUseSettings = actions.mustUseSettings,
                        onAllow = actions.requestOrManage,
                    )
                }

                LibraryState.Failed -> item {
                    EmptyState(
                        icon = Icons.Outlined.ErrorOutline,
                        title = stringResource(R.string.library_failed_title),
                        body = stringResource(R.string.library_failed_body),
                        actionLabel = stringResource(R.string.action_try_again),
                        onAction = { container.media.refresh() },
                    )
                }

                is LibraryState.Ready -> {
                    if (lib.access == PhotoAccess.SELECTED) {
                        item {
                            Notice(
                                icon = Icons.Outlined.Lock,
                                text = stringResource(R.string.access_selected_notice),
                                actionLabel = stringResource(R.string.access_manage),
                                onAction = actions.requestOrManage,
                            )
                        }
                    }
                    if (lib.totalPhotos == 0) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.PhotoLibrary,
                                title = stringResource(R.string.library_empty_title),
                                body = stringResource(
                                    if (lib.access == PhotoAccess.SELECTED) R.string.library_empty_selected_body else R.string.library_empty_body,
                                ),
                                actionLabel = if (lib.access == PhotoAccess.SELECTED) stringResource(R.string.access_manage) else null,
                                onAction = if (lib.access == PhotoAccess.SELECTED) actions.requestOrManage else null,
                            )
                        }
                    } else {
                        state.resumable?.let { resumable ->
                            item { ContinueCard(resumable, onClick = { onResume(resumable.id) }) }
                        }
                        item {
                            CategoryGrid(
                                counts = lib.counts,
                                isPremium = state.isPremium,
                                selectedOnly = lib.access == PhotoAccess.SELECTED,
                                onAll = { onOpenSetup(SessionScope.AllPhotos) },
                                onMonth = { onOpenSetup(SessionScope.Month(state.currentMonth)) },
                                onAlbums = onOpenAlbums,
                                onLarge = {
                                    if (state.isPremium) onOpenSetup(SessionScope.LargePhotos(DEFAULT_LARGE_BYTES))
                                    else onOpenPaywall(PaywallSource.PRO_FEATURE)
                                },
                            )
                        }
                    }
                }
            }

            if (state.queueCount > 0) {
                item { QueueShortcut(state.queueCount, onOpenReview) }
            }
            if (state.showUpgradeCard) {
                item {
                    UpgradeCard(
                        onOpen = { onOpenPaywall(PaywallSource.UPGRADE_CARD) },
                        onDismiss = vm::dismissUpgradeCard,
                    )
                }
            }
            item { Spacer(Modifier.height(Space.xl)) }
        }
    }
}

@Composable
fun AllowanceCard(allowance: AllowanceSnapshot?, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val c = SwipeTheme.colors
    SwipeCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.allowance_label), style = SwipeTheme.type.label, color = c.textSecondary)
        Spacer(Modifier.height(Space.s))
        when {
            allowance == null -> Text("—", style = SwipeTheme.type.numeral, color = c.textPrimary)
            allowance.isPremium -> {
                Text(stringResource(R.string.allowance_unlimited), style = SwipeTheme.type.headline, color = c.textPrimary)
                Spacer(Modifier.height(Space.xs))
                Text(
                    pluralStringResource(R.plurals.allowance_premium_today, allowance.used, allowance.used),
                    style = SwipeTheme.type.bodySmall,
                    color = c.textSecondary,
                )
            }

            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(allowance.remaining.toString(), style = SwipeTheme.type.numeral, color = c.textPrimary)
                    Spacer(Modifier.size(Space.s))
                    Text(
                        pluralStringResource(R.plurals.allowance_remaining_suffix, allowance.remaining),
                        style = SwipeTheme.type.body,
                        color = c.textPrimary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(Space.m))
                SlimProgress(
                    progress = allowance.progress,
                    modifier = Modifier.semantics {
                        contentDescription = "${allowance.usedForDisplay} / ${allowance.limit}"
                    },
                )
                Spacer(Modifier.height(Space.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.allowance_used, allowance.usedForDisplay, allowance.limit),
                        style = SwipeTheme.type.bodySmall,
                        color = c.textSecondary,
                    )
                    Text(
                        stringResource(R.string.allowance_resets, Format.time(allowance.resetsAtMillis)),
                        style = SwipeTheme.type.bodySmall,
                        color = c.textSecondary,
                    )
                }
                if (allowance.isExhausted) {
                    Spacer(Modifier.height(Space.l))
                    PrimaryButton(stringResource(R.string.action_unlock_unlimited), onUnlock, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(mustUseSettings: Boolean, onAllow: () -> Unit) {
    val c = SwipeTheme.colors
    SwipeCard(Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(Space.l))
        Text(stringResource(R.string.permission_title), style = SwipeTheme.type.headline, color = c.textPrimary)
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.permission_body), style = SwipeTheme.type.body, color = c.textSecondary)
        Spacer(Modifier.height(Space.s))
        Text(
            stringResource(if (mustUseSettings) R.string.permission_denied_body else R.string.permission_choice_body),
            style = SwipeTheme.type.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Space.xl))
        PrimaryButton(
            stringResource(if (mustUseSettings) R.string.permission_open_settings else R.string.permission_allow),
            onAllow,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ContinueCard(resumable: ResumableSession, onClick: () -> Unit) {
    val c = SwipeTheme.colors
    SwipeCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = c.textPrimary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_continue), style = SwipeTheme.type.title, color = c.textPrimary)
                Text(
                    scopeTitle(resumable.spec.scope) + " · " +
                        pluralStringResource(R.plurals.photos_left, resumable.remaining, Format.count(resumable.remaining)),
                    style = SwipeTheme.type.bodySmall,
                    color = c.textSecondary,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.textSecondary)
        }
    }
}

@Composable
private fun CategoryGrid(
    counts: LibraryCounts,
    isPremium: Boolean,
    selectedOnly: Boolean,
    onAll: () -> Unit,
    onMonth: () -> Unit,
    onAlbums: () -> Unit,
    onLarge: () -> Unit,
) {
    val c = SwipeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            CategoryCard(
                title = stringResource(if (selectedOnly) R.string.home_card_all_selected else R.string.home_card_all),
                detail = pluralStringResource(R.plurals.to_review, counts.allUnreviewed, Format.count(counts.allUnreviewed)),
                icon = Icons.Outlined.Collections,
                container = c.blueCard,
                content = c.onBlueCard,
                secondary = c.onBlueCardSecondary,
                onClick = onAll,
                modifier = Modifier.weight(1f),
            )
            CategoryCard(
                title = stringResource(R.string.home_card_month),
                detail = pluralStringResource(R.plurals.to_review, counts.monthUnreviewed, Format.count(counts.monthUnreviewed)),
                icon = Icons.Outlined.CalendarMonth,
                container = c.surface,
                content = c.textPrimary,
                secondary = c.textSecondary,
                onClick = onMonth,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            CategoryCard(
                title = stringResource(R.string.home_card_albums),
                detail = pluralStringResource(R.plurals.albums_count, counts.albums, Format.count(counts.albums)),
                icon = Icons.Outlined.PhotoLibrary,
                container = c.mintCard,
                content = c.onPastel,
                secondary = c.onPastelSecondary,
                onClick = onAlbums,
                modifier = Modifier.weight(1f),
            )
            CategoryCard(
                title = stringResource(R.string.home_card_large),
                detail = if (isPremium) {
                    pluralStringResource(R.plurals.large_count, counts.largeUnreviewed, Format.count(counts.largeUnreviewed))
                } else {
                    stringResource(R.string.home_card_large_locked)
                },
                icon = Icons.Outlined.PhotoSizeSelectLarge,
                container = c.lavenderCard,
                content = c.onPastel,
                secondary = c.onPastelSecondary,
                onClick = onLarge,
                badge = stringResource(R.string.badge_pro),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    detail: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    secondary: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    SwipeCard(
        modifier = modifier.fillMaxHeight().heightIn(min = 148.dp),
        color = container,
        bordered = container == SwipeTheme.colors.surface,
        shape = RoundedCornerShape(Radii.card),
        contentPadding = PaddingValues(Space.l + Space.xs),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            if (badge != null) {
                Badge(badge, container = content, content = container, bordered = false)
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = Space.xl))
        Text(title, style = SwipeTheme.type.title, color = content)
        Spacer(Modifier.height(2.dp))
        Text(detail, style = SwipeTheme.type.bodySmall, color = secondary)
    }
}

@Composable
private fun QueueShortcut(count: Int, onClick: () -> Unit) {
    val c = SwipeTheme.colors
    SwipeCard(
        Modifier.fillMaxWidth(),
        color = c.surfaceSecondary,
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.l),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = c.textPrimary)
            Text(
                pluralStringResource(R.plurals.home_queue_shortcut, count, Format.count(count)),
                style = SwipeTheme.type.body,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.textSecondary)
        }
    }
}

@Composable
private fun UpgradeCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = SwipeTheme.colors
    SwipeCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = Space.xl, end = Space.s, top = Space.s, bottom = Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_dismiss), tint = c.textSecondary)
            }
        }
        Box(Modifier.padding(end = Space.l)) {
            Column {
                Text(stringResource(R.string.upgrade_card_title), style = SwipeTheme.type.title, color = c.textPrimary)
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.upgrade_card_body), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(Space.s))
                QuietButton(stringResource(R.string.upgrade_card_cta), onOpen, color = c.textPrimary)
            }
        }
    }
}
