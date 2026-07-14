package com.msaidizi.app.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R

/**
 * Agent Naming — the worker names their Msaidizi.
 *
 * This is the most important moment in onboarding. When Valentine's mum
 * names her Msaidizi, she creates ownership. It's no longer "an app" —
 * it's HER helper, HER friend, HER CFO.
 *
 * Inspired by OpenClaw's agent naming: users name their agent, creating
 * a personal bond that drives engagement.
 *
 * ## Academic Foundations
 *
 * ### PSY 101 — Behavioral Psychology
 * - **Endowment Effect (Kahneman):** People value things they "own" more
 * - **Naming creates ownership:** Once you name something, it's yours
 * - **Personalization drives engagement:** Named agents get used more
 *
 * ### BCB 108 — Communication
 * - "Unaitwa nani?" — natural Swahili, not "Enter agent name"
 * - Suggestions based on common Swahili terms of endearment
 * - Voice input option for non-readers
 *
 * ### ECO 206 — Microfinance
 * - Trust-building through personalization
 * - Named relationships feel more trustworthy than anonymous services
 *
 * Flow:
 * 1. Msaidizi introduces herself: "Habari! Mimi ni rafiki yako mpya..."
 * 2. Asks: "Ungependa uniite jina gani?" (What would you like to call me?)
 * 3. Shows suggestions + custom input
 * 4. Msaidizi responds with delight: "Napenda jina hilo!"
 * 5. Asks worker's name: "Jina lako nani?"
 * 6. Transitions to business discovery
 */
class AgentNamingFragment : Fragment() {

    private var selectedAgentName: String = "Msaidizi"
    private var workerName: String = ""
    private lateinit var customNameInput: EditText
    private lateinit var selectedNameDisplay: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER
        }

        // ── Msaidizi's Introduction ──
        val introText = TextView(requireContext()).apply {
            text = "🤝"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(introText)

        val greeting = TextView(requireContext()).apply {
            text = "Habari! Mimi ni rafiki yako mpya wa biashara."
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(greeting)

        val intro = TextView(requireContext()).apply {
            text = "Nitakusaidia kufuatilia pesa yako, kupanga biashara yako, " +
                "na kukusaidia kukua. Mimi ni CFO wako."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(intro)

        // ── Naming Question ──
        val namingQuestion = TextView(requireContext()).apply {
            text = "Ungependa uniite jina gani?"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(namingQuestion)

        val namingSubtitle = TextView(requireContext()).apply {
            text = "What would you like to call me?"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(namingSubtitle)

        // ── Name Suggestions ──
        val suggestionsLabel = TextView(requireContext()).apply {
            text = "Chagua jina:"
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        layout.addView(suggestionsLabel)

        val suggestionsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val suggestions = listOf(
            "Msaidizi" to "Helper — straightforward and clear",
            "Rafiki" to "Friend — warm and personal",
            "Biashara Yangu" to "My Business — ownership-focused",
            "Mshauri" to "Advisor — professional CFO feel",
            "Mwalimu" to "Teacher — guidance and learning"
        )

        for ((name, description) in suggestions) {
            val button = Button(requireContext()).apply {
                text = "$name — $description"
                textSize = 14f
                setPadding(24, 16, 24, 16)
                setOnClickListener { selectAgentName(name, customNameInput, selectedNameDisplay) }
            }
            suggestionsContainer.addView(button)

            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 8
                )
            }
            suggestionsContainer.addView(spacer)
        }

        layout.addView(suggestionsContainer)

        // ── Custom Name Input ──
        val customLabel = TextView(requireContext()).apply {
            text = "Au andika jina lako mwenyewe:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(customLabel)

        val customInput = EditText(requireContext()).apply {
            hint = "Jina la Msaidizi wako..."
            textSize = 18f
            setPadding(24, 16, 24, 16)
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
        customNameInput = customInput
        layout.addView(customInput)

        // ── Selected Name Display ──
        val selectedDisplay = TextView(requireContext()).apply {
            text = "Jina lililochaguliwa: Msaidizi"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 16)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        selectedNameDisplay = selectedDisplay
        layout.addView(selectedDisplay)

        // ── Worker Name Input ──
        val workerNameLabel = TextView(requireContext()).apply {
            text = "Sasa, jina lako nani?"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }
        layout.addView(workerNameLabel)

        val workerNameSubtitle = TextView(requireContext()).apply {
            text = "What's your name?"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(workerNameSubtitle)

        val workerNameInput = EditText(requireContext()).apply {
            hint = "Jina lako..."
            textSize = 18f
            setPadding(24, 16, 24, 16)
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
        layout.addView(workerNameInput)

        // ── Continue Button ──
        val continueButton = Button(requireContext()).apply {
            text = "Endelea →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                workerName = workerNameInput.text.toString().trim()
                if (workerName.isEmpty()) {
                    workerNameInput.error = "Tafadhali andika jina lako"
                    return@setOnClickListener
                }

                // Get custom name if provided
                val customName = customInput.text.toString().trim()
                if (customName.isNotEmpty()) {
                    selectedAgentName = customName
                }

                // Navigate to business discovery with both names
                val bundle = Bundle().apply {
                    putString("worker_name", workerName)
                    putString("msaidizi_name", selectedAgentName)
                }
                findNavController().navigate(
                    R.id.action_naming_to_business_discovery,
                    bundle
                )
            }
        }
        layout.addView(continueButton)

        // ── Voice Input Hint ──
        val voiceHint = TextView(requireContext()).apply {
            text = "🎤 Unaweza pia kusema jina lako"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 0)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(voiceHint)

        // Set default selection
        selectAgentName("Msaidizi", customInput, selectedDisplay)

        // Entrance animation
        layout.alpha = 0f
        layout.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(100)
            .start()

        return layout
    }

    /**
     * Handle agent name selection from suggestions or custom input.
     */
    private fun selectAgentName(name: String, customInput: EditText, display: TextView) {
        selectedAgentName = name
        customInput.setText("")
        display.text = "Jina lililochaguliwa: $name"

        // Msaidizi responds with delight
        // In voice mode, this would be spoken: "Napenda jina hilo!"
    }
}
