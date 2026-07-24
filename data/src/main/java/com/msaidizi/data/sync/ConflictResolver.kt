package com.msaidizi.data.sync

import com.msaidizi.core.database.entity.TransactionEntity

/**
 * Conflict resolver for sync operations.
 *
 * Handles data conflicts between local (app) and remote (backend) data.
 * Uses different strategies based on entity type:
 *
 * - Transactions: App is authoritative (local-first)
 * - Alama Score: Backend is authoritative (cloud-first)
 * - Worker Profile: Last-write-wins
 *
 * ## Conflict Scenarios
 * 1. Same transaction modified locally and on backend → App wins
 * 2. Alama Score mismatch → Backend wins
 * 3. Worker profile updated on both → Last-write-wins
 * 4. Deleted locally but exists on backend → Keep locally deleted
 */
class ConflictResolver {

    /**
     * Resolve transaction conflicts.
     * App is always authoritative for transaction data.
     */
    fun resolveTransactionConflict(
        local: TransactionEntity,
        remote: BackendTransactionPayload?
    ): ConflictResolution {
        // If no remote version, local wins (new transaction, not yet synced)
        if (remote == null) {
            return ConflictResolution.KeepLocal
        }

        // If local is newer, keep local
        if (local.createdAt > remote.timestamp) {
            return ConflictResolution.KeepLocal
        }

        // If remote is newer but local has been modified, keep local
        // (app is authoritative for transaction data)
        return ConflictResolution.KeepLocal
    }

    /**
     * Resolve Alama Score conflicts.
     * Backend is always authoritative.
     */
    fun resolveAlamaScoreConflict(
        localScore: Double,
        remoteScore: Double
    ): ConflictResolution {
        // Backend is authoritative for Alama Score
        return ConflictResolution.UseRemote
    }

    /**
     * Resolve worker profile conflicts.
     * Last-write-wins strategy.
     */
    fun resolveProfileConflict(
        localUpdatedAt: Long,
        remoteUpdatedAt: Long
    ): ConflictResolution {
        return if (localUpdatedAt >= remoteUpdatedAt) {
            ConflictResolution.KeepLocal
        } else {
            ConflictResolution.UseRemote
        }
    }

    /**
     * Merge conflicting transaction data.
     * Takes the most complete data from both sources.
     */
    fun mergeTransaction(
        local: TransactionEntity,
        remote: BackendTransactionPayload
    ): TransactionEntity {
        return local.copy(
            // Keep local data but fill in any missing fields from remote
            category = local.category.ifBlank { remote.category },
            subcategory = local.subcategory.ifBlank { remote.subcategory },
            locationName = local.locationName.ifBlank { remote.locationName },
            backendTransactionId = remote.transactionId,
            syncedAt = System.currentTimeMillis()
        )
    }

    /**
     * Handle batch conflicts.
     * Returns only the transactions that need to be synced.
     */
    fun filterSyncable(
        localTransactions: List<TransactionEntity>,
        remoteTransactions: List<BackendTransactionPayload>
    ): List<TransactionEntity> {
        val remoteMap = remoteTransactions.associateBy { it.transactionId }

        return localTransactions.filter { local ->
            val remote = remoteMap[local.backendTransactionId]
            when (resolveTransactionConflict(local, remote)) {
                ConflictResolution.KeepLocal -> true
                ConflictResolution.UseRemote -> false
                ConflictResolution.Merge -> true // Merge result is kept locally
            }
        }
    }
}

/**
 * Conflict resolution strategies.
 */
enum class ConflictResolution {
    /** Keep the local (app) version */
    KeepLocal,
    /** Use the remote (backend) version */
    UseRemote,
    /** Merge both versions (best of both) */
    Merge
}

/**
 * Import from api package for type reference.
 */
private typealias BackendTransactionPayload = com.msaidizi.data.api.BackendTransactionPayload
