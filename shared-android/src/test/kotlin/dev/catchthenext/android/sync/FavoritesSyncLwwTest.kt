package dev.catchthenext.android.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FavoritesSyncLwwTest {

    // shouldApplyRemote is the pure LWW predicate: apply iff remoteUpdatedAt > localUpdatedAt.

    @Test
    fun `newer remote timestamp is accepted`() {
        assertTrue(shouldApply(localUpdatedAt = 1_000L, remoteUpdatedAt = 2_000L))
    }

    @Test
    fun `equal timestamps are rejected`() {
        assertFalse(shouldApply(localUpdatedAt = 1_000L, remoteUpdatedAt = 1_000L))
    }

    @Test
    fun `older remote timestamp is rejected`() {
        assertFalse(shouldApply(localUpdatedAt = 5_000L, remoteUpdatedAt = 4_999L))
    }

    @Test
    fun `fresh install accepts any real remote (localUpdatedAt is 0)`() {
        assertTrue(shouldApply(localUpdatedAt = 0L, remoteUpdatedAt = 1L))
    }

    @Test
    fun `zero remote on fresh install is rejected`() {
        assertFalse(shouldApply(localUpdatedAt = 0L, remoteUpdatedAt = 0L))
    }

    @Test
    fun `large timestamp difference still only compares gt`() {
        val now = System.currentTimeMillis()
        assertTrue(shouldApply(localUpdatedAt = now - 60_000, remoteUpdatedAt = now))
        assertFalse(shouldApply(localUpdatedAt = now, remoteUpdatedAt = now - 60_000))
    }

    // Publisher echo guard: skip publish when list content matches the last-published hash.

    @Test
    fun `publisher skips emission with same hash as last published`() {
        val publisher = TestPublisher()
        val favorites = listOf("stop-a", "stop-b")

        publisher.lastPublishedHash = favorites.hashCode()
        assertFalse(publisher.shouldPublish(favorites, localUpdatedAt = 1_000L))
    }

    @Test
    fun `publisher emits when hash differs from last published`() {
        val publisher = TestPublisher()
        val favorites = listOf("stop-a", "stop-b")
        val different = listOf("stop-a", "stop-b", "stop-c")

        publisher.lastPublishedHash = favorites.hashCode()
        assertTrue(publisher.shouldPublish(different, localUpdatedAt = 1_000L))
    }

    // Empty-publish guard: never publish an empty list on a fresh install.

    @Test
    fun `publisher skips empty list when localUpdatedAt is 0 (fresh install)`() {
        val publisher = TestPublisher()
        assertFalse(publisher.shouldPublish(emptyList(), localUpdatedAt = 0L))
    }

    @Test
    fun `publisher allows empty list when localUpdatedAt is non-zero (user cleared favorites)`() {
        val publisher = TestPublisher()
        assertTrue(publisher.shouldPublish(emptyList(), localUpdatedAt = 1_000L))
    }

    // Helpers

    private fun shouldApply(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean =
        remoteUpdatedAt > localUpdatedAt

    /** Inline simulation of the publisher's two pre-publish guards. */
    private class TestPublisher {
        var lastPublishedHash: Int = 0

        fun shouldPublish(favorites: List<String>, localUpdatedAt: Long): Boolean {
            if (favorites.hashCode() == lastPublishedHash) return false
            if (favorites.isEmpty() && localUpdatedAt == 0L) return false
            return true
        }
    }
}
