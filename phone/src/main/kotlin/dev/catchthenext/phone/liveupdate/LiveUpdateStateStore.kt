package dev.catchthenext.phone.liveupdate

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.liveUpdateDataStore by preferencesDataStore("live_update_state")
private val KEY_STATE = stringPreferencesKey("tracking_state")
private val gson = Gson()

class LiveUpdateStateStore(private val ctx: Context) {

    val flow: Flow<TrackingState?> = ctx.liveUpdateDataStore.data.map { prefs ->
        prefs[KEY_STATE]?.let {
            runCatching { gson.fromJson(it, TrackingState::class.java) }.getOrNull()
        }
    }

    suspend fun save(state: TrackingState) {
        ctx.liveUpdateDataStore.edit { it[KEY_STATE] = gson.toJson(state) }
    }

    suspend fun clear() {
        ctx.liveUpdateDataStore.edit { it.remove(KEY_STATE) }
    }
}
