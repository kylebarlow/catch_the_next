package dev.catchthenext.android.sync

import com.google.gson.reflect.TypeToken
import dev.catchthenext.json.AppJson
import dev.catchthenext.model.Stop

data class SyncState(
    val myNodeId: String = "",
    val myCounter: Long = 0L,
    val items: Map<String, FavoriteEntry> = emptyMap(),
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = AppJson.gson

        fun fromJson(json: String): SyncState? =
            runCatching { gson.fromJson(json, SyncState::class.java) }.getOrNull()

        fun itemsFromJson(json: String): Map<String, FavoriteEntry>? = runCatching {
            val type = object : TypeToken<Map<String, FavoriteEntry>>() {}.type
            gson.fromJson<Map<String, FavoriteEntry>>(json, type)
        }.getOrNull()

        fun itemsToJson(items: Map<String, FavoriteEntry>): String = gson.toJson(items)
    }
}

data class FavoriteEntry(
    val stop: Stop? = null,
    val authorNodeId: String = "",
    val authorCounter: Long = 0L,
    val tombstone: Boolean = false,
    val tombstoneAt: Long = 0L,
)
