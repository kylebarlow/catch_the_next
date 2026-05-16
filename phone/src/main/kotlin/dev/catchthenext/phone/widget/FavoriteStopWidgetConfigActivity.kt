package dev.catchthenext.phone.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.catchthenext.phone.PhoneGraph
import dev.catchthenext.phone.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FavoriteStopWidgetConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val favoritesFlow = PhoneGraph.favoritesManager(this).favoritesFlow()

        setContent {
            val favorites by favoritesFlow.collectAsState(emptyList())
            AppTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Choose a stop") }) }
                ) { innerPadding ->
                    if (favorites.isEmpty()) {
                        Text(
                            "No favorites yet — add stops in the app first.",
                            modifier = Modifier.padding(innerPadding).padding(16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                        ) {
                            items(favorites) { stop ->
                                ListItem(
                                    headlineContent = { Text(stop.stopName) },
                                    modifier = Modifier.clickable {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            val glanceId = GlanceAppWidgetManager(this@FavoriteStopWidgetConfigActivity)
                                                .getGlanceIdBy(appWidgetId)
                                            FavoriteStopWidget.configure(
                                                this@FavoriteStopWidgetConfigActivity,
                                                glanceId,
                                                stop.onestopId,
                                                stop.id,
                                                stop.stopName,
                                            )
                                            FavoriteStopWidget().update(
                                                this@FavoriteStopWidgetConfigActivity,
                                                glanceId,
                                            )
                                            setResult(
                                                RESULT_OK,
                                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                            )
                                            finish()
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
