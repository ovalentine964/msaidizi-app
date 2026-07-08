package com.msaidizi.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.msaidizi.app.R
import com.msaidizi.app.ui.theme.AppTypography

/**
 * Stat card component for displaying key metrics.
 * Shows a label, value, and optional icon.
 *
 * ACCESSIBILITY:
 * - Minimum touch target 48dp
 * - Content description set from label+value for screen readers
 * - Minimum text size 16sp for elderly readability
 */
class StatCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconText: TextView
    private val labelText: TextView
    private val valueText: TextView
    private val subtitleText: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.card_stat, this, true)
        iconText = findViewById(R.id.stat_icon)
        labelText = findViewById(R.id.stat_label)
        valueText = findViewById(R.id.stat_value)
        subtitleText = findViewById(R.id.stat_subtitle)

        // ACCESSIBILITY: Minimum touch target
        val minTouch = AppTypography.minTouchTarget(context)
        minimumWidth = minTouch
        minimumHeight = minTouch

        // ACCESSIBILITY: Minimum text sizes for elderly users
        labelText.textSize = AppTypography.MIN_TEXT_SIZE_SP
        valueText.textSize = 20f
        subtitleText.textSize = AppTypography.MIN_TEXT_SIZE_SP
    }

    /**
     * Set the stat value.
     * Also updates content description for screen reader accessibility.
     */
    fun setStat(icon: String, label: String, value: String, subtitle: String = "") {
        iconText.text = icon
        labelText.text = label
        valueText.text = value
        subtitleText.text = subtitle
        subtitleText.visibility = if (subtitle.isEmpty()) GONE else VISIBLE

        // ACCESSIBILITY: Set content description combining all info
        contentDescription = buildString {
            append("$label: $value")
            if (subtitle.isNotEmpty()) append(". $subtitle")
        }
    }

    /**
     * Set value color.
     */
    fun setValueColor(color: Int) {
        valueText.setTextColor(color)
    }
}
