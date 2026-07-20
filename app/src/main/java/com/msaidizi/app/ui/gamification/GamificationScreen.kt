package com.msaidizi.app.ui.gamification

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.msaidizi.app.R
import com.msaidizi.app.databinding.FragmentGamificationBinding
import com.msaidizi.app.databinding.ItemBadgeCategoryHeaderBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Gamification/Engagement UI Screen.
 *
 * Displays the user's gamification progress including:
 * - Badge gallery (18 badges in 7 categories)
 * - Level progress (Mwanafunzi → Mfanyabiashara → Mjasiriamali → Bingwa → Kiongozi → Legend)
 * - Streak display with protection shields
 * - XP progress bar
 * - Variable reward popup (surprise element)
 * - Social proof section (anonymized peer comparison)
 *
 * Anti-shame design principles:
 * - No public leaderboards
 * - Badges can never be lost
 * - Locked badges shown encouragingly ("not yet earned")
 * - Social proof is anonymized and positive
 *
 * All labels in Swahili with English fallbacks.
 */
@AndroidEntryPoint
class GamificationScreen : Fragment() {

    private var _binding: FragmentGamificationBinding? = null
    private val binding get() = requireNotNull(_binding) { "Fragment binding accessed before onCreateView or after onDestroyView" }

    private val viewModel: GamificationViewModel by viewModels()

    private lateinit var badgeAdapter: BadgeCategoryAdapter

    // Category colors for badge accent coloring
    private val categoryColors by lazy {
        mapOf(
            BadgeCategory.ONBOARDING to ContextCompat.getColor(requireContext(), R.color.badge_onboarding),
            BadgeCategory.CONSISTENCY to ContextCompat.getColor(requireContext(), R.color.badge_consistency),
            BadgeCategory.GROWTH to ContextCompat.getColor(requireContext(), R.color.badge_growth),
            BadgeCategory.INTELLIGENCE to ContextCompat.getColor(requireContext(), R.color.badge_intelligence),
            BadgeCategory.FINANCIAL to ContextCompat.getColor(requireContext(), R.color.badge_financial),
            BadgeCategory.SOCIAL to ContextCompat.getColor(requireContext(), R.color.badge_social),
            BadgeCategory.LOYALTY to ContextCompat.getColor(requireContext(), R.color.badge_loyalty)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupBadgeGallery()
        setupSwipeRefresh()
        observeViewModel()

        viewModel.loadGamificationData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupBadgeGallery() {
        badgeAdapter = BadgeCategoryAdapter(categoryColors)
        binding.badgeRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = badgeAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    // ═══════════════════════════════════════════════════════════════
    // OBSERVE VIEWMODEL
    // ═══════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        // Loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentContainer.visibility = if (isLoading) View.GONE else View.VISIBLE
            binding.swipeRefresh.isRefreshing = false
        }

        // Level progress
        viewModel.levelInfo.observe(viewLifecycleOwner) { levelInfo ->
            val perks = viewModel.getLevelPerks(levelInfo.levelIndex)
            val nextPerks = if (levelInfo.levelIndex < 5) {
                viewModel.getLevelPerks(levelInfo.levelIndex + 1)
            } else null
            binding.levelProgress.bind(levelInfo, perks, nextPerks)
        }

        // Badge gallery
        viewModel.badgeGroups.observe(viewLifecycleOwner) { groups ->
            badgeAdapter.submitList(groups)
        }

        // Badge count summary
        viewModel.earnedCount.observe(viewLifecycleOwner) { earned ->
            val total = viewModel.totalCount.value ?: 0
            binding.badgeSummary.text = getString(R.string.badge_summary, earned, total)
        }

        // Streak info
        viewModel.streakInfo.observe(viewLifecycleOwner) { streakInfo ->
            bindStreakInfo(streakInfo)
        }

        // Social proof
        viewModel.socialProof.observe(viewLifecycleOwner) { data ->
            bindSocialProof(data)
        }

        // Variable reward popup
        viewModel.variableReward.observe(viewLifecycleOwner) { reward ->
            reward?.let { showVariableRewardPopup(it) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STREAK DISPLAY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Bind streak info to the streak card.
     * Shows current streak, longest streak, and protection shields.
     */
    private fun bindStreakInfo(streakInfo: com.msaidizi.app.gamification.StreakInfo) {
        // Current streak
        binding.streakCount.text = streakInfo.currentStreak.toString()
        binding.streakLabel.text = if (streakInfo.currentStreak == 1) "siku" else "siku mfululizo"

        // Longest streak
        binding.longestStreak.text = getString(R.string.longest_streak, streakInfo.longestStreak)

        // Protection shields
        val maxProtections = com.msaidizi.app.gamification.GamificationEngine.MAX_STREAK_PROTECTIONS_PER_WEEK
        val available = streakInfo.protectionsAvailable
        binding.streakProtectionCount.text = getString(R.string.protection_count, available, maxProtections)

        // Visual shield icons
        binding.protectionShield1.alpha = if (available >= 1) 1.0f else 0.2f
        binding.protectionShield2.alpha = if (available >= 2) 1.0f else 0.2f

        // Streak celebration emoji
        val streakEmoji = when {
            streakInfo.currentStreak >= 60 -> "🔥🔥🔥"
            streakInfo.currentStreak >= 30 -> "🔥🔥"
            streakInfo.currentStreak >= 7 -> "🔥"
            streakInfo.currentStreak >= 3 -> "✨"
            else -> ""
        }
        binding.streakEmoji.text = streakEmoji

        // Animate streak number on load
        animateStreakCount()
    }

    private fun animateStreakCount() {
        val scaleX = ObjectAnimator.ofFloat(binding.streakCount, View.SCALE_X, 0.5f, 1.2f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(binding.streakCount, View.SCALE_Y, 0.5f, 1.2f, 1.0f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 500
            interpolator = OvershootInterpolator(2.0f)
            start()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SOCIAL PROOF
    // ═══════════════════════════════════════════════════════════════

    /**
     * Bind social proof data — anonymized peer comparison.
     * Positive framing only (anti-shame design).
     */
    private fun bindSocialProof(data: SocialProofData) {
        binding.socialProofMessage.text = data.messageSw

        // Percentile badge
        val percentileText = when {
            data.percentile >= 90 -> "🏆 Top 10%"
            data.percentile >= 70 -> "📈 Juu ya wastani"
            data.percentile >= 50 -> "📊 Wastani"
            else -> "💪 Inakua"
        }
        binding.socialProofPercentile.text = percentileText

        // Comparison bars
        val userRatio = (data.userDailyAvg / data.peerAvgSales).coerceIn(0.0, 2.0)
        binding.userProgress.max = 200
        binding.userProgress.progress = (userRatio * 100).toInt()
        binding.peerProgress.max = 200
        binding.peerProgress.progress = 100  // Peer average = baseline

        binding.userAvgLabel.text = getString(R.string.your_avg, "%.1f".format(data.userDailyAvg))
        binding.peerAvgLabel.text = getString(R.string.peer_avg, "%.1f".format(data.peerAvgSales))

        // Social proof section visibility
        binding.socialProofCard.visibility = View.VISIBLE
    }

    // ═══════════════════════════════════════════════════════════════
    // VARIABLE REWARD POPUP
    // ═══════════════════════════════════════════════════════════════

    /**
     * Show variable reward popup with celebration animation.
     * Uses Material Design 3 dialog with Lottie animation.
     */
    private fun showVariableRewardPopup(reward: VariableRewardPopup) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_variable_reward, null)

        // Set content
        dialogView.findViewById<android.widget.TextView>(R.id.reward_title).text = reward.titleSw
        dialogView.findViewById<android.widget.TextView>(R.id.reward_message).text = reward.messageSw
        dialogView.findViewById<android.widget.TextView>(R.id.reward_points).text =
            "+${reward.bonusPoints}"

        // Lottie animation based on reward type
        val lottieView = dialogView.findViewById<LottieAnimationView>(R.id.reward_animation)
        try {
            if (reward.isJackpot) {
                lottieView.setAnimation(R.raw.anim_jackpot)
            } else {
                lottieView.setAnimation(R.raw.anim_celebration)
            }
            lottieView.playAnimation()
        } catch (e: Throwable) {
            // Lottie animation files not yet bundled — hide gracefully
            Timber.w(e, "Lottie animation not found, hiding animation view")
            lottieView.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Asante! 🙏") { dialogInterface, _ ->
                dialogInterface.dismiss()
                viewModel.dismissVariableReward()
            }
            .setCancelable(true)
            .create()

        dialog.show()

        // Auto-dismiss after 5 seconds
        dialogView.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
                viewModel.dismissVariableReward()
            }
        }, 5000)
    }

    // ═══════════════════════════════════════════════════════════════
    // BADGE CATEGORY ADAPTER
    // ═══════════════════════════════════════════════════════════════

    /**
     * RecyclerView adapter for badge categories.
     * Each category is a section with a header and a grid of badge cards.
     */
    inner class BadgeCategoryAdapter(
        private val categoryColors: Map<BadgeCategory, Int>
    ) : RecyclerView.Adapter<BadgeCategoryAdapter.CategoryViewHolder>() {

        private var items: List<BadgeCategoryGroup> = emptyList()

        fun submitList(newItems: List<BadgeCategoryGroup>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val binding = ItemBadgeCategoryHeaderBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return CategoryViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class CategoryViewHolder(
            private val headerBinding: ItemBadgeCategoryHeaderBinding
        ) : RecyclerView.ViewHolder(headerBinding.root) {

            fun bind(group: BadgeCategoryGroup) {
                val color = categoryColors[group.category] ?: ContextCompat.getColor(
                    itemView.context, R.color.primary
                )

                // Category header
                headerBinding.categoryEmoji.text = group.category.emoji
                headerBinding.categoryName.text = group.nameSw
                headerBinding.categoryCount.text =
                    itemView.context.getString(R.string.category_badge_count, group.earnedCount, group.totalCount)

                // Color accent
                headerBinding.categoryAccent.setBackgroundColor(color)

                // Badge grid
                headerBinding.badgeGrid.removeAllViews()
                val spanCount = 3
                for ((index, status) in group.badges.withIndex()) {
                    val badgeCard = BadgeCard(itemView.context)
                    badgeCard.layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val margin = (4 * itemView.context.resources.displayMetrics.density).toInt()
                        setMargins(margin, margin, margin, margin)
                    }
                    badgeCard.bind(status, color)

                    // Play earn animation for newly earned badges
                    if (status.justEarned) {
                        badgeCard.post { badgeCard.playEarnAnimation() }
                    }

                    headerBinding.badgeGrid.addView(badgeCard)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GamificationScreen"

        fun newInstance() = GamificationScreen()
    }
}
