package com.swipegallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swipegallery.data.prefs.ThemeMode
import com.swipegallery.ui.onboarding.OnboardingScreen
import com.swipegallery.ui.session.DecisionButtons
import com.swipegallery.ui.session.LimitReachedPanel
import com.swipegallery.ui.theme.SwipeGalleryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingAndLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun replayingOnboardingKeepsUsagePreferencesAndHistory() {
        val container = ApplicationProvider.getApplicationContext<SwipeGalleryApp>().container
        val usedBefore = runBlocking {
            container.preferences.setThemeMode(ThemeMode.LIGHT)
            container.preferences.setHapticsEnabled(false)
            container.engine.usedToday()
        }
        var finished = false
        rule.setContent {
            SwipeGalleryTheme(dark = true) { OnboardingScreen(container, onFinished = { finished = true }) }
        }
        rule.onNodeWithText(context.getString(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(context.getString(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(context.getString(R.string.onboarding_get_started)).performClick()
        rule.waitUntil(5_000) { finished }

        runBlocking {
            val prefs = container.preferences.preferences.first()
            assertTrue(prefs.onboardingComplete)
            assertEquals(ThemeMode.LIGHT, prefs.themeMode)
            assertFalse(prefs.hapticsEnabled)
            assertEquals(usedBefore, container.engine.usedToday())
            container.preferences.setThemeMode(ThemeMode.DARK)
            container.preferences.setHapticsEnabled(true)
        }
    }

    @Test
    fun limitStateKeepsQueueReachable_onSmallScreenWithLargeFont() {
        var openedQueue = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                SwipeGalleryTheme(dark = true) {
                    Box(Modifier.size(320.dp, 480.dp).verticalScroll(rememberScrollState())) {
                        LimitReachedPanel(
                            queueCount = 3,
                            resetsAtMillis = 0L,
                            onOpenQueue = { openedQueue = true },
                            onUnlock = {},
                            onHome = {},
                        )
                    }
                }
            }
        }
        val label = context.resources.getQuantityString(R.plurals.limit_review_queue, 3, 3)
        rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        assertTrue(openedQueue)
        rule.onNodeWithText(context.getString(R.string.action_back_home)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun decisionButtonsStayVisibleWithLargeFontOnCompactWidth() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                SwipeGalleryTheme(dark = true) {
                    Box(Modifier.size(320.dp, 200.dp)) {
                        DecisionButtons(canUndo = true, enabled = true, onUndo = {}, onRemove = {}, onKeep = {})
                    }
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.session_keep)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.session_remove)).assertIsDisplayed()
    }
}
