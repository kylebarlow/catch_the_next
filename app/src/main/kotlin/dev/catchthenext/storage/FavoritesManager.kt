package dev.catchthenext.storage

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.catchthenext.model.Stop
import java.io.File

class FavoritesManager(
    private val filePath: String = "${System.getProperty("user.home")}/.catch_the_next/favorites.json"
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun getFavorites(): List<Stop> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Stop>>() {}.type
            gson.fromJson<List<Stop>>(file.readText(), type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(stops: List<Stop>) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(stops))
    }

    fun addFavorite(stop: Stop) {
        val current = getFavorites().toMutableList()
        if (current.none { it.id == stop.id }) {
            current.add(stop)
            saveFavorites(current)
            println("  Added: ${stop.displayString()}")
        } else {
            println("  Already a favorite: ${stop.stopName}")
        }
    }

    fun removeFavorite(stopId: Long): Boolean {
        val current = getFavorites().toMutableList()
        val removed = current.removeAll { it.id == stopId }
        if (removed) saveFavorites(current)
        return removed
    }

    fun isFavorite(stopId: Long): Boolean = getFavorites().any { it.id == stopId }
}
