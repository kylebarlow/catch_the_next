package dev.catchthenext.android.tile

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.reflect.TypeToken
import dev.catchthenext.json.AppJson
import dev.catchthenext.model.Alert
import kotlinx.coroutines.flow.first

private val Context.tileDataStore by preferencesDataStore(name = "tile_cache")

class TileDataStore(private val context: Context) {
    private val gson = AppJson.gson

    object Keys {
        val lat = doublePreferencesKey("cached_lat")
        val lon = doublePreferencesKey("cached_lon")
        val locationAt = longPreferencesKey("cached_loc_at")
        // v2 schema: one JSON blob for all nearby stops (replaces single-stop closestStopId + departuresJson)
        val nearbyDeparturesJson = stringPreferencesKey("nearby_departures_v2")
    }

    suspend fun updateLocation(lat: Double, lon: Double, at: Long = System.currentTimeMillis()) {
        context.tileDataStore.edit { prefs ->
            prefs[Keys.lat] = lat
            prefs[Keys.lon] = lon
            prefs[Keys.locationAt] = at
        }
    }

    suspend fun updateNearbyDepartures(stops: List<StopWithDepartures>) {
        val cached = stops.map { swd ->
            CachedStopDepartures(
                stopId = swd.stop.id,
                departures = swd.departures,
                fetchedAt = swd.fetchedAt,
                alerts = swd.alerts,
            )
        }
        context.tileDataStore.edit { prefs ->
            prefs[Keys.nearbyDeparturesJson] = gson.toJson(cached)
        }
    }

    suspend fun read(): CachedTileData {
        val prefs = context.tileDataStore.data.first()
        val json = prefs[Keys.nearbyDeparturesJson]
        val nearby = if (json != null) {
            val type = object : TypeToken<List<CachedStopDepartures>>() {}.type
            runCatching { gson.fromJson<List<CachedStopDepartures>>(json, type) }
                .getOrNull() ?: emptyList()
        } else emptyList()
        return CachedTileData(
            lat = prefs[Keys.lat],
            lon = prefs[Keys.lon],
            locationAt = prefs[Keys.locationAt],
            nearbyDepartures = nearby
        )
    }
}

data class CachedStopDepartures(
    val stopId: Long,
    val departures: List<CachedDeparture>,
    val fetchedAt: Long,
    val alerts: List<Alert>? = null,
)

data class CachedTileData(
    val lat: Double?,
    val lon: Double?,
    /** When [lat]/[lon] were observed. Null on installs that predate the key — older than anything. */
    val locationAt: Long? = null,
    val nearbyDepartures: List<CachedStopDepartures> = emptyList()
)
