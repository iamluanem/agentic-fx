package com.agenticfx.core.application;

import com.agenticfx.core.account.CurrencyAccount;
import com.agenticfx.core.agent.AgentDecision;
import com.agenticfx.core.agent.AgentDecisionType;
import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.approval.ApprovalRequest;
import com.agenticfx.core.audit.AuditEventType;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.execution.ExecutionProposal;
import com.agenticfx.core.execution.ExecutionStatus;
import com.agenticfx.core.execution.ProposalOrigin;
import com.agenticfx.core.execution.RejectionSource;
import com.agenticfx.core.fx.ConversionRequest;
import com.agenticfx.core.fx.ConversionResult;
import com.agenticfx.core.fx.FxConversion;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.goal.FxGoal;
import com.agenticfx.core.goal.GoalStatus;
import com.agenticfx.core.mandate.AgentMandate;
import com.agenticfx.core.mandate.MandatePermissions;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.policy.AccountState;
import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.policy.PolicyDecision;
import com.agenticfx.core.policy.PolicyEngine;
import com.agenticfx.core.port.EventPublisher;
import com.agenticfx.core.port.Metrics;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.RequestContext;
import com.agenticfx.core.port.Transactions;
import com.agenticfx.core.shared.ConflictException;
import com.agenticfx.core.shared.DomainException;
import com.agenticfx.core.shared.NotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.agenticfx.core.money.MoneyFormat.display;

/**
 * The only path from a proposal to moved money:
 * <pre>Proposal → Policy Engine → (Approval) → quote revalidation → Policy Engine → provider → settlement</pre>
 * Funds are reserved (source debited) in the same transaction as the ALLOW decision, and the provider call is
 * idempotent on the proposal id, so retries and crashes cannot execute a proposal twice.
 */
public final class ExecutionService {

    public record ProposeCommand(UUID goalId, UUID userId, Money sourceAmount, ProposalOrigin origin,
                                 String whatHappened, String why, String idempotencyKey, boolean forceApproval) {
        public ProposeCommand {
            Objects.requireNonNull(goalId, "goalId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(sourceAmount, "sourceAmount");
            Objects.requireNonNull(origin, "origin");
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
                throw new IllegalArgumentException("idempotencyKey is required (max 128 chars)");
            }
            if (!sourceAmount.isPositive()) {
                throw new IllegalArgumentException("sourceAmount must be positive");
            }
        }
    }

    /** Result of a use case. {@code decision}, {@code approval} and {@code conversion} may be null. */
    public record Outcome(ExecutionProposal proposal, PolicyDecision decision, ApprovalRequest approval,
                          FxConversion conversion) {
        public ExecutionStatus status() {
            return proposal.status();
        }
    }

    private final Repositories.Bundle repos;
    private final PolicyEngine policy;
    private final FxProvider provider;
    private final EventPublisher events;
    private final Transactions tx;
    private final Metrics metrics;
    private final Clock clock;
    private final Duration approvalTtl;
    private final Audit audit;

    public ExecutionService(Repositories.Bundle repos, PolicyEngine policy, FxProvider provider,
                            EventPublisher events, Transactions tx, Metrics metrics, RequestContext context,
                            Clock clock, Duration approvalTtl) {
        this.repos = repos;
        this.policy = policy;
        this.provider = provider;
        this.events = events;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
        this.approvalTtl = approvalTtl;
        this.audit = new Audit(clock, context);
    }

    // ================================================================================ propose

    public Outcome propose(ProposeCommand cmd) {
        Outcome staged = tx.inTransaction(() -> stage(cmd));
        return staged.status() == ExecutionStatus.EXECUTING ? executeAndSettle(staged) : staged;
    }

    private Outcome stage(ProposeCommand cmd) {
        Optional<ExecutionProposal> existing = repos.proposals().findByIdempotencyKey(cmd.userId(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            ExecutionProposal p = existing.get();
            if (!p.goalId().equals(cmd.goalId()) || !p.sourceAmount().equals(cmd.sourceAmount())) {
                throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different request");
            }
            return replay(p);
        }

        FxGoal goal = ownedGoal(cmd.goalId(), cmd.userId());
        AgentMandate mandate = repos.mandates().findActive(goal.id())
                .or(() -> repos.mandates().findLatest(goal.id()))
                .orElseThrow(() -> new ConflictException("NO_MANDATE", "Goal has no mandate"));
        repos.proposals().findOpen(goal.id()).ifPresent(open -> {
            throw new ConflictException("PROPOSAL_ALREADY_OPEN",
                    "Proposal " + open.id() + " is still " + open.status() + " for this goal");
        });

        FxQuote quote = provider.getQuote(cmd.sourceAmount().currency(), goal.pair().target(),
                cmd.sourceAmount().amount());
        repos.quotes().insert(quote, cmd.userId());

        Explanation explanation = explain(cmd, quote, goal, mandate);
        ExecutionProposal proposal = ExecutionProposal.propose(goal.id(), cmd.userId(), mandate.ref(), quote,
                cmd.origin(), explanation, cmd.idempotencyKey(), clock.instant());
        repos.proposals().insert(proposal);

        String actor = actorOf(cmd.origin());
        repos.agentDecisions().insert(AgentDecision.of(goal.id(), cmd.userId(), AgentDecisionType.CONVERSION_PROPOSED,
                cmd.origin(), explanation.narrative(), explanation, proposal.id(), clock.instant()));
        metrics.agentDecision(AgentDecisionType.CONVERSION_PROPOSED);
        repos.audit().append(audit.event(AuditEventType.EXECUTION_PROPOSED, cmd.userId(), actor)
                .goalId(goal.id()).mandate(mandate.ref()).proposalId(proposal.id())
                .agentDecision(explanation.narrative())
                .quote(quote.id(), quote.rate().rate().toPlainString(), quote.provider())
                .requested(quote.sourceAmount())
                .summary((cmd.origin() == ProposalOrigin.LLM_AGENT ? "Agent" : "Strategy engine")
                        + " proposed converting " + display(quote.sourceAmount()) + " → "
                        + display(quote.targetAmount()) + ".")
                .build());
        events.publish(new DomainEvent.ExecutionProposed(UUID.randomUUID(), clock.instant(), goal.id(),
                cmd.userId(), proposal.id(), cmd.origin().name(), quote.sourceAmount().amount(),
                quote.sourceAmount().currency().name()));

        PolicyDecision decision = evaluate(proposal, mandate, goal);
        return route(proposal, decision, goal, cmd.forceApproval());
    }

    // ================================================================================ approval

    public Outcome approve(UUID approvalId, UUID actor) {
        Outcome staged = tx.inTransaction(() -> {
            ApprovalRequest approval = ownedApproval(approvalId, actor);
            ExecutionProposal proposal = proposal(approval.proposalId());
            Instant now = clock.instant();
            if (approval.isExpiredAt(now)) {
                return expire(approval, proposal);
            }
            approval.approve(actor, now);
            repos.approvals().update(approval);
            proposal.approve(now);
            repos.audit().append(audit.event(AuditEventType.APPROVAL_GRANTED, actor, Audit.user(actor))
                    .goalId(proposal.goalId()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                    .requested(proposal.sourceAmount())
                    .summary("You approved converting " + display(proposal.sourceAmount()) + ".")
                    .build());
            events.publish(new DomainEvent.ApprovalGranted(UUID.randomUUID(), now, proposal.goalId(),
                    proposal.userId(), proposal.id(), approval.id()));

            revalidateQuote(proposal);
            AgentMandate mandate = repos.mandates().find(proposal.mandateRef())
                    .orElseThrow(() -> new NotFoundException("Mandate", proposal.mandateRef()));
            FxGoal goal = ownedGoal(proposal.goalId(), proposal.userId());
            PolicyDecision decision = evaluate(proposal, mandate, goal);
            Outcome routed = route(proposal, decision, goal, false);
            return new Outcome(routed.proposal(), routed.decision(),
                    routed.approval() != null ? routed.approval() : approval, routed.conversion());
        });
        return staged.status() == ExecutionStatus.EXECUTING ? executeAndSettle(staged) : staged;
    }

    public Outcome reject(UUID approvalId, UUID actor, String reason) {
        return tx.inTransaction(() -> {
            ApprovalRequest approval = ownedApproval(approvalId, actor);
            ExecutionProposal proposal = proposal(approval.proposalId());
            Instant now = clock.instant();
            String note = UntrustedText.clean(reason, 500);
            approval.reject(actor, note, now);
            repos.approvals().update(approval);
            proposal.reject(RejectionSource.USER, note == null ? "Rejected by user" : note, now);
            repos.proposals().update(proposal);
            repos.audit().append(audit.event(AuditEventType.APPROVAL_REJECTED, actor, Audit.user(actor))
                    .goalId(proposal.goalId()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                    .requested(proposal.sourceAmount())
                    .summary("You rejected converting " + display(proposal.sourceAmount()) + "."
                            + (note == null ? "" : " Note: " + note))
                    .build());
            events.publish(new DomainEvent.ApprovalRejected(UUID.randomUUID(), now, proposal.goalId(),
                    proposal.userId(), proposal.id(), approval.id(), note));
            return new Outcome(proposal, null, approval, null);
        });
    }

    /** Sweeper: pending approvals past their deadline are expired and their proposals rejected. */
    public int expireApprovals() {
        List<ApprovalRequest> due = tx.inTransaction(() -> repos.approvals().findPendingExpiredAt(clock.instant()));
        int count = 0;
        for (ApprovalRequest candidate : due) {
            try {
                tx.inTransaction(() -> {
                    ApprovalRequest approval = repos.approvals().find(candidate.id()).orElseThrow();
                    if (approval.status() != com.agenticfx.core.approval.ApprovalStatus.PENDING) {
                        return null;
                    }
                    return expire(approval, proposal(approval.proposalId()));
                });
                count++;
            } catch (ConflictException raced) {
                // decided concurrently by the user; nothing to do
            }
        }
        return count;
    }

    /** Sweeper: re-drives proposals stuck in EXECUTING (crash between reservation and settlement). */
    public int recoverExecutions(Duration olderThan) {
        List<ExecutionProposal> stuck = tx.inTransaction(
                () -> repos.proposals().findExecutingSince(clock.instant().minus(olderThan)));
        stuck.forEach(p -> executeAndSettle(new Outcome(p, null, null, null)));
        return stuck.size();
    }

    /** Called by goal lifecycle changes, inside the caller's transaction. Executing proposals are left alone. */
    void supersedeOpenProposal(UUID goalId, String reason) {
        repos.proposals().findOpen(goalId).ifPresent(p -> {
            if (p.status() == ExecutionStatus.EXECUTING) {
                throw new ConflictException("CONVERSION_IN_PROGRESS",
                        "A conversion is executing; try again in a few seconds");
            }
            Instant now = clock.instant();
            repos.approvals().findPendingByProposal(p.id()).ifPresent(a -> {
                a.expire(now);
                repos.approvals().update(a);
            });
            p.reject(RejectionSource.SUPERSEDED, reason, now);
            repos.proposals().update(p);
        });
    }

    // ================================================================================ internals

    private PolicyDecision evaluate(ExecutionProposal proposal, AgentMandate mandate, FxGoal goal) {
        CurrencyAccount account = repos.accounts().findByUser(proposal.userId())
                .orElseThrow(() -> new NotFoundException("Account for user", proposal.userId()));
        Money committed = repos.proposals().findByGoal(goal.id()).stream()
                .filter(p -> p.status().isInFlight() && !p.id().equals(proposal.id()))
                .map(ExecutionProposal::sourceAmount)
                .filter(m -> m.currency() == goal.pair().source())
                .reduce(Money.zero(goal.pair().source()), Money::plus);

        PolicyDecision decision = policy.evaluate(mandate, proposal, AccountState.of(account, goal, committed));

        repos.policyEvaluations().insert(decision, proposal.quote().id());
        metrics.policyDecision(decision.decision());
        repos.audit().append(audit.event(AuditEventType.POLICY_EVALUATED, proposal.userId(), "policy-engine")
                .goalId(goal.id()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                .policy(decision.decision(), decision.reasonCodes())
                .quote(proposal.quote().id(), proposal.quote().rate().rate().toPlainString(),
                        proposal.quote().provider())
                .requested(proposal.sourceAmount())
                .summary("Policy evaluation: " + decision.decision() + ". " + decision.explanation())
                .details(Map.of("policyVersion", decision.policyVersion()))
                .build());
        events.publish(new DomainEvent.PolicyEvaluated(UUID.randomUUID(), clock.instant(), goal.id(),
                proposal.userId(), proposal.id(), decision.decision(), decision.reasonCodes()));
        return decision;
    }

    private Outcome route(ExecutionProposal proposal, PolicyDecision decision, FxGoal goal, boolean forceApproval) {
        Instant now = clock.instant();
        Decision d = decision.decision();
        if (d == Decision.DENY) {
            proposal.reject(RejectionSource.POLICY, codes(decision), now);
            repos.proposals().update(proposal);
            return new Outcome(proposal, decision, null, null);
        }
        if (d == Decision.REQUIRE_APPROVAL || forceApproval) {
            String reason = d == Decision.REQUIRE_APPROVAL ? codes(decision) : "AGENT_REQUESTED_APPROVAL";
            proposal.awaitApproval(reason, now);
            repos.proposals().update(proposal);
            ApprovalRequest approval = ApprovalRequest.open(proposal.id(), goal.id(), proposal.userId(), now,
                    now.plus(approvalTtl));
            repos.approvals().insert(approval);
            metrics.approvalRequested();
            repos.audit().append(audit.event(AuditEventType.APPROVAL_REQUESTED, proposal.userId(), "system")
                    .goalId(goal.id()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                    .requested(proposal.sourceAmount()).policy(d, decision.reasonCodes())
                    .summary("Waiting for your approval to convert " + display(proposal.sourceAmount()) + " → "
                            + display(proposal.quote().targetAmount()) + ". " + decision.explanation())
                    .build());
            events.publish(new DomainEvent.ApprovalRequested(UUID.randomUUID(), now, goal.id(), proposal.userId(),
                    proposal.id(), approval.id(), approval.expiresAt()));
            return new Outcome(proposal, decision, approval, null);
        }

        // ALLOW: reserve funds and start execution in this transaction.
        CurrencyAccount account = repos.accounts().findByUser(proposal.userId()).orElseThrow();
        account.debit(proposal.sourceAmount());
        repos.accounts().update(account);
        proposal.startExecution(now);
        repos.proposals().update(proposal);
        repos.audit().append(audit.event(AuditEventType.CONVERSION_STARTED, proposal.userId(), "execution-engine")
                .goalId(goal.id()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                .quote(proposal.quote().id(), proposal.quote().rate().rate().toPlainString(), provider.name())
                .requested(proposal.sourceAmount())
                .summary("Reserved " + display(proposal.sourceAmount()) + " and sent the conversion to "
                        + provider.name() + ".")
                .build());
        events.publish(new DomainEvent.ConversionStarted(UUID.randomUUID(), now, goal.id(), proposal.userId(),
                proposal.id()));
        return new Outcome(proposal, decision, null, null);
    }

    private Outcome executeAndSettle(Outcome staged) {
        ExecutionProposal proposal = staged.proposal();
        ConversionResult result;
        try {
            result = provider.execute(new ConversionRequest(proposal.id().toString(), proposal.userId(),
                    proposal.quote()));
        } catch (RuntimeException transientFailure) {
            // Unknown outcome: leave EXECUTING. recoverExecutions() retries with the same idempotency key.
            return staged;
        }
        Outcome settled = tx.inTransaction(() -> settle(proposal.id(), result));
        return new Outcome(settled.proposal(), staged.decision(), staged.approval(), settled.conversion());
    }

    private Outcome settle(UUID proposalId, ConversionResult result) {
        ExecutionProposal proposal = proposal(proposalId);
        if (proposal.status() != ExecutionStatus.EXECUTING) {
            return new Outcome(proposal, null, null,
                    repos.conversions().findByProposal(proposalId).orElse(null));
        }
        Instant now = clock.instant();
        FxGoal goal = ownedGoal(proposal.goalId(), proposal.userId());
        CurrencyAccount account = repos.accounts().findByUser(proposal.userId()).orElseThrow();

        if (!result.succeeded()) {
            account.credit(proposal.sourceAmount());
            repos.accounts().update(account);
            proposal.fail(result.failureReason(), now);
            repos.proposals().update(proposal);
            metrics.execution(false);
            repos.audit().append(audit.event(AuditEventType.CONVERSION_FAILED, proposal.userId(), "execution-engine")
                    .goalId(goal.id()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                    .quote(proposal.quote().id(), proposal.quote().rate().rate().toPlainString(), provider.name())
                    .requested(proposal.sourceAmount())
                    .summary("Conversion failed (" + result.failureReason() + "); "
                            + display(proposal.sourceAmount()) + " returned to your balance.")
                    .build());
            events.publish(new DomainEvent.ConversionFailed(UUID.randomUUID(), now, goal.id(), proposal.userId(),
                    proposal.id(), result.failureReason()));
            return new Outcome(proposal, null, null, null);
        }

        Money reserved = proposal.sourceAmount();
        Money debited = result.sourceDebited();
        if (reserved.isGreaterThan(debited)) {
            account.credit(reserved.minus(debited));
        }
        account.credit(result.targetCredited());
        repos.accounts().update(account);

        FxQuote quote = proposal.quote();
        FxConversion conversion = new FxConversion(UUID.randomUUID(), proposal.id(), goal.id(), proposal.userId(),
                proposal.mandateRef(), quote.id(), provider.name(), result.providerReference(), quote.pair(),
                quote.rate(), debited, result.fee(), result.targetCredited(), result.settledAt());
        repos.conversions().insert(conversion);
        proposal.complete(now);
        repos.proposals().update(proposal);
        goal.recordConversion(debited, result.targetCredited(), now);
        repos.goals().update(goal);
        metrics.execution(true);

        repos.audit().append(audit.event(AuditEventType.CONVERSION_COMPLETED, proposal.userId(), "execution-engine")
                .goalId(goal.id()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                .quote(quote.id(), quote.rate().rate().toPlainString(), provider.name())
                .requested(debited).executed(result.targetCredited())
                .summary("Converted " + display(debited) + " → " + display(result.targetCredited()) + ". Progress: "
                        + display(goal.acquired()) + " of " + display(goal.targetAmount()) + ".")
                .details(Map.of("providerReference", result.providerReference(), "fee", result.fee().toString()))
                .build());
        events.publish(new DomainEvent.ConversionCompleted(UUID.randomUUID(), now, goal.id(), proposal.userId(),
                proposal.id(), conversion.id(), debited.amount(), debited.currency().name(),
                result.targetCredited().amount(), result.targetCredited().currency().name()));

        if (goal.status() == GoalStatus.COMPLETED) {
            AgentMandate mandate = repos.mandates().find(proposal.mandateRef()).orElseThrow();
            if (mandate.isActive()) {
                mandate.revoke(now);
                repos.mandates().update(mandate);
            }
            metrics.goalFinished(true);
            repos.agentDecisions().insert(AgentDecision.of(goal.id(), goal.userId(), AgentDecisionType.GOAL_COMPLETED,
                    ProposalOrigin.STRATEGY_ENGINE, "Goal completed: " + display(goal.acquired()) + " acquired for "
                            + display(goal.spent()) + ".", null, null, now));
            repos.audit().append(audit.event(AuditEventType.GOAL_COMPLETED, goal.userId(), "system")
                    .goalId(goal.id()).mandate(mandate.ref())
                    .summary("Goal completed: " + display(goal.acquired()) + " acquired for " + display(goal.spent())
                            + ". The mandate was revoked.")
                    .build());
            events.publish(new DomainEvent.GoalCompleted(UUID.randomUUID(), now, goal.id(), goal.userId()));
        }
        return new Outcome(proposal, null, null, conversion);
    }

    private void revalidateQuote(ExecutionProposal proposal) {
        FxQuote previous = proposal.quote();
        FxQuote fresh = provider.getQuote(previous.pair().source(), previous.pair().target(),
                previous.sourceAmount().amount());
        repos.quotes().insert(fresh, proposal.userId());
        proposal.requote(fresh, clock.instant());
        repos.audit().append(audit.event(AuditEventType.QUOTE_REVALIDATED, proposal.userId(), "execution-engine")
                .goalId(proposal.goalId()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                .quote(fresh.id(), fresh.rate().rate().toPlainString(), fresh.provider())
                .requested(fresh.sourceAmount())
                .summary("Quote revalidated: rate " + previous.rate().rate().stripTrailingZeros().toPlainString()
                        + " → " + fresh.rate().rate().stripTrailingZeros().toPlainString() + ", you receive "
                        + display(fresh.targetAmount()) + ".")
                .details(Map.of("previousQuoteId", previous.id().toString()))
                .build());
    }

    private Outcome expire(ApprovalRequest approval, ExecutionProposal proposal) {
        Instant now = clock.instant();
        approval.expire(now);
        repos.approvals().update(approval);
        proposal.reject(RejectionSource.APPROVAL_EXPIRED, "Approval expired", now);
        repos.proposals().update(proposal);
        repos.audit().append(audit.event(AuditEventType.APPROVAL_EXPIRED, proposal.userId(), "system")
                .goalId(proposal.goalId()).mandate(proposal.mandateRef()).proposalId(proposal.id())
                .requested(proposal.sourceAmount())
                .summary("Approval expired; the proposal was discarded.")
                .build());
        return new Outcome(proposal, null, approval, null);
    }

    private Outcome replay(ExecutionProposal p) {
        PolicyDecision last = repos.policyEvaluations().findByProposal(p.id()).stream()
                .reduce((a, b) -> b).orElse(null);
        ApprovalRequest approval = repos.approvals().findPendingByProposal(p.id()).orElse(null);
        return new Outcome(p, last, approval, repos.conversions().findByProposal(p.id()).orElse(null));
    }

    private Explanation explain(ProposeCommand cmd, FxQuote quote, FxGoal goal, AgentMandate mandate) {
        Money remainingAfter = quote.pair().target() == goal.pair().target()
                ? goal.remainingTarget().minus(quote.targetAmount()).nonNegative()
                : goal.remainingTarget();
        String what = Optional.ofNullable(UntrustedText.clean(cmd.whatHappened(), 500))
                .orElse("A conversion was requested for goal \"" + goal.name() + "\".");
        String why = Optional.ofNullable(UntrustedText.clean(cmd.why(), 500))
                .orElse("It moves the goal forward within the mandate.");
        return new Explanation(what, why, quote.sourceAmount(), quote.targetAmount(), quote.fee(), quote.rate(),
                remainingAfter, describeRule(mandate));
    }

    static String describeRule(AgentMandate mandate) {
        MandatePermissions p = mandate.permissions();
        return "Mandate v" + mandate.version() + " allows "
                + (p.allowPartialExecution() ? "partial conversions" : "a single conversion")
                + " up to " + display(p.maxTransactionAmount()) + " each and " + display(p.maxTotalSpend())
                + " in total" + p.threshold().map(t -> ", with your approval above " + display(t)).orElse("") + ".";
    }

    private FxGoal ownedGoal(UUID goalId, UUID userId) {
        return repos.goals().find(goalId)
                .filter(g -> g.userId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Goal", goalId));
    }

    private ApprovalRequest ownedApproval(UUID approvalId, UUID actor) {
        return repos.approvals().find(approvalId)
                .filter(a -> a.userId().equals(actor))
                .orElseThrow(() -> new NotFoundException("Approval", approvalId));
    }

    private ExecutionProposal proposal(UUID id) {
        return repos.proposals().find(id).orElseThrow(() -> new NotFoundException("Proposal", id));
    }

    private static String codes(PolicyDecision decision) {
        return decision.reasonCodes().stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String actorOf(ProposalOrigin origin) {
        return origin == ProposalOrigin.LLM_AGENT ? "agent" : "strategy-engine";
    }
}
