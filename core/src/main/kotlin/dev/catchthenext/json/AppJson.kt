package dev.catchthenext.json

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Shared Gson instances. Gson is thread-safe once constructed, so a single instance is reused
 * across all serialization sites rather than allocating one per store.
 */
object AppJson {
    val gson: Gson = Gson()
    val prettyGson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }
}
