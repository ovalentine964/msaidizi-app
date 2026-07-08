package com.msaidizi.app.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SyncableTransaction — Transaction entity with sync conflict resolution fields.
 *
 * Extends the base transaction entity with sync_version, device_id, and
 * last_modified_at fields required by SyncConflictResolver.
 *
 * @author Angavu Intelligence — Architecture Fix Team 4
 */
@Entity(tableName = "transactions")
data class SyncableTransaction(
    @PrimaryKey
    @ColumnInfo(name = "entity_id")
    override val entityId: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "worker_id")
    val workerId: String = "",

    @ColumnInfo(name = "amount")
    val amount: Double = 0.0,

    @ColumnInfo(name = "item")
    val item: String = "",

    @ColumnInfo(name = "category")
    val category: String = "",

    @ColumnInfo(name = "transaction_type")
    val transactionType: String = "sale", // "sale", "expense", "refund"

    @ColumnInfo(name = "payment_method")
    val paymentMethod: String = "cash", // "cash", "mpesa", "both"

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // ── Sync fields (FIX 4.2) ─────────────────────────────────
    @ColumnInfo(name = "sync_version")
    override val syncVersion: Long = 1,

    @ColumnInfo(name = "device_id")
    override val deviceId: String = "",

    @ColumnInfo(name = "last_modified_at")
    override val lastModifiedAt: Long = System.currentTimeMillis()
) : SyncableEntity {

    /**
     * Create a new version of this transaction with incremented sync version.
     * Used when updating the entity locally.
     */
    fun withUpdate(
        amount: Double? = null,
        item: String? = null,
        category: String? = null,
        notes: String? = null,
        deviceId: String = this.deviceId
    ): SyncableTransaction {
        return copy(
            amount = amount ?: this.amount,
            item = item ?: this.item,
            category = category ?: this.category,
            notes = notes ?: this.notes,
            syncVersion = this.syncVersion + 1,
            deviceId = deviceId,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
}


/**
 * SyncableGoal — Goal entity with sync conflict resolution.
 */
@Entity(tableName = "goals")
data class SyncableGoal(
    @PrimaryKey
    @ColumnInfo(name = "entity_id")
    override val entityId: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "worker_id")
    val workerId: String = "",

    @ColumnInfo(name = "goal_name")
    val goalName: String = "",

    @ColumnInfo(name = "target_amount")
    val targetAmount: Double = 0.0,

    @ColumnInfo(name = "current_amount")
    val currentAmount: Double = 0.0,

    @ColumnInfo(name = "deadline")
    val deadline: Long = 0L,

    @ColumnInfo(name = "status")
    val status: String = "active", // "active", "completed", "paused"

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // ── Sync fields ───────────────────────────────────────────
    @ColumnInfo(name = "sync_version")
    override val syncVersion: Long = 1,

    @ColumnInfo(name = "device_id")
    override val deviceId: String = "",

    @ColumnInfo(name = "last_modified_at")
    override val lastModifiedAt: Long = System.currentTimeMillis()
) : SyncableEntity {

    fun withContribution(amount: Double, deviceId: String = this.deviceId): SyncableGoal {
        return copy(
            currentAmount = this.currentAmount + amount,
            status = if (this.currentAmount + amount >= this.targetAmount) "completed" else this.status,
            syncVersion = this.syncVersion + 1,
            deviceId = deviceId,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
}


/**
 * SyncableInventory — Inventory entity with sync conflict resolution.
 */
@Entity(tableName = "inventory")
data class SyncableInventory(
    @PrimaryKey
    @ColumnInfo(name = "entity_id")
    override val entityId: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "worker_id")
    val workerId: String = "",

    @ColumnInfo(name = "item_name")
    val itemName: String = "",

    @ColumnInfo(name = "quantity")
    val quantity: Int = 0,

    @ColumnInfo(name = "unit_cost")
    val unitCost: Double = 0.0,

    @ColumnInfo(name = "selling_price")
    val sellingPrice: Double = 0.0,

    @ColumnInfo(name = "reorder_level")
    val reorderLevel: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // ── Sync fields ───────────────────────────────────────────
    @ColumnInfo(name = "sync_version")
    override val syncVersion: Long = 1,

    @ColumnInfo(name = "device_id")
    override val deviceId: String = "",

    @ColumnInfo(name = "last_modified_at")
    override val lastModifiedAt: Long = System.currentTimeMillis()
) : SyncableEntity {

    fun withQuantityChange(delta: Int, deviceId: String = this.deviceId): SyncableInventory {
        return copy(
            quantity = (this.quantity + delta).coerceAtLeast(0),
            syncVersion = this.syncVersion + 1,
            deviceId = deviceId,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
}
