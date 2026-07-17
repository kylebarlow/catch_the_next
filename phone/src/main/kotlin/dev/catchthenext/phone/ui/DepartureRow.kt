package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.catchthenext.android.tile.absoluteTimeLabel
import dev.catchthenext.android.tile.departureColorArgb
import dev.catchthenext.model.Departure

/** Threshold past which an absolute clock time is shown alongside the countdown. */
private const val ABSOLUTE_TIME_MIN_MINUTES = 15L

/**
 * A single raw [Departure] rendered as a proportional-font row (route + headsign, colored
 * live/scheduled countdown, and — for far-out departures — an absolute clock time). Shared by
 * the stop details and stop confirm screens so neither renders the CLI-formatted displayString().
 */
@Composable
fun DepartureRow(departure: Departure, showAgency: Boolean = false) {
    val routeLabel = buildString {
        when {
            departure.routeShortName.isNotBlank() -> append(departure.routeShortName.trim())
            departure.routeLongName.isNotBlank() -> append(departure.routeLongName)
            else -> append("Unknown route")
        }
        if (departure.headsign.isNotBlank()) append(" → ${departure.headsign}")
    }
    val minutes = departure.displayDepartureMinutes
    val countdown = if (minutes <= 0L) "Now" else "$minutes min"
    val absolute = if (minutes >= ABSOLUTE_TIME_MIN_MINUTES) absoluteTimeLabel(departure.displayDepartureTime) else null

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (departure.scheduleRelationship != "SCHEDULED") {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = departure.scheduleRelationship,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(routeLabel, style = MaterialTheme.typography.bodyLarge)
            }
        },
        supportingContent = if (showAgency) departure.agencyName?.let { { Text(it) } } else null,
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(departureColorArgb(departure.timeSource)),
                    textAlign = TextAlign.End,
                )
                if (absolute != null) {
                    Text(
                        text = absolute,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
