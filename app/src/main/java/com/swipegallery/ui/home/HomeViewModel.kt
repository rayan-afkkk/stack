package com.swipegallery.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.data.media.MediaIndex
import com.swipegallery.data.prefs.UserPreferences
import com.swipegallery.data.review.SessionInfo
import com.swipegallery.domain.allowance.AllowanceSnapshot
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.session.SessionPlanner
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.domain.session.SessionSpec
import com.swipegallery.ui.navigation.STOP_TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Default threshold for the Home "Large photos" count. */
const val DEFAULT_LARGE_BYTES = 5_000_000L

data class LibraryCounts(
    val allUnreviewed: Int,
    val monthUnreviewed: Int,
    val albums: Int,
    val largeUnreviewed: Int,
)

data class ResumableSession(val id: Long, val spec: SessionSpec, val remaining: Int)

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NoAccess : LibraryState
    data object Failed : LibraryState
    data class Ready(val access: PhotoAccess, val totalPhotos: Int, val counts: LibraryCounts) : LibraryState
}

data class HomeUiState(
    val allowance: AllowanceSnapshot? = null,
    val library: LibraryState = LibraryState.Loading,
    val queueCount: Int = 0,
    val resumable: ResumableSession? = null,
    val prefs: UserPreferences? = null,
    val isPremium: Boolean = false,
    val currentMonth: YearMonth = YearMonth.now(),
) {
    val showUpgradeCard: Boolean
        get() = prefs != null && prefs.firstCleanupCompleted && !prefs.upgradeCardDismissed && !isPremium
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val zone get() = container.clock.zone()

    private val library = combine(container.media.index, container.reviews.decidedKeys) { index, decided ->
        index to decided
    }.mapLatest { (index, decided) ->
        when (index) {
            MediaIndex.Loading -> LibraryState.Loading
            MediaIndex.NoAccess -> LibraryState.NoAccess
            is MediaIndex.Failed -> LibraryState.Failed
            is MediaIndex.Ready -> {
                val photos = index.photos
                val month = YearMonth.now(zone)
                LibraryState.Ready(
                    access = index.access,
                    totalPhotos = photos.size,
                    counts = LibraryCounts(
                        allUnreviewed = SessionPlanner.unreviewedCount(photos, SessionScope.AllPhotos, zone, decided),
                        monthUnreviewed = SessionPlanner.unreviewedCount(photos, SessionScope.Month(month), zone, decided),
                        albums = photos.mapNotNullTo(HashSet()) { it.bucketId }.size,
                        largeUnreviewed = SessionPlanner.unreviewedCount(photos, SessionScope.LargePhotos(DEFAULT_LARGE_BYTES), zone, decided),
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    private val resumable = combine(
        container.reviews.resumableSession,
        container.media.index,
        container.reviews.decidedKeys,
    ) { session, index, decided -> Triple(session, index, decided) }
        .mapLatest { (session, index, decided) -> resumableFrom(session, index, decided) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<HomeUiState> = combine(
        container.allowance.snapshot,
        library,
        container.reviews.queueCount,
        resumable,
        combine(container.preferences.preferences, container.premium) { p, premium -> p to (premium == true) },
    ) { allowance, lib, queue, resume, (prefs, premium) ->
        HomeUiState(
            allowance = allowance,
            library = lib,
            queueCount = queue,
            resumable = resume,
            prefs = prefs,
            isPremium = premium,
            currentMonth = YearMonth.now(zone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private suspend fun resumableFrom(session: SessionInfo?, index: MediaIndex, decided: Set<String>): ResumableSession? {
        if (session == null || index !is MediaIndex.Ready) return null
        val acted = container.reviews.actedKeys(session.id)
        val plan = SessionPlanner.plan(index.photos, session.spec, zone, decided, acted)
        return if (plan.remaining.isEmpty()) null else ResumableSession(session.id, session.spec, plan.remaining.size)
    }

    fun dismissUpgradeCard() {
        viewModelScope.launch { container.preferences.dismissUpgradeCard() }
    }
}
