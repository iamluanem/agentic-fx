package com.agenticfx.core.policy;

/** Stable, machine-readable reasons. Clients and the agent should branch on these, never on explanation text. */
public enum ReasonCode {
    // --- DENY: hard limits, cannot be overridden by approval ---
    MANDATE_NOT_ACTIVE(Effect.DENY),
    MANDATE_REFERENCE_MISMATCH(Effect.DENY),
    MANDATE_EXPIRED(Effect.DENY),
    GOAL_NOT_ACTIVE(Effect.DENY),
    DEADLINE_PASSED(Effect.DENY),
    PROPOSAL_NOT_EVALUABLE(Effect.DENY),
    SOURCE_CURRENCY_NOT_ALLOWED(Effect.DENY),
    TARGET_CURRENCY_NOT_ALLOWED(Effect.DENY),
    QUOTE_EXPIRED(Effect.DENY),
    INSUFFICIENT_BALANCE(Effect.DENY),
    MAX_TRANSACTION_AMOUNT_EXCEEDED(Effect.DENY),
    MAX_TOTAL_SPEND_EXCEEDED(Effect.DENY),
    GOAL_BUDGET_EXCEEDED(Effect.DENY),
    GOAL_ALREADY_REACHED(Effect.DENY),
    EXCEEDS_REMAINING_TARGET(Effect.DENY),
    PARTIAL_EXECUTION_NOT_ALLOWED(Effect.DENY),

    // --- REQUIRE_APPROVAL: allowed only with a human decision ---
    TRANSACTION_ABOVE_APPROVAL_THRESHOLD(Effect.REQUIRE_APPROVAL),
    HUMAN_IN_THE_LOOP_REQUIRES_APPROVAL(Effect.REQUIRE_APPROVAL),
    RATE_MOVED_SINCE_APPROVAL(Effect.REQUIRE_APPROVAL),

    // --- ALLOW justifications: which mandate rule permits the operation ---
    WITHIN_MANDATE_LIMITS(Effect.PERMIT),
    WITHIN_APPROVAL_THRESHOLD(Effect.PERMIT),
    AUTONOMOUS_EXECUTION_PERMITTED(Effect.PERMIT),
    HUMAN_APPROVAL_GRANTED(Effect.PERMIT);

    public enum Effect { DENY, REQUIRE_APPROVAL, PERMIT }

    private final Effect effect;

    ReasonCode(Effect effect) {
        this.effect = effect;
    }

    public Effect effect() {
        return effect;
    }
}
