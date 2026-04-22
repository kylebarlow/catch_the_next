package dev.catchthenext.cli

import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import io.github.cdimascio.dotenv.dotenv
import io.github.cdimascio.dotenv.DotenvException

fun main() {
    val apiKey = loadApiKey()
    val baseUrl = System.getenv("CATCH_THE_NEXT_BASE_URL")
        ?: "https://transit.land/api/v2/rest"
    val client = TransitlandClient(apiKey, baseUrl)
    val favorites = FavoritesManager()

    println("\n=== Catch The Next ===\n")

    while (true) {
        printMenu()
        when (prompt("Choice").trim()) {
            "1" -> searchNearbyStops(client, favorites)
            "2" -> viewFavorites(favorites)
            "3" -> showDepartures(client, favorites)
            "4" -> removeFavorite(favorites)
            "5", "q", "quit", "exit" -> {
                println("\nGoodbye!")
                break
            }
            else -> println("  Invalid choice, try again.")
        }
        println()
    }
}

private fun printMenu() {
    println("─────────────────────────")
    println("1. Search for nearby stops")
    println("2. View favorite stops")
    println("3. Show next departures")
    println("4. Remove a favorite stop")
    println("5. Exit")
}

private fun searchNearbyStops(client: TransitlandClient, favorites: FavoritesManager) {
    val lat = prompt("Latitude").toDoubleOrNull() ?: run {
        println("  Invalid latitude."); return
    }
    val lon = prompt("Longitude").toDoubleOrNull() ?: run {
        println("  Invalid longitude."); return
    }
    val radiusInput = prompt("Search radius in meters [default 500]").trim()
    val radius = if (radiusInput.isEmpty()) 500 else radiusInput.toIntOrNull() ?: 500

    println("\n  Searching near $lat, $lon (radius: ${radius}m)...")
    val stops = try {
        client.getNearbyStops(lat, lon, radius)
    } catch (e: Exception) {
        println("  Error fetching stops: ${e.message}")
        return
    }

    if (stops.isEmpty()) {
        println("  No stops found. Try increasing the radius.")
        return
    }

    println("\n  Nearby stops:")
    stops.forEachIndexed { i, stop ->
        val star = if (favorites.isFavorite(stop.id)) " ★" else ""
        println("  [${i + 1}] ${stop.displayString()}$star")
    }

    println()
    val input = prompt("Add to favorites (comma-separated numbers, or Enter to skip)").trim()
    if (input.isEmpty()) return

    val indices = input.split(",").mapNotNull { it.trim().toIntOrNull()?.minus(1) }
    for (idx in indices) {
        if (idx in stops.indices) {
            favorites.addFavorite(stops[idx])
        } else {
            println("  Invalid index: ${idx + 1}")
        }
    }
}

private fun viewFavorites(favorites: FavoritesManager) {
    val stops = favorites.getFavorites()
    if (stops.isEmpty()) {
        println("\n  No favorite stops saved yet.")
        return
    }
    println("\n  Favorite stops:")
    stops.forEach { println("  ★ ${it.displayString()}") }
}

private fun showDepartures(client: TransitlandClient, favorites: FavoritesManager) {
    val stops = favorites.getFavorites()
    if (stops.isEmpty()) {
        println("\n  No favorite stops. Use option 1 to add some.")
        return
    }

    println("\n  === Next Departures ===")
    for (stop in stops) {
        println("\n  ${stop.stopName}  [ID: ${stop.id}]")
        val departures = try {
            client.getDepartures(stop.id)
        } catch (e: Exception) {
            println("    Error: ${e.message}")
            continue
        }

        if (departures.isEmpty()) {
            println("    No upcoming departures found.")
        } else {
            departures.take(8).forEach { println(it.displayString()) }
        }
    }
}

private fun removeFavorite(favorites: FavoritesManager) {
    val stops = favorites.getFavorites()
    if (stops.isEmpty()) {
        println("\n  No favorites to remove.")
        return
    }

    println("\n  Current favorites:")
    stops.forEachIndexed { i, stop -> println("  [${i + 1}] ${stop.displayString()}") }

    val input = prompt("Remove (comma-separated numbers, or Enter to cancel)").trim()
    if (input.isEmpty()) return

    val indices = input.split(",").mapNotNull { it.trim().toIntOrNull()?.minus(1) }
    for (idx in indices) {
        if (idx in stops.indices) {
            val stop = stops[idx]
            if (favorites.removeFavorite(stop.id)) {
                println("  Removed: ${stop.stopName}")
            }
        } else {
            println("  Invalid index: ${idx + 1}")
        }
    }
}

private fun prompt(label: String): String {
    print("\n  $label: ")
    return readLine() ?: ""
}

private fun loadApiKey(): String {
    // Try environment variable first, then .env file
    val fromEnv = System.getenv("TRANSITLAND_API_KEY")
    if (!fromEnv.isNullOrBlank()) return fromEnv

    return try {
        val dotenv = dotenv {
            ignoreIfMissing = true
        }
        val key = dotenv["TRANSITLAND_API_KEY"]
        if (key.isNullOrBlank()) exitMissingKey() else key
    } catch (_: DotenvException) {
        exitMissingKey()
    }
}

private fun exitMissingKey(): Nothing {
    System.err.println(
        "\nError: TRANSITLAND_API_KEY not set.\n" +
        "  Set it as an environment variable, or create a .env file:\n" +
        "    TRANSITLAND_API_KEY=your_key_here\n"
    )
    System.exit(1)
    throw IllegalStateException("unreachable")
}
