package com.swipegallery.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Screen transitions.
 * - Between bottom-nav tabs: a short cross-fade with a gentle slide towards the tab you picked
 *   (right for tabs further along the bar, left for earlier ones).
 * - Into/out of full screens (session, setup, paywall…): a cross-fade with a slight scale.
 * - With system animations off: instant.
 */
class ScreenMotion(private val reducedMotion: Boolean) {
    private val durationMs = 220
    private val fadeMs = 180

    private fun tabIndex(route: String?): Int = route?.let { Routes.tabs.indexOf(it) } ?: -1

    private fun direction(from: String?, to: String?): Int {
        val a = tabIndex(from)
        val b = tabIndex(to)
        return if (a >= 0 && b >= 0 && a != b) (if (b > a) 1 else -1) else 0
    }

    fun enter(from: String?, to: String?): EnterTransition {
        if (reducedMotion) return EnterTransition.None
        val dir = direction(from, to)
        val fade = fadeIn(tween(fadeMs, delayMillis = 40, easing = FastOutSlowInEasing))
        return if (dir != 0) {
            fade + slideInHorizontally(tween(durationMs, easing = FastOutSlowInEasing)) { width -> dir * width / 10 }
        } else {
            fade + scaleIn(tween(durationMs, easing = FastOutSlowInEasing), initialScale = 0.98f)
        }
    }

    fun exit(from: String?, to: String?): ExitTransition {
        if (reducedMotion) return ExitTransition.None
        val dir = direction(from, to)
        val fade = fadeOut(tween(fadeMs - 60, easing = FastOutSlowInEasing))
        return if (dir != 0) {
            fade + slideOutHorizontally(tween(durationMs, easing = FastOutSlowInEasing)) { width -> -dir * width / 10 }
        } else {
            fade + scaleOut(tween(durationMs, easing = FastOutSlowInEasing), targetScale = 0.99f)
        }
    }
}
