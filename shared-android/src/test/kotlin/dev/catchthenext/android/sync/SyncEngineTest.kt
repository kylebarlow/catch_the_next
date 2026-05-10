package dev.catchthenext.android.sync

import dev.catchthenext.model.Stop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val STOP_A = Stop(1L, "s1", "Stop A", 0.0, 0.0, onestopId = "stop-a")
private val STOP_B = Stop(2L, "s2", "Stop B", 0.0, 0.0, onestopId = "stop-b")

private fun entry(stop: Stop? = null, author: String = "phone", counter: Long = 1L, tombstone: Boolean = false, tombstoneAt: Long = 0L) =
    FavoriteEntry(stop = stop, authorNodeId = author, authorCounter = counter, tombstone = tombstone, tombstoneAt = tombstoneAt)

private fun emptyState(nodeId: String = "phone") = SyncState(myNodeId = nodeId, myCounter = 0L, items = emptyMap())

class SyncEngineTest {

    @Test fun `remote item not in local is added`() {
        val remote = mapOf("stop-a" to entry(STOP_A, author = "watch"))
        val result = SyncEngine.merge(emptyState(), remote, now = 0L)
        assertFalse(result.items["stop-a"]!!.tombstone)
        assertEquals(STOP_A, result.items["stop-a"]!!.stop)
    }

    @Test fun `higher counter wins regardless of author`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "phone", counter = 5L)))
        val remote = mapOf("stop-a" to entry(STOP_A, author = "watch", counter = 10L))
        val result = SyncEngine.merge(local, remote, now = 0L)
        assertEquals("watch", result.items["stop-a"]!!.authorNodeId)
        assertEquals(10L, result.items["stop-a"]!!.authorCounter)
    }

    @Test fun `lower counter loses to local`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "phone", counter = 10L)))
        val remote = mapOf("stop-a" to entry(STOP_A, author = "watch", counter = 5L))
        val result = SyncEngine.merge(local, remote, now = 0L)
        assertEquals("phone", result.items["stop-a"]!!.authorNodeId)
        assertEquals(10L, result.items["stop-a"]!!.authorCounter)
    }

    @Test fun `equal counter tiebreaks by nodeId lexicographically`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "aaa", counter = 1L)))
        val remote = mapOf("stop-a" to entry(STOP_A, author = "zzz", counter = 1L))
        val result = SyncEngine.merge(local, remote, now = 0L)
        assertEquals("zzz", result.items["stop-a"]!!.authorNodeId)
    }

    @Test fun `concurrent adds on different items both survive`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "phone", counter = 1L)))
        val remote = mapOf("stop-b" to entry(STOP_B, author = "watch", counter = 1L))
        val result = SyncEngine.merge(local, remote, now = 0L)
        assertEquals(2, result.items.size)
        assertFalse(result.items["stop-a"]!!.tombstone)
        assertFalse(result.items["stop-b"]!!.tombstone)
    }

    @Test fun `tombstone with higher counter wins over live entry`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "phone", counter = 1L)))
        val remote = mapOf("stop-a" to entry(tombstone = true, author = "watch", counter = 2L, tombstoneAt = 1000L))
        val result = SyncEngine.merge(local, remote, now = 2000L)
        assertTrue(result.items["stop-a"]!!.tombstone)
    }

    @Test fun `live entry with higher counter wins over tombstone`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A, author = "phone", counter = 5L)))
        val remote = mapOf("stop-a" to entry(tombstone = true, author = "watch", counter = 3L, tombstoneAt = 1000L))
        val result = SyncEngine.merge(local, remote, now = 2000L)
        assertFalse(result.items["stop-a"]!!.tombstone)
    }

    @Test fun `tombstones older than 30 days are GCed`() {
        val thirtyOneDaysAgo = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000)
        val local = emptyState().copy(items = mapOf(
            "stop-a" to entry(tombstone = true, tombstoneAt = thirtyOneDaysAgo),
        ))
        val result = SyncEngine.merge(local, emptyMap(), now = System.currentTimeMillis())
        assertNull(result.items["stop-a"])
    }

    @Test fun `fresh tombstone is not GCed`() {
        val now = System.currentTimeMillis()
        val local = emptyState().copy(items = mapOf("stop-a" to entry(tombstone = true, tombstoneAt = now)))
        val result = SyncEngine.merge(local, emptyMap(), now = now)
        assertTrue(result.items.containsKey("stop-a"))
    }

    @Test fun `merging empty remote leaves local unchanged`() {
        val local = emptyState().copy(items = mapOf("stop-a" to entry(STOP_A)))
        val result = SyncEngine.merge(local, emptyMap(), now = 0L)
        assertEquals(local, result)
    }

    @Test fun `myNodeId and myCounter are preserved after merge`() {
        val local = SyncState(myNodeId = "phone", myCounter = 7L, items = emptyMap())
        val result = SyncEngine.merge(local, emptyMap(), now = 0L)
        assertEquals("phone", result.myNodeId)
        assertEquals(7L, result.myCounter)
    }
}
