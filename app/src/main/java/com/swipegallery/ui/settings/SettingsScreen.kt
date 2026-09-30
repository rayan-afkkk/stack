package com.swipegallery.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.BuildConfig
import com.swipegallery.R
import com.swipegallery.data.prefs.ThemeMode
import com.swipegallery.data.prefs.UserPreferences
import com.swipegallery.debug.DebugTools
import com.swipegallery.domain.allowance.AllowanceSnapshot
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.ui.components.Badge
import com.swipegallery.ui.components.Divider
import com.swipegallery.ui.components.ListRow
import com.swipegallery.ui.components.Notice
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.SectionLabel
import com.swipegallery.ui.components.SegmentedPill
import com.swipegallery.ui.components.SwipeCard
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.rememberPhotoAccessActions
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.home.AllowanceCard
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.composeEmail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val prefs: UserPreferences? = null,
    val allowance: AllowanceSnapshot? = null,
    val isPremium: Boolean = false,
    val access: PhotoAccess = PhotoAccess.NONE,
    val resetDone: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val resetDone = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        container.preferences.preferences,
        container.allowance.snapshot,
        container.premium,
        container.media.access,
        resetDone,
    ) { prefs, allowance, premium, access, reset ->
        SettingsUiState(prefs, allowance, premium == true, access, reset)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.preferences.setThemeMode(mode) }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { container.preferences.setHapticsEnabled(enabled) }
    }

    /** Clears review markers only. Photos, the queue, today's usage and Premium are untouched. */
    fun resetHistory() {
        viewModelScope.launch {
            container.engine.resetReviewHistory()
            resetDone.value = true
        }
    }

    fun dismissResetNotice() {
        resetDone.value = false
    }
}

@Composable
fun SettingsScreen(
    container: AppContainer,
    onReplayOnboarding: () -> Unit,
    onOpenPaywall: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val vm = containerViewModel { c, _ -> SettingsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = SwipeTheme.colors
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val actions = rememberPhotoAccessActions(container, state.access, state.prefs?.photoPermissionRequested == true)

    Screen {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = Space.gutter),
        ) {
            Column(Modifier.topSafeArea().padding(top = Space.xl, bottom = Space.l)) {
                ScreenHeading(stringResource(R.string.settings_title))
            }

            SwipeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(c.surfaceSecondary).border(1.dp, c.border, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = c.textPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_on_this_device), style = SwipeTheme.type.title, color = c.textPrimary)
                        Text(stringResource(R.string.settings_device_body), style = SwipeTheme.type.bodySmall, color = c.textSecondary)
                    }
                    if (state.isPremium) {
                        Badge(stringResource(R.string.badge_premium), container = c.lavenderCard, content = c.onPastel, bordered = false)
                    } else {
                        Badge(stringResource(R.string.badge_free))
                    }
                }
            }
            Spacer(Modifier.height(Space.l))
            AllowanceCard(state.allowance, onUnlock = onOpenPaywall)
            if (state.allowance?.isPremium == false && state.allowance?.isExhausted == false) {
                Spacer(Modifier.height(Space.s))
                ListRow(
                    icon = Icons.Outlined.WorkspacePremium,
                    title = stringResource(R.string.action_unlock_unlimited),
                    onClick = onOpenPaywall,
                )
            }

            Spacer(Modifier.height(Space.xl))
            SectionLabel(stringResource(R.string.settings_appearance))
            Spacer(Modifier.height(Space.s))
            val prefs = state.prefs
            if (prefs != null) {
                SegmentedPill(
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    selected = prefs.themeMode,
                    label = {
                        stringResource(
                            when (it) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            },
                        )
                    },
                    onSelect = vm::setTheme,
                )
            }

            if (state.resetDone) {
                Spacer(Modifier.height(Space.l))
                Notice(
                    icon = Icons.Outlined.RestartAlt,
                    text = stringResource(R.string.reset_done),
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = vm::dismissResetNotice,
                )
            }

            Spacer(Modifier.height(Space.xl))
            ListRow(Icons.Outlined.Replay, stringResource(R.string.settings_view_onboarding), onClick = onReplayOnboarding)
            Divider()
            ListRow(
                Icons.Outlined.WorkspacePremium,
                stringResource(R.string.settings_premium),
                subtitle = stringResource(if (state.isPremium) R.string.settings_premium_active else R.string.settings_premium_sub),
                onClick = onOpenPaywall,
            )
            Divider()
            ListRow(
                Icons.Outlined.PhotoLibrary,
                stringResource(R.string.settings_photo_access),
                subtitle = stringResource(
                    when (state.access) {
                        PhotoAccess.FULL -> R.string.access_full
                        PhotoAccess.SELECTED -> R.string.access_selected
                        PhotoAccess.NONE -> R.string.access_none
                    },
                ),
                onClick = if (state.access == PhotoAccess.FULL) actions.openSettings else actions.requestOrManage,
            )
            Divider()
            ListRow(
                Icons.Outlined.Vibration,
                stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_sub),
                onClick = { prefs?.let { vm.setHaptics(!it.hapticsEnabled) } },
                trailing = {
                    Switch(
                        checked = prefs?.hapticsEnabled ?: true,
                        onCheckedChange = vm::setHaptics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = c.primaryButton,
                            checkedThumbColor = c.onPrimaryButton,
                            uncheckedTrackColor = c.surfaceSecondary,
                            uncheckedBorderColor = c.border,
                            uncheckedThumbColor = c.textSecondary,
                        ),
                    )
                },
            )
            Divider()
            val supportEmail = BuildConfig.SUPPORT_EMAIL
            if (supportEmail.isNotBlank()) {
                val subject = stringResource(R.string.feedback_subject)
                val body = stringResource(R.string.feedback_body, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
                ListRow(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    stringResource(R.string.settings_help),
                    subtitle = supportEmail,
                    onClick = { context.composeEmail(supportEmail, subject, body) },
                )
                Divider()
            }
            ListRow(Icons.Outlined.Policy, stringResource(R.string.settings_privacy), onClick = onOpenPrivacy)
            Divider()
            ListRow(
                Icons.Outlined.RestartAlt,
                stringResource(R.string.settings_reset),
                subtitle = stringResource(R.string.settings_reset_sub),
                onClick = { confirmReset = true },
            )
            Divider()
            DebugTools.SettingsSection()
            Spacer(Modifier.height(Space.xl))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Palette, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(Space.xs))
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = SwipeTheme.type.label,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surface,
            titleContentColor = c.textPrimary,
            textContentColor = c.textSecondary,
            title = { Text(stringResource(R.string.reset_title), style = SwipeTheme.type.headline) },
            text = { Text(stringResource(R.string.reset_body), style = SwipeTheme.type.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetHistory()
                }) { Text(stringResource(R.string.reset_confirm), color = c.textPrimary, style = SwipeTheme.type.button) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel), color = c.textSecondary, style = SwipeTheme.type.button)
                }
            },
        )
    }
}
