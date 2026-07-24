package com.msaidizi.app.superagent.tools

import javax.inject.Inject

/**
 * Service Voice Commands — Swahili patterns for service transactions
 * "Nimefanya repair ya simu, mia tano" → service transaction
 */
class ServiceVoiceCommands @Inject constructor() {

    data class ServiceMatch(
        val serviceName: String,
        val amount: Double,
        val customerName: String?,
        val confidence: Double
    )

    // Service patterns: verb + object + amount
    private val patterns = mapOf(
        // Repair patterns
        "repair" to listOf("repair", "kurepair", "kufix", "kutengeneza"),
        "beauty" to listOf("kata", "kukata", "nywele", "braid", "kubraid", "manicure", "pedicure"),
        "cleaning" to listOf("osha", "kuosha", "gari", "safisha"),
        "construction" to listOf("fanya", "kufanya", "kuchimba", "kujenga", "kukata")
    )

    fun parseServiceCommand(input: String): ServiceMatch? {
        val lower = input.lowercase()

        // Extract amount
        val amountMatch = Regex("(\\d+[,.]?\\d*)").find(lower) ?: return null
        val amount = amountMatch.value.replace(",", "").toDoubleOrNull() ?: return null

        // Detect service type
        for ((category, keywords) in patterns) {
            if (keywords.any { lower.contains(it) }) {
                val serviceName = extractServiceName(lower, category)
                val customerName = extractCustomerName(lower)
                return ServiceMatch(serviceName, amount, customerName, 0.8)
            }
        }

        // Generic service pattern: "nimemfanyia [customer] [service], [amount]"
        if (lower.contains("nimemfanyia") || lower.contains("nimefanyia") || lower.contains("nimefanya")) {
            return ServiceMatch("service", amount, null, 0.6)
        }

        return null
    }

    private fun extractServiceName(input: String, category: String): String {
        return when (category) {
            "repair" -> when {
                input.contains("simu") -> "Phone repair"
                input.contains("shoe") || input.contains("viatu") -> "Shoe repair"
                input.contains("watch") || input.contains("saa") -> "Watch repair"
                else -> "General repair"
            }
            "beauty" -> when {
                input.contains("nywele") || input.contains("hair") -> "Hair styling"
                input.contains("kata") -> "Haircut"
                input.contains("braid") -> "Hair braiding"
                input.contains("nails") || input.contains("kucha") -> "Nails"
                else -> "Beauty service"
            }
            "cleaning" -> when {
                input.contains("gari") || input.contains("car") -> "Car wash"
                input.contains("ndani") || input.contains("interior") -> "Interior cleaning"
                else -> "Cleaning"
            }
            "construction" -> when {
                input.contains("gate") || input.contains("lango") -> "Gate work"
                input.contains("dirisha") || input.contains("window") -> "Window work"
                input.contains("chair") || input.contains("kiti") -> "Chair making"
                else -> "Construction work"
            }
            else -> "Service"
        }
    }

    private fun extractCustomerName(input: String): String? {
        // Look for "ya [name]" or "wa [name]" patterns
        val match = Regex("(?:ya|wa|kwa)\\s+(\\w+)").find(input)
        return match?.groupValues?.get(1)
    }
}
