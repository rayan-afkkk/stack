package com.swipegallery.ui.review

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipegallery.AppContainer
import com.swipegallery.domain.media.PhotoAccess
import com.swipegallery.domain.review.QueueEstimate
import com.swipegallery.domain.review.QueueItem
import com.swipegallery.domain.trash.BatchOutcome
import com.swipegallery.domain.trash.ProbeState
import com.swipegallery.domain.trash.TrashPlanner
import com.swipegallery.domain.trash.TrashRun
import com.swipegallery.domain.trash.TrashSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TrashStatus { IDLE, PREPARING, AWAITING_SYSTEM, CHECKING }

data class QueueTile(val item: QueueItem, val selected: Boolean, val accessible: Boolean)

/** Background reconciliation results (photos changed or removed outside the app). */
data class QueueNotice(val missing: Int, val changed: Int)

data class ReviewUiState(
    val loaded: Boolean = false,
    val access: PhotoAccess = PhotoAccess.NONE,
    val tiles: List<QueueTile> = emptyList(),
    val status: TrashStatus = TrashStatus.IDLE,
    val result: TrashSummary? = null,
    val prepareFailed: Boolean = false,
    val notice: QueueNotice? = null,
) {
    val selectedItems: List<QueueItem> get() = tiles.filter { it.selected }.map { it.item }
    val selectedEstimate: QueueEstimate get() = QueueEstimate.of(selectedItems)
    val totalEstimate: QueueEstimate get() = QueueEstimate.of(tiles.map { it.item })
    val busy: Boolean get() = status != TrashStatus.IDLE
}

private data class Extras(
    val deselected: Set<String> = emptySet(),
    val inaccessible: Set<String> = emptySet(),
    val status: TrashStatus = TrashStatus.IDLE,
    val result: TrashSummary? = null,
    val prepareFailed: Boolean = false,
    val notice: QueueNotice? = null,
)

/**
 * The review queue is a list of *suggestions*. Only Android's own trash confirmation
 * (MediaStore.createTrashRequest) moves anything, and every outcome is re-read from MediaStore
 * before the queue changes.
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {
    private val extras = MutableStateFlow(Extras())
    private var run: TrashRun? = null
    private var pendingBatch: List<QueueItem>? = null

    private val _launches = Channel<IntentSenderRequest>(Channel.BUFFERED)
    val launches: Flow<IntentSenderRequest> = _launches.receiveAsFlow()

    val state: StateFlow<ReviewUiState> = combine(
        container.reviews.queue,
        container.media.access,
        extras,
    ) { queue, access, x ->
        ReviewUiState(
            loaded = true,
            access = access,
            tiles = queue.map { item ->
                val accessible = access != PhotoAccess.NONE && item.key !in x.inaccessible
                QueueTile(item, selected = accessible && item.key !in x.deselected, accessible = accessible)
            },
            status = x.status,
            result = x.result,
            prepareFailed = x.prepareFailed,
            notice = x.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    /** Re-reads every queued photo: drops ones deleted or edited elsewhere, flags inaccessible ones. */
    fun reconcile() {
        if (extras.value.status != TrashStatus.IDLE) return
        viewModelScope.launch {
            val access = container.media.access.value
            if (access == PhotoAccess.NONE) return@launch
            val queue = container.reviews.queueNow()
            if (queue.isEmpty()) return@launch
            val probe = container.media.probe(queue)
            val full = access == PhotoAccess.FULL
            val trashed = queue.filter { probe[it.key] == ProbeState.TRASHED }.map { it.key }
            val missing = if (full) queue.filter { probe[it.key] == ProbeState.ABSENT }.map { it.key } else emptyList()
            val changed = queue.filter { probe[it.key] == ProbeState.CHANGED }.map { it.key }
            val inaccessible = if (full) emptySet() else queue.filter { probe[it.key] == ProbeState.ABSENT }.mapTo(HashSet()) { it.key }
            val cleanup = container.engine.applyQueueCleanup(trashed, missing, changed)
            extras.update {
                it.copy(
                    inaccessible = inaccessible,
                    notice = if (cleanup.missing + cleanup.changed > 0) QueueNotice(cleanup.missing, cleanup.changed) else it.notice,
                )
            }
        }
    }

    fun toggle(key: String) = extras.update {
        it.copy(deselected = if (key in it.deselected) it.deselected - key else it.deselected + key)
    }

    fun selectAll() = extras.update { it.copy(deselected = emptySet()) }

    fun selectNone() = extras.update { x -> x.copy(deselected = state.value.tiles.mapTo(HashSet()) { it.item.key }) }

    /** Changes a queued photo to "keep". Not a new review; never charged. */
    fun keepInstead(key: String) {
        viewModelScope.launch { container.engine.keepInsteadOfRemove(listOf(key)) }
    }

    fun dismissResult() = extras.update { it.copy(result = null, prepareFailed = false, notice = null) }

    fun moveSelectedToTrash() {
        val current = state.value
        val selected = current.selectedItems
        if (current.busy || selected.isEmpty()) return
        extras.update { it.copy(status = TrashStatus.PREPARING, result = null, prepareFailed = false, notice = null) }
        viewModelScope.launch {
            val probe = container.media.probe(selected)
            val preparation = TrashPlanner.prepare(selected, probe, fullAccess = container.media.access.value == PhotoAccess.FULL)
            container.engine.applyQueueCleanup(
                trashed = preparation.alreadyTrashed.map { it.key },
                missing = preparation.missing.map { it.key },
                changed = preparation.changed.map { it.key },
            )
            extras.update { it.copy(inaccessible = it.inaccessible + preparation.inaccessible.map { item -> item.key }) }
            run = TrashRun(preparation)
            launchNextBatch()
        }
    }

    private suspend fun launchNextBatch() {
        val run = run ?: return finish()
        val batch = run.nextBatch() ?: return finish()
        try {
            val pendingIntent = container.media.createTrashRequest(batch)
            pendingBatch = batch
            extras.update { it.copy(status = TrashStatus.AWAITING_SYSTEM) }
            _launches.send(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // e.g. a URI the system refuses. Nothing was moved; the queue stays intact.
            run.record(BatchOutcome(emptyList(), batch, emptyList(), emptyList()), cancelled = true)
            extras.update { it.copy(prepareFailed = true) }
            finish()
        }
    }

    /** Result of Android's confirmation dialog. [confirmed] = RESULT_OK. */
    fun onSystemResult(confirmed: Boolean) {
        viewModelScope.launch {
            val batch = pendingBatch
            val run = run
            pendingBatch = null
            if (batch == null || run == null) {
                // Process was recreated while the dialog was open: re-read the whole queue.
                extras.update { it.copy(status = TrashStatus.IDLE) }
                reconcile()
                return@launch
            }
            extras.update { it.copy(status = TrashStatus.CHECKING) }
            val outcome = TrashPlanner.reconcile(batch, confirmed, container.media.probe(batch))
            container.engine.applyQueueCleanup(
                trashed = outcome.trashed.map { it.key },
                missing = outcome.missing.map { it.key },
                changed = outcome.changed.map { it.key },
            )
            run.record(outcome, cancelled = !confirmed)
            if (run.isFinished) finish() else launchNextBatch()
        }
    }

    /** Called if the dialog could not be shown at all. */
    fun onLaunchFailed() {
        val run = run
        val batch = pendingBatch
        pendingBatch = null
        if (run != null && batch != null) {
            run.record(BatchOutcome(emptyList(), batch, emptyList(), emptyList()), cancelled = true)
        }
        extras.update { it.copy(prepareFailed = true) }
        finish()
    }

    private fun finish() {
        val summary = run?.currentSummary
        run = null
        pendingBatch = null
        extras.update { it.copy(status = TrashStatus.IDLE, result = summary, deselected = emptySet()) }
    }
}
