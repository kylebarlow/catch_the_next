package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ConfirmUi {
    object Loading : ConfirmUi
    data class Loaded(val stop: Stop, val departures: List<Departure>) : ConfirmUi
    data class Error(val msg: String) : ConfirmUi
}

class StopConfirmViewModel(
    private val getDepartures: suspend (Long) -> List<Departure>,
    private val favoritesManager: FavoritesManager,
    private val stop: Stop,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow<ConfirmUi>(ConfirmUi.Loading)
    val ui: StateFlow<ConfirmUi> = _ui

    init {
        viewModelScope.launch(ioDispatcher) {
            _ui.value = runCatching {
                val departures = getDepartures(stop.id)
                ConfirmUi.Loaded(stop, departures)
            }.getOrElse { ConfirmUi.Error(it.message ?: "Error loading departures") }
        }
    }

    fun confirm() {
        val current = _ui.value as? ConfirmUi.Loaded ?: return
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.addFavorite(current.stop)
        }
    }
}
