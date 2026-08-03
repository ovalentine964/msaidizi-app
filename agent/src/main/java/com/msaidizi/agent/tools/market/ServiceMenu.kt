package com.msaidizi.agent.tools.market

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.model.KnowledgeEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * ServiceMenu — Digital service catalog for service workers.
 *
 * Workers list their services with prices. Customers browse by category.
 * Voice-searchable: "Ninahitaji mtu wa kuosha nguo"
 * Shareable via WhatsApp.
 *
 * Features:
 *  1. Service registration with pricing, duration, and labour/material split
 *  2. Category-based browsing (beauty, repair, construction, cleaning, etc.)
 *  3. Voice search: "Ninahitaji mtu wa kuosha nguo" → laundry services
 *  4. Price comparison across providers
 *  5. Service sharing via WhatsApp (formatted text)
 *  6. Default menus for common business types
 *  7. Service recording with labour/materials tracking
 *
 * 7 Actions: add_service, list, search, share, record, remove, categories
 *
 * Voice-first, bilingual (Kiswahili + English).
 * Integrates with ServiceMenu (existing), BookingScheduler, CustomerMatcher.
 */
@Singleton
class ServiceMenu @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) : Tool {

    override val name = "service_menu"
    override val description = "Digital service catalog — list, browse, search, and share services with prices. Voice: 'Ninahitaji mtu wa kuosha nguo'"

    override val argsSchema = argSchema {
        enum(
            "action", "Service menu action",
            listOf(
                "add_service",   // Add a service to the catalog
                "list",          // List services by worker or category
                "search",        // Search services by keyword
                "share",         // Format for WhatsApp sharing
                "record",        // Record a service transaction
                "remove",        // Remove a service
                "categories"     // List all categories
            ),
            required = false
        )
        string("worker_id", "Worker/business ID", required = false)
        string("service_name", "Service name (e.g. 'Hair braiding', 'Phone screen repair')", required = false)
        string("category", "Service category: beauty, repair, construction, cleaning, laundry, transport, entertainment, tech, health, food", required = false)
        number("price", "Price in KES", required = false)
        number("labour_cost", "Labour portion of the price", required = false)
        number("materials_cost", "Materials portion of the price", required = false)
        integer("duration_minutes", "Estimated duration in minutes", required = false)
        string("description", "Service description or notes", required = false)
        string("business_type", "Business type for default menu: fundi, salon, barber, car_wash, welder, tailor, laundry, cyber, mechanic", required = false)
        string("keyword", "Search keyword", required = false)
        string("customer_name", "Customer name (for record action)", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    private val dbHelper: ServiceMenuDbHelper by lazy { ServiceMenuDbHelper(context) }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "list"

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, params)
        }

        return when (action.lowercase()) {
            "add_service" -> addService(params)
            "list" -> listServices(params)
            "search" -> searchServices(params)
            "share" -> shareServices(params)
            "record" -> recordService(params)
            "remove" -> removeService(params)
            "categories" -> listCategories(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ADD SERVICE — Worker adds to their catalog
    // ──────────────────────────────────────────────

    private fun addService(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=salon_001", "MISSING_WORKER_ID")
        val serviceName = params["service_name"]
            ?: return ToolResult.error(name, "Service name required. Try: service_name='Hair braiding'", "MISSING_SERVICE_NAME")
        val category = params["category"] ?: guessCategory(serviceName)
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price required in KES. Try: price=500", "MISSING_PRICE")
        val labourCost = params["labour_cost"]?.toDoubleOrNull() ?: (price * 0.7)
        val materialsCost = params["materials_cost"]?.toDoubleOrNull() ?: (price * 0.3)
        val duration = params["duration_minutes"]?.toIntOrNull()
        val description = params["description"]

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()

            // Check for duplicate
            val existing = findService(db, workerId, serviceName)
            if (existing != null) {
                // Update existing
                db.execSQL(
                    """UPDATE services SET price = ?, labour_cost = ?, materials_cost = ?,
                       duration_minutes = ?, description = ?, category = ?, updated_at = ?
                       WHERE id = ?""",
                    arrayOf(price, labourCost, materialsCost, duration, description, category, now, existing)
                )
                return ToolResult.success(
                    name,
                    data = mapOf("service" to serviceName, "price" to price, "updated" to true),
                    message = "✅ Huduma '$serviceName' imesasishwa: KES ${"%,.0f".format(price)}"
                )
            }

            db.execSQL(
                """INSERT INTO services
                   (worker_id, service_name, category, price, labour_cost, materials_cost,
                    duration_minutes, description, is_active, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""",
                arrayOf(workerId, serviceName, category, price, labourCost, materialsCost, duration, description, now, now)
            )

            return ToolResult.success(
                name,
                data = mapOf("service" to serviceName, "price" to price, "category" to category),
                message = "✅ Huduma imeongezwa: *$serviceName* — KES ${"%,.0f".format(price)} (${categoryToSwahili(category)})"
            )
        } catch (e: Exception) {
            Timber.e(e, "Add service failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // LIST — Browse services
    // ──────────────────────────────────────────────

    private fun listServices(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
        val category = params["category"]
        val businessType = params["business_type"]

        val db = dbHelper.readableDatabase

        // If worker specified, show their catalog
        if (workerId != null) {
            val services = getWorkerServices(db, workerId)
            if (services.isEmpty()) {
                // Offer default menu if business type specified
                if (businessType != null) {
                    val defaults = getDefaultMenu(businessType)
                    if (defaults.isNotEmpty()) {
                        val output = buildString {
                            appendLine("📋 *Menu ya Chaguo — ${businessTypeToSwahili(businessType)}*")
                            appendLine("(Hujaweka huduma zako bado — hii ni menu ya mfano)")
                            appendLine()
                            defaults.forEachIndexed { i, svc ->
                                appendLine("${i + 1}. ${svc.name} — KES ${"%,.0f".format(svc.price)}")
                                appendLine("   📂 ${categoryToSwahili(svc.category)} | ⏱ ${svc.durationMinutes} min")
                            }
                            appendLine()
                            appendLine("Weka huduma zako: add_service service_name=Jina price=500")
                        }
                        return ToolResult.success(name, data = defaults, message = output)
                    }
                }
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna huduma kwa worker $workerId.\n\nOngeza: add_service service_name=Jina price=500"
                )
            }

            val output = buildString {
                appendLine("📋 *Menu ya Huduma*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                val grouped = services.groupBy { it["category"]?.toString() ?: "other" }
                grouped.forEach { (cat, catServices) ->
                    appendLine("📂 *${categoryToSwahili(cat)}*")
                    catServices.forEach { svc ->
                        val price = (svc["price"] as? Number)?.toDouble() ?: 0.0
                        val duration = (svc["duration_minutes"] as? Number)?.toInt()
                        val desc = svc["description"]?.toString()

                        appendLine("   • ${svc["service_name"]} — KES ${"%,.0f".format(price)}")
                        if (duration != null) appendLine("     ⏱ $duration min")
                        if (desc != null) appendLine("     📝 $desc")
                    }
                    appendLine()
                }

                val totalServices = services.size
                val avgPrice = services.mapNotNull { (it["price"] as? Number)?.toDouble() }.average()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 Jumla: $totalServices huduma | Wastani: KES ${"%,.0f".format(avgPrice)}")
                appendLine("📤 Share menu: share worker_id=$workerId")
            }

            return ToolResult.success(name, data = services, message = output)
        }

        // No worker specified — browse by category across all workers
        if (category != null) {
            val services = getServicesByCategory(db, category)
            if (services.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🔍 Hakuna huduma za ${categoryToSwahili(category)} kwenye mfumo bado."
                )
            }

            val output = buildString {
                appendLine("🔍 *Huduma za ${categoryToSwahili(category)}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                services.forEachIndexed { i, svc ->
                    val price = (svc["price"] as? Number)?.toDouble() ?: 0.0
                    appendLine("${i + 1}. ${svc["service_name"]} — KES ${"%,.0f".format(price)}")
                    appendLine("   👤 ${svc["worker_id"]}")
                }
            }

            return ToolResult.success(name, data = services, message = output)
        }

        // Show categories
        return listCategories(params)
    }

    // ──────────────────────────────────────────────
    // SEARCH — Find services by keyword
    // ──────────────────────────────────────────────

    private fun searchServices(params: Map<String, String>): ToolResult {
        val keyword = params["keyword"] ?: params["service_name"]
            ?: return ToolResult.error(name, "Keyword required. Try: keyword=braids au keyword=kuosha nguo", "MISSING_KEYWORD")
        val category = params["category"]

        val db = dbHelper.readableDatabase
        try {
            val results = searchServicesByKeyword(db, keyword, category)

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🔍 Hakuna huduma zinazofanana na '$keyword'.\n\nJaribu neno jingine au angalia categories: categories"
                )
            }

            val output = buildString {
                appendLine("🔍 *Matokeo ya Utafutaji: '$keyword'*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                results.forEachIndexed { i, svc ->
                    val price = (svc["price"] as? Number)?.toDouble() ?: 0.0
                    val duration = (svc["duration_minutes"] as? Number)?.toInt()
                    appendLine("${i + 1}. *${svc["service_name"]}* — KES ${"%,.0f".format(price)}")
                    appendLine("   👤 ${svc["worker_id"]}")
                    appendLine("   📂 ${categoryToSwahili(svc["category"]?.toString() ?: "")}")
                    if (duration != null) appendLine("   ⏱ $duration min")
                    appendLine()
                }
            }

            return ToolResult.success(name, data = results, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Search services failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SHARE — Format for WhatsApp sharing
    // ──────────────────────────────────────────────

    private fun shareServices(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required for sharing", "MISSING_WORKER_ID")

        val db = dbHelper.readableDatabase
        val services = getWorkerServices(db, workerId)

        if (services.isEmpty()) {
            return ToolResult.success(name, message = "Hakuna huduma za kushare. Ongeza kwanza: add_service")
        }

        val shareText = buildString {
            appendLine("📋 *MENU YA HUDUMA*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine()

            val grouped = services.groupBy { it["category"]?.toString() ?: "other" }
            grouped.forEach { (cat, catServices) ->
                appendLine("📂 ${categoryToSwahili(cat)}")
                catServices.forEach { svc ->
                    val price = (svc["price"] as? Number)?.toDouble() ?: 0.0
                    appendLine("  • ${svc["service_name"]} — KES ${"%,.0f".format(price)}")
                }
                appendLine()
            }

            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("📞 Wasiliana nasi kwa maelezo zaidi!")
            appendLine("💡 *Msaidizi — Biashara yako, mkononi mwako*")
        }

        return ToolResult.success(
            name,
            data = mapOf("share_text" to shareText, "service_count" to services.size),
            message = shareText
        )
    }

    // ──────────────────────────────────────────────
    // RECORD — Record a service transaction
    // ──────────────────────────────────────────────

    private fun recordService(params: Map<String, String>): ToolResult {
        val serviceName = params["service_name"] ?: "Service"
        val amount = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Amount required. Try: price=500", "MISSING_AMOUNT")
        val labourCost = params["labour_cost"]?.toDoubleOrNull() ?: (amount * 0.7)
        val materialsCost = params["materials_cost"]?.toDoubleOrNull() ?: (amount * 0.3)
        val customer = params["customer_name"]
        val workerId = params["worker_id"]

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO service_transactions
                   (worker_id, service_name, labour_cost, materials_cost, total_charged, customer_name, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(workerId, serviceName, labourCost, materialsCost, amount, customer, now)
            )

            return ToolResult.success(
                name,
                data = mapOf(
                    "service" to serviceName,
                    "amount" to amount,
                    "labour" to labourCost,
                    "materials" to materialsCost,
                    "customer" to customer
                ),
                message = "✅ Service imerekodwa: $serviceName — KES ${"%,.0f".format(amount)}" +
                        " (Kazi: ${"%,.0f".format(labourCost)}, Vifaa: ${"%,.0f".format(materialsCost)})" +
                        if (customer != null) " — Mteja: $customer" else ""
            )
        } catch (e: Exception) {
            Timber.e(e, "Record service failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // REMOVE — Deactivate a service
    // ──────────────────────────────────────────────

    private fun removeService(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")
        val serviceName = params["service_name"]
            ?: return ToolResult.error(name, "Service name required", "MISSING_SERVICE_NAME")

        val db = dbHelper.writableDatabase
        val existing = findService(db, workerId, serviceName)
            ?: return ToolResult.error(name, "Huduma '$serviceName' haikupatikana.", "NOT_FOUND")

        db.execSQL("UPDATE services SET is_active = 0, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), existing))

        return ToolResult.success(
            name,
            message = "✅ Huduma '$serviceName' imeondolewa kwenye menu."
        )
    }

    // ──────────────────────────────────────────────
    // CATEGORIES — List all available categories
    // ──────────────────────────────────────────────

    private fun listCategories(params: Map<String, String>): ToolResult {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """SELECT category, COUNT(*) as count, MIN(price) as min_price, MAX(price) as max_price, AVG(price) as avg_price
               FROM services WHERE is_active = 1 GROUP BY category ORDER BY count DESC""",
            null
        )

        val categories = mutableListOf<Map<String, Any?>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                categories.add(cursorToMap(c))
            }
        }

        if (categories.isEmpty()) {
            val output = buildString {
                appendLine("📂 *Aina za Huduma (Mfano)*")
                appendLine()
                val defaultCategories = listOf(
                    "beauty" to "Mapambo (Salon, Barber, Nails)",
                    "repair" to "Ukarabati (Phone, Shoe, Watch)",
                    "construction" to "Ujenzi (Mason, Welder, Carpenter)",
                    "cleaning" to "Usafi (Nyumba, Magari)",
                    "laundry" to "Kufua Nguo",
                    "transport" to "Usafiri (Boda, Taxi)",
                    "entertainment" to "Burudani (DJ, MC, Muziki)",
                    "tech" to "Teknolojia (Cyber, Phone Repair)",
                    "health" to "Afya (Dawa, Massage)",
                    "food" to "Chakula (Catering, Mama Ntilie)"
                )
                defaultCategories.forEach { (key, desc) ->
                    appendLine("   📂 *$key* — $desc")
                }
                appendLine()
                appendLine("Ongeza huduma yako: add_service service_name=Jina price=500 category=beauty")
            }
            return ToolResult.success(name, message = output)
        }

        val output = buildString {
            appendLine("📂 *Aina za Huduma Zilizopo*")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            categories.forEach { cat ->
                val name = cat["category"]?.toString() ?: ""
                val count = (cat["count"] as? Number)?.toInt() ?: 0
                val minPrice = (cat["min_price"] as? Number)?.toDouble() ?: 0.0
                val maxPrice = (cat["max_price"] as? Number)?.toDouble() ?: 0.0
                val avgPrice = (cat["avg_price"] as? Number)?.toDouble() ?: 0.0

                appendLine("📂 *${categoryToSwahili(name)}* — $count huduma")
                appendLine("   💰 Bei: KES ${"%,.0f".format(minPrice)} — ${"%,.0f".format(maxPrice)} (wastani: ${"%,.0f".format(avgPrice)})")
                appendLine()
            }
        }

        return ToolResult.success(name, data = categories, message = output)
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili/English voice input.
     *
     * Patterns:
     *  - "Ninahitaji mtu wa kuosha nguo" → search (keyword=kuosha nguo, category=laundry)
     *  - "Bei ya kinyozi ni ngapi?" → search (category=beauty)
     *  - "Nataka kuongeza huduma" → add_service
     *  - "Nimefanya kazi ya kuweka tiles" → record
     *  - "Share menu yangu" → share
     */
    private suspend fun parseVoiceInput(voiceInput: String, params: Map<String, String>): ToolResult {
        val input = voiceInput.trim().lowercase()

        // Search patterns
        val searchPatterns = listOf(
            "ninahitaji", "nataka", "nahitaji", "natafuta", "najitaji",
            "i need", "looking for", "want", "find me", "where can i",
            "bei ya", "price ya", "gharama"
        )
        val isSearch = searchPatterns.any { input.contains(it) }

        if (isSearch) {
            val keyword = extractSearchKeyword(input)
            val category = extractCategory(input)
            return searchServices(mapOf(
                "keyword" to (keyword ?: voiceInput),
                "category" to (category ?: ""),
                "worker_id" to (params["worker_id"] ?: "")
            ))
        }

        // Record patterns
        if (input.contains("nimemaliza") || input.contains("nimefanya") || input.contains("record") ||
            input.contains("nimerekodi")) {
            return recordService(params)
        }

        // Share patterns
        if (input.contains("share") || input.contains("tuma") || input.contains("menu")) {
            val workerId = params["worker_id"]
            if (workerId != null) {
                return shareServices(mapOf("worker_id" to workerId))
            }
        }

        // Default: list categories
        return listCategories(params)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun findService(db: SQLiteDatabase, workerId: String, serviceName: String): Long? {
        val cursor = db.rawQuery(
            "SELECT id FROM services WHERE worker_id = ? AND service_name = ? AND is_active = 1",
            arrayOf(workerId, serviceName)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun getWorkerServices(db: SQLiteDatabase, workerId: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT * FROM services WHERE worker_id = ? AND is_active = 1 ORDER BY category, service_name",
            arrayOf(workerId)
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun getServicesByCategory(db: SQLiteDatabase, category: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT * FROM services WHERE category = ? AND is_active = 1 ORDER BY price ASC",
            arrayOf(category)
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun searchServicesByKeyword(db: SQLiteDatabase, keyword: String, category: String?): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("SELECT * FROM services WHERE is_active = 1 AND (service_name LIKE ? OR description LIKE ?)")
            if (category != null) append(" AND category = ?")
            append(" ORDER BY price ASC LIMIT 20")
        }
        val args = mutableListOf("%$keyword%", "%$keyword%")
        if (category != null) args.add(category)

        val cursor = db.rawQuery(query, args.toTypedArray())
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun extractSearchKeyword(input: String): String? {
        // Remove common prefixes to get the actual search term
        val cleaned = input
            .replace(Regex("""^(ninahitaji|nataka|nahitaji|natafuta|i need|looking for|find me)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(mtu wa|mwenye|anayefanya|wa)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned.ifBlank { null }
    }

    private fun extractCategory(input: String): String? {
        val categoryMap = mapOf(
            "kuosha nguo" to "laundry", "nguo" to "laundry", "laundry" to "laundry", "fua" to "laundry",
            "kinyozi" to "beauty", "nywele" to "beauty", "salon" to "beauty", "hair" to "beauty",
            "braids" to "beauty", "weave" to "beauty", "nails" to "beauty", "pedicure" to "beauty",
            "gari" to "repair", "simu" to "repair", "phone" to "repair", "shoe" to "repair",
            "tiles" to "construction", "painting" to "construction", "ujenzi" to "construction",
            "maji" to "construction", "plumber" to "construction", "umeme" to "construction",
            "usafi" to "cleaning", "kunyosha" to "cleaning",
            "dj" to "entertainment", "muziki" to "entertainment", "mc" to "entertainment",
            "cyber" to "tech", "computer" to "tech", "print" to "tech"
        )
        for ((keyword, category) in categoryMap) {
            if (input.contains(keyword)) return category
        }
        return null
    }

    private fun guessCategory(serviceName: String): String {
        val name = serviceName.lowercase()
        return when {
            name.contains("hair") || name.contains("braids") || name.contains("salon") || name.contains("beard") || name.contains("nails") -> "beauty"
            name.contains("repair") || name.contains("fix") || name.contains("screen") || name.contains("battery") -> "repair"
            name.contains("tile") || name.contains("paint") || name.contains("build") || name.contains("plumb") || name.contains("weld") || name.contains("electric") -> "construction"
            name.contains("wash") || name.contains("clean") || name.contains("usafi") -> "cleaning"
            name.contains("laundry") || name.contains("fua") || name.contains("nguo") -> "laundry"
            name.contains("dj") || name.contains("mc") || name.contains("music") || name.contains("sound") -> "entertainment"
            name.contains("cyber") || name.contains("print") || name.contains("computer") || name.contains("phone") -> "tech"
            name.contains("cook") || name.contains("food") || name.contains("catering") -> "food"
            name.contains("massage") || name.contains("dawa") -> "health"
            name.contains("transport") || name.contains("boda") || name.contains("taxi") -> "transport"
            else -> "other"
        }
    }

    private fun categoryToSwahili(category: String): String = when (category) {
        "beauty" -> "Mapambo"
        "repair" -> "Ukarabati"
        "construction" -> "Ujenzi"
        "cleaning" -> "Usafi"
        "laundry" -> "Kufua Nguo"
        "transport" -> "Usafiri"
        "entertainment" -> "Burudani"
        "tech" -> "Teknolojia"
        "health" -> "Afya"
        "food" -> "Chakula"
        "other" -> "Nyingine"
        else -> category.replaceFirstChar { it.uppercase() }
    }

    private fun businessTypeToSwahili(type: String): String = when (type) {
        "fundi" -> "Fundi (Mkarabati)"
        "salon" -> "Salon"
        "barber" -> "Kinyozi"
        "car_wash" -> "Kuosha Magari"
        "welder" -> "Fundi wa Vyuma"
        "tailor" -> "Mshoni"
        "laundry" -> "Kufua Nguo"
        "cyber" -> "Cyber Cafe"
        "mechanic" -> "Fundi wa Gari"
        else -> type.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    /**
     * Get default service menu for a business type.
     * Used when a worker hasn't set up their own menu yet.
     */
    fun getDefaultMenu(businessType: String): List<ServiceItem> {
        return when (businessType) {
            "fundi" -> listOf(
                ServiceItem("Phone screen repair", 1500.0, "repair", 0.7, 30),
                ServiceItem("Phone battery replacement", 800.0, "repair", 0.5, 20),
                ServiceItem("Shoe repair", 200.0, "repair", 0.8, 15),
                ServiceItem("Watch battery", 150.0, "repair", 0.9, 10),
                ServiceItem("General repair", 500.0, "repair", 0.7, 30)
            )
            "salon" -> listOf(
                ServiceItem("Hair braiding", 500.0, "beauty", 0.9, 120),
                ServiceItem("Hair washing", 100.0, "beauty", 0.95, 20),
                ServiceItem("Manicure", 200.0, "beauty", 0.9, 30),
                ServiceItem("Pedicure", 300.0, "beauty", 0.9, 45),
                ServiceItem("Facial", 400.0, "beauty", 0.8, 30)
            )
            "barber" -> listOf(
                ServiceItem("Haircut", 150.0, "beauty", 0.95, 20),
                ServiceItem("Beard trim", 50.0, "beauty", 0.95, 10),
                ServiceItem("Shave", 50.0, "beauty", 0.95, 15),
                ServiceItem("Haircut + beard", 200.0, "beauty", 0.95, 30)
            )
            "car_wash" -> listOf(
                ServiceItem("Small car wash", 200.0, "cleaning", 0.9, 20),
                ServiceItem("SUV wash", 350.0, "cleaning", 0.9, 30),
                ServiceItem("Interior clean", 300.0, "cleaning", 0.85, 45),
                ServiceItem("Full detail", 1000.0, "cleaning", 0.8, 120)
            )
            "welder" -> listOf(
                ServiceItem("Gate repair", 2000.0, "construction", 0.5, 120),
                ServiceItem("Window grills", 3000.0, "construction", 0.4, 180),
                ServiceItem("Metal chair", 1500.0, "construction", 0.5, 90),
                ServiceItem("Custom fabrication", 5000.0, "construction", 0.4, 480)
            )
            "tailor" -> listOf(
                ServiceItem("Dress sewing", 1500.0, "beauty", 0.8, 180),
                ServiceItem("Trouser alteration", 200.0, "beauty", 0.9, 20),
                ServiceItem("Shirt repair", 100.0, "beauty", 0.9, 15),
                ServiceItem("Uniform making", 2000.0, "beauty", 0.7, 240)
            )
            "laundry" -> listOf(
                ServiceItem("Kilo moja ya nguo", 50.0, "laundry", 0.9, 30),
                ServiceItem("Blanket/duvet", 200.0, "laundry", 0.8, 60),
                ServiceItem("Ironing (per piece)", 20.0, "laundry", 0.95, 5),
                ServiceItem("Dry cleaning", 300.0, "laundry", 0.7, 1440)
            )
            "cyber" -> listOf(
                ServiceItem("Printing (per page)", 10.0, "tech", 0.9, 1),
                ServiceItem("Photocopy (per page)", 5.0, "tech", 0.95, 1),
                ServiceItem("Scanning", 50.0, "tech", 0.9, 5),
                ServiceItem("Internet (per hour)", 50.0, "tech", 0.95, 60),
                ServiceItem("Typing (per page)", 50.0, "tech", 0.95, 15)
            )
            "mechanic" -> listOf(
                ServiceItem("Oil change", 500.0, "repair", 0.4, 30),
                ServiceItem("Brake repair", 1500.0, "repair", 0.5, 60),
                ServiceItem("Engine diagnosis", 300.0, "repair", 0.9, 30),
                ServiceItem("General service", 2000.0, "repair", 0.5, 120),
                ServiceItem("Tire change", 200.0, "repair", 0.8, 20)
            )
            else -> emptyList()
        }
    }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            map[name] = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                else -> null
            }
        }
        return map
    }
}

// ──────────────────────────────────────────────
// Data classes (updated from original)
// ──────────────────────────────────────────────

data class ServiceItem(
    val name: String,
    val price: Double,
    val category: String,
    val labourRatio: Double = 0.7,
    val durationMinutes: Int = 30
)

// ──────────────────────────────────────────────
// SQLiteOpenHelper — Service menu database
// ──────────────────────────────────────────────

class ServiceMenuDbHelper(context: Context) : SQLiteOpenHelper(
    context, "service_menu.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Service catalog
        db.execSQL("""
            CREATE TABLE services (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                service_name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'other',
                price REAL NOT NULL,
                labour_cost REAL,
                materials_cost REAL,
                duration_minutes INTEGER,
                description TEXT,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Service transactions
        db.execSQL("""
            CREATE TABLE service_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT,
                service_name TEXT NOT NULL,
                labour_cost REAL,
                materials_cost REAL,
                total_charged REAL NOT NULL,
                customer_name TEXT,
                created_at INTEGER NOT NULL
            )
        """)

        // Indexes
        db.execSQL("CREATE INDEX idx_services_worker ON services(worker_id)")
        db.execSQL("CREATE INDEX idx_services_category ON services(category)")
        db.execSQL("CREATE INDEX idx_services_active ON services(is_active)")
        db.execSQL("CREATE INDEX idx_services_name ON services(service_name)")
        db.execSQL("CREATE INDEX idx_transactions_worker ON service_transactions(worker_id)")
        db.execSQL("CREATE INDEX idx_transactions_created ON service_transactions(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }
}
