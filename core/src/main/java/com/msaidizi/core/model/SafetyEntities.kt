package com.msaidizi.core.model

import androidx.room.*

// ──────────────────────────────────────────────
// Emergency Contact Entity
// ──────────────────────────────────────────────

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,               // +254 format
    val relationship: String = "",    // "wife", "brother", "sacco_chairman", etc.
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// SOS Event Log Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "sos_events",
    indices = [Index(value = ["triggeredAt"])]
)
data class SOSEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
