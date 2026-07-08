package com.msaidizi.app.onboarding

import android.app.Dialog
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.msaidizi.app.R
import java.util.Locale

/**
 * AgentNamingDialog — Let the worker name their Msaidizi.
 *
 * Valentine's vision: "Like in OpenClaw every user names his agent with
 * the name he prefers — same to Msaidizi, workers get to give Msaidizi
 * a name they prefer."
 *
 * This is deeply personal. The worker isn't configuring software —
 * they're naming a companion. The dialog should feel warm, not technical.
 *
 * BCB 108 (Communication): The naming moment establishes the relationship.
 * A named agent feels more trustworthy, more personal, more "yours."
 *
 * Suggested names (in Kiswahili):
 * - "Msaidizi" — Helper (default)
 * - "Rafiki" — Friend
 * - "Biashara Yangu" — My Business
 * - "Mshauri" — Advisor
 * - "Mwalimu" — Teacher
 * - Custom — whatever the worker wants
 *
 * @author Angavu Intelligence — Implementation Swarm 9
 */
class AgentNamingDialog : DialogFragment() {

    private var onNameConfirmed: ((String) -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var selectedLanguage: Language = Language.KISWAHILI

    companion object {
        fun newInstance(
            language: Language,
            onNameConfirmed: (String) -> Unit
        ): AgentNamingDialog {
            return AgentNamingDialog().apply {
                this.selectedLanguage = language
                this.onNameConfirmed = onNameConfirmed
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_agent_naming, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameInput = view.findViewById<TextInputEditText>(R.id.input_agent_name)
        val confirmButton = view.findViewById<MaterialButton>(R.id.btn_confirm_name)
        val skipButton = view.findViewById<MaterialButton>(R.id.btn_use_default)

        // Suggested name chips
        val suggestedNames = getSuggestedNames()
        setupSuggestionChips(view, suggestedNames, nameInput)

        // Confirm custom name
        confirmButton.setOnClickListener {
            val name = nameInput.text?.toString()?.trim() ?: ""
            if (name.isNotEmpty()) {
                speakConfirmation(name)
                onNameConfirmed?.invoke(name)
                dismiss()
            } else {
                // Gentle nudge — not an error, just ask
                nameInput.error = getEmptyNameMessage()
            }
        }

        // Skip — use default "Msaidizi"
        skipButton.setOnClickListener {
            val defaultName = "Msaidizi"
            speakConfirmation(defaultName)
            onNameConfirmed?.invoke(defaultName)
            dismiss()
        }

        // Initialize TTS for the confirmation voice
        initTTS()
    }

    /**
     * Get suggested names based on the worker's language.
     * BCB 108: Culturally appropriate suggestions.
     */
    private fun getSuggestedNames(): List<Pair<String, String>> {
        return when (selectedLanguage) {
            Language.KISWAHILI -> listOf(
                "Msaidizi" to "Msaidizi (Helper)",
                "Rafiki" to "Rafiki (Friend)",
                "Mshauri" to "Mshauri (Advisor)",
                "Mwalimu" to "Mwalimu (Teacher)",
                "Biashara Yangu" to "Biashara Yangu (My Business)"
            )
            Language.ENGLISH -> listOf(
                "Msaidizi" to "Msaidizi (Helper)",
                "Friend" to "Friend",
                "Advisor" to "Advisor",
                "CFO" to "My CFO",
                "BizHelper" to "Biz Helper"
            )
            Language.HAUSA -> listOf(
                "Abokin" to "Abokin (Friend)",
                "Mai taimako" to "Mai taimako (Helper)",
                "Msaidizi" to "Msaidizi"
            )
            Language.YORUBA -> listOf(
                "Alagbawi" to "Alagbawi (Friend)",
                "Oluranlọwọ" to "Oluranlọwọ (Helper)",
                "Msaidizi" to "Msaidizi"
            )
            else -> listOf(
                "Msaidizi" to "Msaidizi",
                "Rafiki" to "Rafiki",
                "Mshauri" to "Mshauri"
            )
        }
    }

    private fun setupSuggestionChips(
        view: View,
        suggestions: List<Pair<String, String>>,
        nameInput: TextInputEditText
    ) {
        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_suggestions)
        chipGroup?.removeAllViews()

        suggestions.forEach { (value, label) ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                isCheckable = true
                setOnClickListener {
                    nameInput.setText(value)
                }
            }
            chipGroup?.addView(chip)
        }
    }

    /**
     * Speak the confirmation in the worker's language.
     * "Sawa! Nitaitwa [name] tangu sasa."
     * "Okay! I'll be called [name] from now on."
     */
    private fun speakConfirmation(name: String) {
        val message = when (selectedLanguage) {
            Language.KISWAHILI -> "Sawa! Nitaitwa $name tangu sasa. Karibu, $name yuko hapa kukusaidia."
            Language.ENGLISH -> "Okay! I'll be called $name from now on. Welcome, $name is here to help you."
            Language.HAUSA -> "To! Zan yi suna $name daga yanzu. Barka da zuwa."
            Language.YORUBA -> "O dara! Emi ni $name lati isiyi. Kaabo."
            else -> "Okay! $name is ready to help."
        }
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "agent_naming")
    }

    private fun getEmptyNameMessage(): String {
        return when (selectedLanguage) {
            Language.KISWAHILI -> "Andika jina la Msaidizi wako"
            Language.ENGLISH -> "Enter a name for your helper"
            else -> "Enter a name"
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = when (selectedLanguage) {
                    Language.KISWAHILI -> Locale("sw", "KE")
                    Language.ENGLISH -> Locale.US
                    Language.AMHARIC -> Locale("am", "ET")
                    Language.HAUSA -> Locale("ha", "NG")
                    Language.PORTUGUESE -> Locale("pt", "MZ")
                    else -> Locale("sw", "KE")
                }
                tts?.language = locale
            }
        }
    }

    override fun onDestroyView() {
        tts?.shutdown()
        super.onDestroyView()
    }
}
