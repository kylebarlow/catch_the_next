package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.ui.AddStopUi
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.PlaceSearchUi
import dev.catchthenext.android.ui.PlaceSearchViewModel
import dev.catchthenext.model.Stop

@Composable
fun AddStopSearchTab(
    viewModel: AddStopViewModel,
    placeSearchViewModel: PlaceSearchViewModel,
    addStopUi: AddStopUi,
    onAdd: (Stop) -> Unit,
) {
    val placeUi by placeSearchViewModel.ui.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // Whether the user has selected a place and we're now showing stops at that location.
    var showingStopsForPlace by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { q ->
                query = q
                showingStopsForPlace = false
                placeSearchViewModel.onQueryChanged(q)
            },
            label = { Text("Search for a place or address") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (showingStopsForPlace) {
            // Show stops at the selected place location using the shared AddStopViewModel state.
            when (val state = addStopUi) {
                is AddStopUi.Locating -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is AddStopUi.Loaded -> StopList(stops = state.stops, onAdd = onAdd)
                is AddStopUi.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No stops found near this location")
                }
                is AddStopUi.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.msg)
                }
                else -> Unit
            }
        } else {
            // Show geocode results.
            when (val state = placeUi) {
                is PlaceSearchUi.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type at least 3 characters to search")
                }
                is PlaceSearchUi.Searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is PlaceSearchUi.NoResults -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No places found")
                }
                is PlaceSearchUi.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.msg)
                }
                is PlaceSearchUi.Results -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.places) { place ->
                        ListItem(
                            headlineContent = { Text(place.displayName) },
                            supportingContent = place.type?.let { { Text(it) } },
                            trailingContent = {
                                TextButton(onClick = {
                                    showingStopsForPlace = true
                                    viewModel.loadFor(LatLon(place.lat, place.lon))
                                }) { Text("Find stops") }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
