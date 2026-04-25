package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.wear.tile.TileState
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow<DeparturesUi>(DeparturesUi.Loading)
    val ui: StateFlow<DeparturesUi> = _ui

    init { refresh() }

    fun refresh(force: Boolean = false) {
        _ui.value = DeparturesUi.Loading
        viewModelScope.launch(ioDispatcher) {
            _ui.value = DeparturesUi.Loaded(computeState(force))
        }
    }
}
