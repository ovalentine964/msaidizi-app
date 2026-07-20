package com.msaidizi.app.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R
import com.msaidizi.app.core.ai.BundledModelState
import com.msaidizi.app.core.ai.FullModelDownloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Model download screen during first-launch onboarding.
 *
 * Shows friendly progress: "Preparing your AI CFO..."
 * The bundled mini-model means the app works immediately.
 * The full model downloads in the background.
 *
 * Valentine's mum doesn't need to understand any of this.
 * She just sees: "Almost ready..." then "Done!"
 */
@AndroidEntryPoint
class ModelSetupFragment : Fragment() {

    private val viewModel: ModelSetupViewModel by viewModels()

    private lateinit var statusIcon: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var wifiToggle: Button
    private lateinit var continueButton: Button
    private lateinit var skipButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            gravity = android.view.Gravity.CENTER
        }

        // Icon
        statusIcon = TextView(requireContext()).apply {
            text = "🧠"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(statusIcon)

        // Title
        statusTitle = TextView(requireContext()).apply {
            text = "Preparing Your AI CFO"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(statusTitle)

        // Status message
        statusMessage = TextView(requireContext()).apply {
            text = "Getting everything ready for you..."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(statusMessage)

        // Progress bar
        progressBar = ProgressBar(
            requireContext(), null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                24
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        layout.addView(progressBar)

        // Progress text
        progressText = TextView(requireContext()).apply {
            text = ""
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(progressText)

        // Mobile data toggle — data-saver aware
        wifiToggle = Button(requireContext()).apply {
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                viewModel.toggleWifiOnly()
            }
        }
        layout.addView(wifiToggle)

        // Data saver info — shows data cost estimate
        val dataSaverInfo = TextView(requireContext()).apply {
            text = "💡 Data Saver: Pakia toleo ndogo kwanza (≈300MB), kisha ongeza ubora kwenye WiFi"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 8, 32, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(dataSaverInfo)

        // Spacer
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            )
        }
        layout.addView(spacer)

        // Continue button
        continueButton = Button(requireContext()).apply {
            text = "Continue →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
            setOnClickListener {
                findNavController().navigate(R.id.action_model_download_to_first_use)
            }
        }
        layout.addView(continueButton)

        // Skip button
        skipButton = Button(requireContext()).apply {
            text = "Skip — start using now"
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setOnClickListener {
                findNavController().navigate(R.id.action_model_download_to_first_use)
            }
        }
        layout.addView(skipButton)

        // Observe state
        observeState()

        return layout
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe bundled model state
                launch {
                    viewModel.bundledModelState.collect { state ->
                        when (state) {
                            BundledModelState.READY -> {
                                statusIcon.text = "✅"
                                statusTitle.text = "Ready to Go!"
                                statusMessage.text = "Your AI helper is ready.\nThe full AI brain is downloading in the background."
                                progressBar.progress = 100
                                progressText.text = "Basic AI: Ready ✅"
                                continueButton.visibility = View.VISIBLE
                                skipButton.visibility = View.GONE
                            }
                            BundledModelState.FULL_MODEL_READY -> {
                                statusIcon.text = "🎉"
                                statusTitle.text = "All Set!"
                                statusMessage.text = "Everything is ready. Your AI CFO is fully loaded."
                                progressBar.progress = 100
                                progressText.text = "Full AI: Ready ✅"
                                continueButton.visibility = View.VISIBLE
                                skipButton.visibility = View.GONE
                            }
                            BundledModelState.CHECKING -> {
                                statusIcon.text = "⏳"
                                statusTitle.text = "Preparing Your AI CFO"
                                statusMessage.text = "Getting everything ready for you..."
                            }
                            BundledModelState.UNAVAILABLE -> {
                                statusIcon.text = "📱"
                                statusTitle.text = "Almost There"
                                statusMessage.text = "Your AI is downloading. You can start using Msaidizi now!"
                                continueButton.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                // Observe full model download progress
                launch {
                    viewModel.downloadState.collect { state ->
                        when (state) {
                            FullModelDownloadState.DOWNLOADING -> {
                                wifiToggle.text = "📶 Downloading... (WiFi only)"
                            }
                            FullModelDownloadState.WAITING_FOR_WIFI -> {
                                wifiToggle.text = "📶 Connect to WiFi to download full AI"
                            }
                            FullModelDownloadState.COMPLETED -> {
                                progressText.text = "Full AI: Downloaded ✅"
                                wifiToggle.text = "✅ Full AI downloaded"
                                wifiToggle.isEnabled = false
                            }
                            FullModelDownloadState.FAILED -> {
                                wifiToggle.text = "⚠️ Download failed — tap to retry"
                            }
                            else -> {}
                        }
                    }
                }

                // Observe download progress
                launch {
                    viewModel.fullModelProgress.collect { progress ->
                        if (progress > 0f) {
                            val percent = (progress * 100).toInt()
                            progressBar.progress = percent
                            progressText.text = "Full AI: $percent% downloaded"
                        }
                    }
                }

                // Observe WiFi-only toggle state
                launch {
                    viewModel.wifiOnly.collect { wifiOnly ->
                        wifiToggle.text = if (wifiOnly) {
                            "📶 Download on WiFi only (saves data) ✅"
                        } else {
                            "📶 Download on any network (uses data)"
                        }
                    }
                }
            }
        }
    }
}
