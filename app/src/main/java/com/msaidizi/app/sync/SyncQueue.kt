package com.msaidizi.app.sync

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber


/**
 * Sync queue — stores data locally until it can be uploaded.
 * Uses SQLite as the queue backend (no separate queue needed).
 */
class SyncQueue(
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get all unsynced transactions.
     */
    suspend fun getUnsyncedTransactions(): List<Transaction> {
        return transactionDao.getUnsyncedTransactions()
    }

    /**
     * Get count of unsynced transactions.
     */
    suspend fun getUnsyncedCount(): Int {
        return transactionDao.getUnsyncedTransactions().size
    }

    /**
     * Mark transactions as synced.
     */
    suspend fun markAsSynced(transactionIds: List<Long>) {
        val syncTime = System.currentTimeMillis() / 1000
        transactionDao.markAsSynced(transactionIds, syncTime)
        Timber.d("Marked ${transactionIds.size} transactions as synced")
    }

    /**
     * Prepare sync payload.
     * Serializes transactions to JSON for upload.
     */
    suspend fun prepareSyncPayload(): SyncPayload {
        val transactions = getUnsyncedTransactions()

        return SyncPayload(
            transactions = transactions,
            timestamp = System.currentTimeMillis(),
            deviceInfo = getDeviceInfo()
        )
    }

    /**
     * Get device info for sync metadata.
     */
    private fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to android.os.Build.MODEL,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            "appVersion" to "1.0.0"
        )
    }
}

/**
 * Sync payload data class.
 */
@kotlinx.serialization.Serializable
data class SyncPayload(
    val transactions: List<Transaction>,
    val timestamp: Long,
    val deviceInfo: Map<String, String>
)
