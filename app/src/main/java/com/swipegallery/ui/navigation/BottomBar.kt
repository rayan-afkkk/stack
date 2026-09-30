package com.swipegallery.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swipegallery.R
import com.swipegallery.ui.components.Divider
import com.swipegallery.ui.theme.SwipeTheme

private data class Tab(val route: String, val label: Int, val icon: ImageVector, val activeIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.ALBUMS, R.string.nav_albums, Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Tab(Routes.REVIEW, R.string.nav_review, Icons.Outlined.Delete, Icons.Filled.Delete),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun BottomBar(currentRoute: String?, pendingCount: Int, onSelect: (String) -> Unit) {
    val c = SwipeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background),
    ) {
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .heightIn(min = 60.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val tint by animateColorAsState(
                    if (selected) c.textPrimary else c.textSecondary,
                    animationSpec = tween(200),
                    label = "tabTint",
                )
                val label = stringResource(tab.label)
                val badge = if (tab.route == Routes.REVIEW && pendingCount > 0) pendingCount else 0
                val badgeDescription = pluralStringResource(R.plurals.nav_review_badge, badge, badge)
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 60.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(
                            if (selected) tab.activeIcon else tab.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        if (badge > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-6).dp)
                                    .heightIn(min = 16.dp)
                                    .widthIn(min = 16.dp)
                                    .clip(CircleShape)
                                    .background(c.accent)
                                    .padding(horizontal = 4.dp)
                                    .clearAndSetSemantics { contentDescription = badgeDescription },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (badge > 99) "99+" else badge.toString(),
                                    style = SwipeTheme.type.nav.copy(fontSize = SwipeTheme.type.nav.fontSize * 0.85f),
                                    color = c.onAccent,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = SwipeTheme.type.nav,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
