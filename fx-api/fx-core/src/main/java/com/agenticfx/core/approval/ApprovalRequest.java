package com.agenticfx.core.approval;

import com.agenticfx.core.shared.DomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A human decision the agent is waiting on. Only the goal owner can decide; decisions are final. */
public class ApprovalRequest {

    private final UUID id;
    private final UUID proposalId;
    private final UUID goalId;
    private final UUID userId;
    private ApprovalStatus status;
    private final Instant requestedAt;
    private final Instant expiresAt;
    private Instant decidedAt;
    private UUID decidedBy;
    private String note;
    private long version;

    public ApprovalRequest(UUID id, UUID proposalId, UUID goalId, UUID userId, ApprovalStatus status,
                           Instant requestedAt, Instant expiresAt, Instant decidedAt, UUID decidedBy,
                           String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId");
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = Objects.requireNonNull(status, "status");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy;
        this.note = note;
        this.version = version;
    }

    public static ApprovalRequest open(UUID proposalId, UUID goalId, UUID userId, Instant now, Instant expiresAt) {
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Approval must expire in the future");
        }
        return new ApprovalRequest(UUID.randomUUID(), proposalId, goalId, userId, ApprovalStatus.PENDING,
                now, expiresAt, null, null, null, 0);
    }

    public void approve(UUID actor, Instant now) {
        decide(ApprovalStatus.APPROVED, actor, null, now);
    }

    public void reject(UUID actor, String reason, Instant now) {
        decide(ApprovalStatus.REJECTED, actor, reason, now);
    }

    public void expire(Instant now) {
        requirePending();
        status = ApprovalStatus.EXPIRED;
        decidedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private void decide(ApprovalStatus outcome, UUID actor, String reason, Instant now) {
        requirePending();
        Objects.requireNonNull(actor, "actor");
        if (!actor.equals(userId)) {
            throw new DomainException("APPROVAL_FORBIDDEN", "Only the goal owner can decide this approval");
        }
        if (isExpiredAt(now)) {
            throw new DomainException("APPROVAL_EXPIRED", "Approval request expired at " + expiresAt);
        }
        status = outcome;
        decidedAt = now;
        decidedBy = actor;
        note = reason;
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new DomainException("APPROVAL_ALREADY_DECIDED", "Approval is already " + status);
        }
    }

    /** Called by the persistence adapter after a successful optimistic-locked update. */
    public void versionIncremented() {
        version++;
    }

    public UUID id() { return id; }
    public UUID proposalId() { return proposalId; }
    public UUID goalId() { return goalId; }
    public UUID userId() { return userId; }
    public ApprovalStatus status() { return status; }
    public Instant requestedAt() { return requestedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant decidedAt() { return decidedAt; }
    public UUID decidedBy() { return decidedBy; }
    public String note() { return note; }
    public long version() { return version; }
}
