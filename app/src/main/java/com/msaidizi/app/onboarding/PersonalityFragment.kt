package com.msaidizi.app.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R

/**
 * Stub: Personality / assistant style onboarding step.
 * TODO: Replace with full implementation including language and tone preferences.
 */
class PersonalityFragment : Fragment() {

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

        val title = TextView(requireContext()).apply {
            text = "Msaidizi Wako"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(requireContext()).apply {
            text = "Nitakuwa msaidizi wako wa kila siku.\nTutazungumza kwa Kiswahili."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val button = Button(requireContext()).apply {
            text = "Endelea"
            setOnClickListener {
                findNavController().navigate(R.id.action_personality_to_first_use)
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(button)

        return layout
    }
}
