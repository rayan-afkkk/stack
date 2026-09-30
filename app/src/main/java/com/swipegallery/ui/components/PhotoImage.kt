package com.swipegallery.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Precision
import com.swipegallery.R
import com.swipegallery.ui.theme.Space
import com.swipegallery.ui.theme.SwipeTheme

/**
 * Builds a size-bounded request so photos are decoded near display size, never at full
 * camera resolution. The same builder is used for prefetching so memory-cache hits line up.
 */
fun photoRequest(context: Context, uri: Uri, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(widthPx.coerceIn(64, MAX_DECODE_PX), heightPx.coerceIn(64, MAX_DECODE_PX))
        .precision(Precision.INEXACT)
        .build()

private const val MAX_DECODE_PX = 2048

/** Enqueues decodes for upcoming photos. Dispose the returned handles when the session ends. */
fun prefetchPhotos(context: Context, uris: List<Uri>, widthPx: Int, heightPx: Int): List<Disposable> {
    val loader = SingletonImageLoader.get(context)
    return uris.map { loader.enqueue(photoRequest(context, it, widthPx, heightPx)) }
}

@Composable
fun PhotoImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    widthPx: Int,
    heightPx: Int,
    contentScale: ContentScale = ContentScale.Crop,
    showErrorText: Boolean = false,
    onError: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(uri, widthPx, heightPx) { photoRequest(context, uri, widthPx, heightPx) }
    var failed by remember(uri) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Error -> {
                        if (!failed) {
                            failed = true
                            onError?.invoke()
                        }
                    }

                    is AsyncImagePainter.State.Success -> failed = false
                    else -> Unit
                }
            },
        )
        if (failed) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(SwipeTheme.colors.surfaceSecondary)
                    .padding(Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = if (showErrorText) null else stringResource(R.string.photo_load_failed),
                    tint = SwipeTheme.colors.textSecondary,
                    modifier = Modifier.size(if (showErrorText) 36.dp else 22.dp),
                )
                if (showErrorText) {
                    Spacer(Modifier.height(Space.s))
                    Text(
                        stringResource(R.string.photo_load_failed),
                        style = SwipeTheme.type.bodySmall,
                        color = SwipeTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
