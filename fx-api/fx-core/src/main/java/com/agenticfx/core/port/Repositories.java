package com.agenticfx.core.port;

import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.audit.AuditEvent;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.policy.PolicyDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence ports. {@code update} methods use optimistic locking: they must fail with
 * {@link com.agenticfx.core.shared.ConflictException} (code {@code CONCURRENT_MODIFICATION}) when the stored
 * version differs, and call {@code versionIncremented()} on success. Inserts must enforce the uniqueness rules
 * documented in the schema and fail with a ConflictException.
 */
public final class Repositories {

    private Repositories() {
    }

    public interface Goals {
        void insert(FxGoal goal);
        void update(FxGoal goal);
        Optional<FxGoal> find(UUID id);
        List<FxGoal> findByUser(UUID userId);
        List<FxGoal> findActiveByPair(CurrencyPair pair);
        /** ACTIVE or PAUSED goals (those that can still expire). */
        List<FxGoal> findUnfinished();
    }

    public interface Mandates {
        void insert(AgentMandate mandate);
        void update(AgentMandate mandate);
        Optional<AgentMandate> find(MandateRef ref);
        Optional<AgentMandate> findActive(UUID goalId);
        /** Highest version, whatever its status. */
        Optional<AgentMandate> findLatest(UUID goalId);
        List<AgentMandate> findAll(UUID goalId);
    }

    public interface Proposals {
        void insert(ExecutionProposal proposal);
        void update(ExecutionProposal proposal);
        Optional<ExecutionProposal> find(UUID id);
        Optional<ExecutionProposal> findByIdempotencyKey(UUID userId, String key);
        /** PROPOSED, AWAITING_APPROVAL, APPROVED or EXECUTING — at most one per goal. */
        Optional<ExecutionProposal> findOpen(UUID goalId);
        List<ExecutionProposal> findByGoal(UUID goalId);
        List<ExecutionProposal> findExecutingSince(Instant before);
        Optional<Instant> lastRejectionAt(UUID goalId);
    }

    public interface Approvals {
        void insert(ApprovalRequest approval);
        void update(ApprovalRequest approval);
        Optional<ApprovalRequest> find(UUID id);
        Optional<ApprovalRequest> findPendingByProposal(UUID proposalId);
        List<ApprovalRequest> findByUser(UUID userId, boolean pendingOnly);
        List<ApprovalRequest> findPendingExpiredAt(Instant now);
    }

    public interface Conversions {
        void insert(FxConversion conversion);
        Optional<FxConversion> findByProposal(UUID proposalId);
        List<FxConversion> findByGoal(UUID goalId);
        List<FxConversion> findByUser(UUID userId);
    }

    public interface Accounts {
        Optional<CurrencyAccount> findByUser(UUID userId);
        void update(CurrencyAccount account);
    }

    public interface Quotes {
        void insert(FxQuote quote, UUID userId);
    }

    public interface PolicyEvaluations {
        void insert(PolicyDecision decision, UUID quoteId);
        List<PolicyDecision> findByProposal(UUID proposalId);
    }

    public interface AgentDecisions {
        void insert(AgentDecision decision);
        /** Newest first. */
        List<AgentDecision> findByGoal(UUID goalId, int limit);
    }

    public interface AuditLog {
        void append(AuditEvent event);
        /** Oldest first (timeline order). */
        List<AuditEvent> findByGoal(UUID goalId);
        /** Newest first. */
        List<AuditEvent> findByUser(UUID userId, int limit);
    }

    /** All repositories, grouped to keep service constructors readable. */
    public record Bundle(Goals goals, Mandates mandates, Proposals proposals, Approvals approvals,
                      Conversions conversions, Accounts accounts, Quotes quotes,
                      PolicyEvaluations policyEvaluations, AgentDecisions agentDecisions, AuditLog audit) {
    }
}
