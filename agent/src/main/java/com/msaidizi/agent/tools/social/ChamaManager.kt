package com.msaidizi.agent.tools.social

import androidx.room.*
import com.msaidizi.core.database.ChamaDao
import com.msaidizi.core.database.ChamaMemberDao
import com.msaidizi.core.database.ChamaContributionDao
import com.msaidizi.core.database.ChamaPayoutDao
import com.msaidizi.core.model.ChamaEntity
import com.msaidizi.core.model.ChamaMemberEntity
import com.msaidizi.core.model.ChamaContributionEntity
import com.msaidizi.core.model.ChamaPayoutEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ──────────────────────────────────────────────
// Chama Manager — Group savings & rotation tool
// ──────────────────────────────────────────────

/**
 * ChamaManager — Manage informal savings groups (chamas).
 *
 * Chamas are the #1 financial vehicle for informal workers in Kenya,
 * handling KES 300B+ annually. Workers pool money on a weekly or monthly
 * basis, taking turns receiving the pot. This tool replaces paper/WhatsApp
 * coordination with structured tracking.
 *
 * Supports:
 *  - Creating chamas with members, contribution amounts, and frequency
 *  - Recording contributions (with M-Pesa reference tracking)
 *  - Tracking payouts in rotation order
 *  - Calculating balances, penalties for late payment
 *  - Group savings goals with progress tracking
 *  - Voice input in Swahili: "Nimechangia chama elfu moja"
 */
@Singleton
class ChamaManager @Inject constructor(
    private val chamaDao: ChamaDao,
    private val chamaMemberDao: ChamaMemberDao,
    private val chamaContributionDao: ChamaContributionDao,
    private val chamaPayoutDao: ChamaPayoutDao,
    private val approvalWorkflow: ChamaApprovalWorkflow
) : Tool {

    override val name = "chama_manager"
    override val description = "Manage chamas (informal savings groups). " +
            "Create chamas, record contributions, track payouts, calculate balances, " +
            "and monitor group savings goals. Supports Swahili voice input."

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf(
                "create", "contribute", "payout", "balance",
                "goal", "list", "status", "members", "history"
            ))
        // ── Create chama ──
        string("name", "Chama name (e.g. 'Vijana Wa Biashara')", required = false)
        string("members", "Comma-separated phone numbers of members", required = false)
        string("member_names", "Comma-separated member names (same order as members)", required = false)
        number("contribution", "Contribution amount per cycle in KES", required = false)
        enum("frequency", "Contribution frequency", listOf("weekly", "monthly"), required = false)
        number("savings_target", "Group savings target in KES (optional)", required = false)
        // ── Record contribution ──
        string("chama_id", "Chama ID", required = false)
        string("member_phone", "Member phone number", required = false)
        string("member_name", "Member name (alternative to phone)", required = false)
        number("amount", "Amount in KES", required = false)
        string("mpesa_ref", "M-Pesa transaction reference", required = false)
        string("date", "Date of contribution (YYYY-MM-DD, defaults to today)", required = false)
        // ── Payout ──
        string("recipient_phone", "Phone of payout recipient", required = false)
        string("recipient_name", "Name of payout recipient (alternative)", required = false)
        // ── Penalty ──
        number("penalty", "Penalty amount for late payment in KES", required = false)
        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input to parse (e.g. 'Nimechangia chama elfu moja')", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // If voice_text is provided, parse it first to extract action + params
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "list"
        return when (action.lowercase()) {
            "create" -> createChama(effectiveParams)
            "contribute" -> recordContribution(effectiveParams)
            "payout" -> recordPayout(effectiveParams)
            "balance" -> calculateBalances(effectiveParams)
            "goal" -> updateGoal(effectiveParams)
            "list" -> listChamas()
            "status" -> chamaStatus(effectiveParams)
            "members" -> listMembers(effectiveParams)
            "history" -> contributionHistory(effectiveParams)
            "approve" -> handleApproval(effectiveParams)
            "vote" -> handleVote(effectiveParams)
            "proposals" -> handleListProposals(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // CREATE CHAMA
    // ──────────────────────────────────────────────

    private suspend fun createChama(params: Map<String, String>): ToolResult {
        return try {
            val chamaName = params["name"]
                ?: return ToolResult.error(name, "Chama name required", "MISSING_NAME")
            val phones = params["members"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: return ToolResult.error(name, "Member phone numbers required", "MISSING_MEMBERS")
            val contribution = params["contribution"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Contribution amount required", "MISSING_CONTRIBUTION")
            val frequency = params["frequency"] ?: "monthly"
            val savingsTarget = params["savings_target"]?.toDoubleOrNull() ?: 0.0
            val memberNames = params["member_names"]?.split(",")?.map { it.trim() } ?: emptyList()

            // Create chama entity
            val chama = ChamaEntity(
                name = chamaName,
                contributionAmount = contribution,
                frequency = frequency,
                savingsTarget = savingsTarget,
                createdAt = System.currentTimeMillis()
            )
            val chamaId = chamaDao.insert(chama)

            // Create member entities — rotation order follows insertion order
            phones.forEachIndexed { index, phone ->
                val mName = memberNames.getOrNull(index) ?: "Member ${index + 1}"
                val member = ChamaMemberEntity(
                    chamaId = chamaId,
                    name = mName,
                    phone = phone,
                    rotationOrder = index + 1,
                    isActive = true,
                    joinedAt = System.currentTimeMillis()
                )
                chamaMemberDao.insert(member)
            }

            val totalPool = contribution * phones.size
            Timber.d("Created chama: $chamaName (id=$chamaId, ${phones.size} members, Ksh $contribution/$frequency)")

            ToolResult.success(
                name,
                data = mapOf(
                    "chama_id" to chamaId,
                    "name" to chamaName,
                    "members" to phones.size,
                    "contribution" to contribution,
                    "frequency" to frequency,
                    "pool_per_cycle" to totalPool,
                    "savings_target" to savingsTarget
                ),
                message = "Chama '$chamaName' created! " +
                        "${phones.size} members, Ksh ${"%,.0f".format(contribution)} $frequency. " +
                        "Pool per cycle: Ksh ${"%,.0f".format(totalPool)}" +
                        if (savingsTarget > 0) ". Target: Ksh ${"%,.0f".format(savingsTarget)}" else ""
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create chama")
            ToolResult.error(name, "Failed to create chama: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RECORD CONTRIBUTION
    // ──────────────────────────────────────────────

    private suspend fun recordContribution(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
            val mpesaRef = params["mpesa_ref"]
            val penalty = params["penalty"]?.toDoubleOrNull() ?: 0.0

            // Resolve member by phone or name
            val member = resolveMember(chamaId, params["member_phone"], params["member_name"])
                ?: return ToolResult.error(name, "Member not found. Provide member_phone or member_name.", "MEMBER_NOT_FOUND")

            // Verify chama exists
            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            // Record contribution
            val contribution = ChamaContributionEntity(
                chamaId = chamaId,
                memberId = member.id,
                memberName = member.name,
                amount = amount,
                mpesaRef = mpesaRef,
                penalty = penalty,
                date = params["date"] ?: todayDate(),
                timestamp = System.currentTimeMillis()
            )
            val contributionId = chamaContributionDao.insert(contribution)

            // Check if this contribution meets or exceeds the expected amount
            val expected = chama.contributionAmount
            val status = when {
                amount >= expected -> "✅ Full contribution"
                amount >= expected * 0.5 -> "⚠️ Partial contribution"
                else -> "❗ Below 50% of expected"
            }

            Timber.d("Recorded contribution: ${member.name} Ksh $amount to chama $chamaId")

            ToolResult.success(
                name,
                data = mapOf(
                    "contribution_id" to contributionId,
                    "chama" to chama.name,
                    "member" to member.name,
                    "amount" to amount,
                    "expected" to expected,
                    "mpesa_ref" to (mpesaRef ?: "none"),
                    "penalty" to penalty,
                    "status" to status
                ),
                message = "${member.name} contributed Ksh ${"%,.0f".format(amount)} to ${chama.name}" +
                        (mpesaRef?.let { " (M-Pesa: $it)" } ?: "") +
                        (if (penalty > 0) " + Ksh ${"%,.0f".format(penalty)} penalty" else "") +
                        ". $status"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record contribution")
            ToolResult.error(name, "Failed to record contribution: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RECORD PAYOUT — With approval workflow
    // ──────────────────────────────────────────────

    private suspend fun recordPayout(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val member = resolveMember(chamaId, params["recipient_phone"], params["recipient_name"])
                ?: return ToolResult.error(name, "Recipient member not found", "MEMBER_NOT_FOUND")

            // Calculate total pool from contributions since last payout
            val lastPayout = chamaPayoutDao.getLastPayout(chamaId)
            val sinceTimestamp = lastPayout?.timestamp ?: 0L
            val totalContributions = chamaContributionDao.getTotalContributedSince(chamaId, sinceTimestamp) ?: 0.0
            val totalPenalties = chamaContributionDao.getTotalPenaltiesSince(chamaId, sinceTimestamp) ?: 0.0
            val payoutAmount = totalContributions + totalPenalties // penalties go into the pot

            // ── HUMAN-IN-THE-LOOP: Chama Approval Workflow ──
            // Payouts require majority approval from chama members
            val proposalResult = approvalWorkflow.createProposal(
                chamaId = chamaId,
                proposerPhone = params["proposer_phone"] ?: params["recipient_phone"] ?: "",
                action = ChamaProposalAction.WITHDRAWAL,
                description = "Malipo kwa ${member.name} — KES ${"%,.0f".format(payoutAmount)}",
                amount = payoutAmount,
                metadata = mapOf(
                    "recipient" to member.name,
                    "cycle" to (chamaPayoutDao.getPayoutCount(chamaId) + 1).toString()
                )
            )

            if (proposalResult.status == ProposalStatus.PENDING) {
                // Approval needed — don't execute payout yet
                return ToolResult.success(
                    toolName = name,
                    data = mapOf(
                        "approval_required" to true,
                        "proposal_id" to proposalResult.proposalId,
                        "chama" to chama.name,
                        "recipient" to member.name,
                        "amount" to payoutAmount,
                        "required_approvals" to proposalResult.requiredApprovals,
                        "total_members" to proposalResult.totalMembers
                    ),
                    message = proposalResult.message
                )
            }

            // Determine this payout's cycle number
            val payoutCount = chamaPayoutDao.getPayoutCount(chamaId)
            val cycleNumber = payoutCount + 1

            val payout = ChamaPayoutEntity(
                chamaId = chamaId,
                recipientId = member.id,
                recipientName = member.name,
                amount = payoutAmount,
                cycleNumber = cycleNumber,
                timestamp = System.currentTimeMillis()
            )
            val payoutId = chamaPayoutDao.insert(payout)

            Timber.d("Recorded payout: ${member.name} received Ksh $payoutAmount from chama $chamaId (cycle $cycleNumber)")

            ToolResult.success(
                name,
                data = mapOf(
                    "payout_id" to payoutId,
                    "chama" to chama.name,
                    "recipient" to member.name,
                    "amount" to payoutAmount,
                    "cycle" to cycleNumber,
                    "contributions_total" to totalContributions,
                    "penalties_total" to totalPenalties
                ),
                message = "💰 Payout cycle $cycleNumber: ${member.name} received Ksh ${"%,.0f".format(payoutAmount)} from ${chama.name}" +
                        (if (totalPenalties > 0) " (includes Ksh ${"%,.0f".format(totalPenalties)} from penalties)" else "")
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record payout")
            ToolResult.error(name, "Failed to record payout: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CALCULATE BALANCES
    // ──────────────────────────────────────────────

    private suspend fun calculateBalances(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val members = chamaMemberDao.getByChama(chamaId).first()
            val contributions = chamaContributionDao.getByChama(chamaId).first()

            // Calculate current cycle contributions
            val lastPayout = chamaPayoutDao.getLastPayout(chamaId)
            val cycleStart = lastPayout?.timestamp ?: chama.createdAt

            val balanceReport = members.map { member ->
                val memberContributions = contributions.filter {
                    it.memberId == member.id && it.timestamp >= cycleStart
                }
                val totalPaid = memberContributions.sumOf { it.amount }
                val totalPenalties = memberContributions.sumOf { it.penalty }
                val expected = chama.contributionAmount
                val owing = (expected - totalPaid).coerceAtLeast(0.0)
                val overpaid = (totalPaid - expected).coerceAtLeast(0.0)

                mapOf(
                    "name" to member.name,
                    "phone" to member.phone,
                    "paid" to totalPaid,
                    "expected" to expected,
                    "owing" to owing,
                    "overpaid" to overpaid,
                    "penalties" to totalPenalties,
                    "status" to when {
                        totalPaid >= expected -> "✅ Paid"
                        totalPaid > 0 -> "⚠️ Partial"
                        else -> "❌ Unpaid"
                    },
                    "rotation_position" to member.rotationOrder
                )
            }

            val totalCollected = balanceReport.sumOf { it["paid"] as Double }
            val totalOwing = balanceReport.sumOf { it["owing"] as Double }
            val totalPenalties = balanceReport.sumOf { it["penalties"] as Double }
            val paidCount = balanceReport.count { it["status"] == "✅ Paid" }

            val report = buildString {
                appendLine("📊 ${chama.name} — Balance Report")
                appendLine("Cycle pool: Ksh ${"%,.0f".format(chama.contributionAmount * members.size)}")
                appendLine("Collected: Ksh ${"%,.0f".format(totalCollected)} / Ksh ${"%,.0f".format(chama.contributionAmount * members.size)}")
                appendLine("Members paid: $paidCount/${members.size}")
                appendLine("───")
                balanceReport.forEach { b ->
                    appendLine("${b["status"]} ${b["name"]}: Ksh ${"%,.0f".format(b["paid"] as Double)} / Ksh ${"%,.0f".format(b["expected"] as Double)}")
                    val owing = b["owing"] as Double
                    val penalties = b["penalties"] as Double
                    if (owing > 0) appendLine("   ↳ Owing: Ksh ${"%,.0f".format(owing)}")
                    if (penalties > 0) appendLine("   ↳ Penalties: Ksh ${"%,.0f".format(penalties)}")
                }
                if (totalOwing > 0) appendLine("───\n⚠️ Total outstanding: Ksh ${"%,.0f".format(totalOwing)}")
                if (totalPenalties > 0) appendLine("Penalties collected: Ksh ${"%,.0f".format(totalPenalties)}")
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "chama" to chama.name,
                    "members" to balanceReport,
                    "total_collected" to totalCollected,
                    "total_owing" to totalOwing,
                    "total_penalties" to totalPenalties,
                    "paid_count" to paidCount,
                    "total_members" to members.size
                ),
                message = report.trim()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate balances")
            ToolResult.error(name, "Failed to calculate balances: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // GOAL TRACKING
    // ──────────────────────────────────────────────

    private suspend fun updateGoal(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val target = params["savings_target"]?.toDoubleOrNull()

            // If a new target is provided, update it
            if (target != null) {
                chamaDao.updateSavingsTarget(chamaId, target)
                return ToolResult.success(
                    name,
                    data = mapOf("chama" to chama.name, "new_target" to target),
                    message = "Savings target for ${chama.name} updated to Ksh ${"%,.0f".format(target)}"
                )
            }

            // Otherwise, report goal progress
            val totalSaved = chamaContributionDao.getTotalContributed(chamaId) ?: 0.0
            val goalTarget = chama.savingsTarget

            if (goalTarget <= 0) {
                return ToolResult.success(
                    name,
                    message = "${chama.name} has no savings target set. Use savings_target to set one."
                )
            }

            val pct = (totalSaved / goalTarget * 100).toInt().coerceAtMost(100)
            val remaining = (goalTarget - totalSaved).coerceAtLeast(0.0)
            val progressBar = buildProgressBar(pct)

            ToolResult.success(
                name,
                data = mapOf(
                    "chama" to chama.name,
                    "saved" to totalSaved,
                    "target" to goalTarget,
                    "percentage" to pct,
                    "remaining" to remaining
                ),
                message = "🎯 ${chama.name} Savings Goal\n" +
                        "$progressBar $pct%\n" +
                        "Saved: Ksh ${"%,.0f".format(totalSaved)} / Ksh ${"%,.0f".format(goalTarget)}\n" +
                        if (remaining > 0) "Remaining: Ksh ${"%,.0f".format(remaining)}" else "🎉 Goal reached!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update goal")
            ToolResult.error(name, "Failed to update goal: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // LIST CHAMAS
    // ──────────────────────────────────────────────

    private suspend fun listChamas(): ToolResult {
        return try {
            val chamas = chamaDao.getAll().first()

            if (chamas.isEmpty()) {
                return ToolResult.success(name, message = "No chamas yet. Create one with 'create chama'.")
            }

            val list = chamas.joinToString("\n") { chama ->
                val memberCount = chamaMemberDao.getMemberCount(chama.id)
                val totalSaved = chamaContributionDao.getTotalContributed(chama.id) ?: 0.0
                val goalStr = if (chama.savingsTarget > 0) {
                    val pct = (totalSaved / chama.savingsTarget * 100).toInt().coerceAtMost(100)
                    " | Goal: $pct%"
                } else ""
                "👥 ${chama.name} (id: ${chama.id}) — " +
                        "${memberCount} members, Ksh ${"%,.0f".format(chama.contributionAmount)} ${chama.frequency}" +
                        " | Saved: Ksh ${"%,.0f".format(totalSaved)}$goalStr"
            }

            ToolResult.success(name, message = list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to list chamas")
            ToolResult.error(name, "Failed to list chamas: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CHAMA STATUS (summary)
    // ──────────────────────────────────────────────

    private suspend fun chamaStatus(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val members = chamaMemberDao.getByChama(chamaId).first()
            val totalSaved = chamaContributionDao.getTotalContributed(chamaId) ?: 0.0
            val totalPenalties = chamaContributionDao.getTotalPenalties(chamaId) ?: 0.0
            val payoutCount = chamaPayoutDao.getPayoutCount(chamaId)
            val lastPayout = chamaPayoutDao.getLastPayout(chamaId)

            // Current cycle stats
            val lastPayoutTs = lastPayout?.timestamp ?: chama.createdAt
            val cycleContributions = chamaContributionDao.getTotalContributedSince(chamaId, lastPayoutTs) ?: 0.0
            val cyclePaidCount = chamaContributionDao.getUniqueContributorsSince(chamaId, lastPayoutTs)
            val expectedPool = chama.contributionAmount * members.size

            // Next payout recipient (rotation)
            val nextRotationIndex = payoutCount % members.size
            val nextRecipient = members.find { it.rotationOrder == nextRotationIndex + 1 }

            // Goal progress
            val goalStr = if (chama.savingsTarget > 0) {
                val pct = (totalSaved / chama.savingsTarget * 100).toInt().coerceAtMost(100)
                "\n🎯 Goal: Ksh ${"%,.0f".format(totalSaved)} / Ksh ${"%,.0f".format(chama.savingsTarget)} ($pct%)"
            } else ""

            val report = buildString {
                appendLine("👥 ${chama.name}")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("📋 ${members.size} members | Ksh ${"%,.0f".format(chama.contributionAmount)} ${chama.frequency}")
                appendLine("")
                appendLine("📊 Current Cycle:")
                appendLine("   Collected: Ksh ${"%,.0f".format(cycleContributions)} / Ksh ${"%,.0f".format(expectedPool)}")
                appendLine("   Members paid: $cyclePaidCount/${members.size}")
                appendLine("")
                appendLine("💰 Total saved: Ksh ${"%,.0f".format(totalSaved)}")
                appendLine("📝 Payouts completed: $payoutCount")
                lastPayout?.let {
                    appendLine("   Last payout: ${it.recipientName} — Ksh ${"%,.0f".format(it.amount)}")
                }
                appendLine("")
                appendLine("⏭️ Next payout: ${nextRecipient?.name ?: "TBD"} (position ${nextRotationIndex + 1})")
                if (totalPenalties > 0) appendLine("⚠️ Total penalties collected: Ksh ${"%,.0f".format(totalPenalties)}")
                append(goalStr)
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "chama" to chama.name,
                    "chama_id" to chama.id,
                    "members" to members.size,
                    "contribution" to chama.contributionAmount,
                    "frequency" to chama.frequency,
                    "total_saved" to totalSaved,
                    "cycle_collected" to cycleContributions,
                    "cycle_paid" to cyclePaidCount,
                    "payouts_completed" to payoutCount,
                    "next_recipient" to (nextRecipient?.name ?: "TBD"),
                    "savings_target" to chama.savingsTarget
                ),
                message = report.trim()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get chama status")
            ToolResult.error(name, "Failed to get chama status: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // LIST MEMBERS
    // ──────────────────────────────────────────────

    private suspend fun listMembers(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val members = chamaMemberDao.getByChama(chamaId).first()

            val list = members.joinToString("\n") { m ->
                "  ${m.rotationOrder}. ${m.name} (${m.phone})" +
                        if (!m.isActive) " [inactive]" else ""
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "chama" to chama.name,
                    "members" to members.map {
                        mapOf(
                            "name" to it.name,
                            "phone" to it.phone,
                            "rotation_order" to it.rotationOrder,
                            "active" to it.isActive
                        )
                    }
                ),
                message = "👥 ${chama.name} Members (rotation order):\n$list"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to list members")
            ToolResult.error(name, "Failed to list members: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CONTRIBUTION HISTORY
    // ──────────────────────────────────────────────

    private suspend fun contributionHistory(params: Map<String, String>): ToolResult {
        return try {
            val chamaId = params["chama_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

            val chama = chamaDao.getById(chamaId)
                ?: return ToolResult.error(name, "Chama not found", "CHAMA_NOT_FOUND")

            val contributions = chamaContributionDao.getByChama(chamaId).first()

            if (contributions.isEmpty()) {
                return ToolResult.success(name, message = "No contributions recorded for ${chama.name} yet.")
            }

            val recent = contributions.sortedByDescending { it.timestamp }.take(20)
            val history = recent.joinToString("\n") { c ->
                val penaltyStr = if (c.penalty > 0) " +Ksh ${"%,.0f".format(c.penalty)} penalty" else ""
                val refStr = c.mpesaRef?.let { " [$it]" } ?: ""
                "  ${c.date} — ${c.memberName}: Ksh ${"%,.0f".format(c.amount)}$refStr$penaltyStr"
            }

            ToolResult.success(
                name,
                message = "📜 ${chama.name} — Recent Contributions:\n$history"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get contribution history")
            ToolResult.error(name, "Failed to get history: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER (Swahili)
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili voice input to extract chama action and parameters.
     *
     * Examples:
     *  - "Nimechangia chama elfu moja" → contribute, 1000
     *  - "Nimechangia vijana wa biashara mia tano" → contribute, 500
     *  - "Nimetuma pesa ya chama mbili elfu" → contribute, 2000
     *  - "Nimeshinda chama" → status
     *  - "Nani hajachangia?" → balance
     */
    fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        // ── Detect action ──
        when {
            // Contribution patterns
            lower.contains(Regex("nimechangia|nimetuma|nilituma|nimelipa|nilipia|nimeweka|niliweka|changia|tuma")) -> {
                params["action"] = "contribute"
            }
            // Payout patterns
            lower.contains(Regex("nimeshinda|ameshinda|ametwaa|nimetwaa|pata|pokea|lipa mtu|nimelipia mtu")) -> {
                params["action"] = "payout"
            }
            // Balance/status patterns
            lower.contains(Regex("nani.*hajachangia|salio|baki|deni|madeni|balance|owe|owing")) -> {
                params["action"] = "balance"
            }
            // Status patterns
            lower.contains(Regex("hali ya chama|chama ikoje|ripoti|report|status|vipi chama")) -> {
                params["action"] = "status"
            }
            // Create patterns
            lower.contains(Regex("unda|tengeneza|create|anza|fungua|start")) -> {
                params["action"] = "create"
            }
            // List patterns
            lower.contains(Regex("orodhesha|list|chama zangu|zote")) -> {
                params["action"] = "list"
            }
            // Goal patterns
            lower.contains(Regex("lengo|goal|target|akiba|savings")) -> {
                params["action"] = "goal"
            }
        }

        // ── Extract amount ──
        val amount = extractSwahiliAmount(lower)
        if (amount != null) {
            params["amount"] = amount.toString()
        }

        // ── Extract M-Pesa reference ──
        val mpesaRefRegex = Regex("""(?:mpesa|m-pesa|reference|ref|nambari)\s*[:\-]?\s*([A-Z0-9]{8,12})""", RegexOption.IGNORE_CASE)
        mpesaRefRegex.find(text)?.let {
            params["mpesa_ref"] = it.groupValues[1].uppercase()
        }
        // Also try standalone alphanumeric ref patterns (e.g., "QHK4Y6Z1PL")
        if (!params.containsKey("mpesa_ref")) {
            val refOnly = Regex("""\b([A-Z][A-Z0-9]{7,11})\b""").find(text)
            refOnly?.let { params["mpesa_ref"] = it.groupValues[1] }
        }

        // ── Extract member name if mentioned ──
        // Patterns like "ya John", "kwa Mary", "ya mwenyewe"
        val memberPattern = Regex("""(?:ya|kwa|kwa\s+ma)\s+(\w+)""", RegexOption.IGNORE_CASE)
        memberPattern.find(text)?.let {
            val name = it.groupValues[1]
            if (name !in listOf("chama", "pesa", "sasa", "leo", "hii", "hiyo")) {
                params["member_name"] = name.replaceFirstChar { c -> c.uppercase() }
            }
        }

        return params
    }

    /**
     * Extract amounts from Swahili voice input.
     * Supports: "elfu moja" (1000), "mia tano" (500), "mbili elfu" (2000),
     *           "elfu mbili na mia tano" (2500), plain digits, "ksh 500"
     */
    private fun extractSwahiliAmount(text: String): Double? {
        // Try currency pattern first
        val currencyRegex = Regex("""(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""", RegexOption.IGNORE_CASE)
        currencyRegex.find(text)?.let {
            return it.groupValues[1].ifEmpty { it.groupValues[2] }.toDoubleOrNull()
        }

        // Try plain numbers if no Swahili words present
        if (!text.contains(Regex("mia|elfu|laki|kumi|ishirini"))) {
            val plainNumber = Regex("""(\d+\.?\d*)""").find(text)
            return plainNumber?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )
        val swahiliTens = mapOf(
            "kumi" to 10, "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70, "themanini" to 80, "tisini" to 90
        )

        var total = 0.0

        // "laki" (100,000)
        Regex("laki\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 100_000
        }
        for ((word, value) in swahiliOnes) {
            if (text.contains("laki $word")) total += value * 100_000
        }

        // "elfu" (1,000) — can come as "elfu 2" or "mbili elfu"
        Regex("elfu\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 1000
        }
        for ((word, value) in swahiliOnes) {
            if (text.contains("elfu $word")) total += value * 1000
            // Also match "mbili elfu" pattern
            if (text.contains("$word elfu")) total += value * 1000
        }

        // "mia" (100)
        Regex("mia\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 100
        }
        for ((word, value) in swahiliOnes) {
            if (text.contains("mia $word")) total += value * 100
        }
        // Standalone "mia"
        if (text.contains("mia") && !text.contains(Regex("mia\\s+(mbili|tatu|nne|tano|sita|saba|nane|tisa|\\d)"))) {
            if (total == 0.0) total = 100.0
        }

        // Bare tens
        for ((word, value) in swahiliTens) {
            if (text.contains(word) && total == 0.0) total += value
        }

        // Handle "na" (and) for compound amounts: "elfu mbili na mia tano" = 2500
        // The above regexes already handle individual parts; "na" is just a connector.

        return if (total > 0) total else null
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    /**
     * Resolve a member by phone number or name within a chama.
     */
    private suspend fun resolveMember(
        chamaId: Long,
        phone: String?,
        name: String?
    ): ChamaMemberEntity? {
        if (phone != null) {
            val member = chamaMemberDao.getByPhone(chamaId, phone.trim())
            if (member != null) return member
        }
        if (name != null) {
            val member = chamaMemberDao.getByName(chamaId, name.trim())
            if (member != null) return member
        }
        return null
    }

    private fun todayDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun buildProgressBar(pct: Int, width: Int = 10): String {
        val filled = (pct * width / 100).coerceIn(0, width)
        return "[" + "█".repeat(filled) + "░".repeat(width - filled) + "]"
    }

    // ──────────────────────────────────────────────
    // APPROVAL WORKFLOW HANDLERS
    // ──────────────────────────────────────────────

    /**
     * Handle approval of a chama proposal.
     */
    private suspend fun handleApproval(params: Map<String, String>): ToolResult {
        val proposalId = params["proposal_id"]
            ?: return ToolResult.error(name, "Proposal ID required", "MISSING_PROPOSAL_ID")
        val voterPhone = params["voter_phone"]
            ?: return ToolResult.error(name, "Voter phone required", "MISSING_VOTER_PHONE")
        val approve = params["approve"]?.toBooleanStrictOrNull() ?: true

        val result = approvalWorkflow.vote(
            proposalId = proposalId,
            voterPhone = voterPhone,
            vote = if (approve) Vote.APPROVE else Vote.REJECT,
            comment = params["comment"]
        )

        return ToolResult.success(
            name,
            data = mapOf(
                "proposal_id" to proposalId,
                "status" to result.status.name,
                "approvals" to result.approvals,
                "rejections" to result.rejections,
                "total_members" to result.totalMembers
            ),
            message = result.message
        )
    }

    /**
     * Handle vote on a chama proposal (alias for approve with explicit vote).
     */
    private suspend fun handleVote(params: Map<String, String>): ToolResult {
        return handleApproval(params)
    }

    /**
     * List active proposals for a chama.
     */
    private suspend fun handleListProposals(params: Map<String, String>): ToolResult {
        val chamaId = params["chama_id"]?.toLongOrNull()
            ?: return ToolResult.error(name, "Chama ID required", "MISSING_CHAMA_ID")

        val proposals = approvalWorkflow.listActiveProposals(chamaId)

        if (proposals.isEmpty()) {
            return ToolResult.success(name, message = "Hakuna pendekezo linalosubiri kura.")
        }

        val message = buildString {
            appendLine("📋 PENDEKEZO ZINAZOSUBIRI KURA:")
            for (p in proposals) {
                appendLine()
                appendLine(p.message)
            }
        }

        return ToolResult.success(
            name,
            data = mapOf("proposals" to proposals.map { mapOf(
                "proposal_id" to it.proposalId,
                "action" to (it.action?.name ?: "unknown"),
                "approvals" to it.approvals,
                "required" to it.requiredApprovals,
                "total" to it.totalMembers
            )}),
            message = message
        )
    }
}
