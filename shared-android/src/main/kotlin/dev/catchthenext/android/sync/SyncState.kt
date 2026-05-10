package dev.catchthenext.android.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.catchthenext.model.Stop

data class SyncState(
    val myNodeId: String = "",
    val myCounter: Long = 0L,
    val items: Map<String, FavoriteEntry> = emptyMap(),
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): SyncState? =
            runCatching { Gson().fromJson(json, SyncState::class.java) }.getOrNull()

        fun itemsFromJson(json: String): Map<String, FavoriteEntry>? = runCatching {
            val type = object : TypeToken<Map<String, FavoriteEntry>>() {}.type
            Gson().fromJson<Map<String, FavoriteEntry>>(json, type)
        }.getOrNull()

        fun itemsToJson(items: Map<String, FavoriteEntry>): String = Gson().toJson(items)
    }
}

data class FavoriteEntry(
    val stop: Stop? = null,
    val authorNodeId: String = "",
    val authorCounter: Long = 0L,
    val tombstone: Boolean = false,
    val tombstoneAt: Long = 0L,
)
