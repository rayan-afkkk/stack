package com.swipegallery

import android.app.Application
import com.swipegallery.data.billing.BillingRepository
import com.swipegallery.data.billing.EntitlementCache
import com.swipegallery.data.db.AppDatabase
import com.swipegallery.data.db.RoomReviewStore
import com.swipegallery.data.media.MediaRepository
import com.swipegallery.data.prefs.PreferencesRepository
import com.swipegallery.data.review.AllowanceRepository
import com.swipegallery.data.review.ReviewRepository
import com.swipegallery.debug.DebugTools
import com.swipegallery.domain.review.ReviewEngine
import com.swipegallery.domain.time.AppClock
import com.swipegallery.domain.time.SystemAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Manual dependency graph. Everything here is process-scoped. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: AppClock = SystemAppClock

    val database = AppDatabase.create(app)
    val preferences = PreferencesRepository(app)
    private val entitlementCache = EntitlementCache(app)

    val billing = BillingRepository(
        context = app,
        scope = appScope,
        cache = entitlementCache,
        productId = BuildConfig.PREMIUM_PRODUCT_ID,
        clock = clock,
    )

    /** Null until the cached entitlement is loaded, so nothing is decided on a guess. */
    val premium: StateFlow<Boolean?> = combine(billing.entitlement, DebugTools.premiumOverride) { state, debug ->
        state?.let { it.isPremium || debug }
    }.stateIn(appScope, SharingStarted.Eagerly, null)

    val media = MediaRepository(app, appScope)

    val engine = ReviewEngine(
        store = RoomReviewStore(database),
        clock = clock,
        isPremium = { premium.filterNotNull().first() },
    )

    val reviews = ReviewRepository(database, engine, clock)
    val allowance = AllowanceRepository(database.reviewDao(), clock, premium, appScope)

    fun start() {
        media.start()
        billing.start()
        appScope.launch { reviews.pruneLedger() }
    }

    fun onForeground() {
        media.onForeground()
        appScope.launch {
            billing.refreshPurchases()
            billing.loadProduct()
        }
    }
}
