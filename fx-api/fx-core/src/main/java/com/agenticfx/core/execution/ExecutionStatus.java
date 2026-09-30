package com.agenticfx.core.execution;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum ExecutionStatus {
    PROPOSED,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    EXECUTING,
    COMPLETED,
    FAILED;

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> ALLOWED = Map.of(
            PROPOSED, EnumSet.of(AWAITING_APPROVAL, EXECUTING, REJECTED),
            AWAITING_APPROVAL, EnumSet.of(APPROVED, REJECTED),
            // After approval the quote is revalidated: it may execute, need re-approval (rate moved) or be denied.
            APPROVED, EnumSet.of(EXECUTING, AWAITING_APPROVAL, REJECTED),
            EXECUTING, EnumSet.of(COMPLETED, FAILED),
            REJECTED, EnumSet.noneOf(ExecutionStatus.class),
            COMPLETED, EnumSet.noneOf(ExecutionStatus.class),
            FAILED, EnumSet.noneOf(ExecutionStatus.class));

    public boolean canTransitionTo(ExecutionStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Money is committed (reserved against limits) while a proposal is in one of these states. */
    public boolean isInFlight() {
        return this == AWAITING_APPROVAL || this == APPROVED || this == EXECUTING;
    }
}
