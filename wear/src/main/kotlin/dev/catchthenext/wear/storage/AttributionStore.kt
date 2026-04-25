package dev.catchthenext.wear.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.catchthenext.model.FeedAttribution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.attributionDataStore by preferencesDataStore(name = "attributions")

class AttributionStore(private val context: Context) {
    private val gson = Gson()
    private val feedsKey = stringPreferencesKey("known_feeds")

    val attributionsFlow: Flow<Set<FeedAttribution>> = context.attributionDataStore.data.map { prefs ->
        val json = prefs[feedsKey] ?: return@map emptySet()
        val type = object : TypeToken<Set<FeedAttribution>>() {}.type
        runCatching { gson.fromJson<Set<FeedAttribution>>(json, type) }.getOrNull() ?: emptySet()
    }

    suspend fun recordSeen(feeds: List<FeedAttribution>) {
        if (feeds.isEmpty()) return
        context.attributionDataStore.edit { prefs ->
            val existing: Set<FeedAttribution> = prefs[feedsKey]?.let { json ->
                val type = object : TypeToken<Set<FeedAttribution>>() {}.type
                runCatching { gson.fromJson<Set<FeedAttribution>>(json, type) }.getOrNull()
            } ?: emptySet()
            val merged = (existing + feeds).distinctBy { it.feedOnestopId }.toSet()
            prefs[feedsKey] = gson.toJson(merged)
        }
    }
}
