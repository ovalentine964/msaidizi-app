package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.GivingDao

/**
 * TitheTool — Alias for GivingTool for tithe-specific intents.
 */
class TitheTool(
    private val givingDao: GivingDao
) : Tool {
    private val delegate = GivingTool(givingDao)

    override val name = "tithe"
    override val description = "Zakat/Tithe — Track tithes"
    override val supportedIntents = listOf("tithe", "zakat")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val enrichedArgs = args.toMutableMap()
        if (!enrichedArgs.containsKey("type")) {
            enrichedArgs["type"] = if (language == "sw") "Zakat/Tithe" else "Tithe"
        }
        return delegate.execute(enrichedArgs, language)
    }

    override fun onLowMemory() {}
}
