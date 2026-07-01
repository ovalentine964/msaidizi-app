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
 * Stub: First use / tutorial onboarding step.
 * TODO: Replace with full implementation including guided first transaction demo.
 */
class FirstUseFragment : Fragment() {

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
            text = "Tayari!"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(requireContext()).apply {
            text = "Sasa tuanze kufuatilia biashara yako.\nSema mauzo yako na nitakusaidia."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val button = Button(requireContext()).apply {
            text = "Anza Biashara"
            setOnClickListener {
                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(button)

        return layout
    }
}
