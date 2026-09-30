package com.swipegallery.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.swipegallery.domain.media.PhotoAccess

/** Minimum permissions for reading photos on each supported Android version. */
object PhotoPermissions {

    /** Requested together; on Android 14+ the system offers "Allow all", "Select photos" or "Don't allow". */
    fun requestSet(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** The permission whose rationale state tells us whether the system will still show a dialog. */
    fun primary(): String = requestSet().first()

    fun currentAccess(context: Context): PhotoAccess {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.SELECTED

                else -> PhotoAccess.NONE
            }

            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
            else -> PhotoAccess.NONE
        }
    }
}
