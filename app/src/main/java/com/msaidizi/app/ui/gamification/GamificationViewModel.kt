package com.msaidizi.app.ui.gamification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class GamificationViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(GamificationUiState())
    val uiState: StateFlow<GamificationUiState> = _uiState.asStateFlow()

    init {
        loadGamification()
    }

    private fun loadGamification() {
        // Gamification data is derived from activity patterns.
        // For now, provide the structure with defaults.
        _uiState.value = GamificationUiState(
            points = 0,
            level = 1,
            streak = 0,
            badges = listOf(
                Badge("🥇", "Rekodi ya Kwanza", "Rekodi mauzo yako ya kwanzo", false),
                Badge("🔥", "Streak ya Siku 7", "Rekodi kwa siku 7 mfululizo", false),
                Badge("💎", "Streak ya Siku 30", "Rekodi kwa siku 30 mfululizo", false),
                Badge("🎯", "Mlengo wa Kwanza", "Kamilisha lengo lako la kwanza", false),
                Badge("💰", "Faida ya 100K", "Pata faida ya KSh 100,000", false)
            )
        )
    }
}

data class GamificationUiState(
    val points: Int = 0,
    val level: Int = 1,
    val streak: Int = 0,
    val badges: List<Badge> = emptyList()
)

data class Badge(
    val emoji: String,
    val name: String,
    val description: String,
    val earned: Boolean
)
