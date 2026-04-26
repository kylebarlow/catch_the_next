package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.catchthenext.android.ui.ConfirmUi
import dev.catchthenext.android.ui.StopConfirmViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopConfirmScreen(navController: NavController, viewModel: StopConfirmViewModel) {
    val ui by viewModel.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm stop") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val state = ui) {
                is ConfirmUi.Loading -> CircularProgressIndicator()
                is ConfirmUi.Error -> Text(state.msg)
                is ConfirmUi.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = "Add \"${state.stop.stopName}\"?",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Row(Modifier.padding(top = 12.dp)) {
                                    Button(onClick = {
                                        viewModel.confirm()
                                        navController.popBackStack("favorites", inclusive = false)
                                    }) { Text("Confirm") }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { navController.popBackStack() }) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }
                    if (state.departures.isNotEmpty()) {
                        item {
                            Text(
                                text = "Upcoming departures",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
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
