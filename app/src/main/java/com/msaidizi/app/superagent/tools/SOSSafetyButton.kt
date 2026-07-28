package com.msaidizi.app.superagent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════
// FIX 1: SOS / SAFETY BUTTON — P0 CRITICAL
// ══════════════════════════════════════════════
// One-tap emergency button for boda boda riders.
// Sends GPS location to pre-set emergency contacts via SMS.
// Records 30 seconds of audio as evidence.
//
// This addresses the #1 life-threatening gap: motorcycle crashes
// are the leading cause of road traffic injuries in Kenya.
// ══════════════════════════════════════════════

// ──────────────────────────────────────────────
// Emergency Contact Entity (Room)
// ──────────────────────────────────────────────

@androidx.room.Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,               // +254 format
    val relationship: String = "",    // "wife", "brother", "sacco_chairman", etc.
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// SOS Event Log Entity
// ──────────────────────────────────────────────

@androidx.room.Entity(
    tableName = "sos_events",
    indices = [androidx.room.Index(value = ["triggeredAt"])]
)
data class SOSEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAt: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val audioFilePath: String? = null,
    val contactsNotified: Int = 0,        // how many contacts received SMS
    val smsMessage: String = "",
    val status: String = "triggered",     // triggered | cancelled | resolved
    val resolvedAt: Long? = null,
    val notes: String = ""
)

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@androidx.room.Dao
interface EmergencyContactDao {
    @androidx.room.Insert
    suspend fun insert(contact: EmergencyContactEntity): Long

    @androidx.room.Update
    suspend fun update(contact: EmergencyContactEntity)

    @androidx.room.Delete
    suspend fun delete(contact: EmergencyContactEntity)

    @androidx.room.Query("SELECT * FROM emergency_contacts WHERE isActive = 1 ORDER BY id ASC")
    fun getAllActive(): kotlinx.coroutines.flow.Flow<List<EmergencyContactEntity>>

    @androidx.room.Query("SELECT * FROM emergency_contacts WHERE isActive = 1")
    suspend fun getAllActiveOnce(): List<EmergencyContactEntity>

    @androidx.room.Query("SELECT * FROM emergency_contacts WHERE id = :id")
    suspend fun getById(id: Long): EmergencyContactEntity?

    @androidx.room.Query("SELECT COUNT(*) FROM emergency_contacts WHERE isActive = 1")
    suspend fun getActiveCount(): Int
}

@androidx.room.Dao
interface SOSEventDao {
    @androidx.room.Insert
    suspend fun insert(event: SOSEventEntity): Long

    @androidx.room.Update
    suspend fun update(event: SOSEventEntity)

    @androidx.room.Query("SELECT * FROM sos_events ORDER BY triggeredAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): kotlinx.coroutines.flow.Flow<List<SOSEventEntity>>

    @androidx.room.Query("SELECT * FROM sos_events WHERE id = :id")
    suspend fun getById(id: Long): SOSEventEntity?

    @androidx.room.Query("SELECT * FROM sos_events WHERE status = 'triggered' ORDER BY triggeredAt DESC LIMIT 1")
    suspend fun getActiveSOS(): SOSEventEntity?

    @androidx.room.Query("UPDATE sos_events SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, resolvedAt: Long = System.currentTimeMillis())
}

// ──────────────────────────────────────────────
// SOS SAFETY BUTTON TOOL
// ──────────────────────────────────────────────

/**
 * SOS Safety Button — one-tap emergency alert for boda boda riders.
 *
 * Actions:
 *  - trigger:   Activate SOS — get location, send SMS to contacts, record audio
 *  - cancel:    Cancel active SOS (false alarm)
 *  - resolve:   Mark SOS as resolved
 *  - add_contact: Add emergency contact
 *  - remove_contact: Remove emergency contact
 *  - list_contacts: List emergency contacts
 *  - history:   View SOS event history
 *  - test:      Test SOS without sending real SMS
 *
 * Voice (Swahili):
 *  - "Msaidizi, niko na hatari!" → trigger
 *  - "Sitisha!" → cancel
 *  - "Nimefika salama" → resolve
 *  - "Ongeza mwenyeji wangu" → add_contact
 */
@Singleton
class SOSSafetyButton @Inject constructor(
    private val emergencyContactDao: EmergencyContactDao,
    private val sosEventDao: SOSEventDao,
    private val appContext: Context
) : Tool {

    override val name = "sos_safety"
    override val description = "EMERGENCY SOS button — one-tap sends your GPS location to emergency contacts " +
            "via SMS and records 30 seconds of audio. For boda boda riders in danger. " +
            "Use 'trigger' to activate, 'cancel' for false alarm, 'add_contact' to set up contacts first."

    override val argsSchema = argSchema {
        enum("action", "SOS action",
            listOf("trigger", "cancel", "resolve", "add_contact", "remove_contact",
                "list_contacts", "history", "test"))

        // ── add_contact ──
        string("contact_name", "Emergency contact name (e.g. 'Mama', 'Chairman')", required = false)
        string("contact_phone", "Phone number in +254 format", required = false)
        string("relationship", "Relationship (e.g. 'wife', 'brother', 'sacco')", required = false)

        // ── remove_contact ──
        string("contact_id", "Contact ID to remove", required = false)

        // ── resolve/history ──
        string("event_id", "SOS event ID", required = false)
        string("notes", "Resolution notes", required = false)
        integer("limit", "History entries to return", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Parse voice input if provided
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "trigger"
        return when (action.lowercase()) {
            "trigger" -> triggerSOS(effectiveParams)
            "cancel" -> cancelSOS(effectiveParams)
            "resolve" -> resolveSOS(effectiveParams)
            "add_contact" -> addContact(effectiveParams)
            "remove_contact" -> removeContact(effectiveParams)
            "list_contacts" -> listContacts()
            "history" -> viewHistory(effectiveParams)
            "test" -> testSOS(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // TRIGGER SOS
    // ──────────────────────────────────────────────

    private suspend fun triggerSOS(params: Map<String, String>): ToolResult {
        return try {
            // 1. Get contacts
            val contacts = emergencyContactDao.getAllActiveOnce()
            if (contacts.isEmpty()) {
                return ToolResult.error(
                    name,
                    "⚠️ Hakuna contacts za dharura! Tafadhali ongeza contact kwanza.\n" +
                            "Sema: 'Ongeza mwenyeji wangu' au tumia add_contact.",
                    "NO_CONTACTS"
                )
            }

            // 2. Get GPS location
            val location = getLastKnownLocation()
            val lat = location?.latitude
            val lon = location?.longitude
            val accuracy = location?.accuracy

            // 3. Build emergency SMS
            val smsMessage = buildEmergencySMS(lat, lon, accuracy)

            // 4. Log SOS event
            val event = SOSEventEntity(
                latitude = lat,
                longitude = lon,
                locationAccuracy = accuracy,
                contactsNotified = contacts.size,
                smsMessage = smsMessage,
                status = "triggered"
            )
            val eventId = sosEventDao.insert(event)

            // 5. Send SMS to all contacts (background)
            var sentCount = 0
            for (contact in contacts) {
                try {
                    sendSMS(contact.phone, smsMessage)
                    sentCount++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send SOS SMS to ${contact.phone}")
                }
            }

            // 6. Start audio recording (30 seconds, background)
            val audioPath = startAudioRecording(eventId)

            // Update event with audio path
            if (audioPath != null) {
                sosEventDao.update(
                    event.copy(id = eventId, audioFilePath = audioPath, contactsNotified = sentCount)
                )
            }

            // 7. Build Google Maps link
            val mapsLink = if (lat != null && lon != null) {
                "https://maps.google.com/?q=$lat,$lon"
            } else {
                "GPS haikupatikana"
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "event_id" to eventId,
                    "latitude" to lat,
                    "longitude" to longitude,
                    "accuracy" to accuracy,
                    "contacts_notified" to sentCount,
                    "audio_recording" to (audioPath != null),
                    "maps_link" to mapsLink
                ),
                message = buildString {
                    appendLine("🚨🚨🚨 SOS IMEWASHWA! 🚨🚨🚨")
                    appendLine()
                    if (lat != null && lon != null) {
                        appendLine("📍 Eneo lako: $lat, $lon")
                        appendLine("🔗 $mapsLink")
                        accuracy?.let { appendLine("🎯 Usahihi: ${"%.0f".format(it)}m") }
                    } else {
                        appendLine("⚠️ GPS haikupatikana — wasiliana na contacts zako moja kwa moja")
                    }
                    appendLine()
                    appendLine("📱 SMS imetumwa kwa contacts $sentCount/${contacts.size}:")
                    contacts.forEach { c ->
                        appendLine("   • ${c.name} (${c.relationship}): ${c.phone}")
                    }
                    appendLine()
                    appendLine("🎤 Recording sauti kwa sekunde 30...")
                    appendLine()
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("Tafadhali pumzika na uwe salama.")
                    appendLine("Kama ni kosa, sema 'Sitisha' kufuta SOS.")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "SOS trigger failed")
            ToolResult.error(name, "SOS imeshindikana: ${e.message}. Piga simu 999/112 moja kwa moja!", "SOS_FAILED")
        }
    }

    // ──────────────────────────────────────────────
    // CANCEL SOS
    // ──────────────────────────────────────────────

    private suspend fun cancelSOS(params: Map<String, String>): ToolResult {
        return try {
            val activeSOS = sosEventDao.getActiveSOS()
                ?: return ToolResult.success(name, message = "✅ Hakuna SOS inayoendesha. Uko salama!")

            sosEventDao.updateStatus(activeSOS.id, "cancelled")

            // Notify contacts that it was a false alarm
            val contacts = emergencyContactDao.getAllActiveOnce()
            val cancelMsg = "✅ SOS ya msaidizi ilikuwa kosa. Niko salama! — ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
            for (contact in contacts) {
                try {
                    sendSMS(contact.phone, cancelMsg)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send cancel SMS")
                }
            }

            ToolResult.success(
                name,
                data = mapOf("event_id" to activeSOS.id, "status" to "cancelled"),
                message = "✅ SOS imefutwa. Contacts zako zimepata ujumbe kwamba uko salama."
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed to cancel SOS: ${e.message}", "CANCEL_FAILED")
        }
    }

    // ──────────────────────────────────────────────
    // RESOLVE SOS
    // ──────────────────────────────────────────────

    private suspend fun resolveSOS(params: Map<String, String>): ToolResult {
        return try {
            val eventId = params["event_id"]?.toLongOrNull()
            val notes = params["notes"] ?: ""

            val event = if (eventId != null) {
                sosEventDao.getById(eventId)
            } else {
                sosEventDao.getActiveSOS()
            }

            if (event == null) {
                return ToolResult.success(name, message = "✅ Hakuna SOS inayoendesha.")
            }

            sosEventDao.updateStatus(event.id, "resolved")
            if (notes.isNotEmpty()) {
                sosEventDao.update(event.copy(notes = notes, status = "resolved"))
            }

            ToolResult.success(
                name,
                data = mapOf("event_id" to event.id, "status" to "resolved"),
                message = "✅ SOS #${event.id} imetatuliwa. Furaha kuona uko salama! 🙏"
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD CONTACT
    // ──────────────────────────────────────────────

    private suspend fun addContact(params: Map<String, String>): ToolResult {
        return try {
            val name = params["contact_name"]
                ?: return ToolResult.error(name, "contact_name required. Sema: jina la mtu?", "MISSING_NAME")
            val phone = params["contact_phone"]
                ?: return ToolResult.error(name, "contact_phone required. Sema: nambari ya simu?", "MISSING_PHONE")
            val relationship = params["relationship"] ?: ""

            // Validate phone format
            val cleanPhone = normalizeKenyanPhone(phone)
            if (cleanPhone == null) {
                return ToolResult.error(
                    name,
                    "Nambari ya simu si sahihi: $phone. Tumia format: +2547XXXXXXXX au 07XXXXXXXX",
                    "INVALID_PHONE"
                )
            }

            // Check max contacts (5)
            val currentCount = emergencyContactDao.getActiveCount()
            if (currentCount >= 5) {
                return ToolResult.error(
                    name,
                    "Umefikia contact 5 za dharura. Futa moja kwanza ukitaka kuongeza nyingine.",
                    "MAX_CONTACTS"
                )
            }

            val contact = EmergencyContactEntity(
                name = name,
                phone = cleanPhone,
                relationship = relationship
            )
            val contactId = emergencyContactDao.insert(contact)

            ToolResult.success(
                name,
                data = mapOf(
                    "contact_id" to contactId,
                    "name" to name,
                    "phone" to cleanPhone,
                    "relationship" to relationship
                ),
                message = "✅ Contact ya dharura imeongezwa!\n" +
                        "👤 $name ($relationship)\n" +
                        "📱 $cleanPhone\n" +
                        "Jumla: ${currentCount + 1}/5 contacts"
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // REMOVE CONTACT
    // ──────────────────────────────────────────────

    private suspend fun removeContact(params: Map<String, String>): ToolResult {
        return try {
            val contactId = params["contact_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "contact_id required", "MISSING_ID")

            val contact = emergencyContactDao.getById(contactId)
                ?: return ToolResult.error(name, "Contact haipatikani", "NOT_FOUND")

            emergencyContactDao.delete(contact)

            ToolResult.success(
                name,
                message = "✅ Contact ${contact.name} imeondolewa."
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // LIST CONTACTS
    // ──────────────────────────────────────────────

    private suspend fun listContacts(): ToolResult {
        return try {
            val contacts = emergencyContactDao.getAllActiveOnce()

            if (contacts.isEmpty()) {
                return ToolResult.success(
                    name,
                    data = mapOf("contacts" to emptyList<Any>()),
                    message = "📱 Hakuna contacts za dharura bado.\n\n" +
                            "Ongeza contacts ili SOS iweze kukutumia SMS.\n" +
                            "Sema: 'Ongeza [jina] [nambari]' mfano: 'Ongeza Mama +254712345678'"
                )
            }

            val report = buildString {
                appendLine("📱 Contacts za Dharura (${contacts.size}/5):")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                contacts.forEachIndexed { i, c ->
                    appendLine("  ${i + 1}. ${c.name} (${c.relationship.ifEmpty { "—" }})")
                    appendLine("     📱 ${c.phone}")
                }
                appendLine()
                appendLine("SOS itawatumia SMS na location yako ukibonyeza kitufe.")
            }

            ToolResult.success(
                name,
                data = mapOf("contacts" to contacts, "count" to contacts.size),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HISTORY
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val limit = params["limit"]?.toIntOrNull() ?: 10
            val events = sosEventDao.getRecent(limit).first()

            if (events.isEmpty()) {
                return ToolResult.success(
                    name,
                    data = mapOf("events" to emptyList<Any>()),
                    message = "📜 Hakuna matukio ya SOS. Mungu akuwekee salama! 🙏"
                )
            }

            val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            val report = buildString {
                appendLine("📜 Historia ya SOS:")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                events.forEach { e ->
                    val statusEmoji = when (e.status) {
                        "triggered" -> "🔴"
                        "cancelled" -> "🟡"
                        "resolved" -> "🟢"
                        else -> "⚪"
                    }
                    appendLine("  $statusEmoji ${dateFormat.format(Date(e.triggeredAt))}")
                    if (e.latitude != null) {
                        appendLine("     📍 ${e.latitude}, ${e.longitude}")
                    }
                    appendLine("     📱 Contacts: ${e.contactsNotified} | Status: ${e.status}")
                    if (e.notes.isNotEmpty()) appendLine("     📝 ${e.notes}")
                    appendLine()
                }
            }

            ToolResult.success(name, data = mapOf("events" to events), message = report)
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // TEST SOS (dry run)
    // ──────────────────────────────────────────────

    private suspend fun testSOS(params: Map<String, String>): ToolResult {
        return try {
            val contacts = emergencyContactDao.getAllActiveOnce()
            if (contacts.isEmpty()) {
                return ToolResult.error(name, "Ongeza contacts kwanza kabla ya kujaribu.", "NO_CONTACTS")
            }

            val location = getLastKnownLocation()
            val lat = location?.latitude
            val lon = location?.longitude

            val testMsg = "🧪 HII NI MTIHANI — SOS Test\n" +
                    "Mtu wako anajaribu mfumo wa dharura.\n" +
                    if (lat != null) "📍 Location: $lat, $lon\nhttps://maps.google.com/?q=$lat,$lon"
                    else "📍 GPS haikupatikana"

            ToolResult.success(
                name,
                data = mapOf(
                    "contacts_count" to contacts.size,
                    "location" to mapOf("lat" to lat, "lon" to lon),
                    "sms_preview" to testMsg
                ),
                message = "🧪 MTIHANI WA SOS\n\n" +
                        "SMS itakayotumwa:\n━━━━\n$testMsg\n━━━━\n\n" +
                        "Contacts ${contacts.size} watapata ujumbe huu:\n" +
                        contacts.joinToString("\n") { "  • ${it.name}: ${it.phone}" } +
                        "\n\nTuma SMS za mtihani? (Hii ni test tu, si dharura)"
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Test failed: ${e.message}", "ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER (Swahili)
    // ──────────────────────────────────────────────

    private fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        when {
            // SOS trigger patterns
            lower.contains(Regex("hatari|sos|msaidizi.*hatari|naumia|nimepata ajali|nimeangukia|nipate msaada|haraka|emergency|polisi|ambulance")) -> {
                params["action"] = "trigger"
            }
            // Cancel patterns
            lower.contains(Regex("sitisha|futa|kosa|sio hatari|nipo salama|cancel|false alarm|ni sawa")) -> {
                params["action"] = "cancel"
            }
            // Resolve patterns
            lower.contains(Regex("nimefika salama|nimeshika|tatizo limeisha|resolved|nipo sawa")) -> {
                params["action"] = "resolve"
            }
            // Add contact patterns
            lower.contains(Regex("ongeza.*contact|ongeza.*nambari|weka.*nambari|jina.*nambari|add.*contact")) -> {
                params["action"] = "add_contact"
                // Try to extract name and phone
                val phoneMatch = Regex("(\\+?254\\d{9}|07\\d{8}|01\\d{8})").find(text)
                phoneMatch?.let { params["contact_phone"] = it.value }

                val nameMatch = Regex("(?:ongeza|weka|jina)\\s+(\\w+)").find(text)
                nameMatch?.let { params["contact_name"] = it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
            }
            // List contacts
            lower.contains(Regex("contacts|nambari.*dharura|list.*contact|nani.*namba")) -> {
                params["action"] = "list_contacts"
            }
            // History
            lower.contains(Regex("historia.*sos|sos.*zangu|matukio|history")) -> {
                params["action"] = "history"
            }
        }

        return params
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun buildEmergencySMS(lat: Double?, lon: Double?, accuracy: Float?): String {
        val time = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("🚨 DHARURA! Msaidizi SOS!")
            appendLine("Mtu wako wa bodaboda ana hatari.")
            appendLine("Saa: $time")
            if (lat != null && lon != null) {
                appendLine("📍 Eneo: $lat, $lon")
                appendLine("🔗 https://maps.google.com/?q=$lat,$lon")
                accuracy?.let { appendLine("🎯 Usahihi: ${"%.0f".format(it)}m") }
            } else {
                appendLine("⚠️ GPS haikupatikana — piga simu haraka!")
            }
            appendLine()
            appendLine("Piga 999 au 112 (ambulance/polisi)")
            appendLine("— Msaidizi App")
        }
    }

    private fun getLastKnownLocation(): Location? {
        return try {
            val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w("Location permission not granted")
                return null
            }
            // Try GPS first, then network
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            gpsLocation ?: networkLocation
        } catch (e: Exception) {
            Timber.e(e, "Failed to get location")
            null
        }
    }

    private fun sendSMS(phone: String, message: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            // Split message if > 160 chars
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            Timber.d("SOS SMS sent to $phone")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send SMS to $phone")
            throw e
        }
    }

    private fun startAudioRecording(eventId: Long): String? {
        return try {
            val dir = File(appContext.filesDir, "sos_audio")
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "sos_${eventId}_${System.currentTimeMillis()}.3gp")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            // Stop after 30 seconds
            CoroutineScope(Dispatchers.IO).launch {
                delay(30_000)
                try {
                    recorder.stop()
                    recorder.release()
                    Timber.d("SOS audio saved: ${file.absolutePath}")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to stop audio recorder")
                }
            }

            file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio recording")
            null
        }
    }

    private fun normalizeKenyanPhone(phone: String): String? {
        val cleaned = phone.replace(Regex("[\\s\\-()]"), "")
        return when {
            cleaned.matches(Regex("\\+254\\d{9}")) -> cleaned
            cleaned.matches(Regex("254\\d{9}")) -> "+$cleaned"
            cleaned.matches(Regex("07\\d{8}")) -> "+254${cleaned.substring(1)}"
            cleaned.matches(Regex("01\\d{8}")) -> "+254${cleaned.substring(1)}"
            cleaned.matches(Regex("7\\d{8}")) -> "+254$cleaned"
            else -> null
        }
    }
}
