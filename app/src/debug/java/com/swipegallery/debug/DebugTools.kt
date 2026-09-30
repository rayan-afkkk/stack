package com.swipegallery.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swipegallery.ui.theme.SwipeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DEBUG BUILDS ONLY. This source set is not compiled into release builds; the release variant
 * of this object (src/release) exposes a constant `false` and no UI.
 */
object DebugTools {
    private val override = MutableStateFlow(false)
    val premiumOverride: StateFlow<Boolean> = override.asStateFlow()

    @Composable
    fun SettingsSection() {
        val enabled by premiumOverride.collectAsState()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Debug: simulate Premium", style = SwipeTheme.type.body, color = SwipeTheme.colors.textPrimary)
                    Text(
                        "Debug builds only. Not persisted. Does not touch Google Play.",
                        style = SwipeTheme.type.bodySmall,
                        color = SwipeTheme.colors.textSecondary,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { override.value = it })
            }
        }
    }
}
