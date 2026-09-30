package com.swipegallery.data.review

import com.swipegallery.data.db.ReviewDao
import com.swipegallery.domain.allowance.AllowanceSnapshot
import com.swipegallery.domain.time.AppClock
import com.swipegallery.domain.time.dayFlow
import com.swipegallery.domain.time.ledgerKey
import com.swipegallery.domain.time.nextResetMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Today's review allowance, re-evaluated when the local day changes (midnight, zone change). */
@OptIn(ExperimentalCoroutinesApi::class)
class AllowanceRepository(
    dao: ReviewDao,
    clock: AppClock,
    premium: Flow<Boolean?>,
    scope: CoroutineScope,
) {
    val snapshot: StateFlow<AllowanceSnapshot?> = clock.dayFlow()
        .flatMapLatest { day -> dao.observeChargedCount(day.ledgerKey()).map { used -> day to used } }
        .combine(premium.filterNotNull()) { (day, used), isPremium ->
            AllowanceSnapshot(day = day, used = used, isPremium = isPremium, resetsAtMillis = clock.nextResetMillis())
        }
        .stateIn(scope, SharingStarted.Eagerly, null)
}
