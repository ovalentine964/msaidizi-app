package com.msaidizi.agent.guardrails

import timber.log.Timber
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AuditTrailManager — Pillar 5: Audit Trail.
 *
 * Implements immutable, hash-chained audit logs with Merkle tree tamper-evidence.
 *
 * Properties:
 * - Every log entry includes the hash of the previous entry (hash chain)
 * - Merkle tree roots are computed periodically for tamper detection
 * - Financial records retained for 7 years (2,555 days)
 * - All safety-critical events are logged (auth, transactions, guardrail blocks)
 * - Log entries are append-only (no modification, no deletion)
 *
 * Tamper Detection:
 * - Hash chain: modifying any entry breaks all subsequent hashes
 * - Merkle tree: efficient batch verification of log integrity
 * - Periodic root publication for external audit
 */
@Singleton
class AuditTrailManager @Inject constructor() {

    private val entries = mutableListOf<AuditEntry>()
    private val merkleRoots = mutableListOf<MerkleRoot>()
    private var lastMerkleComputation = 0L

    // ─── Core Logging ───

    /**
     * Append an immutable audit entry.
     * Each entry is hash-chained to the previous entry.
     */
    fun log(
        eventType: AuditEventType,
        actor: String,
        action: String,
        resource: String? = null,
        details: Map<String, String> = emptyMap(),
        severity: AuditSeverity = AuditSeverity.INFO
    ): AuditEntry {
        val previousHash = entries.lastOrNull()?.entryHash ?: GENESIS_HASH
        val timestamp = Instant.now().epochSecond
        val sequenceNumber = entries.size.toLong()

        val entry = AuditEntry(
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            eventType = eventType,
            actor = actor,
            action = action,
            resource = resource,
            details = details,
            severity = severity,
            previousHash = previousHash,
            entryHash = "" // Computed below
        )

        // Compute entry hash (includes all fields except entryHash itself)
        val entryWithHash = entry.copy(entryHash = computeEntryHash(entry))
        entries.add(entryWithHash)

        // Auto-compute Merkle root every MERKLE_INTERVAL entries
        if (entries.size % MERKLE_INTERVAL == 0) {
            computeMerkleRoot()
        }

        Timber.d("Audit: [${eventType.name}] $actor → $action (${severity.name})")
        return entryWithHash
    }

    /**
     * Log a financial transaction event.
     */
    fun logFinancialTransaction(
        actor: String,
        transactionType: String,
        amount: Double,
        sourceId: String?,
        result: String
    ): AuditEntry {
        return log(
            eventType = AuditEventType.FINANCIAL_TRANSACTION,
            actor = actor,
            action = "record_$transactionType",
            resource = "transaction",
            details = mapOf(
                "type" to transactionType,
                "amount" to amount.toString(),
                "source_id" to (sourceId ?: "none"),
                "result" to result
            ),
            severity = AuditSeverity.HIGH
        )
    }

    /**
     * Log an authentication event.
     */
    fun logAuth(actor: String, action: String, success: Boolean): AuditEntry {
        return log(
            eventType = AuditEventType.AUTHENTICATION,
            actor = actor,
            action = action,
            details = mapOf("success" to success.toString()),
            severity = if (success) AuditSeverity.INFO else AuditSeverity.HIGH
        )
    }

    /**
     * Log a guardrail/block event.
     */
    fun logGuardrailBlock(
        actor: String,
        reason: String,
        blockedContent: String? = null
    ): AuditEntry {
        return log(
            eventType = AuditEventType.GUARDRAIL_BLOCK,
            actor = actor,
            action = "blocked",
            resource = "guardrails",
            details = mapOf(
                "reason" to reason,
                "content_preview" to (blockedContent?.take(100) ?: "")
            ),
            severity = AuditSeverity.CRITICAL
        )
    }

    /**
     * Log a privacy event (data access, export, deletion).
     */
    fun logPrivacyEvent(
        actor: String,
        action: String,
        dataType: String,
        details: Map<String, String> = emptyMap()
    ): AuditEntry {
        return log(
            eventType = AuditEventType.PRIVACY_EVENT,
            actor = actor,
            action = action,
            resource = dataType,
            details = details,
            severity = AuditSeverity.HIGH
        )
    }

    // ─── Hash Chain Verification ───

    /**
     * Verify the integrity of the entire hash chain.
     * Returns true if all entries are properly chained.
     */
    fun verifyHashChain(): ChainVerificationResult {
        if (entries.isEmpty()) return ChainVerificationResult(valid = true, entriesChecked = 0)

        var previousHash = GENESIS_HASH
        for (entry in entries) {
            // Check previous hash link
            if (entry.previousHash != previousHash) {
                return ChainVerificationResult(
                    valid = false,
                    entriesChecked = entry.sequenceNumber.toInt(),
                    brokenAt = entry.sequenceNumber,
                    expectedPreviousHash = previousHash,
                    actualPreviousHash = entry.previousHash
                )
            }

            // Verify entry hash
            val expectedHash = computeEntryHash(entry.copy(entryHash = ""))
            if (entry.entryHash != expectedHash) {
                return ChainVerificationResult(
                    valid = false,
                    entriesChecked = entry.sequenceNumber.toInt(),
                    brokenAt = entry.sequenceNumber,
                    reason = "Entry hash mismatch"
                )
            }

            previousHash = entry.entryHash
        }

        return ChainVerificationResult(valid = true, entriesChecked = entries.size)
    }

    // ─── Merkle Tree ───

    /**
     * Compute a Merkle tree root over the current log entries.
     * Used for efficient batch tamper detection.
     */
    fun computeMerkleRoot(): MerkleRoot {
        if (entries.isEmpty()) {
            return MerkleRoot(
                rootHash = GENESIS_HASH,
                entryCount = 0,
                timestamp = Instant.now().epochSecond
            )
        }

        // Compute leaf hashes
        var currentLevel = entries.map { it.entryHash }

        // Build tree bottom-up
        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<String>()
            for (i in currentLevel.indices step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                nextLevel.add(hashPair(left, right))
            }
            currentLevel = nextLevel
        }

        val root = MerkleRoot(
            rootHash = currentLevel.first(),
            entryCount = entries.size,
            timestamp = Instant.now().epochSecond,
            startSequence = entries.first().sequenceNumber,
            endSequence = entries.last().sequenceNumber
        )
        merkleRoots.add(root)
        lastMerkleComputation = Instant.now().epochSecond

        Timber.d("Merkle root computed: ${root.rootHash.take(16)}... (${root.entryCount} entries)")
        return root
    }

    /**
     * Generate a Merkle proof for a specific entry.
     * Allows external verification of a single entry's inclusion.
     */
    fun generateMerkleProof(sequenceNumber: Long): MerkleProof? {
        val index = sequenceNumber.toInt()
        if (index < 0 || index >= entries.size) return null

        val proof = mutableListOf<String>()
        var currentLevel = entries.map { it.entryHash }
        var idx = index

        while (currentLevel.size > 1) {
            val isRight = idx % 2 == 1
            val siblingIdx = if (isRight) idx - 1 else idx + 1

            if (siblingIdx < currentLevel.size) {
                proof.add(currentLevel[siblingIdx])
            } else {
                proof.add(currentLevel[idx]) // Duplicate for odd count
            }

            // Move up
            val nextLevel = mutableListOf<String>()
            for (i in currentLevel.indices step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                nextLevel.add(hashPair(left, right))
            }
            currentLevel = nextLevel
            idx /= 2
        }

        return MerkleProof(
            entryHash = entries[index].entryHash,
            proofPath = proof,
            rootHash = currentLevel.first(),
            entryIndex = index
        )
    }

    /**
     * Verify a Merkle proof.
     */
    fun verifyMerkleProof(proof: MerkleProof): Boolean {
        var current = proof.entryHash
        var idx = proof.entryIndex

        for (sibling in proof.proofPath) {
            current = if (idx % 2 == 0) hashPair(current, sibling) else hashPair(sibling, current)
            idx /= 2
        }

        return current == proof.rootHash
    }

    // ─── Retention & Cleanup ───

    /**
     * Get entries that have exceeded the 7-year retention period.
     * Financial records: 7 years (2,555 days)
     * Other records: 1 year (365 days)
     */
    fun getExpiredEntries(): List<AuditEntry> {
        val now = Instant.now().epochSecond
        return entries.filter { entry ->
            val maxAge = when (entry.eventType) {
                AuditEventType.FINANCIAL_TRANSACTION -> FINANCIAL_RETENTION_SECONDS
                AuditEventType.AUTHENTICATION -> GENERAL_RETENTION_SECONDS
                AuditEventType.GUARDRAIL_BLOCK -> GENERAL_RETENTION_SECONDS
                AuditEventType.PRIVACY_EVENT -> FINANCIAL_RETENTION_SECONDS // Privacy records = 7 years
                AuditEventType.SYSTEM_EVENT -> GENERAL_RETENTION_SECONDS
            }
            (now - entry.timestamp) > maxAge
        }
    }

    /**
     * Get the current audit log size.
     */
    fun getLogSize(): Int = entries.size

    /**
     * Get the latest Merkle root.
     */
    fun getLatestMerkleRoot(): MerkleRoot? = merkleRoots.lastOrNull()

    /**
     * Get all Merkle roots (for external audit publication).
     */
    fun getMerkleRoots(): List<MerkleRoot> = merkleRoots.toList()

    /**
     * Query entries by criteria.
     */
    fun queryEntries(
        eventType: AuditEventType? = null,
        actor: String? = null,
        since: Long? = null,
        until: Long? = null,
        limit: Int = 100
    ): List<AuditEntry> {
        return entries
            .filter { entry ->
                (eventType == null || entry.eventType == eventType) &&
                (actor == null || entry.actor == actor) &&
                (since == null || entry.timestamp >= since) &&
                (until == null || entry.timestamp <= until)
            }
            .takeLast(limit)
    }

    // ─── Hash Helpers ───

    private fun computeEntryHash(entry: AuditEntry): String {
        val input = buildString {
            append(entry.sequenceNumber)
            append("|")
            append(entry.timestamp)
            append("|")
            append(entry.eventType.name)
            append("|")
            append(entry.actor)
            append("|")
            append(entry.action)
            append("|")
            append(entry.resource ?: "")
            append("|")
            append(entry.details.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
            append("|")
            append(entry.severity.name)
            append("|")
            append(entry.previousHash)
        }
        return sha256(input)
    }

    private fun hashPair(left: String, right: String): String {
        return sha256("$left|$right")
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val MERKLE_INTERVAL = 100 // Compute Merkle root every 100 entries
        private const val FINANCIAL_RETENTION_SECONDS = 7L * 365 * 24 * 3600 // 7 years
        private const val GENERAL_RETENTION_SECONDS = 365L * 24 * 3600 // 1 year
    }
}

// ─── Data Classes ───

enum class AuditEventType {
    FINANCIAL_TRANSACTION,
    AUTHENTICATION,
    GUARDRAIL_BLOCK,
    PRIVACY_EVENT,
    SYSTEM_EVENT
}

enum class AuditSeverity {
    INFO,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class AuditEntry(
    val sequenceNumber: Long,
    val timestamp: Long,
    val eventType: AuditEventType,
    val actor: String,
    val action: String,
    val resource: String?,
    val details: Map<String, String>,
    val severity: AuditSeverity,
    val previousHash: String,
    val entryHash: String
)

data class MerkleRoot(
    val rootHash: String,
    val entryCount: Int,
    val timestamp: Long,
    val startSequence: Long = 0,
    val endSequence: Long = 0
)

data class MerkleProof(
    val entryHash: String,
    val proofPath: List<String>,
    val rootHash: String,
    val entryIndex: Int
)

data class ChainVerificationResult(
    val valid: Boolean,
    val entriesChecked: Int,
    val brokenAt: Long? = null,
    val expectedPreviousHash: String? = null,
    val actualPreviousHash: String? = null,
    val reason: String? = null
)
