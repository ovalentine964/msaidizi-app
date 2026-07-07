package com.msaidizi.app.onboarding

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R

/**
 * Welcome screen — the first thing Valentine's mum sees.
 *
 * "Karibu! Welcome to Msaidizi" in Kiswahili + English.
 * Warm, friendly, no technical jargon.
 * Just: "Your business helper is here."
 */
class IntroductionFragment : Fragment() {

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

        // App icon/logo area
        val logoContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val logoText = TextView(requireContext()).apply {
            text = "🤝"
            textSize = 72f
            gravity = android.view.Gravity.CENTER
        }
        logoContainer.addView(logoText)
        layout.addView(logoContainer)

        // Karibu! — big warm welcome
        val title = TextView(requireContext()).apply {
            text = "Karibu!"
            textSize = 36f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(R.color.primary, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(title)

        // Subtitle: "Welcome to Msaidizi"
        val subtitle = TextView(requireContext()).apply {
            text = "Welcome to Msaidizi"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        layout.addView(subtitle)

        // Tagline
        val tagline = TextView(requireContext()).apply {
            text = "Your business helper is here.\nSpeak your language. Track your money. Grow your business."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 48)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(tagline)

        // Feature highlights
        val features = listOf(
            "🎤  Speak, don't type — in YOUR language",
            "📊  See if you're making money",
            "🔒  Your data stays on YOUR phone",
            "🆓  Free forever — no charges, ever"
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

        // "Let's get started" button
        val button = Button(requireContext()).apply {
            text = "Let's Get Started →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                findNavController().navigate(R.id.action_introduction_to_naming)
            }
        }
        layout.addView(button)

        // Privacy note
        val privacyNote = TextView(requireContext()).apply {
            text = "🔒 No account needed. No data leaves your phone."
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(privacyNote)

        // Entrance animation
        layout.alpha = 0f
        layout.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(200)
            .start()

        return layout
    }
}
