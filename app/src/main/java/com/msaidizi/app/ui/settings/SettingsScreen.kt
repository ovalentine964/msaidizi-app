package com.msaidizi.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.msaidizi.app.R
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings screen — app configuration.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var languageSpinner: Spinner
    private lateinit var voiceSpeedSlider: Slider
    private lateinit var autoSyncSwitch: SwitchMaterial
    private lateinit var wifiOnlySwitch: SwitchMaterial
    private lateinit var businessNameInput: EditText
    private lateinit var businessTypeSpinner: Spinner
    private lateinit var syncButton: Button
    private lateinit var syncStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        observeState()
    }

    private fun setupViews(view: View) {
        languageSpinner = view.findViewById(R.id.language_spinner)
        voiceSpeedSlider = view.findViewById(R.id.voice_speed_slider)
        autoSyncSwitch = view.findViewById(R.id.auto_sync_switch)
        wifiOnlySwitch = view.findViewById(R.id.wifi_only_switch)
        businessNameInput = view.findViewById(R.id.business_name_input)
        businessTypeSpinner = view.findViewById(R.id.business_type_spinner)
        syncButton = view.findViewById(R.id.sync_button)
        syncStatus = view.findViewById(R.id.sync_status)

        // Language spinner
        val languages = arrayOf("Kiswahili", "English", "Sheng")
        languageSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, languages)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val langCode = when (position) {
                    0 -> "sw"
                    1 -> "en"
                    2 -> "sheng"
                    else -> "sw"
                }
                viewModel.setLanguage(langCode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Voice speed slider
        voiceSpeedSlider.addOnChangeListener { _, value, _ ->
            viewModel.setVoiceSpeed(value)
        }

        // Auto sync switch
        autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoSync(isChecked)
        }

        // WiFi only switch
        wifiOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setWifiOnly(isChecked)
        }

        // Business type spinner
        val businessTypes = arrayOf("General", "Food Vendor", "Produce", "Retail", "Services")
        businessTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, businessTypes)

        // Sync button
        syncButton.setOnClickListener {
            viewModel.triggerSync()
            syncStatus.text = "Syncing..."
        }

        // Models button — navigate to model download screen
        val modelsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.models_button)
        modelsButton.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_models)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: SettingsUiState) {
        val langIndex = when (state.language) {
            "sw" -> 0
            "en" -> 1
            "sheng" -> 2
            else -> 0
        }
        languageSpinner.setSelection(langIndex)

        voiceSpeedSlider.value = state.voiceSpeed
        autoSyncSwitch.isChecked = state.autoSync
        wifiOnlySwitch.isChecked = state.wifiOnly
        businessNameInput.setText(state.businessName)
    }
}
