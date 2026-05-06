package dev.catchthenext.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "favorites")

class AndroidFavoritesManager(private val context: Context) : FavoritesManager {
    private val gson = Gson()
    private val FAVORITES_KEY = stringPreferencesKey("favorites_list")

    override fun favoritesFlow(): Flow<List<Stop>> = context.dataStore.data.map { prefs ->
        val json = prefs[FAVORITES_KEY] ?: return@map emptyList()
        val type = object : TypeToken<List<Stop>>() {}.type
        runCatching { gson.fromJson<List<Stop>>(json, type) }.getOrNull() ?: emptyList()
    }

    private fun getFavoritesJson(): String? = runBlocking {
        context.dataStore.data.map { preferences ->
            preferences[FAVORITES_KEY]
        }.first()
    }

    override fun getFavorites(): List<Stop> {
        val json = getFavoritesJson() ?: return emptyList()
        val type = object : TypeToken<List<Stop>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    override fun saveFavorites(stops: List<Stop>) = runBlocking {
        val json = gson.toJson(stops)
        context.dataStore.edit { preferences ->
            preferences[FAVORITES_KEY] = json
        }
        Unit
    }

    override fun addFavorite(stop: Stop) {
        val favorites = getFavorites().toMutableList()
        if (favorites.none { it.onestopId != null && it.onestopId == stop.onestopId }) {
            favorites.add(stop)
            saveFavorites(favorites)
        }
    }

    override fun removeFavorite(onestopId: String): Boolean {
        val favorites = getFavorites().toMutableList()
        val removed = favorites.removeAll { it.onestopId == onestopId }
        if (removed) {
            saveFavorites(favorites)
        }
        return removed
    }

    override fun isFavorite(onestopId: String): Boolean {
        return getFavorites().any { it.onestopId == onestopId }
    }
}
