package com.msaidizi.agent.guardrails

import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AccessControlManager — Pillar 4: Access Control.
 *
 * Implements RBAC with 6 roles and scope-based tool tokens.
 *
 * Roles:
 * - worker: Basic operations (record sales/expenses, check stock)
 * - agent: Worker + manage inventory, view reports
 * - field_officer: Agent + manage customers, view analytics
 * - admin: Full business operations + user management
 * - DPO: Data protection oversight, privacy audit access
 * - engineer: System configuration, model management, debugging
 *
 * Each tool call is validated against the caller's role and scope.
 * Scope tokens are time-limited and capability-scoped.
 */
@Singleton
class AccessControlManager @Inject constructor() {

    private val secureRandom = SecureRandom()
    private val activeSessions = mutableMapOf<String, SessionContext>()
    private val activeTokens = mutableMapOf<String, ScopeToken>()

    // ─── Role Definitions ───

    /**
     * Get the set of allowed tool names for a given role.
     */
    fun getAllowedTools(role: Role): Set<String> {
        return ROLE_PERMISSIONS[role] ?: emptySet()
    }

    /**
     * Check if a role can access a specific tool.
     */
    fun canAccessTool(role: Role, toolName: String): Boolean {
        return toolName in getAllowedTools(role)
    }

    /**
     * Check if a role can perform a specific action on a resource.
     */
    fun canPerformAction(role: Role, resource: String, action: String): Boolean {
        val permissions = ROLE_ACTION_PERMISSIONS[role] ?: return false
        val resourcePerms = permissions[resource] ?: return false
        return action in resourcePerms
    }

    // ─── Session Management ───

    /**
     * Create an authenticated session for a user.
     */
    fun createSession(userId: String, role: Role, deviceId: String): SessionContext {
        val sessionId = generateSecureId()
        val session = SessionContext(
            sessionId = sessionId,
            userId = userId,
            role = role,
            deviceId = deviceId,
            createdAt = Instant.now().epochSecond,
            expiresAt = Instant.now().epochSecond + SESSION_DURATION_SECONDS,
            allowedTools = getAllowedTools(role)
        )
        activeSessions[sessionId] = session
        Timber.d("Session created: $sessionId for user=$userId role=${role.name}")
        return session
    }

    /**
     * Validate a session is still active and not expired.
     */
    fun validateSession(sessionId: String): SessionContext? {
        val session = activeSessions[sessionId] ?: return null
        if (Instant.now().epochSecond > session.expiresAt) {
            activeSessions.remove(sessionId)
            Timber.w("Session expired: $sessionId")
            return null
        }
        return session
    }

    /**
     * Invalidate a session (logout).
     */
    fun invalidateSession(sessionId: String) {
        activeSessions.remove(sessionId)
        // Also revoke all tokens for this session
        activeTokens.entries.removeIf { it.value.sessionId == sessionId }
        Timber.d("Session invalidated: $sessionId")
    }

    // ─── Scope Tokens ───

    /**
     * Issue a scope-limited token for a specific tool execution.
     * Tokens are single-use or time-limited.
     */
    fun issueScopeToken(
        sessionId: String,
        toolName: String,
        scope: Set<String>,
        ttlSeconds: Long = TOKEN_TTL_SECONDS
    ): ScopeToken? {
        val session = validateSession(sessionId) ?: return null

        if (!canAccessTool(session.role, toolName)) {
            Timber.w("Role ${session.role.name} cannot access tool $toolName")
            return null
        }

        val token = ScopeToken(
            tokenId = generateSecureId(),
            sessionId = sessionId,
            toolName = toolName,
            scope = scope,
            issuedAt = Instant.now().epochSecond,
            expiresAt = Instant.now().epochSecond + ttlSeconds,
            maxUses = 1,
            useCount = 0
        )
        activeTokens[token.tokenId] = token
        Timber.d("Scope token issued: ${token.tokenId} for tool=$toolName scope=$scope")
        return token
    }

    /**
     * Validate and consume a scope token.
     * Returns true if the token is valid and the action is within scope.
     */
    fun validateAndConsumeToken(tokenId: String, requiredScope: String): Boolean {
        val token = activeTokens[tokenId] ?: return false

        // Check expiry
        if (Instant.now().epochSecond > token.expiresAt) {
            activeTokens.remove(tokenId)
            Timber.w("Scope token expired: $tokenId")
            return false
        }

        // Check use count
        if (token.useCount >= token.maxUses) {
            activeTokens.remove(tokenId)
            Timber.w("Scope token exhausted: $tokenId")
            return false
        }

        // Check scope
        if (requiredScope !in token.scope && "*" !in token.scope) {
            Timber.w("Scope token $tokenId missing required scope: $requiredScope")
            return false
        }

        // Consume
        token.useCount++
        if (token.useCount >= token.maxUses) {
            activeTokens.remove(tokenId)
        }

        return true
    }

    /**
     * Revoke all tokens for a session.
     */
    fun revokeTokens(sessionId: String) {
        activeTokens.entries.removeIf { it.value.sessionId == sessionId }
    }

    // ─── Authorization Check ───

    /**
     * Full authorization check: session + role + tool access + scope.
     */
    fun authorize(
        sessionId: String,
        toolName: String,
        requiredScope: String = "execute"
    ): AuthorizationResult {
        val session = validateSession(sessionId)
            ?: return AuthorizationResult(
                authorized = false,
                reason = "Invalid or expired session"
            )

        if (!canAccessTool(session.role, toolName)) {
            return AuthorizationResult(
                authorized = false,
                reason = "Role '${session.role.name}' cannot access tool '$toolName'",
                role = session.role
            )
        }

        return AuthorizationResult(
            authorized = true,
            reason = "Authorized",
            role = session.role,
            userId = session.userId
        )
    }

    // ─── Helpers ───

    private fun generateSecureId(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_DURATION_SECONDS = 3600L // 1 hour
        private const val TOKEN_TTL_SECONDS = 300L // 5 minutes

        /**
         * Role → allowed tool names.
         */
        val ROLE_PERMISSIONS: Map<Role, Set<String>> = run {
            val workerPerms = setOf(
                "record_transaction", "check_stock", "voice_pipeline",
                "language_detector", "code_switch_handler"
            )
            val agentPerms = workerPerms + setOf(
                "inventory_tracker", "daily_report", "weekly_report",
                "pricing_advisor", "customer_manager"
            )
            val fieldOfficerPerms = agentPerms + setOf(
                "anomaly_detector", "goal_tracker", "gamification_engine",
                "whatsapp_reporter", "restock_predictor"
            )
            val adminPerms = fieldOfficerPerms + setOf(
                "user_management", "business_config", "security_guard",
                "model_downloader", "sync_engine"
            )
            mapOf(
                Role.WORKER to workerPerms,
                Role.AGENT to agentPerms,
                Role.FIELD_OFFICER to fieldOfficerPerms,
                Role.ADMIN to adminPerms,
                Role.DPO to setOf(
                    "privacy_audit", "data_export", "data_deletion",
                    "consent_manager", "audit_trail_viewer"
                ),
                Role.ENGINEER to setOf(
                    "system_config", "model_management", "log_viewer",
                    "debug_tools", "performance_monitor", "sync_engine",
                    "model_downloader"
                )
            )
        }

        /**
         * Role → resource → allowed actions.
         */
        val ROLE_ACTION_PERMISSIONS: Map<Role, Map<String, Set<String>>> = mapOf(
            Role.WORKER to mapOf(
                "transaction" to setOf("create", "read_own"),
                "stock" to setOf("read"),
                "report" to setOf("read_own")
            ),
            Role.AGENT to mapOf(
                "transaction" to setOf("create", "read_own", "read_team"),
                "stock" to setOf("read", "update"),
                "report" to setOf("read_own", "read_team"),
                "customer" to setOf("read", "create")
            ),
            Role.FIELD_OFFICER to mapOf(
                "transaction" to setOf("create", "read_own", "read_team", "read_region"),
                "stock" to setOf("read", "update"),
                "report" to setOf("read_own", "read_team", "read_region"),
                "customer" to setOf("read", "create", "update"),
                "analytics" to setOf("read")
            ),
            Role.ADMIN to mapOf(
                "transaction" to setOf("create", "read", "delete"),
                "stock" to setOf("read", "update", "delete"),
                "report" to setOf("read", "create", "export"),
                "customer" to setOf("read", "create", "update", "delete"),
                "user" to setOf("read", "create", "update", "delete"),
                "business" to setOf("read", "update"),
                "system" to setOf("read", "update")
            ),
            Role.DPO to mapOf(
                "privacy" to setOf("read", "audit", "export", "delete"),
                "user_data" to setOf("read", "export", "delete"),
                "audit_log" to setOf("read"),
                "consent" to setOf("read", "update")
            ),
            Role.ENGINEER to mapOf(
                "system" to setOf("read", "update", "debug"),
                "model" to setOf("read", "update", "deploy"),
                "log" to setOf("read", "export"),
                "config" to setOf("read", "update")
            )
        )
    }
}

// ─── Enums & Data Classes ───

/**
 * RBAC Roles — hierarchical with increasing privileges.
 */
enum class Role(val displayName: String, val hierarchyLevel: Int) {
    WORKER("Worker", 0),
    AGENT("Agent", 1),
    FIELD_OFFICER("Field Officer", 2),
    ADMIN("Admin", 3),
    DPO("Data Protection Officer", 4),
    ENGINEER("Engineer", 5);

    fun hasAtLeast(required: Role): Boolean = this.hierarchyLevel >= required.hierarchyLevel
}

data class SessionContext(
    val sessionId: String,
    val userId: String,
    val role: Role,
    val deviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val allowedTools: Set<String>
)

data class ScopeToken(
    val tokenId: String,
    val sessionId: String,
    val toolName: String,
    val scope: Set<String>,
    val issuedAt: Long,
    val expiresAt: Long,
    val maxUses: Int,
    var useCount: Int
)

data class AuthorizationResult(
    val authorized: Boolean,
    val reason: String,
    val role: Role? = null,
    val userId: String? = null
)
