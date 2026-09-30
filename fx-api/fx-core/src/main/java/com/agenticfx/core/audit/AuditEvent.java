package com.agenticfx.core.audit;

import com.agenticfx.core.mandate.MandateRef;
import com.agenticfx.core.money.Money;
import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.policy.ReasonCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, append-only record of something that happened. Persisted to a table that rejects UPDATE/DELETE.
 * Optional fields are {@code null} when not applicable to the event type.
 *
 * @param actor who caused it: {@code user:<id>}, {@code agent}, {@code strategy-engine} or {@code system}
 */
public record AuditEvent(
        UUID id,
        Instant timestamp,
        AuditEventType type,
        String actor,
        UUID userId,
        UUID goalId,
        MandateRef mandateRef,
        UUID proposalId,
        String agentDecision,
        Decision policyDecision,
        List<ReasonCode> reasonCodes,
        UUID quoteId,
        String quoteRate,
        String provider,
        Money requestedAmount,
        Money executedAmount,
        String summary,
        String correlationId,
        Map<String, String> details) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(summary, "summary");
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Builder builder(AuditEventType type, UUID userId, Instant timestamp) {
        return new Builder(type, userId, timestamp);
    }

    public static final class Builder {
        private final AuditEventType type;
        private final UUID userId;
        private final Instant timestamp;
        private String actor = "system";
        private UUID goalId;
        private MandateRef mandateRef;
        private UUID proposalId;
        private String agentDecision;
        private Decision policyDecision;
        private List<ReasonCode> reasonCodes;
        private UUID quoteId;
        private String quoteRate;
        private String provider;
        private Money requestedAmount;
        private Money executedAmount;
        private String summary;
        private String correlationId;
        private Map<String, String> details;

        private Builder(AuditEventType type, UUID userId, Instant timestamp) {
            this.type = type;
            this.userId = userId;
            this.timestamp = timestamp;
        }

        public Builder actor(String actor) { this.actor = actor; return this; }
        public Builder goalId(UUID goalId) { this.goalId = goalId; return this; }
        public Builder mandate(MandateRef ref) { this.mandateRef = ref; return this; }
        public Builder proposalId(UUID proposalId) { this.proposalId = proposalId; return this; }
        public Builder agentDecision(String agentDecision) { this.agentDecision = agentDecision; return this; }
        public Builder policy(Decision decision, List<ReasonCode> codes) {
            this.policyDecision = decision;
            this.reasonCodes = codes;
            return this;
        }
        public Builder quote(UUID quoteId, String rate, String provider) {
            this.quoteId = quoteId;
            this.quoteRate = rate;
            this.provider = provider;
            return this;
        }
        public Builder requested(Money amount) { this.requestedAmount = amount; return this; }
        public Builder executed(Money amount) { this.executedAmount = amount; return this; }
        public Builder summary(String summary) { this.summary = summary; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder details(Map<String, String> details) { this.details = details; return this; }

        public AuditEvent build() {
            return new AuditEvent(UUID.randomUUID(), timestamp, type, actor, userId, goalId, mandateRef, proposalId,
                    agentDecision, policyDecision, reasonCodes, quoteId, quoteRate, provider, requestedAmount,
                    executedAmount, summary, correlationId, details);
        }
    }
}
