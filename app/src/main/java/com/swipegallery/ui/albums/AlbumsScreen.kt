package com.swipegallery.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.R
import com.swipegallery.data.media.MediaIndex
import com.swipegallery.data.media.contentUri
import com.swipegallery.data.prefs.UserPreferences
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.session.AlbumSummary
import com.swipegallery.domain.session.SessionPlanner
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.ui.components.CenteredLoading
import com.swipegallery.ui.components.EmptyState
import com.swipegallery.ui.components.Notice
import com.swipegallery.ui.components.PhotoImage
import com.swipegallery.ui.components.Screen
import com.swipegallery.ui.components.ScreenHeading
import com.swipegallery.ui.components.readableWidth
import com.swipegallery.ui.components.rememberPhotoAccessActions
import com.swipegallery.ui.components.topSafeArea
import com.swipegallery.ui.navigation.containerViewModel
import com.swipegallery.ui.theme.Radii
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.util.Format
import com.swipegallery.ui.navigation.STOP_TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object NoAccess : AlbumsUiState
    data object Failed : AlbumsUiState
    data class Ready(val access: PhotoAccess, val albums: List<AlbumSummary>) : AlbumsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModel(container: AppContainer) : ViewModel() {
    val state: StateFlow<AlbumsUiState> = combine(container.media.index, container.reviews.decidedKeys) { i, d -> i to d }
        .mapLatest { (index, decided) ->
            when (index) {
                MediaIndex.Loading -> AlbumsUiState.Loading
                MediaIndex.NoAccess -> AlbumsUiState.NoAccess
                is MediaIndex.Failed -> AlbumsUiState.Failed
                is MediaIndex.Ready -> AlbumsUiState.Ready(index.access, SessionPlanner.albums(index.photos, decided))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlbumsUiState.Loading)

    val prefs: StateFlow<UserPreferences?> = container.preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
}

@Composable
fun AlbumsScreen(container: AppContainer, onOpenSetup: (SessionScope) -> Unit) {
    val vm = containerViewModel { c, _ -> AlbumsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val access = when (val s = state) {
        is AlbumsUiState.Ready -> s.access
        else -> PhotoAccess.NONE
    }
    val actions = rememberPhotoAccessActions(container, access, prefs?.photoPermissionRequested == true)

    Screen {
        androidx.compose.foundation.layout.Box(Modifier.readableWidth(900.dp)) {
            val tilePx = with(LocalDensity.current) { 220.dp.roundToPx() }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
                horizontalArrangement = Arrangement.spacedBy(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.topSafeArea().padding(top = Space.xl, bottom = Space.s)) {
                        ScreenHeading(stringResource(R.string.albums_title))
                    }
                }
                when (val s = state) {
                    AlbumsUiState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { CenteredLoading() }
                    AlbumsUiState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Outlined.ErrorOutline,
                            title = stringResource(R.string.library_failed_title),
                            body = stringResource(R.string.library_failed_body),
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = { container.media.refresh() },
                        )
                    }

                    AlbumsUiState.NoAccess -> item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Outlined.PhotoLibrary,
                            title = stringResource(R.string.permission_title),
                            body = stringResource(R.string.permission_body),
                            actionLabel = stringResource(
                                if (actions.mustUseSettings) R.string.permission_open_settings else R.string.permission_allow,
                            ),
                            onAction = actions.requestOrManage,
                        )
                    }

                    is AlbumsUiState.Ready -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.albums_subtitle),
                                style = SwipeTheme.type.bodySmall,
                                color = SwipeTheme.colors.textSecondary,
                            )
                        }
                        if (s.access == PhotoAccess.SELECTED) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Notice(
                                    icon = Icons.Outlined.Lock,
                                    text = stringResource(R.string.access_selected_albums_notice),
                                    actionLabel = stringResource(R.string.access_manage),
                                    onAction = actions.requestOrManage,
                                )
                            }
                        }
                        if (s.albums.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    icon = Icons.Outlined.PhotoLibrary,
                                    title = stringResource(R.string.albums_empty_title),
                                    body = stringResource(R.string.albums_empty_body),
                                )
                            }
                        } else {
                            items(s.albums, key = { it.bucketId }, contentType = { "album" }) { album ->
                                AlbumTile(album, tilePx) {
                                    onOpenSetup(SessionScope.Album(album.bucketId, album.name))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: AlbumSummary, tilePx: Int, onClick: () -> Unit) {
    val c = SwipeTheme.colors
    val name = album.name.ifBlank { stringResource(R.string.scope_album_unnamed) }
    val shape = RoundedCornerShape(Radii.tile)
    Column(
        Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        PhotoImage(
            uri = album.cover.contentUri(),
            contentDescription = null,
            widthPx = tilePx,
            heightPx = tilePx,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.border, shape),
        )
        Spacer(Modifier.height(Space.s))
        Text(name, style = SwipeTheme.type.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            pluralStringResource(R.plurals.album_counts, album.total, Format.count(album.total), Format.count(album.unreviewed)),
            style = SwipeTheme.type.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Space.xs))
    }
}
