package com.msaidizi.app.ui.mindset

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MindsetViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MindsetUiState())
    val uiState: StateFlow<MindsetUiState> = _uiState.asStateFlow()

    private val tips = listOf(
        Tip("💰", "Akiba", "Rekodi kila miamala — hata ndogo. Ukijua pesa yako inapoenda, ndio unapata udhibiti.", "Record every transaction — even small ones. Knowing where your money goes gives you control."),
        Tip("📈", "Ukuaji", "Tofauti ya biashara na kazi ni kwamba biashara inaweza kukua bila wewe kuwepo.", "The difference between a business and a job is that a business can grow without you being there."),
        Tip("🏦", "Utajiri", "Akiba ya 10% ya kila mauzo ndio msingi wa utajiri. Anza leo, siyo kesho.", "Saving 10% of every sale is the foundation of wealth. Start today, not tomorrow."),
        Tip("📦", "Manunuzi", "Nunua kwa wingi, uza kwa kimoja. Hii ndiyo siri ya faida.", "Buy in bulk, sell by unit. This is the secret of profit."),
        Tip("🤝", "Wateja", "Mteja kurudi ni ishara ya biashara nzuri. Heshimu kila mteja.", "A returning customer is a sign of good business. Respect every customer."),
        Tip("💼", "Uhasibu", "Usichanganye pesa za biashara na pesa za nyumba. Weka tofauti.", "Don't mix business money with household money. Keep them separate."),
        Tip("🔍", "Uchambuzi", "Kila siku, uliza: Nimepata faida gani leo? Nini naweza kuboresha kesho?", "Every day ask: What profit did I make today? What can I improve tomorrow?"),
        Tip("📊", "Soko", "Ukosefu wa taarifa ndio adui mkubwa wa biashara. Jua bei za soko kila wakati.", "Lack of information is the biggest enemy of business. Know market prices always."),
        Tip("🌱", "Kuanza", "Biashara ndogo ikilindwa vizuri, inakuwa kubwa. Anza na ulicho nacho.", "A small business well managed becomes big. Start with what you have."),
        Tip("📞", "Uhusiano", "Usiogope kumpigia mteja simu. Uhusiano mzuri = mauzo zaidi.", "Don't be afraid to call a customer. Good relationships = more sales.")
    )

    init {
        loadTips()
    }

    fun nextTip() {
        _uiState.value = _uiState.value.copy(
            currentIndex = (_uiState.value.currentIndex + 1) % tips.size,
            currentTip = tips[(_uiState.value.currentIndex + 1) % tips.size]
        )
    }

    private fun loadTips() {
        _uiState.value = MindsetUiState(
            currentTip = tips.first(),
            currentIndex = 0,
            totalTips = tips.size
        )
    }
}

data class MindsetUiState(
    val currentTip: Tip? = null,
    val currentIndex: Int = 0,
    val totalTips: Int = 0
)

data class Tip(
    val emoji: String,
    val category: String,
    val swahili: String,
    val english: String
)
