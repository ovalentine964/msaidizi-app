package com.msaidizi.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.msaidizi.app.R

/**
 * Stat card component for displaying key metrics.
 * Shows a label, value, and optional icon.
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
    }

    /**
     * Set the stat value.
     */
    fun setStat(icon: String, label: String, value: String, subtitle: String = "") {
        iconText.text = icon
        labelText.text = label
        valueText.text = value
        subtitleText.text = subtitle
        subtitleText.visibility = if (subtitle.isEmpty()) GONE else VISIBLE
    }

    /**
     * Set value color.
     */
    fun setValueColor(color: Int) {
        valueText.setTextColor(color)
    }
}
