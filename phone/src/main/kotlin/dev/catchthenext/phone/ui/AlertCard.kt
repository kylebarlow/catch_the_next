package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.catchthenext.android.ui.AlertEmphasis
import dev.catchthenext.android.ui.display
import dev.catchthenext.android.ui.emphasis
import dev.catchthenext.model.Alert

@Composable
internal fun AlertCard(alert: Alert) {
    val uriHandler = LocalUriHandler.current
    val containerColor = when (alert.emphasis()) {
        AlertEmphasis.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        AlertEmphasis.ELEVATED -> MaterialTheme.colorScheme.tertiaryContainer
        AlertEmphasis.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val display = alert.display()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(display.header, fontWeight = FontWeight.Bold)
            }
            display.description?.let { desc ->
                Text(desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            display.url?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("More info") }
            }
        }
    }
}
