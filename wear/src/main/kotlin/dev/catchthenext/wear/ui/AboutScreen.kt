package dev.catchthenext.wear.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.wear.BuildConfig

@Composable
fun AboutScreen(viewModel: AboutViewModel) {
    val attributions by viewModel.attributions.collectAsState()
    val context = LocalContext.current

    val creditedFeeds = attributions.filter {
        !it.useWithoutAttribution || !it.attributionText.isNullOrBlank()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Text
        )()
    ) {
        item { Text("About") }
        item { Text("Catch the Next v${BuildConfig.VERSION_NAME}") }

        item { Text("Data") }
        item { Text("Transit data via Transitland") }
        item {
            Chip(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.transit.land/terms"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                label = { Text("transit.land/terms") },
                secondaryLabel = { Text("Terms & attribution") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        if (creditedFeeds.isNotEmpty()) {
            item { Text("Feed credits") }
            items(creditedFeeds.toList()) { feed ->
                val label = feed.feedName ?: feed.feedOnestopId
                val detail = buildString {
                    feed.attributionText?.let { append(it) }
                    feed.licenseSpdx?.let { if (isNotEmpty()) append(" · "); append(it) }
                }
                Text("$label${if (detail.isNotBlank()) ": $detail" else ""}")
            }
        }

        item { Text("Open source") }
        item { Text("Kotlin · OkHttp · Gson\nWear Compose · Horologist\nProtoLayout") }
    }
}
