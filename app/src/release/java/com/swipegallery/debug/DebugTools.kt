package com.swipegallery.debug

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Release builds: no debug entitlement, no debug UI. */
object DebugTools {
    val premiumOverride: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    @Composable
    fun SettingsSection() = Unit
}
