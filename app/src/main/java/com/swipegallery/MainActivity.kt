package com.swipegallery

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swipegallery.ui.navigation.SwipeGalleryRoot

class MainActivity : ComponentActivity() {
    private val container get() = (application as SwipeGalleryApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = true)
        setContent {
            SwipeGalleryRoot(container = container, onThemeResolved = ::applySystemBars)
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions or the library may have changed while we were away (e.g. in Settings).
        container.onForeground()
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
