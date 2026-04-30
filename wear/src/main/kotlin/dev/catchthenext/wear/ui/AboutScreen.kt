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
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.wear.BuildConfig

@Composable
fun AboutScreen(viewModel: AboutViewModel) {
    val attributions by viewModel.attributions.collectAsState()
    val unattributedFeeds by viewModel.unattributedFeeds.collectAsState()
    val context = LocalContext.current

    val creditedFeeds = attributions.filter {
        !it.useWithoutAttribution || !it.attributionText.isNullOrBlank()
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
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
                onClick = { openUrl("https://www.transit.land/terms") },
                label = { Text("transit.land/terms") },
                secondaryLabel = { Text("Terms & attribution") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        if (creditedFeeds.isNotEmpty()) {
            item { Text("Feed credits") }
            items(creditedFeeds.toList()) { feed ->
                val label = feed.feedName ?: feed.feedOnestopId
                val feedPageUrl = "https://www.transit.land/feeds/${feed.feedOnestopId}"
                Chip(
                    onClick = { openUrl(feedPageUrl) },
                    label = { Text(label) },
                    secondaryLabel = feed.attributionText?.takeIf { it.isNotBlank() }?.let {
                        { Text(it) }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                feed.attributionInstructions?.takeIf { it.isNotBlank() }?.let {
                    Text(it)
                }
                feed.licenseSpdx?.takeIf { it.isNotBlank() }?.let {
                    Text(it)
                }
                feed.licenseUrl?.let { url ->
                    Chip(
                        onClick = { openUrl(url) },
                        label = { Text("License") },
                        secondaryLabel = { Text(label) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }

        if (BuildConfig.DEBUG && unattributedFeeds.isNotEmpty()) {
            item { Text("Missing attribution") }
            items(unattributedFeeds.toList()) { feed ->
                Text(feed.feedName ?: feed.feedOnestopId)
            }
        }

        item { Text("Open source") }
        item { Text("Kotlin · OkHttp · Gson\nWear Compose · Horologist\nProtoLayout") }
    }
}
