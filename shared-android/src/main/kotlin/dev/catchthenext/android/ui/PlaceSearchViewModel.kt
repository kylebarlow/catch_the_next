package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.model.Place
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

sealed interface PlaceSearchUi {
    object Idle : PlaceSearchUi
    object Searching : PlaceSearchUi
    data class Results(val places: List<Place>) : PlaceSearchUi
    object NoResults : PlaceSearchUi
    data class Error(val msg: String) : PlaceSearchUi
}

@OptIn(FlowPreview::class)
class PlaceSearchViewModel(
    private val geocode: suspend (String, Double?, Double?) -> List<Place>,
    private val focus: () -> LatLon? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 350L,
) : ViewModel() {

    private val _ui = MutableStateFlow<PlaceSearchUi>(PlaceSearchUi.Idle)
    val ui: StateFlow<PlaceSearchUi> = _ui

    private val _query = MutableStateFlow("")

    init {
        _query
            .debounce(debounceMs)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                flow {
                    if (q.length < 3) {
                        emit(PlaceSearchUi.Idle)
                        return@flow
                    }
                    emit(PlaceSearchUi.Searching)
                    val result = runCatching {
                        val f = focus()
                        geocode(q, f?.lat, f?.lon)
                    }
                    emit(
                        result.fold(
                            onSuccess = { places ->
                                if (places.isEmpty()) PlaceSearchUi.NoResults
                                else PlaceSearchUi.Results(places)
                            },
                            onFailure = { PlaceSearchUi.Error(it.message ?: "Search error") },
                        )
                    )
                }
            }
            .onEach { _ui.value = it }
            .launchIn(viewModelScope)
    }

    fun onQueryChanged(q: String) {
        _query.value = q
        if (q.length < 3) _ui.value = PlaceSearchUi.Idle
    }
}
