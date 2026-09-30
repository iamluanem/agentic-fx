package com.agenticfx.core.event;

import com.agenticfx.core.policy.Decision;
import com.agenticfx.core.policy.ReasonCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain events. Written to the outbox in the same transaction as the state change, then relayed to the broker.
 * Consumers must be idempotent on {@link #eventId()}. Amounts are plain decimals plus ISO codes for portability.
 */
public sealed interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    /** Partition key: the goal (or pair code for market events), so events of one goal stay ordered. */
    String key();

    default String type() {
        return getClass().getSimpleName();
    }

    record FxRateUpdated(UUID eventId, Instant occurredAt, String pair, BigDecimal rate, BigDecimal reference,
                         long sequence) implements DomainEvent {
        public String key() { return pair; }
    }

    record GoalCreated(UUID eventId, Instant occurredAt, UUID goalId, UUID userId) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record GoalActivated(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID mandateId,
                         int mandateVersion) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record MarketConditionMatched(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, String pair,
                                  BigDecimal rate, BigDecimal improvementBps) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ExecutionProposed(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                             String origin, BigDecimal sourceAmount, String sourceCurrency) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record PolicyEvaluated(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                           Decision decision, List<ReasonCode> reasonCodes) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ApprovalRequested(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                             UUID approvalId, Instant expiresAt) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ApprovalGranted(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                           UUID approvalId) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ApprovalRejected(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                            UUID approvalId, String reason) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ConversionStarted(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId)
            implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ConversionCompleted(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                               UUID conversionId, BigDecimal sourceAmount, String sourceCurrency,
                               BigDecimal targetAmount, String targetCurrency) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record ConversionFailed(UUID eventId, Instant occurredAt, UUID goalId, UUID userId, UUID proposalId,
                            String reason) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }

    record GoalCompleted(UUID eventId, Instant occurredAt, UUID goalId, UUID userId) implements DomainEvent {
        public String key() { return goalId.toString(); }
    }
}
