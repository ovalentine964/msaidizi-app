package com.msaidizi.app.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R

/**
 * Language selection — 14 African languages with flags/icons.
 *
 * Valentine's mum picks her language. That's it.
 * The app adapts everything: UI, voice recognition, TTS.
 */
class LanguageSelectionFragment : Fragment() {

    data class Language(
        val code: String,
        val name: String,
        val nativeName: String,
        val flag: String,
        val greeting: String
    )

    companion object {
        val LANGUAGES = listOf(
            Language("sw", "Swahili", "Kiswahili", "🇰🇪", "Habari!"),
            Language("en", "English", "English", "🇬🇧", "Hello!"),
            Language("am", "Amharic", "አማርኛ", "🇪🇹", "ሰላም!"),
            Language("ha", "Hausa", "Hausa", "🇳🇬", "Sannu!"),
            Language("ig", "Igbo", "Igbo", "🇳🇬", "Ndewo!"),
            Language("yo", "Yoruba", "Yorùbá", "🇳🇬", "Bawo ni!"),
            Language("zu", "Zulu", "isiZulu", "🇿🇦", "Sawubona!"),
            Language("xh", "Xhosa", "isiXhosa", "🇿🇦", "Molo!"),
            Language("rw", "Kinyarwanda", "Kinyarwanda", "🇷🇼", "Muraho!"),
            Language("ln", "Lingala", "Lingala", "🇨🇩", "Mbote!"),
            Language("sn", "Shona", "ChiShona", "🇿🇼", "Mhoro!"),
            Language("lg", "Luganda", "Luganda", "🇺🇬", "Oli otya!"),
            Language("so", "Somali", "Soomaali", "🇸🇴", "Iska warran!"),
            Language("pt", "Portuguese", "Português", "🇲🇿", "Olá!")
        )
    }

    private var selectedLanguage: String = "sw"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scrollView = ScrollView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // Title
        val title = TextView(requireContext()).apply {
            text = "Choose Your Language"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        // Subtitle
        val subtitle = TextView(requireContext()).apply {
            text = "Chagua lugha yako"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(subtitle)

        // Language buttons
        val languageButtons = mutableListOf<Button>()

        for (lang in LANGUAGES) {
            val button = Button(requireContext()).apply {
                text = "${lang.flag}  ${lang.nativeName}"
                textSize = 16f
                setPadding(32, 16, 32, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 6, 0, 6)
                }

                // Default selection highlight
                if (lang.code == selectedLanguage) {
                    setBackgroundColor(resources.getColor(R.color.primary, null))
                    setTextColor(resources.getColor(android.R.color.white, null))
                }

                setOnClickListener {
                    selectedLanguage = lang.code
                    // Update all buttons
                    for (btn in languageButtons) {
                        btn.setBackgroundColor(
                            resources.getColor(android.R.color.transparent, null)
                        )
                        btn.setTextColor(resources.getColor(R.color.primary, null))
                    }
                    // Highlight selected
                    setBackgroundColor(resources.getColor(R.color.primary, null))
                    setTextColor(resources.getColor(android.R.color.white, null))
                }
            }
            languageButtons.add(button)
            layout.addView(button)
        }

        // Spacer
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                32
            )
        }
        layout.addView(spacer)

        // Continue button
        val continueButton = Button(requireContext()).apply {
            text = "Continue →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                // Save selection and proceed
                val activity = requireActivity() as? OnboardingActivity
                activity?.onboardingData?.let { data ->
                    data.language = selectedLanguage
                }
                findNavController().navigate(R.id.action_language_selection_to_voice_setup)
            }
        }
        layout.addView(continueButton)

        scrollView.addView(layout)
        return scrollView
    }
}
