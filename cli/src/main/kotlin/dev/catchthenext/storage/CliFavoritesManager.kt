package dev.catchthenext.storage

import com.google.gson.reflect.TypeToken
import dev.catchthenext.json.AppJson
import dev.catchthenext.model.Stop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CliFavoritesManager(
    private val filePath: String = "${System.getProperty("user.home")}/.catch_the_next/favorites.json"
) : FavoritesManager {
    private val gson = AppJson.prettyGson
    private val _flow = MutableStateFlow(emptyList<Stop>())

    override fun getFavorites(): List<Stop> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Stop>>() {}.type
            gson.fromJson<List<Stop>>(file.readText(), type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun saveFavorites(stops: List<Stop>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(stops))
        _flow.tryEmit(stops)
    }

    override fun addFavorite(stop: Stop) {
        val current = getFavorites().toMutableList()
        if (current.none { it.onestopId != null && it.onestopId == stop.onestopId }) {
            current.add(stop)
            saveFavorites(current)
            println("  Added: ${stop.displayString()}")
        } else {
            println("  Already a favorite: ${stop.stopName}")
        }
    }

    override fun removeFavorite(onestopId: String): Boolean {
        val current = getFavorites().toMutableList()
        val removed = current.removeAll { it.onestopId == onestopId }
        if (removed) saveFavorites(current)
        return removed
    }

    override fun isFavorite(onestopId: String): Boolean = getFavorites().any { it.onestopId == onestopId }

    override fun favoritesFlow(): Flow<List<Stop>> = _flow.asStateFlow()
}
