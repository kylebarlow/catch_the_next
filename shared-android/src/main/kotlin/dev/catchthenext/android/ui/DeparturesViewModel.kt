package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.tile.TileState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface DeparturesUi {
    object Loading : DeparturesUi
    data class Loaded(val tileState: TileState, val isRefreshing: Boolean = false) : DeparturesUi
}

class DeparturesViewModel(
    private val computeState: suspend (forceFresh: Boolean) -> TileState,
    private val quickCacheRead: (suspend () -> TileState?)? = null,
    favoritesCountFlow: Flow<Int>? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow<DeparturesUi>(DeparturesUi.Loading)
    val ui: StateFlow<DeparturesUi> = _ui

    init {
        viewModelScope.launch(ioDispatcher) {
            // Show cached state immediately if available, skipping the loading spinner
            val quick = quickCacheRead?.invoke()
            if (quick != null) _ui.value = DeparturesUi.Loaded(quick)
            _ui.value = DeparturesUi.Loaded(computeState(false))
        }
        // Re-run computeState whenever the favorites count changes (e.g. cold-start sync
        // populates favorites after the initial pass completed with 0).
        if (favoritesCountFlow != null) {
            viewModelScope.launch(ioDispatcher) {
                favoritesCountFlow.drop(1).distinctUntilChanged().collect {
                    refreshInternal(force = false)
                }
            }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch(ioDispatcher) { refreshInternal(force) }
    }

    private suspend fun refreshInternal(force: Boolean) {
        val current = _ui.value
        if (current is DeparturesUi.Loaded) {
            _ui.value = current.copy(isRefreshing = true)
        }
        _ui.value = DeparturesUi.Loaded(computeState(force))
    }
}
