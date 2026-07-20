package com.msaidizi.app.ui.loans

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.msaidizi.app.R
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import com.msaidizi.app.ui.accessibility.VoiceInputHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Loan Management screen — records, tracks, and visualizes loans.
 *
 * Features:
 * - Loan recording form with purpose selector
 * - Visual loan status cards with repayment progress
 * - ROI tracking for business loans
 * - Repayment schedule display
 * - Color-coded default risk indicators
 * - Voice-first interface (prominent record button)
 *
 * All labels in Swahili for semi-literate informal workers.
 */
@AndroidEntryPoint
class LoanFragment : Fragment() {

    private val viewModel: LoanViewModel by viewModels()

    // ── Summary views ──
    private lateinit var totalOutstandingValue: TextView
    private lateinit var activeLoansCount: TextView
    private lateinit var completedLoansCount: TextView

    // ── Loan list ──
    private lateinit var loanCardsContainer: LinearLayout
    private lateinit var emptyStateView: View
    private lateinit var swipeRefresh: View

    // ── Record form ──
    private lateinit var recordFormCard: MaterialCardView
    private lateinit var amountInput: EditText
    private lateinit var purposeDropdown: AutoCompleteTextView
    private lateinit var lenderInput: EditText
    private lateinit var interestInput: EditText
    private lateinit var termInput: EditText
    private lateinit var submitLoanButton: MaterialButton
    private lateinit var cancelFormButton: MaterialButton

    // ── Repayment schedule ──
    private lateinit var scheduleContainer: LinearLayout
    private lateinit var scheduleHeader: TextView

    // ── Action buttons ──
    private lateinit var addLoanButton: MaterialButton
    private lateinit var loadingIndicator: View

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null
    private var amountVoiceHelper: VoiceInputHelper? = null
    private var lenderVoiceHelper: VoiceInputHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_loan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsHelper = AccessibilityTtsHelper(requireContext())
        setupViews(view)
        setupFormDropdowns()
        setupClickListeners()
        observeState()
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════

    private fun setupViews(view: View) {
        // Summary
        totalOutstandingValue = view.findViewById(R.id.total_outstanding_value)
        activeLoansCount = view.findViewById(R.id.active_loans_count)
        completedLoansCount = view.findViewById(R.id.completed_loans_count)

        // Loan list
        loanCardsContainer = view.findViewById(R.id.loan_cards_container)
        emptyStateView = view.findViewById(R.id.empty_state)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        // Record form
        recordFormCard = view.findViewById(R.id.record_form_card)
        amountInput = view.findViewById(R.id.input_amount)
        purposeDropdown = view.findViewById(R.id.input_purpose)
        lenderInput = view.findViewById(R.id.input_lender)
        interestInput = view.findViewById(R.id.input_interest)
        termInput = view.findViewById(R.id.input_term)
        submitLoanButton = view.findViewById(R.id.btn_submit_loan)
        cancelFormButton = view.findViewById(R.id.btn_cancel_form)

        // Repayment schedule
        scheduleContainer = view.findViewById(R.id.schedule_container)
        scheduleHeader = view.findViewById(R.id.schedule_header)

        // Actions
        addLoanButton = view.findViewById(R.id.btn_add_loan)

        // ACCESSIBILITY: Content descriptions
        amountInput.contentDescription = "Kiasi cha mkopo (KSh)"
        purposeDropdown.contentDescription = "Kusudi la mkopo"
        lenderInput.contentDescription = "Jina la mkopaji"
        interestInput.contentDescription = "Kiwango cha riba"
        termInput.contentDescription = "Muda wa mkopo (miezi)"
        addLoanButton.contentDescription = "Rekodi mkopo mpya"

        // ACCESSIBILITY: Minimum touch targets
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        addLoanButton.minimumHeight = minTouch
        submitLoanButton.minimumHeight = minTouch
        cancelFormButton.minimumHeight = minTouch

        // ACCESSIBILITY: Voice input for amount and lender fields
        setupVoiceInput()
    }

    private fun setupFormDropdowns() {
        val purposes = listOf(
            "Biashara (Business)",
            "Binafsi (Personal)",
            "Dharura (Emergency)",
            "Elimu (Education)"
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, purposes)
        purposeDropdown.setAdapter(adapter)
    }

    private fun setupClickListeners() {
        addLoanButton.setOnClickListener {
            viewModel.toggleRecordForm()
        }

        submitLoanButton.setOnClickListener {
            submitLoan()
        }

        cancelFormButton.setOnClickListener {
            viewModel.toggleRecordForm()
        }
    }

    /**
     * Set up voice input fallback for text fields.
     * For non-literate users who cannot type amounts or lender names.
     */
    private fun setupVoiceInput() {
        val minTouch = (48 * resources.displayMetrics.density).toInt()

        // Voice input for amount
        val amountMic = android.widget.ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Gusa kusema kiasi badala ya kuandika"
            background = null
            minimumWidth = minTouch
            minimumHeight = minTouch
        }
        val amountParent = amountInput.parent as? ViewGroup
        amountParent?.addView(amountMic)
        amountVoiceHelper = VoiceInputHelper.attach(
            context = requireContext(),
            editText = amountInput,
            micButton = amountMic,
            ttsHelper = ttsHelper
        )

        // Voice input for lender
        val lenderMic = android.widget.ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Gusa kusema jina la mkopaji badala ya kuandika"
            background = null
            minimumWidth = minTouch
            minimumHeight = minTouch
        }
        val lenderParent = lenderInput.parent as? ViewGroup
        lenderParent?.addView(lenderMic)
        lenderVoiceHelper = VoiceInputHelper.attach(
            context = requireContext(),
            editText = lenderInput,
            micButton = lenderMic,
            ttsHelper = ttsHelper
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE OBSERVATION
    // ═══════════════════════════════════════════════════════════════

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: LoanUiState) {
        // Loading
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Summary
        totalOutstandingValue.text = "KSh ${formatAmount(state.totalOutstanding.toInt())}"
        activeLoansCount.text = "${state.activeLoanCount}"
        completedLoansCount.text = "${state.completedCount}"

        // Record form visibility
        recordFormCard.visibility = if (state.showRecordForm) View.VISIBLE else View.GONE
        addLoanButton.text = if (state.showRecordForm) "Funga Fomu" else "+ Rekodi Mkopo"

        // Submit button state
        submitLoanButton.isEnabled = !state.isRecording
        submitLoanButton.text = if (state.isRecording) "Inarekodi..." else "Rekodi Mkopo"

        // Loan cards
        loanCardsContainer.removeAllViews()
        if (state.activeLoans.isEmpty() && !state.isLoading) {
            emptyStateView.visibility = View.VISIBLE
            loanCardsContainer.visibility = View.GONE
        } else {
            emptyStateView.visibility = View.GONE
            loanCardsContainer.visibility = View.VISIBLE
            state.activeLoans.forEach { loan ->
                val cardView = createLoanCard(loan)
                loanCardsContainer.addView(cardView)
            }
        }

        // Repayment schedule
        updateRepaymentSchedule(state.repaymentSchedule)

        // Messages — spoken aloud for accessibility
        state.successMessage?.let { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            ttsHelper?.speakSuccess(msg)
            viewModel.clearMessages()
        }
        state.error?.let { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            ttsHelper?.speakError(msg)
            viewModel.clearMessages()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN CARD CREATION
    // ═══════════════════════════════════════════════════════════════

    private fun createLoanCard(loan: LoanCardData): MaterialCardView {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            cardElevation = dpToPx(2).toFloat()
            radius = dpToPx(12).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }

        val padding = dpToPx(16)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // ── Header row: Lender + Risk badge ──
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val lenderText = TextView(context).apply {
            text = loan.lender.ifEmpty { "Mkopo" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val riskBadge = createRiskBadge(loan.defaultRisk)

        headerRow.addView(lenderText)
        headerRow.addView(riskBadge)
        contentLayout.addView(headerRow)

        // ── Purpose tag ──
        val purposeTag = TextView(context).apply {
            text = loan.purpose
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.primary_light))
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        contentLayout.addView(purposeTag)

        // ── Amount row ──
        val amountRow = createInfoRow(
            "Kiasi:",
            "KSh ${formatAmount(loan.amount.toInt())}",
            R.color.text_primary
        )
        contentLayout.addView(amountRow)

        // ── Balance row ──
        val balanceRow = createInfoRow(
            "Salio:",
            "KSh ${formatAmount(loan.balance.toInt())}",
            R.color.text_primary
        )
        contentLayout.addView(balanceRow)

        // ── Repayment progress bar ──
        val progressLabel = TextView(context).apply {
            text = "Maendeleo ya Malipo: ${loan.paymentsCompleted}/${loan.totalPayments}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
        contentLayout.addView(progressLabel)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (loan.progress * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(8)
            )
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.loan_progress_drawable)
                ?: ContextCompat.getDrawable(context, android.R.drawable.progress_horizontal)
        }
        contentLayout.addView(progressBar)

        // Animate progress bar
        ObjectAnimator.ofInt(progressBar, "progress", 0, (loan.progress * 100).toInt()).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val progressPercent = TextView(context).apply {
            text = "${(loan.progress * 100).toInt()}%"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
            setPadding(0, dpToPx(2), 0, 0)
        }
        contentLayout.addView(progressPercent)

        // ── Next payment info ──
        if (loan.nextPaymentAmount != null && loan.daysUntilNextPayment != null) {
            val nextPaymentContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(12), 0, 0)
            }

            val nextPaymentLabel = TextView(context).apply {
                text = "Malipo Yajayo:"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            val nextPaymentValue = TextView(context).apply {
                val daysText = when {
                    loan.daysUntilNextPayment < 0 -> " (IMEPITWA!)"
                    loan.daysUntilNextPayment == 0 -> " (LEO!)"
                    loan.daysUntilNextPayment == 1 -> " (KESHO)"
                    else -> " (siku ${loan.daysUntilNextPayment})"
                }
                text = "KSh ${formatAmount(loan.nextPaymentAmount.toInt())}$daysText"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ContextCompat.getColor(context, when {
                    loan.daysUntilNextPayment < 0 -> R.color.error
                    loan.daysUntilNextPayment <= 3 -> R.color.warning
                    else -> R.color.text_primary
                }))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            nextPaymentContainer.addView(nextPaymentLabel)
            nextPaymentContainer.addView(nextPaymentValue)
            contentLayout.addView(nextPaymentContainer)
        }

        // ── ROI section for business loans ──
        if (loan.isBusinessLoan) {
            val roiContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(12), 0, 0)
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant))
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            }

            val roiLabel = TextView(context).apply {
                text = "📊 ROI ya Biashara"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val roiValue = TextView(context).apply {
                val interestCost = loan.totalDue - loan.amount
                val roiText = if (loan.amount > 0) {
                    val roiPercent = ((loan.amount - interestCost) / loan.amount * 100)
                    "Gharama ya riba: KSh ${formatAmount(interestCost.toInt())} | " +
                    "ROI: ${String.format("%.1f", roiPercent)}%"
                } else {
                    "Inakokotoa..."
                }
                text = roiText
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            roiContainer.addView(roiLabel)
            roiContainer.addView(roiValue)
            contentLayout.addView(roiContainer)
        }

        // ── Repayment action button ──
        val repayButton = MaterialButton(context).apply {
            text = "Lipa Sasa"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                showRepaymentDialog(loan)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }
        contentLayout.addView(repayButton)

        card.addView(contentLayout)
        return card
    }

    private fun createRiskBadge(risk: DefaultRisk): TextView {
        val context = requireContext()
        return TextView(context).apply {
            text = risk.labelSw
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
            background = ContextCompat.getDrawable(context, android.R.drawable.editbox_background)
            setBackgroundColor(ContextCompat.getColor(context, when (risk) {
                DefaultRisk.LOW -> R.color.success
                DefaultRisk.MEDIUM -> R.color.warning
                DefaultRisk.HIGH -> R.color.error
                DefaultRisk.CRITICAL -> R.color.holo_red_dark
            }))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun createInfoRow(label: String, value: String, valueColor: Int): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))

            addView(TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, valueColor))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // REPAYMENT SCHEDULE
    // ═══════════════════════════════════════════════════════════════

    private fun updateRepaymentSchedule(items: List<RepaymentItem>) {
        scheduleContainer.removeAllViews()

        if (items.isEmpty()) {
            scheduleHeader.visibility = View.GONE
            return
        }

        scheduleHeader.visibility = View.VISIBLE
        val now = System.currentTimeMillis() / 1000

        items.take(10).forEach { item ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(6), 0, dpToPx(6))
            }

            // Status indicator
            val statusDot = View(requireContext()).apply {
                val size = dpToPx(10)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dpToPx(8)
                }
                background = ContextCompat.getDrawable(requireContext(), android.R.drawable.presence_online)
                setBackgroundColor(ContextCompat.getColor(requireContext(), when (item.status) {
                    "PAID" -> R.color.success
                    "OVERDUE" -> R.color.error
                    "PARTIAL" -> R.color.warning
                    else -> R.color.text_hint
                }))
            }

            // Date
            val dateText = TextView(requireContext()).apply {
                text = formatDate(item.dueDate)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Lender
            val lenderText = TextView(requireContext()).apply {
                text = item.lender
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Amount
            val amountText = TextView(requireContext()).apply {
                text = "KSh ${formatAmount(item.amount.toInt())}"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(requireContext(), when (item.status) {
                    "PAID" -> R.color.success
                    "OVERDUE" -> R.color.error
                    else -> R.color.text_primary
                }))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            row.addView(statusDot)
            row.addView(dateText)
            row.addView(lenderText)
            row.addView(amountText)

            scheduleContainer.addView(row)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FORM SUBMISSION
    // ═══════════════════════════════════════════════════════════════

    private fun submitLoan() {
        val amountStr = amountInput.text.toString().trim()
        val purpose = purposeDropdown.text.toString().trim()
        val lender = lenderInput.text.toString().trim()
        val interestStr = interestInput.text.toString().trim()
        val termStr = termInput.text.toString().trim()

        // Validation
        if (amountStr.isEmpty()) {
            amountInput.error = "Weka kiasi"
            return
        }
        val amount = amountStr.replace(",", "").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            amountInput.error = "Kiasi si sahihi"
            return
        }

        if (purpose.isEmpty()) {
            purposeDropdown.error = "Chagua kusudi"
            return
        }

        if (lender.isEmpty()) {
            lenderInput.error = "Weka jina la mkopaji"
            return
        }

        val interest = interestStr.toDoubleOrNull() ?: 15.0
        val term = termStr.toIntOrNull() ?: 6

        // Normalize purpose to Swahili key
        val purposeKey = when {
            purpose.contains("Biashara", ignoreCase = true) -> "Biashara"
            purpose.contains("Binafsi", ignoreCase = true) -> "Binafsi"
            purpose.contains("Dharura", ignoreCase = true) -> "Dharura"
            purpose.contains("Elimu", ignoreCase = true) -> "Elimu"
            else -> purpose
        }

        viewModel.recordLoan(amount, purposeKey, lender, interest, term)

        // Clear form
        amountInput.text.clear()
        purposeDropdown.text.clear()
        lenderInput.text.clear()
        interestInput.text.clear()
        termInput.text.clear()
    }

    private fun showRepaymentDialog(loan: LoanCardData) {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = "Kiasi cha malipo (KSh)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        if (loan.nextPaymentAmount != null) {
            input.setText("${loan.nextPaymentAmount.toInt()}")
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Lipa Mkopo wa ${loan.lender}")
            .setMessage("Salio: KSh ${formatAmount(loan.balance.toInt())}")
            .setView(input)
            .setPositiveButton("Lipa") { _, _ ->
                val amount = input.text.toString().replace(",", "").toDoubleOrNull()
                if (amount != null && amount > 0) {
                    viewModel.recordRepayment(loan.id, amount)
                } else {
                    Toast.makeText(context, "Kiasi si sahihi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Ghairi", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun formatAmount(amount: Int): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,d", amount)
        } else {
            amount.toString()
        }
    }

    private fun formatDate(timestamp: Long): String {
        return try {
            val date = Instant.ofEpochSecond(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            date.format(DateTimeFormatter.ofPattern("dd MMM"))
        } catch (e: Throwable) {
            "—"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
