package com.swipegallery.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.data.media.MediaIndex
import com.swipegallery.domain.allowance.AllowanceSnapshot
import com.swipegallery.domain.media.PhotoRef
import com.swipegallery.domain.review.CommitResult
import com.swipegallery.domain.review.Decision
import com.swipegallery.domain.review.UndoResult
import com.swipegallery.domain.session.SessionPlanner
import com.swipegallery.domain.session.SessionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SessionPhase {
    LOADING,
    REVIEWING,

    /** Free allowance used up and the current photo was not already charged today. */
    LIMIT_REACHED,

    /** Every photo in this session has a decision. */
    COMPLETED,

    /** The scope has photos, but all of them were reviewed before. */
    ALL_REVIEWED,

    /** The scope has no accessible photos at all. */
    NO_PHOTOS,

    /** Photo access missing or revoked during the session. */
    PERMISSION_NEEDED,

    /** A Premium-only session opened without Premium (e.g. after a refund). */
    PREMIUM_REQUIRED,
    NOT_FOUND,
}

sealed interface SessionMessage {
    data object PhotoRemovedElsewhere : SessionMessage
    data object UndoAlreadyTrashed : SessionMessage
    data object UndoPhotoGone : SessionMessage
    data object SaveFailed : SessionMessage
}

/** Tells the deck to bring an undone photo back in from the side it left. */
data class UndoReturn(val key: String, val decision: Decision, val token: Long)

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.LOADING,
    val spec: SessionSpec? = null,
    val current: PhotoRef? = null,
    val next: PhotoRef? = null,
    val afterNext: PhotoRef? = null,
    val upcoming: List<PhotoRef> = emptyList(),
    val decidedInSession: Int = 0,
    val remainingInSession: Int = 0,
    val allowance: AllowanceSnapshot? = null,
    val canUndo: Boolean = false,
    val keptInSession: Int = 0,
    val queuedInSession: Int = 0,
    val queueCount: Int = 0,
    val undoReturn: UndoReturn? = null,
    val message: SessionMessage? = null,
    /** True while a decision is being written; further decisions are ignored until it lands. */
    val committing: Boolean = false,
    val currentUnavailable: Boolean = false,
) {
    val total: Int get() = decidedInSession + remainingInSession
    val progress: Float get() = if (total == 0) 0f else decidedInSession.toFloat() / total
}

/**
 * Owns the ordered list of photos for one durable session. Decisions are persisted by the
 * ReviewEngine the moment a swipe is released; the card animation never gates persistence.
 */
class SessionViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val sessionId: Long = handle.get<Long>("sessionId") ?: -1L
    private val zone get() = container.clock.zone()

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    /** Remaining photos in order. Only touched on the main thread. */
    private val remaining = ArrayDeque<PhotoRef>()
    private var spec: SessionSpec? = null
    private var decidable: Boolean? = null
    private var decidableJob: Job? = null
    private var inFlightKey: String? = null
    private var undoToken = 0L

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            container.allowance.snapshot.collect { snapshot ->
                _state.update { it.copy(allowance = snapshot) }
                refreshDecidable()
            }
        }
        viewModelScope.launch {
            container.reviews.queueCount.collect { count -> _state.update { it.copy(queueCount = count) } }
        }
        viewModelScope.launch {
            container.media.index.collect { index -> onIndexChanged(index) }
        }
    }

    private suspend fun load() {
        val info = container.reviews.session(sessionId)
        if (info == null) {
            _state.update { it.copy(phase = SessionPhase.NOT_FOUND) }
            return
        }
        spec = info.spec
        _state.update { it.copy(spec = info.spec) }
        if (info.spec.requiresPremium && container.premium.first { it != null } != true) {
            _state.update { it.copy(phase = SessionPhase.PREMIUM_REQUIRED) }
            return
        }
        val index = container.media.index.first { it !is MediaIndex.Loading }
        rebuild(index)
    }

    private suspend fun rebuild(index: MediaIndex) {
        val spec = spec ?: return
        if (index !is MediaIndex.Ready) {
            if (index is MediaIndex.NoAccess) _state.update { it.copy(phase = SessionPhase.PERMISSION_NEEDED) }
            return
        }
        val decided = container.reviews.decidedKeysNow()
        val acted = container.reviews.actedKeys(sessionId)
        val plan = SessionPlanner.plan(index.photos, spec, zone, decided, acted)
        remaining.clear()
        remaining.addAll(plan.remaining)
        val counts = container.reviews.counts(sessionId)
        val phase = when {
            remaining.isNotEmpty() -> SessionPhase.REVIEWING
            acted.isNotEmpty() -> SessionPhase.COMPLETED
            plan.scopeTotal == 0 -> SessionPhase.NO_PHOTOS
            else -> SessionPhase.ALL_REVIEWED
        }
        _state.update {
            it.copy(
                phase = phase,
                decidedInSession = acted.size,
                keptInSession = counts.kept,
                queuedInSession = counts.queued,
                canUndo = acted.isNotEmpty(),
            )
        }
        if (phase == SessionPhase.COMPLETED) onCompleted()
        publishDeck()
    }

    /** Reconcile with the live library: removed photos disappear, revoked access is surfaced. */
    private suspend fun onIndexChanged(index: MediaIndex) {
        val phase = _state.value.phase
        if (phase == SessionPhase.LOADING || phase == SessionPhase.NOT_FOUND || phase == SessionPhase.PREMIUM_REQUIRED) return
        when (index) {
            MediaIndex.NoAccess -> _state.update { it.copy(phase = SessionPhase.PERMISSION_NEEDED) }
            is MediaIndex.Ready -> {
                if (phase == SessionPhase.PERMISSION_NEEDED) {
                    rebuild(index)
                    return
                }
                val present = withContext(Dispatchers.Default) { index.keys }
                val currentKey = remaining.firstOrNull()?.key
                val removed = remaining.removeAll { it.key !in present && it.key != inFlightKey }
                if (removed) {
                    val currentGone = currentKey != null && currentKey !in present && currentKey != inFlightKey
                    _state.update { it.copy(message = if (currentGone) SessionMessage.PhotoRemovedElsewhere else it.message) }
                    if (remaining.isEmpty() && _state.value.phase == SessionPhase.REVIEWING) {
                        _state.update { it.copy(phase = if (it.decidedInSession > 0) SessionPhase.COMPLETED else SessionPhase.ALL_REVIEWED) }
                        if (_state.value.phase == SessionPhase.COMPLETED) onCompleted()
                    }
                    publishDeck()
                }
            }

            else -> Unit
        }
    }

    private fun publishDeck() {
        val list = remaining
        _state.update {
            it.copy(
                current = list.getOrNull(0),
                next = list.getOrNull(1),
                afterNext = list.getOrNull(2),
                upcoming = list.take(4),
                remainingInSession = list.size,
                currentUnavailable = false,
            )
        }
        refreshDecidable()
    }

    private fun refreshDecidable() {
        val current = remaining.firstOrNull()
        decidableJob?.cancel()
        if (current == null) {
            decidable = null
            return
        }
        decidableJob = viewModelScope.launch {
            val ok = container.engine.canDecide(current.key)
            if (remaining.firstOrNull()?.key != current.key) return@launch
            decidable = ok
            _state.update { s ->
                when {
                    !ok && s.phase == SessionPhase.REVIEWING -> s.copy(phase = SessionPhase.LIMIT_REACHED)
                    ok && s.phase == SessionPhase.LIMIT_REACHED -> s.copy(phase = SessionPhase.REVIEWING)
                    else -> s
                }
            }
        }
    }

    /**
     * Called by the deck when a swipe or button commits. Returns false if the decision cannot be
     * taken right now (another commit in flight, allowance exhausted, stale card), in which case
     * the deck snaps the card back. Persistence starts immediately and advances the deck when done.
     */
    fun decide(photo: PhotoRef, decision: Decision): Boolean {
        val s = _state.value
        if (s.phase != SessionPhase.REVIEWING || inFlightKey != null) return false
        if (remaining.firstOrNull()?.key != photo.key) return false
        if (decidable == false) {
            _state.update { it.copy(phase = SessionPhase.LIMIT_REACHED) }
            return false
        }
        inFlightKey = photo.key
        _state.update { it.copy(committing = true, undoReturn = null) }
        viewModelScope.launch {
            val result = runCatching { container.engine.commit(sessionId, photo.meta(), decision) }.getOrNull()
            inFlightKey = null
            when (result) {
                is CommitResult.Committed, CommitResult.AlreadyApplied -> advancePast(photo, decision)
                CommitResult.AlreadyTrashed -> {
                    remaining.removeAll { it.key == photo.key }
                    _state.update { it.copy(committing = false) }
                    publishDeck()
                }

                is CommitResult.Blocked -> {
                    decidable = false
                    _state.update { it.copy(committing = false, phase = SessionPhase.LIMIT_REACHED, current = photo) }
                }

                null -> _state.update { it.copy(committing = false, message = SessionMessage.SaveFailed) }
            }
        }
        return true
    }

    private suspend fun advancePast(photo: PhotoRef, decision: Decision) {
        remaining.removeAll { it.key == photo.key }
        _state.update {
            it.copy(
                committing = false,
                decidedInSession = it.decidedInSession + 1,
                keptInSession = it.keptInSession + if (decision == Decision.KEEP) 1 else 0,
                queuedInSession = it.queuedInSession + if (decision == Decision.REMOVE) 1 else 0,
                canUndo = true,
            )
        }
        if (remaining.isEmpty()) {
            _state.update { it.copy(phase = SessionPhase.COMPLETED) }
            onCompleted()
        }
        publishDeck()
    }

    private suspend fun onCompleted() {
        container.reviews.markCompleted(sessionId)
        container.preferences.setFirstCleanupCompleted()
    }

    /** Restores the previous decision. Never refunds a used review; recommitting today is free. */
    fun undo() {
        if (inFlightKey != null) return
        viewModelScope.launch {
            when (val result = container.engine.undo(sessionId)) {
                is UndoResult.Undone -> {
                    val index = container.media.index.value as? MediaIndex.Ready
                    val photo = index?.byKey(result.photo.key)
                    _state.update {
                        it.copy(
                            decidedInSession = (it.decidedInSession - 1).coerceAtLeast(0),
                            keptInSession = (it.keptInSession - if (result.decision == Decision.KEEP) 1 else 0).coerceAtLeast(0),
                            queuedInSession = (it.queuedInSession - if (result.decision == Decision.REMOVE) 1 else 0).coerceAtLeast(0),
                            canUndo = it.decidedInSession - 1 > 0,
                        )
                    }
                    if (photo == null) {
                        _state.update { it.copy(message = SessionMessage.UndoPhotoGone) }
                        return@launch
                    }
                    remaining.removeAll { it.key == photo.key }
                    remaining.addFirst(photo)
                    undoToken++
                    _state.update {
                        it.copy(
                            phase = SessionPhase.REVIEWING,
                            undoReturn = UndoReturn(photo.key, result.decision, undoToken),
                        )
                    }
                    publishDeck()
                }

                is UndoResult.AlreadyTrashed -> _state.update {
                    it.copy(
                        message = SessionMessage.UndoAlreadyTrashed,
                        decidedInSession = (it.decidedInSession - 1).coerceAtLeast(0),
                        canUndo = it.decidedInSession - 1 > 0,
                    )
                }

                UndoResult.NothingToUndo -> _state.update { it.copy(canUndo = false) }
            }
        }
    }

    /** The image failed to decode; if the file is gone, skip it without charging a review. */
    fun onImageFailed(photo: PhotoRef) {
        viewModelScope.launch {
            if (!container.media.isAvailable(photo) && remaining.firstOrNull()?.key == photo.key) {
                if (_state.value.current?.key == photo.key) {
                    _state.update { it.copy(currentUnavailable = true) }
                }
            }
        }
    }

    /** Skip a photo that no longer exists. Not a decision, never charged. */
    fun skipUnavailable() {
        if (inFlightKey != null) return
        remaining.removeFirstOrNull() ?: return
        _state.update { it.copy(message = SessionMessage.PhotoRemovedElsewhere) }
        if (remaining.isEmpty()) {
            _state.update { it.copy(phase = if (it.decidedInSession > 0) SessionPhase.COMPLETED else SessionPhase.ALL_REVIEWED) }
        }
        publishDeck()
        container.media.refresh()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** From the "everything reviewed" state: open an explicit revisit session. */
    fun startRevisit(onOpened: (Long) -> Unit) {
        val spec = spec ?: return
        viewModelScope.launch { onOpened(container.reviews.startOrResume(spec.copy(includeReviewed = true))) }
    }
}
