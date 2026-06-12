package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Text
import dev.catchthenext.android.ui.AlertEmphasis
import dev.catchthenext.android.ui.display
import dev.catchthenext.android.ui.emphasis
import dev.catchthenext.model.Alert

@Composable
internal fun AlertItem(alert: Alert, maxLines: Int = Int.MAX_VALUE) {
    val bgColor = when (alert.emphasis()) {
        AlertEmphasis.CRITICAL, AlertEmphasis.ELEVATED -> Color(0xFF4A3000)
        AlertEmphasis.NORMAL -> Color(0xFF2A2A2A)
    }
    val display = alert.display()
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(bgColor),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Text(
                text = display.header,
                fontWeight = FontWeight.Bold,
            )
            display.description?.let { desc ->
                Text(text = desc, maxLines = maxLines)
            }
        }
    }
}
