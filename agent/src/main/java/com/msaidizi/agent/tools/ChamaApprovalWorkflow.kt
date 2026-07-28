package com.msaidizi.agent.tools

import com.msaidizi.agent.guardrails.AuditTrailManager
import com.msaidizi.agent.guardrails.AuditEventType
import com.msaidizi.agent.guardrails.AuditSeverity
import com.msaidizi.core.database.ChamaDao
import com.msaidizi.core.database.ChamaMemberDao
import com.msaidizi.core.model.ChamaEntity
import com.msaidizi.core.model.ChamaMemberEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChamaApprovalWorkflow — Majority Approval for Group Financial Decisions.
 *
 * For group financial decisions in chamas (savings groups), this workflow
 * requires majority member approval before execution:
 *
 * - Withdrawals from the group pot
 * - Large contributions changes
 * - New member additions
 * - Rule changes (contribution amount, frequency)
 * - Payout order changes
 *
 * Flow:
 * 1. Member proposes a financial action
 * 2. System creates approval request with required majority
 * 3. Other members vote (approve/reject)
 * 4. When majority reached → action executes
 * 5. If quorum not reached within timeout → action cancelled
 *
 * Voting rules:
 * - Simple majority (>50%) for standard decisions
 * - 2/3 majority for constitutional changes (rule changes, member removal)
 * - Unanimous for dissolution
 */
@Singleton
class ChamaApprovalWorkflow @Inject constructor(
    private val chamaDao: ChamaDao,
    private val chamaMemberDao: ChamaMemberDao,
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Configuration ───

    /** Default approval timeout: 48 hours */
    val DEFAULT_TIMEOUT_MS = 48 * 60 * 60 * 1000L

    // ─── Active Proposals ───

    private val activeProposals = ConcurrentHashMap<String, ChamaProposal>()

    /** History of all proposals for governance tracking */
    private val proposalHistory = mutableListOf<ChamaProposalRecord>()

    // ─── Core API ───

    /**
     * Create a new approval proposal for a chama financial decision.
     */
    suspend fun createProposal(
        chamaId: Long,
        proposerPhone: String,
        action: ChamaProposalAction,
        description: String,
        amount: Double? = null,
        metadata: Map<String, String> = emptyMap()
    ): ProposalResult {
        val chama = chamaDao.getById(chamaId)
            ?: return ProposalResult(
                proposalId = "",
                status = ProposalStatus.ERROR,
                message = "Chama haikupatikana."
            )

        val members = chamaMemberDao.getByChama(chamaId).first()
        val proposer = members.find { it.phone == proposerPhone.trim() }
            ?: return ProposalResult(
                proposalId = "",
                status = ProposalStatus.ERROR,
                message = "Wewe si mwanachama wa ${chama.name}."
            )

        val proposalId = UUID.randomUUID().toString()
        val requiredApprovals = calculateRequiredApprovals(members.size, action)
        val timeoutAt = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS

        val proposal = ChamaProposal(
            proposalId = proposalId,
            chamaId = chamaId,
            chamaName = chama.name,
            proposerPhone = proposerPhone,
            proposerName = proposer.name,
            action = action,
            description = description,
            amount = amount,
            totalMembers = members.size,
            requiredApprovals = requiredApprovals,
            votes = mutableMapOf(proposerPhone to Vote.APPROVE), // Proposer auto-approves
            createdAt = System.currentTimeMillis(),
            timeoutAt = timeoutAt,
            metadata = metadata
        )

        activeProposals[proposalId] = proposal

        // Audit log
        auditTrailManager.log(
            eventType = AuditEventType.FINANCIAL_TRANSACTION,
            actor = "ChamaApprovalWorkflow",
            action = "proposal_created",
            resource = action.name,
            details = mapOf(
                "proposal_id" to proposalId,
                "chama_id" to chamaId.toString(),
                "chama_name" to chama.name,
                "proposer" to proposer.name,
                "action" to action.name,
                "amount" to (amount?.toString() ?: "N/A"),
                "required_approvals" to requiredApprovals.toString(),
                "total_members" to members.size.toString()
            ),
            severity = AuditSeverity.HIGH
        )

        val message = buildProposalMessage(proposal, chama, members)

        Timber.d("Chama proposal created: $proposalId (${action.name}, requires $requiredApprovals/${members.size} votes)")
        return ProposalResult(
            proposalId = proposalId,
            status = ProposalStatus.PENDING,
            message = message,
            requiredApprovals = requiredApprovals,
            currentApprovals = 1, // Proposer auto-approves
            totalMembers = members.size
        )
    }

    /**
     * Cast a vote on a proposal.
     */
    suspend fun vote(
        proposalId: String,
        voterPhone: String,
        vote: Vote,
        comment: String? = null
    ): VoteResult {
        val proposal = activeProposals[proposalId]
            ?: return VoteResult(
                proposalId = proposalId,
                status = VoteStatus.NOT_FOUND,
                message = "Pendekezo halikupatikana."
            )

        // Check timeout
        if (System.currentTimeMillis() > proposal.timeoutAt) {
            activeProposals.remove(proposalId)
            recordProposal(proposal, ProposalOutcome.TIMED_OUT)
            return VoteResult(
                proposalId = proposalId,
                status = VoteStatus.EXPIRED,
                message = "Muda wa kupiga kura umekwisha."
            )
        }

        // Check if voter is a member
        val members = chamaMemberDao.getByChama(proposal.chamaId).first()
        val voter = members.find { it.phone == voterPhone.trim() }
            ?: return VoteResult(
                proposalId = proposalId,
                status = VoteStatus.NOT_MEMBER,
                message = "Wewe si mwanachama."
            )

        // Check if already voted
        if (proposal.votes.containsKey(voterPhone)) {
            return VoteResult(
                proposalId = proposalId,
                status = VoteStatus.ALREADY_VOTED,
                message = "Umeshapiga kura."
            )
        }

        // Record vote
        proposal.votes[voterPhone] = vote

        // Audit log
        auditTrailManager.log(
            eventType = AuditEventType.FINANCIAL_TRANSACTION,
            actor = "ChamaApprovalWorkflow",
            action = "vote_cast",
            resource = proposal.action.name,
            details = mapOf(
                "proposal_id" to proposalId,
                "voter" to voter.name,
                "vote" to vote.name,
                "comment" to (comment ?: "")
            ),
            severity = AuditSeverity.MEDIUM
        )

        // Check if majority reached
        val approvals = proposal.votes.values.count { it == Vote.APPROVE }
        val rejections = proposal.votes.values.count { it == Vote.REJECT }

        return when {
            approvals >= proposal.requiredApprovals -> {
                // Majority reached — approve
                activeProposals.remove(proposalId)
                recordProposal(proposal, ProposalOutcome.APPROVED)
                VoteResult(
                    proposalId = proposalId,
                    status = VoteStatus.MAJORITY_REACHED,
                    message = "✅ Pendekezo limekubaliwa! (${approvals}/${proposal.totalMembers} wamekubali)",
                    approvals = approvals,
                    rejections = rejections,
                    totalMembers = proposal.totalMembers
                )
            }
            rejections > (proposal.totalMembers - proposal.requiredApprovals) -> {
                // Majority rejection — can't reach approval threshold
                activeProposals.remove(proposalId)
                recordProposal(proposal, ProposalOutcome.REJECTED)
                VoteResult(
                    proposalId = proposalId,
                    status = VoteStatus.REJECTED,
                    message = "❌ Pendekezo limekataliwa. (${rejections}/${proposal.totalMembers} wamekataa)",
                    approvals = approvals,
                    rejections = rejections,
                    totalMembers = proposal.totalMembers
                )
            }
            else -> {
                // Still pending
                VoteResult(
                    proposalId = proposalId,
                    status = VoteStatus.VOTE_RECORDED,
                    message = "Kura yako imerekodwa. Inasubiri ${proposal.requiredApprovals - approvals} kura zaidi.",
                    approvals = approvals,
                    rejections = rejections,
                    totalMembers = proposal.totalMembers
                )
            }
        }
    }

    /**
     * Get the status of a proposal.
     */
    suspend fun getProposalStatus(proposalId: String): ProposalStatusResult {
        val proposal = activeProposals[proposalId]
            ?: return ProposalStatusResult(
                proposalId = proposalId,
                found = false,
                message = "Pendekezo halikupatikana."
            )

        val approvals = proposal.votes.values.count { it == Vote.APPROVE }
        val rejections = proposal.votes.values.count { it == Vote.REJECT }
        val pending = proposal.totalMembers - approvals - rejections

        val members = chamaMemberDao.getByChama(proposal.chamaId).first()
        val voterDetails = members.map { member ->
            val vote = proposal.votes[member.phone]
            VoterDetail(
                name = member.name,
                phone = member.phone,
                vote = vote,
                hasVoted = vote != null
            )
        }

        return ProposalStatusResult(
            proposalId = proposalId,
            found = true,
            chamaName = proposal.chamaName,
            action = proposal.action,
            description = proposal.description,
            amount = proposal.amount,
            proposerName = proposal.proposerName,
            approvals = approvals,
            rejections = rejections,
            pendingVotes = pending,
            requiredApprovals = proposal.requiredApprovals,
            totalMembers = proposal.totalMembers,
            voters = voterDetails,
            timeoutAt = proposal.timeoutAt,
            message = buildStatusMessage(proposal, approvals, rejections, pending)
        )
    }

    /**
     * List all active proposals for a chama.
     */
    suspend fun listActiveProposals(chamaId: Long): List<ProposalStatusResult> {
        val proposals = activeProposals.values.filter { it.chamaId == chamaId }
        return proposals.map { getProposalStatus(it.proposalId) }
    }

    /**
     * Cancel a proposal (only proposer or admin can cancel).
     */
    fun cancel(proposalId: String, cancellerPhone: String): Boolean {
        val proposal = activeProposals[proposalId] ?: return false
        if (proposal.proposerPhone != cancellerPhone) return false

        activeProposals.remove(proposalId)
        recordProposal(proposal, ProposalOutcome.CANCELLED)

        auditTrailManager.log(
            eventType = AuditEventType.SYSTEM_EVENT,
            actor = "ChamaApprovalWorkflow",
            action = "proposal_cancelled",
            resource = proposal.action.name,
            details = mapOf("proposal_id" to proposalId),
            severity = AuditSeverity.MEDIUM
        )

        return true
    }

    /**
     * Get governance history for a chama.
     */
    fun getGovernanceHistory(chamaId: Long, limit: Int = 20): List<ChamaProposalRecord> {
        synchronized(proposalHistory) {
            return proposalHistory
                .filter { it.chamaId == chamaId }
                .sortedByDescending { it.resolvedAt }
                .take(limit)
        }
    }

    // ─── Approval Thresholds ───

    /**
     * Calculate required approvals based on action type and group size.
     */
    private fun calculateRequiredApprovals(memberCount: Int, action: ChamaProposalAction): Int {
        val majorityType = when (action) {
            // Standard decisions: simple majority
            ChamaProposalAction.WITHDRAWAL,
            ChamaProposalAction.CONTRIBUTION_CHANGE,
            ChamaProposalAction.PAYOUT_REORDER,
            ChamaProposalAction.NEW_MEMBER -> MajorityType.SIMPLE

            // Constitutional changes: 2/3 majority
            ChamaProposalAction.RULE_CHANGE,
            ChamaProposalAction.REMOVE_MEMBER,
            ChamaProposalAction.FREQUENCY_CHANGE -> MajorityType.TWO_THIRDS

            // Dissolution: unanimous
            ChamaProposalAction.DISSOLUTION -> MajorityType.UNANIMOUS
        }

        return when (majorityType) {
            MajorityType.SIMPLE -> (memberCount / 2) + 1
            MajorityType.TWO_THIRDS -> ((memberCount * 2) / 3) + 1
            MajorityType.UNANIMOUS -> memberCount
        }
    }

    // ─── Message Building ───

    private fun buildProposalMessage(
        proposal: ChamaProposal,
        chama: ChamaEntity,
        members: List<ChamaMemberEntity>
    ): String {
        val amountStr = if (proposal.amount != null) " KES ${"%,.0f".format(proposal.amount)}" else ""
        val actionLabel = actionLabel(proposal.action)
        val majorityLabel = majorityLabel(proposal.requiredApprovals, proposal.totalMembers)

        return buildString {
            appendLine("📋 **PENDEKEZO JIPYA — ${chama.name}**")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("👤 Mpendekezaji: ${proposal.proposerName}")
            appendLine("📌 Kitendo: $actionLabel$amountStr")
            appendLine("📝 Maelezo: ${proposal.description}")
            appendLine()
            appendLine("🗳️ Inahitaji: $majorityLabel")
            appendLine("   (${proposal.requiredApprovals}/${proposal.totalMembers} wanachama wanahitaji kukubali)")
            appendLine()
            appendLine("Wanachama wanaweza kupiga kura:")
            members.forEach { member ->
                val status = if (member.phone == proposal.proposerPhone) "✅ (Mpendekezaji)" else "⏳"
                appendLine("   ${member.name}: $status")
            }
            appendLine()
            appendLine("⏰ Muda: masaa 48")
        }
    }

    private fun buildStatusMessage(
        proposal: ChamaProposal,
        approvals: Int,
        rejections: Int,
        pending: Int
    ): String {
        return buildString {
            appendLine("📊 **HALI YA PENDEKEZO**")
            appendLine("   ${proposal.description}")
            appendLine("   ✅ Wakubali: $approvals")
            appendLine("   ❌ Wakataa: $rejections")
            appendLine("   ⏳ Bado hawajapiga kura: $pending")
            appendLine("   Inahitaji: ${proposal.requiredApprovals}/${proposal.totalMembers}")
        }
    }

    private fun actionLabel(action: ChamaProposalAction): String {
        return when (action) {
            ChamaProposalAction.WITHDRAWAL -> "Kutoa pesa"
            ChamaProposalAction.CONTRIBUTION_CHANGE -> "Kubadilisha kiasi cha mchango"
            ChamaProposalAction.NEW_MEMBER -> "Kuongeza mwanachama mpya"
            ChamaProposalAction.REMOVE_MEMBER -> "Kuondoa mwanachama"
            ChamaProposalAction.RULE_CHANGE -> "Kubadilisha sheria"
            ChamaProposalAction.PAYOUT_REORDER -> "Kubadilisha mpangilio wa malipo"
            ChamaProposalAction.FREQUENCY_CHANGE -> "Kubadilisha mara ya mchango"
            ChamaProposalAction.DISSOLUTION -> "Kuvunja chama"
        }
    }

    private fun majorityLabel(required: Int, total: Int): String {
        val ratio = required.toDouble() / total
        return when {
            ratio >= 1.0 -> "Kura zote (unanimous)"
            ratio >= 0.67 -> "Theluthi mbili (2/3 majority)"
            else -> "Urahisi (simple majority)"
        }
    }

    private fun recordProposal(proposal: ChamaProposal, outcome: ProposalOutcome) {
        synchronized(proposalHistory) {
            proposalHistory.add(
                ChamaProposalRecord(
                    proposalId = proposal.proposalId,
                    chamaId = proposal.chamaId,
                    chamaName = proposal.chamaName,
                    action = proposal.action,
                    description = proposal.description,
                    amount = proposal.amount,
                    proposerName = proposal.proposerName,
                    totalVotes = proposal.votes.size,
                    approvals = proposal.votes.values.count { it == Vote.APPROVE },
                    rejections = proposal.votes.values.count { it == Vote.REJECT },
                    outcome = outcome,
                    createdAt = proposal.createdAt,
                    resolvedAt = System.currentTimeMillis()
                )
            )
        }
    }
}

// ─── Data Classes ───

enum class ChamaProposalAction {
    WITHDRAWAL,            // Withdraw from group pot
    CONTRIBUTION_CHANGE,   // Change contribution amount
    NEW_MEMBER,            // Add new member
    REMOVE_MEMBER,         // Remove member (2/3)
    RULE_CHANGE,           // Change rules (2/3)
    PAYOUT_REORDER,        // Change payout rotation
    FREQUENCY_CHANGE,      // Change contribution frequency
    DISSOLUTION            // Dissolve chama (unanimous)
}

enum class Vote {
    APPROVE,
    REJECT
}

enum class MajorityType {
    SIMPLE,      // >50%
    TWO_THIRDS,  // >=2/3
    UNANIMOUS    // 100%
}

enum class ProposalStatus {
    PENDING,
    ERROR
}

enum class VoteStatus {
    VOTE_RECORDED,
    MAJORITY_REACHED,
    REJECTED,
    EXPIRED,
    ALREADY_VOTED,
    NOT_MEMBER,
    NOT_FOUND
}

enum class ProposalOutcome {
    APPROVED,
    REJECTED,
    TIMED_OUT,
    CANCELLED
}

data class ProposalResult(
    val proposalId: String,
    val status: ProposalStatus,
    val message: String,
    val requiredApprovals: Int = 0,
    val currentApprovals: Int = 0,
    val totalMembers: Int = 0
)

data class VoteResult(
    val proposalId: String,
    val status: VoteStatus,
    val message: String,
    val approvals: Int = 0,
    val rejections: Int = 0,
    val totalMembers: Int = 0
)

data class ProposalStatusResult(
    val proposalId: String,
    val found: Boolean,
    val chamaName: String? = null,
    val action: ChamaProposalAction? = null,
    val description: String? = null,
    val amount: Double? = null,
    val proposerName: String? = null,
    val approvals: Int = 0,
    val rejections: Int = 0,
    val pendingVotes: Int = 0,
    val requiredApprovals: Int = 0,
    val totalMembers: Int = 0,
    val voters: List<VoterDetail> = emptyList(),
    val timeoutAt: Long = 0,
    val message: String
)

data class VoterDetail(
    val name: String,
    val phone: String,
    val vote: Vote?,
    val hasVoted: Boolean
)

data class ChamaProposalRecord(
    val proposalId: String,
    val chamaId: Long,
    val chamaName: String,
    val action: ChamaProposalAction,
    val description: String,
    val amount: Double?,
    val proposerName: String,
    val totalVotes: Int,
    val approvals: Int,
    val rejections: Int,
    val outcome: ProposalOutcome,
    val createdAt: Long,
    val resolvedAt: Long
)

internal data class ChamaProposal(
    val proposalId: String,
    val chamaId: Long,
    val chamaName: String,
    val proposerPhone: String,
    val proposerName: String,
    val action: ChamaProposalAction,
    val description: String,
    val amount: Double?,
    val totalMembers: Int,
    val requiredApprovals: Int,
    val votes: MutableMap<String, Vote>,
    val createdAt: Long,
    val timeoutAt: Long,
    val metadata: Map<String, String> = emptyMap()
)
