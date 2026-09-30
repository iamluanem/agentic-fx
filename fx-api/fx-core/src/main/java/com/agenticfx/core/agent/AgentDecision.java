package com.agenticfx.core.agent;

import com.agenticfx.core.execution.ProposalOrigin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** What the agent concluded at a point in time, and why. Advisory only: carries no authority. */
public record AgentDecision(
        UUID id,
        UUID goalId,
        UUID userId,
        AgentDecisionType type,
        ProposalOrigin source,
        String summary,
        Explanation explanation,
        UUID proposalId,
        Instant decidedAt) {

    public AgentDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public static AgentDecision of(UUID goalId, UUID userId, AgentDecisionType type, ProposalOrigin source,
                                   String summary, Explanation explanation, UUID proposalId, Instant now) {
        return new AgentDecision(UUID.randomUUID(), goalId, userId, type, source, summary, explanation,
                proposalId, now);
    }
}
