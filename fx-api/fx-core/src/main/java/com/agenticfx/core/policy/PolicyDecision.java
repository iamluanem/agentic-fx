package com.agenticfx.core.policy;

import com.agenticfx.core.mandate.MandateRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of a policy evaluation. {@code reasonCodes} holds the codes that determined the decision
 * (deny codes for DENY, approval codes for REQUIRE_APPROVAL, permit codes for ALLOW); {@code findings}
 * holds every rule outcome for the audit trail.
 */
public record PolicyDecision(
        Decision decision,
        List<ReasonCode> reasonCodes,
        String explanation,
        List<Finding> findings,
        String policyVersion,
        UUID proposalId,
        MandateRef mandateRef,
        Instant evaluatedAt) {

    public PolicyDecision {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(mandateRef, "mandateRef");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        reasonCodes = List.copyOf(reasonCodes);
        findings = List.copyOf(findings);
    }

    public boolean isAllowed() {
        return decision == Decision.ALLOW;
    }
}
