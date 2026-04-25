package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.FeedAttribution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AboutViewModel(
    attributionsFlow: Flow<Set<FeedAttribution>>,
) : ViewModel() {

    val attributions: StateFlow<Set<FeedAttribution>> = attributionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
