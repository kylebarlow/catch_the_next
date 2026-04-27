package dev.catchthenext.android.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.syncMetadataStore by preferencesDataStore(name = "sync_metadata")

data class SyncMetadata(
    val ownVersion: Long,
    val ownUpdatedAt: Long,
    val peerVersions: Map<String, Long>,
    val publishPending: Boolean,
)

class SyncMetadataStore(private val context: Context) {
    private val keyVersion = longPreferencesKey("local_version")
    private val keyUpdatedAt = longPreferencesKey("local_updated_at")
    private val keyPeerVersions = stringPreferencesKey("peer_versions_json")
    private val keyPublishPending = booleanPreferencesKey("publish_pending")

    private val gson = Gson()

    suspend fun read(): SyncMetadata {
        val prefs = context.syncMetadataStore.data.first()
        val peerVersions: Map<String, Long> = prefs[keyPeerVersions]?.let { json ->
            runCatching {
                gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
            }.getOrNull()
        } ?: emptyMap()
        return SyncMetadata(
            ownVersion = prefs[keyVersion] ?: 0L,
            ownUpdatedAt = prefs[keyUpdatedAt] ?: 0L,
            peerVersions = peerVersions,
            publishPending = prefs[keyPublishPending] ?: false,
        )
    }

    suspend fun writeOwn(version: Long, updatedAt: Long) {
        context.syncMetadataStore.edit { prefs ->
            prefs[keyVersion] = version
            prefs[keyUpdatedAt] = updatedAt
            prefs[keyPublishPending] = false
        }
    }

    suspend fun writePeerVersion(nodeId: String, version: Long) {
        context.syncMetadataStore.edit { prefs ->
            val existing: Map<String, Long> = prefs[keyPeerVersions]?.let { json ->
                runCatching {
                    gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
                }.getOrNull()
            } ?: emptyMap()
            val updated = existing + (nodeId to version)
            prefs[keyPeerVersions] = gson.toJson(updated)
        }
    }

    suspend fun setPublishPending(pending: Boolean) {
        context.syncMetadataStore.edit { prefs ->
            prefs[keyPublishPending] = pending
        }
    }
}
