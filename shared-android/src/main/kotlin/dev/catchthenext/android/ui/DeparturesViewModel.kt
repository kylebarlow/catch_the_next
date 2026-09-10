package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.Tuning
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface DeparturesUi {
    object Loading : DeparturesUi
    data class Loaded(val tileState: TileState, val isRefreshing: Boolean = false) : DeparturesUi
}

class DeparturesViewModel(
    private val computeState: suspend (forceFresh: Boolean, onIntermediate: suspend (TileState) -> Unit) -> TileState,
    private val quickCacheRead: (suspend () -> TileState?)? = null,
    favoritesCountFlow: Flow<Int>? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow<DeparturesUi>(DeparturesUi.Loading)
    val ui: StateFlow<DeparturesUi> = _ui

    // Guards against the double run on open: the VM's own init pass and the screen's
    // repeatOnLifecycle(RESUMED) refresh would otherwise each run the (location-gated) pipeline.
    private val runMutex = Mutex()
    @Volatile private var lastCompletedAt = 0L

    init {
        viewModelScope.launch(ioDispatcher) {
            // Hold the lock across the cache read too, so a refresh(false) arriving from the
            // screen's repeatOnLifecycle while the cache read is still in flight is dropped.
            runMutex.withLock {
                // Show cached state immediately if available, skipping the loading spinner.
                // The network run is still in flight, so flag it as refreshing.
                val quick = quickCacheRead?.invoke()
                if (quick != null) _ui.value = DeparturesUi.Loaded(quick, isRefreshing = true)
                runCompute(force = false)
            }
        }
        // Re-run computeState whenever the favorites count changes (e.g. cold-start sync
        // populates favorites after the initial pass completed with 0).
        if (favoritesCountFlow != null) {
            viewModelScope.launch(ioDispatcher) {
                favoritesCountFlow.drop(1).distinctUntilChanged().collect {
                    refreshInternal(force = false, bypassDebounce = true)
                }
            }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch(ioDispatcher) { refreshInternal(force) }
    }

    /**
     * [force] bypasses both the debounce and the per-stop departures cache; [bypassDebounce]
     * only skips the debounce (used for events that genuinely changed the inputs).
     */
    private suspend fun refreshInternal(force: Boolean, bypassDebounce: Boolean = false) {
        val skipDebounce = force || bypassDebounce
        if (!skipDebounce) {
            // A run is already in flight — its result will reach the UI.
            if (runMutex.isLocked) return
            if (now() - lastCompletedAt < Tuning.VM_REFRESH_DEBOUNCE_MS) return
        }
        runMutex.withLock { runCompute(force) }
    }

    /**
     * Runs the pipeline and publishes the result. The pipeline's intermediate state (departures
     * for the location it already had) is published as soon as it lands, still flagged
     * refreshing, so the screen never waits on the parallel fresh fix. Callers must hold
     * [runMutex].
     */
    private suspend fun runCompute(force: Boolean) {
        val current = _ui.value
        if (current is DeparturesUi.Loaded) {
            _ui.value = current.copy(isRefreshing = true)
        }
        val state = computeState(force) { intermediate ->
            _ui.value = DeparturesUi.Loaded(intermediate, isRefreshing = true)
        }
        _ui.value = DeparturesUi.Loaded(state)
        lastCompletedAt = now()
    }
}
