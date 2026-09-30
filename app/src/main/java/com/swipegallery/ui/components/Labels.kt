package com.swipegallery.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.swipegallery.R
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.util.Format

@Composable
fun scopeTitle(scope: SessionScope): String = when (scope) {
    SessionScope.AllPhotos -> stringResource(R.string.scope_all_photos)
    is SessionScope.Month -> Format.month(scope.yearMonth)
    is SessionScope.Album -> scope.name.ifBlank { stringResource(R.string.scope_album_unnamed) }
    is SessionScope.LargePhotos -> stringResource(
        R.string.scope_large_photos_min,
        Format.bytes(LocalContext.current, scope.minBytes),
    )
}
