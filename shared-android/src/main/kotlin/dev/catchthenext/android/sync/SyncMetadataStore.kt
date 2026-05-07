package dev.catchthenext.android.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.syncMetadataStore by preferencesDataStore(name = "sync_metadata")

data class SyncMetadata(
    val localUpdatedAt: Long,
)

class SyncMetadataStore(private val context: Context) {
    private val keyUpdatedAt = longPreferencesKey("local_updated_at")

    suspend fun read(): SyncMetadata {
        val prefs = context.syncMetadataStore.data.first()
        return SyncMetadata(
            localUpdatedAt = prefs[keyUpdatedAt] ?: 0L,
        )
    }

    suspend fun writeLocalUpdatedAt(ts: Long) {
        context.syncMetadataStore.edit { prefs ->
            prefs[keyUpdatedAt] = ts
        }
    }
}
