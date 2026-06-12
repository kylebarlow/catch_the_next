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
import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertSeverity

@Composable
internal fun AlertItem(alert: Alert, maxLines: Int = Int.MAX_VALUE) {
    val bgColor = when (alert.severityLevel) {
        AlertSeverity.SEVERE, AlertSeverity.WARNING -> Color(0xFF4A3000)
        else -> Color(0xFF2A2A2A)
    }
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(bgColor),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Text(
                text = alert.headerText.orEmpty(),
                fontWeight = FontWeight.Bold,
            )
            val desc = alert.descriptionText
            if (!desc.isNullOrBlank()) {
                Text(text = desc, maxLines = maxLines)
            }
        }
    }
}
