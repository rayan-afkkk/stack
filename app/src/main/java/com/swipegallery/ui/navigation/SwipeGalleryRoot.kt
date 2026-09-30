package com.swipegallery.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.swipegallery.AppContainer
import com.swipegallery.ui.albums.AlbumsScreen
import com.swipegallery.ui.home.HomeScreen
import com.swipegallery.ui.onboarding.OnboardingScreen
import com.swipegallery.ui.paywall.PaywallScreen
import com.swipegallery.ui.privacy.PrivacyScreen
import com.swipegallery.ui.review.ReviewScreen
import com.swipegallery.ui.session.SessionScreen
import com.swipegallery.ui.settings.SettingsScreen
import com.swipegallery.ui.setup.SessionSetupScreen
import com.swipegallery.ui.theme.SwipeGalleryTheme
import com.swipegallery.ui.theme.SwipeTheme
import com.swipegallery.ui.theme.isDarkTheme
import com.swipegallery.util.rememberReducedMotion

@Composable
fun SwipeGalleryRoot(container: AppContainer, onThemeResolved: (dark: Boolean) -> Unit) {
    val prefs by container.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    val loaded = prefs
    if (loaded == null) {
        // DataStore reads in a few milliseconds; keep the dark launch canvas meanwhile.
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val dark = isDarkTheme(loaded.themeMode)
    LaunchedEffect(dark) { onThemeResolved(dark) }
    SwipeGalleryTheme(dark = dark) {
        AppNavigation(container, startAtOnboarding = !loaded.onboardingComplete)
    }
}

@Composable
private fun AppNavigation(container: AppContainer, startAtOnboarding: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val pending by container.reviews.queueCount.collectAsStateWithLifecycle(initialValue = 0)
    val reducedMotion = rememberReducedMotion()
    val motion = remember(reducedMotion) { ScreenMotion(reducedMotion) }

    Column(
        Modifier
            .fillMaxSize()
            .background(SwipeTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        NavHost(
            navController = nav,
            startDestination = if (startAtOnboarding) Routes.ONBOARDING else Routes.HOME,
            modifier = Modifier.weight(1f),
            enterTransition = { motion.enter(initialState.destination.route, targetState.destination.route) },
            exitTransition = { motion.exit(initialState.destination.route, targetState.destination.route) },
            popEnterTransition = { motion.enter(initialState.destination.route, targetState.destination.route) },
            popExitTransition = { motion.exit(initialState.destination.route, targetState.destination.route) },
        ) {
            composable(
                Routes.ONBOARDING,
                arguments = listOf(navArgument("replay") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val replay = entry.arguments?.getBoolean("replay") ?: false
                OnboardingScreen(
                    container = container,
                    onFinished = {
                        if (replay) {
                            nav.popBackStack()
                        } else {
                            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenSetup = { nav.navigate(Routes.setup(it)) },
                    onResume = { nav.navigate(Routes.session(it)) },
                    onOpenAlbums = { nav.navigateTab(Routes.ALBUMS) },
                    onOpenReview = { nav.navigateTab(Routes.REVIEW) },
                    onOpenPaywall = { nav.navigate(Routes.paywall(it)) },
                )
            }
            composable(Routes.ALBUMS) {
                AlbumsScreen(container = container, onOpenSetup = { nav.navigate(Routes.setup(it)) })
            }
            composable(Routes.REVIEW) {
                ReviewScreen(container = container, onStartCleanup = { nav.navigateTab(Routes.HOME) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onReplayOnboarding = { nav.navigate(Routes.onboarding(replay = true)) },
                    onOpenPaywall = { nav.navigate(Routes.paywall(PaywallSource.SETTINGS)) },
                    onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                )
            }
            composable(
                Routes.SETUP,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType; defaultValue = "all" },
                    navArgument("arg") { type = NavType.StringType; defaultValue = "" },
                    navArgument("label") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                SessionSetupScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onStart = { id ->
                        nav.navigate(Routes.session(id)) { popUpTo(Routes.SETUP) { inclusive = true } }
                    },
                    onOpenPaywall = { nav.navigate(Routes.paywall(PaywallSource.PRO_FEATURE)) },
                )
            }
            composable(
                Routes.SESSION,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) {
                SessionScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenQueue = { nav.navigateTab(Routes.REVIEW) },
                    onOpenPaywall = { nav.navigate(Routes.paywall(it)) },
                    onHome = { nav.navigateTab(Routes.HOME) },
                    onOpenSession = { id -> nav.navigate(Routes.session(id)) { popUpTo(Routes.SESSION) { inclusive = true } } },
                )
            }
            composable(
                Routes.PAYWALL,
                arguments = listOf(navArgument("source") { type = NavType.StringType; defaultValue = PaywallSource.SETTINGS.name }),
            ) { entry ->
                val source = entry.arguments?.getString("source")
                    ?.let { runCatching { PaywallSource.valueOf(it) }.getOrNull() } ?: PaywallSource.SETTINGS
                PaywallScreen(container = container, source = source, onClose = { nav.popBackStack() })
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(onBack = { nav.popBackStack() })
            }
        }
        if (route in Routes.tabs) {
            BottomBar(currentRoute = route, pendingCount = pending, onSelect = { nav.navigateTab(it) })
        }
    }
}

/** Tab switch that keeps each tab's state and never stacks duplicate tabs. */
fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
