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
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import com.msaidizi.app.ui.accessibility.VoiceInputHelper
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings screen — app configuration.
 *
 * ACCESSIBILITY:
 * - Language selection speaks preview in selected language (audio preview)
 * - Business name input has voice fallback (mic button)
 * - All controls have content descriptions for screen readers
 * - Minimum touch targets 48dp on all interactive elements
 * - Errors are spoken, not just displayed
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

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null
    private var businessNameVoiceHelper: VoiceInputHelper? = null
    private lateinit var businessNameMicButton: ImageButton
    private lateinit var languagePreviewButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsHelper = AccessibilityTtsHelper(requireContext())
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

        // ACCESSIBILITY: Content descriptions
        languageSpinner.contentDescription = "Chagua lugha"
        voiceSpeedSlider.contentDescription = "Kasi ya sauti"
        autoSyncSwitch.contentDescription = "Sawasisha kiotomatiki"
        wifiOnlySwitch.contentDescription = "Wifi pekee"
        businessNameInput.contentDescription = "Jina la biashara yako"
        businessTypeSpinner.contentDescription = "Aina ya biashara"
        syncButton.contentDescription = "Sawasisha data sasa"

        // ACCESSIBILITY: Minimum touch targets
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        syncButton.minimumHeight = minTouch

        // Language spinner with audio preview
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

                // ── Accessibility: Audio preview of selected language ──
                ttsHelper?.setLanguage(langCode)
                val previewText = when (langCode) {
                    "sw" -> "Umechagua Kiswahili. Habari, mimi ni Msaidizi wako."
                    "en" -> "You selected English. Hello, I am your assistant."
                    "sheng" -> "Umechagua Sheng. Sasa ni poa, Msaidizi iko hapa."
                    else -> "Umechagua Kiswahili."
                }
                ttsHelper?.speak(previewText)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Voice speed slider
        voiceSpeedSlider.addOnChangeListener { _, value, _ ->
            viewModel.setVoiceSpeed(value)
            ttsHelper?.setSpeechRate(value)
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
            ttsHelper?.speak("Sawasisha data...")
        }

        // Models button — navigate to model download screen
        val modelsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.models_button)
        modelsButton?.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_models)
        }

        // ── Accessibility: Voice input for business name ──
        businessNameMicButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Gusa kusema jina la biashara badala ya kuandika"
            background = null
            minimumWidth = minTouch
            minimumHeight = minTouch
            setPadding(8, 8, 8, 8)
        }
        val nameInputParent = businessNameInput.parent as? ViewGroup
        nameInputParent?.addView(businessNameMicButton)

        businessNameVoiceHelper = VoiceInputHelper.attach(
            context = requireContext(),
            editText = businessNameInput,
            micButton = businessNameMicButton,
            language = "sw",
            ttsHelper = ttsHelper,
            onResult = { text ->
                viewModel.setBusinessName(text)
                ttsHelper?.speak("Jina la biashara ni $text")
            }
        )

        // ── Accessibility: Language preview button ──
        languagePreviewButton = Button(requireContext()).apply {
            text = "🔊 Sikiliza"
            textSize = 16f
            minimumHeight = minTouch
            contentDescription = "Sikiliza mfano wa lugha iliyochaguliwa"
            setOnClickListener {
                val currentLang = viewModel.uiState.value.language
                ttsHelper?.setLanguage(currentLang)
                val previewText = when (currentLang) {
                    "sw" -> "Hii ni Kiswahili. Msaidizi yuko tayari kukusaidia."
                    "en" -> "This is English. Msaidizi is ready to help you."
                    "sheng" -> "Hii ni Sheng. Msaidizi iko ready kukusort."
                    else -> "Msaidizi yuko tayari."
                }
                ttsHelper?.speak(previewText)
            }
        }
        val langParent = languageSpinner.parent as? ViewGroup
        langParent?.addView(languagePreviewButton)
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

    override fun onDestroyView() {
        super.onDestroyView()
        ttsHelper?.release()
        ttsHelper = null
        businessNameVoiceHelper?.destroy()
        businessNameVoiceHelper = null
    }
}
