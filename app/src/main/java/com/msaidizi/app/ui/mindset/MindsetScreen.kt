package com.msaidizi.app.ui.mindset

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.msaidizi.app.R
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Mindset Academy screen — daily lessons, rich habits, and affirmations.
 *
 * Features:
 * - Daily lesson display with voice playback
 * - Rich habits score (circular gauge 0-100)
 * - Daily affirmation (bilingual Swahili/English)
 * - Habit stacking formula (visual timeline)
 * - Module progress (5 categories with completion status)
 * - Voice-first interface
 *
 * All labels in Swahili for semi-literate informal workers.
 */
@AndroidEntryPoint
class MindsetFragment : Fragment() {

    private val viewModel: MindsetViewModel by viewModels()

    // ── Daily lesson ──
    private lateinit var lessonCard: MaterialCardView
    private lateinit var lessonCategoryTag: TextView
    private lateinit var lessonTitle: TextView
    private lateinit var lessonSource: TextView
    private lateinit var lessonDuration: TextView
    private lateinit var lessonPlayButton: MaterialButton
    private lateinit var lessonCompleteButton: MaterialButton

    // ── Score gauge ──
    private lateinit var scoreGaugeContainer: FrameLayout
    private lateinit var scoreGaugeView: CircularScoreGauge
    private lateinit var scoreLabel: TextView
    private lateinit var peerComparisonText: TextView

    // ── Affirmation ──
    private lateinit var affirmationCard: MaterialCardView
    private lateinit var affirmationEmoji: TextView
    private lateinit var affirmationTextSw: TextView
    private lateinit var affirmationTextEn: TextView
    private lateinit var affirmationScoreContext: TextView

    // ── Habit list ──
    private lateinit var habitsContainer: LinearLayout
    private lateinit var habitsHeader: TextView

    // ── Habit formula ──
    private lateinit var formulaCard: MaterialCardView
    private lateinit var formulaTitle: TextView
    private lateinit var formulaSubtitle: TextView
    private lateinit var formulaStepsContainer: LinearLayout
    private lateinit var formulaNextStep: TextView

    // ── Modules ──
    private lateinit var modulesContainer: LinearLayout
    private lateinit var modulesHeader: TextView

    // ── Loading ──
    private lateinit var loadingIndicator: View

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_mindset, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsHelper = AccessibilityTtsHelper(requireContext())
        setupViews(view)
        setupClickListeners()
        observeState()
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════

    @Suppress("UNCHECKED_CAST")
    private fun setupViews(view: View) {
        // Daily lesson
        lessonCard = view.findViewById(R.id.lesson_card)
        lessonCategoryTag = view.findViewById(R.id.lesson_category_tag)
        lessonTitle = view.findViewById(R.id.lesson_title)
        lessonSource = view.findViewById(R.id.lesson_source)
        lessonDuration = view.findViewById(R.id.lesson_duration)
        lessonPlayButton = view.findViewById(R.id.btn_play_lesson)
        lessonCompleteButton = view.findViewById(R.id.btn_complete_lesson)

        // Score gauge — use the custom view from layout, or create programmatically
        scoreGaugeContainer = view.findViewById(R.id.score_gauge_container)
        scoreLabel = view.findViewById(R.id.score_label)
        peerComparisonText = view.findViewById(R.id.peer_comparison_text)

        // Create and add the circular gauge
        scoreGaugeView = CircularScoreGauge(requireContext())
        scoreGaugeContainer.addView(scoreGaugeView, 0)

        // Affirmation
        affirmationCard = view.findViewById(R.id.affirmation_card)
        affirmationEmoji = view.findViewById(R.id.affirmation_emoji)
        affirmationTextSw = view.findViewById(R.id.affirmation_text_sw)
        affirmationTextEn = view.findViewById(R.id.affirmation_text_en)
        affirmationScoreContext = view.findViewById(R.id.affirmation_score_context)

        // Habits
        habitsContainer = view.findViewById(R.id.habits_container)
        habitsHeader = view.findViewById(R.id.habits_header)

        // Habit formula
        formulaCard = view.findViewById(R.id.formula_card)
        formulaTitle = view.findViewById(R.id.formula_title)
        formulaSubtitle = view.findViewById(R.id.formula_subtitle)
        formulaStepsContainer = view.findViewById(R.id.formula_steps_container)
        formulaNextStep = view.findViewById(R.id.formula_next_step)

        // Modules
        modulesContainer = view.findViewById(R.id.modules_container)
        modulesHeader = view.findViewById(R.id.modules_header)

        // Loading
        loadingIndicator = view.findViewById(R.id.loading_indicator)
    }

    private fun setupClickListeners() {
        lessonPlayButton.setOnClickListener {
            viewModel.deliverLesson()
            // ACCESSIBILITY: Speak lesson title when play is pressed
            val lesson = viewModel.uiState.value.dailyLesson
            if (lesson != null) {
                ttsHelper?.speak("Somo la leo: ${lesson.title}. Kutoka kitabu: ${lesson.sourceBook}")
            }
        }

        lessonCompleteButton.setOnClickListener {
            viewModel.completeLesson()
        }
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

    private fun updateUI(state: MindsetUiState) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Daily lesson
        updateLessonCard(state)

        // Score gauge
        scoreGaugeView.setScore(state.todayScore)
        scoreLabel.text = "Alama ya Leo: ${state.todayScore}/100"
        peerComparisonText.text = state.peerComparison

        // Affirmation
        updateAffirmation(state.affirmation)

        // Habits
        updateHabits(state.habits)

        // Habit formula
        updateFormula(state.habitFormula)

        // Modules
        updateModules(state.modules)

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
    // LESSON CARD
    // ═══════════════════════════════════════════════════════════════

    private fun updateLessonCard(state: MindsetUiState) {
        val lesson = state.dailyLesson
        if (lesson == null) {
            lessonCard.visibility = View.GONE
            return
        }

        lessonCard.visibility = View.VISIBLE
        lessonCategoryTag.text = "${getCategoryEmoji(lesson.category)} ${lesson.categorySw}"
        lessonTitle.text = lesson.title
        lessonSource.text = "📖 Kutoka: '${lesson.sourceBook}'"
        lessonDuration.text = "⏱ Dakika ${lesson.durationSeconds / 60}"

        // Update button states based on playback
        if (state.isLessonPlaying) {
            lessonPlayButton.text = "🔊 Inacheza..."
            lessonPlayButton.isEnabled = false
            lessonCompleteButton.visibility = View.VISIBLE
        } else {
            lessonPlayButton.text = "▶️ Sikiliza Somo"
            lessonPlayButton.isEnabled = true
            lessonCompleteButton.visibility = View.GONE
        }
    }

    private fun getCategoryEmoji(category: String): String {
        return when (category) {
            "HABITS" -> "🔄"
            "GOALS" -> "🎯"
            "FINANCIAL_LITERACY" -> "💰"
            "MINDSET" -> "🧠"
            "GIVING" -> "🤝"
            else -> "📚"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AFFIRMATION
    // ═══════════════════════════════════════════════════════════════

    private fun updateAffirmation(affirmation: DailyAffirmation?) {
        if (affirmation == null) {
            affirmationCard.visibility = View.GONE
            return
        }

        affirmationCard.visibility = View.VISIBLE
        affirmationEmoji.text = affirmation.emoji
        affirmationTextSw.text = affirmation.textSw
        affirmationTextEn.text = affirmation.textEn
        affirmationScoreContext.text = affirmation.scoreContext
    }

    // ═══════════════════════════════════════════════════════════════
    // HABITS LIST
    // ═══════════════════════════════════════════════════════════════

    private fun updateHabits(habits: List<HabitData>) {
        habitsContainer.removeAllViews()

        val completedCount = habits.count { it.completed }
        habitsHeader.text = "Tabia za Leo ($completedCount/${habits.size})"

        habits.forEach { habit ->
            val habitRow = createHabitRow(habit)
            habitsContainer.addView(habitRow)
        }
    }

    private fun createHabitRow(habit: HabitData): MaterialCardView {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            cardElevation = dpToPx(1).toFloat()
            radius = dpToPx(8).toFloat()
            setCardBackgroundColor(
                ContextCompat.getColor(context,
                    if (habit.completed) R.color.surface_variant else R.color.surface
                )
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                viewModel.toggleHabit(habit.id)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        // Checkbox-style indicator
        val checkbox = TextView(context).apply {
            text = if (habit.completed) "✅" else "⬜"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dpToPx(10), 0)
        }

        // Emoji
        val emoji = TextView(context).apply {
            text = habit.emoji
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, dpToPx(8), 0)
        }

        // Name
        val name = TextView(context).apply {
            text = habit.name
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context,
                if (habit.completed) R.color.text_secondary else R.color.text_primary
            ))
            if (habit.completed) {
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(checkbox)
        row.addView(emoji)
        row.addView(name)

        card.addView(row)
        return card
    }

    // ═══════════════════════════════════════════════════════════════
    // HABIT FORMULA (VISUAL TIMELINE)
    // ═══════════════════════════════════════════════════════════════

    private fun updateFormula(formula: HabitFormula?) {
        if (formula == null) {
            formulaCard.visibility = View.GONE
            return
        }

        formulaCard.visibility = View.VISIBLE
        formulaTitle.text = formula.title
        formulaSubtitle.text = formula.subtitle

        formulaStepsContainer.removeAllViews()
        formula.steps.forEach { step ->
            val stepView = createFormulaStep(step, formula.steps.size)
            formulaStepsContainer.addView(stepView)
        }

        // Next step hint
        if (formula.nextStep != null) {
            formulaNextStep.text = "Linalofuata: ${formula.nextStep}"
            formulaNextStep.visibility = View.VISIBLE
        } else {
            formulaNextStep.text = "🎉 Tabia zote zimekamilika leo!"
            formulaNextStep.visibility = View.VISIBLE
        }
    }

    private fun createFormulaStep(step: FormulaStep, totalSteps: Int): LinearLayout {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }

        // Timeline dot + line
        val timelineContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dot = View(context).apply {
            val size = dpToPx(12)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
            setBackgroundColor(ContextCompat.getColor(context,
                if (step.completed) R.color.success else R.color.text_hint
            ))
        }

        timelineContainer.addView(dot)

        // Connector line (except last)
        if (step.order < totalSteps) {
            val line = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(2), dpToPx(16))
                setBackgroundColor(ContextCompat.getColor(context,
                    if (step.completed) R.color.success else R.color.divider
                ))
            }
            timelineContainer.addView(line)
        }

        // Step content
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val emojiText = TextView(context).apply {
            text = step.emoji
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, dpToPx(6), 0)
        }

        val nameText = TextView(context).apply {
            text = "${step.order}. ${step.name}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context,
                if (step.completed) R.color.text_secondary else R.color.text_primary
            ))
            if (step.completed) {
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
        }

        val statusText = TextView(context).apply {
            text = if (step.completed) "✅" else "→"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(8), 0, 0, 0)
        }

        contentContainer.addView(emojiText)
        contentContainer.addView(nameText)
        contentContainer.addView(statusText)

        container.addView(timelineContainer)
        container.addView(contentContainer)

        return container
    }

    // ═══════════════════════════════════════════════════════════════
    // MODULES
    // ═══════════════════════════════════════════════════════════════

    private fun updateModules(modules: List<ModuleData>) {
        modulesContainer.removeAllViews()

        val totalCompleted = modules.sumOf { it.completedLessons }
        val totalLessons = modules.sumOf { it.totalLessons }
        modulesHeader.text = "Moduli ($totalCompleted/$totalLessons)"

        modules.forEach { module ->
            val moduleView = createModuleCard(module)
            modulesContainer.addView(moduleView)
        }
    }

    private fun createModuleCard(module: ModuleData): MaterialCardView {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            cardElevation = dpToPx(2).toFloat()
            radius = dpToPx(12).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }

        val padding = dpToPx(14)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Header row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val emojiText = TextView(context).apply {
            text = module.emoji
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, 0, dpToPx(10), 0)
        }

        val nameContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(context).apply {
            text = module.name
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }

        val descText = TextView(context).apply {
            text = module.description
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dpToPx(2), 0, 0)
        }

        nameContainer.addView(nameText)
        nameContainer.addView(descText)

        val completionBadge = TextView(context).apply {
            text = if (module.isCompleted) "✅" else "${module.completedLessons}/${module.totalLessons}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context,
                if (module.isCompleted) R.color.success else R.color.text_secondary
            ))
            typeface = Typeface.DEFAULT_BOLD
        }

        headerRow.addView(emojiText)
        headerRow.addView(nameContainer)
        headerRow.addView(completionBadge)
        content.addView(headerRow)

        // Progress bar
        val progressBar = LinearProgressIndicator(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(6)
            ).apply {
                topMargin = dpToPx(10)
            }
            max = 100
            setProgressCompat(module.progressPercent, true)
            setIndicatorColor(ContextCompat.getColor(context,
                if (module.isCompleted) R.color.success else R.color.primary
            ))
            trackColor = ContextCompat.getColor(context, R.color.surface_variant)
        }
        content.addView(progressBar)

        // Progress text
        val progressText = TextView(context).apply {
            text = "${module.progressPercent}% imekamilika"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            gravity = android.view.Gravity.END
            setPadding(0, dpToPx(4), 0, 0)
        }
        content.addView(progressText)

        card.addView(content)
        return card
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

// ═══════════════════════════════════════════════════════════════
// CUSTOM VIEW: Circular Score Gauge
// ═══════════════════════════════════════════════════════════════

/**
 * Animated circular gauge that displays a score from 0 to 100.
 * Used for the Rich Habits daily score visualization.
 *
 * Features:
 * - Smooth animation on score change
 * - Color-coded: red (0-30), orange (31-60), green (61-100)
 * - Large center number
 * - Label text below
 */
class CircularScoreGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentScore: Float = 0f
    private var targetScore: Float = 0f
    private var animator: ValueAnimator? = null

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        textSize = 72f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF757575.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    init {
        // Set a reasonable default size
        minimumWidth = dpToPx(180)
        minimumHeight = dpToPx(180)
    }

    fun setScore(score: Int) {
        val newTarget = score.toFloat().coerceIn(0f, 100f)
        if (newTarget == targetScore) return

        targetScore = newTarget

        animator?.cancel()
        animator = ValueAnimator.ofFloat(currentScore, targetScore).apply {
            duration = 1000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                currentScore = animation.animatedValue as Float
                updateProgressColor()
                invalidate()
            }
            start()
        }
    }

    private fun updateProgressColor() {
        progressPaint.color = when {
            currentScore <= 30 -> 0xFFF44336.toInt()  // Red
            currentScore <= 60 -> 0xFFFF9800.toInt()  // Orange
            else -> 0xFF4CAF50.toInt()                 // Green
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dpToPx(180)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) - backgroundPaint.strokeWidth

        // Draw background arc
        rect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        canvas.drawArc(rect, 135f, 270f, false, backgroundPaint)

        // Draw progress arc
        val sweepAngle = (currentScore / 100f) * 270f
        canvas.drawArc(rect, 135f, sweepAngle, false, progressPaint)

        // Draw score text
        val scoreText = currentScore.toInt().toString()
        canvas.drawText(scoreText, centerX, centerY + 20f, textPaint)

        // Draw label
        canvas.drawText("/ 100", centerX, centerY + 55f, labelPaint)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}


