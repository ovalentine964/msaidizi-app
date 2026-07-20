package com.msaidizi.app.ui.tithe

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.msaidizi.app.R
import com.msaidizi.app.core.model.TitheRecord
import com.msaidizi.app.finance.TitheTracker
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tithe & Giving Tracker screen.
 *
 * Features:
 * - Prominent voice recording button for recording giving
 * - Manual input form (amount, type, recipient)
 * - Visual tithe history with cards
 * - Abundance pattern visualization (monthly bar chart)
 * - Consistency score with streak celebration
 * - All labels in Swahili for semi-literate workers
 */
@AndroidEntryPoint
class TitheFragment : Fragment() {

    private val viewModel: TitheViewModel by viewModels()

    // Voice
    private lateinit var micButton: FloatingActionButton
    private lateinit var micStatusText: TextView

    // Summary cards
    private lateinit var totalGivenCard: MaterialCardView
    private lateinit var totalGivenText: TextView
    private lateinit var streakCard: MaterialCardView
    private lateinit var streakText: TextView
    private lateinit var streakEmoji: TextView
    private lateinit var consistencyCard: MaterialCardView
    private lateinit var consistencyScoreText: TextView
    private lateinit var consistencyProgress: ProgressBar
    private lateinit var frequencyText: TextView

    // Abundance pattern
    private lateinit var abundanceCard: MaterialCardView
    private lateinit var abundanceText: TextView
    private lateinit var abundanceIndicator: TextView
    private lateinit var monthlyChartContainer: LinearLayout

    // Reminder
    private lateinit var reminderCard: MaterialCardView
    private lateinit var reminderText: TextView

    // Manual form
    private lateinit var formCard: MaterialCardView
    private lateinit var toggleFormButton: MaterialButton
    private lateinit var amountInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var recipientInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var submitButton: MaterialButton

    // History
    private lateinit var historyRecycler: RecyclerView
    private lateinit var historyEmptyText: TextView

    // Confirmation
    private lateinit var confirmationCard: MaterialCardView
    private lateinit var confirmationText: TextView

    // Error
    private lateinit var errorCard: MaterialCardView
    private lateinit var errorText: TextView

    private var isRecording = false

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tithe, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsHelper = AccessibilityTtsHelper(requireContext())
        setupViews(view)
        setupTypeSpinner()
        observeState()
    }

    private fun setupViews(view: View) {
        // Voice
        micButton = view.findViewById(R.id.mic_button)
        micStatusText = view.findViewById(R.id.mic_status_text)

        // Summary cards
        totalGivenCard = view.findViewById(R.id.total_given_card)
        totalGivenText = view.findViewById(R.id.total_given_text)
        streakCard = view.findViewById(R.id.streak_card)
        streakText = view.findViewById(R.id.streak_text)
        streakEmoji = view.findViewById(R.id.streak_emoji)
        consistencyCard = view.findViewById(R.id.consistency_card)
        consistencyScoreText = view.findViewById(R.id.consistency_score_text)
        consistencyProgress = view.findViewById(R.id.consistency_progress)
        frequencyText = view.findViewById(R.id.frequency_text)

        // Abundance pattern
        abundanceCard = view.findViewById(R.id.abundance_card)
        abundanceText = view.findViewById(R.id.abundance_text)
        abundanceIndicator = view.findViewById(R.id.abundance_indicator)
        monthlyChartContainer = view.findViewById(R.id.monthly_chart_container)

        // Reminder
        reminderCard = view.findViewById(R.id.reminder_card)
        reminderText = view.findViewById(R.id.reminder_text)

        // Manual form
        formCard = view.findViewById(R.id.form_card)
        toggleFormButton = view.findViewById(R.id.toggle_form_button)
        amountInput = view.findViewById(R.id.amount_input)
        typeSpinner = view.findViewById(R.id.type_spinner)
        recipientInput = view.findViewById(R.id.recipient_input)
        notesInput = view.findViewById(R.id.notes_input)
        submitButton = view.findViewById(R.id.submit_button)

        // History
        historyRecycler = view.findViewById(R.id.history_recycler)
        historyEmptyText = view.findViewById(R.id.history_empty_text)

        // Confirmation
        confirmationCard = view.findViewById(R.id.confirmation_card)
        confirmationText = view.findViewById(R.id.confirmation_text)

        // Error
        errorCard = view.findViewById(R.id.error_card)
        errorText = view.findViewById(R.id.error_text)

        // Setup RecyclerView
        historyRecycler.layoutManager = LinearLayoutManager(requireContext())

        // Mic button — voice recording
        micButton.setOnClickListener {
            if (isRecording) {
                stopVoiceRecording()
            } else {
                startVoiceRecording()
            }
        }

        // Toggle form
        toggleFormButton.setOnClickListener {
            viewModel.toggleForm()
        }

        // Submit manual form
        submitButton.setOnClickListener {
            submitManualForm()
        }

        // Dismiss confirmation on tap
        confirmationCard.setOnClickListener {
            viewModel.clearConfirmation()
        }

        // Dismiss error on tap
        errorCard.setOnClickListener {
            viewModel.clearError()
        }
    }

    private fun setupTypeSpinner() {
        val types = TitheTracker.GivingType.entries.map { "${it.swahili} — ${it.name}" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = adapter
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: TitheUiState) {
        // Loading state
        micButton.isEnabled = !state.isProcessing

        // Recording state
        isRecording = state.isRecording
        if (state.isRecording) {
            micButton.setImageResource(android.R.drawable.ic_media_pause)
            micStatusText.text = "🎤 Inasikiliza… Sema kiasi ulichotoa"
            startPulseAnimation(micButton)
        } else if (state.isProcessing) {
            micStatusText.text = "⏳ Inachakata…"
        } else {
            micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            micStatusText.text = "Gusa kurekodi — \"Nilitoa sadaka KSh 200\""
            stopPulseAnimation(micButton)
        }

        // Total given
        totalGivenText.text = "KSh ${formatAmount(state.totalGiven)}"

        // Streak
        streakText.text = "${state.streakDays} siku"
        streakEmoji.text = when {
            state.streakDays >= 30 -> "🏆"
            state.streakDays >= 14 -> "🔥"
            state.streakDays >= 7 -> "⭐"
            state.streakDays >= 1 -> "🌱"
            else -> "📌"
        }
        // Celebrate streak milestones
        if (state.streakDays in listOf(7, 14, 30)) {
            celebrateStreak(streakCard)
        }

        // Consistency score
        consistencyScoreText.text = "${state.consistencyScore}/100"
        consistencyProgress.progress = state.consistencyScore
        frequencyText.text = state.givingFrequency.ifBlank { "Bado hakuna data" }

        // Abundance pattern
        val patternValue = state.abundancePattern
        abundanceText.text = if (patternValue > 0) {
            "+${formatAmount(patternValue)}%"
        } else if (patternValue < 0) {
            "${formatAmount(patternValue)}%"
        } else {
            "Hakuna data bado"
        }
        abundanceIndicator.text = when {
            patternValue > 5 -> "📈 Mapato yanaongezeka"
            patternValue > 0 -> "📊 Mapato ni wastani"
            patternValue < 0 -> "📉 Mapato yamepungua"
            else -> "📊 Rekodi zaidi ili kuona mwenendo"
        }

        // Monthly chart (simple bar visualization)
        updateMonthlyChart(state.monthlyTotals)

        // Reminder
        reminderText.text = viewModel.getGivingReminder()

        // Form visibility
        formCard.visibility = if (state.showForm) View.VISIBLE else View.GONE
        toggleFormButton.text = if (state.showForm) "Funga fomu" else "📝 Andika mwenyewe"

        // History
        if (state.titheRecords.isEmpty()) {
            historyRecycler.visibility = View.GONE
            historyEmptyText.visibility = View.VISIBLE
            historyEmptyText.text = "Hakuna rekodi bado. Anza kutoa leo! 🙏"
        } else {
            historyRecycler.visibility = View.VISIBLE
            historyEmptyText.visibility = View.GONE
            historyRecycler.adapter = TitheHistoryAdapter(state.titheRecords)
        }

        // Confirmation message
        if (state.confirmationMessage != null) {
            confirmationCard.visibility = View.VISIBLE
            confirmationText.text = state.confirmationMessage
            // Auto-hide after 5 seconds
            viewLifecycleOwner.lifecycleScope.launch {
                delay(5000)
                viewModel.clearConfirmation()
            }
            // Celebrate animation
            celebrateConfirmation(confirmationCard)
        } else {
            confirmationCard.visibility = View.GONE
        }

        // Error message — spoken aloud for accessibility
        if (state.error != null) {
            errorCard.visibility = View.VISIBLE
            errorText.text = state.error
            ttsHelper?.speakError(state.error!!)
        } else {
            errorCard.visibility = View.GONE
        }
    }

    /**
     * Update the simple monthly bar chart.
     * Uses colored bars proportional to the max monthly total.
     */
    private fun updateMonthlyChart(monthlyTotals: List<Pair<String, Double>>) {
        monthlyChartContainer.removeAllViews()

        if (monthlyTotals.isEmpty()) return

        val maxTotal = monthlyTotals.maxOfOrNull { it.second } ?: 1.0

        for ((month, total) in monthlyTotals) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            // Month label
            val label = TextView(requireContext()).apply {
                text = month
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(40),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(label)

            // Bar
            val barContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dpToPx(20),
                    1f
                )
                setPadding(dpToPx(4), 0, dpToPx(4), 0)
            }

            val bar = View(requireContext()).apply {
                val barWidth = if (maxTotal > 0) {
                    ((total / maxTotal) * 100).toInt().coerceIn(0, 100)
                } else 0
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    barWidth.toFloat()
                )
                alpha = 0.8f
            }
            barContainer.addView(bar)

            // Empty space
            val space = View(requireContext()).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (100 - ((total / maxTotal) * 100).toInt().coerceIn(0, 100)).toFloat()
                )
            }
            barContainer.addView(space)
            row.addView(barContainer)

            // Amount label
            val amountLabel = TextView(requireContext()).apply {
                text = if (total > 0) "KSh ${formatAmount(total)}" else "-"
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(70),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(amountLabel)

            monthlyChartContainer.addView(row)
        }
    }

    /**
     * Start voice recording (with permission check).
     */
    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            return
        }
        viewModel.startRecording()
        // In a real implementation, this would start the Whisper pipeline
        // For now, the voice text will be passed via processVoiceInput()
    }

    /**
     * Stop voice recording and process the result.
     */
    private fun stopVoiceRecording() {
        viewModel.stopRecording()
        // In a real implementation, this would stop the Whisper pipeline
        // and pass the transcribed text to processVoiceInput()
    }

    /**
     * Process voice input text (called from voice pipeline callback).
     * Example: "Nilitoa sadaka KSh 200"
     */
    fun processVoiceInput(text: String) {
        viewModel.recordGivingFromVoice(text)
    }

    /**
     * Submit the manual form.
     */
    private fun submitManualForm() {
        val amountStr = amountInput.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()

        if (amount == null || amount <= 0) {
            amountInput.error = "Weka kiasi sahihi"
            return
        }

        val typeIndex = typeSpinner.selectedItemPosition
        val type = TitheTracker.GivingType.entries[typeIndex].name
        val recipient = recipientInput.text.toString().trim()
        val notes = notesInput.text.toString().trim()

        viewModel.recordGivingManually(amount, type, recipient, notes)

        // Clear form
        amountInput.text.clear()
        recipientInput.text.clear()
        notesInput.text.clear()
    }

    /**
     * Celebrate streak milestone with a bounce animation.
     */
    private fun celebrateStreak(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator()
        }
        scaleX.start()
        scaleY.start()
    }

    /**
     * Celebrate confirmation with a slide-in animation.
     */
    private fun celebrateConfirmation(view: View) {
        view.alpha = 0f
        view.translationY = -50f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * Pulse animation for the mic button during recording.
     */
    private fun startPulseAnimation(view: View) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.5f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        view.tag = animator
        animator.start()
    }

    private fun stopPulseAnimation(view: View) {
        (view.tag as? ObjectAnimator)?.cancel()
        view.alpha = 1f
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            String.format("%,d", amount.toLong())
        } else {
            String.format("%,.2f", amount)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecording()
        } else {
            Toast.makeText(
                requireContext(),
                "Ruhusa ya kurekodi inahitajika",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 201
    }
}

/**
 * RecyclerView adapter for tithe history cards.
 * Shows each giving record with type icon, amount, recipient, and date.
 */
class TitheHistoryAdapter(
    private val records: List<TitheRecord>
) : RecyclerView.Adapter<TitheHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeIcon: TextView = view.findViewById(R.id.tithe_type_icon)
        val typeLabel: TextView = view.findViewById(R.id.tithe_type_label)
        val amount: TextView = view.findViewById(R.id.tithe_amount)
        val recipient: TextView = view.findViewById(R.id.tithe_recipient)
        val date: TextView = view.findViewById(R.id.tithe_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tithe_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]

        // Type icon based on giving type
        holder.typeIcon.text = when (record.type) {
            "TITHE" -> "⛪"
            "OFFERING" -> "🕯️"
            "ZAKAT" -> "🕌"
            "SADAQAH" -> "🤲"
            "CHARITY" -> "❤️"
            else -> "🙏"
        }

        // Type label in Swahili
        holder.typeLabel.text = try {
            TitheTracker.GivingType.valueOf(record.type).swahili
        } catch (_: Throwable) {
            record.type
        }

        // Amount
        holder.amount.text = "KSh ${formatAmount(record.amount)}"

        // Recipient
        if (record.recipient.isNotBlank()) {
            holder.recipient.text = record.recipient
            holder.recipient.visibility = View.VISIBLE
        } else {
            holder.recipient.visibility = View.GONE
        }

        // Date
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("sw", "KE"))
        holder.date.text = dateFormat.format(Date(record.date))
    }

    override fun getItemCount() = records.size

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            String.format("%,d", amount.toLong())
        } else {
            String.format("%,.2f", amount)
        }
    }
}
