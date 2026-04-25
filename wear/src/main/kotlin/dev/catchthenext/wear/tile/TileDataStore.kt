package dev.catchthenext.wear.tile

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.tileDataStore by preferencesDataStore(name = "tile_cache")

class TileDataStore(private val context: Context) {
    private val gson = Gson()

    object Keys {
        val lat = doublePreferencesKey("cached_lat")
        val lon = doublePreferencesKey("cached_lon")
        // v2 schema: one JSON blob for all nearby stops (replaces single-stop closestStopId + departuresJson)
        val nearbyDeparturesJson = stringPreferencesKey("nearby_departures_v2")
    }

    suspend fun updateLocation(lat: Double, lon: Double) {
        context.tileDataStore.edit { prefs ->
            prefs[Keys.lat] = lat
            prefs[Keys.lon] = lon
        }
    }

    suspend fun updateNearbyDepartures(stops: List<StopWithDepartures>) {
        val now = System.currentTimeMillis()
        val cached = stops.map { swd ->
            CachedStopDepartures(
                stopId = swd.stop.id,
                departures = swd.departures,
                fetchedAt = now
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
            nearbyDepartures = nearby
        )
    }
}

data class CachedStopDepartures(
    val stopId: Long,
    val departures: List<CachedDeparture>,
    val fetchedAt: Long
)

data class CachedTileData(
    val lat: Double?,
    val lon: Double?,
    val nearbyDepartures: List<CachedStopDepartures> = emptyList()
)
