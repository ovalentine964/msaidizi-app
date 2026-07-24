package com.msaidizi.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.msaidizi.core.common.error.ErrorCategory
import com.msaidizi.core.common.error.ErrorCompactor
import com.msaidizi.core.common.error.Result
import com.msaidizi.core.database.dao.ProofPointDao
import com.msaidizi.core.database.dao.TransactionDao
import com.msaidizi.core.database.dao.WorkerProfileDao
import com.msaidizi.data.api.BackendProofPoint
import com.msaidizi.data.api.BackendSyncBatch
import com.msaidizi.data.api.BackendTransactionPayload
import com.msaidizi.data.api.BackendWorkerProfile
import com.msaidizi.data.api.DeviceInfo
import com.msaidizi.data.api.MsaidiziApi
import com.msaidizi.data.api.SyncResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Offline-first sync manager for Msaidizi.
 *
 * Implements the M-KOPA data contract: the app collects data,
 * the backend creates intelligence. Sync is batched, compressed,
 * and happens automatically when internet is available.
 *
 * ## Sync Strategy
 * 1. Transactions saved to Room DB immediately (offline-first)
 * 2. Proof points queued in sync queue
 * 3. When online: batch into BackendSyncBatch (max 100 items)
 * 4. Compress payload (gzip)
 * 5. Send to backend via API
 * 6. Receive updated Alama Score
 * 7. Mark items as synced in Room DB
 *
 * ## Conflict Resolution
 * - Backend is authoritative for Alama Score
 * - App is authoritative for transaction data
 * - Last-write-wins for worker profile
 */
class BiasharaSync(
    private val context: Context,
    private val api: MsaidiziApi,
    private val transactionDao: TransactionDao,
    private val proofPointDao: ProofPointDao,
    private val workerProfileDao: WorkerProfileDao
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private var isNetworkAvailable = false
    private var syncJob: kotlinx.coroutines.Job? = null

    /**
     * Initialize network monitoring.
     * Starts listening for network changes to trigger sync.
     */
    fun initialize() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkAvailable = true
                triggerSync()
            }

            override fun onLost(network: Network) {
                isNetworkAvailable = false
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    /**
     * Trigger a sync if conditions are met.
     * Safe to call frequently — won't start a new sync if one is in progress.
     */
    fun triggerSync() {
        if (syncJob?.isActive == true) return
        if (!isNetworkAvailable) return

        syncJob = scope.launch {
            performSync()
        }
    }

    /**
     * Perform the actual sync operation.
     */
    private suspend fun performSync() {
        _syncState.value = SyncState.Syncing

        try {
            // Get unsynced transactions
            val unsyncedTransactions = transactionDao.getUnsynced(limit = BATCH_SIZE)
            if (unsyncedTransactions.isEmpty()) {
                _syncState.value = SyncState.Idle
                return
            }

            // Get unsynced proof points
            val unsyncedProofs = proofPointDao.getUnsynced(limit = BATCH_SIZE)

            // Build sync batch
            val batchId = UUID.randomUUID().toString()
            val workerProfile = workerProfileDao.getProfile()

            val transactionPayloads = unsyncedTransactions.map { tx ->
                BackendTransactionPayload(
                    workerId = workerProfile?.id?.toString() ?: "unknown",
                    transactionId = tx.backendTransactionId.ifBlank { tx.id.toString() },
                    type = tx.type,
                    item = tx.item,
                    category = tx.category,
                    subcategory = tx.subcategory,
                    quantity = tx.quantity,
                    unit = tx.unit,
                    unitPrice = tx.unitPrice,
                    totalAmount = tx.totalAmount,
                    costBasis = tx.costBasis,
                    margin = tx.margin,
                    marginPercent = tx.marginPercent,
                    paymentMethod = tx.paymentMethod,
                    mpesaCode = tx.mpesaCode.ifBlank { null },
                    locationLat = tx.locationLat,
                    locationLng = tx.locationLng,
                    locationName = tx.locationName,
                    timestamp = tx.createdAt,
                    timeOfDay = tx.timeOfDay,
                    dayOfWeek = tx.dayOfWeek,
                    confidence = tx.confidence,
                    verificationSource = tx.verificationSource,
                    dataCompleteness = tx.confidence // Use confidence as proxy
                )
            }

            val proofPayloads = unsyncedProofs.map { proof ->
                BackendProofPoint(
                    proofType = proof.type,
                    weight = proof.weight,
                    timestamp = proof.timestamp,
                    data = emptyMap() // Simplified — full data in dataJson field
                )
            }

            val batch = BackendSyncBatch(
                batchId = batchId,
                workerId = workerProfile?.id?.toString() ?: "unknown",
                transactions = transactionPayloads,
                proofPoints = proofPayloads,
                workerProfile = workerProfile?.let {
                    BackendWorkerProfile(
                        workerId = it.id.toString(),
                        name = it.name,
                        businessType = it.businessType,
                        businessCategory = it.businessCategory,
                        locationName = it.locationName,
                        region = it.region,
                        language = it.language,
                        dialect = it.dialect,
                        avgDailyRevenue = it.avgDailyRevenue,
                        daysActive = it.daysActive,
                        alamaScore = it.alamaScore,
                        alamaTier = it.alamaTier
                    )
                },
                deviceInfo = getDeviceInfo(),
                syncTimestamp = System.currentTimeMillis()
            )

            // Send to backend
            val token = "Bearer ${getAuthToken()}"
            val response = api.syncBatch(token, batch)

            if (response.isSuccessful) {
                val syncResponse = response.body()!!

                // Mark transactions as synced
                val syncedIds = unsyncedTransactions.map { it.id }
                transactionDao.markBatchSynced(
                    ids = syncedIds,
                    syncedAt = System.currentTimeMillis(),
                    batchId = batchId
                )

                // Mark proof points as synced
                if (unsyncedProofs.isNotEmpty()) {
                    val proofIds = unsyncedProofs.map { it.id }
                    proofPointDao.markSynced(proofIds, System.currentTimeMillis())
                }

                // Update Alama Score if returned
                syncResponse.alamaScore?.let { alama ->
                    workerProfile?.let { profile ->
                        workerProfileDao.updateAlamaScore(
                            id = profile.id,
                            score = alama.score,
                            tier = alama.tier,
                            proofPoints = alama.totalProofPoints
                        )
                    }
                }

                _syncState.value = SyncState.Synced(
                    transactionCount = unsyncedTransactions.size,
                    proofCount = unsyncedProofs.size,
                    alamaScore = syncResponse.alamaScore?.score
                )
            } else {
                _syncState.value = SyncState.Error("Sync failed: ${response.code()}")
            }
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(
                ErrorCompactor.compact(e, ErrorCategory.NETWORK).message
            )
        }
    }

    /**
     * Get current device info for sync payload.
     */
    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceId = android.os.Build.SERIAL ?: "unknown",
            osVersion = android.os.Build.VERSION.RELEASE,
            appVersion = "0.1.0", // TODO: Get from BuildConfig
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        )
    }

    /**
     * Get auth token from encrypted storage.
     * TODO: Integrate with EncryptedStorage
     */
    private fun getAuthToken(): String {
        // Placeholder — will be integrated with EncryptedStorage
        return ""
    }

    companion object {
        private const val BATCH_SIZE = 100
    }
}

/**
 * Sync state for UI observation.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Synced(
        val transactionCount: Int,
        val proofCount: Int,
        val alamaScore: Double?
    ) : SyncState()
    data class Error(val message: String) : SyncState()
}
