package com.msaidizi.app.ui.goals

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
import com.msaidizi.app.finance.GoalPlanner
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Goal Planning & Achievement screen.
 *
 * Features:
 * - Voice-first goal creation with prominent microphone button
 * - Goal progress visualization (circular progress with milestones)
 * - Time-to-goal prediction display
 * - Active goals list with progress cards
 * - Celebration animation on milestone completion
 * - All labels in Swahili for semi-literate workers
 */
@AndroidEntryPoint
class GoalFragment : Fragment() {

    private val viewModel: GoalViewModel by viewModels()

    // Overall summary
    private lateinit var overallProgressCard: MaterialCardView
    private lateinit var overallProgressText: TextView
    private lateinit var overallProgressBar: ProgressBar
    private lateinit var totalSavedText: TextView
    private lateinit var totalTargetText: TextView
    private lateinit var activeCountText: TextView

    // Voice creation
    private lateinit var micButton: FloatingActionButton
    private lateinit var micStatusText: TextView

    // Create form
    private lateinit var createFormCard: MaterialCardView
    private lateinit var toggleCreateButton: MaterialButton
    private lateinit var goalNameInput: EditText
    private lateinit var goalAmountInput: EditText
    private lateinit var goalCategorySpinner: Spinner
    private lateinit var goalDeadlineInput: EditText
    private lateinit var createSubmitButton: MaterialButton

    // Active goals
    private lateinit var activeGoalsRecycler: RecyclerView
    private lateinit var activeGoalsEmptyText: TextView

    // Completed goals
    private lateinit var completedSection: MaterialCardView
    private lateinit var completedRecycler: RecyclerView
    private lateinit var completedEmptyText: TextView

    // Confirmation / celebration
    private lateinit var celebrationCard: MaterialCardView
    private lateinit var celebrationText: TextView
    private lateinit var celebrationEmoji: TextView

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
        return inflater.inflate(R.layout.fragment_goals, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsHelper = AccessibilityTtsHelper(requireContext())
        setupViews(view)
        setupCategorySpinner()
        observeState()
    }

    private fun setupViews(view: View) {
        // Overall summary
        overallProgressCard = view.findViewById(R.id.overall_progress_card)
        overallProgressText = view.findViewById(R.id.overall_progress_text)
        overallProgressBar = view.findViewById(R.id.overall_progress_bar)
        totalSavedText = view.findViewById(R.id.total_saved_text)
        totalTargetText = view.findViewById(R.id.total_target_text)
        activeCountText = view.findViewById(R.id.active_count_text)

        // Voice
        micButton = view.findViewById(R.id.mic_button)
        micStatusText = view.findViewById(R.id.mic_status_text)

        // Create form
        createFormCard = view.findViewById(R.id.create_form_card)
        toggleCreateButton = view.findViewById(R.id.toggle_create_button)
        goalNameInput = view.findViewById(R.id.goal_name_input)
        goalAmountInput = view.findViewById(R.id.goal_amount_input)
        goalCategorySpinner = view.findViewById(R.id.goal_category_spinner)
        goalDeadlineInput = view.findViewById(R.id.goal_deadline_input)
        createSubmitButton = view.findViewById(R.id.create_submit_button)

        // Active goals
        activeGoalsRecycler = view.findViewById(R.id.active_goals_recycler)
        activeGoalsEmptyText = view.findViewById(R.id.active_goals_empty_text)

        // Completed goals
        completedSection = view.findViewById(R.id.completed_section)
        completedRecycler = view.findViewById(R.id.completed_recycler)
        completedEmptyText = view.findViewById(R.id.completed_empty_text)

        // Celebration
        celebrationCard = view.findViewById(R.id.celebration_card)
        celebrationText = view.findViewById(R.id.celebration_text)
        celebrationEmoji = view.findViewById(R.id.celebration_emoji)

        // Error
        errorCard = view.findViewById(R.id.error_card)
        errorText = view.findViewById(R.id.error_text)

        // Setup RecyclerViews
        activeGoalsRecycler.layoutManager = LinearLayoutManager(requireContext())
        completedRecycler.layoutManager = LinearLayoutManager(requireContext())

        // Mic button
        micButton.setOnClickListener {
            if (isRecording) {
                stopVoiceRecording()
            } else {
                startVoiceRecording()
            }
        }

        // Toggle create form
        toggleCreateButton.setOnClickListener {
            viewModel.toggleCreateForm()
        }

        // Submit create form
        createSubmitButton.setOnClickListener {
            submitCreateForm()
        }

        // Dismiss celebration
        celebrationCard.setOnClickListener {
            viewModel.clearConfirmation()
        }

        // Dismiss error
        errorCard.setOnClickListener {
            viewModel.clearError()
        }
    }

    private fun setupCategorySpinner() {
        val categories = GoalPlanner.GoalCategory.entries.map { it.swahiliName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        goalCategorySpinner.adapter = adapter
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

    private fun updateUI(state: GoalUiState) {
        // Overall summary
        val progressPercent = (state.overallProgress * 100).toInt()
        overallProgressText.text = "$progressPercent%"
        overallProgressBar.progress = progressPercent
        totalSavedText.text = "KSh ${formatAmount(state.totalSaved)}"
        totalTargetText.text = "Lengo: KSh ${formatAmount(state.totalTarget)}"
        activeCountText.text = "Malengo ${state.activeCount}"

        // Voice state
        isRecording = state.isProcessing.not() && isRecording
        micButton.isEnabled = !state.isProcessing

        if (state.isProcessing) {
            micStatusText.text = "⏳ Inachakata…"
        } else if (isRecording) {
            micStatusText.text = "🎤 Inasikiliza… Sema lengo lako"
        } else {
            micStatusText.text = "Gusa kurekodi — \"Ninataka kununua friji\""
        }

        // Create form visibility
        createFormCard.visibility = if (state.showCreateForm) View.VISIBLE else View.GONE
        toggleCreateButton.text = if (state.showCreateForm) "Funga" else "➕ Lengo Jipya"

        // Active goals
        if (state.activeGoalDetails.isEmpty()) {
            activeGoalsRecycler.visibility = View.GONE
            activeGoalsEmptyText.visibility = View.VISIBLE
            activeGoalsEmptyText.text = "Huna malengo bado. Unda lengo lako la kwanza! 🎯"
        } else {
            activeGoalsRecycler.visibility = View.VISIBLE
            activeGoalsEmptyText.visibility = View.GONE
            activeGoalsRecycler.adapter = ActiveGoalAdapter(
                goals = state.activeGoalDetails,
                progressInputGoalId = state.progressInputGoalId,
                onProgressClick = { goalId -> viewModel.toggleProgressInput(goalId) },
                onCompleteClick = { goalId -> viewModel.completeGoal(goalId) },
                onAbandonClick = { goalId -> viewModel.abandonGoal(goalId) },
                onSubmitProgress = { goalId, amount ->
                    viewModel.updateProgress(goalId, amount)
                }
            )
        }

        // Completed goals
        if (state.completedGoals.isEmpty()) {
            completedSection.visibility = View.GONE
        } else {
            completedSection.visibility = View.VISIBLE
            completedRecycler.adapter = CompletedGoalAdapter(state.completedGoals)
        }

        // Celebration — spoken aloud for accessibility
        if (state.confirmationMessage != null) {
            celebrationCard.visibility = View.VISIBLE
            celebrationText.text = state.confirmationMessage
            ttsHelper?.speakSuccess(state.confirmationMessage!!)

            // Set celebration emoji based on milestone
            celebrationEmoji.text = when (state.celebratingMilestone) {
                0.25 -> "🌱"
                0.50 -> "🔥"
                0.75 -> "⭐"
                1.0 -> "🎉🏆"
                else -> "👏"
            }

            // Celebration animation
            celebrateMilestone(celebrationCard, state.celebratingMilestone ?: 0.0)

            // Auto-hide after 6 seconds
            viewLifecycleOwner.lifecycleScope.launch {
                delay(6000)
                viewModel.clearConfirmation()
            }
        } else {
            celebrationCard.visibility = View.GONE
        }

        // Error — spoken aloud for accessibility
        if (state.error != null) {
            errorCard.visibility = View.VISIBLE
            errorText.text = state.error
            ttsHelper?.speakError(state.error!!)
        } else {
            errorCard.visibility = View.GONE
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
        isRecording = true
        viewModel.toggleCreateForm() // Show form as fallback
        micStatusText.text = "🎤 Inasikiliza… Sema lengo lako"
        startPulseAnimation(micButton)
        // In production, this would start the Whisper pipeline
    }

    /**
     * Stop voice recording.
     */
    private fun stopVoiceRecording() {
        isRecording = false
        stopPulseAnimation(micButton)
        micStatusText.text = "Gusa kurekodi — \"Ninataka kununua friji\""
    }

    /**
     * Process voice input for goal creation (called from voice pipeline).
     */
    fun processVoiceInput(text: String) {
        // Parse voice input and create goal
        // Example: "Ninataka kununua friji KSh 25000"
        val amount = extractAmountFromText(text)
        if (amount != null && amount > 0) {
            viewModel.createGoalFromVoice(
                description = text,
                targetAmount = amount,
                deadline = System.currentTimeMillis() / 1000 + (90 * 86400) // Default 3 months
            )
        } else {
            // Show form for manual entry with pre-filled description
            goalNameInput.setText(text)
            viewModel.toggleCreateForm()
        }
    }

    /**
     * Submit the create goal form.
     */
    private fun submitCreateForm() {
        val name = goalNameInput.text.toString().trim()
        val amountStr = goalAmountInput.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        val categoryIndex = goalCategorySpinner.selectedItemPosition
        val category = GoalPlanner.GoalCategory.entries[categoryIndex].name
        val deadlineDaysStr = goalDeadlineInput.text.toString().trim()
        val deadlineDays = deadlineDaysStr.toIntOrNull() ?: 90

        if (name.isBlank()) {
            goalNameInput.error = "Andika jina la lengo"
            return
        }

        if (amount == null || amount <= 0) {
            goalAmountInput.error = "Weka kiasi sahihi"
            return
        }

        viewModel.createGoalManually(name, amount, category, deadlineDays)

        // Clear form
        goalNameInput.text.clear()
        goalAmountInput.text.clear()
        goalDeadlineInput.text.clear()
    }

    /**
     * Celebration animation for milestone achievement.
     * More dramatic for higher milestones.
     */
    private fun celebrateMilestone(view: View, milestone: Double) {
        val scale = when {
            milestone >= 1.0 -> 1.25f
            milestone >= 0.75 -> 1.15f
            milestone >= 0.50 -> 1.10f
            else -> 1.05f
        }

        view.alpha = 0f
        view.scaleX = 0.5f
        view.scaleY = 0.5f

        view.animate()
            .alpha(1f)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start()
            }
            .start()

        // For 100% completion, add extra sparkle
        if (milestone >= 1.0) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeat(3) {
                    delay(200)
                    view.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .withEndAction {
                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(150)
                                .start()
                        }
                        .start()
                }
            }
        }
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

    private fun extractAmountFromText(text: String): Double? {
        val numberPattern = Regex("""(\d+(?:\.\d+)?)""")
        return numberPattern.findAll(text).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000)
        } else if (amount >= 1_000) {
            String.format("%,d", amount.toLong())
        } else if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
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
        private const val REQUEST_AUDIO_PERMISSION = 301
    }
}

/**
 * RecyclerView adapter for active goal cards.
 * Shows progress ring, milestone indicators, forecast, and action buttons.
 */
class ActiveGoalAdapter(
    private val goals: List<GoalDetail>,
    private val progressInputGoalId: Long?,
    private val onProgressClick: (Long) -> Unit,
    private val onCompleteClick: (Long) -> Unit,
    private val onAbandonClick: (Long) -> Unit,
    private val onSubmitProgress: (Long, Double) -> Unit
) : RecyclerView.Adapter<ActiveGoalAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val categoryIcon: TextView = view.findViewById(R.id.goal_category_icon)
        val goalName: TextView = view.findViewById(R.id.goal_name)
        val progressPercent: TextView = view.findViewById(R.id.goal_progress_percent)
        val progressBar: ProgressBar = view.findViewById(R.id.goal_progress_bar)
        val currentAmount: TextView = view.findViewById(R.id.goal_current_amount)
        val targetAmount: TextView = view.findViewById(R.id.goal_target_amount)
        val remainingText: TextView = view.findViewById(R.id.goal_remaining_text)
        val forecastText: TextView = view.findViewById(R.id.goal_forecast_text)
        val encouragementText: TextView = view.findViewById(R.id.goal_encouragement_text)
        val trackIndicator: TextView = view.findViewById(R.id.goal_track_indicator)
        val milestoneContainer: LinearLayout = view.findViewById(R.id.milestone_container)
        val progressInputContainer: LinearLayout = view.findViewById(R.id.progress_input_container)
        val progressAmountInput: EditText = view.findViewById(R.id.progress_amount_input)
        val progressSubmitButton: MaterialButton = view.findViewById(R.id.progress_submit_button)
        val addProgressButton: MaterialButton = view.findViewById(R.id.add_progress_button)
        val completeButton: MaterialButton = view.findViewById(R.id.complete_button)
        val abandonButton: MaterialButton = view.findViewById(R.id.abandon_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_active_goal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val detail = goals[position]
        val goal = detail.goal

        // Category icon
        val category = try {
            GoalPlanner.GoalCategory.valueOf(goal.category)
        } catch (_: Exception) {
            GoalPlanner.GoalCategory.OTHER
        }
        holder.categoryIcon.text = getCategoryIcon(category)
        holder.goalName.text = goal.name

        // Progress
        holder.progressPercent.text = "${detail.progressPercent}%"
        holder.progressBar.progress = detail.progressPercent
        holder.currentAmount.text = "KSh ${formatAmount(goal.currentAmount)}"
        holder.targetAmount.text = "/ KSh ${formatAmount(goal.targetAmount)}"
        holder.remainingText.text = "Imebaki: KSh ${formatAmount(detail.remainingAmount)}"

        // Forecast
        holder.forecastText.text = detail.forecastMessage

        // Encouragement
        holder.encouragementText.text = detail.encouragement

        // Track indicator
        holder.trackIndicator.text = if (detail.isOnTrack) "✅ Uko kwenye njia" else "⚠️ Inahitaji jitihada zaidi"

        // Milestone indicators
        holder.milestoneContainer.removeAllViews()
        val milestoneThresholds = listOf(0.25, 0.50, 0.75, 1.0)
        val reachedMilestones = detail.milestones.map { it.percentage }.toSet()

        for (threshold in milestoneThresholds) {
            val indicator = TextView(holder.itemView.context).apply {
                text = when (threshold) {
                    0.25 -> "🌱"
                    0.50 -> "🔥"
                    0.75 -> "⭐"
                    1.0 -> "🏆"
                    else -> "○"
                }
                textSize = 18f
                alpha = if (reachedMilestones.contains(threshold)) 1.0f else 0.3f
                setPadding(8, 0, 8, 0)
            }
            holder.milestoneContainer.addView(indicator)
        }

        // Progress input visibility
        val showInput = progressInputGoalId == goal.id
        holder.progressInputContainer.visibility = if (showInput) View.VISIBLE else View.GONE

        // Add progress button
        holder.addProgressButton.setOnClickListener {
            onProgressClick(goal.id)
        }

        // Submit progress
        holder.progressSubmitButton.setOnClickListener {
            val amountStr = holder.progressAmountInput.text.toString().trim()
            val amount = amountStr.toDoubleOrNull()
            if (amount != null && amount > 0) {
                onSubmitProgress(goal.id, amount)
                holder.progressAmountInput.text.clear()
            }
        }

        // Complete button
        holder.completeButton.setOnClickListener {
            onCompleteClick(goal.id)
        }

        // Abandon button
        holder.abandonButton.setOnClickListener {
            onAbandonClick(goal.id)
        }

        // Animate progress bar
        animateProgressBar(holder.progressBar, detail.progressPercent)
    }

    override fun getItemCount() = goals.size

    private fun getCategoryIcon(category: GoalPlanner.GoalCategory): String {
        return when (category) {
            GoalPlanner.GoalCategory.EQUIPMENT -> "🔧"
            GoalPlanner.GoalCategory.INVENTORY -> "📦"
            GoalPlanner.GoalCategory.SAVINGS -> "💰"
            GoalPlanner.GoalCategory.DEBT_REDUCTION -> "💳"
            GoalPlanner.GoalCategory.BUSINESS_EXPANSION -> "🏪"
            GoalPlanner.GoalCategory.EDUCATION -> "📚"
            GoalPlanner.GoalCategory.EMERGENCY_FUND -> "🛡️"
            GoalPlanner.GoalCategory.ASSET -> "🏠"
            GoalPlanner.GoalCategory.OTHER -> "🎯"
        }
    }

    private fun animateProgressBar(progressBar: ProgressBar, target: Int) {
        val animator = ObjectAnimator.ofInt(progressBar, "progress", 0, target)
        animator.duration = 800
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.start()
    }

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000)
        } else if (amount >= 1_000) {
            String.format("%,d", amount.toLong())
        } else if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
        }
    }
}

/**
 * RecyclerView adapter for completed goal cards.
 */
class CompletedGoalAdapter(
    private val goals: List<com.msaidizi.app.core.model.GoalRecord>
) : RecyclerView.Adapter<CompletedGoalAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val goalName: TextView = view.findViewById(R.id.completed_goal_name)
        val goalAmount: TextView = view.findViewById(R.id.completed_goal_amount)
        val completedIcon: TextView = view.findViewById(R.id.completed_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_completed_goal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val goal = goals[position]
        holder.completedIcon.text = "✅"
        holder.goalName.text = goal.name
        holder.goalAmount.text = "KSh ${formatAmount(goal.targetAmount)}"
    }

    override fun getItemCount() = goals.size

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000)
        } else if (amount >= 1_000) {
            String.format("%,d", amount.toLong())
        } else {
            amount.toLong().toString()
        }
    }
}
