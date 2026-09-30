package com.swipegallery.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.domain.session.SessionOrder
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.ui.components.Badge
import com.swipegallery.ui.components.CenteredLoading
import com.swipegallery.ui.components.EmptyState
import com.swipegallery.ui.components.PrimaryButton
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.SectionLabel
import com.swipegallery.ui.components.SegmentedPill
import com.swipegallery.ui.components.SwipeCard
import com.swipegallery.ui.components.bottomSafeArea
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.scopeTitle
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.Format

@Composable
fun SessionSetupScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onStart: (Long) -> Unit,
    onOpenPaywall: () -> Unit,
) {
    val vm = containerViewModel { c, handle -> SessionSetupViewModel(c, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val c = SwipeTheme.colors

    Screen {
        Column(Modifier.fillMaxSize().topSafeArea().bottomSafeArea().readableWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = c.textPrimary)
                }
            }
            when (val s = state) {
                SetupUiState.Loading -> CenteredLoading()
                SetupUiState.Invalid -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(R.string.library_failed_title),
                    body = stringResource(R.string.library_failed_body),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack,
                )

                SetupUiState.NoAccess -> EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = stringResource(R.string.permission_title),
                    body = stringResource(R.string.permission_body),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack,
                )

                is SetupUiState.Ready -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Space.gutter),
                    ) {
                        val scope = s.choice.scope
                        ScreenHeading(
                            if (scope is SessionScope.Month) stringResource(R.string.scope_month_title) else scopeTitle(scope),
                        )
                        Spacer(Modifier.height(Space.s))
                        Text(
                            stringResource(R.string.setup_subtitle),
                            style = SwipeTheme.type.body,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(Space.xl))

                        if (scope is SessionScope.Month) {
                            SectionLabel(stringResource(R.string.setup_month))
                            Spacer(Modifier.height(Space.s))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(Space.s),
                                contentPadding = PaddingValues(end = Space.l),
                            ) {
                                items(s.months, key = { it.yearMonth.toString() }) { bucket ->
                                    val selected = bucket.yearMonth == scope.yearMonth
                                    Column(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (selected) c.primaryButton else c.surfaceSecondary)
                                            .border(1.dp, c.border, CircleShape)
                                            .selectable(selected = selected, role = Role.RadioButton) { vm.setMonth(bucket.yearMonth) }
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = Space.l, vertical = Space.s),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            Format.shortMonth(bucket.yearMonth),
                                            style = SwipeTheme.type.label,
                                            color = if (selected) c.onPrimaryButton else c.textPrimary,
                                        )
                                        Text(
                                            Format.count(bucket.unreviewed),
                                            style = SwipeTheme.type.label,
                                            color = if (selected) c.onPrimaryButton else c.textSecondary,
                                        )
                                    }
                                }
                            }
                            if (s.months.none { it.yearMonth == scope.yearMonth }) {
                                Spacer(Modifier.height(Space.s))
                                Text(
                                    stringResource(R.string.setup_month_empty, Format.month(scope.yearMonth)),
                                    style = SwipeTheme.type.bodySmall,
                                    color = c.textSecondary,
                                )
                            }
                            Spacer(Modifier.height(Space.xl))
                        }

                        if (scope is SessionScope.LargePhotos) {
                            val context = LocalContext.current
                            SectionLabel(stringResource(R.string.setup_min_size))
                            Spacer(Modifier.height(Space.s))
                            SegmentedPill(
                                options = LARGE_SIZE_OPTIONS,
                                selected = scope.minBytes,
                                label = { stringResource(R.string.setup_size_option, Format.bytes(context, it)) },
                                onSelect = vm::setMinSize,
                            )
                            Spacer(Modifier.height(Space.xl))
                        }

                        SectionLabel(stringResource(R.string.setup_order))
                        Spacer(Modifier.height(Space.s))
                        val orders = if (scope is SessionScope.LargePhotos) {
                            listOf(SessionOrder.LARGEST, SessionOrder.NEWEST, SessionOrder.OLDEST)
                        } else {
                            listOf(SessionOrder.NEWEST, SessionOrder.OLDEST)
                        }
                        SegmentedPill(
                            options = orders,
                            selected = s.choice.order,
                            label = {
                                stringResource(
                                    when (it) {
                                        SessionOrder.NEWEST -> R.string.order_newest
                                        SessionOrder.OLDEST -> R.string.order_oldest
                                        SessionOrder.LARGEST -> R.string.order_largest
                                    },
                                )
                            },
                            onSelect = vm::setOrder,
                        )
                        Spacer(Modifier.height(Space.xl))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.setup_revisit), style = SwipeTheme.type.body, color = c.textPrimary)
                                Text(stringResource(R.string.setup_revisit_body), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                            }
                            Switch(
                                checked = s.choice.includeReviewed,
                                onCheckedChange = vm::setIncludeReviewed,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = c.primaryButton,
                                    checkedThumbColor = c.onPrimaryButton,
                                    uncheckedTrackColor = c.surfaceSecondary,
                                    uncheckedBorderColor = c.border,
                                    uncheckedThumbColor = c.textSecondary,
                                ),
                            )
                        }
                        Spacer(Modifier.height(Space.xl))

                        SwipeCard(Modifier.fillMaxWidth()) {
                            Text(
                                pluralStringResource(R.plurals.setup_to_review, s.toReview, Format.count(s.toReview)),
                                style = SwipeTheme.type.headline,
                                color = c.textPrimary,
                            )
                            if (s.alreadyReviewed > 0) {
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    pluralStringResource(R.plurals.setup_already_reviewed, s.alreadyReviewed, Format.count(s.alreadyReviewed)),
                                    style = SwipeTheme.type.bodySmall,
                                    color = c.textSecondary,
                                )
                            }
                            if (s.unknownSize > 0) {
                                Spacer(Modifier.height(Space.xs))
                                Text(
                                    pluralStringResource(R.plurals.setup_unknown_size, s.unknownSize, Format.count(s.unknownSize)),
                                    style = SwipeTheme.type.bodySmall,
                                    color = c.textSecondary,
                                )
                            }
                            if (s.access == com.swipegallery.domain.media.PhotoAccess.SELECTED) {
                                Spacer(Modifier.height(Space.xs))
                                Text(stringResource(R.string.setup_selected_only), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                            }
                        }
                        if (s.toReview == 0) {
                            EmptyState(
                                icon = if (s.inScope == 0) Icons.Outlined.PhotoLibrary else Icons.Outlined.DoneAll,
                                title = stringResource(if (s.inScope == 0) R.string.setup_none_title else R.string.setup_all_reviewed_title),
                                body = stringResource(if (s.inScope == 0) R.string.setup_none_body else R.string.setup_all_reviewed_body),
                            )
                        }
                        Spacer(Modifier.height(Space.xl))
                    }
                    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.l)) {
                        if (s.locked) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                Badge(stringResource(R.string.badge_pro))
                                Text(stringResource(R.string.setup_pro_note), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                            }
                            Spacer(Modifier.height(Space.m))
                            PrimaryButton(stringResource(R.string.action_unlock_premium), onOpenPaywall, Modifier.fillMaxWidth())
                        } else {
                            PrimaryButton(
                                text = stringResource(if (s.resumableSessionId != null) R.string.setup_resume else R.string.setup_start),
                                onClick = { vm.start(onStart) },
                                enabled = s.toReview > 0,
                                loading = starting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
