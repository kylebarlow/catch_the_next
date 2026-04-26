package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.tile.TileState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DeparturesUi {
    object Loading : DeparturesUi
    data class Loaded(val tileState: TileState) : DeparturesUi
}

class DeparturesViewModel(
    private val computeState: suspend (forceFresh: Boolean) -> TileState,
    private val quickCacheRead: (suspend () -> TileState?)? = null,
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
    }

    fun refresh(force: Boolean = false) {
        _ui.value = DeparturesUi.Loading
        viewModelScope.launch(ioDispatcher) {
            _ui.value = DeparturesUi.Loaded(computeState(force))
        }
    }
}
