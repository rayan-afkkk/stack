package com.swipegallery.ui.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.data.media.MediaIndex
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.session.MonthBucket
import com.swipegallery.domain.session.ScopeCodec
import com.swipegallery.domain.session.SessionOrder
import com.swipegallery.domain.session.SessionPlanner
import com.swipegallery.domain.session.SessionScope
import com.swipegallery.domain.session.SessionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

val LARGE_SIZE_OPTIONS = listOf(2_000_000L, 5_000_000L, 10_000_000L)

data class SetupChoice(
    val scope: SessionScope,
    val order: SessionOrder,
    val includeReviewed: Boolean,
) {
    val spec: SessionSpec get() = SessionSpec(scope, order, includeReviewed)
}

sealed interface SetupUiState {
    data object Loading : SetupUiState
    data object NoAccess : SetupUiState
    data object Invalid : SetupUiState

    data class Ready(
        val choice: SetupChoice,
        val access: PhotoAccess,
        val toReview: Int,
        val inScope: Int,
        val alreadyReviewed: Int,
        val unknownSize: Int,
        val months: List<MonthBucket>,
        val resumableSessionId: Long?,
        val isPremium: Boolean,
    ) : SetupUiState {
        val locked: Boolean get() = choice.spec.requiresPremium && !isPremium
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSetupViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val zone get() = container.clock.zone()

    private val initialScope: SessionScope? = ScopeCodec.decode(
        type = handle.get<String>("type") ?: "all",
        arg = handle.get<String>("arg")?.takeIf { it.isNotEmpty() },
        label = handle.get<String>("label"),
    )

    private val choice = MutableStateFlow(
        initialScope?.let {
            SetupChoice(
                scope = it,
                order = if (it is SessionScope.LargePhotos) SessionOrder.LARGEST else SessionOrder.NEWEST,
                includeReviewed = false,
            )
        },
    )

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting

    val state: StateFlow<SetupUiState> = combine(
        choice,
        container.media.index,
        container.reviews.decidedKeys,
        container.premium,
    ) { choice, index, decided, premium -> Inputs(choice, index, decided, premium == true) }
        .mapLatest { inputs -> build(inputs) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState.Loading)

    private data class Inputs(val choice: SetupChoice?, val index: MediaIndex, val decided: Set<String>, val premium: Boolean)

    private suspend fun build(inputs: Inputs): SetupUiState {
        val choice = inputs.choice ?: return SetupUiState.Invalid
        val index = inputs.index
        return when (index) {
            MediaIndex.Loading -> SetupUiState.Loading
            MediaIndex.NoAccess -> SetupUiState.NoAccess
            is MediaIndex.Failed -> SetupUiState.Invalid
            is MediaIndex.Ready -> {
                val plan = SessionPlanner.plan(index.photos, choice.spec, zone, inputs.decided)
                val months = if (choice.scope is SessionScope.Month) {
                    SessionPlanner.months(index.photos, zone, inputs.decided)
                } else {
                    emptyList()
                }
                SetupUiState.Ready(
                    choice = choice,
                    access = index.access,
                    toReview = plan.remaining.size,
                    inScope = plan.scopeTotal,
                    alreadyReviewed = plan.alreadyReviewedCount,
                    unknownSize = plan.unknownSizeCount,
                    months = months,
                    resumableSessionId = container.reviews.findOpenSession(choice.spec)?.id,
                    isPremium = inputs.premium,
                )
            }
        }
    }

    fun setOrder(order: SessionOrder) = choice.update { it?.copy(order = order) }

    fun setIncludeReviewed(include: Boolean) = choice.update { it?.copy(includeReviewed = include) }

    fun setMonth(month: YearMonth) = choice.update { it?.copy(scope = SessionScope.Month(month)) }

    fun setMinSize(bytes: Long) = choice.update { it?.copy(scope = SessionScope.LargePhotos(bytes)) }

    /** Starts or resumes the matching session. Premium-only specs are refused here too. */
    fun start(onStarted: (Long) -> Unit) {
        val ready = state.value as? SetupUiState.Ready ?: return
        if (ready.locked || _starting.value) return
        _starting.value = true
        viewModelScope.launch {
            try {
                onStarted(container.reviews.startOrResume(ready.choice.spec))
            } finally {
                _starting.value = false
            }
        }
    }
}
