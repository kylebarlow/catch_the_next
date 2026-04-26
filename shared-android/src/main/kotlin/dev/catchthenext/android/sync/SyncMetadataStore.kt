package dev.catchthenext.android.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.syncMetadataStore by preferencesDataStore(name = "sync_metadata")

class SyncMetadataStore(private val context: Context) {
    private val keyVersion = longPreferencesKey("local_version")
    private val keyUpdatedAt = longPreferencesKey("local_updated_at")

    suspend fun read(): Pair<Long, Long> {
        val prefs = context.syncMetadataStore.data.first()
        return Pair(prefs[keyVersion] ?: 0L, prefs[keyUpdatedAt] ?: 0L)
    }

    suspend fun write(version: Long, updatedAt: Long) {
        context.syncMetadataStore.edit { prefs ->
            prefs[keyVersion] = version
            prefs[keyUpdatedAt] = updatedAt
        }
    }
}
