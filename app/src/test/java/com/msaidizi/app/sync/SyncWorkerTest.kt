package com.msaidizi.app.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SyncWorker input/output keys and contract.
 *
 * Full integration tests require Android context (WorkManager, Hilt).
 * These tests verify the data contract and constants.
 */
class SyncWorkerTest {

    @Test
    fun `input key constants are defined`() {
        assertEquals("force_sync", SyncWorker.KEY_FORCE_SYNC)
    }

    @Test
    fun `output key constants are defined`() {
        assertEquals("synced_count", SyncWorker.KEY_SYNCED_COUNT)
        assertEquals("error_message", SyncWorker.KEY_ERROR_MESSAGE)
    }

    @Test
    fun `SyncState enum has all expected values`() {
        val states = SyncState.values()
        assertEquals(4, states.size)
        assertNotNull(SyncState.IDLE)
        assertNotNull(SyncState.SYNCING)
        assertNotNull(SyncState.SUCCESS)
        assertNotNull(SyncState.ERROR)
    }

    @Test
    fun `SyncStatus enum has all expected values`() {
        val statuses = SyncStatus.values()
        assertEquals(4, statuses.size)
        assertNotNull(SyncStatus.SUCCESS)
        assertNotNull(SyncStatus.NO_NETWORK)
        assertNotNull(SyncStatus.ALREADY_IN_PROGRESS)
        assertNotNull(SyncStatus.ERROR)
    }

    @Test
    fun `NetworkState enum has expected values`() {
        val states = NetworkState.values()
        assertEquals(2, states.size)
        assertNotNull(NetworkState.CONNECTED)
        assertNotNull(NetworkState.DISCONNECTED)
    }

    @Test
    fun `ConnectionType enum has expected values`() {
        val types = ConnectionType.values()
        assertEquals(5, types.size)
        assertNotNull(ConnectionType.NONE)
        assertNotNull(ConnectionType.WIFI)
        assertNotNull(ConnectionType.CELLULAR)
        assertNotNull(ConnectionType.ETHERNET)
        assertNotNull(ConnectionType.OTHER)
    }
}
