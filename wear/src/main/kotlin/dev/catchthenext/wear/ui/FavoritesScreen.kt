package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Text
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.navigation.NavController
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults

@Composable
fun FavoritesScreen(navController: NavController, viewModel: FavoritesViewModel) {
    val favorites by viewModel.favorites.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(first = ScalingLazyColumnDefaults.ItemType.Text, last = ScalingLazyColumnDefaults.ItemType.Text)()
    ) {
        items(favorites) { stop ->
            Chip(
                onClick = { /* TODO: Go to Stop Details */ },
                label = { Text(stop.stopName) }
            )
        }
    }
}
