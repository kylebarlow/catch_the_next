package dev.catchthenext.phone

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.catchthenext.android.location.LocationCache
import dev.catchthenext.android.ui.PlaceSearchViewModel
import dev.catchthenext.android.ui.SharedViewModelDeps
import dev.catchthenext.android.ui.createSharedViewModel

class PhoneViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val deps = SharedViewModelDeps(
            context = context,
            client = PhoneGraph.transitlandClient(),
            favoritesManager = PhoneGraph.favoritesManager(context),
            syncStateStore = PhoneGraph.syncStateStore(context),
            favoritesSyncController = PhoneGraph.favoritesSyncController(),
            peerLabel = "watch",
        )
        createSharedViewModel(modelClass, deps)?.let { return it as T }

        return when {
            modelClass.isAssignableFrom(PlaceSearchViewModel::class.java) ->
                PlaceSearchViewModel(
                    geocode = { q, focusLat, focusLon ->
                        PhoneGraph.transitlandClient().geocodePlace(q, focusLat, focusLon)
                    },
                    focus = { LocationCache.get() },
                ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
