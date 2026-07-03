package com.msaidizi.app.ui.gamification

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.msaidizi.app.R
import com.msaidizi.app.databinding.ViewLevelProgressBinding
import com.msaidizi.app.gamification.LevelInfo

/**
 * Reusable level progress component.
 *
 * Displays:
 * - Current level name and emoji icon
 * - XP progress bar with animated fill
 * - Next level preview with points needed
 * - Perks list for current and next level
 *
 * Material Design 3 styling with Swahili labels.
 *
 * Levels (0-5):
 * 0. Mwanafunzi (Student) 📚
 * 1. Mfanyabiashara (Business Person) 🏪
 * 2. Mjasiriamali (Entrepreneur) 🚀
 * 3. Bingwa (Champion) 🏆
 * 4. Kiongozi (Leader) 👑
 * 5. Legend ⭐
 */
class LevelProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewLevelProgressBinding =
        ViewLevelProgressBinding.inflate(LayoutInflater.from(context), this, true)

    private var currentLevel: LevelInfo? = null

    init {
        orientation = VERTICAL
    }

    /**
     * Bind level data and animate the progress bar.
     *
     * @param levelInfo Current level information
     * @param perks List of perks for the current level (Swahili)
     * @param nextLevelPerks List of perks for the next level (Swahili), null if max level
     */
    fun bind(
        levelInfo: LevelInfo,
        perks: List<String>,
        nextLevelPerks: List<String>?
    ) {
        currentLevel = levelInfo

        // Level name and emoji
        binding.levelEmoji.text = levelInfo.emoji
        binding.levelName.text = levelInfo.nameSw

        // XP text
        if (levelInfo.levelIndex >= 5) {
            binding.levelXpText.text = context.getString(R.string.level_max_reached)
            binding.levelXpText.setTextColor(context.getColor(R.color.level_legend))
        } else {
            val currentXp = levelInfo.totalPoints
            val nextXp = levelInfo.nextLevelPoints
            binding.levelXpText.text = context.getString(R.string.xp_format, currentXp, nextXp)
        }

        // Animate progress bar
        binding.levelProgressBar.max = 100
        binding.levelProgressBar.progress = 0
        ObjectAnimator.ofInt(
            binding.levelProgressBar,
            "progress",
            (levelInfo.progress * 100).toInt()
        ).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            start()
        }

        // Progress percentage text
        binding.levelProgressPercent.text = "${(levelInfo.progress * 100).toInt()}%"

        // Current level perks
        binding.levelPerksContainer.removeAllViews()
        for (perk in perks) {
            addPerkItem(perk, isEarned = true)
        }

        // Next level preview
        if (levelInfo.levelIndex < 5 && nextLevelPerks != null) {
            binding.nextLevelSection.visibility = View.VISIBLE
            val nextLevelIndex = levelInfo.levelIndex + 1
            val nextName = LEVEL_NAMES_SW[nextLevelIndex]
            val nextEmoji = LEVEL_EMOJIS[nextLevelIndex]
            binding.nextLevelTitle.text = context.getString(R.string.next_level_title, nextEmoji, nextName)

            binding.nextLevelPerksContainer.removeAllViews()
            for (perk in nextLevelPerks) {
                addPerkItem(perk, isEarned = false)
            }

            val pointsNeeded = levelInfo.nextLevelPoints - levelInfo.totalPoints
            binding.pointsToNext.text = context.getString(R.string.points_to_next, pointsNeeded)
        } else {
            binding.nextLevelSection.visibility = View.GONE
        }

        // Level-up celebration text for max level
        if (levelInfo.levelIndex >= 5) {
            binding.legendCelebration.visibility = View.VISIBLE
            binding.legendCelebration.text = context.getString(R.string.legend_celebration)
        } else {
            binding.legendCelebration.visibility = View.GONE
        }
    }

    /**
     * Add a single perk item to a container.
     */
    private fun addPerkItem(text: String, isEarned: Boolean) {
        val container = if (isEarned) binding.levelPerksContainer else binding.nextLevelPerksContainer
        val itemView = LayoutInflater.from(context)
            .inflate(R.layout.item_level_perk, container, false)

        val icon = itemView.findViewById<android.widget.ImageView>(R.id.perk_icon)
        val label = itemView.findViewById<android.widget.TextView>(R.id.perk_label)

        label.text = text
        if (isEarned) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.setColorFilter(context.getColor(R.color.level_perk_earned))
            label.alpha = 1.0f
        } else {
            icon.setImageResource(R.drawable.ic_radio_unchecked)
            icon.setColorFilter(context.getColor(R.color.level_perk_locked))
            label.alpha = 0.6f
        }

        container.addView(itemView)
    }

    companion object {
        private val LEVEL_NAMES_SW = arrayOf(
            "Mwanafunzi", "Mfanyabiashara", "Mjasiriamali",
            "Bingwa", "Kiongozi", "Legend"
        )
        private val LEVEL_EMOJIS = arrayOf("📚", "🏪", "🚀", "🏆", "👑", "⭐")
    }
}
