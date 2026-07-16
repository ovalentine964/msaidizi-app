package com.msaidizi.app.ui.models

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.msaidizi.app.R
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.voice.ModelState
import com.msaidizi.app.voice.transfer.ModelTransfer
import com.msaidizi.app.voice.transfer.SdCardModelLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fragment for model download management.
 * Shows tiered model status, download buttons, and peer transfer options.
 *
 * Uses XML layout (NOT Compose) for 2GB device compatibility.
 */
@AndroidEntryPoint
class ModelDownloadFragment : Fragment() {

    private val viewModel: ModelDownloadViewModel by viewModels()

    // Views
    private lateinit var storageUsedText: TextView
    private lateinit var whisperStatusIcon: TextView
    private lateinit var whisperProgress: android.widget.ProgressBar
    private lateinit var piperStatusIcon: TextView
    private lateinit var piperProgress: android.widget.ProgressBar
    private lateinit var qwenStatusIcon: TextView
    private lateinit var qwenProgress: android.widget.ProgressBar
    private lateinit var downloadTier1Button: MaterialButton
    private lateinit var downloadTier2Button: MaterialButton
    private lateinit var sendBluetoothButton: MaterialButton
    private lateinit var scanSdcardButton: MaterialButton
    private lateinit var wifiNotice: TextView
    private lateinit var statusMessage: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_model_download, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        observeState()
    }

    private fun bindViews(view: View) {
        storageUsedText = view.findViewById(R.id.storage_used_text)
        whisperStatusIcon = view.findViewById(R.id.whisper_status_icon)
        whisperProgress = view.findViewById(R.id.whisper_progress)
        piperStatusIcon = view.findViewById(R.id.piper_status_icon)
        piperProgress = view.findViewById(R.id.piper_progress)
        qwenStatusIcon = view.findViewById(R.id.qwen_status_icon)
        qwenProgress = view.findViewById(R.id.qwen_progress)
        downloadTier1Button = view.findViewById(R.id.download_tier1_button)
        downloadTier2Button = view.findViewById(R.id.download_tier2_button)
        sendBluetoothButton = view.findViewById(R.id.send_bluetooth_button)
        scanSdcardButton = view.findViewById(R.id.scan_sdcard_button)
        wifiNotice = view.findViewById(R.id.wifi_notice)
        statusMessage = view.findViewById(R.id.status_message)
    }

    private fun setupListeners() {
        downloadTier1Button.setOnClickListener {
            viewModel.downloadTier1()
        }

        downloadTier2Button.setOnClickListener {
            viewModel.downloadTier2()
        }

        sendBluetoothButton.setOnClickListener {
            // Show model picker for Bluetooth send
            showBluetoothModelPicker()
        }

        scanSdcardButton.setOnClickListener {
            viewModel.scanSdCard()
            viewModel.copyFromSdCard()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: ModelDownloadUiState) {
        // Storage info
        storageUsedText.text = getString(R.string.models_storage_used, state.storageUsed)

        // Update individual model items
        for (model in state.models) {
            when (model.id) {
                "whisper-tiny-int4" -> updateModelItem(
                    whisperStatusIcon, whisperProgress, model
                )
                "piper-swahili" -> updateModelItem(
                    piperStatusIcon, piperProgress, model
                )
                "gemma-4-e2b-q4km", "gemma-4-e2b-q3km" -> updateModelItem(
                    qwenStatusIcon, qwenProgress, model  // Reuse Qwen views for primary model display
                )
                "qwen-3.5-0.8b-q4km" -> { /* Qwen is now fallback; primary model shown above */ }
            }
        }

        // Tier 1 button state
        if (state.tier1Ready) {
            downloadTier1Button.text = getString(R.string.models_tier1_ready)
            downloadTier1Button.isEnabled = false
        } else {
            downloadTier1Button.text = getString(R.string.models_download_voice)
            downloadTier1Button.isEnabled = true
        }

        // Tier 2 button state
        if (state.tier2Ready) {
            downloadTier2Button.text = getString(R.string.models_tier2_ready)
            downloadTier2Button.isEnabled = false
        } else {
            downloadTier2Button.text = getString(R.string.models_download_ai)
            downloadTier2Button.isEnabled = state.isWifi
            wifiNotice.visibility = if (state.isWifi) View.GONE else View.VISIBLE
        }

        // Transfer state messages
        when (val ts = state.transferState) {
            is ModelTransfer.TransferState.Error -> {
                showStatus(ts.message, isError = true)
            }
            is ModelTransfer.TransferState.Complete -> {
                showStatus(getString(R.string.models_transfer_complete, ts.modelId), isError = false)
            }
            is ModelTransfer.TransferState.Transferring -> {
                showStatus(getString(R.string.models_transferring, ts.percent), isError = false)
            }
            is ModelTransfer.TransferState.Receiving -> {
                showStatus(getString(R.string.models_receiving, ts.percent), isError = false)
            }
            is ModelTransfer.TransferState.Verifying -> {
                showStatus(getString(R.string.models_verifying), isError = false)
            }
            else -> { hideStatus() }
        }

        // SD card state
        when (val sd = state.sdCardState) {
            is SdCardModelLoader.SdCardState.ModelsFound -> {
                showStatus(getString(R.string.models_sdcard_found, sd.models.size), isError = false)
            }
            is SdCardModelLoader.SdCardState.Copying -> {
                showStatus(getString(R.string.models_sdcard_copying, sd.percent), isError = false)
            }
            is SdCardModelLoader.SdCardState.Complete -> {
                showStatus(getString(R.string.models_sdcard_complete, sd.copiedCount), isError = false)
            }
            is SdCardModelLoader.SdCardState.Error -> {
                showStatus(sd.message, isError = true)
            }
            else -> { }
        }
    }

    private fun updateModelItem(
        statusIcon: TextView,
        progressBar: android.widget.ProgressBar,
        model: ModelStatusUi
    ) {
        when (model.state) {
            ModelState.READY -> {
                statusIcon.text = "✅"
                progressBar.visibility = View.GONE
            }
            ModelState.DOWNLOADING -> {
                statusIcon.text = "⬇️"
                progressBar.visibility = View.VISIBLE
                progressBar.progress = (model.progress * 100).toInt()
            }
            ModelState.VERIFYING -> {
                statusIcon.text = "🔍"
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 100
            }
            ModelState.ERROR -> {
                statusIcon.text = "❌"
                progressBar.visibility = View.GONE
            }
            ModelState.PAUSED -> {
                statusIcon.text = "⏸️"
                progressBar.visibility = View.VISIBLE
                progressBar.progress = (model.progress * 100).toInt()
            }
            ModelState.NOT_DOWNLOADED -> {
                statusIcon.text = "⏳"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showBluetoothModelPicker() {
        // Simple dialog to pick which model to send
        val readyModels = viewModel.uiState.value.models.filter { it.state == ModelState.READY }
        if (readyModels.isEmpty()) {
            Toast.makeText(context, getString(R.string.models_none_ready), Toast.LENGTH_SHORT).show()
            return
        }

        val names = readyModels.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.models_pick_send))
            .setItems(names) { _, which ->
                viewModel.sendModelViaBluetooth(requireActivity(), readyModels[which].id)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusMessage.visibility = View.VISIBLE
        statusMessage.text = message
        statusMessage.setTextColor(
            if (isError) resources.getColor(android.R.color.holo_red_dark, null)
            else resources.getColor(R.color.text_secondary, null)
        )
    }

    private fun hideStatus() {
        statusMessage.visibility = View.GONE
    }
}
