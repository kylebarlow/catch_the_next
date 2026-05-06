package dev.catchthenext.android.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FavoritesSyncConflictTest {

    private fun meta(
        ownVersion: Long = 1L,
        ownUpdatedAt: Long = 1_000L,
        peerVersions: Map<String, Long> = emptyMap(),
        peerTimestamps: Map<String, Long> = emptyMap(),
        publishPending: Boolean = false,
    ) = SyncMetadata(
        ownVersion = ownVersion,
        ownUpdatedAt = ownUpdatedAt,
        peerVersions = peerVersions,
        peerTimestamps = peerTimestamps,
        publishPending = publishPending,
    )

    @Test
    fun `new peer version is always accepted`() {
        val m = meta(peerVersions = mapOf("peer1" to 5L), peerTimestamps = mapOf("peer1" to 500L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 6L, remoteUpdatedAt = 600L))
    }

    @Test
    fun `same peer version with newer wallclock is accepted as tiebreak`() {
        val m = meta(peerVersions = mapOf("peer1" to 5L), peerTimestamps = mapOf("peer1" to 500L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 5L, remoteUpdatedAt = 600L))
    }

    @Test
    fun `same peer version with same wallclock is rejected`() {
        val m = meta(peerVersions = mapOf("peer1" to 5L), peerTimestamps = mapOf("peer1" to 1_000L))
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 5L, remoteUpdatedAt = 1_000L))
    }

    @Test
    fun `same peer version already seen is not re-applied on migration`() {
        // After schema migration peerTimestamps is absent for existing peers. Receiving the same
        // version we already applied must not overwrite local state.
        val m = meta(peerVersions = mapOf("peer1" to 64L), peerTimestamps = emptyMap())
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 64L, remoteUpdatedAt = 9_999_999L))
    }

    @Test
    fun `older peer version with equal timestamp is rejected`() {
        val m = meta(peerVersions = mapOf("peer1" to 10L), peerTimestamps = mapOf("peer1" to 9_999L))
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 9L, remoteUpdatedAt = 9_999L))
    }

    @Test
    fun `unknown peer starts from zero - any version is accepted`() {
        val m = meta(peerVersions = emptyMap())
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "newPeer", remoteVersion = 1L, remoteUpdatedAt = 500L))
    }

    @Test
    fun `clock skew backward does not stall sync - logical version still accepted`() {
        // Peer's clock went backward: its updatedAt is less than our last-seen peer timestamp.
        // But the logical version is higher, so it must still be accepted.
        val m = meta(peerVersions = mapOf("peer1" to 3L), peerTimestamps = mapOf("peer1" to 5_000L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 4L, remoteUpdatedAt = 1L))
    }

    @Test
    fun `republish handshake - same version bumped wallclock accepted`() {
        // Peer re-published with the same logical version but a fresh updatedAt (republish handshake).
        val m = meta(peerVersions = mapOf("peer1" to 7L), peerTimestamps = mapOf("peer1" to 500L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 7L, remoteUpdatedAt = 600L))
    }

    @Test
    fun `version counter reset detected via newer timestamp`() {
        // Peer reinstalled: version counter reset from 64 back to 1, but wall-clock moved forward.
        // The newer timestamp must override the stale version comparison so sync resumes.
        val m = meta(peerVersions = mapOf("peer1" to 64L), peerTimestamps = mapOf("peer1" to 1_000L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 1L, remoteUpdatedAt = 2_000L))
    }

    @Test
    fun `version counter reset on migration detected via newer timestamp`() {
        // Same as above but peerTimestamps is absent (pre-migration state). Counter reset must
        // still be detected because remoteVersion < lastApplied and any real timestamp beats 0.
        val m = meta(peerVersions = mapOf("peer1" to 64L), peerTimestamps = emptyMap())
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peer1", remoteVersion = 35L, remoteUpdatedAt = 1_778_000_000_000L))
    }

    @Test
    fun `multiple peers are tracked independently`() {
        val m = meta(
            peerVersions = mapOf("peerA" to 10L, "peerB" to 3L),
            peerTimestamps = mapOf("peerA" to 9_999L, "peerB" to 1L),
        )
        assertFalse(FavoritesSyncListener.shouldApplyRemote(m, "peerA", remoteVersion = 9L, remoteUpdatedAt = 9_999L))
        assertTrue(FavoritesSyncListener.shouldApplyRemote(m, "peerB", remoteVersion = 4L, remoteUpdatedAt = 1L))
    }
}
