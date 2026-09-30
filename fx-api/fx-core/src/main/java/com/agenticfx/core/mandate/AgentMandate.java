package com.agenticfx.core.mandate;

import com.agenticfx.core.shared.InvalidStateTransitionException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable version of what the agent is authorised to do for a goal. Permissions never change in place:
 * {@link #amend} creates version N+1 as a DRAFT, and activating it supersedes the previous version.
 * Only status and lifecycle timestamps are mutable.
 */
public class AgentMandate {

    private final UUID id;
    private final int version;
    private final UUID goalId;
    private final UUID userId;
    private final MandatePermissions permissions;
    private final ExecutionMode executionMode;
    private final LocalDate validUntil;
    private MandateStatus status;
    private final Instant createdAt;
    private Instant activatedAt;
    private Instant endedAt;
    private long rowVersion;

    public AgentMandate(UUID id, int version, UUID goalId, UUID userId, MandatePermissions permissions,
                        ExecutionMode executionMode, LocalDate validUntil, MandateStatus status,
                        Instant createdAt, Instant activatedAt, Instant endedAt, long rowVersion) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = version;
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.activatedAt = activatedAt;
        this.endedAt = endedAt;
        this.rowVersion = rowVersion;
        if (version < 1) {
            throw new IllegalArgumentException("Mandate version starts at 1");
        }
    }

    public static AgentMandate draft(UUID goalId, UUID userId, MandatePermissions permissions,
                                     ExecutionMode mode, LocalDate validUntil, Instant now) {
        return new AgentMandate(UUID.randomUUID(), 1, goalId, userId, permissions, mode, validUntil,
                MandateStatus.DRAFT, now, null, null, 0);
    }

    /** New DRAFT version with different terms. The current version stays in force until the new one is activated. */
    public AgentMandate amend(MandatePermissions newPermissions, ExecutionMode newMode, LocalDate newValidUntil,
                              Instant now) {
        if (status == MandateStatus.SUPERSEDED || status == MandateStatus.REVOKED) {
            throw new InvalidStateTransitionException("AgentMandate", status, MandateStatus.DRAFT);
        }
        return new AgentMandate(id, version + 1, goalId, userId, newPermissions, newMode, newValidUntil,
                MandateStatus.DRAFT, now, null, null, 0);
    }

    /** Requires explicit user action; never invoked by the agent. */
    public void activate(Instant now) {
        transition(MandateStatus.ACTIVE);
        activatedAt = now;
    }

    public void suspend() {
        transition(MandateStatus.SUSPENDED);
    }

    public void resume() {
        if (status != MandateStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("AgentMandate", status, MandateStatus.ACTIVE);
        }
        transition(MandateStatus.ACTIVE);
    }

    public void supersede(Instant now) {
        transition(MandateStatus.SUPERSEDED);
        endedAt = now;
    }

    public void revoke(Instant now) {
        transition(MandateStatus.REVOKED);
        endedAt = now;
    }

    public boolean isActive() {
        return status == MandateStatus.ACTIVE;
    }

    public boolean isExpiredOn(LocalDate today) {
        return today.isAfter(validUntil);
    }

    public MandateRef ref() {
        return new MandateRef(id, version);
    }

    private void transition(MandateStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException("AgentMandate", status, next);
        }
        status = next;
    }

    /** Called by the persistence adapter after a successful optimistic-locked update. */
    public void versionIncremented() {
        rowVersion++;
    }

    public long rowVersion() { return rowVersion; }
    public UUID id() { return id; }
    public int version() { return version; }
    public UUID goalId() { return goalId; }
    public UUID userId() { return userId; }
    public MandatePermissions permissions() { return permissions; }
    public ExecutionMode executionMode() { return executionMode; }
    public LocalDate validUntil() { return validUntil; }
    public MandateStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant activatedAt() { return activatedAt; }
    public Instant endedAt() { return endedAt; }
}
