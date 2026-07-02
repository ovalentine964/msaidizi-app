package com.msaidizi.app.ui.record

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.msaidizi.app.R
import com.msaidizi.app.core.language.CalibrationAction
import com.msaidizi.app.core.language.CalibratedConfidence

/**
 * Shows pronunciation feedback when STT confidence is low.
 *
 * Uses confidence-based routing (STA 341 Estimation):
 * - High confidence (>0.85): Accept directly — view stays hidden
 * - Medium confidence (0.6–0.85): Show confirmation card
 *   "Niliskia 'biashara', sahihi?" (I heard 'biashara', correct?)
 * - Low confidence (<0.6): Show alternatives card
 *   "Je, ulisema 'mazungumzo' au 'mazungumza'?" (Did you say X or Y?)
 *
 * Animated: slides up from bottom with spring physics.
 * Tracks corrections for federated learning via [onCorrection] callback.
 */
class PronunciationFeedbackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    // ── Views ──────────────────────────────────────────────────────────
    private val feedbackIcon: TextView
    private val feedbackTitle: TextView
    private val feedbackMessage: TextView
    private val heardText: TextView
    private val expectedLabel: TextView
    private val expectedText: TextView
    private val correctionInput: EditText
    private val confirmButton: Button
    private val rejectButton: Button
    private val alternativeContainer: LinearLayout
    private val alternativeButtons: List<Button>

    // ── State ──────────────────────────────────────────────────────────
    private var currentTranscription: String = ""
    private var currentConfidence: CalibratedConfidence? = null
    private var feedbackMode: FeedbackMode = FeedbackMode.HIDDEN

    // ── Callbacks ──────────────────────────────────────────────────────
    /** Called when user confirms the transcription was correct */
    var onConfirmed: ((transcription: String) -> Unit)? = null

    /** Called when user provides a correction */
    var onCorrection: ((original: String, corrected: String) -> Unit)? = null

    /** Called when user selects an alternative */
    var onAlternativeSelected: ((alternative: String) -> Unit)? = null

    /** Called when feedback is dismissed */
    var onDismissed: (() -> Unit)? = null

    init {
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.view_pronunciation_feedback, this, true)

        // Bind views
        feedbackIcon = findViewById(R.id.feedback_icon)
        feedbackTitle = findViewById(R.id.feedback_title)
        feedbackMessage = findViewById(R.id.feedback_message)
        heardText = findViewById(R.id.heard_text)
        expectedLabel = findViewById(R.id.expected_label)
        expectedText = findViewById(R.id.expected_text)
        correctionInput = findViewById(R.id.correction_input)
        confirmButton = findViewById(R.id.btn_confirm)
        rejectButton = findViewById(R.id.btn_reject)
        alternativeContainer = findViewById(R.id.alternative_container)
        alternativeButtons = listOf(
            findViewById(R.id.btn_alt_1),
            findViewById(R.id.btn_alt_2),
            findViewById(R.id.btn_alt_3)
        )

        setupClickListeners()

        // Start hidden below screen
        translationY = 500f
        alpha = 0f
        visibility = View.GONE
    }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Show pronunciation feedback based on ASR confidence.
     *
     * @param transcription What the ASR system heard
     * @param confidence Calibrated confidence from [ConfidenceCalibrator]
     * @param expectedText Optional: what the system expected (for comparison)
     * @param alternatives Optional: alternative transcriptions the ASR considered
     */
    fun showFeedback(
        transcription: String,
        confidence: CalibratedConfidence,
        expectedText: String? = null,
        alternatives: List<String> = emptyList()
    ) {
        currentTranscription = transcription
        currentConfidence = confidence

        when (confidence.action) {
            CalibrationAction.ACCEPT, CalibrationAction.ACCEPT_AND_LOG -> {
                // High confidence — no feedback needed, just dismiss
                dismiss()
                return
            }
            CalibrationAction.CONFIRM -> {
                showConfirmationMode(transcription, expectedText)
            }
            CalibrationAction.REJECT -> {
                showRejectMode(transcription, alternatives)
            }
        }

        animateIn()
    }

    /**
     * Show feedback with custom message (for manual triggers).
     */
    fun showCustomFeedback(
        title: String,
        message: String,
        transcription: String,
        showCorrectionInput: Boolean = true
    ) {
        currentTranscription = transcription

        feedbackIcon.text = "🎤"
        feedbackTitle.text = title
        feedbackMessage.text = message
        heardText.text = "\"$transcription\""
        heardText.visibility = View.VISIBLE
        expectedLabel.visibility = View.GONE
        expectedText.visibility = View.GONE

        // Show/hide correction input
        correctionInput.visibility = if (showCorrectionInput) View.VISIBLE else View.GONE
        if (showCorrectionInput) {
            correctionInput.text.clear()
            correctionInput.hint = context.getString(R.string.pronunciation_correction_hint)
        }

        // Show confirm/reject buttons
        confirmButton.text = context.getString(R.string.pronunciation_yes_correct)
        rejectButton.text = context.getString(R.string.pronunciation_no_wrong)
        confirmButton.visibility = View.VISIBLE
        rejectButton.visibility = View.VISIBLE

        // Hide alternatives
        alternativeContainer.visibility = View.GONE

        feedbackMode = FeedbackMode.CUSTOM
        animateIn()
    }

    /**
     * Dismiss the feedback card with animation.
     */
    fun dismiss() {
        animateOut()
    }

    /**
     * Is the feedback view currently visible?
     */
    fun isShowingFeedback(): Boolean = visibility == View.VISIBLE && feedbackMode != FeedbackMode.HIDDEN

    // ════════════════════════════════════════════════════════════════════
    // MODE SETUP
    // ════════════════════════════════════════════════════════════════════

    /**
     * Confirmation mode: medium confidence (0.6–0.85).
     * "Niliskia 'biashara', sahihi?"
     */
    private fun showConfirmationMode(transcription: String, expected: String?) {
        feedbackMode = FeedbackMode.CONFIRM

        feedbackIcon.text = "🤔"
        feedbackTitle.text = context.getString(R.string.pronunciation_confirm_title)
        feedbackMessage.text = context.getString(R.string.pronunciation_confirm_message, transcription)

        heardText.text = "\"$transcription\""
        heardText.visibility = View.VISIBLE

        if (expected != null && expected != transcription) {
            expectedLabel.text = context.getString(R.string.pronunciation_expected_label)
            expectedLabel.visibility = View.VISIBLE
            expectedText.text = "\"$expected\""
            expectedText.visibility = View.VISIBLE
        } else {
            expectedLabel.visibility = View.GONE
            expectedText.visibility = View.GONE
        }

        // Show correction input for "No, I said..."
        correctionInput.visibility = View.VISIBLE
        correctionInput.text.clear()
        correctionInput.hint = context.getString(R.string.pronunciation_correction_hint)

        // Button setup
        confirmButton.text = context.getString(R.string.pronunciation_yes_correct)
        rejectButton.text = context.getString(R.string.pronunciation_submit_correction)
        confirmButton.visibility = View.VISIBLE
        rejectButton.visibility = View.VISIBLE

        // Hide alternatives
        alternativeContainer.visibility = View.GONE
    }

    /**
     * Reject mode: low confidence (<0.6).
     * Shows alternatives or asks user to type what they said.
     */
    private fun showRejectMode(transcription: String, alternatives: List<String>) {
        feedbackMode = FeedbackMode.REJECT

        feedbackIcon.text = "❓"
        feedbackTitle.text = context.getString(R.string.pronunciation_reject_title)
        feedbackMessage.text = context.getString(R.string.pronunciation_reject_message)

        heardText.text = "\"$transcription\""
        heardText.visibility = View.VISIBLE
        expectedLabel.visibility = View.GONE
        expectedText.visibility = View.GONE

        // Show correction input
        correctionInput.visibility = View.VISIBLE
        correctionInput.text.clear()
        correctionInput.hint = context.getString(R.string.pronunciation_what_did_you_say)

        // Show alternatives if available
        if (alternatives.isNotEmpty()) {
            alternativeContainer.visibility = View.VISIBLE
            for ((index, button) in alternativeButtons.withIndex()) {
                if (index < alternatives.size) {
                    button.text = alternatives[index]
                    button.visibility = View.VISIBLE
                } else {
                    button.visibility = View.GONE
                }
            }
        } else {
            alternativeContainer.visibility = View.GONE
        }

        // Button setup
        confirmButton.text = context.getString(R.string.pronunciation_submit_correction)
        rejectButton.text = context.getString(R.string.pronunciation_try_again)
        confirmButton.visibility = View.VISIBLE
        rejectButton.visibility = View.VISIBLE
    }

    // ════════════════════════════════════════════════════════════════════
    // CLICK HANDLERS
    // ════════════════════════════════════════════════════════════════════

    private fun setupClickListeners() {
        // Confirm button — "Yes, that's correct" or "Submit correction"
        confirmButton.setOnClickListener {
            when (feedbackMode) {
                FeedbackMode.CONFIRM -> {
                    val correction = correctionInput.text.toString().trim()
                    if (correction.isNotEmpty()) {
                        // User typed a correction
                        onCorrection?.invoke(currentTranscription, correction)
                    } else {
                        // User confirmed it's correct
                        onConfirmed?.invoke(currentTranscription)
                    }
                    dismiss()
                }
                FeedbackMode.REJECT, FeedbackMode.CUSTOM -> {
                    val correction = correctionInput.text.toString().trim()
                    if (correction.isNotEmpty()) {
                        onCorrection?.invoke(currentTranscription, correction)
                        dismiss()
                    }
                    // If empty, do nothing — user needs to type something
                }
                FeedbackMode.HIDDEN -> { /* no-op */ }
            }
        }

        // Reject button — "No, wrong" or "Try again"
        rejectButton.setOnClickListener {
            when (feedbackMode) {
                FeedbackMode.CONFIRM -> {
                    // User says it's wrong — focus on correction input
                    correctionInput.requestFocus()
                    showKeyboard()
                }
                FeedbackMode.REJECT -> {
                    // Try again — dismiss and let user speak again
                    onDismissed?.invoke()
                    dismiss()
                }
                FeedbackMode.CUSTOM -> {
                    onDismissed?.invoke()
                    dismiss()
                }
                FeedbackMode.HIDDEN -> { /* no-op */ }
            }
        }

        // Alternative buttons
        for ((index, button) in alternativeButtons.withIndex()) {
            button.setOnClickListener {
                val selectedText = button.text.toString()
                onAlternativeSelected?.invoke(selectedText)
                dismiss()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ANIMATIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Slide up from bottom with spring physics.
     */
    private fun animateIn() {
        visibility = View.VISIBLE

        val slideUp = ObjectAnimator.ofFloat(this, "translationY", 500f, 0f).apply {
            duration = 400
            interpolator = OvershootInterpolator(0.6f)
        }

        val fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply {
            duration = 300
        }

        AnimatorSet().apply {
            playTogether(slideUp, fadeIn)
            start()
        }
    }

    /**
     * Slide down and fade out.
     */
    private fun animateOut() {
        val slideDown = ObjectAnimator.ofFloat(this, "translationY", 0f, 500f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }

        val fadeOut = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
            duration = 250
        }

        AnimatorSet().apply {
            playTogether(slideDown, fadeOut)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = View.GONE
                    feedbackMode = FeedbackMode.HIDDEN
                }
            })
            start()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(correctionInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    // ════════════════════════════════════════════════════════════════════
    // ENUMS
    // ════════════════════════════════════════════════════════════════════

    enum class FeedbackMode {
        HIDDEN,
        CONFIRM,  // Medium confidence — "Did I hear this right?"
        REJECT,   // Low confidence — "What did you say?"
        CUSTOM    // Manual trigger with custom message
    }
}
