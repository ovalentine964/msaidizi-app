package com.msaidizi.app.voice.transfer

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Looper
import androidx.core.content.FileProvider
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles peer-to-peer model transfer via Bluetooth and WiFi Direct.
 *
 * Strategy:
 * - Bluetooth OPP: Universally supported, ~0.3 MB/s, for small models
 * - WiFi Direct P2P: Fast ~20 MB/s, for large models (Qwen 300MB)
 * - Files go to staging directory → SHA-256 verified → atomic move
 *
 * Transfer flow:
 * 1. Sender generates 6-digit transfer code
 * 2. Receiver enters code to pair
 * 3. File transfers over chosen channel
 * 4. Receiver verifies SHA-256
 * 5. Model installed to final location
 */
@Singleton
class ModelTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val BT_SERVICE_NAME = "MsaidiziModelTransfer"
        private val BT_UUID = java.util.UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val WIFI_DIRECT_PORT = 8765
        private const val BUFFER_SIZE = 8192
        private const val STAGING_DIR_NAME = "models_staging"
    }

    sealed class TransferState {
        object Idle : TransferState()
        data class Discovering(val devices: List<DeviceInfo>) : TransferState()
        data class Connecting(val deviceName: String) : TransferState()
        data class Transferring(val modelId: String, val percent: Int) : TransferState()
        data class Verifying(val modelId: String) : TransferState()
        data class Complete(val modelId: String) : TransferState()
        data class Error(val message: String) : TransferState()
        data class WaitingForReceiver(val code: String) : TransferState()
        data class Receiving(val modelId: String, val percent: Int) : TransferState()
    }

    data class DeviceInfo(
        val name: String,
        val address: String,
        val isWifiDirect: Boolean = false
    )

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState

    private val stagingDir = File(context.filesDir, STAGING_DIR_NAME).apply { mkdirs() }
    private var serverJob: Job? = null

    // ────────────── Transfer Code ──────────────

    /**
     * Generate a 6-digit transfer code for pairing.
     * Display this code to the sender so they can verify the correct recipient.
     */
    fun generateTransferCode(): String {
        val random = SecureRandom()
        return String.format("%06d", random.nextInt(1_000_000))
    }

    // ────────────── Send via Bluetooth ──────────────

    /**
     * Send a model file using Android's Bluetooth OPP (Object Push Profile).
     * Opens the system Bluetooth share picker.
     *
     * @param activity Current activity for starting the share intent
     * @param modelId Model to send
     */
    fun sendModelViaBluetooth(activity: Activity, modelId: String) {
        val modelFile = modelRegistry.getModelPath(modelId)
        if (modelFile == null) {
            _transferState.value = TransferState.Error("Model haipatikani (not found)")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                modelFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(intent, "Tuma Model (Send Model)"))
            _transferState.value = TransferState.Transferring(modelId, -1) // Indeterminate
            Timber.i("Bluetooth send initiated for model %s", modelId)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initiate Bluetooth send")
            _transferState.value = TransferState.Error("Imeshindikana kutuma (Failed to send)")
        }
    }

    // ────────────── WiFi Direct Transfer ──────────────

    /**
     * Check if WiFi Direct is available on this device.
     */
    fun isWifiDirectAvailable(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }

    /**
     * Start WiFi Direct peer discovery.
     * Returns discovered devices via transferState.
     */
    fun startWifiDirectDiscovery(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        _transferState.value = TransferState.Discovering(emptyList())

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WiFi Direct peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Timber.w("WiFi Direct discovery failed: %d", reason)
                _transferState.value = TransferState.Error(
                    "Haijapatikana vifaa (No devices found)"
                )
            }
        })
    }

    /**
     * Send a model file over WiFi Direct to a connected peer.
     *
     * @param modelId Model to send
     * @param groupOwnerAddress IP address of the group owner
     */
    suspend fun sendModelViaWifiDirect(
        modelId: String,
        groupOwnerAddress: String
    ) = withContext(Dispatchers.IO) {
        val modelFile = modelRegistry.getModelPath(modelId)
        if (modelFile == null) {
            _transferState.value = TransferState.Error("Model haipatikani")
            return@withContext
        }

        try {
            _transferState.value = TransferState.Connecting("Kifaa cha mbali (Remote device)")

            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(groupOwnerAddress, WIFI_DIRECT_PORT), 15000)

            socket.use { sock ->
                val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(sock.getInputStream()))

                // Protocol: [modelId_len][modelId][fileSize][sha256][file_data]
                val modelIdBytes = modelId.toByteArray()
                output.writeInt(modelIdBytes.size)
                output.write(modelIdBytes)

                val fileSize = modelFile.length()
                output.writeLong(fileSize)

                // Send SHA-256 for verification
                val sha256 = sha256File(modelFile)
                output.writeUTF(sha256)
                output.flush()

                // Send file data with progress
                var sent = 0L
                modelFile.inputStream().use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        sent += bytesRead
                        val percent = ((sent * 100) / fileSize).toInt()
                        _transferState.value = TransferState.Transferring(modelId, percent)
                    }
                }
                output.flush()

                // Wait for acknowledgment
                val ack = input.readBoolean()
                if (ack) {
                    _transferState.value = TransferState.Complete(modelId)
                    Timber.i("Model %s sent successfully via WiFi Direct", modelId)
                } else {
                    _transferState.value = TransferState.Error("Mdhibiti hakubali (Receiver rejected)")
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "WiFi Direct send failed")
            _transferState.value = TransferState.Error("Umeshindwa kutuma (Send failed)")
        }
    }

    // ────────────── Receive via WiFi Direct ──────────────

    /**
     * Start listening for incoming model transfers on WiFi Direct.
     * Runs a server socket that accepts one connection.
     *
     * @param scope Coroutine scope for the server job
     */
    fun startReceiving(scope: CoroutineScope) {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(WIFI_DIRECT_PORT)
                Timber.d("Model transfer server listening on port %d", WIFI_DIRECT_PORT)

                while (isActive) {
                    val clientSocket = withContext(Dispatchers.IO) {
                        serverSocket.accept()
                    }

                    launch {
                        handleIncomingTransfer(clientSocket)
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Model transfer server error")
                _transferState.value = TransferState.Error("Server error: ${e.message}")
            }
        }
    }

    /**
     * Stop listening for incoming transfers.
     */
    fun stopReceiving() {
        serverJob?.cancel()
        serverJob = null
    }

    /**
     * Handle an incoming model transfer from a connected peer.
     * Protocol: [modelId_len][modelId][fileSize][sha256][file_data]
     */
    private suspend fun handleIncomingTransfer(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.use { sock ->
                val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

                // Read model ID
                val modelIdLen = input.readInt()
                val modelIdBytes = ByteArray(modelIdLen)
                input.readFully(modelIdBytes)
                val modelId = String(modelIdBytes)

                // Read file size and SHA-256
                val fileSize = input.readLong()
                val expectedSha256 = input.readUTF()

                Timber.i("Receiving model %s (%d bytes)", modelId, fileSize)
                _transferState.value = TransferState.Receiving(modelId, 0)

                // Validate model ID
                val def = com.msaidizi.app.voice.ModelRegistry.MODELS[modelId]
                if (def == null) {
                    Timber.w("Unknown model ID received: %s", modelId)
                    output.writeBoolean(false)
                    output.flush()
                    return@withContext
                }

                // Check storage space
                if (stagingDir.freeSpace < fileSize) {
                    Timber.w("Not enough storage for incoming model")
                    _transferState.value = TransferState.Error("Hakuna nafasi ya kutosha (Not enough space)")
                    output.writeBoolean(false)
                    output.flush()
                    return@withContext
                }

                // Receive file to staging directory
                val stagedFile = File(stagingDir, "${def.filename}.incoming")
                var received = 0L

                stagedFile.outputStream().use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (received < fileSize) {
                        val toRead = minOf(buffer.size.toLong(), fileSize - received).toInt()
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        fos.write(buffer, 0, bytesRead)
                        received += bytesRead
                        val percent = ((received * 100) / fileSize).toInt()
                        _transferState.value = TransferState.Receiving(modelId, percent)
                    }
                }

                // Verify SHA-256
                _transferState.value = TransferState.Verifying(modelId)
                val actualSha256 = sha256File(stagedFile)

                if (actualSha256 != expectedSha256) {
                    Timber.e("SHA-256 mismatch for received model %s", modelId)
                    stagedFile.delete()
                    output.writeBoolean(false)
                    output.flush()
                    _transferState.value = TransferState.Error("Uthibitisho umeshindwa (Verification failed)")
                    return@withContext
                }

                // Install from staging
                val finalFile = File(stagingDir, def.filename)
                stagedFile.renameTo(finalFile)

                val installed = modelRegistry.installFromStaging(modelId, finalFile)
                if (installed) {
                    output.writeBoolean(true)
                    output.flush()
                    _transferState.value = TransferState.Complete(modelId)
                    Timber.i("Model %s received and installed successfully", modelId)
                } else {
                    output.writeBoolean(false)
                    output.flush()
                    _transferState.value = TransferState.Error("Kusanikisha imeshindwa (Install failed)")
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error handling incoming transfer")
            _transferState.value = TransferState.Error("Kupokea imeshindwa (Receive failed)")
        }
    }

    // ────────────── Bluetooth File Receive ──────────────

    private var bluetoothFileObserver: FileObserver? = null

    /**
     * Start watching the Bluetooth receive directory for incoming model files.
     * Received files are moved to staging, verified, and installed.
     *
     * @param scope Coroutine scope for async file processing
     */
    fun startBluetoothFileObserver(scope: CoroutineScope) {
        val btDir = File(Environment.getExternalStorageDirectory(), "Bluetooth")
        if (!btDir.exists()) {
            Timber.w("Bluetooth receive directory does not exist: %s", btDir.absolutePath)
            return
        }

        bluetoothFileObserver?.stopWatching()

        bluetoothFileObserver = object : FileObserver(
            btDir.absolutePath,
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                path ?: return
                val file = File(btDir, path)

                // Only process model files
                if (!file.name.endsWith(".onnx") && !file.name.endsWith(".gguf")) return

                Timber.d("Bluetooth model file detected: %s", file.name)

                scope.launch(Dispatchers.IO) {
                    handleBluetoothReceivedFile(file)
                }
            }
        }

        bluetoothFileObserver?.startWatching()
        Timber.i("Bluetooth file observer started for %s", btDir.absolutePath)
    }

    /**
     * Stop watching the Bluetooth receive directory.
     */
    fun stopBluetoothFileObserver() {
        bluetoothFileObserver?.stopWatching()
        bluetoothFileObserver = null
        Timber.d("Bluetooth file observer stopped")
    }

    /**
     * Handle a file received via Bluetooth OPP.
     * Moves to staging, verifies integrity, and installs.
     */
    private suspend fun handleBluetoothReceivedFile(file: File) = withContext(Dispatchers.IO) {
        try {
            // Match to a known model by filename
            val def = ModelRegistry.MODELS.values.find { it.filename == file.name }
            if (def == null) {
                Timber.w("Bluetooth received unknown file: %s — ignoring", file.name)
                return@withContext
            }

            Timber.i("Bluetooth received model file: %s (%d bytes)", def.id, file.length())

            // Move to staging directory for verification
            val stagedFile = File(stagingDir, "${def.filename}.bluetooth.incoming")
            file.copyTo(stagedFile, overwrite = true)
            file.delete()

            // Install from staging (includes SHA-256 verification)
            val installed = modelRegistry.installFromStaging(def.id, stagedFile)
            if (installed) {
                _transferState.value = TransferState.Complete(def.id)
                Timber.i("Model %s received via Bluetooth and installed", def.id)
            } else {
                _transferState.value = TransferState.Error(
                    "Uthibitisho umeshindwa — faili limeondolewa (Verification failed — file removed)"
                )
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error handling Bluetooth received file: %s", file.name)
            _transferState.value = TransferState.Error(
                "Imeshindwa kuchakata faili la Bluetooth (Failed to process Bluetooth file)"
            )
        }
    }

    /**
     * Register a BroadcastReceiver for Bluetooth file transfers.
     * Received files are automatically moved to staging and verified.
     */
    fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
    }

    /**
     * Unregister the Bluetooth receiver and stop file observer.
     */
    fun unregisterBluetoothReceiver() {
        stopBluetoothFileObserver()
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Throwable) {
            Timber.w(e, "Error unregistering Bluetooth receiver")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Bluetooth OPP receive is handled by the system.
            // Files go to /sdcard/Bluetooth/ by default.
            // We check for new files there after transfer completes.
            Timber.d("Bluetooth transfer event received")
        }
    }

    // ────────────── Helpers ──────────────

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reset transfer state to idle.
     */
    fun resetState() {
        _transferState.value = TransferState.Idle
    }
}
