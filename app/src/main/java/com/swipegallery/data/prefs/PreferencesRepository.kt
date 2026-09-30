package com.swipegallery.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    /** Dark is the default on first launch. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    val onboardingComplete: Boolean = false,
    val hapticsEnabled: Boolean = true,
    /** Set once the system permission dialog has been shown after an explicit tap. */
    val photoPermissionRequested: Boolean = false,
    val firstCleanupCompleted: Boolean = false,
    val upgradeCardDismissed: Boolean = false,
)

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")

class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.preferencesStore

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val haptics = booleanPreferencesKey("haptics_enabled")
        val permissionRequested = booleanPreferencesKey("photo_permission_requested")
        val firstCleanup = booleanPreferencesKey("first_cleanup_completed")
        val upgradeDismissed = booleanPreferencesKey("upgrade_card_dismissed")
    }

    val preferences: Flow<UserPreferences> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            UserPreferences(
                themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
                onboardingComplete = p[Keys.onboarding] ?: false,
                hapticsEnabled = p[Keys.haptics] ?: true,
                photoPermissionRequested = p[Keys.permissionRequested] ?: false,
                firstCleanupCompleted = p[Keys.firstCleanup] ?: false,
                upgradeCardDismissed = p[Keys.upgradeDismissed] ?: false,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setOnboardingComplete() = store.edit { it[Keys.onboarding] = true }
    suspend fun setHapticsEnabled(enabled: Boolean) = store.edit { it[Keys.haptics] = enabled }
    suspend fun setPhotoPermissionRequested() = store.edit { it[Keys.permissionRequested] = true }
    suspend fun setFirstCleanupCompleted() = store.edit { it[Keys.firstCleanup] = true }
    suspend fun dismissUpgradeCard() = store.edit { it[Keys.upgradeDismissed] = true }
}
