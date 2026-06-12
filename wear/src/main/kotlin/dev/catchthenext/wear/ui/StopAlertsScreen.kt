package dev.catchthenext.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.android.ui.StopAlertsViewModel

@Composable
fun StopAlertsScreen(navController: NavController, viewModel: StopAlertsViewModel) {
    val ui by viewModel.ui.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Text
        )()
    ) {
        item { Text(ui.stopName ?: "Service alerts", fontWeight = FontWeight.Bold) }
        if (ui.alerts.isEmpty()) {
            item { Text("No active alerts") }
        } else {
            items(ui.alerts) { alert -> AlertItem(alert) }
        }
    }
}
