package dev.catchthenext.wear.tile

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.catchthenext.model.Stop
import kotlinx.coroutines.flow.first

private val Context.tileDataStore by preferencesDataStore(name = "tile_cache")

class TileDataStore(private val context: Context) {
    private val gson = Gson()

    object Keys {
        val lat = doublePreferencesKey("cached_lat")
        val lon = doublePreferencesKey("cached_lon")
        val closestStopId = longPreferencesKey("closest_stop_id")
        val departuresJson = stringPreferencesKey("departures_json")
        val departuresFetchedAt = longPreferencesKey("departures_fetched_at")
    }

    suspend fun updateLocation(lat: Double, lon: Double) {
        context.tileDataStore.edit { prefs ->
            prefs[Keys.lat] = lat
            prefs[Keys.lon] = lon
        }
    }

    suspend fun updateDepartures(stop: Stop, departures: List<CachedDeparture>) {
        context.tileDataStore.edit { prefs ->
            prefs[Keys.closestStopId] = stop.id
            prefs[Keys.departuresJson] = gson.toJson(departures)
            prefs[Keys.departuresFetchedAt] = System.currentTimeMillis()
        }
    }

    suspend fun read(): CachedTileData {
        val prefs = context.tileDataStore.data.first()
        val departuresJson = prefs[Keys.departuresJson]
        val departures = if (departuresJson != null) {
            val type = object : TypeToken<List<CachedDeparture>>() {}.type
            runCatching { gson.fromJson<List<CachedDeparture>>(departuresJson, type) }
                .getOrNull() ?: emptyList()
        } else emptyList()
        return CachedTileData(
            lat = prefs[Keys.lat],
            lon = prefs[Keys.lon],
            closestStopId = prefs[Keys.closestStopId],
            departures = departures,
            departuresFetchedAt = prefs[Keys.departuresFetchedAt]
        )
    }
}

data class CachedTileData(
    val lat: Double?,
    val lon: Double?,
    val closestStopId: Long?,
    val departures: List<CachedDeparture>,
    val departuresFetchedAt: Long?
)
