package com.agenticfx.core.execution;

import com.agenticfx.core.agent.Explanation;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.ExchangeRate;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.shared.DomainException;
import com.agenticfx.core.shared.InvalidStateTransitionException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A request to convert money, created by the Strategy Engine or the LLM agent. A proposal has no authority:
 * it only becomes a conversion after the Policy Engine allows it (and a human approves, when required).
 */
public class ExecutionProposal {

    private final UUID id;
    private final UUID goalId;
    private final UUID userId;
    private final MandateRef mandateRef;
    private FxQuote quote;
    private ExecutionStatus status;
    private final ProposalOrigin origin;
    private final Explanation explanation;
    private final String idempotencyKey;
    private ExchangeRate approvedRate;
    private RejectionSource rejectionSource;
    private String statusReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public ExecutionProposal(UUID id, UUID goalId, UUID userId, MandateRef mandateRef, FxQuote quote,
                             ExecutionStatus status, ProposalOrigin origin, Explanation explanation,
                             String idempotencyKey, ExchangeRate approvedRate, RejectionSource rejectionSource,
                             String statusReason, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.mandateRef = Objects.requireNonNull(mandateRef, "mandateRef");
        this.quote = Objects.requireNonNull(quote, "quote");
        this.status = Objects.requireNonNull(status, "status");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.approvedRate = approvedRate;
        this.rejectionSource = rejectionSource;
        this.statusReason = statusReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static ExecutionProposal propose(UUID goalId, UUID userId, MandateRef mandateRef, FxQuote quote,
                                            ProposalOrigin origin, Explanation explanation,
                                            String idempotencyKey, Instant now) {
        return new ExecutionProposal(UUID.randomUUID(), goalId, userId, mandateRef, quote, ExecutionStatus.PROPOSED,
                origin, explanation, idempotencyKey, null, null, null, now, now, 0);
    }

    public void awaitApproval(String reason, Instant now) {
        transition(ExecutionStatus.AWAITING_APPROVAL, now);
        approvedRate = null;
        statusReason = reason;
    }

    /** Records the human approval, pinning the rate the human saw so later slippage can be detected. */
    public void approve(Instant now) {
        transition(ExecutionStatus.APPROVED, now);
        approvedRate = quote.rate();
        statusReason = null;
    }

    public void reject(RejectionSource source, String reason, Instant now) {
        transition(ExecutionStatus.REJECTED, now);
        rejectionSource = Objects.requireNonNull(source, "source");
        statusReason = reason;
    }

    /** Replaces the quote with a fresh one for the same pair and amount (quote revalidation). */
    public void requote(FxQuote fresh, Instant now) {
        if (status != ExecutionStatus.PROPOSED && status != ExecutionStatus.APPROVED) {
            throw new DomainException("REQUOTE_NOT_ALLOWED", "Cannot requote a proposal in status " + status);
        }
        if (!fresh.pair().equals(quote.pair()) || !fresh.sourceAmount().equals(quote.sourceAmount())) {
            throw new DomainException("REQUOTE_MISMATCH", "A requote must keep the pair and source amount");
        }
        quote = fresh;
        updatedAt = now;
    }

    public void startExecution(Instant now) {
        transition(ExecutionStatus.EXECUTING, now);
    }

    public void complete(Instant now) {
        transition(ExecutionStatus.COMPLETED, now);
    }

    public void fail(String reason, Instant now) {
        transition(ExecutionStatus.FAILED, now);
        statusReason = reason;
    }

    public Money sourceAmount() {
        return quote.sourceAmount();
    }

    public boolean isApproved() {
        return status == ExecutionStatus.APPROVED && approvedRate != null;
    }

    private void transition(ExecutionStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException("ExecutionProposal", status, next);
        }
        status = next;
        updatedAt = now;
    }

    /** Called by the persistence adapter after a successful optimistic-locked update. */
    public void versionIncremented() {
        version++;
    }

    public UUID id() { return id; }
    public UUID goalId() { return goalId; }
    public UUID userId() { return userId; }
    public MandateRef mandateRef() { return mandateRef; }
    public FxQuote quote() { return quote; }
    public ExecutionStatus status() { return status; }
    public ProposalOrigin origin() { return origin; }
    public Explanation explanation() { return explanation; }
    public String idempotencyKey() { return idempotencyKey; }
    public Optional<ExchangeRate> approvedRate() { return Optional.ofNullable(approvedRate); }
    public Optional<RejectionSource> rejectionSource() { return Optional.ofNullable(rejectionSource); }
    public Optional<String> statusReason() { return Optional.ofNullable(statusReason); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
