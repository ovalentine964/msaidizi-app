package com.msaidizi.app.agent.tools

/**
 * EducationTool — Financial literacy and business education (Rich Habits).
 */
class EducationTool : Tool {
    override val name = "education"
    override val description = "Elimu — Financial literacy tips"
    override val supportedIntents = listOf("rich_habits", "mindset", "financial_tip", "education")
    override val memoryRequiredMB = 5

    private val tips = listOf(
        "Rekodi kila miamala — hata ndogo. Ukijua pesa yako inapoenda, ndio unapata udhibiti." to
        "Record every transaction — even small ones. Knowing where your money goes gives you control.",
        "Tofauti ya biashara na kazi ni kwamba biashara inaweza kukua bila wewe kuwepo." to
        "The difference between a business and a job is that a business can grow without you being there.",
        "Akiba ya 10% ya kila mauzo ndio msingi wa utajiri. Anza leo, siyo kesho." to
        "Saving 10% of every sale is the foundation of wealth. Start today, not tomorrow.",
        "Nunua kwa wingi, uza kwa kimoja. Hii ndiyo siri ya faida." to
        "Buy in bulk, sell by unit. This is the secret of profit.",
        "Mteja kurudi ni ishara ya biashara nzuri. Heshimu kila mteja." to
        "A returning customer is a sign of good business. Respect every customer.",
        "Usichanganye pesa za biashara na pesa za nyumba. Weka tofauti." to
        "Don't mix business money with household money. Keep them separate.",
        "Kila siku, uliza: Nimepata faida gani leo? Nini naweza kuboresha kesho?" to
        "Every day ask: What profit did I make today? What can I improve tomorrow?",
        "Ukosefu wa taarifa ndio adui mkubwa wa biashara. Jua bei za soko kila wakati." to
        "Lack of information is the biggest enemy of business. Know market prices always.",
        "Biashara ndogo ikilindwa vizuri, inakuwa kubwa. Anza na ulicho nacho." to
        "A small business well managed becomes big. Start with what you have.",
        "Usiogope kumpigia mteja simu. Uhusiano mzuri = mauzo zaidi." to
        "Don't be afraid to call a customer. Good relationships = more sales."
    )

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val (swTip, enTip) = tips.random()

        return ToolResult(
            text = if (language == "sw") {
                "📚 Tabia ya Tajiri:\n\n\"$swTip\"\n\n💡 Endelea kujifunza kila siku!"
            } else {
                "📚 Rich Habits:\n\n\"$enTip\"\n\n💡 Keep learning every day!"
            },
            data = mapOf("type" to "education"),
            success = true
        )
    }

    override fun onLowMemory() {}
}
