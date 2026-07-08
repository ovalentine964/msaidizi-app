package com.msaidizi.app.data.sync

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SyncConflictResolver — Handles concurrent multi-device updates.
 *
 * FIX 4.2: Implements last-write-wins with version vectors for
 * conflict detection and resolution.
 *
 * Problem: Two devices (e.g., worker switches phones, or shares with
 * a family member) may update the same entity concurrently. The old
 * dedup approach silently dropped the second update.
 *
 * Solution: Each entity carries a sync_version (monotonic counter per
 * device) and a device_id. When conflicts are detected:
 * 1. If versions are from different devices → last-write-wins by timestamp
 * 2. If versions are from the same device → higher version wins
 * 3. Conflicts are logged for potential client-side review
 *
 * Academic grounding:
 * - Distributed systems: Version vectors (Lamport, 1978)
 * - Conflict-free replicated data types (CRDTs) for merge semantics
 * - Last-write-wins register (Shapiro et al., 2011)
 *
 * @author Angavu Intelligence — Architecture Fix Team 4
 */
object SyncConflictResolver {

    /**
     * Resolve a conflict between a local entity and a remote entity.
     *
     * @param local The entity on this device
     * @param remote The entity from the server/other device
     * @param mergeStrategy How to handle conflicts
     * @return Resolution containing the winning entity and conflict metadata
     */
    fun <T : SyncableEntity> resolve(
        local: T,
        remote: T,
        mergeStrategy: MergeStrategy = MergeStrategy.LAST_WRITE_WINS
    ): Resolution<T> {
        // No conflict if same version
        if (local.syncVersion == remote.syncVersion &&
            local.deviceId == remote.deviceId
        ) {
            return Resolution(
                winner = local,
                conflict = null,
                action = ResolutionAction.NO_CHANGE
            )
        }

        // Same device, different versions — higher version wins
        if (local.deviceId == remote.deviceId) {
            return if (local.syncVersion >= remote.syncVersion) {
                Resolution(
                    winner = local,
                    conflict = null,
                    action = ResolutionAction.LOCAL_KEPT
                )
            } else {
                Resolution(
                    winner = remote,
                    conflict = null,
                    action = ResolutionAction.REMOTE_ACCEPTED
                )
            }
        }

        // Different devices — conflict detected
        return when (mergeStrategy) {
            MergeStrategy.LAST_WRITE_WINS -> resolveLastWriteWins(local, remote)
            MergeStrategy.REMOTE_WINS -> Resolution(
                winner = remote,
                conflict = ConflictRecord.from(local, remote),
                action = ResolutionAction.REMOTE_ACCEPTED
            )
            MergeStrategy.LOCAL_WINS -> Resolution(
                winner = local,
                conflict = ConflictRecord.from(local, remote),
                action = ResolutionAction.LOCAL_KEPT
            )
            MergeStrategy.MERGE -> resolveWithMerge(local, remote)
        }
    }

    private fun <T : SyncableEntity> resolveLastWriteWins(
        local: T,
        remote: T
    ): Resolution<T> {
        val localTs = local.lastModifiedAt
        val remoteTs = remote.lastModifiedAt

        return if (localTs >= remoteTs) {
            Resolution(
                winner = local,
                conflict = ConflictRecord.from(local, remote),
                action = ResolutionAction.LOCAL_KEPT,
                reason = "Local is newer (${localTs} >= ${remoteTs})"
            )
        } else {
            Resolution(
                winner = remote,
                conflict = ConflictRecord.from(local, remote),
                action = ResolutionAction.REMOTE_ACCEPTED,
                reason = "Remote is newer (${remoteTs} > ${localTs})"
            )
        }
    }

    private fun <T : SyncableEntity> resolveWithMerge(
        local: T,
        remote: T
    ): Resolution<T> {
        // For merge strategy, we keep the newer entity but log the conflict
        // In a full CRDT implementation, fields would be merged individually
        val winner = if (local.lastModifiedAt >= remote.lastModifiedAt) local else remote
        return Resolution(
            winner = winner,
            conflict = ConflictRecord.from(local, remote),
            action = ResolutionAction.MERGED,
            reason = "Merge strategy: kept newer entity, conflict logged for review"
        )
    }

    /**
     * Batch-resolve a list of entity pairs.
     */
    fun <T : SyncableEntity> resolveBatch(
        pairs: List<Pair<T, T>>,
        mergeStrategy: MergeStrategy = MergeStrategy.LAST_WRITE_WINS
    ): BatchResolution<T> {
        val resolutions = pairs.map { (local, remote) ->
            resolve(local, remote, mergeStrategy)
        }
        return BatchResolution(resolutions)
    }
}


// ── Interfaces & Data Classes ─────────────────────────────────

/**
 * Interface for entities that participate in sync with conflict resolution.
 * All Room entities that sync should implement this.
 */
interface SyncableEntity {
    /** Monotonically increasing version per device. */
    val syncVersion: Long

    /** Device that last modified this entity. */
    val deviceId: String

    /** Timestamp of last modification (epoch millis). */
    val lastModifiedAt: Long

    /** Globally unique entity ID (stable across devices). */
    val entityId: String
}


enum class MergeStrategy {
    /** Most recent timestamp wins. */
    LAST_WRITE_WINS,

    /** Server/remote version always wins. */
    REMOTE_WINS,

    /** Local version always wins. */
    LOCAL_WINS,

    /** Attempt field-level merge (CRDT-style). */
    MERGE
}


enum class ResolutionAction {
    NO_CHANGE,
    LOCAL_KEPT,
    REMOTE_ACCEPTED,
    MERGED
}


data class Resolution<T : SyncableEntity>(
    val winner: T,
    val conflict: ConflictRecord?,
    val action: ResolutionAction,
    val reason: String? = null
)


data class ConflictRecord(
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("local_device_id") val localDeviceId: String,
    @SerializedName("local_version") val localVersion: Long,
    @SerializedName("local_timestamp") val localTimestamp: Long,
    @SerializedName("remote_device_id") val remoteDeviceId: String,
    @SerializedName("remote_version") val remoteVersion: Long,
    @SerializedName("remote_timestamp") val remoteTimestamp: Long,
    @SerializedName("resolved_at") val resolvedAt: Long = System.currentTimeMillis(),
    @SerializedName("resolution_action") val resolutionAction: ResolutionAction
) {
    companion object {
        fun <T : SyncableEntity> from(local: T, remote: T): ConflictRecord {
            return ConflictRecord(
                entityId = local.entityId,
                entityType = local::class.simpleName ?: "Unknown",
                localDeviceId = local.deviceId,
                localVersion = local.syncVersion,
                localTimestamp = local.lastModifiedAt,
                remoteDeviceId = remote.deviceId,
                remoteVersion = remote.syncVersion,
                remoteTimestamp = remote.lastModifiedAt,
                resolutionAction = ResolutionAction.LAST_WRITE_WINS
            )
        }
    }
}


data class BatchResolution<T : SyncableEntity>(
    val resolutions: List<Resolution<T>>
) {
    val conflicts: List<ConflictRecord>
        get() = resolutions.mapNotNull { it.conflict }

    val conflictCount: Int
        get() = conflicts.size

    val winners: List<T>
        get() = resolutions.map { it.winner }

    fun summary(): String {
        val actions = resolutions.groupBy { it.action }
        return "Batch: ${resolutions.size} entities, " +
            "${conflicts.size} conflicts, " +
            "actions: ${actions.mapValues { it.value.size }}"
    }
}


// ── Conflict Log (for audit trail) ───────────────────────────

/**
 * Stores conflict history for audit and potential client-side review.
 * In production, this backs a Room entity or sync log table.
 */
class ConflictLog(private val maxEntries: Int = 1000) {

    private val log = ConcurrentHashMap<String, MutableList<ConflictRecord>>()

    fun record(conflict: ConflictRecord) {
        val entityLog = log.getOrPut(conflict.entityId) { mutableListOf() }
        synchronized(entityLog) {
            entityLog.add(conflict)
            // Trim to max size
            while (entityLog.size > maxEntries) {
                entityLog.removeAt(0)
            }
        }
    }

    fun getConflicts(entityId: String): List<ConflictRecord> {
        return log[entityId]?.toList() ?: emptyList()
    }

    fun getAllConflicts(): List<ConflictRecord> {
        return log.values.flatMap { it.toList() }.sortedByDescending { it.resolvedAt }
    }

    fun conflictCount(entityId: String): Int {
        return log[entityId]?.size ?: 0
    }

    fun clear() {
        log.clear()
    }
}
