package com.agenticfx.app.events;

import com.agenticfx.app.persistence.JsonCodec;
import com.agenticfx.core.application.StrategyRunner;
import com.agenticfx.core.event.DomainEvent;
import com.agenticfx.core.market.RateObservation;
import com.agenticfx.core.money.CurrencyPair;
import com.agenticfx.core.money.ExchangeRate;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumer side. Idempotent twice over: an event id already recorded in {@code processed_events} is skipped,
 * and every handler is itself idempotent (strategy proposals use {@code strategy:<goal>:<sequence>} keys),
 * so a redelivery racing the first delivery cannot cause a second execution.
 */
@Component
public class EventDispatcher {

    static final String CONSUMER = "fx-api";
    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final JsonCodec json;
    private final JdbcClient jdbc;
    private final StrategyRunner strategy;

    public EventDispatcher(JsonCodec json, JdbcClient jdbc, StrategyRunner strategy) {
        this.json = json;
        this.jdbc = jdbc;
        this.strategy = strategy;
    }

    public void dispatch(String envelopeJson) {
        JsonNode envelope = json.tree(envelopeJson);
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        String type = envelope.path("type").asText();
        Class<?> javaType = EventEnvelope.TYPES.get(type);
        if (javaType == null) {
            log.warn("Ignoring unknown event type {}", type);
            return;
        }
        if (alreadyProcessed(eventId)) {
            return;
        }
        String correlationId = envelope.path("correlationId").asText(null);
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            handle((DomainEvent) json.convert(envelope.path("payload"), javaType));
            markProcessed(eventId);
        } catch (RuntimeException e) {
            log.error("Handler failed for {} {}: {}", type, eventId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void handle(DomainEvent event) {
        switch (event) {
            case DomainEvent.FxRateUpdated e -> {
                CurrencyPair pair = CurrencyPair.parse(e.pair());
                strategy.onRate(new RateObservation(new ExchangeRate(pair, e.rate()),
                        new ExchangeRate(pair, e.reference()), e.occurredAt(), e.sequence()));
            }
            case DomainEvent.ApprovalRequested e -> log.info(
                    "Notify user {}: approval {} needed for proposal {} (expires {})",
                    e.userId(), e.approvalId(), e.proposalId(), e.expiresAt());
            case DomainEvent.ConversionCompleted e -> log.info(
                    "Notify user {}: converted {} {} into {} {}", e.userId(), e.sourceAmount(), e.sourceCurrency(),
                    e.targetAmount(), e.targetCurrency());
            case DomainEvent.GoalCompleted e -> log.info("Notify user {}: goal {} completed", e.userId(), e.goalId());
            default -> {
                // Other events are published for downstream consumers (analytics, notifications).
            }
        }
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer = :c AND event_id = :id")
                .param("c", CONSUMER).param("id", eventId).query(Long.class).single() > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("INSERT INTO processed_events (consumer, event_id) VALUES (:c, :id) ON CONFLICT DO NOTHING")
                .param("c", CONSUMER).param("id", eventId).update();
    }
}
