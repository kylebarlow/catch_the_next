package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.catchthenext.android.ui.DetailsUi
import dev.catchthenext.android.ui.StopDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(navController: NavController, viewModel: StopDetailsViewModel) {
    val ui by viewModel.ui.collectAsState()

    val title = when (val s = ui) {
        is DetailsUi.Loaded -> s.stop.stopName
        else -> "Stop details"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ui is DetailsUi.Loaded) {
                        val loaded = ui as DetailsUi.Loaded
                        IconButton(onClick = {
                            viewModel.toggleFavorite()
                            if (loaded.isFavorite) navController.popBackStack()
                        }) {
                            Icon(
                                imageVector = if (loaded.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (loaded.isFavorite) "Remove favorite" else "Add favorite",
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val state = ui) {
                is DetailsUi.Loading -> CircularProgressIndicator()
                is DetailsUi.Error -> Text(state.msg)
                is DetailsUi.Loaded -> {
                    if (state.departures.isEmpty()) {
                        Text("No upcoming departures")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.departures) { departure ->
                                ListItem(headlineContent = { Text(departure.displayString()) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
