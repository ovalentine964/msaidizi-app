package com.msaidizi.app.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.msaidizi.app.MainActivity
import com.msaidizi.app.R

/**
 * "Msaidizi is ready!" screen — the final step of onboarding.
 *
 * Warm, celebratory. Valentine's mum has made it!
 * "Tell me about your business" with a voice prompt.
 */
class FirstUseFragment : Fragment() {

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

        // Celebration icon
        val icon = TextView(requireContext()).apply {
            text = "🎉"
            textSize = 72f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(icon)

        // Title
        val title = TextView(requireContext()).apply {
            text = "Msaidizi is Ready!"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.primary, null))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        // Subtitle
        val subtitle = TextView(requireContext()).apply {
            text = "Your AI business helper is here."
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(subtitle)

        // What she can do
        val features = listOf(
            "🎤  \"Nilizungumza nyanya tatu\" — record sales by voice",
            "📊  \"Nimepata faida ngapi?\" — ask about your profit",
            "📦  \"Stock yangu ikoje?\" — check your inventory",
            "💡  \"Nifanye nini?\" — get business advice"
        )

        for (feature in features) {
            val featureText = TextView(requireContext()).apply {
                text = feature
                textSize = 15f
                setPadding(0, 12, 0, 12)
            }
            layout.addView(featureText)
        }

        // Spacer
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            )
        }
        layout.addView(spacer)

        // "Start using Msaidizi" button
        val startButton = Button(requireContext()).apply {
            text = "🎤  Start Using Msaidizi"
            textSize = 20f
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
        }
        layout.addView(startButton)

        // Encouragement
        val encouragement = TextView(requireContext()).apply {
            text = "Just speak naturally — Msaidizi understands you.\nHakuna haja ya kujua teknolojia!"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(encouragement)

        // Entrance animation
        layout.alpha = 0f
        layout.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(300)
            .start()

        return layout
    }
}
