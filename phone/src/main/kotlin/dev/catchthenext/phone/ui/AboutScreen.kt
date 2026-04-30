package dev.catchthenext.phone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.phone.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: AboutViewModel, navController: NavController? = null) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = if (navController != null) ({
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }) else ({})
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text("Catch the Next") },
                    supportingContent = { Text("v${BuildConfig.VERSION_NAME}") },
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text("Transit data") },
                    supportingContent = { Text("Via Transitland") },
                    trailingContent = {
                        TextButton(onClick = { openUrl("https://www.transit.land/terms") }) {
                            Text("transit.land/terms")
                        }
                    }
                )
                HorizontalDivider()
            }

            if (creditedFeeds.isNotEmpty()) {
                item {
                    Text(
                        text = "Feed credits",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                }
                items(creditedFeeds.toList()) { feed ->
                    val label = feed.feedName ?: feed.feedOnestopId
                    val feedPageUrl = "https://www.transit.land/feeds/${feed.feedOnestopId}"
                    ListItem(
                        modifier = Modifier.clickable { openUrl(feedPageUrl) },
                        headlineContent = { Text(label) },
                        supportingContent = {
                            Column {
                                feed.attributionText?.takeIf { it.isNotBlank() }?.let {
                                    Text(it)
                                }
                                feed.attributionInstructions?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                feed.licenseSpdx?.takeIf { it.isNotBlank() }?.let {
                                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        trailingContent = feed.licenseUrl?.let { url ->
                            { TextButton(onClick = { openUrl(url) }) { Text("License") } }
                        },
                    )
                    HorizontalDivider()
                }
            }

            if (BuildConfig.DEBUG && unattributedFeeds.isNotEmpty()) {
                item {
                    Text(
                        text = "Feeds missing attribution metadata",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                items(unattributedFeeds.toList()) { feed ->
                    ListItem(
                        headlineContent = { Text(feed.feedName ?: feed.feedOnestopId) },
                        supportingContent = { Text("use_without_attribution=false but no attribution text/instructions received") },
                    )
                }
                item { HorizontalDivider() }
            }

            item {
                ListItem(
                    headlineContent = { Text("Open source") },
                    supportingContent = { Text("Kotlin · OkHttp · Gson · Jetpack Compose · Glance") },
                )
            }
        }
    }
}
