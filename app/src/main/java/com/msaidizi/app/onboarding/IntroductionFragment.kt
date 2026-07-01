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
 * Stub: Introduction onboarding step.
 * TODO: Replace with full implementation including welcome screen and app overview.
 */
class IntroductionFragment : Fragment() {

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
            text = "Karibu Msaidizi!"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(requireContext()).apply {
            text = "Msaidizi ni msaidizi wako wa biashara.\nNitakusaidia kufuatilia mauzo, faida, na stock."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val button = Button(requireContext()).apply {
            text = "Anza"
            setOnClickListener {
                findNavController().navigate(R.id.action_introduction_to_business)
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(button)

        return layout
    }
}
