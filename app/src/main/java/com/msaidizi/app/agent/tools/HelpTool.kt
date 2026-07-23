package com.msaidizi.app.agent.tools

/**
 * HelpTool — Show available commands and capabilities.
 */
class HelpTool : Tool {
    override val name = "help"
    override val description = "Msaada — Show help and available commands"
    override val supportedIntents = listOf("help")
    override val memoryRequiredMB = 0

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("📋 Msaidizi — Amri zinazopatikana:\n\n")
                    append("💰 MAUZO:\n")
                    append("  • \"Nimeuza nyanya kwa 500\" — Rekodi mauzo\n")
                    append("  • \"Nimenunua mchele kwa 200\" — Rekodi manunuzi\n")
                    append("  • \"Nimetumia 100 kwa usafiri\" — Rekodi gharama\n\n")
                   append("📊 UCHAMBUZI:\n")
                    append("  • \"Salio\" — Angalia salio lako\n")
                    append("  • \"Faida\" — Angalia faida\n")
                    append("  • \"Stock\" — Angalia bidhaa\n")
                    append("  • \"Muhtasari wa leo\" — Muhtasari wa siku\n")
                    append("  • \"Muhtasari wa wiki\" — Muhtasari wa wiki\n\n")
                    append("🎯 MALENGO:\n")
                    append("  • \"Lengo la akiba 5000\" — Weka lengo\n")
                    append("  • \"Angalia malengo\" — Angalia maendeleo\n\n")
                    append("💳 MIKopo:\n")
                    append("  • \"Mkopo 10000\" — Rekodi mkopo\n")
                    append("  • \"Angalia mikopo\" — Angalia mikopo\n\n")
                    append("💡 USHAURI:\n")
                    append("  • \"Nisaidie na biashara\" — Pata ushauri\n\n")
                    append("🗣️ Ongea tu! Sema chochote kuhusu biashara yako.")
                }
            } else {
                buildString {
                    append("📋 Msaidizi — Available commands:\n\n")
                    append("💰 SALES:\n")
                    append("  • \"I sold tomatoes for 500\" — Record sale\n")
                    append("  • \"I bought rice for 200\" — Record purchase\n")
                    append("  • \"I spent 100 on transport\" — Record expense\n\n")
                    append("📊 ANALYSIS:\n")
                    append("  • \"Balance\" — Check your balance\n")
                    append("  • \"Profit\" — Check profit\n")
                    append("  • \"Stock\" — Check inventory\n")
                    append("  • \"Daily summary\" — Today's summary\n")
                    append("  • \"Weekly summary\" — This week's summary\n\n")
                    append("🎯 GOALS:\n")
                    append("  • \"Goal savings 5000\" — Set a goal\n")
                    append("  • \"Check goals\" — View progress\n\n")
                    append("💡 ADVICE:\n")
                    append("  • \"Help me with business\" — Get advice\n\n")
                    append("🗣️ Just talk! Say anything about your business.")
                }
            },
            data = mapOf("type" to "help"),
            success = true
        )
    }

    override fun onLowMemory() {}
}
