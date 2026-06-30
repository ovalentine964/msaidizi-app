package com.msaidizi.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.msaidizi.app.R

/**
 * Loading indicator with optional message.
 */
class LoadingIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val progressBar: ProgressBar
    private val messageText: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.component_loading, this, true)
        progressBar = findViewById(R.id.progress_bar)
        messageText = findViewById(R.id.loading_message)
    }

    /**
     * Show loading with message.
     */
    fun show(message: String = "Loading...") {
        messageText.text = message
        visibility = VISIBLE
    }

    /**
     * Hide loading indicator.
     */
    fun hide() {
        visibility = GONE
    }
}
