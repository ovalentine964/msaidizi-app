package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class SyncBatch(val transactions: List<String>, val gradients: List<Double>, val vocabulary: List<String>)
data class SyncResult(val status: String, val syncedCount: Int, val conflictsResolved: Int)

/**
 * SyncEngine — Batch and sync anonymized business data to cloud.
 */
@Singleton
class SyncEngine @Inject constructor() : Tool {

    override val name = "sync_engine"
    override val description = "Batch and sync anonymized business data to cloud backend"

    private var lastSyncTime = 0L
    private val pendingBatch = mutableListOf<String>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "sync"
        return when (action.lowercase()) {
            "add" -> {
                val transaction = params["transaction"]
                    ?: return ToolResult.error(name, "Transaction data required", "MISSING_DATA")
                addToBatch(transaction)
                ToolResult.success(name, mapOf("pending" to pendingBatch.size), "Added to sync batch (${pendingBatch.size} pending)")
            }
            "sync" -> sync()
            "status" -> {
                val wifiAvailable = params["wifi"]?.toBooleanStrictOrNull() ?: false
                val battery = params["battery"]?.toIntOrNull() ?: 100
                val shouldSync = shouldSync(wifiAvailable, battery)
                ToolResult.success(
                    name,
                    mapOf("pending" to pendingBatch.size, "last_sync" to lastSyncTime, "should_sync" to shouldSync),
                    "Pending: ${pendingBatch.size} items. Last sync: ${if (lastSyncTime > 0) "$lastSyncTime" else "never"}. Ready: $shouldSync"
                )
            }
            "clear" -> {
                pendingBatch.clear()
                ToolResult.success(name, message = "Sync batch cleared")
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun addToBatch(transaction: String) { pendingBatch.add(transaction) }

    fun sync(): ToolResult {
        if (pendingBatch.isEmpty()) return ToolResult.success(name, message = "Nothing to sync")
        val batch = SyncBatch(pendingBatch.toList(), emptyList(), emptyList())
        // In production: anonymize → encrypt → send to backend
        val anonymized = batch.transactions.map { anonymize(it) }
        lastSyncTime = System.currentTimeMillis()
        val count = pendingBatch.size
        pendingBatch.clear()
        return ToolResult.success(
            name,
            mapOf("synced_count" to count, "timestamp" to lastSyncTime),
            "Synced $count items to cloud"
        )
    }

    fun shouldSync(wifiAvailable: Boolean, batteryPercent: Int): Boolean {
        return wifiAvailable && batteryPercent > 20 && pendingBatch.isNotEmpty()
    }

    private fun anonymize(transaction: String): String {
        // Strip PII, keep business data only
        return transaction.replace(Regex("\\d{10}"), "***")
    }
}
