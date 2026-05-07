package dev.catchthenext.android.sync

import com.google.gson.Gson
import dev.catchthenext.model.Stop

data class FavoritesSyncPayload(
    val favorites: List<Stop>,
    val updatedAt: Long,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): FavoritesSyncPayload? =
            runCatching { Gson().fromJson(json, FavoritesSyncPayload::class.java) }.getOrNull()

        fun fromBytes(bytes: ByteArray): FavoritesSyncPayload? =
            runCatching { Gson().fromJson(bytes.toString(Charsets.UTF_8), FavoritesSyncPayload::class.java) }.getOrNull()
    }
}
