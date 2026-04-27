package dev.catchthenext.android.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FavoritesSyncConflictTest {

    private fun meta(
        ownVersion: Long = 1L,
        ownUpdatedAt: Long = 1_000L,
        peerVersions: Map<String, Long> = emptyMap(),
        publishPending: Boolean = false,
    ) = SyncMetadata(
        ownVersion = ownVersion,
        ownUpdatedAt = ownUpdatedAt,
        peerVersions = peerVersions,
        publishPending = publishPending,
    )

    @Test
    fun `new peer version is always accepted`() {
        val m = meta(peerVersions = mapOf("peer1" to 5L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 6L, remoteUpdatedAt = 1_001L))
    }

    @Test
    fun `same peer version with newer wallclock is accepted as tiebreak`() {
        val m = meta(ownUpdatedAt = 1_000L, peerVersions = mapOf("peer1" to 5L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 5L, remoteUpdatedAt = 2_000L))
    }

    @Test
    fun `same peer version with same wallclock is rejected`() {
        val m = meta(ownUpdatedAt = 1_000L, peerVersions = mapOf("peer1" to 5L))
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 5L, remoteUpdatedAt = 1_000L))
    }

    @Test
    fun `older peer version is rejected`() {
        val m = meta(peerVersions = mapOf("peer1" to 10L))
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 9L, remoteUpdatedAt = 9_999L))
    }

    @Test
    fun `unknown peer starts from zero - any version is accepted`() {
        val m = meta(peerVersions = emptyMap())
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "newPeer", remoteVersion = 1L, remoteUpdatedAt = 500L))
    }

    @Test
    fun `clock skew backward does not stall sync - logical version still accepted`() {
        // Peer's clock went backward: its updatedAt is LESS than our ownUpdatedAt.
        // But the logical version is higher, so it must still be accepted.
        val m = meta(ownUpdatedAt = 9_999L, peerVersions = mapOf("peer1" to 3L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 4L, remoteUpdatedAt = 1L))
    }

    @Test
    fun `republish handshake - same version bumped wallclock accepted`() {
        // Peer re-published with the same logical version but a fresh updatedAt (republish handshake).
        val m = meta(ownUpdatedAt = 500L, peerVersions = mapOf("peer1" to 7L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 7L, remoteUpdatedAt = 600L))
    }

    @Test
    fun `multiple peers are tracked independently`() {
        val m = meta(peerVersions = mapOf("peerA" to 10L, "peerB" to 3L))
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peerA", remoteVersion = 9L, remoteUpdatedAt = 9_999L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peerB", remoteVersion = 4L, remoteUpdatedAt = 1L))
    }
}
