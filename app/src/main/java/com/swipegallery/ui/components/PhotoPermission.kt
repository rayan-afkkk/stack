package com.swipegallery.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.swipegallery.AppContainer
import com.swipegallery.data.media.PhotoPermissions
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.util.findActivity
import com.swipegallery.util.openAppSettings
import kotlinx.coroutines.launch

/**
 * Photo access actions. The system dialog is only launched from an explicit tap; after a
 * permanent denial we send the user to Settings instead of asking again.
 */
class PhotoAccessActions(
    val requestOrManage: () -> Unit,
    val openSettings: () -> Unit,
    /** True when the system will no longer show the dialog (user denied it). */
    val mustUseSettings: Boolean,
)

@Composable
fun rememberPhotoAccessActions(
    container: AppContainer,
    access: PhotoAccess,
    permissionRequestedBefore: Boolean,
): PhotoAccessActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { container.preferences.setPhotoPermissionRequested() }
        container.media.onForeground()
        container.media.refresh()
    }
    val currentAccess = rememberUpdatedState(access)
    val activity = context.findActivity()
    val mustUseSettings = access == PhotoAccess.NONE && permissionRequestedBefore && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, PhotoPermissions.primary())
    return remember(mustUseSettings, launcher) {
        PhotoAccessActions(
            requestOrManage = {
                if (mustUseSettings && currentAccess.value == PhotoAccess.NONE) {
                    context.openAppSettings()
                } else {
                    // With "Selected photos" on Android 14+, re-requesting shows the system's
                    // selection sheet so the user can change or expand what is shared.
                    launcher.launch(PhotoPermissions.requestSet())
                }
            },
            openSettings = { context.openAppSettings() },
            mustUseSettings = mustUseSettings,
        )
    }
}
