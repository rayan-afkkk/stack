package com.swipegallery.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swipegallery.AppContainer
import com.swipegallery.SwipeGalleryApp

/**
 * Library-wide screens (Home, Albums, Setup) stop observing ~1s after leaving the screen, so their
 * gallery-wide counting never competes with swiping on another screen. Short enough to survive a
 * rotation without recomputing.
 */
const val STOP_TIMEOUT_MS = 1_000L

/** Creates a screen ViewModel from the app container, scoped to the current back-stack entry. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (container: AppContainer, handle: SavedStateHandle) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[APPLICATION_KEY] as SwipeGalleryApp
            create(app.container, createSavedStateHandle())
        }
    },
)
