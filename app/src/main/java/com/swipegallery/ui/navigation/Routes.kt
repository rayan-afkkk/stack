package com.swipegallery.ui.navigation

import android.net.Uri
import com.swipegallery.domain.session.ScopeCodec
import com.swipegallery.domain.session.SessionScope

object Routes {
    const val ONBOARDING = "onboarding?replay={replay}"
    const val HOME = "home"
    const val ALBUMS = "albums"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val SESSION = "session/{sessionId}"
    const val SETUP = "setup?type={type}&arg={arg}&label={label}"
    const val PAYWALL = "paywall?source={source}"
    const val PRIVACY = "privacy"

    fun onboarding(replay: Boolean) = "onboarding?replay=$replay"
    fun session(id: Long) = "session/$id"
    fun paywall(source: PaywallSource) = "paywall?source=${source.name}"

    fun setup(scope: SessionScope): String {
        val arg = ScopeCodec.arg(scope).orEmpty()
        val label = (scope as? SessionScope.Album)?.name.orEmpty()
        return "setup?type=${ScopeCodec.type(scope)}&arg=${Uri.encode(arg)}&label=${Uri.encode(label)}"
    }

    val tabs = listOf(HOME, ALBUMS, REVIEW, SETTINGS)
}

/** Where the paywall was opened from; changes only the introductory line. */
enum class PaywallSource { LIMIT, PRO_FEATURE, UPGRADE_CARD, SETTINGS }
