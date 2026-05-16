package dev.catchthenext.phone.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

class ClosestFavoriteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .padding(8.dp)
                        .clickable(actionRunCallback<StartClosestTrackingAction>(actionParametersOf())),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "🚌",
                            style = TextStyle(fontSize = 18.sp),
                            modifier = GlanceModifier.padding(bottom = 2.dp),
                        )
                        Text(
                            "Nearest\nstop",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}

class ClosestFavoriteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ClosestFavoriteWidget()
}
