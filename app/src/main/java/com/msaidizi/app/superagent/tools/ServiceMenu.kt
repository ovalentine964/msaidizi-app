package com.msaidizi.app.superagent.tools

import javax.inject.Inject

/**
 * Service Menu — Support for service workers (fundi, salon, barber, etc.)
 * Tracks labour vs materials, service pricing, appointments
 */
data class ServiceItem(
    val name: String,
    val basePrice: Double,
    val category: String,    // "repair", "beauty", "cleaning", "construction"
    val labourRatio: Double  // 0.0-1.0, how much is labour vs materials
)

data class ServiceTransaction(
    val serviceName: String,
    val labourCost: Double,
    val materialsCost: Double,
    val totalCharged: Double,
    val customerName: String?,
    val timestamp: Long
)

class ServiceMenu @Inject constructor() {
    private val services = mutableMapOf<String, MutableList<ServiceItem>>()
    private val transactions = mutableListOf<ServiceTransaction>()

    // Default service menus by business type
    fun getDefaultMenu(businessType: String): List<ServiceItem> {
        return when (businessType) {
            "fundi" -> listOf(
                ServiceItem("Phone screen repair", 1500.0, "repair", 0.7),
                ServiceItem("Phone battery replacement", 800.0, "repair", 0.5),
                ServiceItem("Shoe repair", 200.0, "repair", 0.8),
                ServiceItem("Watch battery", 150.0, "repair", 0.9),
                ServiceItem("General repair", 500.0, "repair", 0.7)
            )
            "salon" -> listOf(
                ServiceItem("Hair braiding", 500.0, "beauty", 0.9),
                ServiceItem("Hair washing", 100.0, "beauty", 0.95),
                ServiceItem("Manicure", 200.0, "beauty", 0.9),
                ServiceItem("Pedicure", 300.0, "beauty", 0.9),
                ServiceItem("Facial", 400.0, "beauty", 0.8)
            )
            "barber" -> listOf(
                ServiceItem("Haircut", 150.0, "beauty", 0.95),
                ServiceItem("Beard trim", 50.0, "beauty", 0.95),
                ServiceItem("Shave", 50.0, "beauty", 0.95),
                ServiceItem("Haircut + beard", 200.0, "beauty", 0.95)
            )
            "car_wash" -> listOf(
                ServiceItem("Small car wash", 200.0, "cleaning", 0.9),
                ServiceItem("SUV wash", 350.0, "cleaning", 0.9),
                ServiceItem("Interior clean", 300.0, "cleaning", 0.85),
                ServiceItem("Full detail", 1000.0, "cleaning", 0.8)
            )
            "welder" -> listOf(
                ServiceItem("Gate repair", 2000.0, "construction", 0.5),
                ServiceItem("Window grills", 3000.0, "construction", 0.4),
                ServiceItem("Metal chair", 1500.0, "construction", 0.5),
                ServiceItem("Custom fabrication", 5000.0, "construction", 0.4)
            )
            "tailor" -> listOf(
                ServiceItem("Dress sewing", 1500.0, "beauty", 0.8),
                ServiceItem("Trouser alteration", 200.0, "beauty", 0.9),
                ServiceItem("Shirt repair", 100.0, "beauty", 0.9),
                ServiceItem("Uniform making", 2000.0, "beauty", 0.7)
            )
            else -> emptyList()
        }
    }

    fun recordService(serviceName: String, labourCost: Double, materialsCost: Double, customerName: String?): ServiceTransaction {
        val transaction = ServiceTransaction(
            serviceName = serviceName,
            labourCost = labourCost,
            materialsCost = materialsCost,
            totalCharged = labourCost + materialsCost,
            customerName = customerName,
            timestamp = System.currentTimeMillis()
        )
        transactions.add(transaction)
        return transaction
    }

    fun getDailyRevenue(): Double = transactions.filter { isToday(it.timestamp) }.sumOf { it.totalCharged }
    fun getDailyLabour(): Double = transactions.filter { isToday(it.timestamp) }.sumOf { it.labourCost }
    fun getDailyMaterials(): Double = transactions.filter { isToday(it.timestamp) }.sumOf { it.materialsCost }

    private fun isToday(timestamp: Long): Boolean {
        val today = java.time.LocalDate.now()
        val date = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return date == today
    }
}
